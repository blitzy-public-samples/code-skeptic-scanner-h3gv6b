package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
/**
 * Response body returned with HTTP 200 by {@code POST /auth/token}, the service's only
 * unauthenticated endpoint.
 *
 * <p>The record serialises to exactly three snake_case keys and nothing else:
 *
 * <pre>{@code
 * {"access_token": "<compact-jws>", "token_type": "bearer", "expires_in": 3600}
 * }</pre>
 *
 * <p>Units and literals fixed by that contract:
 *
 * <ul>
 *   <li>{@code expires_in} is expressed in <strong>seconds</strong>. The configured lifetime
 *       {@code scanner.jwt.expiration-minutes} (default 60) is expressed in minutes and is
 *       converted by the caller: the 60-minute default reaches this record as {@code 3600L}.
 *   <li>{@code token_type} carries the lowercase literal {@code bearer}, supplied by
 *       {@code api/AuthController}.
 *   <li>{@code access_token} carries the compact JWS minted by {@code security/JwtService}.
 * </ul>
 *
 * <p>The record is a passive carrier: the body is empty, it declares no default, no constant, no
 * static factory and no conversion, and it holds no state beyond its three components. Its
 * {@code accessToken} component is a live credential, and no instance of this record is logged.
 *
 * <p>Usage, for a compact JWS with the 60-minute default lifetime:
 *
 * <pre>{@code
 * new TokenResponse(compactJws, "bearer", 3600L);
 * }</pre>
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
}
