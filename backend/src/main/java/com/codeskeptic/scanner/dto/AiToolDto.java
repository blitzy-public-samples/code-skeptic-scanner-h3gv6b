package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire contract for a single tracked AI coding tool.
 *
 * <p>Mirrors the three columns of the {@code ai_tools} table, which declares no association. The
 * source class was named {@code AITool}. {@code id} is the primary key and is carried as a
 * {@link String} at this boundary — see docs/DECISION_LOG.md DL-023. Every component is nullable and
 * no validation constraint is declared — see docs/DECISION_LOG.md DL-050.
 *
 * <p>Serialised form: {@code {"id":"1","name":"GPT-4","description":"Large language model"}}
 *
 * @param id          the {@code ai_tools.id} primary-key column
 *                    (backend/app/db/models.py:L35), rendered as a string
 * @param name        the {@code ai_tools.name} column
 *                    (backend/app/db/models.py:L36)
 * @param description the {@code ai_tools.description} column
 *                    (backend/app/db/models.py:L37)
 */
// Ported from backend/app/db/models.py:L32-37 (faithful port of the ai_tools columns) — see docs/DECISION_LOG.md
public record AiToolDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description) {
}
