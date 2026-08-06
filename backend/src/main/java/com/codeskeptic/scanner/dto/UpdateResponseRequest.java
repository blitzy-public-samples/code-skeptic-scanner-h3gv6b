package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>The two updatable members are the {@code content} and {@code is_approved} columns of the
 * {@code responses} table ({@code backend/app/db/models.py:L24,L26}). Wire keys stay snake_case and
 * no identifier is carried in the body. No Bean Validation constraint is declared and this record
 * rejects no value: it is the free-form {@code request.json} of
 * {@code backend/app/api/responses.py:L54} — see docs/DECISION_LOG.md DL-050 and DL-082.
 *
 * <p>Each component is held as the raw JSON node the body carried, which makes the three inbound
 * states distinguishable — see docs/DECISION_LOG.md DL-082:
 *
 * <ul>
 *   <li>a key the body omits binds to {@code null}: {@link #writesContent()} /
 *       {@link #writesApproval()} report {@code false} and the column is left untouched;
 *   <li>a key the body carries with a JSON {@code null} binds to a null node:
 *       the presence accessor reports {@code true} and the value accessor reports {@code null}, so
 *       the column is written {@code null};
 *   <li>a key the body carries with any other value binds to that node: the presence accessor
 *       reports {@code true} and the value accessor reports the value its column can hold.
 * </ul>
 *
 * <p>{@link #contentValue()} and {@link #approvalValue()} interpret a carried node the way Jackson
 * interprets a scalar bound to a {@link String} and to a {@link Boolean}; a structured node is
 * carried through as its compact JSON text and as {@code false} respectively. No carried node
 * produces an error here, so no request body is answered 400 on account of this record.
 *
 * @param content    raw {@code content} node, {@code null} when the body omits the key
 * @param isApproved raw {@code is_approved} node, {@code null} when the body omits the key
 */
// Ported from backend/app/api/responses.py:L54 (faithful port; source read request.json free-form) —
// see docs/DECISION_LOG.md DL-050, DL-082 and DL-244
public record UpdateResponseRequest(

        @JsonProperty("content")
        JsonNode content,

        @JsonProperty("is_approved")
        JsonNode isApproved

) {

    /**
     * Reports whether the request body carried the {@code content} key.
     *
     * @return {@code true} when the body carried the key, with any value, a JSON {@code null}
     *     included
     */
    public boolean writesContent() {
        return content != null;
    }

    /**
     * Returns the value to write to the {@code content} column.
     *
     * @return {@code null} when the body omitted the key or carried a JSON {@code null}; the text of
     *     a carried scalar; the compact JSON text of a carried object or array
     */
    public String contentValue() {
        if (content == null || content.isNull()) {
            return null;
        }
        return content.isValueNode() ? content.asText() : content.toString();
    }

    /**
     * Reports whether the {@code is_approved} column is written by this request.
     *
     * @return {@code true} when the body carried the key, with any value, a JSON {@code null}
     *     included
     */
    public boolean writesApproval() {
        return isApproved != null;
    }

    /**
     * Returns the value to write to the {@code is_approved} column.
     *
     * @return {@code null} when the body omitted the key or carried a JSON {@code null}; otherwise
     *     the carried node read as a flag — {@code true} for a true boolean, for the text
     *     {@code "true"} and for a non-zero number, and {@code false} for every other carried node
     */
    public Boolean approvalValue() {
        if (isApproved == null || isApproved.isNull()) {
            return null;
        }
        return isApproved.asBoolean();
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
