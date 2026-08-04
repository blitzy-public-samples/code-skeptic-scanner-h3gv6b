package com.codeskeptic.scanner.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.LoginRequest;
import com.codeskeptic.scanner.dto.TokenResponse;
import com.codeskeptic.scanner.security.JwtService;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019, DL-117, DL-118
/**
 * Serves the one HTTP route that issues a bearer token.
 *
 * <p>This class has no counterpart in the retired Python tree — DL-019.
 * {@code backend/app/main.py:L26-29} registered four blueprints — {@code tweets_bp},
 * {@code responses_bp}, {@code settings_bp} and {@code analytics_bp} — and none declared an
 * authentication route. {@code create_access_token} at {@code backend/app/core/security.py:L6-12} and
 * the {@code passlib} helpers at {@code :L14-18} had no call site in the retired tree.
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
 * {@link LoginRequest}. That record declares no Bean Validation constraint and this class declares no
 * {@code @Valid}, so an absent body and an absent or {@code null} member each reach
 * {@link #issueToken(LoginRequest)} as {@code null}. A member longer than
 * {@value #MAXIMUM_CREDENTIAL_LENGTH} characters is rejected before the
 * {@link AuthenticationManager} is reached — DL-118.
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
 * <p>The credential check is performed by the injected {@link AuthenticationManager}, which
 * {@code security.SecurityConfig} publishes from the application's
 * {@code AuthenticationConfiguration}. That manager resolves the single {@code scanner.auth}
 * principal through the {@code InMemoryUserDetailsManager} and compares the presented password
 * against the stored hash through the {@code BCryptPasswordEncoder} — DL-020. This class declares no
 * {@code AuthenticationProvider}, holds no {@code UserDetailsService} and holds no
 * {@code PasswordEncoder}. It reads no entity, holds no repository and adds no table: the schema this
 * service creates stays the four tables of {@code backend/app/db/models.py}.
 *
 * <p>Nothing here publishes to X: this class declares no ingestion and no response-publishing
 * operation. No submitted password, no submitted principal name and no minted token is written to the
 * log at any level — DL-052.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-017, DL-018,
 * DL-019, DL-020, DL-021, DL-052, DL-079, DL-117 and DL-118; construct-level provenance is
 * recorded in {@code docs/TRACEABILITY_MATRIX.md}.
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
     * matches that header's scheme name without regard to case; this member is the OAuth 2 token-type
     * value and is not that header.
     */
    private static final String TOKEN_TYPE = "bearer";

    /**
     * Longest {@code username} and {@code password} this route reads — DL-118. A longer member is
     * answered with the same 401 and the same empty body as a credential that does not
     * authenticate.
     */
    private static final int MAXIMUM_CREDENTIAL_LENGTH = 256;

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

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019, DL-117, DL-118
    /**
     * Authenticates a submitted credential and returns a freshly minted bearer token.
     *
     * <p>The status is 200 on success, 401 when the submitted credential is rejected, and 500 when
     * the authentication backend itself fails; this method selects no fourth status.
     *
     * <p>The {@code sub} claim of the minted token is {@link Authentication#getName()} of the
     * authentication the {@link AuthenticationManager} returned — the resolved principal name, which
     * is present on every authentication that manager completes — and not the submitted string —
     * DL-079.
     *
     * <p>A {@code null} request, a request whose {@code username} member is absent and a request
     * whose {@code password} member is absent are each carried to the {@link AuthenticationManager}
     * as a {@code null} principal or a {@code null} credential, and the manager rejects each of them.
     * A member longer than {@value #MAXIMUM_CREDENTIAL_LENGTH} characters is rejected here, before
     * the manager and therefore before bcrypt, and reports the same 401 — DL-118.
     *
     * <p>Only a rejected credential yields 401: {@link BadCredentialsException},
     * {@link UsernameNotFoundException} and {@link AccountStatusException} and their subtypes. Every
     * other {@link AuthenticationException} — {@code AuthenticationServiceException} and
     * {@code InternalAuthenticationServiceException} among them — propagates to
     * {@link GlobalExceptionHandler}, which answers 500 with
     * {@code {"error": "Internal server error"}} — DL-117.
     *
     * <p>The 401 carries an empty body — the same status and the same empty body that the entry point
     * of {@code security.SecurityConfig} returns for an unauthenticated request to any other route.
     * No {@code {"error": <string>}} envelope is built here, and this route puts no new string on the
     * wire — DL-019.
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
     *     empty body when the submitted credential is rejected
     */
    @PostMapping("/auth/token")
    public ResponseEntity<TokenResponse> issueToken(
            @RequestBody(required = false) LoginRequest request) {

        // An absent body and an absent member are read alike, as an unsupplied value.
        String username = (request == null) ? null : request.username();
        String password = (request == null) ? null : request.password();

        if (exceedsLengthCeiling(username) || exceedsLengthCeiling(password)) {
            log.warn("A credential submitted to POST /auth/token exceeded the accepted length");
            return unauthorized();
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (BadCredentialsException | UsernameNotFoundException
                | AccountStatusException rejected) {
            // Only the exception's type is logged: never the submitted principal name, never the
            // submitted password, never the provider's message.
            log.warn("A credential submitted to POST /auth/token did not authenticate: {}",
                    rejected.getClass().getSimpleName());
            return unauthorized();
        }

        // DL-018: the claim set is sub, iat and exp. DL-079: the subject is the resolved principal
        // name. DL-017: the lifetime is converted once, by JwtService.getExpirationSeconds().
        String token = jwtService.generateToken(authentication.getName());
        TokenResponse body = new TokenResponse(token, TOKEN_TYPE, jwtService.getExpirationSeconds());

        log.info("Issued a bearer token valid for {} second(s)", body.expiresIn());

        return ResponseEntity.ok(body);
    }

    /**
     * Reports whether a submitted member is longer than this route reads.
     *
     * @param credential the submitted {@code username} or {@code password}, which may be {@code null}
     * @return {@code true} when the value is present and longer than
     *     {@value #MAXIMUM_CREDENTIAL_LENGTH} characters
     */
    private static boolean exceedsLengthCeiling(String credential) {
        return credential != null && credential.length() > MAXIMUM_CREDENTIAL_LENGTH;
    }

    /**
     * Builds the one rejection response this route returns: 401 with an empty body.
     *
     * @return the 401 response; never {@code null}
     */
    private static ResponseEntity<TokenResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
