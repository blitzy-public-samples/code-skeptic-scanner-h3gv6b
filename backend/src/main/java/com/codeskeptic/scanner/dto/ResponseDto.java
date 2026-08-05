package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Outbound wire contract for a stored reply.
 *
 * <p>Five components in source declaration order. Each JSON key is fixed by an explicit
 * {@link JsonProperty}, is snake_case, and both identifiers are typed as strings — see
 * docs/DECISION_LOG.md DL-022 and DL-023.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080 and DL-081. Every component accepts
 * {@code null}. {@code content}, {@code generatedAt}, {@code isApproved} and {@code tweetId} each map
 * to a column {@code backend/app/db/models.py:L23-28} declares without {@code nullable=False}, and a
 * {@code null} column is carried to the wire as JSON {@code null}; {@code isApproved} is boxed
 * accordingly. {@code id} is {@code null} on exactly one instance: the generation result
 * {@code service/LlmService} returns before the row exists, since the key is assigned by the database
 * on insert. Every instance that reaches the wire is produced by {@code service.mapper.ResponseMapper}
 * from a stored row and therefore carries the assigned key.
 *
 * <p>Serialised form:
 * {@code {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}}
 *
 * <p>{@code is_approved} carries the approval flag a human reads
 * ({@code backend/app/db/models.py:L26}).
 *
 * @param id          backend/app/schema/response.py:L5 - {@code id: str}; {@code null} only on an
 *                    unstored generation result
 * @param content     backend/app/schema/response.py:L6 - {@code content: str}; may be {@code null}
 * @param generatedAt backend/app/schema/response.py:L7 - {@code generated_at: datetime}; may be
 *                    {@code null}
 * @param isApproved  backend/app/schema/response.py:L8 - {@code is_approved: bool}; may be
 *                    {@code null}
 * @param tweetId     backend/app/schema/response.py:L9 - {@code tweet_id: str}; may be {@code null}
 */
// Ported from backend/app/schema/response.py:L4-9 (faithful port) — see docs/DECISION_LOG.md
// The null policy of every component is recorded as DL-080 and DL-081 — see docs/DECISION_LOG.md
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("content") String content,
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        @JsonProperty("is_approved") Boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {
}
