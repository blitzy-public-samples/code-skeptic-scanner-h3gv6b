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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
 * <p>The verification half of the token scheme; {@link JwtService} is the minting half — DL-021.
 *
 * <p>The scheme name of the {@code Authorization} header is matched without regard to case — DL-113.
 * A token that is accepted, on a request carrying no authentication yet, sets its subject as an
 * authenticated principal holding no authorities and writes the resulting context to the
 * {@link SecurityContextRepository} the chain reads — DL-112. An absent header, another scheme, an
 * empty remainder and a token that is not accepted all leave the context untouched.
 *
 * <p>Runs on the {@code REQUEST}, {@code ASYNC} and {@code ERROR} dispatches, alongside the chain's
 * authorization stage — DL-112. The chain always continues, exactly once per dispatch, on every path:
 * this filter sets no status, writes no body and clears no context, and no exception leaves it.
 *
 * <p>No record written here carries a header value, a token value, the token's subject, the request
 * method or the request URI — DL-052, DL-094, DL-111.
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

    private final JwtService jwtService;

    /** The repository the security chain reads the context back from — DL-112. */
    private final SecurityContextRepository securityContextRepository;

    /** Resolves the {@code sub} claim against the configured credential store — DL-021. */
    private final UserDetailsService userDetailsService;

    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService,
            SecurityContextRepository securityContextRepository,
            UserDetailsService userDetailsService) {
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository,
                "securityContextRepository must not be null");
        this.userDetailsService = Objects.requireNonNull(userDetailsService,
                "userDetailsService must not be null");
    }

    /**
     * Authenticates the request when it presents an acceptable bearer token, then continues the
     * chain.
     *
     * <p>An unacceptable token and an absent header are treated alike: the context is left as it was
     * found and the request proceeds unauthenticated. An authentication already present in the
     * context is not replaced.
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
            if (username.isPresent()
                    && securityContextHolderStrategy.getContext().getAuthentication() == null) {
                UserDetails principal = resolvePrincipal(username.get());
                if (principal == null) {
                    // A fixed sentence: no request method, no request URI and no principal —
                    // DL-111 — see docs/DECISION_LOG.md
                    log.debug("A presented bearer token named a principal the credential store "
                            + "does not hold; the request continues unauthenticated");
                } else {
                    SecurityContext context = securityContextHolderStrategy.createEmptyContext();
                    context.setAuthentication(authenticationFor(principal.getUsername(), request));
                    securityContextHolderStrategy.setContext(context);
                    securityContextRepository.saveContext(context, request, response);
                    // A fixed sentence: no request method, no request URI and no principal —
                    // DL-111 — see docs/DECISION_LOG.md
                    log.debug("Authenticated a request through a bearer token");
                }
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
     * Resolves a verified subject against the credential store.
     *
     * <p>{@link UserDetailsService#loadUserByUsername(String)} is called on every authenticated
     * request. A subject the store does not hold yields {@code null} and the request proceeds
     * unauthenticated, so a token minted for a principal name that is no longer configured stops
     * authenticating at that moment, and not at its expiry.
     *
     * @param username the verified {@code sub} claim
     * @return the stored principal, or {@code null} when the store holds none of that name
     */
    private UserDetails resolvePrincipal(String username) {
        try {
            return userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException absent) {
            return null;
        }
    }

    private static UsernamePasswordAuthenticationToken authenticationFor(String username,
            HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
