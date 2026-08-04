package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

// Ported from backend/app/api/settings.py:L16,L17-18 (faithful port) — see docs/DECISION_LOG.md DL-050
/**
 * Inbound request body for {@code PUT /settings/{key}}.
 *
 * <p>One component, {@code value} — the single member the source route read from the body
 * ({@code backend/app/api/settings.py:L16}). The addressed setting is identified by the {@code key}
 * path variable, so this body carries neither {@code key} nor {@code description}.
 *
 * <p>{@code @NotNull} on {@link #value()} is the only constraint declared and corresponds to the
 * {@code if new_value is None:} guard at {@code backend/app/api/settings.py:L17} and its
 * {@code {"error": "No value provided"}} 400 response at {@code :L18}.
 *
 * <p>Wire shape: {@code {"value": "100"}}
 *
 * @param value new value for the addressed setting, carried on the JSON key {@code value}; required
 */
public record UpdateSettingRequest(
        @NotNull
        @JsonProperty("value")
        String value
) {
}
