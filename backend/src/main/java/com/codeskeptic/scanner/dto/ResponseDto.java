package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Outbound wire contract for a generated reply.
 *
 * <p>Five components in source declaration order. All five were required in the source model, which
 * declared no {@code Optional[...]} component and no default; this record declares no validation
 * annotation and carries requiredness in the canonical constructor instead — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>{@code isApproved} is a primitive. {@code content}, {@code generatedAt} and {@code tweetId}
 * reject {@code null}. {@code id} accepts {@code null}, which is its value on a generation result
 * that has not been persisted yet — see docs/DECISION_LOG.md DL-023.
 *
 * <p>Each JSON key is fixed by an explicit {@link JsonProperty}, is snake_case, and both identifiers
 * are typed as strings — see docs/DECISION_LOG.md DL-022 and DL-023.
 *
 * <p>Serialised form:
 * {@code {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}}
 *
 * <p>{@code is_approved} carries the approval flag for a human to read
 * ({@code backend/app/db/models.py:L26}). No code path in this application publishes to X.
 *
 * @param id          backend/app/schema/response.py:L5 - {@code id: str}; {@code null} until the
 *                    {@code responses} row carries a key
 * @param content     backend/app/schema/response.py:L6 - {@code content: str}
 * @param generatedAt backend/app/schema/response.py:L7 -
 *                    {@code generated_at: datetime}
 * @param isApproved  backend/app/schema/response.py:L8 -
 *                    {@code is_approved: bool}
 * @param tweetId     backend/app/schema/response.py:L9 - {@code tweet_id: str}
 */
// Ported from backend/app/schema/response.py:L4-9 (faithful port) — see docs/DECISION_LOG.md
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("content") String content,
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        @JsonProperty("is_approved") boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {

    /**
     * Rejects a null value for {@code content}, {@code generatedAt} and {@code tweetId}.
     *
     * <p>{@code id} is not checked. It is {@code null} on a generation result that has not been
     * persisted yet, which is what {@code service.LlmService.generateResponse} returns and what
     * {@code backend/app/services/llm_service.py:L32} also left unset; it is populated by
     * {@code service.mapper.ResponseMapper} once the {@code responses} row carries a key.
     *
     * @throws NullPointerException if {@code content}, {@code generatedAt} or {@code tweetId} is
     *                             null
     */
    public ResponseDto {
        Objects.requireNonNull(content, "content must not be null.");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null.");
        Objects.requireNonNull(tweetId, "tweetId must not be null.");
    }
}
