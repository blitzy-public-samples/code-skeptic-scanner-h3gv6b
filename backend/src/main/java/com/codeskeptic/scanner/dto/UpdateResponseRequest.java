package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>The two updatable properties are the {@code content} and {@code is_approved} columns of the
 * {@code responses} table ({@code backend/app/db/models.py:L24,L26}). Wire keys stay snake_case and
 * no identifier is carried in the body. No validation constraint is declared — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>Each component is held as the raw JSON node the body carried. The accessors expose only values
 * their target columns can hold — see docs/DECISION_LOG.md DL-082:
 *
 * <ul>
 *   <li>{@link #writesContent()} reports {@code true} only for a JSON string;
 *   <li>{@link #writesApproval()} reports {@code true} only for a JSON boolean;
 *   <li>an omitted key, an explicit JSON {@code null}, or a value of another JSON type is not
 *       writable and leaves its column unchanged.
 * </ul>
 *
 * <p>Jackson may bind any JSON node type into either component. A request carrying no writable value
 * is reported by {@link #carriesNoWritableValue()} and rejected by the service with the existing
 * {@code Update data is required} response — DL-082.
 *
 * @param content    raw {@code content} node, or {@code null} when the body omits the key
 * @param isApproved raw {@code is_approved} node, or {@code null} when the body omits the key
 */
// Ported from backend/app/api/responses.py:L54 (faithful port; source read request.json free-form) —
// see docs/DECISION_LOG.md DL-050 and DL-082
public record UpdateResponseRequest(

        @JsonProperty("content")
        JsonNode content,

        @JsonProperty("is_approved")
        JsonNode isApproved

) {

    /**
     * Reports whether the request carries text that can be written to {@code responses.content}.
     *
     * @return {@code true} only when {@code content} is a JSON string
     */
    public boolean writesContent() {
        return content != null && content.isTextual();
    }

    /**
     * Returns the {@code content} value the request body carried.
     *
     * @return the text, or {@code null} when {@link #writesContent()} is {@code false}
     */
    public String contentValue() {
        return (content == null || !content.isTextual()) ? null : content.textValue();
    }

    /**
     * Reports whether the request carries a boolean that can be written to
     * {@code responses.is_approved}.
     *
     * @return {@code true} only when {@code is_approved} is a JSON boolean
     */
    public boolean writesApproval() {
        return isApproved != null && isApproved.isBoolean();
    }

    /**
     * Returns the {@code is_approved} value the request body carried.
     *
     * @return the flag, or {@code null} when {@link #writesApproval()} is {@code false}
     */
    public Boolean approvalValue() {
        return (isApproved == null || !isApproved.isBoolean()) ? null : isApproved.booleanValue();
    }

    /**
     * Reports whether the request carries no value either target column can hold.
     *
     * @return {@code true} when neither a textual {@code content} nor a boolean
     *     {@code is_approved} value is present
     */
    public boolean carriesNoWritableValue() {
        return !writesContent() && !writesApproval();
    }

}
