package com.codeskeptic.scanner.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Faithful port of intent: the eleven bare @jwt_required guards at backend/app/api/tweets.py:L10,L24,L37;
// responses.py:L9,L23,L34,L52; settings.py:L8,L14; analytics.py:L8,L18 — see docs/DECISION_LOG.md DL-021.
// Token verification is delegated to JwtService — see docs/DECISION_LOG.md DL-014.
/**
 * Establishes the {@link SecurityContextHolder} authentication for a request presenting a bearer
 * token this service minted.
 *
 * <p>The retired Python tree registered {@code JWTManager(app)} at {@code backend/app/main.py:L22}
 * and applied {@code @jwt_required} bare, without parentheses, at all eleven route sites named
 * above; in {@code flask-jwt-extended} 4.x that form registers the decorator factory and leaves the
 * route unguarded. This filter is the verification half of that scheme, made effective — DL-021.
 * {@link JwtService} is the minting half.
 *
 * <p>Per request the filter reads the {@code Authorization} header, which
 * {@code frontend/src/utils/api.ts:L14} already sends as {@code Bearer <token>}. One of three
 * outcomes follows:
 *
 * <ul>
 *   <li>No header, a header carrying another scheme, or {@code Bearer} with an empty remainder — the
 *       context is left untouched.</li>
 *   <li>A bearer token {@link JwtService#extractUsername(String)} does not verify — the context is
 *       left untouched.</li>
 *   <li>A bearer token that verifies, on a request carrying no authentication yet — the token's
 *       subject is set as an authenticated principal holding no authorities.</li>
 * </ul>
 *
 * <p>The chain always continues, exactly once, on every path. This filter sets no status, writes no
 * body and clears no context; a request reaching the authorization stage with no authentication is
 * answered by the entry point {@code SecurityConfig} configures.
 * {@link JwtService#extractUsername(String)} absorbs every verification failure; no exception leaves
 * this filter. Neither a header value nor a token value is written to the log.
 *
 * <p>This class carries no stereotype annotation and is not a bean; {@code SecurityConfig}
 * constructs it directly and registers it in the security filter chain — DL-021.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-014 and DL-021;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>The single field is {@code final} and holds a stateless singleton; every member declared here
 * is safe for concurrent use.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Scheme prefix {@code frontend/src/utils/api.ts:L14} sends, matched case-sensitively including
     * its trailing space.
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Verifies a presented token and reads its {@code sub} claim. */
    private final JwtService jwtService;

    /**
     * Retains the collaborator that verifies presented tokens.
     *
     * @param jwtService the service that verifies a presented token and reads its {@code sub} claim
     * @throws NullPointerException if {@code jwtService} is {@code null}
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
    }

    /**
     * Authenticates the request when it presents a verifiable bearer token, then continues the chain.
     *
     * <p>An unverifiable token and an absent header are treated alike: the context is left as it was
     * found and the request proceeds unauthenticated. An authentication already present in the
     * context is never replaced.
     *
     * @param request the request whose {@code Authorization} header is read
     * @param response passed along untouched
     * @param filterChain the chain, continued exactly once on every path
     * @throws ServletException if a downstream filter or the servlet raises it
     * @throws IOException if a downstream filter or the servlet raises it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token != null) {
            Optional<String> username = jwtService.extractUsername(token);
            if (username.isEmpty()) {
                log.debug("A presented bearer token did not verify; {} {} continues unauthenticated",
                        request.getMethod(), request.getRequestURI());
            } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
                SecurityContextHolder.getContext()
                        .setAuthentication(authenticationFor(username.get(), request));
                log.debug("Authenticated {} {} as '{}'",
                        request.getMethod(), request.getRequestURI(), username.get());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Reads the token a bearer {@code Authorization} header carries.
     *
     * @param header the raw header value, which may be {@code null}
     * @return the trimmed token, or {@code null} when the header is absent, carries another scheme,
     *     or leaves an empty remainder after the prefix
     */
    private static String bearerToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Builds the authenticated token for a verified subject.
     *
     * <p>The three-argument constructor marks the token authenticated. The principal is the token's
     * {@code sub} claim, the credentials are {@code null} and the authority collection is empty —
     * DL-021.
     *
     * @param username the verified {@code sub} claim
     * @param request the request whose remote address and session id are recorded as details
     * @return an authenticated token holding no authorities
     */
    private static UsernamePasswordAuthenticationToken authenticationFor(String username,
            HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
