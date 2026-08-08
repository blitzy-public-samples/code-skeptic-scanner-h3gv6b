package com.codeskeptic.scanner.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
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
// A request carrying more than one Authorization field is refused before any token is parsed — see
// docs/DECISION_LOG.md DL-308.
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
 * <p>Exactly one {@code Authorization} field is read. A request presenting two or more is ambiguous —
 * which field is authoritative is not something this filter may choose — so every one of them is
 * discarded without being parsed and the request proceeds unauthenticated, whatever order the fields
 * arrived in and whichever of them would have been accepted alone — DL-308.
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
     * <p>An unacceptable token, an absent header and more than one {@code Authorization} field are
     * treated alike: the context is left as it was found and the request proceeds unauthenticated. An
     * authentication already present in the context is not replaced.
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

        String token = bearerToken(soleAuthorizationHeader(request));
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

    // Net-new refusal of an ambiguous request — DL-308 — see docs/DECISION_LOG.md
    /**
     * Reads the request's single {@code Authorization} field.
     *
     * <p>A request carrying two or more such fields is ambiguous, so none of them is returned and the
     * request is left unauthenticated: reading the first would make the outcome depend on field
     * order, which is what a proxy or a client may reorder — DL-308. The refusal is recorded once per
     * request at {@code DEBUG} as a fixed sentence carrying the field count and nothing else: no
     * header value, no token and no request line — DL-052, DL-111.
     *
     * @param request the request to read, never {@code null}
     * @return the sole header value, or {@code null} when the request carries none or more than one
     */
    private static String soleAuthorizationHeader(HttpServletRequest request) {
        Enumeration<String> fields = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (fields == null || !fields.hasMoreElements()) {
            return null;
        }
        String sole = fields.nextElement();
        if (!fields.hasMoreElements()) {
            return sole;
        }

        int count = 2;
        while (fields.hasMoreElements()) {
            fields.nextElement();
            count++;
        }
        // A fixed sentence: the field count only — no header value, no token, no request method and
        // no request URI — DL-111, DL-308 — see docs/DECISION_LOG.md
        log.debug("A request presented {} Authorization fields, which is ambiguous; none was parsed "
                + "and the request continues unauthenticated", count);
        return null;
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
