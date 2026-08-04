package com.codeskeptic.scanner.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Faithful port of intent: the eleven bare @jwt_required guards at backend/app/api/tweets.py:L10,L24,L37;
// responses.py:L9,L23,L34,L52; settings.py:L8,L14; analytics.py:L8,L18 — see docs/DECISION_LOG.md DL-021.
// Token verification is delegated to JwtService — see docs/DECISION_LOG.md DL-014.
// Context persistence and dispatch coverage — see docs/DECISION_LOG.md DL-112.
// Case-insensitive scheme matching — see docs/DECISION_LOG.md DL-113.
/**
 * Establishes the authentication for a request presenting a bearer token this service minted.
 *
 * <p>The retired Python tree registered {@code JWTManager(app)} at {@code backend/app/main.py:L22}
 * and applied {@code @jwt_required} bare, without parentheses, at all eleven route sites named
 * above; in {@code flask-jwt-extended} 4.x that form registers the decorator factory and leaves the
 * route unguarded. This filter is the verification half of that scheme, made effective — DL-021.
 * {@link JwtService} is the minting half.
 *
 * <p>Per request the filter reads the {@code Authorization} header, which
 * {@code frontend/src/utils/api.ts:L14} already sends as {@code Bearer <token>}. The scheme name is
 * matched without regard to case — DL-113. One of three outcomes follows:
 *
 * <ul>
 *   <li>No header, a header carrying another scheme, or the bearer scheme with an empty remainder —
 *       the context is left untouched.</li>
 *   <li>A bearer token {@link JwtService#extractUsername(String)} does not accept — the context is
 *       left untouched.</li>
 *   <li>A bearer token that is accepted, on a request carrying no authentication yet — the token's
 *       subject is set as an authenticated principal holding no authorities, and the resulting
 *       context is written to the {@link SecurityContextRepository} the chain reads — DL-112.</li>
 * </ul>
 *
 * <p>The filter runs on the {@code REQUEST}, {@code ASYNC} and {@code ERROR} dispatches alongside
 * the security chain's authorization stage — DL-112.
 *
 * <p>The chain always continues, exactly once per dispatch, on every path. This filter sets no
 * status, writes no body and clears no context; a request reaching the authorization stage with no
 * authentication is answered by the entry point {@code SecurityConfig} configures.
 * {@link JwtService#extractUsername(String)} absorbs every verification failure; no exception leaves
 * this filter. Neither a header value, nor a token value, nor the token's subject is written to the
 * log — DL-111.
 *
 * <p>This class carries no stereotype annotation and is not a bean; {@code SecurityConfig}
 * constructs it directly and registers it in the security filter chain — DL-021.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-014, DL-021,
 * DL-111, DL-112 and DL-113; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>All three fields are {@code final} and hold stateless collaborators; every member declared here
 * is safe for concurrent use.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Scheme name {@code frontend/src/utils/api.ts:L14} sends, matched without regard to case and
     * required to be followed by a space — DL-113.
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Verifies a presented token and reads its {@code sub} claim. */
    private final JwtService jwtService;

    /** The repository the security chain reads the context back from — DL-112. */
    private final SecurityContextRepository securityContextRepository;

    /** Strategy that holds the context for the current thread. */
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    /**
     * Retains the collaborators that verify presented tokens and persist the resulting context.
     *
     * @param jwtService the service that verifies a presented token and reads its {@code sub} claim
     * @param securityContextRepository the repository shared with the enclosing chain; this filter
     *     writes the context the chain reads
     * @throws NullPointerException if either argument is {@code null}
     */
    public JwtAuthenticationFilter(JwtService jwtService,
            SecurityContextRepository securityContextRepository) {
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository,
                "securityContextRepository must not be null");
    }

    /**
     * Authenticates the request when it presents an acceptable bearer token, then continues the
     * chain.
     *
     * <p>An unacceptable token and an absent header are treated alike: the context is left as it was
     * found and the request proceeds unauthenticated. An authentication already present in the
     * context is never replaced.
     *
     * @param request the request whose {@code Authorization} header is read
     * @param response handed to the context repository so the context can be persisted
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
                log.debug("A presented bearer token was not accepted; {} continues unauthenticated",
                        request.getMethod());
            } else if (securityContextHolderStrategy.getContext().getAuthentication() == null) {
                SecurityContext context = securityContextHolderStrategy.createEmptyContext();
                context.setAuthentication(authenticationFor(username.get(), request));
                securityContextHolderStrategy.setContext(context);
                securityContextRepository.saveContext(context, request, response);
                log.debug("Authenticated {} through a bearer token", request.getMethod());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Runs this filter on the {@code ASYNC} dispatch, on which the authorization stage also runs —
     * DL-112.
     *
     * @return {@code false} always
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * Runs this filter on the {@code ERROR} dispatch, on which the authorization stage also runs —
     * DL-112.
     *
     * @return {@code false} always
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Reads the token a bearer {@code Authorization} header carries.
     *
     * <p>The scheme name is compared without regard to case, so {@code Bearer}, {@code bearer} and
     * {@code BEARER} are all read alike — DL-113.
     *
     * @param header the raw header value, which may be {@code null}
     * @return the trimmed token, or {@code null} when the header is absent, carries another scheme,
     *     or leaves an empty remainder after the scheme name
     */
    private static String bearerToken(String header) {
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
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
