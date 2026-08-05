package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound request body for {@code PUT /responses/{responseId}}.
 *
 * <p>The two updatable properties are the {@code content} and {@code is_approved} columns of the
 * {@code responses} table ({@code backend/app/db/models.py:L24,L26}). Wire keys stay snake_case and
 * no identifier is carried in the body. No Bean Validation constraint is declared — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>Each component is held as the raw JSON node the body carried. The accessors expose only values
 * their target columns can hold — see docs/DECISION_LOG.md DL-082:
 *
 * <ul>
 *   <li>a key the body omits binds to {@code null}, and {@link #writesContent()} /
 *       {@link #writesApproval()} report {@code false};
 *   <li>a key the body carries with a value its column can hold binds to that value, and the presence
 *       accessor reports {@code true};
 *   <li>a key the body carries with any other value — a JSON {@code null} included — is rejected by
 *       the constructor, so no instance carries one.
 * </ul>
 *
 * <p>The constructor accepts a {@code content} node only when it is a JSON string and an
 * {@code is_approved} node only when it is a JSON boolean, which are the types
 * {@code backend/app/db/models.py:L24,L26} declares for the two columns and
 * {@code backend/app/schema/response.py:L6,L8} declares as required on the wire. Any other carried
 * node, including an explicit JSON {@code null}, raises {@link IllegalArgumentException}; through the
 * request-body converter that reaches the client as HTTP 400 with the body
 * {@code {"error": "Bad request"}} — see docs/DECISION_LOG.md DL-082, DL-092 and DL-231.
 *
 * <p>A component that is present therefore always carries a usable value: for an instance that
 * exists, {@link #writesContent()} implies a non-null {@link #contentValue()} and
 * {@link #writesApproval()} implies a non-null {@link #approvalValue()}.
 *
 * @param content    raw {@code content} node, {@code null} when the body omits the key, and otherwise
 *                   a JSON string
 * @param isApproved raw {@code is_approved} node, {@code null} when the body omits the key, and
 *                   otherwise a JSON boolean
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
     * Rejects a carried node whose type its column cannot hold.
     *
     * <p>An omitted key, which binds to {@code null}, is accepted by both components.
     *
     * @throws IllegalArgumentException when {@code content} is carried and is not a JSON string, or
     *                                  when {@code isApproved} is carried and is not a JSON boolean.
     *                                  A JSON {@code null} node is carried and is neither of those,
     *                                  so it is rejected by whichever component carries it
     */
    // The two column types of backend/app/db/models.py:L24,L26 — DL-082, DL-231 — see
    // docs/DECISION_LOG.md
    public UpdateResponseRequest {
        if (content != null && !content.isTextual()) {
            throw new IllegalArgumentException(
                    "The content member of a response update must be a JSON string.");
        }
        if (isApproved != null && !isApproved.isBoolean()) {
            throw new IllegalArgumentException(
                    "The is_approved member of a response update must be a JSON boolean.");
        }
    }

    /**
     * Reports whether the request body carried the {@code content} key.
     *
     * @return {@code true} when the body carried the key, in which case {@link #contentValue()}
     *     carries its text
     */
    public boolean writesContent() {
        return content != null && content.isTextual();
    }

    /**
     * Returns the {@code content} value the request body carried.
     *
     * @return the text, or {@code null} when the body omitted the key; never {@code null} when
     *     {@link #writesContent()} reports {@code true}
     */
    public String contentValue() {
        return (content == null) ? null : content.textValue();
    }

    /**
     * Reports whether the request body carried the {@code is_approved} key.
     *
     * @return {@code true} when the body carried the key, in which case {@link #approvalValue()}
     *     carries its flag
     */
    public boolean writesApproval() {
        return isApproved != null && isApproved.isBoolean();
    }

    /**
     * Returns the {@code is_approved} value the request body carried.
     *
     * @return the flag, or {@code null} when the body omitted the key; never {@code null} when
     *     {@link #writesApproval()} reports {@code true}
     */
    public Boolean approvalValue() {
        return (isApproved == null) ? null : isApproved.booleanValue();
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
