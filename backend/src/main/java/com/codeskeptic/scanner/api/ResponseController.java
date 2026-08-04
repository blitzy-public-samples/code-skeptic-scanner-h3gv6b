package com.codeskeptic.scanner.api;

import java.util.Objects;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.CreateResponseRequest;
import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.UpdateResponseRequest;
import com.codeskeptic.scanner.service.ResponseService;

// Ported from backend/app/api/responses.py:L1-65 (faithful port) — see docs/DECISION_LOG.md
/**
 * Serves the four HTTP routes of the {@code responses} resource.
 *
 * <p>Replaces the Flask blueprint {@code responses_bp}, declared at
 * {@code backend/app/api/responses.py:L6} and registered on the application object at
 * {@code backend/app/main.py:L27}. The four routes declared here are the four the blueprint declared.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code GET /responses}</td>
 *   <td>{@link #getResponses(int, int)}</td>
 *   <td>200, a two-key envelope</td>
 *   <td>{@code backend/app/api/responses.py:L8-20}</td>
 * </tr>
 * <tr>
 *   <td>{@code GET /responses/{responseId}}</td>
 *   <td>{@link #getResponse(String)}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/responses.py:L22-31}</td>
 * </tr>
 * <tr>
 *   <td>{@code POST /responses}</td>
 *   <td>{@link #generateResponse(CreateResponseRequest)}</td>
 *   <td>201, one JSON object</td>
 *   <td>{@code backend/app/api/responses.py:L33-49}</td>
 * </tr>
 * <tr>
 *   <td>{@code PUT /responses/{responseId}}</td>
 *   <td>{@link #updateResponse(String, UpdateResponseRequest)}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/responses.py:L51-65}</td>
 * </tr>
 * </table>
 *
 * <p>All four paths are unprefixed, as the blueprint spelled them: no {@code /api} segment and no
 * version segment. Each path is spelled in full on its own handler and no class-level mapping
 * contributes a prefix. {@code POST /responses} is the only route of the four that answers 201; the
 * other three answer 200.
 *
 * <p>Every rendered body is a {@code dto} record and every JSON key is snake_case, fixed by an
 * explicit {@code @JsonProperty} on the record component — see docs/DECISION_LOG.md DL-022. Both
 * identifiers of {@link ResponseDto} are carried as strings — DL-023. No entity type is rendered and
 * none is referenced here; this class reaches the {@code responses} table through
 * {@code service.ResponseService} only.
 *
 * <p>This class selects the success status and builds no error body. The five error statuses of the
 * source routes are produced away from here, each raised by {@code service.ResponseService} and
 * answered by {@link GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>{@code NotFoundException} carrying {@code Response not found}, the wire literal of
 *       {@code backend/app/api/responses.py:L31}, for a {@code responseId} naming no row on
 *       {@code GET /responses/{responseId}} — answered with 404.</li>
 *   <li>{@code BadRequestException} carrying {@code Tweet ID is required}, the wire literal of
 *       {@code backend/app/api/responses.py:L41}, for a {@code null} or empty {@code tweet_id} on
 *       {@code POST /responses}; {@code MethodArgumentNotValidException} is raised instead when the
 *       body carries no {@code tweet_id} member, and {@link GlobalExceptionHandler} answers both with
 *       400 and that same literal.</li>
 *   <li>{@code ResponseGenerationException} carrying {@code Failed to generate response}, the wire
 *       literal of {@code backend/app/api/responses.py:L49}, for every failure past that guard on
 *       {@code POST /responses} — answered with 500.</li>
 *   <li>{@code BadRequestException} carrying {@code Update data is required}, the wire literal of
 *       {@code backend/app/api/responses.py:L57}, for an absent body and a body carrying neither
 *       updatable key on {@code PUT /responses/{responseId}} — answered with 400.</li>
 *   <li>{@code NotFoundException} carrying {@code Response not found or update failed}, the wire
 *       literal of {@code backend/app/api/responses.py:L65}, for a {@code responseId} naming no row on
 *       {@code PUT /responses/{responseId}} — answered with 404.</li>
 * </ul>
 *
 * <p>The two 404 literals are different strings: {@code :L31} reports {@code Response not found} and
 * {@code :L65} reports {@code Response not found or update failed}.
 * {@code service.ResponseService} selects the one belonging to the route.
 *
 * <p>{@code POST /responses} declares no 404 branch. Its statuses are 400, 201 and 500 only, as at
 * {@code backend/app/api/responses.py:L33-49}: a {@code tweet_id} carrying no number and a
 * {@code tweet_id} naming no {@code tweets} row are both answered with the 500 literal of {@code :L49}
 * — see docs/DECISION_LOG.md DL-076.
 *
 * <p>Both path variables are bound as {@link String} — the type Flask's default path converter
 * delivered at {@code backend/app/api/responses.py:L22} and {@code :L51}. An identifier of any
 * spelling reaches the service, and one carrying no number is reported with the same literal as one
 * naming no row — see docs/DECISION_LOG.md DL-048.
 *
 * <p>A query parameter whose text {@code int} cannot hold is rejected by the framework before a
 * handler is entered, and {@link GlobalExceptionHandler} answers it with 400 and
 * {@code {"error": "Bad request"}} — DL-092.
 *
 * <p>{@code ResponseService()} was constructed per request at {@code backend/app/api/responses.py:L14},
 * {@code :L25}, {@code :L43} and {@code :L59}; the service is an injected singleton here, held in a
 * final field. {@code response.to_dict()} at {@code :L18}, {@code :L29}, {@code :L47} and {@code :L63}
 * was never defined in the source; that conversion is performed by
 * {@code service.mapper.ResponseMapper} behind {@code service.ResponseService} and never here.
 *
 * <p>Authentication is enforced by the security filter chain, which runs ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/responses.py:L9}, {@code :L23}, {@code :L34} and {@code :L52} — see
 * docs/DECISION_LOG.md DL-021. No method here declares an authorization annotation.
 *
 * <p>No handler here writes to the X API and none holds a client that could reach it: the only
 * collaborator is {@code service.ResponseService}. {@code is_approved}
 * ({@code backend/app/db/models.py:L26}) is a flag a human reviewer reads and no handler acts on its
 * value.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-022, DL-023,
 * DL-038, DL-048, DL-050, DL-059, DL-076 and DL-092; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Its one collaborator is held in a final field and is itself a
 * singleton, and this class holds no other state, so every member declared here is safe for concurrent
 * use.
 */
