package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound request body of {@code POST /auth/token}.
 *
 * <p>Wire shape: {@code {"username": "...", "password": "..."}}
 *
 * <p>Neither component declares a Bean Validation constraint, so a JSON member that is absent, or
 * present with a JSON {@code null}, binds to {@code null}.
 *
 * <p>{@link #toString()} is overridden to render both components as {@code ***REDACTED***}, so no
 * submitted credential can reach diagnostic output through it, whatever the components hold.
 * Deserialization is unaffected: Jackson uses the canonical constructor and the {@link JsonProperty}
 * names. The {@code password} member is bound write-only, so it is read during deserialisation and
 * omitted from any serialised form of this record.
 *
 * @param username the submitted principal name, or {@code null} when the JSON
 *                 member is absent
 * @param password the submitted plaintext credential, or {@code null} when the
 *                 JSON member is absent
 */
// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
public record LoginRequest(
        @JsonProperty("username") String username,
        @JsonProperty(value = "password", access = JsonProperty.Access.WRITE_ONLY) String password) {

    /** Rendered by {@link #toString()} in place of each component. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * Returns a fixed description of this request that carries neither component.
     *
     * @return the fixed text {@code LoginRequest[username=***REDACTED***,
     *         password=***REDACTED***]}
     */
    @Override
    public String toString() {
        return "LoginRequest[username=" + REDACTED
                + ", password=" + REDACTED
                + "]";
    }
}
