package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Outbound wire contract for a generated reply.
 *
 * <p>Declares the same five fields, in the same order, as the Pydantic model it
 * replaces. All five components are required. The source model declared no
 * {@code Optional[...]} component, no {@code Field(...)}, no {@code @validator},
 * no {@code Config} and no default; this record declares no validation
 * annotation. See docs/DECISION_LOG.md DL-050.</p>
 *
 * <p>JSON keys are snake_case and both identifiers are typed as strings, matching
 * the source model. Each key is fixed by an explicit {@link JsonProperty};
 * application.yml declares no {@code spring.jackson.property-naming-strategy}.
 * See docs/DECISION_LOG.md DL-022 and DL-023.</p>
 *
 * <p>Serialised form:
 * {@code {"id":"12","content":"...","generated_at":"2026-01-31T09:15:00","is_approved":false,"tweet_id":"7"}}</p>
 *
 * <p>This is the body of three responses on the {@code /responses} routes - the
 * 200 of {@code GET /responses/{responseId}}
 * (backend/app/api/responses.py:L29), the 201 of {@code POST /responses}
 * (backend/app/api/responses.py:L47) and the 200 of
 * {@code PUT /responses/{responseId}} (backend/app/api/responses.py:L63) - and
 * the element type of the {@code responses} array carried by
 * {@code PaginatedResponsesDto} (backend/app/api/responses.py:L18). The source
 * produced each of those bodies by calling a {@code to_dict()} that it never
 * defined. {@code service.mapper.ResponseMapper} performs the mapping onto this
 * record, including the {@code Integer} primary key to {@code String} wire
 * identifier conversion.</p>
 *
 * <p>{@code is_approved} carries the approval flag for a human to read
 * (backend/app/db/models.py:L26). No code path in this application publishes to
 * X. See docs/DECISION_LOG.md.</p>
 *
 * @param id          backend/app/schema/response.py:L5 - {@code id: str}
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
        @JsonProperty("is_approved") Boolean isApproved,
        @JsonProperty("tweet_id") String tweetId) {
}
