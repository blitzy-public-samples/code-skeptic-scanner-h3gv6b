package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * those call sites imported at {@code :L3} defined none of them. This class declares no further
 * operation and no static method reachable from outside it.
 *
 * <p>The source constructed the service once per request, at
 * {@code backend/app/api/responses.py:L14}, {@code :L25}, {@code :L43} and {@code :L59}. This is one
 * singleton bean holding its five collaborators in final fields.
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
 * <p>None of the five is minted here. Each is held as a constant on its exception type and reached
 * through that type's factory, and no identifier, driver text or stack detail is appended to any of
 * them. This class selects no HTTP status; {@code api.GlobalExceptionHandler} does.
 *
 * <p>The two 404 messages are different strings. {@code GET /responses/{responseId}} carries the
 * literal of {@code :L31}; {@code PUT /responses/{responseId}} carries the literal of {@code :L65}.
 *
 * <p>{@code POST /responses} reports the two outcomes of {@code :L46-49}: a stored draft, or the
 * single 500 literal. Every failure on that path is reported as that one literal — an identifier that
 * parses to no number, an identifier naming no {@code tweets} row, a generation failure and a
 * persistence failure alike. That path reports no 404.
 *
 * <p>Every entity is converted to its wire form inside the transaction that loaded it. Neither a
 * detached entity nor an uninitialised proxy leaves this class; {@code spring.jpa.open-in-view} is
 * {@code false}.
 *
 * <p>A generated reply is stored with {@code is_approved} {@code false} and is sent nowhere. This
 * class opens no connection to X, holds no HTTP client, and declares no operation that publishes,
 * posts, replies, retweets or sends. {@code is_approved} is written on request by
 * {@link #updateResponse(String, UpdateResponseRequest)} and read by a human reviewer; no branch here
 * reads it to emit anything outward.
 *
 * <p>Rows are the only thing this class adds. It contributes no column, table, index or constraint.
 * The {@code tweets}-to-{@code responses} association declared at
 * {@code backend/app/db/models.py:L27-28,L30} is the only association it writes.
 *
 * <p>The {@code responses} and {@code tweets} tables are reached through {@link ResponseRepository}
 * and {@link TweetRepository} only. This class declares no JPQL and no native query, memoises
 * nothing, and retries nothing.
 *
 * <p>Text generation is delegated to {@link LlmService}, which returns a {@link ResponseDto}. No
 * vendor type appears in this class.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md}; construct-level
 * provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean and its five collaborators are themselves singletons. It holds no other
 * state, and every member declared here is safe for concurrent use. Two callers updating the same row
 * concurrently both write, and the later write stands.
 */
