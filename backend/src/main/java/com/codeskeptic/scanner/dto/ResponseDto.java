package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Outbound wire contract for a stored reply.
 *
 * <p>Five components in source declaration order. Each JSON key is fixed by an explicit
 * {@link JsonProperty}, is snake_case, and both identifiers are typed as strings — see
 * docs/DECISION_LOG.md DL-022 and DL-023.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080 and DL-081. This is the wire form of a stored row,
 * so {@code id} is never {@code null}; the canonical constructor rejects an unstored value.
 * {@code service.LlmService} returns generated text. After persistence assigns the identifier,
 * {@code service.mapper.ResponseMapper} validates the remaining source-required fields and constructs
 * this record.
 *
 * <p>Serialised form:
 * {@code {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}}
 *
 * <p>{@code is_approved} carries the approval flag a human reads
 * ({@code backend/app/db/models.py:L26}).
 *
 * @param id          backend/app/schema/response.py:L5 - {@code id: str}; never {@code null}
 * @param content     backend/app/schema/response.py:L6 - {@code content: str}
 * @param generatedAt backend/app/schema/response.py:L7 - {@code generated_at: datetime}
 * @param isApproved  backend/app/schema/response.py:L8 - {@code is_approved: bool}
 * @param tweetId     backend/app/schema/response.py:L9 - {@code tweet_id: str}
 */
// Ported from backend/app/schema/response.py:L4-9 (faithful port) — see docs/DECISION_LOG.md
// The stored-row identifier invariant is recorded as DL-080 and DL-081 — see docs/DECISION_LOG.md
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("content") String content,
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        @JsonProperty("is_approved") Boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {

    /**
     * Rejects an unstored response carrying no assigned identifier.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public ResponseDto {
        Objects.requireNonNull(id, "id must not be null.");
    }
}
