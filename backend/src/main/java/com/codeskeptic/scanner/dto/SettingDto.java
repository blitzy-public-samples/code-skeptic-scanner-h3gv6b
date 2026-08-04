package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire representation of a single row of the {@code settings} table.
 *
 * <p>Two endpoints emit this type:</p>
 * <ul>
 *   <li>{@code GET /settings} — the element type of the returned JSON array
 *       (see docs/DECISION_LOG.md DL-039).</li>
 *   <li>{@code PUT /settings/{key}} — the body of the 200 response.</li>
 * </ul>
 *
 * <p>The three components correspond one-to-one, in declaration order, to the three
 * columns the {@code settings} table declares. Every column is declared
 * {@code Column(String)}. Every component is a nullable {@code String}. This record is
 * outbound only and carries no validation constraints; the inbound counterpart for
 * {@code PUT /settings/{key}} is the separate {@code UpdateSettingRequest} record.</p>
 *
 * <p>A {@code GET /settings} payload serialises as:</p>
 * <pre>{@code
 * [{"key":"tweet_popularity_threshold","value":"100","description":"..."}]
 * }</pre>
 *
 * <p>Instances are produced from the {@code Setting} entity by
 * {@code com.codeskeptic.scanner.service.mapper.SettingMapper}; this record holds no
 * reference to the persistence layer.</p>
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
