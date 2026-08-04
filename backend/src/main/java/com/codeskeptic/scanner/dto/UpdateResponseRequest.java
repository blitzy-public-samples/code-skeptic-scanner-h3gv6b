package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>Both components are optional. The source handler extracted no named field from
 * the body, and its {@code "Update data is required"} 400 fires when the body itself
 * is absent or empty ({@code backend/app/api/responses.py:L54,L56-57}).
 *
 * <p>The updatable properties are the {@code content} and {@code is_approved}
 * columns of the {@code responses} table
 * ({@code backend/app/db/models.py:L24,L26}). The record carries no identifier:
 * {@code responseId} arrives as a path variable, while {@code generated_at} and
 * {@code tweet_id} are not part of this body.
 *
 * <p>Wire keys stay snake_case: {@code content} and {@code is_approved}.
 *
 * @param content    replacement response text; {@code null} when the request body
 *                   does not carry the {@code content} key
 * @param isApproved human approval flag; {@code null} when the request body does not
 *                   carry the {@code is_approved} key
 */
// Ported from backend/app/api/responses.py:L54 (faithful port; source reads request.json free-form) — see docs/DECISION_LOG.md DL-050
public record UpdateResponseRequest(

        @JsonProperty("content")
        String content,

        @JsonProperty("is_approved")
        Boolean isApproved

) {
}
