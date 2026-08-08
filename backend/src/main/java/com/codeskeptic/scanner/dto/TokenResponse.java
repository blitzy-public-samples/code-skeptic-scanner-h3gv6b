package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
/**
 * Response body returned with HTTP 200 by {@code POST /auth/token}.
 *
 * <p>Three snake_case keys and nothing else:
 *
 * <pre>{@code {"access_token": "<compact-jws>", "token_type": "bearer", "expires_in": 3600}}</pre>
 *
 * <p>{@code expires_in} is expressed in <strong>seconds</strong>, while the configured lifetime
 * {@code scanner.jwt.expiration-minutes} (default 60) is expressed in minutes; the 60-minute
 * default reaches this record as {@code 3600L}. {@code token_type} carries the lowercase literal
 * {@code bearer}.
 *
 * <p>{@code accessToken} is a live credential, so {@link #toString()} is overridden to render it as
 * {@code ***REDACTED***}; the non-secret {@code tokenType} and {@code expiresIn} are rendered as they
 * are. Serialization is unaffected: Jackson uses the component accessors and the
 * {@link JsonProperty} names, so all three keys are still emitted.
 *
 * @param accessToken the compact JWS the client presents as {@code Authorization: Bearer}
 * @param tokenType the token type literal {@code bearer}
 * @param expiresIn the token lifetime in seconds
 */
public record TokenResponse(

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("token_type")
        String tokenType,

        @JsonProperty("expires_in")
        Long expiresIn
) {

    private static final String REDACTED = "***REDACTED***";

    /**
     * Returns a description of this response in which the minted token is replaced by a fixed
     * marker.
     *
     * @return the record's components with {@code accessToken} rendered as
     *     {@code ***REDACTED***}
     */
    @Override
    public String toString() {
        return "TokenResponse[accessToken=" + REDACTED
                + ", tokenType=" + tokenType
                + ", expiresIn=" + expiresIn
                + "]";
    }
}
