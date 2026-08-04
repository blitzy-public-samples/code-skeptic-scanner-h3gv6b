package com.codeskeptic.scanner.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.LoginRequest;
import com.codeskeptic.scanner.dto.TokenResponse;
import com.codeskeptic.scanner.security.JwtService;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
/**
 * Serves the one HTTP route that issues a bearer token.
 *
 * <p>This class has no counterpart in the retired Python tree — DL-019. Three facts establish that.
 * {@code backend/app/main.py:L26-29} registered four blueprints on the application object —
 * {@code tweets_bp}, {@code responses_bp}, {@code settings_bp} and {@code analytics_bp} — and none of
 * them declared an authentication route; the eleven routes those four blueprints declared are the
 * eleven this service still serves. {@code create_access_token} at
 * {@code backend/app/core/security.py:L6-12} was declared and no call site in the retired tree
 * invoked it, and the {@code passlib} helpers at {@code :L14-18} were likewise never invoked.
 * {@code frontend/src/utils/api.ts:L13-16} sends {@code Authorization: Bearer <token>} on every
 * request it makes.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code POST /auth/token}</td>
 *   <td>{@link #issueToken(LoginRequest)}</td>
 *   <td>200, one JSON object</td>
 *   <td>no source construct — net-new, DL-019</td>
 * </tr>
 * </table>
 *
 * <p>This class declares that one route and no second route: no refresh route, no logout route, no
 * registration route, no principal-describing route and no operation over the credential store. The
 * path is unprefixed, as the paths of the four retired blueprints were: it carries no {@code /api}
 * segment and no version segment, and no type-level request mapping contributes a prefix.
 *
 * <p>The request body is {@code {"username": "...", "password": "..."}}, bound to
 * {@link LoginRequest}. That record declares no Bean Validation constraint, and this class declares
 * no {@code @Valid}, so an absent body and an absent or {@code null} member each reach
 * {@link #issueToken(LoginRequest)} as {@code null} and are carried to the
 * {@link AuthenticationManager} unaltered.
 *
 * <p>The 200 body is {@link TokenResponse}:
 *
 * <pre>{@code {"access_token": "<compact-jws>", "token_type": "bearer", "expires_in": 3600}}</pre>
 *
 * <p>{@code token_type} carries the lowercase literal {@code bearer}. {@code expires_in} counts
 * seconds, while the configured lifetime {@code scanner.jwt.expiration-minutes} counts minutes;
 * {@link JwtService#getExpirationSeconds()} is the one place that converts between the two, and this
 * class neither multiplies nor divides the value it reports — DL-017. The minted token carries
 * exactly the {@code sub}, {@code iat} and {@code exp} claims: no role, no scope, no permission and
 * no authority claim — DL-018. {@code src/main/resources/application.yml} declares
 * {@code expiration-minutes: 60}, so the value on the wire is {@code 3600}.
 *
 * <p>This class carries no method-security annotation. Authorization for this route is declared in
 * the security filter chain. {@code security.SecurityConfig} permits
 * {@code POST /auth/token} with no authentication — the only such route in the service — and requires
 * an authenticated principal on every other request, which covers all eleven pre-existing routes —
 * DL-019, DL-021.
 *
 * <p>The credential check is performed entirely by the injected {@link AuthenticationManager}, which
 * {@code security.SecurityConfig} publishes from the application's
 * {@code AuthenticationConfiguration}. That manager resolves the single {@code scanner.auth}
 * principal through the {@code InMemoryUserDetailsManager} and compares the presented password
 * against the stored hash through the {@code BCryptPasswordEncoder} — DL-020, the successor of the
 * {@code passlib} helpers at {@code backend/app/core/security.py:L14-18}. This class declares no
 * {@code AuthenticationProvider}, holds no {@code UserDetailsService} and holds no
 * {@code PasswordEncoder}. It reads no entity, holds no repository and adds no table: the schema this
 * service creates stays the four tables of {@code backend/app/db/models.py} — {@code tweets},
 * {@code responses}, {@code ai_tools} and {@code settings}.
 *
 * <p>Nothing here publishes to X: this class declares no ingestion and no response-publishing
 * operation. No submitted password, no submitted principal name and no minted token is written to the
 * log at any level — DL-052.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-017, DL-018,
 * DL-019, DL-020, DL-021, DL-052, DL-078 and DL-079; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Both collaborators are held in final fields and are themselves
 * singletons, and this class holds no other state, so every member declared here is safe for
 * concurrent use.
 */
