package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

// Ported from backend/app/api/settings.py:L16,L17-18 (faithful port) — see docs/DECISION_LOG.md DL-050
/**
 * Inbound request body for {@code PUT /settings/{key}}.
 *
 * <p>The route reads a single member, {@code value}, from the request body
 * (backend/app/api/settings.py:L16), and updates the value only (:L20). The setting being
 * addressed is identified by the {@code key} path variable on the route
 * {@code '/settings/<key>'} (:L13); this body carries neither {@code key} nor
 * {@code description}.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * {"value": "100"}
 * }</pre>
 *
 * <p>The {@code @NotNull} on {@link #value()} corresponds to the {@code if new_value is None:}
 * guard at :L17 and the {@code {"error": "No value provided"}} 400 response at :L18. It is the
 * only constraint declared on this record.
 *
 * @param value new value for the addressed setting, carried on the JSON key {@code value};
 *              the stored column is {@code value = Column(String)} at
 *              backend/app/db/models.py:L43
 */
public record UpdateSettingRequest(
        @NotNull
        @JsonProperty("value")
        String value
) {
}
