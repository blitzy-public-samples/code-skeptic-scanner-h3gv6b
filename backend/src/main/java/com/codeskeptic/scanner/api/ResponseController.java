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

// Endpoint contract ported from backend/app/api/responses.py:L8-65 (faithful port) — see
// docs/DECISION_LOG.md DL-021, DL-022, DL-023, DL-038, DL-048, DL-050, DL-059, DL-076, DL-092
/**
 * Serves the four HTTP routes of the {@code responses} resource, all unprefixed — no {@code /api}
 * segment and no version segment (DL-059). Replaces the Flask blueprint {@code responses_bp} declared
 * at {@code backend/app/api/responses.py:L6} and registered at {@code backend/app/main.py:L27}.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr><td>{@code GET /responses}</td><td>{@link #getResponses(String, String)}</td>
 *   <td>200, a two-key envelope</td><td>{@code backend/app/api/responses.py:L8-20}</td></tr>
 * <tr><td>{@code GET /responses/{responseId}}</td><td>{@link #getResponse(String)}</td>
 *   <td>200, one JSON object</td><td>{@code backend/app/api/responses.py:L22-31}</td></tr>
 * <tr><td>{@code POST /responses}</td><td>{@link #generateResponse(CreateResponseRequest)}</td>
 *   <td>201, one JSON object</td><td>{@code backend/app/api/responses.py:L33-49}</td></tr>
 * <tr><td>{@code PUT /responses/{responseId}}</td>
 *   <td>{@link #updateResponse(String, UpdateResponseRequest)}</td>
 *   <td>200, one JSON object</td><td>{@code backend/app/api/responses.py:L51-65}</td></tr>
 * </table>
 *
 * <p>Wire keys are snake_case, fixed by an explicit {@code @JsonProperty} on each record component
 * (DL-022), and both identifiers of {@link ResponseDto} are carried as strings (DL-023). No entity
 * type is rendered or referenced here; the {@code responses} table is reached through
 * {@code service.ResponseService} only.
 *
 * <p>This class selects the success status and builds no error body. The five error statuses of the
 * source routes are raised by {@code service.ResponseService} and answered by
 * {@link GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>404 {@code Response not found} ({@code :L31}) for a {@code responseId} naming no row on
 *       {@code GET /responses/{responseId}}.</li>
 *   <li>400 {@code Tweet ID is required} ({@code :L41}) for every {@code tweet_id} value the guard at
 *       {@code :L40} read as false — {@code null}, {@code ""}, a zero, {@code false}, an empty array,
 *       an empty object. A body carrying no {@code tweet_id} member raises
 *       {@code MethodArgumentNotValidException} instead, answered with the same status and literal —
 *       DL-286.</li>
 *   <li>500 {@code Failed to generate response} ({@code :L49}) for every failure past that guard.
 *       {@code POST /responses} declares no 404 branch: a {@code tweet_id} carrying no number and one
 *       naming no {@code tweets} row both answer with this literal — DL-076.</li>
 *   <li>400 {@code Update data is required} ({@code :L57}) for an absent body and a body carrying
 *       neither updatable key on {@code PUT /responses/{responseId}}.</li>
 *   <li>404 {@code Response not found or update failed} ({@code :L65}) for a {@code responseId} naming
 *       no row on {@code PUT /responses/{responseId}}.</li>
 * </ul>
 *
 * <p>Both path variables bind as {@link String}, the type Flask's default path converter delivered at
 * {@code :L22} and {@code :L51} — DL-048. {@code page} and {@code per_page} also bind as
 * {@link String} and are read as whole numbers here, substituting the declared default for an absent,
 * blank or non-numeric value as {@code request.args.get(..., type=int)} did at {@code :L11-12}; no
 * query parameter on these routes produces an error status — DL-217.
 *
 * <p>The service is an injected singleton in a final field, in place of the per-request
 * {@code ResponseService()} at {@code :L14}, {@code :L25}, {@code :L43} and {@code :L59}; the
 * conversion invoked as {@code response.to_dict()} at {@code :L18}, {@code :L29}, {@code :L47} and
 * {@code :L63} happens in {@code service.mapper.ResponseMapper} behind it and never here.
 * Authentication is enforced by the security filter chain, in place of the bare {@code @jwt_required}
 * at {@code :L9}, {@code :L23}, {@code :L34} and {@code :L52} — DL-021.
 *
 * <p>No handler here writes to the X API and {@code service.ResponseService} declares no publish
 * operation. {@code is_approved} ({@code backend/app/db/models.py:L26}) is a flag a human reviewer
 * reads and no handler acts on its value.
 *
 * <p>Singleton bean holding one collaborator in a final field and no other state, so every member
 * declared here is safe for concurrent use.
 */
@RestController
public class ResponseController {

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_PER_PAGE = 10;

    private final ResponseService responseService;

    public ResponseController(ResponseService responseService) {
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
    }