@Service
public class ResponseService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(ResponseService.class);

    /**
     * Wire page number applied when the requested page is below the first one. It is the default the
     * source declared for the {@code page} query parameter at
     * {@code backend/app/api/responses.py:L11}.
     */
    private static final int DEFAULT_PAGE = 1;

    /**
     * Page size applied when the requested size is not positive. It is the default the source declared
     * for the {@code per_page} query parameter at {@code backend/app/api/responses.py:L12}.
     */
    private static final int DEFAULT_PER_PAGE = 10;

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
     * Creates the bean with its collaborators, replacing the per-request construction at
     * {@code backend/app/api/responses.py:L14,L25,L43,L59}.
     *
     * @param responseRepository data access for the {@code responses} table, must not be {@code null}
     * @param tweetRepository    data access for the {@code tweets} table, must not be {@code null}
     * @param llmService         reply-text generator, must not be {@code null}
     * @param responseMapper     {@link Response}-to-wire converter, must not be {@code null}
     * @param tweetMapper        {@link Tweet}-to-wire converter, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public ResponseService(ResponseRepository responseRepository,
            TweetRepository tweetRepository,
            LlmService llmService,
            ResponseMapper responseMapper,
            TweetMapper tweetMapper) {
        this.responseRepository = Objects.requireNonNull(responseRepository,
                "responseRepository must not be null.");
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null.");
        this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper must not be null.");
        this.tweetMapper = Objects.requireNonNull(tweetMapper, "tweetMapper must not be null.");
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
     * is read as {@value #DEFAULT_PER_PAGE}; neither is rejected. A {@code page} beyond the last one
     * yields an empty {@code responses} list and a populated pagination block.
     *
     * <p>The pagination block carries {@code page} as the 1-based number of the page returned,
     * {@code per_page} as its size, {@code total} as the number of rows in the table and
     * {@code total_pages} as the number of pages that size divides the table into. The rows are
     * converted inside this method's transaction.
     *
     * @param page    the 1-based page number to return; a value below {@value #DEFAULT_PAGE} is read
     *                as {@value #DEFAULT_PAGE}
     * @param perPage the number of rows per page; a value below {@code 1} is read as
     *                {@value #DEFAULT_PER_PAGE}
     * @return the {@code responses} and {@code pagination} envelope, never {@code null}; the
     *         {@code responses} list is empty when the page holds no row and is unmodifiable
     */
    @Transactional(readOnly = true)
    public PaginatedResponsesDto getPaginatedResponses(int page, int perPage) {
        // Out-of-range values are read as the defaults of backend/app/api/responses.py:L11-12 —
        // DL-077 — see docs/DECISION_LOG.md
        int requestedPage = (page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
        int requestedPerPage = (perPage < 1) ? DEFAULT_PER_PAGE : perPage;

        // The wire page of backend/app/api/responses.py:L11 is 1-based; PageRequest is 0-based —
        // DL-038 — see docs/DECISION_LOG.md
        Page<Response> found =
                responseRepository.findAll(PageRequest.of(requestedPage - 1, requestedPerPage));

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
            log.warn("Rejected the read of response '{}': the identifier names no row.", responseId);
            throw NotFoundException.responseNotFound();
        }

        return responseMapper.toDto(existing.get());
    }

    // Contract from backend/app/api/responses.py:L38-49 (400/201/500) — see docs/DECISION_LOG.md
    // DL-076
    /**
     * Generates a reply to the {@code tweets} row identified by {@code tweetId}, stores it as a new
     * {@code responses} row, and returns the stored row.
     *
     * <p>{@code tweetId} is rejected when it is {@code null} and when it is the empty string, matching
     * the {@code if not tweet_id} guard at {@code backend/app/api/responses.py:L40}. A whitespace-only
     * value passes that guard.
     *
     * <p>Past the guard this method reports the two outcomes of {@code :L46-49}. The stored row is
     * returned, or the single literal of {@code :L49} is reported. That one literal covers every
     * failure on this path: a {@code tweetId} carrying no number, a {@code tweetId} naming no
     * {@code tweets} row, a failure raised by {@link LlmService#generateResponse(TweetDto)}, and a
     * failure raised while storing the row. No 404 is reported here.
     *
     * <p>The stored row carries the generated text as {@code content}, {@code is_approved}
     * {@code false}, {@code generated_at} as the current local time, and the loaded {@link Tweet} as
     * its association. Its {@code id} is assigned by the database on insert and is read back from the
     * stored row, replacing the {@code response.save()} call at
     * {@code backend/app/tasks/response_generation.py:L25-26}. The row is converted inside this
     * method's transaction.
     *
     * <p>The stored row is a draft. It is sent nowhere, and nothing here publishes to X.
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
    @Transactional
    public ResponseDto generateResponse(String tweetId) {
        // backend/app/api/responses.py:L40-41 — the guard tests null and the empty string
        if (tweetId == null || tweetId.isEmpty()) {
            log.warn("Rejected a generation request: the request carried no tweet identifier.");
            throw BadRequestException.tweetIdRequired();
        }

        log.info("Generating a response for tweet '{}'.", tweetId);

        try {
            Long identifier = parseIdentifier(tweetId);
            Optional<Tweet> found = (identifier == null)
                    ? Optional.empty()
                    : tweetRepository.findById(identifier);
            if (found.isEmpty()) {
                log.error("Response generation failed for tweet '{}': the identifier names no row.",
                        tweetId);
                throw new ResponseGenerationException();
            }

            // Replaces Tweet.get(tweet_id) at backend/app/tasks/response_generation.py:L16
            Tweet tweet = found.get();
            TweetDto subject = tweetMapper.toDto(tweet);
            ResponseDto generated = llmService.generateResponse(subject);

            Response response = new Response();
            response.setContent(generated.content());
            // A flag a human reads; never a trigger.
            response.setIsApproved(false);
            // Minted where the row is built — DL-077 — see docs/DECISION_LOG.md
            response.setGeneratedAt(LocalDateTime.now());
            // backend/app/db/models.py:L27-28 — the association owns the tweet_id column
            response.setTweet(tweet);

            // Replaces response.save() at backend/app/tasks/response_generation.py:L25-26
            ResponseDto stored = responseMapper.toDto(responseRepository.save(response));

            log.info("Stored response {} for tweet '{}' awaiting review.", stored.id(), tweetId);
            return stored;
        } catch (ResponseGenerationException e) {
            // The failure already carrying the wire literal of :L49 passes through unchanged.
            throw e;
        } catch (RuntimeException e) {
            log.error("Response generation failed for tweet '{}'.", tweetId, e);
            throw new ResponseGenerationException(e);
        }
    }

    // Call site backend/app/api/responses.py:L60; 400 literal :L57; 404 literal :L65 — see
    // docs/DECISION_LOG.md DL-048 and DL-076
    /**
     * Applies a partial update to the {@code responses} row identified by {@code responseId} and
     * returns the stored row.
     *
     * <p>The request is rejected when it is {@code null} and when it carries neither {@code content}
     * nor {@code is_approved}, matching the {@code if not update_data} guard at
     * {@code backend/app/api/responses.py:L56}: an empty JSON object reaches this method as a request
     * whose two components are both {@code null}. The request is tested before the row is read, in the
     * order of {@code :L56-60}. Nothing is stored when the request is rejected.
     *
     * <p>{@code responseId} arrives as the raw path segment. An identifier carrying no number, a
     * {@code null} identifier and an identifier naming no row are all reported with the literal of
     * {@code :L65}, which is a different string from the one {@link #getResponseById(String)} reports.
     *
     * <p>Two columns are writable here. {@code content} is written when the request carries it, and
     * {@code is_approved} is written when the request carries it; a component that is {@code null} is
     * not written, and {@code is_approved} {@code false} is written like any other value.
     * {@code id}, {@code generated_at} and {@code tweet_id} are not written by this method, and no
     * value is trimmed or normalised on the way in.
     *
     * <p>{@code is_approved} is a flag a human reviewer reads. Writing it sends nothing anywhere.
     *
     * @param responseId the raw path segment identifying the row; an unparseable and a {@code null}
     *                   value are both reported as absent
     * @param request    the columns to write; {@code null}, and a request carrying neither component,
     *                   are both rejected
     * @return the stored row in its wire form, never {@code null}
     * @throws BadRequestException when {@code request} is {@code null} or carries neither component,
     *                             carrying the wire literal of
     *                             {@code backend/app/api/responses.py:L57}
     * @throws NotFoundException   when {@code responseId} names no row, carrying the wire literal of
     *                             {@code backend/app/api/responses.py:L65}
     */
    @Transactional
    public ResponseDto updateResponse(String responseId, UpdateResponseRequest request) {
        // backend/app/api/responses.py:L56-57 — the guard tests null and a body carrying no field
        if (request == null || (request.content() == null && request.isApproved() == null)) {
            log.warn("Rejected the update of response '{}': the request carried no updatable field.",
                    responseId);
            throw BadRequestException.updateDataRequired();
        }

        Optional<Response> existing = findByIdentifier(responseId);

        // backend/app/api/responses.py:L62-65 — a literal distinct from the one at :L31
        if (existing.isEmpty()) {
            log.warn("Rejected the update of response '{}': the identifier names no row.", responseId);
            throw NotFoundException.responseNotFoundOrUpdateFailed();
        }

        Response response = existing.get();
        if (request.content() != null) {
            response.setContent(request.content());
        }
        if (request.isApproved() != null) {
            response.setIsApproved(request.isApproved());
        }

        ResponseDto stored = responseMapper.toDto(responseRepository.save(response));

        log.info("Updated response '{}': content {}, approval {}.",
                responseId,
                request.content() == null ? "unchanged" : "replaced",
                request.isApproved() == null ? "unchanged" : request.isApproved());

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
        Long identifier = parseIdentifier(responseId);
        return (identifier == null) ? Optional.empty() : responseRepository.findById(identifier);
    }

    /**
     * Converts a raw path segment or request value into the identifier type the repositories take.
     *
     * <p>{@code null} is returned for a {@code null} value and for a value that
     * {@link Long#valueOf(String)} does not accept, which includes the empty string, a whitespace-only
     * value, a value carrying any non-digit character and a value beyond the range of a
     * {@link Long}. No value is trimmed before the conversion.
     *
     * @param value the raw value to convert; may be {@code null}
     * @return the converted identifier, or {@code null} when {@code value} carries no number
     */
    // DL-077 — see docs/DECISION_LOG.md
    private static Long parseIdentifier(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
