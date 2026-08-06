package com.codeskeptic.scanner.dto;

import java.util.Locale;
import java.util.function.Predicate;

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
 * <p>The canonical constructor accepts a carried {@code content} only as a JSON string and a carried
 * {@code is_approved} only as a JSON boolean, an explicit JSON {@code null} for either included. Any
 * other carried type — a number, a boolean under {@code content}, a string under {@code is_approved},
 * an object or an array — is rejected with {@link IllegalArgumentException}, which Jackson reports as
 * {@code ValueInstantiationException} and the request-body converter as
 * {@code HttpMessageNotReadableException}, so the request is answered 400
 * {@code {"error": "Bad request"}} — see docs/DECISION_LOG.md DL-231 and DL-092. No value is trimmed,
 * defaulted or coerced.
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
     * Rejects a carried key whose value the addressed column cannot hold.
     *
     * @throws IllegalArgumentException when {@code content} is carried as anything but a JSON string
     *                                 or an explicit JSON {@code null}, or {@code isApproved} as
     *                                 anything but a JSON boolean or an explicit JSON {@code null}
     */
    public UpdateResponseRequest {
        if (carriesUnusableValue(content, JsonNode::isTextual)) {
            throw new IllegalArgumentException(
                    "content must be a JSON string or null; a " + typeOf(content) + " was carried.");
        }
        if (carriesUnusableValue(isApproved, JsonNode::isBoolean)) {
            throw new IllegalArgumentException("is_approved must be a JSON boolean or null; a "
                    + typeOf(isApproved) + " was carried.");
        }
    }

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
     * @return the text of the carried JSON string; {@code null} when the body omitted the key or
     *     carried an explicit JSON {@code null}
     */
    public String contentValue() {
        return (content == null || content.isNull()) ? null : content.textValue();
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
     * @return the carried JSON boolean; {@code null} when the body omitted the key or carried an
     *     explicit JSON {@code null}
     */
    public Boolean approvalValue() {
        return (isApproved == null || isApproved.isNull()) ? null : isApproved.booleanValue();
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

    /**
     * Reports whether a carried node holds a value its column cannot hold.
     *
     * @param carried  the node the body carried, or {@code null} when the key was omitted
     * @param accepted the shape the column accepts
     * @return {@code true} when a node is carried, is not an explicit JSON {@code null} and does not
     *     satisfy {@code accepted}
     */
    private static boolean carriesUnusableValue(JsonNode carried,
            Predicate<JsonNode> accepted) {
        return carried != null && !carried.isNull() && !accepted.test(carried);
    }

    /**
     * Names the JSON type of a carried node for the rejection message.
     *
     * @param carried the carried node; must not be {@code null}
     * @return the lower-case node-type name, such as {@code number} or {@code object}
     */
    private static String typeOf(JsonNode carried) {
        return carried.getNodeType().name().toLowerCase(Locale.ROOT);
    }

}
