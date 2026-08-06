package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.dto.UpdateResponseRequest;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.ResponseRepository.ResponseRow;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.codeskeptic.scanner.util.LogSafe;
import com.codeskeptic.scanner.util.QueryParameters;

// Net-new (no Python module existed; signatures dictated by
// backend/app/api/responses.py:L15,L26,L44,L60) — see docs/DECISION_LOG.md
/**
 * Reads, generates and updates the rows of the {@code responses} table.
 *
 * <p>Four operations are exposed, one per route of the retired Flask blueprint:
 * {@link #getPaginatedResponses(int, int)} for {@code GET /responses},
 * {@link #getResponseById(String)} for {@code GET /responses/{responseId}},
 * {@link #generateResponse(String)} for {@code POST /responses} and
 * {@link #updateResponse(String, UpdateResponseRequest)} for {@code PUT /responses/{responseId}}.
 * Each signature is fixed by the call site the blueprint already declared, at
 * {@code backend/app/api/responses.py:L15}, {@code :L26}, {@code :L44} and {@code :L60}; the module
 * those call sites imported at {@code :L3} defined none of them.
 *
 * <p>The source constructed the service once per request, at
 * {@code backend/app/api/responses.py:L14}, {@code :L25}, {@code :L43} and {@code :L59}. This is one
 * singleton bean holding its six collaborators in final fields — DL-211.
 *
 * <p>The set of client-visible messages this class can produce is closed at five, each a wire literal
 * of the source:
 *
 * <ul>
 *   <li>{@code Tweet ID is required} — {@code backend/app/api/responses.py:L41}, carried by
 *       {@link BadRequestException#tweetIdRequired()}, rendered as 400.
 *   <li>{@code Response not found} — {@code :L31}, carried by
 *       {@link NotFoundException#responseNotFound()}, rendered as 404.
 *   <li>{@code Failed to generate response} — {@code :L49}, carried by
 *       {@link ResponseGenerationException}, rendered as 500.
 *   <li>{@code Update data is required} — {@code :L57}, carried by
 *       {@link BadRequestException#updateDataRequired()}, rendered as 400.
 *   <li>{@code Response not found or update failed} — {@code :L65}, carried by
 *       {@link NotFoundException#responseNotFoundOrUpdateFailed()}, rendered as 404.
 * </ul>
 *
 * <p>Each literal is held as a constant on its exception type and reached through that type's factory;
 * no identifier, driver text or stack detail is appended to any of them. This class selects no HTTP
 * status; {@code api.GlobalExceptionHandler} does — DL-076.
 *
 * <p>The two 404 messages are different strings: {@code GET /responses/{responseId}} carries the
 * literal of {@code :L31} and {@code PUT /responses/{responseId}} the literal of {@code :L65} —
 * DL-076.
 *
 * <p>{@code POST /responses} reports the two outcomes of {@code :L46-49}: a stored draft, or the
 * single 500 literal, which covers an identifier that parses to no number, an identifier naming no
 * {@code tweets} row, a generation failure and a persistence failure alike — DL-076.
 *
 * <p>Every entity is converted to its wire form inside the transaction that loaded it;
 * {@code spring.jpa.open-in-view} is {@code false}.
 *
 * <p>A generated reply is stored with {@code is_approved} {@code false}.
 * {@link #updateResponse(String, UpdateResponseRequest)} writes that flag on request and a human
 * reviewer reads it.
 *
 * <p>Rows are the only thing this class adds; it contributes no column, table, index or constraint.
 * The {@code tweets}-to-{@code responses} association declared at
 * {@code backend/app/db/models.py:L27-28,L30} is the only association it writes.
 *
 * <p>The {@code responses} and {@code tweets} tables are reached through {@link ResponseRepository}
 * and {@link TweetRepository} only.
 *
 * <p>Text generation is delegated to {@link LlmService}, which returns the generated text — DL-081.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md}; construct-level
 * provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean and its six collaborators are themselves singletons. Its only mutable
 * state is a concurrent set of integer tweet identifiers claimed by background generation in this
 * application instance. Database row locks coordinate the storage step across instances. Every member
 * declared here is safe for concurrent use — DL-195.
 */
