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
 * Serves the two HTTP routes of the {@code settings} resource, both unprefixed as the blueprint
 * spelled them. Replaces the Flask blueprint {@code settings_bp} declared at
 * {@code backend/app/api/settings.py:L5} and registered at {@code backend/app/main.py:L28}.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr><td>{@code GET /settings}</td><td>{@link #getSettings()}</td>
 *   <td>200, a JSON array</td><td>{@code backend/app/api/settings.py:L7-11}</td></tr>
 * <tr><td>{@code PUT /settings/{key}}</td>
 *   <td>{@link #updateSetting(String, UpdateSettingRequest)}</td>
 *   <td>200, one JSON object</td><td>{@code backend/app/api/settings.py:L13-24}</td></tr>
 * </table>
 *
 * <p>{@code GET /settings} renders an unwrapped JSON array of {@link SettingDto} objects, each carrying
 * {@code key}, {@code value} and {@code description}, matching the bare {@code jsonify(settings)} at
 * {@code :L11} — DL-039. {@code PUT /settings/{key}} addresses a row that already exists and creates
 * none; the default rows it addresses are seeded by {@code service.SettingsService} — DL-040.
 *
 * <p>The guard at {@code :L17} is {@code if new_value is None:}, a test for {@code null} alone, so an
 * empty string, {@code "false"} and {@code "0"} are all accepted; {@code dto.UpdateSettingRequest}
 * declares {@code @NotNull} and no other constraint — DL-050.
 *
 * <p>This class selects status 200 and builds no error body. {@code service.SettingsService.updateSetting}
 * raises {@code BadRequestException} carrying {@code No value provided} ({@code :L18}) for a
 * {@code null} value and {@code NotFoundException} carrying {@code Setting not found} ({@code :L22})
 * for a key naming no row; a body whose {@code value} member is absent raises
 * {@code MethodArgumentNotValidException}, which {@link GlobalExceptionHandler} answers with the same
 * 400 and literal.
 *
 * <p>Both service operations were invoked statically on the class at {@code :L10} and {@code :L20};
 * both are instance calls on the injected singleton here — DL-043. Authentication is enforced by the
 * security filter chain in place of the bare {@code @jwt_required} at {@code :L8} and {@code :L14} —
 * DL-021. The {@code settings} table is reached through {@code service.SettingsService} only.
 *
 * <p>Singleton bean, thread-safe, holding no mutable state.
 */
@RestController
public class SettingController {

    private final SettingsService settingsService;

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
     * row. An empty table renders an empty array and the status is 200 either way. The route reads no
     * query parameter and no header, as at {@code :L9}.
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
     * <p>Reproduces {@code PUT /settings/<key>} at {@code backend/app/api/settings.py:L13-24}. The path
     * variable binds as a {@link String}, the type Flask's default path converter delivered, so a key of
     * any spelling reaches the service and one naming no row yields 404 — DL-048.
     *
     * <p>The body's one member, {@code value}, read at {@code :L16}, is passed to the service verbatim:
     * not trimmed, defaulted or coerced, so an empty string is stored, matching the {@code is None} test
     * at {@code :L17}. An absent body binds to {@code null} and a body carrying no {@code value} member
     * fails the {@code @NotNull} constraint of {@link UpdateSettingRequest}; both answer 400 with the
     * literal of {@code :L18}.
     *
     * <pre>{@code {"value":"250"}}</pre>
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
