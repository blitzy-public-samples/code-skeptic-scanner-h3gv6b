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
import com.codeskeptic.scanner.util.QueryParameters;

// Endpoint contract ported from backend/app/api/responses.py:L8-65 (faithful port) — see
// docs/DECISION_LOG.md DL-021, DL-022, DL-023, DL-038, DL-048, DL-050, DL-059, DL-076, DL-092
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
 *   <td>{@link #getResponses(String, String)}</td>
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
 * <p>Paths are unprefixed: no {@code /api} segment and no version segment.
 * {@code POST /responses} answers 201; the other three answer 200.
 *
 * <p>Every rendered body is a {@code dto} record whose JSON keys are snake_case, fixed by an explicit
 * {@code @JsonProperty} on each record component — DL-022. Both identifiers of {@link ResponseDto}
 * are carried as strings — DL-023. No entity type is rendered or referenced here; this class reaches
 * the {@code responses} table through {@code service.ResponseService} only.
 *
 * <p>This class selects the success status and builds no error body. The five error statuses of the
 * source routes are raised by {@code service.ResponseService} and answered by
 * {@link GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>{@code NotFoundException} carrying {@code Response not found}, the wire literal of
 *       {@code backend/app/api/responses.py:L31}, for a {@code responseId} naming no row on
 *       {@code GET /responses/{responseId}} — answered with 404.</li>
 *   <li>{@code BadRequestException} carrying {@code Tweet ID is required}, the wire literal of
 *       {@code backend/app/api/responses.py:L41}, for every {@code tweet_id} value the guard at
 *       {@code :L40} read as false — {@code null}, {@code ""}, a zero, {@code false}, an empty array
 *       and an empty object — on {@code POST /responses};
 *       {@code MethodArgumentNotValidException} is raised instead when the body carries no
 *       {@code tweet_id} member, and both are answered with 400 and that literal — DL-240.</li>
 *   <li>{@code ResponseGenerationException} carrying {@code Failed to generate response}, the wire
 *       literal of {@code backend/app/api/responses.py:L49}, for every failure past that guard on
 *       {@code POST /responses} — answered with 500.</li>
 *   <li>{@code BadRequestException} carrying {@code Update data is required}, the wire literal of
 *       {@code backend/app/api/responses.py:L57}, for an absent body and a body carrying neither
 *       updatable key on {@code PUT /responses/{responseId}} — answered with 400.</li>
 *   <li>{@code NotFoundException} carrying {@code Response not found or update failed}, the wire
 *       literal of {@code backend/app/api/responses.py:L65}, for a {@code responseId} naming no row
 *       on {@code PUT /responses/{responseId}} — answered with 404.</li>
 * </ul>
 *
 * <p>All four paths are unprefixed: no {@code /api} segment and no version segment — DL-059.
 * {@code POST /responses} is the only route of the four that answers 201.
 *
 * <p>{@code POST /responses} declares no 404 branch. Its statuses are 400, 201 and 500 only, as at
 * {@code backend/app/api/responses.py:L33-49}: a {@code tweet_id} carrying no number and a
 * {@code tweet_id} naming no {@code tweets} row are both answered with the 500 literal of
 * {@code :L49} — DL-076.
 *
 * <p>Both path variables are bound as {@link String}, the type Flask's default path converter
 * delivered at {@code backend/app/api/responses.py:L22} and {@code :L51} — DL-048.
 *
 * <p>{@code page} and {@code per_page} are bound as {@link String} and converted by
 * {@code util.QueryParameters}, which substitutes the default for an absent, blank or non-numeric
 * value as {@code request.args.get(..., type=int)} did at
 * {@code backend/app/api/responses.py:L11-12}. No query parameter on these routes produces an error
 * status — DL-217.
 *
 * <p>The service is an injected singleton held in a final field, in place of the per-request
 * {@code ResponseService()} at {@code backend/app/api/responses.py:L14}, {@code :L25}, {@code :L43}
 * and {@code :L59}. The conversion invoked as {@code response.to_dict()} at {@code :L18},
 * {@code :L29}, {@code :L47} and {@code :L63} is performed by {@code service.mapper.ResponseMapper}
 * behind {@code service.ResponseService} and never here.
 *
 * <p>Authentication is enforced by the security filter chain ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/responses.py:L9}, {@code :L23}, {@code :L34} and {@code :L52} — DL-021. No
 * method here declares an authorization annotation.
 *
 * <p>No handler here writes to the X API and its one collaborator,
 * {@code service.ResponseService}, declares no publish operation. {@code is_approved}
 * ({@code backend/app/db/models.py:L26}) is a flag a human reviewer reads and no handler acts on its
 * value.
 *
 * <p>This is a singleton bean holding its one collaborator in a final field and no other state, so
 * every member declared here is safe for concurrent use.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-022,
 * DL-023, DL-038, DL-048, DL-050, DL-059, DL-076, DL-092 and DL-217; construct-level provenance is
 * recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@RestController
public class ResponseController {

    /**
     * Page number applied when {@code page} carries no number —
     * {@code backend/app/api/responses.py:L11}.
     */
    private static final int DEFAULT_PAGE = 1;

    /**
     * Page size applied when {@code per_page} carries no number —
     * {@code backend/app/api/responses.py:L12}.
     */
    private static final int DEFAULT_PER_PAGE = 10;

    /** Reads, generates and updates the rows of the {@code responses} table. */
    private final ResponseService responseService;

    /**
     * Creates the controller with its one collaborator.
     *
     * @param responseService the service serving all four routes, must not be {@code null}
     * @throws NullPointerException when {@code responseService} is {@code null}
     */
    public ResponseController(ResponseService responseService) {
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
    }

    // Ported from backend/app/api/responses.py:L8-20 (faithful port) — DL-038, DL-217 — see
    // docs/DECISION_LOG.md
    /**
     * Renders one page of the {@code responses} table inside the two-key list envelope.
     *
     * <p>Reproduces {@code GET /responses} at {@code backend/app/api/responses.py:L8-20}. The body
     * carries {@code responses}, a JSON array of {@link ResponseDto} objects, and {@code pagination},
     * whose keys are {@code page}, {@code per_page}, {@code total} and {@code total_pages} — DL-038. An
     * empty page renders {@code responses} as an empty array, and the status is 200 either way.
     *
     * <p>The query-parameter names are {@code page} at {@code backend/app/api/responses.py:L11},
     * defaulting to 1, and {@code per_page} at {@code :L12}, defaulting to 10. {@code per_page} is
     * spelled with an underscore on the wire and carries no camelCase alias — see
     * docs/DECISION_LOG.md DL-059.
     *
     * <p>Each parameter is bound as text and read as a whole number after trimming. A value that holds
     * no whole number — the empty string, a whitespace-only value, {@code abc}, {@code 2.5}, a value
     * beyond {@code int} range — takes the same default an absent parameter takes, and the request is
     * accepted; no such value is reported as a client error — see docs/DECISION_LOG.md DL-217.
     *
     * <p>A value that holds a whole number is passed to the service unchanged, including {@code 0} and
     * a negative value: it is not clamped or bounds-checked here. {@code page} is 1-based on the wire,
     * and {@code service.ResponseService} performs the conversion to the 0-based index Spring Data
     * takes and reports the 1-based number back — DL-038.
     *
     * <p>Example response body for page 1 of 10 per page over a single row:
     *
     * <pre>{@code
     * {"responses":[{"id":"1","content":"...","generated_at":"2026-01-31T09:15:00",
     *                "is_approved":false,"tweet_id":"7"}],
     *  "pagination":{"page":1,"per_page":10,"total":1,"total_pages":1}}
     * }</pre>
     *
     * @param rawPage    the {@code page} query parameter exactly as the request carried it, or
     *                   {@code null} when the request carried none
     * @param rawPerPage the {@code per_page} query parameter exactly as the request carried it, or
     *                   {@code null} when the request carried none
     * @return 200 carrying the {@code responses} and {@code pagination} envelope
     */
    @GetMapping("/responses")
    public ResponseEntity<PaginatedResponsesDto> getResponses(
            @RequestParam(name = "page", required = false) String rawPage,
            @RequestParam(name = "per_page", required = false) String rawPerPage) {

        // backend/app/api/responses.py:L11-12 — request.args.get(..., type=int) returns the default
        // when the conversion raises — DL-217 — see docs/DECISION_LOG.md
        int page = QueryParameters.intOrDefault(rawPage, DEFAULT_PAGE);
        int perPage = QueryParameters.intOrDefault(rawPerPage, DEFAULT_PER_PAGE);

        // backend/app/api/responses.py:L15 — constructed per request in the source
        return ResponseEntity.ok(responseService.getPaginatedResponses(page, perPage));
    }

    // Query-parameter conversion parity with request.args.get(..., type=int) at
    // backend/app/api/responses.py:L12-13 — see docs/DECISION_LOG.md DL-048

    // backend/app/api/responses.py:L22 — String path variable — DL-048 — 404 literal :L31
    /**
     * Renders the single {@code responses} row named by the path.
     *
     * <p>Reproduces {@code GET /responses/&lt;response_id&gt;} at
     * {@code backend/app/api/responses.py:L22-31}. The path-variable name is {@code responseId},
     * carrying the segment the source route spelled {@code response_id}, bound as a {@code String} —
     * DL-048.
     *
     * <p>A segment of any spelling reaches the service. A segment carrying no number and a number
     * naming no row are both answered with 404 and the literal of {@code :L31}, which is a different
     * string from the one {@link #updateResponse(String, UpdateResponseRequest)} reports.
     *
     * @param responseId the raw path segment identifying the row
     * @return 200 carrying the stored row
     */
    @GetMapping("/responses/{responseId}")
    public ResponseEntity<ResponseDto> getResponse(@PathVariable("responseId") String responseId) {
        return ResponseEntity.ok(responseService.getResponseById(responseId));
    }

    // Ported from backend/app/api/responses.py:L33-49 (faithful port) — DL-050, DL-076, DL-240 — see
    // docs/DECISION_LOG.md
    /**
     * Generates a reply to one {@code tweets} row, stores it, and renders the stored row.
     *
     * <p>Reproduces {@code POST /responses} at {@code backend/app/api/responses.py:L33-49}. The body
     * carries one member, {@code tweet_id}, read at {@code :L38} and declared by
     * {@link CreateResponseRequest} with {@code @NotNull} as its only constraint — see
     * docs/DECISION_LOG.md DL-050.
     *
     * <p>The success status is 201, as at {@code :L47}, and the body is the stored row carrying the
     * {@code id} the database assigned. No {@code Location} header is set, as at {@code :L47}. The
     * stored row carries {@code is_approved} {@code false}.
     *
     * <p>An absent body binds to {@code null} and reaches the service as {@code null}. A body carrying
     * no {@code tweet_id} member fails the {@code @NotNull} constraint and is answered with 400 and
     * the literal of {@code :L41}.
     *
     * <p>Every carried value is read through {@link CreateResponseRequest#usableTweetId()}, which
     * applies the {@code if not tweet_id} guard at {@code :L40} to the raw JSON value: {@code null},
     * {@code ""}, {@code 0}, {@code 0.0}, {@code -0.0}, {@code false}, {@code []} and {@code {}} each
     * reach the service as {@code null} and are answered with 400 and that same literal, before any
     * identifier is parsed and before generation is attempted — DL-240. Every other value reaches
     * generation as text.
     *
     * <p>This route declares no 404 branch: every failure past the guard is answered with 500 and the
     * literal of {@code :L49} — DL-076.
     *
     * @param request the request body; {@code null} when the request carried no body
     * @return 201 carrying the stored row
     */
    @PostMapping("/responses")
    public ResponseEntity<ResponseDto> generateResponse(
            @Valid @RequestBody(required = false) CreateResponseRequest request) {
        // backend/app/api/responses.py:L38,L40 — the guard reads the decoded value — DL-240
        String tweetId = (request == null) ? null : request.usableTweetId();

        ResponseDto generated = responseService.generateResponse(tweetId);

        return ResponseEntity.status(HttpStatus.CREATED).body(generated);
    }

    // Ported from backend/app/api/responses.py:L51-65 (faithful port); the binding-time rejection of a
    // wrong-typed member is net-new — DL-048, DL-050, DL-231 — see docs/DECISION_LOG.md
    /**
     * Applies a partial update to one {@code responses} row and renders the stored row.
     *
     * <p>Reproduces {@code PUT /responses/&lt;response_id&gt;} at
     * {@code backend/app/api/responses.py:L51-65}. The path-variable name is {@code responseId},
     * carrying the segment the source route spelled {@code response_id}, bound as a {@code String} —
     * DL-048.
     *
     * <p>The two updatable properties are {@code content} and {@code is_approved}, declared by
     * {@link UpdateResponseRequest}. No other property is bound or forwarded: {@code id},
     * {@code generated_at} and {@code tweet_id} are not writable through this route.
     *
     * <p>Two distinct mechanisms govern the body, and only the first is Bean Validation.
     * {@link UpdateResponseRequest} declares no Bean Validation constraint and this parameter declares
     * no {@code @Valid} annotation, matching the free-form {@code request.json} read at {@code :L54} —
     * see docs/DECISION_LOG.md DL-050. Separately, and net-new, the record's canonical constructor
     * rejects at binding time a carried key whose value the addressed column cannot hold — a
     * {@code content} that is not a JSON string, an {@code is_approved} that is not a JSON boolean, and
     * an explicit JSON {@code null} for either — which the converter reports as 400
     * {@code {"error": "Bad request"}} — see docs/DECISION_LOG.md DL-231.
     *
     * <p>Neither value the body carries is trimmed, defaulted or coerced. An absent body binds to
     * {@code null}. Reproducing the {@code if not update_data} guard at {@code :L56}, an absent body
     * and a body carrying neither key are both answered with 400 and the literal of {@code :L57}.
     *
     * <p>A {@code responseId} carrying no number and one naming no row are both answered with 404 and
     * the literal of {@code :L65}, {@code Response not found or update failed} — a different string
     * from the {@code Response not found} that {@link #getResponse(String)} reports at {@code :L31}.
     *
     * @param responseId the raw path segment identifying the row
     * @param request    the columns to write; {@code null} when the request carried no body
     * @return 200 carrying the stored row
     */
    @PutMapping("/responses/{responseId}")
    public ResponseEntity<ResponseDto> updateResponse(
            @PathVariable("responseId") String responseId,
            @RequestBody(required = false) UpdateResponseRequest request) {
        return ResponseEntity.ok(responseService.updateResponse(responseId, request));
    }
}
