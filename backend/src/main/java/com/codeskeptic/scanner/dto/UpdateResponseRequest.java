package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>Both components are optional and no validation constraint is declared: the source handler
 * extracted no named field from the body ({@code backend/app/api/responses.py:L54}) — see
 * docs/DECISION_LOG.md DL-050. Wire keys stay snake_case, and the two updatable properties are the
 * {@code content} and {@code is_approved} columns of the {@code responses} table
 * ({@code backend/app/db/models.py:L24,L26}). No identifier is carried in the body.
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
