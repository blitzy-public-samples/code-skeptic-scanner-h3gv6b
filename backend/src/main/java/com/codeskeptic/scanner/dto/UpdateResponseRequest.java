package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>The two updatable members are the {@code content} and {@code is_approved} columns of the
 * {@code responses} table ({@code backend/app/db/models.py:L24,L26}). Wire keys stay snake_case and
 * no identifier is carried in the body. No Bean Validation constraint is declared: the free-form
 * {@code request.json} of {@code backend/app/api/responses.py:L54} declares none — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>Each component is held as the raw JSON node the body carried, which makes the three inbound
 * states distinguishable — see docs/DECISION_LOG.md DL-082:
 *
 * <ul>
 *   <li>a key the body omits binds to {@code null}: {@link #writesContent()} /
 *       {@link #writesApproval()} report {@code false} and the column is left untouched;
 *   <li>a key the body carries with a JSON {@code null} binds to a null node: the presence accessor
 *       reports {@code true} and the value accessor reports {@code null}, so the column is written
 *       {@code null} — see docs/DECISION_LOG.md DL-244;
 *   <li>a key the body carries with a value the addressed column can hold binds to that node: the
 *       presence accessor reports {@code true} and the value accessor reports the value.
 * </ul>
 *
 * <p>No carried type is rejected: the free-form {@code request.json} of
 * {@code backend/app/api/responses.py:L54} assigned whatever the body held to the column, so this
 * record accepts whatever the body holds and renders it deterministically. A carried {@code content}
 * that is a JSON string yields its text and any other carried node yields its JSON rendering; a
 * carried {@code is_approved} yields the boolean the node evaluates to. No value is trimmed or
 * defaulted — see docs/DECISION_LOG.md DL-231.
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