@RestController
public class AuthController {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * Value of the {@code token_type} member of every 200 body this class returns.
     *
     * <p>The literal is lowercase. {@code frontend/src/utils/api.ts:L13-16} sends the scheme name
     * {@code Bearer} in the {@code Authorization} header, and {@code security.JwtAuthenticationFilter}
     * matches that header against the prefix {@code "Bearer "}; this member is the OAuth 2 token-type
     * value and is not that header.
     */
    private static final String TOKEN_TYPE = "bearer";

    /**
     * Performs the credential check behind {@code POST /auth/token} — DL-019.
     *
     * <p>Published by {@code security.SecurityConfig} from the application's
     * {@code AuthenticationConfiguration}, over the single {@code scanner.auth} principal — DL-020.
     */
    private final AuthenticationManager authenticationManager;

    /** Mints the token returned in the 200 body and reports its lifetime in seconds. */
    private final JwtService jwtService;

    /**
     * Creates the controller with its two collaborators.
     *
     * @param authenticationManager checks the submitted credential, must not be {@code null}
     * @param jwtService mints the token and reports its lifetime, must not be {@code null}
     * @throws NullPointerException when either argument is {@code null}
     */
    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager,
                "authenticationManager must not be null.");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null.");
    }

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
    /**
     * Authenticates a submitted credential and returns a freshly minted bearer token.
     *
     * <p>The status is 200 on success and 401 on an authentication failure; this class selects no
     * third status. The 200 body carries the compact JWS, the token type {@code bearer} and the
     * token's lifetime in seconds.
     *
     * <p>The {@code sub} claim of the minted token is {@link Authentication#getName()} of the
     * authentication the {@link AuthenticationManager} returned — the resolved principal name, which
     * is present on every authentication that manager completes — and not the submitted string —
     * DL-079.
     *
     * <p>A {@code null} request, a request whose {@code username} member is absent and a request
     * whose {@code password} member is absent are each carried to the {@link AuthenticationManager}
     * as a {@code null} principal or a {@code null} credential. The manager rejects every one of them
     * with an {@link AuthenticationException}: an unresolvable principal name and a password that
     * does not match the stored hash both raise
     * {@link org.springframework.security.authentication.BadCredentialsException}.
     *
     * <p>Every {@link AuthenticationException} is answered with 401 and an empty body — the same
     * status and the same empty body that the {@code HttpStatusEntryPoint} of
     * {@code security.SecurityConfig} returns for an unauthenticated request to any other route. No
     * {@code {"error": <string>}} envelope is built here, and this route puts no new string on the
     * wire — DL-019. {@link GlobalExceptionHandler} declares no handler for
     * {@link AuthenticationException}, and its {@code @ExceptionHandler(Exception.class)} method
     * answers 500.
     *
     * <p>Example request body:
     *
     * <pre>{@code {"username":"admin","password":"<plaintext>"}}</pre>
     *
     * <p>Example response body:
     *
     * <pre>{@code {"access_token":"eyJhbGciOiJIUzI1NiJ9...","token_type":"bearer","expires_in":3600}}</pre>
     *
     * @param request the submitted credential, or {@code null} when the request carries no body
     * @return 200 carrying the minted token, its type and its lifetime in seconds, or 401 with an
     *     empty body when the submitted credential does not authenticate
     */
    @PostMapping("/auth/token")
    public ResponseEntity<TokenResponse> issueToken(
            @RequestBody(required = false) LoginRequest request) {

        // An absent body and an absent member are read alike, as an unsupplied value.
        String username = (request == null) ? null : request.username();
        String password = (request == null) ? null : request.password();

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException ex) {
            // 401 and an empty body, matching the HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED) that
            // security/SecurityConfig installs for every other route — see docs/DECISION_LOG.md
            // DL-078. Only the exception's type is logged: never the submitted principal name, never
            // the submitted password.
            log.warn("A credential submitted to POST /auth/token did not authenticate: {}",
                    ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // DL-018: the claim set is sub, iat and exp. DL-079: the subject is the resolved principal
        // name. DL-017: the lifetime is converted once, by JwtService.getExpirationSeconds().
        String token = jwtService.generateToken(authentication.getName());
        TokenResponse body = new TokenResponse(token, TOKEN_TYPE, jwtService.getExpirationSeconds());

        log.info("Issued a bearer token valid for {} second(s)", body.expiresIn());

        return ResponseEntity.ok(body);
    }
}
