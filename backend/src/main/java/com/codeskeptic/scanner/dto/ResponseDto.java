package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Outbound wire contract for a stored reply: five components in source declaration order, each JSON key
 * fixed by an explicit {@link JsonProperty} and snake_case, with both identifiers typed as strings —
 * DL-022, DL-023.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080 and DL-081. Every {@code responses} column except
 * the primary key is nullable, and this record carries a {@code null} column value to the wire as
 * JSON {@code null}, which is what {@code response.to_dict()} at
 * {@code backend/app/api/responses.py:L18,L29} produced for a column holding {@code None}. The
 * canonical constructor therefore rejects exactly one component, {@code id}, which is both the
 * primary key a stored row always carries and the marker of an unstored value:
 * {@code service.LlmService} returns generated text, and only after persistence assigns the
 * identifier does {@code service.mapper.ResponseMapper} read the row and construct this record.
 * {@code content}, {@code generatedAt}, {@code isApproved} and {@code tweetId} may each be
 * {@code null}. A {@code null} {@code isApproved} is the state {@code GET /analytics/summary} counts
 * as pending — see docs/DECISION_LOG.md DL-041. The {@code responses} columns stay nullable — see
 * docs/DECISION_LOG.md DL-080.
 *
 * <p>A row that carries no approval flag and no association renders the same shape with those two
 * members as JSON {@code null}:
 * {@code {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":null,"tweet_id":null}}
 *
 * <p>{@code is_approved} carries the approval flag a human reads
 * ({@code backend/app/db/models.py:L26}).
 *
 * <pre>{@code
 * {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}
 * }</pre>
 *
 * @param id          backend/app/schema/response.py:L5 - {@code id: str}; never {@code null}
 * @param content     backend/app/schema/response.py:L6 - {@code content: str}; may be {@code null}
 *                    when the column holds none
 * @param generatedAt backend/app/schema/response.py:L7 - {@code generated_at: datetime}; may be
 *                    {@code null} when the column holds none
 * @param isApproved  backend/app/schema/response.py:L8 - {@code is_approved: bool}; may be
 *                    {@code null}, which is the pending state of DL-041
 * @param tweetId     backend/app/schema/response.py:L9 - {@code tweet_id: str}; may be {@code null}
 *                    when the row carries no association
 */
// Ported from backend/app/schema/response.py:L4-9 (faithful port) — see docs/DECISION_LOG.md
// The null policy that carries an empty column to the wire as JSON null, and the stored-row
// identifier invariant, are recorded as DL-080 and DL-081 — see docs/DECISION_LOG.md
public record ResponseDto(
        @JsonProperty("id") String id,
        @JsonProperty("content") String content,
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        @JsonProperty("is_approved") Boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {

    /**
     * Rejects a {@code null} identifier, which is an unstored response carrying no assigned
     * identifier.
     *
     * <p>{@code id} is the only rejected component; every other component is carried exactly as the
     * column holds it, {@code null} included, and none is defaulted, trimmed or substituted — see
     * docs/DECISION_LOG.md DL-080.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    // The wire form of a stored row, whose nullable columns carry through as JSON null — DL-080,
    // DL-081 — see docs/DECISION_LOG.md
    public ResponseDto {
        Objects.requireNonNull(id, "id must not be null.");
    }
}
