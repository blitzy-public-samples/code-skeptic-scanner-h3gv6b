package com.codeskeptic.scanner.api;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
import com.codeskeptic.scanner.util.LogSafe;

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
 * <p>This class declares that one route and no other, and no operation over the credential store.
 * The path is unprefixed, as the paths of the four retired blueprints were: it carries no
 * {@code /api} segment and no version segment, and no type-level request mapping contributes a
 * prefix.
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
 * {@code expiration-minutes: 60}; the value on the wire is {@code 3600}.
 *
 * <p>Authorization for this route is declared in the security filter chain:
 * {@code security.SecurityConfig} permits {@code POST /auth/token} with no authentication and requires
 * an authenticated principal on every other request — DL-019, DL-021.
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
 * <p>No submitted password, no submitted principal name and no minted token is written to the log at
 * any level — DL-052. The resolved principal of a successful issuance reaches the log as the
 * correlation token {@code util/LogSafe} derives from it, never as its own text — DL-197.
 *
 * <p>The credential-verification work this process performs at one time is bounded: a submitted
 * credential of accepted length acquires one of {@link #MAXIMUM_CONCURRENT_VERIFICATIONS} permits,
 * waiting at most {@value #VERIFICATION_WAIT_MILLIS} milliseconds for it, and reaches bcrypt only
 * while it holds one. A request that acquires none is answered with the same 401 and the same empty
 * body a rejected credential receives, and no bcrypt computation is performed for it — DL-272.
 *
 * <p>This class declares no per-client request quota and no request-rate ceiling on
 * {@code POST /auth/token}; those remain ingress controls the deployment supplies — DL-272.
 *
 * <p>Rejection reporting is bounded: a rejection is reported at {@code WARN} at most once per
 * {@value #REJECTION_REPORT_INTERVAL_SECONDS} seconds per reason, carrying the number suppressed
 * after the previous record, and every suppressed rejection is written at {@code DEBUG} — DL-272.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-017, DL-018,
 * DL-019, DL-020, DL-021, DL-052, DL-079, DL-117, DL-118 and DL-272; construct-level provenance is
 * recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This class is a singleton bean and is thread-safe. Its mutable state is the six
 * {@link java.util.concurrent.atomic.AtomicLong} fields the rejection reporter carries and the
 * {@link Semaphore} bounding verification work; no response status, header or body is derived from any
 * of the counters, and the semaphore selects between the 401 this route already returns and the
 * verification path.
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

    /** Fewest verification permits this process issues, whatever the processor count — DL-272. */
    private static final int MINIMUM_CONCURRENT_VERIFICATIONS = 2;

    /**
     * Credential verifications this process performs at one time — DL-272.
     *
     * <p>The value is the greater of {@value #MINIMUM_CONCURRENT_VERIFICATIONS} and the processor
     * count the runtime reports, read once at class initialisation. It is the number of bcrypt
     * computations this process performs at one time on behalf of this route.
     */
    private static final int MAXIMUM_CONCURRENT_VERIFICATIONS = Math.max(
            MINIMUM_CONCURRENT_VERIFICATIONS, Runtime.getRuntime().availableProcessors());

    /**
     * Longest a request waits for one of the {@link #MAXIMUM_CONCURRENT_VERIFICATIONS} verification
     * permits, in milliseconds — DL-272. A request that does not hold a permit by this bound is
     * answered with the route's 401 and reaches bcrypt in no way.
     */
    private static final long VERIFICATION_WAIT_MILLIS = 250L;

    /**
     * Shortest span between two {@code WARN} records reporting rejected credentials of the same
     * reason, in seconds. A rejection arriving inside the span is counted and reported by the next
     * record — DL-272.
     */
    private static final long REJECTION_REPORT_INTERVAL_SECONDS = 60L;

    /** {@value #REJECTION_REPORT_INTERVAL_SECONDS} seconds in nanoseconds — DL-272. */
    private static final long REJECTION_REPORT_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(REJECTION_REPORT_INTERVAL_SECONDS);

    /** Rejections for an over-length member not yet carried by a {@code WARN} record — DL-272. */
    private final AtomicLong unreportedOverLength = new AtomicLong();

    /** Reading of {@link System#nanoTime()} at the last over-length {@code WARN} — DL-272. */
    private final AtomicLong lastOverLengthReportNanos = new AtomicLong();

    /** Rejections by the manager not yet carried by a {@code WARN} record — DL-272. */
    private final AtomicLong unreportedNotAuthenticated = new AtomicLong();

    /** Reading of {@link System#nanoTime()} at the last not-authenticated {@code WARN} — DL-272. */
    private final AtomicLong lastNotAuthenticatedReportNanos = new AtomicLong();

    /** Requests turned away for want of a verification permit, not yet reported — DL-272. */
    private final AtomicLong unreportedUnverified = new AtomicLong();

    /** Reading of {@link System#nanoTime()} at the last permit-exhaustion {@code WARN} — DL-272. */
    private final AtomicLong lastUnverifiedReportNanos = new AtomicLong();

    /**
     * Permits bounding the credential verifications in progress at one time — DL-272.
     *
     * <p>{@link #MAXIMUM_CONCURRENT_VERIFICATIONS} permits are issued. A request acquires one before
     * it reaches {@link AuthenticationManager#authenticate}, waits at most
     * {@value #VERIFICATION_WAIT_MILLIS} milliseconds for it, and releases it as the verification
     * returns or raises. A request that acquires none is answered with the route's 401 and no bcrypt
     * computation is performed for it.
     */
    private final Semaphore verificationPermits =
            new Semaphore(MAXIMUM_CONCURRENT_VERIFICATIONS);

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
     * authentication the {@link AuthenticationManager} returned, not the submitted string — DL-079.
     *
     * <p>A {@code null} request, a request whose {@code username} member is absent and a request
     * whose {@code password} member is absent are each carried to the {@link AuthenticationManager}
     * as a {@code null} principal or a {@code null} credential, and the manager rejects each of them.
     * A member longer than {@value #MAXIMUM_CREDENTIAL_LENGTH} characters is rejected here, ahead of
     * both the manager and bcrypt, and reports the same 401 — DL-118.
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
            // Bounded reporting: an attempt flood yields one record per interval, not one per
            // attempt — DL-272 — see docs/DECISION_LOG.md
            reportRejection(unreportedOverLength, lastOverLengthReportNanos,
                    "exceeded the accepted length of {} characters", MAXIMUM_CREDENTIAL_LENGTH);
            return unauthorized();
        }

        // Bounded verification work: at most MAXIMUM_CONCURRENT_VERIFICATIONS bcrypt computations run
        // at one time in this process — DL-272 — see docs/DECISION_LOG.md
        if (!acquireVerificationPermit()) {
            reportRejection(unreportedUnverified, lastUnverifiedReportNanos,
                    "was not verified: all {} verification permit(s) were held",
                    MAXIMUM_CONCURRENT_VERIFICATIONS);
            return unauthorized();
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (BadCredentialsException | UsernameNotFoundException
                | AccountStatusException rejected) {
            // Only the exception's type is logged: never the submitted principal name, never the
            // submitted password, never the provider's message. Bounded reporting — DL-272.
            reportRejection(unreportedNotAuthenticated, lastNotAuthenticatedReportNanos,
                    "did not authenticate: {}", LogSafe.type(rejected));
            return unauthorized();
        } finally {
            verificationPermits.release();
        }

        // DL-018: the claim set is sub, iat and exp, and the subject is the resolved principal name.
        // DL-017: the lifetime is converted once, by JwtService.getExpirationSeconds().
        String token = jwtService.generateToken(authentication.getName());
        TokenResponse body = new TokenResponse(token, TOKEN_TYPE, jwtService.getExpirationSeconds());

        // The principal reaches the log as a correlation token only, never as its own text — see
        // docs/DECISION_LOG.md DL-197
        log.info("Issued a bearer token to principal {}, valid for {} second(s)",
                LogSafe.correlation(authentication.getName()), body.expiresIn());

        return ResponseEntity.ok(body);
    }

    // Net-new bound on the credential-verification work in progress — DL-272 — see
    // docs/DECISION_LOG.md
    /**
     * Acquires one of the {@link #MAXIMUM_CONCURRENT_VERIFICATIONS} verification permits.
     *
     * <p>The call waits at most {@value #VERIFICATION_WAIT_MILLIS} milliseconds. An interrupt while
     * waiting restores the thread's interrupt status and is answered as an unacquired permit, so the
     * request is reported with the route's 401 and no verification is started for it.
     *
     * @return {@code true} when a permit is held, which the caller releases; {@code false} when the
     *     wait elapsed or the thread was interrupted
     */
    private boolean acquireVerificationPermit() {
        try {
            return verificationPermits.tryAcquire(VERIFICATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    // Net-new bounded reporting of rejected credentials — DL-272 — see docs/DECISION_LOG.md
    /**
     * Records one rejected credential, at most once per
     * {@value #REJECTION_REPORT_INTERVAL_SECONDS} seconds for the supplied reason.
     *
     * <p>The first rejection of a reason is reported at {@code WARN} immediately: the two report-time
     * counters start at zero, and a zero reading is treated as due. Each later rejection increments the
     * counter; the next record that falls due carries how many were suppressed and is written at
     * {@code WARN}, and a suppressed rejection is written at {@code DEBUG}. Two rejections that fall
     * due at the same instant leave one record, since the report time is advanced with a
     * compare-and-set — see docs/DECISION_LOG.md DL-272.
     *
     * <p>Nothing about the submitted credential is recorded: no principal name, no password, no
     * length and no client address — DL-052.
     *
     * @param unreported      the counter of rejections of this reason not yet reported
     * @param lastReportNanos the reading of {@link System#nanoTime()} at the last record of this
     *     reason
     * @param reason          a fixed description of the rejection, holding exactly one {@code {}}
     *     placeholder for {@code detail}
     * @param detail          the value of that placeholder, which is never any part of the submitted
     *     credential
     */
    private static void reportRejection(AtomicLong unreported, AtomicLong lastReportNanos,
            String reason, Object detail) {
        unreported.incrementAndGet();

        long now = System.nanoTime();
        long previous = lastReportNanos.get();
        boolean due = previous == 0L || now - previous >= REJECTION_REPORT_INTERVAL_NANOS;
        if (!due || !lastReportNanos.compareAndSet(previous, now)) {
            log.debug("A credential submitted to POST /auth/token " + reason, detail);
            return;
        }

        long rejected = unreported.getAndSet(0L);
        log.warn("{} credential(s) submitted to POST /auth/token in the last {}s " + reason,
                rejected, REJECTION_REPORT_INTERVAL_SECONDS, detail);
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
