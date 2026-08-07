package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>The two updatable members are the {@code content} and {@code is_approved} columns of the
 * {@code responses} table ({@code backend/app/db/models.py:L24,L26}); keys stay snake_case and no
 * identifier is carried. No Bean Validation constraint is declared: the free-form
 * {@code request.json} of {@code backend/app/api/responses.py:L54} declares none — DL-050.
 *
 * <p>Each component is held as the raw JSON node the body carried, which makes the three inbound
 * states distinguishable — DL-082:
 *
 * <ul>
 *   <li>an omitted key binds to {@code null}: the presence accessor reports {@code false} and the
 *       column is left untouched;
 *   <li>a key carrying JSON {@code null} binds to a null node: the presence accessor reports
 *       {@code true} and the column is written {@code null} — DL-244;
 *   <li>a key carrying a value binds to that node: the presence accessor reports {@code true} and the
 *       value accessor reports the value.
 * </ul>
 *
 * <p>No carried type is rejected, matching that free-form read: a {@code content} node that is a JSON
 * string yields its text and any other node yields its JSON rendering, and {@code is_approved} yields
 * the boolean the node evaluates to. Nothing is trimmed or defaulted — DL-231.
 *
 * @param content    raw {@code content} node, {@code null} when the body omits the key
 * @param isApproved raw {@code is_approved} node, {@code null} when the body omits the key
 */
// Ported from backend/app/api/responses.py:L54 (faithful port; source read request.json free-form) —
// see docs/DECISION_LOG.md DL-050, DL-082, DL-231 and DL-244
public record UpdateResponseRequest(

        @JsonProperty("content")
        JsonNode content,

        @JsonProperty("is_approved")
        JsonNode isApproved

) {

    /**
     * Reports whether the request body carried the {@code content} key.
     *
     * @return {@code true} when the body carried the key, an explicit JSON {@code null} included
     */
    public boolean writesContent() {
        return content != null;
    }

    /**
     * Returns the value to write to the {@code content} column.
     *
     * @return the text of the carried JSON string, or the JSON rendering of any other carried node;
     *     {@code null} when the body omitted the key or carried an explicit JSON {@code null}
     */
    public String contentValue() {
        if (content == null || content.isNull()) {
            return null;
        }
        return content.isTextual() ? content.textValue() : content.toString();
    }

    /**
     * Reports whether the {@code is_approved} column is written by this request.
     *
     * @return {@code true} when the body carried the key, an explicit JSON {@code null} included
     */
    public boolean writesApproval() {
        return isApproved != null;
    }

    /**
     * Returns the value to write to the {@code is_approved} column.
     *
     * @return the boolean the carried node evaluates to; {@code null} when the body omitted the key
     *     or carried an explicit JSON {@code null}
     */
    public Boolean approvalValue() {
        return (isApproved == null || isApproved.isNull()) ? null : isApproved.asBoolean();
    }

    /**
     * Reports whether the request body carried neither updatable member.
     *
     * @return {@code true} when the body carried neither the {@code content} key nor the
     *     {@code is_approved} key
     */
    public boolean carriesNoUpdatableMember() {
        return content == null && isApproved == null;
    }

}
