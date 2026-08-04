package com.codeskeptic.scanner.api;

import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.dto.UpdateSettingRequest;
import com.codeskeptic.scanner.service.SettingsService;

// Ported from backend/app/api/settings.py:L1-24 (faithful port) — see docs/DECISION_LOG.md
/**
 * Serves the two HTTP routes of the {@code settings} resource.
 *
 * <p>Replaces the Flask blueprint {@code settings_bp}, declared at
 * {@code backend/app/api/settings.py:L5} and registered on the application object at
 * {@code backend/app/main.py:L28}. The two routes declared here are the two the blueprint declared.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code GET /settings}</td>
 *   <td>{@link #getSettings()}</td>
 *   <td>200, a JSON array</td>
 *   <td>{@code backend/app/api/settings.py:L7-11}</td>
 * </tr>
 * <tr>
 *   <td>{@code PUT /settings/{key}}</td>
 *   <td>{@link #updateSetting(String, UpdateSettingRequest)}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/settings.py:L13-24}</td>
 * </tr>
 * </table>
 *
 * <p>Both paths are unprefixed, as the blueprint spelled them: no {@code /api} segment and no version
 * segment. Each path is spelled in full on its own handler.
 *
 * <p>{@code GET /settings} renders a JSON array whose elements are {@link SettingDto} objects, each
 * carrying {@code key}, {@code value} and {@code description}, unwrapped, matching the bare
 * {@code jsonify(settings)} at {@code backend/app/api/settings.py:L11} — see docs/DECISION_LOG.md
 * DL-039.
 *
 * <p>{@code PUT /settings/{key}} addresses a row that already exists: a {@code key} naming no row is
 * reported as absent and no row is created for it. The default rows the route addresses are seeded by
 * {@code service.SettingsService} — see docs/DECISION_LOG.md DL-040.
 *
 * <p>The guard at {@code backend/app/api/settings.py:L17} is {@code if new_value is None:}, a test for
 * {@code null} alone, so an empty string, {@code "false"} and {@code "0"} are all accepted;
 * {@code dto.UpdateSettingRequest} declares {@code @NotNull} and no other constraint — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>This class selects the status 200 and builds no error body. The two error statuses of the source
 * route are produced away from here:
 *
 * <ul>
 *   <li>{@code BadRequestException} carrying {@code No value provided}, the wire literal of
 *       {@code backend/app/api/settings.py:L18}, is raised by
 *       {@code service.SettingsService.updateSetting} for a {@code null} value, and
 *       {@code MethodArgumentNotValidException} is raised for a body whose {@code value} member is
 *       absent. {@link GlobalExceptionHandler} answers both with 400 and the same literal.</li>
 *   <li>{@code NotFoundException} carrying {@code Setting not found}, the wire literal of
 *       {@code backend/app/api/settings.py:L22}, is raised by
 *       {@code service.SettingsService.updateSetting} for a key naming no row.
 *       {@link GlobalExceptionHandler} answers it with 404.</li>
 * </ul>
 *
 * <p>{@code SettingsService.get_all_settings()} at {@code backend/app/api/settings.py:L10} and
 * {@code SettingsService.update_setting(key, new_value)} at {@code :L20} were invoked statically on
 * the class; both are instance calls on the injected singleton here — see docs/DECISION_LOG.md
 * DL-043.
 *
 * <p>Authentication is enforced by the security filter chain, which runs ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/settings.py:L8} and {@code :L14} — see docs/DECISION_LOG.md DL-021.
 *
 * <p>This class reaches the {@code settings} table through {@code service.SettingsService} only.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-039, DL-040,
 * DL-043, DL-048 and DL-050; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Its one collaborator is held in a final field and is itself a
 * singleton, and this class holds no other state, so every member declared here is safe for concurrent
 * use.
 */
@RestController
public class SettingController {

    /** Reads and updates the rows of the {@code settings} table. */
    private final SettingsService settingsService;

    /**
     * Creates the controller with its one collaborator, replacing the static invocation at
     * {@code backend/app/api/settings.py:L10,L20} — DL-043.
     *
     * @param settingsService the service serving both routes, must not be {@code null}
     * @throws NullPointerException when {@code settingsService} is {@code null}
     */
    public SettingController(SettingsService settingsService) {
        this.settingsService = Objects.requireNonNull(settingsService,
                "settingsService must not be null.");
    }

    // backend/app/api/settings.py:L7-11 — array response — DL-039
    /**
     * Renders every row of the {@code settings} table.
     *
     * <p>Reproduces {@code GET /settings} at {@code backend/app/api/settings.py:L7-11}. The body is a
     * JSON array; each element carries the {@code key}, {@code value} and {@code description} of one
     * row. An empty table renders an empty array, and the status is 200 either way.
     *
     * <p>The route reads no query parameter and no header, as at
     * {@code backend/app/api/settings.py:L9}.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * [
     *   {"key":"tweet_popularity_threshold","value":"100","description":"Minimum like count ..."},
     *   {"key":"response_generation_delay","value":"60","description":"Seconds between ..."}
     * ]
     * }</pre>
     *
     * @return 200 carrying one {@link SettingDto} per row, an empty list when the table holds no row
     */
    @GetMapping("/settings")
    public ResponseEntity<List<SettingDto>> getSettings() {
        // backend/app/api/settings.py:L10 — invoked statically on the class in the source — DL-043
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    /**
     * Replaces the {@code value} of one row of the {@code settings} table and renders the stored row.
     *
     * <p>Reproduces {@code PUT /settings/<key>} at {@code backend/app/api/settings.py:L13-24}. The
     * path-variable name is {@code key}, as at {@code :L13}, bound as a {@code String} — the type
     * Flask's default path converter delivered. A key of any spelling reaches the service and one
     * naming no row yields 404 — see docs/DECISION_LOG.md DL-048.
     *
     * <p>The body carries one member, {@code value}, read at {@code :L16}, and is passed to the service
     * verbatim: not trimmed, defaulted or coerced. An empty string is stored, matching the
     * {@code is None} test at {@code :L17}.
     *
     * <p>An absent body binds to {@code null} and reaches the service as a {@code null} value; a body
     * carrying no {@code value} member fails the {@code @NotNull} constraint of
     * {@link UpdateSettingRequest}. Both answer 400 with the literal of {@code :L18}.
     *
     * <p>Example request body:
     *
     * <pre>{@code {"value":"250"}}</pre>
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"key":"tweet_popularity_threshold","value":"250","description":"Minimum like count ..."}
     * }</pre>
     *
     * @param key     the primary key of the row to update, taken from the path
     * @param request the request body; {@code null} when the request carried no body
     * @return 200 carrying the stored row: {@code key} and {@code description} unchanged, {@code value}
     *         as supplied
     */
    // backend/app/api/settings.py:L13 — L17 guard is 'is None', not falsiness
    @PutMapping("/settings/{key}")
    public ResponseEntity<SettingDto> updateSetting(
            @PathVariable String key,
            @Valid @RequestBody(required = false) UpdateSettingRequest request) {
        // backend/app/api/settings.py:L16 — request.json.get('value')
        String value = (request == null) ? null : request.value();

        // backend/app/api/settings.py:L20 — invoked statically on the class in the source — DL-043
        return ResponseEntity.ok(settingsService.updateSetting(key, value));
    }
}
