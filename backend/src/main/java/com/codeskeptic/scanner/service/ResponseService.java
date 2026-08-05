package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.codeskeptic.scanner.util.LogSafe;

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

    /** Demarcates the short transactional unit that stores a generated row — DL-086. */
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
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate,
                "transactionTemplate must not be null.");
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
     * <p>The pagination block carries {@code page} as the 1-based number of the page returned,
     * {@code per_page} as its size, {@code total} as the number of rows in the table and
     * {@code total_pages} as the number of pages that size divides the table into — DL-038. The rows
     * are converted inside this method's transaction.
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
        // DL-200 — see docs/DECISION_LOG.md
        int requestedPage = (page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
        // No upper bound is applied; the source declared none — see docs/DECISION_LOG.md DL-123
        int requestedPerPage = (perPage < 1) ? DEFAULT_PER_PAGE : perPage;

        // The wire page of backend/app/api/responses.py:L11 is 1-based; PageRequest is 0-based —
        // DL-038 — see docs/DECISION_LOG.md
        Page<Response> found = responseRepository.findAll(
                PageRequest.of(Math.subtractExact(requestedPage, WIRE_PAGE_OFFSET), requestedPerPage));

        List<ResponseDto> responses = responseMapper.toDtoList(found.getContent());
        PaginationDto pagination = new PaginationDto(
                found.getNumber() + 1,
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());

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
     * <p>{@code tweetId} is rejected when it is {@code null} and when it is the empty string, matching
     * the {@code if not tweet_id} guard at {@code backend/app/api/responses.py:L40}. A whitespace-only
     * value passes that guard.
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
            String generatedText = llmService.generateResponse(subject);
            ResponseDto stored = store(identifier, generatedText, false);

            log.info("Stored response {} for tweet '{}' awaiting review.",
                    stored.id(), LogSafe.logSafe(tweetId));
            return stored;
        } catch (ResponseGenerationException e) {
            // The failure already carrying the wire literal of :L49 passes through unchanged.
            throw e;
        } catch (RuntimeException e) {
            // The failing layer owns the diagnostic record: service/LlmService reports the provider
            // status and error code, and readSubject and store report an absent row. This record
            // carries only the wrapping — see docs/DECISION_LOG.md DL-052
            log.debug("Wrapping a generation failure for tweet '{}' as the wire literal of "
                    + "backend/app/api/responses.py:L49: {}.",
                    tweetId, LogSafe.type(e));
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
     *       differ; and</li>
     *   <li>a pessimistic lock on the parent {@code tweets} row, acquired inside the short storage
     *       transaction before {@link ResponseRepository#existsByTweetId(Integer)} and the insert,
     *       which serialises the final check across application instances.</li>
     * </ul>
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

        if (!claimed.add(identifier)) {
            log.debug("Tweet '{}' is already being generated for; this pass stores nothing.",
                    LogSafe.logSafe(tweetId));
            return Optional.empty();
        }

        try {
            TweetDto subject = readSubject(identifier);
            String generatedText = llmService.generateResponse(subject);
            ResponseDto stored = store(identifier, generatedText, true);

            if (stored == null) {
                log.debug("Tweet '{}' acquired a response while one was being generated; nothing was "
                        + "stored.", tweetId);
                return Optional.empty();
            }

            log.info("Stored response {} for tweet '{}' awaiting review.", stored.id(), tweetId);
            return Optional.of(stored);
        } catch (ResponseGenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Single sanitized error log for this path — DL-084 — see docs/DECISION_LOG.md
            log.error("Response generation failed for tweet '{}': {}.",
                    LogSafe.logSafe(tweetId), LogSafe.type(e));
            throw new ResponseGenerationException(e);
        } finally {
            claimed.remove(identifier);
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
     * {@code generated_at} as the current local time, and the re-read {@link Tweet} as its
     * association. Its {@code id} is assigned by the database on insert and is read back from the
     * stored row, replacing the {@code response.save()} call at
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
            // Minted where the row is built — DL-201 — see docs/DECISION_LOG.md
            response.setGeneratedAt(LocalDateTime.now());
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
     * <p>The request is rejected when it is {@code null} or carries no value a writable column can
     * hold. A JSON string can write {@code content}; a JSON boolean can write
     * {@code is_approved}. An omitted key, explicit JSON {@code null}, or wrong-typed value is not
     * writable. The request is tested before the row is read, using the existing
     * {@code Update data is required} failure — DL-082.
     *
     * <p>{@code responseId} arrives as the raw path segment. An identifier carrying no number, a
     * {@code null} identifier and an identifier naming no row are all reported with the literal of
     * {@code :L65}, which is a different string from the one {@link #getResponseById(String)} reports.
     *
     * <p>Two columns are writable here, and each is written exactly when the request carries a value
     * of the column's JSON type. This method never writes {@code null}; an unwritable component leaves
     * its column untouched. {@code id}, {@code generated_at} and {@code tweet_id} are not written, and
     * no accepted value is trimmed or normalised on the way in — DL-082.
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
     * @param request    the columns to write; {@code null}, and a request carrying no writable value,
     *                   are both rejected
     * @return the stored row in its wire form, never {@code null}
     * @throws BadRequestException when {@code request} is {@code null} or carries no writable value,
     *                             carrying the wire literal of
     *                             {@code backend/app/api/responses.py:L57}
     * @throws NotFoundException   when {@code responseId} names no row, carrying the wire literal of
     *                             {@code backend/app/api/responses.py:L65}
     */
    @Transactional
    public ResponseDto updateResponse(String responseId, UpdateResponseRequest request) {
        // A request carrying no column-compatible value reaches the existing :L57 failure — DL-082.
        if (request == null || request.carriesNoWritableValue()) {
            log.warn("Rejected the update of response '{}': the request carried no writable value.",
                    LogSafe.logSafe(responseId));
            throw BadRequestException.updateDataRequired();
        }

        // Locked read ahead of the mutation — DL-122 — see docs/DECISION_LOG.md
        Optional<Response> existing = findByIdentifierForUpdate(responseId);

        // backend/app/api/responses.py:L62-65 — a literal distinct from the one at :L31
        if (existing.isEmpty()) {
            log.warn("Rejected the update of response '{}': the identifier names no row.",
                    LogSafe.logSafe(responseId));
            throw NotFoundException.responseNotFoundOrUpdateFailed();
        }

        Response response = existing.get();
        // Only values the target columns can hold are written — DL-082 — see docs/DECISION_LOG.md
        if (request.writesContent()) {
            response.setContent(request.contentValue());
        }
        if (request.writesApproval()) {
            response.setIsApproved(request.approvalValue());
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
    // DL-202 — see docs/DECISION_LOG.md
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
