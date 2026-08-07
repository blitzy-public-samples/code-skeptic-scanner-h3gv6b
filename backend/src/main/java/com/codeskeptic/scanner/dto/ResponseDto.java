package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Outbound wire contract for a stored reply: five components in source declaration order, each JSON key
 * fixed by an explicit {@link JsonProperty} and snake_case, with both identifiers typed as strings —
 * DL-022, DL-023.
 *
 * <p>Null policy — DL-080, DL-081. {@code backend/app/schema/response.py:L5-9} declares all five fields
 * required and none {@code Optional}, so the canonical constructor rejects a {@code null} for every
 * component. Being the wire form of a stored row, the {@code id} rejection additionally excludes an
 * unstored value: {@code service.LlmService} returns generated text, and only after persistence assigns
 * the identifier does {@code service.mapper.ResponseMapper} read the row and construct this record. The
 * {@code responses} columns stay nullable.
 *
 * <p>{@code is_approved} carries the approval flag a human reads
 * ({@code backend/app/db/models.py:L26}).
 *
 * <pre>{@code
 * {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}
 * }</pre>
 *
 * @param id          {@code backend/app/schema/response.py:L5}
 * @param content     {@code :L6}
 * @param generatedAt {@code :L7}
 * @param isApproved  {@code :L8}
 * @param tweetId     {@code :L9}
 */
// Ported from backend/app/schema/response.py:L4-9 (faithful port) — see docs/DECISION_LOG.md
// The required-versus-optional contract of AAP TR-6 and the stored-row identifier invariant are
// recorded as DL-080 and DL-081 — see docs/DECISION_LOG.md
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("content") String content,
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        @JsonProperty("is_approved") Boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {

    /**
     * Rejects a {@code null} value for any of the five components the source schema declares
     * required, which for {@code id} also rejects an unstored response carrying no assigned
     * identifier.
     *
     * <p>No component is defaulted, trimmed or substituted.
     *
     * @throws NullPointerException if {@code id}, {@code content}, {@code generatedAt},
     *     {@code isApproved} or {@code tweetId} is {@code null}
     */
    // The required fields of backend/app/schema/response.py:L5-9 — AAP TR-6, DL-080 — see
    // docs/DECISION_LOG.md
    public ResponseDto {
        Objects.requireNonNull(id, "id must not be null.");
        Objects.requireNonNull(content, "content must not be null.");
        Objects.requireNonNull(generatedAt, "generated_at must not be null.");
        Objects.requireNonNull(isApproved, "is_approved must not be null.");
        Objects.requireNonNull(tweetId, "tweet_id must not be null.");
    }
}
