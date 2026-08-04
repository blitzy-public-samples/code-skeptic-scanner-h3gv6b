package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound request body of {@code POST /auth/token}, the only unauthenticated
 * endpoint exposed by this service.
 *
 * <p>The wire shape is exactly:
 *
 * <pre>{@code
 * {"username": "...", "password": "..."}
 * }</pre>
 *
 * <p>Submitted credentials are matched against the configuration-backed
 * principal declared by {@code scanner.auth.username} and
 * {@code scanner.auth.password-hash}.
 *
 * <p>Neither component declares a Bean Validation constraint. A JSON member
 * that is absent, or that is present with a JSON {@code null}, binds to
 * {@code null} and is carried through to the authentication manager.
 *
 * <p>The compiler-generated {@code toString()} of a record renders every
 * component, {@link #password()} included. Instances of this type are never
 * logged.
 *
 * @param username the submitted principal name, or {@code null} when the JSON
 *                 member is absent
 * @param password the submitted plaintext credential, or {@code null} when the
 *                 JSON member is absent
 */
// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
public record LoginRequest(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password) {
}