@RestController
public class ResponseController {

    /** Reads, generates and updates the rows of the {@code responses} table. */
    private final ResponseService responseService;

    /**
     * Creates the controller with its one collaborator, replacing the per-request construction at
     * {@code backend/app/api/responses.py:L14,L25,L43,L59}.
     *
     * @param responseService the service serving all four routes, must not be {@code null}
     * @throws NullPointerException when {@code responseService} is {@code null}
     */
    public ResponseController(ResponseService responseService) {
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
    }

    // backend/app/api/responses.py:L8 — L11-12 defaults 1 / 10 — envelope :L17-20 — DL-038
    /**
     * Renders one page of the {@code responses} table inside the two-key list envelope.
     *
     * <p>Reproduces {@code GET /responses} at {@code backend/app/api/responses.py:L8-20}. The body
     * carries {@code responses}, a JSON array of {@link ResponseDto} objects, and {@code pagination},
     * whose keys are {@code page}, {@code per_page}, {@code total} and {@code total_pages} — see
     * docs/DECISION_LOG.md DL-038. An empty page renders {@code responses} as an empty array, and the
     * status is 200 either way.
     *
     * <p>The query-parameter names are the ones the source read: {@code page} at
     * {@code backend/app/api/responses.py:L11}, defaulting to 1, and {@code per_page} at {@code :L12},
     * defaulting to 10. {@code per_page} is spelled with an underscore on the wire and carries no
     * camelCase alias — see docs/DECISION_LOG.md DL-059.
     *
     * <p>Both values are passed to the service unchanged: not clamped, converted or bounds-checked
     * here. {@code page} is 1-based on the wire, and {@code service.ResponseService} performs the
     * conversion to the 0-based index Spring Data takes and reports the 1-based number back — DL-038.
     *
     * <p>Example response body for page 1 of 10 per page over a single row:
     *
     * <pre>{@code
     * {"responses":[{"id":"1","content":"...","generated_at":"2026-01-31T09:15:00",
     *                "is_approved":false,"tweet_id":"7"}],
     *  "pagination":{"page":1,"per_page":10,"total":1,"total_pages":1}}
     * }</pre>
     *
     * @param page    the 1-based page number, read from the {@code page} query parameter; 1 when the
     *                request omits it
     * @param perPage the number of rows per page, read from the {@code per_page} query parameter; 10
     *                when the request omits it
     * @return 200 carrying the {@code responses} and {@code pagination} envelope
     */
    @GetMapping("/responses")
    public ResponseEntity<PaginatedResponsesDto> getResponses(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "10") int perPage) {
        // backend/app/api/responses.py:L15 — constructed per request in the source
        return ResponseEntity.ok(responseService.getPaginatedResponses(page, perPage));
    }

    // backend/app/api/responses.py:L22 — String path variable — DL-048 — 404 literal :L31
    /**
     * Renders the single {@code responses} row named by the path.
     *
     * <p>Reproduces {@code GET /responses/&lt;response_id&gt;} at
     * {@code backend/app/api/responses.py:L22-31}. The path-variable name is {@code responseId},
     * carrying the segment the source route spelled {@code response_id} at {@code :L22}, bound as a
     * {@code String} — see docs/DECISION_LOG.md DL-048.
     *
     * <p>A segment of any spelling reaches the service. A segment carrying no number and a number
     * naming no row are both answered with 404 and the literal of {@code :L31}, which is a different
     * string from the one {@link #updateResponse(String, UpdateResponseRequest)} reports.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"id":"1","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,
     *  "tweet_id":"7"}
     * }</pre>
     *
     * @param responseId the raw path segment identifying the row
     * @return 200 carrying the stored row
     */
    @GetMapping("/responses/{responseId}")
    public ResponseEntity<ResponseDto> getResponse(@PathVariable("responseId") String responseId) {
        // backend/app/api/responses.py:L26 — the :L28-31 branch is answered by GlobalExceptionHandler
        return ResponseEntity.ok(responseService.getResponseById(responseId));
    }

    // backend/app/api/responses.py:L33 — 400 :L41 / 201 :L47 / 500 :L49 — no 404 branch — DL-076
    /**
     * Generates a reply to one {@code tweets} row, stores it, and renders the stored row.
     *
     * <p>Reproduces {@code POST /responses} at {@code backend/app/api/responses.py:L33-49}. The body
     * carries one member, {@code tweet_id}, read at {@code :L38} and declared by
     * {@link CreateResponseRequest} with {@code @NotNull} as its only constraint — see
     * docs/DECISION_LOG.md DL-050.
     *
     * <p>The success status is 201, as at {@code :L47}, and the body is the stored row carrying the
     * {@code id} the database assigned. No {@code Location} header is set; the source set none at
     * {@code :L47}. The stored row carries {@code is_approved} {@code false}.
     *
     * <p>An absent body binds to {@code null} and its {@code tweet_id} reaches the service as
     * {@code null}. The guard at {@code :L40} is {@code if not tweet_id}, a falsiness test, so
     * {@code null} and the empty string are both answered with 400 and the literal of {@code :L41}; a
     * body carrying no {@code tweet_id} member fails the {@code @NotNull} constraint and is answered
     * with 400 and that same literal.
     *
     * <p>This route declares no 404 branch. Every failure past the guard — a {@code tweet_id} carrying
     * no number, a {@code tweet_id} naming no {@code tweets} row, and a generation or storage failure —
     * is answered with 500 and the literal of {@code :L49} — see docs/DECISION_LOG.md DL-076.
     *
     * <p>Example request body:
     *
     * <pre>{@code {"tweet_id":"7"}}</pre>
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,
     *  "tweet_id":"7"}
     * }</pre>
     *
     * @param request the request body; {@code null} when the request carried no body
     * @return 201 carrying the stored row
     */
    @PostMapping("/responses")
    public ResponseEntity<ResponseDto> generateResponse(
            @Valid @RequestBody(required = false) CreateResponseRequest request) {
        // backend/app/api/responses.py:L38 — request.json.get('tweet_id')
        String tweetId = (request == null) ? null : request.tweetId();

        // backend/app/api/responses.py:L44 — constructed per request in the source
        ResponseDto generated = responseService.generateResponse(tweetId);

        // backend/app/api/responses.py:L47 — 201, the only 201 of the four routes
        return ResponseEntity.status(HttpStatus.CREATED).body(generated);
    }

    // backend/app/api/responses.py:L51 — 400 :L57 / 404 :L65 — :L31 vs :L65 are two distinct literals
    /**
     * Applies a partial update to one {@code responses} row and renders the stored row.
     *
     * <p>Reproduces {@code PUT /responses/&lt;response_id&gt;} at
     * {@code backend/app/api/responses.py:L51-65}. The path-variable name is {@code responseId},
     * carrying the segment the source route spelled {@code response_id} at {@code :L51}, bound as a
     * {@code String} — see docs/DECISION_LOG.md DL-048.
     *
     * <p>The two updatable properties are {@code content} and {@code is_approved}, declared by
     * {@link UpdateResponseRequest}. No other property is bound or forwarded: {@code id},
     * {@code generated_at} and {@code tweet_id} are not writable through this route. The source read
     * {@code request.json} free-form at {@code :L54} and declared no per-field requirement;
     * {@link UpdateResponseRequest} declares no validation constraint and this parameter declares no
     * validation annotation — see docs/DECISION_LOG.md DL-050.
     *
     * <p>The body is passed to the service verbatim: not trimmed, defaulted or coerced. An absent body
     * binds to {@code null}. The guard at {@code :L56} is {@code if not update_data}, a falsiness test,
     * so an absent body and a body carrying neither key are both answered with 400 and the literal of
     * {@code :L57}.
     *
     * <p>A {@code responseId} carrying no number and one naming no row are both answered with 404 and
     * the literal of {@code :L65}, {@code Response not found or update failed} — a different string
     * from the {@code Response not found} that {@link #getResponse(String)} reports at {@code :L31}.
     *
     * <p>Example request body:
     *
     * <pre>{@code {"content":"Revised reply","is_approved":true}}</pre>
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"id":"1","content":"Revised reply","generated_at":"2026-01-31T09:15:00","is_approved":true,
     *  "tweet_id":"7"}
     * }</pre>
     *
     * @param responseId the raw path segment identifying the row
     * @param request    the columns to write; {@code null} when the request carried no body
     * @return 200 carrying the stored row
     */
    @PutMapping("/responses/{responseId}")
    public ResponseEntity<ResponseDto> updateResponse(
            @PathVariable("responseId") String responseId,
            @RequestBody(required = false) UpdateResponseRequest request) {
        // backend/app/api/responses.py:L54,L60 — the body reaches the service exactly as bound
        return ResponseEntity.ok(responseService.updateResponse(responseId, request));
    }
}