    // Ported from backend/app/api/responses.py:L8-20 (faithful port) — DL-038, DL-217 — see
    // docs/DECISION_LOG.md
    /**
     * Renders one page of the {@code responses} table together with the block that describes it.
     *
     * <p>Reproduces {@code GET /responses} at {@code backend/app/api/responses.py:L8-20}. The body
     * carries {@code responses}, a JSON array of {@link ResponseDto} objects, and {@code pagination},
     * whose keys are {@code page}, {@code per_page}, {@code total} and {@code total_pages} — DL-038. An
     * empty page renders an empty array and the status is 200 either way.
     *
     * <p>The parameters are {@code page} ({@code :L11}, default 1) and {@code per_page} ({@code :L12},
     * default 10); {@code per_page} carries no camelCase alias — DL-059. Each is bound as text and read
     * as a whole number after trimming, and a value holding no whole number takes the same default an
     * absent parameter takes, and is not reported as a client error — DL-217. A value that does
     * hold a whole number reaches the service unchanged, {@code 0} and negatives included: nothing is
     * clamped here. The 1-based to 0-based conversion, the page-size floor and the reported counters
     * belong to {@code service.ResponseService} — DL-038, DL-217.
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
        int page = intOrDefault(rawPage, DEFAULT_PAGE);
        int perPage = intOrDefault(rawPerPage, DEFAULT_PER_PAGE);

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

    // Ported from backend/app/api/responses.py:L33-49 (faithful port) — DL-050, DL-076, DL-286 — see
    // docs/DECISION_LOG.md
    /**
     * Generates a reply to one {@code tweets} row, stores it, and renders the stored row.
     *
     * <p>Reproduces {@code POST /responses} at {@code backend/app/api/responses.py:L33-49}. The body
     * carries one member, {@code tweet_id}, read at {@code :L38} and declared by
     * {@link CreateResponseRequest} with {@code @NotNull} as its only constraint — DL-050. The success
     * status is 201, as at {@code :L47}, with no {@code Location} header, and the stored row carries
     * {@code is_approved} {@code false}.
     *
     * <p>A body carrying no {@code tweet_id} member fails that constraint. Every carried value is read
     * through {@link CreateResponseRequest#usableTweetId()}, which applies the {@code if not tweet_id}
     * guard at {@code :L40} to the raw JSON value: {@code null}, {@code ""}, {@code 0}, {@code 0.0},
     * {@code -0.0}, {@code false}, {@code []} and {@code {}} each reach the service as {@code null}
     * before any identifier is parsed and before generation is attempted — DL-286. Both paths answer
     * 400 with the literal of {@code :L41}; every other value reaches generation as text.
     *
     * <p>This route declares no 404 branch: every failure past the guard answers 500 with the literal
     * of {@code :L49} — DL-076.
     *
     * @param request the request body; {@code null} when the request carried no body
     * @return 201 carrying the stored row
     */
    @PostMapping("/responses")
    public ResponseEntity<ResponseDto> generateResponse(
            @Valid @RequestBody(required = false) CreateResponseRequest request) {
        // backend/app/api/responses.py:L38,L40 — the guard reads the decoded value — DL-286
        String tweetId = (request == null) ? null : request.usableTweetId();

        ResponseDto generated = responseService.generateResponse(tweetId);

        return ResponseEntity.status(HttpStatus.CREATED).body(generated);
    }

    // Ported from backend/app/api/responses.py:L51-65 (faithful port); the binding-time rejection of a
    // wrong-typed member is net-new — DL-048, DL-050, DL-082, DL-231, DL-244 — see
    // docs/DECISION_LOG.md
    /**
     * Applies a partial update to one {@code responses} row and renders the stored row.
     *
     * <p>Reproduces {@code PUT /responses/&lt;response_id&gt;} at
     * {@code backend/app/api/responses.py:L51-65}. The path variable binds as a {@link String} —
     * DL-048. The two updatable properties are {@code content} and {@code is_approved}; {@code id},
     * {@code generated_at} and {@code tweet_id} are not writable through this route.
     *
     * <p>The body is governed by one mechanism, and it is not Bean Validation:
     * {@link UpdateResponseRequest} declares no constraint and this parameter declares no
     * {@code @Valid}, matching the free-form {@code request.json} read at {@code :L54} — DL-050. The
     * presence of a key decides which column is written and the carried value decides what is stored,
     * an explicit JSON {@code null} included, and a value of any JSON type is accepted — DL-082,
     * DL-231, DL-244. Nothing is trimmed, defaulted or coerced.
     *
     * <p>Reproducing the {@code if not update_data} guard at {@code :L56}, an absent body and a body
     * carrying neither key are both answered with 400 and the literal of {@code :L57}.
     *
     * <p>A {@code responseId} carrying no number, one naming no row, and a write that leaves
     * {@code content} or {@code is_approved} empty are all answered with 404 and the literal of
     * {@code :L65}, {@code Response not found or update failed}; {@link #getResponse(String)} reports
     * the different string of {@code :L31}. The third case rolls the transaction back — DL-244.
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

    // Query-parameter conversion of request.args.get(..., type=int) at
    // backend/app/api/tweets.py:L12-13 — see docs/DECISION_LOG.md DL-217
    /**
     * Converts one raw query-parameter value into an {@code int}.
     *
     * <p>The default is returned for a {@code null} value, which is an absent parameter; for a blank
     * value, which is a parameter present with nothing after the {@code =}; for a value carrying any
     * character a decimal {@code int} cannot hold, which includes a fractional value, a hexadecimal
     * value and a value carrying a unit; and for a value beyond the range of an {@code int}. That is
     * the fallback behaviour of Werkzeug's {@code type=int} conversion, which the retired handlers
     * relied on. Surrounding whitespace is discarded and a leading sign is accepted.
     *
     * @param rawValue     the value as the request carried it, or {@code null} when the request
     *                     carried none
     * @param defaultValue the value to return when {@code rawValue} carries no {@code int}
     * @return the converted value, or {@code defaultValue}
     */
    private static int intOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notAnInteger) {
            return defaultValue;
        }
    }

}