@Service
public class ResponseService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(ResponseService.class);

    /** Difference between a 1-based wire page number and the 0-based repository index — DL-038. */
    /**
     * Page number read when {@code page} lies below it, transcribing the default of
     * {@code request.args.get('page', 1, type=int)} at backend/app/api/responses.py:L11.
     */
    private static final int DEFAULT_PAGE = 1;

    /**
     * Page size read when {@code perPage} lies below one, transcribing the default of
     * {@code request.args.get('per_page', 10, type=int)} at backend/app/api/responses.py:L12.
     */
    private static final int DEFAULT_PER_PAGE = 10;

    private static final int WIRE_PAGE_OFFSET = 1;

    /** Rows one page statement returns, however large {@code per_page} is — DL-249. */
    private static final int PAGE_FETCH_CHUNK_ROWS = 500;

    /**
     * Order of every page read: {@code responses.id} ascending, the total order consecutive chunks of
     * one page are positioned in — DL-249 — see docs/DECISION_LOG.md.
     */
    private static final Sort PAGE_ORDER = Sort.by(Sort.Direction.ASC, "id");

    /**
     * Bound on a transaction that takes a pessimistic row lock, in seconds. It matches the statement
     * bound {@link ResponseRepository#LOCK_WAIT_MILLIS} declares — see docs/DECISION_LOG.md DL-246.
     */
    private static final int LOCK_WAIT_SECONDS = 5;

    /** Data access for the {@code responses} table. */
    private final ResponseRepository responseRepository;

    /** Data access for the {@code tweets} table, read to resolve the association of a new row. */
    private final TweetRepository tweetRepository;

    /** Generates the reply text of a new {@code responses} row. */
    private final LlmService llmService;

    /** Converts a {@link Response} into its {@link ResponseDto} wire form. */
    private final ResponseMapper responseMapper;

    /** Converts a {@link Tweet} into the {@link TweetDto} the generator reads. */
    private final TweetMapper tweetMapper;

    /**
     * Demarcates the short transactional unit that stores a generated row — DL-086. Its timeout is
     * {@value #LOCK_WAIT_SECONDS} seconds, matching the statement bound of
     * {@link ResponseRepository#LOCK_WAIT_MILLIS} — DL-246. It is a bounded copy of the injected
     * template rather than the injected instance, so the shared bean keeps its own definition.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Integer identifiers of the {@code tweets} rows this instance is currently generating for. Raw
     * aliases such as {@code 7} and {@code 007} resolve to the same claim. An identifier is present
     * only while {@link #generateResponseIfAbsent(String)} runs for it — DL-195.
     */
    private final Set<Integer> claimed = ConcurrentHashMap.newKeySet();

    /**
     * Creates the bean with its collaborators, replacing the per-request construction at
     * {@code backend/app/api/responses.py:L14,L25,L43,L59}.
     *
     * @param responseRepository data access for the {@code responses} table, must not be {@code null}
     * @param tweetRepository    data access for the {@code tweets} table, must not be {@code null}
     * @param llmService         reply-text generator, must not be {@code null}
     * @param responseMapper     {@link Response}-to-wire converter, must not be {@code null}
     * @param tweetMapper        {@link Tweet}-to-wire converter, must not be {@code null}
     * @param transactionTemplate demarcates the unit that stores a generated row, must not be
     *                           {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public ResponseService(ResponseRepository responseRepository,
            TweetRepository tweetRepository,
            LlmService llmService,
            ResponseMapper responseMapper,
            TweetMapper tweetMapper,
            TransactionTemplate transactionTemplate) {
        this.responseRepository = Objects.requireNonNull(responseRepository,
                "responseRepository must not be null.");
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null.");
        this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper must not be null.");
        this.tweetMapper = Objects.requireNonNull(tweetMapper, "tweetMapper must not be null.");
        this.transactionTemplate = boundedCopyOf(Objects.requireNonNull(transactionTemplate,
                "transactionTemplate must not be null."));
    }

    // The storage transaction carries the same bound as the statement inside it — DL-246 — see
    // docs/DECISION_LOG.md
    /**
     * Copies {@code injected} onto the same transaction manager with a
     * {@value #LOCK_WAIT_SECONDS}-second timeout.
     *
     * <p>The copy carries every other attribute of {@code injected}, so a deployment that customises
     * the shared template's propagation or isolation keeps that customisation, and the shared bean
     * itself is left unmodified.
     *
     * @param injected the template supplied by the context, never {@code null}
     * @return a bounded copy on the same transaction manager; never {@code null}
     * @throws IllegalArgumentException when {@code injected} carries no transaction manager
     */
    private static TransactionTemplate boundedCopyOf(TransactionTemplate injected) {
        PlatformTransactionManager manager = injected.getTransactionManager();
        if (manager == null) {
            throw new IllegalArgumentException("transactionTemplate must carry a transaction manager.");
        }

        TransactionTemplate bounded = new TransactionTemplate(manager, injected);
        bounded.setTimeout(LOCK_WAIT_SECONDS);
        return bounded;
    }

    // Call site backend/app/api/responses.py:L15; envelope :L17-20; parameter defaults :L11-12 — see
    // docs/DECISION_LOG.md DL-038
    /**
     * Returns one page of {@code responses} rows together with the pagination block of the
     * {@code GET /responses} envelope.
     *
     * <p>{@code page} is 1-based, as the query parameter at {@code backend/app/api/responses.py:L11}
     * is, and is converted to the 0-based index {@code findAll(Pageable)} takes. A {@code page} below
     * {@value #DEFAULT_PAGE} is read as {@value #DEFAULT_PAGE} and a {@code perPage} below {@code 1}
     * is read as {@value #DEFAULT_PER_PAGE}; neither is rejected, and no upper bound is applied to
     * {@code perPage}. A {@code page} beyond the last one yields an empty {@code responses} list and
     * a populated pagination block.
     *
     * <p>A {@code page} whose first row lies beyond {@link Integer#MAX_VALUE} rows — that is, one for
     * which {@code (page - 1) * perPage} exceeds that bound — is answered the same way: the empty list
     * and the same populated block, with the requested page number and page size restated. No page
     * size is reduced and no request is rejected — see docs/DECISION_LOG.md DL-225.
     *
     * <p>The pagination block carries {@code page} as the 1-based number of the page returned,
     * {@code per_page} as its size, {@code total} as the number of rows in the table and
     * {@code total_pages} as the number of pages that size divides the table into — DL-038. The rows
     * are converted inside this method's transaction.
     *
     * <p>A {@code page} whose first row lies at an offset beyond {@link Integer#MAX_VALUE}, the
     * largest offset the paged query can express, is answered from a row count alone: an empty
     * {@code responses} list with the requested {@code page} and {@code per_page} restated and the
     * whole-table counters unchanged — see docs/DECISION_LOG.md DL-225. No pagination argument is
     * answered with an error status.
     *
     * <p>A page of at most {@value #PAGE_FETCH_CHUNK_ROWS} rows is read by one statement. A larger page
     * is read as consecutive chunks of that bound, each chunk converted before the next is read, so the
     * rows one statement returns are bounded however large {@code per_page} is — see
     * docs/DECISION_LOG.md DL-249. Rows are ordered by {@code responses.id} ascending. The rows the page
     * itself holds are bounded by the table, which is the wire contract this migration preserves — see
     * docs/DECISION_LOG.md DL-123, DL-200 and DL-249.
     *
     * @param page    the 1-based page number to return; a value below {@value #DEFAULT_PAGE} is read
     *                as {@value #DEFAULT_PAGE}
     * @param perPage the number of rows per page; a value below {@code 1} is read as
     *                {@value #DEFAULT_PER_PAGE} and no larger value is reduced
     * @return the {@code responses} and {@code pagination} envelope, never {@code null}; the
     *         {@code responses} list is empty when the page holds no row and is unmodifiable
     */
    @Transactional(readOnly = true)
    public PaginatedResponsesDto getPaginatedResponses(int page, int perPage) {
        // Out-of-range values are read as the defaults of backend/app/api/responses.py:L11-12 —
        // DL-123 — see docs/DECISION_LOG.md
        int requestedPage = (page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
        // No upper bound is applied; the source declared none — see docs/DECISION_LOG.md DL-123
        int requestedPerPage = (perPage < 1) ? DEFAULT_PER_PAGE : perPage;

        // The wire page of backend/app/api/responses.py:L11 is 1-based; PageRequest is 0-based —
        // DL-038 — see docs/DECISION_LOG.md
        PageRequest requested = PageRequest.of(
                Math.subtractExact(requestedPage, WIRE_PAGE_OFFSET), requestedPerPage, PAGE_ORDER);

        // The page read selects the five wire members only; no tweets column is read — DL-245 — see
        // docs/DECISION_LOG.md
        List<ResponseDto> responses;
        long total;
        if (!QueryParameters.withinQueryableOffset(requested)) {
            // A page whose first row lies past the offset the query can express is answered without a
            // paged query — DL-225 — see docs/DECISION_LOG.md
            responses = List.of();
            total = responseRepository.count();
        } else if (requestedPerPage <= PAGE_FETCH_CHUNK_ROWS) {
            Page<ResponseRow> found = responseRepository.findAllRows(requested);
            responses = responseMapper.toDtoRowList(found.getContent());
            total = found.getTotalElements();
        } else {
            // A page larger than the chunk bound is read as consecutive bounded chunks, each mapped
            // before the next is read — DL-249 — see docs/DECISION_LOG.md
            responses = QueryParameters.mapInChunks(requested, PAGE_FETCH_CHUNK_ROWS,
                    responseRepository::findRowChunk, responseMapper::toDtoRowList);
            total = responseRepository.count();
        }

        PaginationDto pagination = new PaginationDto(
                requestedPage,
                requestedPerPage,
                total,
                QueryParameters.totalPages(total, requestedPerPage));

        log.debug("Rendering {} response row(s) for page {} of {} at {} per page.",
                responses.size(), pagination.page(), pagination.totalPages(), pagination.perPage());

        // backend/app/api/responses.py:L17-20
        return new PaginatedResponsesDto(responses, pagination);
    }

    // Call site backend/app/api/responses.py:L26; 404 literal :L31 — see docs/DECISION_LOG.md DL-048
    /**
     * Returns the {@code responses} row identified by {@code responseId}.
     *
     * <p>{@code responseId} arrives as the raw path segment. An identifier that carries no number, and
     * a {@code null} identifier, are both reported as absent, matching the string path converter the
     * source route used at {@code backend/app/api/responses.py:L22}. An identifier that carries a
     * number naming no row is reported as absent as well. All three report the literal of {@code :L31}
     * — see docs/DECISION_LOG.md DL-048.
     *
     * <p>The row is converted inside this method's transaction, and its {@code tweet_id} is carried as
     * a {@link String}.
     *
     * @param responseId the raw path segment identifying the row; an unparseable and a {@code null}
     *                   value are both reported as absent
     * @return the stored row in its wire form, never {@code null}
     * @throws NotFoundException when {@code responseId} names no row, carrying the wire literal of
     *                           {@code backend/app/api/responses.py:L31}
     */
    @Transactional(readOnly = true)
    public ResponseDto getResponseById(String responseId) {
        Optional<Response> existing = findByIdentifier(responseId);

        // backend/app/api/responses.py:L28-31
        if (existing.isEmpty()) {
            log.warn("Rejected the read of response '{}': the identifier names no row.",
                    LogSafe.logSafe(responseId));
            throw NotFoundException.responseNotFound();
        }

        return responseMapper.toDto(existing.get());
    }

    // Contract from backend/app/api/responses.py:L38-49 (400/201/500) — see docs/DECISION_LOG.md
    /**
     * Generates a reply to the {@code tweets} row identified by {@code tweetId}, stores it as a new
     * {@code responses} row, and returns the stored row.
     *
     * <p>{@code tweetId} is rejected when it is {@code null} and when it is the empty string. A
     * whitespace-only value passes. {@code api/ResponseController} reads the request body through
     * {@code dto/CreateResponseRequest.usableTweetId()}, which reports {@code null} for every value the
     * {@code if not tweet_id} guard at {@code backend/app/api/responses.py:L40} read as false, so this
     * method answers that whole set with the literal of {@code :L41} — DL-240.
     *
     * <p>Past the guard this method reports the two outcomes of {@code :L46-49}: the stored row, or the
     * single literal of {@code :L49}, which covers a {@code tweetId} carrying no number, a
     * {@code tweetId} naming no {@code tweets} row, a failure raised by
     * {@link LlmService#generateResponse(TweetDto)} and a failure raised while storing the row — see
     * docs/DECISION_LOG.md DL-076.
     *
     * <p>The stored row carries the generated text as {@code content}, {@code is_approved}
     * {@code false}, {@code generated_at} as the current local time, and the loaded {@link Tweet} as
     * its association. Its {@code id} is assigned by the database on insert and is read back from the
     * stored row, replacing the {@code response.save()} call at
     * {@code backend/app/tasks/response_generation.py:L25-26}. The row is converted inside this
     * method's transaction.
     *
     * <p>No database transaction spans the generation request. Reading the subject row and storing the
     * generated row are separate units of work and the call to
     * {@link LlmService#generateResponse(TweetDto)} runs between them with no transaction open — see
     * docs/DECISION_LOG.md DL-086. The subject row is read again inside the storing unit, so a row
     * deleted while the model was answering is reported as the literal of {@code :L49}.
     *
     * @param tweetId the raw identifier of the {@code tweets} row to reply to; must be neither
     *                {@code null} nor empty
     * @return the stored row in its wire form, carrying the assigned {@code id}, never {@code null}
     * @throws BadRequestException          when {@code tweetId} is {@code null} or empty, carrying the
     *                                      wire literal of {@code backend/app/api/responses.py:L41}
     * @throws ResponseGenerationException  when {@code tweetId} carries no number, when it names no
     *                                      {@code tweets} row, or when generating or storing the row
     *                                      fails, carrying the wire literal of
     *                                      {@code backend/app/api/responses.py:L49}
     */
    public ResponseDto generateResponse(String tweetId) {
        // backend/app/api/responses.py:L40-41 — the guard tests null and the empty string
        if (tweetId == null || tweetId.isEmpty()) {
            log.warn("Rejected a generation request: the request carried no tweet identifier.");
            throw BadRequestException.tweetIdRequired();
        }

        log.info("Generating a response for tweet '{}'.", LogSafe.logSafe(tweetId));

        Integer identifier = parseIdentifier(tweetId);
        try {
            TweetDto subject = readSubject(identifier);
            String generatedText = generateText(subject);
            ResponseDto stored = store(identifier, generatedText, false);

            log.info("Stored response {} for tweet '{}' awaiting review.",
                    stored.id(), LogSafe.logSafe(tweetId));
            return stored;
        } catch (ResponseGenerationException e) {
            // Already recorded by the layer that raised it — DL-252 — see docs/DECISION_LOG.md
            throw e;
        } catch (RuntimeException e) {
            // The one ERROR record a failure with no adapter owner receives, written ahead of the
            // fixed translation. A provider failure arrives as a ResponseGenerationException the
            // clause above rethrows unrecorded, so exactly one ERROR exists per failure. The record
            // carries the failure's class only — never a statement, a SQL state or a message — see
            // docs/DECISION_LOG.md DL-052, DL-252
            log.error("Generating a response for tweet '{}' failed: {}; responding with the wire "
                    + "literal of backend/app/api/responses.py:L49.",
                    LogSafe.logSafe(tweetId), LogSafe.type(e));
            throw new ResponseGenerationException(e);
        }
    }

    // The single entry point of both background generation paths — DL-195 — see
    // docs/DECISION_LOG.md
    /**
     * Generates a reply for the {@code tweets} row identified by {@code tweetId}, stores it only when
     * that row still carries none, and reports what was stored.
     *
     * <p>This is the one operation {@code task.TweetStreamListener} and
     * {@code task.ResponseGenerationScheduler} call, so at most one automatic {@code responses} row
     * is stored per {@code tweets} row. Two protections combine:
     *
     * <ul>
     *   <li>an in-process claim on the parsed integer identifier, held for the whole generation,
     *       which a second concurrent caller in this instance cannot take even when the raw strings
     *       differ;</li>
     *   <li>a read of {@link ResponseRepository#existsByTweetId(Integer)} taken in its own short
     *       transaction <em>before</em> the language-model call, so a row that already carries a reply
     *       costs no generation — DL-252; and</li>
     *   <li>a pessimistic lock on the parent {@code tweets} row, acquired inside the short storage
     *       transaction before {@link ResponseRepository#existsByTweetId(Integer)} and the insert,
     *       which serialises the final check across application instances.</li>
     * </ul>
     *
     * <p>No database connection is held across the language-model call: the pre-call read, the call and
     * the storage transaction are three separate boundaries — DL-252. Both callers of this operation
     * run only in the process that {@code scanner.background.enabled} designates, so two processes do
     * not generate for one row concurrently — DL-250.
     *
     * <p>An empty result means nothing was stored: the row already carried a reply, or another caller
     * held the claim. Both outcomes are recorded at {@code DEBUG}.
     *
     * <p>{@code POST /responses} does not come through here: it calls
     * {@link #generateResponse(String)} directly, so its documented outcomes are unchanged — see
     * docs/DECISION_LOG.md DL-195.
     *
     * @param tweetId the raw identifier of the {@code tweets} row to reply to; must be neither
     *                {@code null} nor empty
     * @return the stored row in its wire form, or empty when nothing was stored
     * @throws BadRequestException         when {@code tweetId} is {@code null} or empty
     * @throws ResponseGenerationException when generating or storing the row fails
     */
    public Optional<ResponseDto> generateResponseIfAbsent(String tweetId) {
        if (tweetId == null || tweetId.isEmpty()) {
            log.warn("Rejected a background generation request: it carried no tweet identifier.");
            throw BadRequestException.tweetIdRequired();
        }

        Integer identifier = parseIdentifier(tweetId);
        if (identifier == null) {
            log.error("Response generation failed: the background request identifier carries no "
                    + "integer.");
            throw new ResponseGenerationException();
        }

        return generateWhenAbsent(identifier, null);
    }

    // The same operation for a caller that already holds the row, under a name of its own — DL-226 —
    // see docs/DECISION_LOG.md
    /**
     * Generates and stores a reply for a {@code tweets} row the caller already holds, unless that row
     * already carries one, and reports what was stored.
     *
     * <p>Named distinctly from {@link #generateResponseIfAbsent(String)} rather than overloading it, so
     * no call site can be ambiguous about which subject form it passes — DL-226.
     *
     * <p>Behaves exactly as {@link #generateResponseIfAbsent(String)} in every respect except one: the
     * subject row is read from the supplied entity and this method selects nothing from the
     * {@code tweets} table — see docs/DECISION_LOG.md DL-226. The in-process claim on the
     * row's identifier, the transaction-scoped
     * {@link ResponseRepository#existsByTweetId(Integer)} guard, the stored column values and every
     * client-visible message are the same ones {@link #generateResponseIfAbsent(String)} produces —
     * see docs/DECISION_LOG.md DL-195, DL-226.
     *
     * <p>Only the identifier and the columns {@code dto/TweetDto} carries are read from
     * {@code subject}; its {@code responses} association is never traversed. The entity may be
     * detached: no operation here reattaches it, and the row the new reply is attached to is the one
     * the storing transaction reads.
     *
     * @param subject the {@code tweets} row to reply to, carrying its assigned identifier; must not be
     *                {@code null}
     * @return the stored row in its wire form, or empty when nothing was stored
     * @throws NullPointerException        when {@code subject} is {@code null}
     * @throws BadRequestException         when {@code subject} carries no identifier, which means it
     *                                     has not been stored
     * @throws ResponseGenerationException when generating or storing the row fails
     */
    public Optional<ResponseDto> generateResponseIfAbsentFor(Tweet subject) {
        Objects.requireNonNull(subject, "subject must not be null.");

        Integer identifier = subject.getId();
        if (identifier == null) {
            log.warn("Rejected a background generation request: the supplied tweet carries no "
                    + "identifier.");
            throw BadRequestException.tweetIdRequired();
        }

        return generateWhenAbsent(identifier, subject);
    }

    /**
     * Generates and stores one reply for the named {@code tweets} row unless it already carries one.
     *
     * <p>The claim on {@code identifier} is taken first and released when this method returns or
     * raises. The stored state is then read: a row that already carries a reply returns empty before
     * any provider call is made — DL-252. The subject row is taken from {@code loaded} when the caller
     * supplied it and read from the {@code tweets} table otherwise.
     *
     * @param identifier the parsed identifier of the row to reply to, never {@code null}
     * @param loaded     the row the caller already holds, or {@code null} to read it here
     * @return the stored row in its wire form, or empty when nothing was stored
     * @throws ResponseGenerationException when the identifier names no row, or when generating or
     *                                     storing the row fails
     */
    private Optional<ResponseDto> generateWhenAbsent(Integer identifier, Tweet loaded) {
        if (!claimed.add(identifier)) {
            log.debug("Tweet '{}' is already being generated for; this pass stores nothing.",
                    identifier);
            return Optional.empty();
        }

        try {
            // The stored state is read before the provider call, in its own short transaction, so a
            // row another process already answered costs no generation — DL-252 — see
            // docs/DECISION_LOG.md
            if (responseRepository.existsByTweetId(identifier)) {
                log.debug("Tweet '{}' already carries a response; no generation was requested.",
                        identifier);
                return Optional.empty();
            }


            // The supplied row is the subject; only a caller that holds none reads one — DL-226 —
            // see docs/DECISION_LOG.md
            TweetDto subject = (loaded == null) ? readSubject(identifier) : tweetMapper.toDto(loaded);

            // Current preflight ahead of the paid provider call. Each read is its own short
            // transaction, so no connection is held across the provider call — DL-247, DL-252 — see
            // docs/DECISION_LOG.md
            if (!tweetRepository.existsById(identifier)
                    || responseRepository.existsByTweetId(identifier)) {
                log.debug("Tweet '{}' is gone or already answered at the moment of generation; no "
                        + "provider call is made and nothing is stored.", identifier);
                return Optional.empty();
            }

            String generatedText = generateText(subject);
            ResponseDto stored = store(identifier, generatedText, true);

            if (stored == null) {
                log.debug("Tweet '{}' acquired a response while one was being generated; nothing was "
                        + "stored.", identifier);
                return Optional.empty();
            }

            log.info("Stored response {} for tweet '{}' awaiting review.", stored.id(), identifier);
            return Optional.of(stored);
        } catch (ResponseGenerationException e) {
            // Already recorded by the layer that raised it — DL-252 — see docs/DECISION_LOG.md
            throw e;
        } catch (RuntimeException e) {
            // The one ERROR record this failure receives, ahead of the fixed translation — DL-252 —
            // see docs/DECISION_LOG.md
            log.error("Response generation failed for tweet '{}': {}.", identifier, LogSafe.type(e));
            throw new ResponseGenerationException(e);
        } finally {
            claimed.remove(identifier);
        }
    }


    // The provider adapter owns its own diagnostic record — DL-252 — see docs/DECISION_LOG.md
    /**
     * Requests the generated text for {@code subject} and translates a provider failure.
     *
     * <p>{@code service.LlmService} records every failure it raises: a rejected request and a transport
     * failure at {@code ERROR}, an unusable reply at {@code WARN}. This method records the failure at
     * {@code DEBUG} only and never at {@code ERROR}. Exactly one {@code ERROR} record exists for a
     * provider failure, and the adapter writes it — DL-252.
     *
     * <p>A failure already carrying the wire literal of {@code backend/app/api/responses.py:L49}
     * passes through unchanged; every other failure is wrapped so it carries that literal. The record
     * names the identifier through {@link LogSafe#logSafe(String)} and the failure through
     * {@link LogSafe#type(Throwable)}: no prompt, no model output and no provider payload reaches the
     * log.
     *
     * @param subject the wire form of the row to reply to, never {@code null}
     * @return the generated text, never {@code null}
     * @throws ResponseGenerationException when the provider raises, carrying the wire literal of
     *                                     {@code backend/app/api/responses.py:L49}
     */
    private String generateText(TweetDto subject) {
        try {
            return llmService.generateResponse(subject);
        } catch (RuntimeException providerFailure) {
            log.debug("The generation provider failed for tweet '{}': {}; responding with the wire "
                    + "literal of backend/app/api/responses.py:L49.",
                    LogSafe.logSafe(subject.id()), LogSafe.type(providerFailure));

            if (providerFailure instanceof ResponseGenerationException alreadyTranslated) {
                throw alreadyTranslated;
            }
            throw new ResponseGenerationException(providerFailure);
        }
    }

    /**
     * Reads the {@code tweets} row a generation request names and returns its wire form.
     *
     * <p>The row is converted inside the transaction the repository operation demarcates; the
     * {@code responses} association is not read.
     *
     * @param identifier the parsed identifier, or {@code null} when the request carried no number
     * @return the subject row in its wire form, never {@code null}
     * @throws ResponseGenerationException when {@code identifier} is {@code null} or names no row,
     *                                     carrying the wire literal of
     *                                     {@code backend/app/api/responses.py:L49}
     */
    // Replaces Tweet.get(tweet_id) at backend/app/tasks/response_generation.py:L16 — DL-086 — see
    // docs/DECISION_LOG.md
    private TweetDto readSubject(Integer identifier) {
        Optional<Tweet> found = (identifier == null)
                ? Optional.empty()
                : tweetRepository.findById(identifier);
        if (found.isEmpty()) {
            log.error("Response generation failed: identifier {} names no tweets row.", identifier);
            throw new ResponseGenerationException();
        }
        return tweetMapper.toDto(found.get());
    }

    /**
     * Stores the generated reply as a new {@code responses} row and returns the stored row.
     *
     * <p>The row carries the generated text as {@code content}, {@code is_approved} {@code false},
     * {@code generated_at} as the current local time truncated to microseconds, and the re-read
     * {@link Tweet} as its association. Its {@code id} is assigned by the database on insert and is
     * read back from the stored row, replacing the {@code response.save()} call at
     * {@code backend/app/tasks/response_generation.py:L25-26}.
     *
     * <p>The transaction first re-reads the parent through
     * {@link TweetRepository#findByIdForUpdate(Integer)}. The parent-row lock is held through the
     * existence check and insert. When {@code onlyWhenAbsent} is set, a row that already carries a
     * reply stores nothing and returns {@code null}. The lock, check and optional insert share one
     * transaction — see docs/DECISION_LOG.md DL-195.
     *
     * @param identifier the parsed identifier of the parent row
     * @param generatedText the text to store; neither {@code null} nor blank
     * @param onlyWhenAbsent {@code true} to store nothing when the parent row already carries a reply
     * @return the stored row in its wire form, or {@code null} when {@code onlyWhenAbsent} is set and
     *         the parent row already carries a reply
     * @throws ResponseGenerationException when {@code identifier} names no row at this point,
     *                                     carrying the wire literal of
     *                                     {@code backend/app/api/responses.py:L49}
     */
    // Replaces response.save() at backend/app/tasks/response_generation.py:L25-26 — DL-086 — see
    // docs/DECISION_LOG.md
    // The parent lock and onlyWhenAbsent check form the transaction-scoped guard — DL-195 — see
    // docs/DECISION_LOG.md
    private ResponseDto store(Integer identifier, String generatedText, boolean onlyWhenAbsent) {
        try {
            return storeInTransaction(identifier, generatedText, onlyWhenAbsent);
        } catch (PessimisticLockingFailureException | QueryTimeoutException contended) {
            // A parent row another writer holds for the whole bound is left to that writer — DL-246
            log.warn("Tweet {} stayed locked by another writer for the whole {}s bound ({}); nothing "
                    + "was stored.", identifier, LOCK_WAIT_SECONDS, LogSafe.type(contended));
            return null;
        }
    }

    /**
     * Performs the storage attempt of {@link #store(Integer, String, boolean)} inside one bounded
     * transaction.
     *
     * @param identifier     the parsed parent identifier, or {@code null}
     * @param generatedText  the text to store
     * @param onlyWhenAbsent whether an existing reply suppresses the insert
     * @return the stored row in its wire form, or {@code null} when nothing was stored
     * @throws ResponseGenerationException when {@code identifier} names no {@code tweets} row
     */
    private ResponseDto storeInTransaction(Integer identifier, String generatedText,
            boolean onlyWhenAbsent) {
        return transactionTemplate.execute(status -> {
            Optional<Tweet> found = (identifier == null)
                    ? Optional.empty()
                    : tweetRepository.findByIdForUpdate(identifier);
            if (found.isEmpty()) {
                log.error("Response generation failed: identifier {} names no tweets row.",
                        identifier);
                throw new ResponseGenerationException();
            }

            if (onlyWhenAbsent && responseRepository.existsByTweetId(identifier)) {
                log.debug("Tweet {} already carries a response; the background pass stores nothing.",
                        identifier);
                return null;
            }

            Response response = new Response();
            response.setContent(generatedText);
            // backend/app/db/models.py:L26 — the flag a human reviewer reads
            response.setIsApproved(false);
            // Minted where the row is built, at the precision the column stores — DL-232 — see
            // docs/DECISION_LOG.md
            response.setGeneratedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            // backend/app/db/models.py:L27-28 — the association owns the tweet_id column
            response.setTweet(found.get());

            return responseMapper.toDto(responseRepository.save(response));
        });
    }

    // Call site backend/app/api/responses.py:L60; 400 literal :L57; 404 literal :L65 — see
    // docs/DECISION_LOG.md DL-048
    /**
     * Applies a partial update to the {@code responses} row identified by {@code responseId} and
     * returns the stored row.
     *
     * <p>The request is rejected when it is {@code null} and when it carries neither the
     * {@code content} key nor the {@code is_approved} key, matching the {@code if not update_data}
     * guard at {@code backend/app/api/responses.py:L56}. The request is tested before the row is read,
     * in the order of {@code :L56-60}.
     *
     * <p>{@link UpdateResponseRequest} rejects no body: every carried value reaches this method,
     * a JSON {@code null} included — see docs/DECISION_LOG.md DL-082 and DL-244.
     *
     * <p>{@code responseId} arrives as the raw path segment. An identifier carrying no number, a
     * {@code null} identifier and an identifier naming no row are all reported with the literal of
     * {@code :L65}, which is a different string from the one {@link #getResponseById(String)} reports.
     *
     * <p>Two columns are writable here, and each is written exactly when the request body carried its
     * key: presence decides whether the column is written, and the carried value decides what is
     * stored. A key the body omits leaves its column untouched; a key carrying a JSON {@code null}
     * writes {@code null} to its nullable column — see docs/DECISION_LOG.md DL-082 and DL-244. Both
     * members are declared required by the wire contract of {@code backend/app/schema/response.py:L6,L8}
     * (DL-080), so a write that would leave either of them empty is reported with the literal of
     * {@code :L65} and rolls this transaction back, leaving the row as it was. {@code id},
     * {@code generated_at} and {@code tweet_id} are not written by this method, and no value is trimmed
     * or normalised on the way in.
     *
     * <p>{@code is_approved} is the flag a human reviewer reads.
     *
     * <p>The row is read through {@link ResponseRepository#findByIdForUpdate(Integer)}, which holds a
     * pessimistic write lock on it for the remainder of this method's transaction, so two requests
     * writing the two different columns at the same moment cannot overwrite one another's column — see
     * docs/DECISION_LOG.md DL-122.
     *
     * @param responseId the raw path segment identifying the row; an unparseable and a {@code null}
     *                   value are both reported as absent
     * @param request    the columns to write; {@code null}, and a request carrying neither updatable
     *                   member, are both rejected
     * @return the stored row in its wire form, never {@code null}
     * @throws BadRequestException when {@code request} is {@code null} or carries neither updatable
     *                             member, carrying the wire literal of
     *                             {@code backend/app/api/responses.py:L57}
     * @throws NotFoundException   when {@code responseId} names no row, and when the update would
     *                             leave {@code content} or {@code is_approved} empty; both carry the
     *                             wire literal of {@code backend/app/api/responses.py:L65}
     */
    @Transactional(timeout = LOCK_WAIT_SECONDS)
    public ResponseDto updateResponse(String responseId, UpdateResponseRequest request) {
        // A request carrying neither updatable member reaches the existing :L57 failure — DL-082.
        if (request == null || request.carriesNoUpdatableMember()) {
            log.warn("Rejected the update of response '{}': the request carried no writable value.",
                    LogSafe.logSafe(responseId));
            throw BadRequestException.updateDataRequired();
        }

        // Locked read ahead of the mutation, bounded by LOCK_WAIT_SECONDS — DL-122, DL-246 — see
        // docs/DECISION_LOG.md
        Optional<Response> existing;
        try {
            existing = findByIdentifierForUpdate(responseId);
        } catch (PessimisticLockingFailureException | QueryTimeoutException contended) {
            log.warn("Rejected the update of response '{}': the row stayed locked by another writer "
                    + "for the whole {}s bound ({}).", LogSafe.logSafe(responseId),
                    LOCK_WAIT_SECONDS, LogSafe.type(contended));
            throw NotFoundException.responseNotFoundOrUpdateFailed();
        }

        // backend/app/api/responses.py:L62-65 — a literal distinct from the one at :L31
        if (existing.isEmpty()) {
            log.warn("Rejected the update of response '{}': the identifier names no row.",
                    LogSafe.logSafe(responseId));
            throw NotFoundException.responseNotFoundOrUpdateFailed();
        }

        Response response = existing.get();
        // Presence of the key decides what is written; the carried value decides what is stored, a
        // JSON null included — DL-082, DL-244 — see docs/DECISION_LOG.md
        if (request.writesContent()) {
            response.setContent(request.contentValue());
        }
        if (request.writesApproval()) {
            response.setIsApproved(request.approvalValue());
        }

        // The two writable columns are nullable, and dto/ResponseDto declares both members required
        // — DL-080. A write that would leave either empty is reported with the wire literal of
        // backend/app/api/responses.py:L65 and this transaction rolls back, so no row is left in a
        // state the wire contract cannot render — DL-244 — see docs/DECISION_LOG.md
        if (response.getContent() == null || response.getIsApproved() == null) {
            log.warn("Rejected the update of response '{}': the update would leave a member the "
                    + "wire contract declares required empty.", LogSafe.logSafe(responseId));
            throw NotFoundException.responseNotFoundOrUpdateFailed();
        }

        ResponseDto stored = responseMapper.toDto(responseRepository.save(response));

        log.info("Updated response '{}': content {}, approval {}.",
                LogSafe.logSafe(responseId),
                request.writesContent() ? "written" : "unchanged",
                request.writesApproval() ? "written" : "unchanged");

        return stored;
    }

    /**
     * Reads the {@code responses} row named by a raw path segment.
     *
     * <p>An empty {@link Optional} is returned for a {@code null} segment, for a segment carrying no
     * number, and for a number naming no row. The caller selects the literal reported for that empty
     * result: {@link #getResponseById(String)} and
     * {@link #updateResponse(String, UpdateResponseRequest)} report different ones.
     *
     * @param responseId the raw path segment identifying the row; may be {@code null}
     * @return the row, or an empty {@link Optional} when {@code responseId} names none
     */
    private Optional<Response> findByIdentifier(String responseId) {
        Integer identifier = parseIdentifier(responseId);
        return (identifier == null) ? Optional.empty() : responseRepository.findById(identifier);
    }

    // Locked read ahead of the mutation — DL-122 — see docs/DECISION_LOG.md
    /**
     * Reads the {@code responses} row named by a raw path segment, holding a write lock on it.
     *
     * <p>Behaves exactly as {@link #findByIdentifier(String)} in every respect except the lock: an
     * empty {@link Optional} is returned for a {@code null} segment, for a segment carrying no number,
     * and for a number naming no row. No lock is taken in any of those three cases: no row is read.
     *
     * <p>The lock lives for the duration of the caller's transaction, so this operation is called only
     * from {@link #updateResponse(String, UpdateResponseRequest)}, which is
     * {@link Transactional} — DL-122.
     *
     * @param responseId the raw path segment identifying the row; may be {@code null}
     * @return the locked row, or an empty {@link Optional} when {@code responseId} names none
     */
    private Optional<Response> findByIdentifierForUpdate(String responseId) {
        Integer identifier = parseIdentifier(responseId);
        return (identifier == null)
                ? Optional.empty()
                : responseRepository.findByIdForUpdate(identifier);
    }

    /**
     * Converts a raw path segment or request value into the identifier type the repositories take.
     *
     * <p>{@code null} is returned for a {@code null} value and for a value that
     * {@link Integer#valueOf(String)} does not accept, which includes the empty string, a
     * whitespace-only value, a value carrying any non-digit character and a value beyond the range of
     * an {@link Integer}. No value is trimmed before the conversion.
     *
     * @param value the raw value to convert; may be {@code null}
     * @return the converted identifier, or {@code null} when {@code value} carries no number
     */
    // DL-138 — see docs/DECISION_LOG.md
    private static Integer parseIdentifier(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
