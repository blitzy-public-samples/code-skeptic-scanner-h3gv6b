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
 * <p>Each component is held as the raw JSON node the body carried, and a key's presence is reported
 * independently of its value — see docs/DECISION_LOG.md DL-082:
 *
 * <ul>
 *   <li>a key the body omits binds to {@code null}, and {@link #contentPresent()} /
 *       {@link #approvalPresent()} report {@code false};
 *   <li>a key the body carries as JSON {@code null} binds to a null node, and the presence accessor
 *       reports {@code true} while {@link #contentValue()} / {@link #approvalValue()} report
 *       {@code null};
 *   <li>a key the body carries with a value binds to that value.
 * </ul>
 *
 * <p>No JSON type is rejected, matching the free-form {@code update_data = request.json} of
 * {@code backend/app/api/responses.py:L54} — see docs/DECISION_LOG.md DL-050. A node whose type the
 * target column cannot hold reads as {@code null} through the value accessors below, while its key
 * still counts as present.
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
     * Reports whether the request body carried the {@code content} key, whatever its value.
     *
     * @return {@code true} when the body carried the key, including when it carried it as
     *     {@code null}
     */
    public boolean contentPresent() {
        return content != null;
    }

    /**
     * Returns the {@code content} value the request body carried.
     *
     * @return the text, or {@code null} when the body carried the key as {@code null}, omitted it, or
     *     carried a node that is not a JSON string
     */
    public String contentValue() {
        return (content == null || !content.isTextual()) ? null : content.textValue();
    }

    /**
     * Reports whether the request body carried the {@code is_approved} key, whatever its value.
     *
     * @return {@code true} when the body carried the key, including when it carried it as
     *     {@code null}
     */
    public boolean approvalPresent() {
        return isApproved != null;
    }

    /**
     * Returns the {@code is_approved} value the request body carried.
     *
     * @return the flag, or {@code null} when the body carried the key as {@code null}, omitted it, or
     *     carried a node that is not a JSON boolean
     */
    public Boolean approvalValue() {
        return (isApproved == null || !isApproved.isBoolean()) ? null : isApproved.booleanValue();
    }

    /**
     * Reports whether the request body carried neither updatable key.
     *
     * <p>This is the condition the source expressed as {@code if not update_data} at
     * {@code backend/app/api/responses.py:L56}: an empty JSON object carries no key.
     *
     * @return {@code true} when neither {@code content} nor {@code is_approved} was carried
     */
    public boolean carriesNoUpdatableField() {
        return !contentPresent() && !approvalPresent();
    }

}
