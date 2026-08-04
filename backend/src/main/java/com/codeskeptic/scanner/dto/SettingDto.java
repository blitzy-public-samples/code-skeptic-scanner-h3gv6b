package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire representation of a single row of the {@code settings} table — see docs/DECISION_LOG.md
 * DL-039.
 *
 * <p>The three components correspond one-to-one, in declaration order, to the three columns the
 * {@code settings} table declares, each {@code Column(String)}. Every component is a nullable
 * {@code String}. This record is outbound only and declares no validation constraint; the inbound
 * counterpart is {@link UpdateSettingRequest}.
 *
 * <p>Serialised form: {@code {"key":"tweet_popularity_threshold","value":"100","description":"..."}}
 *
 * @param key         the {@code settings} primary key, unchanged on the wire
 * @param value       the stored value of the setting
 * @param description the human-readable description of the setting
 */
// Ported from backend/app/db/models.py:L39-44 (faithful port of the settings columns) — see docs/DECISION_LOG.md
public record SettingDto(

        @JsonProperty("key") String key,

        @JsonProperty("value") String value,

        @JsonProperty("description") String description) {
}
