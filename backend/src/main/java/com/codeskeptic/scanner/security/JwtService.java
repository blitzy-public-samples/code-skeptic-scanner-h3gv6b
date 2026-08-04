package com.codeskeptic.scanner.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codeskeptic.scanner.config.ScannerProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Minting is ported from backend/app/core/security.py:L6-12 (faithful port) — see
// docs/DECISION_LOG.md DL-014, DL-015, DL-016, DL-017, DL-018.
// Token parsing: net-new (PyJWT decode imported at backend/app/core/security.py:L1, never used) —
// see docs/DECISION_LOG.md DL-014.
/**
 * Mints and parses the compact JWS this service issues to its own clients.
 *
 * <p>Four operations are exposed. {@link #generateToken(String)} mints a token for a principal name,
 * replacing {@code create_access_token(data, expires_delta)} at
 * {@code backend/app/core/security.py:L6-12}. {@link #extractUsername(String)} and
 * {@link #extractExpiration(String)} verify a presented token's signature and read one claim from
 * it. {@link #getExpirationSeconds()} reports the configured token lifetime converted to seconds.
 *
 * <p>Every minted token carries exactly three claims — {@code sub}, {@code iat} and {@code exp} —
 * and is signed with HS256 — DL-015, DL-018. The signing key is derived once, at construction, from
 * {@code scanner.jwt.secret}; the source read its settings on every call at
 * {@code backend/app/core/security.py:L7}.
 *
 * <p>Construction fails with {@link IllegalStateException} when {@code scanner.jwt.secret} is absent
 * or blank — DL-016 — and when {@code scanner.jwt.algorithm} names anything outside the HS family —
 * DL-015. A secret shorter than the 256 bits HS256 requires fails with
 * {@link io.jsonwebtoken.security.WeakKeyException} from {@link Keys#hmacShaKeyFor(byte[])}.
 *
 * <p>No jjwt type appears in any signature here and no jjwt exception leaves this class: both
 * extraction methods answer {@link Optional#empty()} for every token they cannot verify. Neither the
 * secret, the derived key nor any token value is written to the log.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-014, DL-015,
 * DL-016, DL-017 and DL-018; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Both fields are {@code final} and hold immutable state, and each
 * operation builds its own short-lived builder or parser; every member declared here is safe for
 * concurrent use.
 */
@Service
public class JwtService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * Algorithm names {@code scanner.jwt.algorithm} may carry, compared after trimming and
     * upper-casing. Signing always applies {@code Jwts.SIG.HS256} — DL-015.
     */
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("HS256", "HS384", "HS512");

    /** Multiplier applied by {@link #getExpirationSeconds()} to the configured lifetime in minutes. */
    private static final long SECONDS_PER_MINUTE = 60L;

    /**
     * HMAC-SHA key derived from {@code scanner.jwt.secret}, used to sign every minted token and to
     * verify every presented one.
     */
    private final SecretKey signingKey;

    /**
     * Token lifetime in minutes, bound from {@code scanner.jwt.expiration-minutes}. This replaces
     * the {@code expires_delta} argument the source required from each caller at
     * {@code backend/app/core/security.py:L6}.
     */
    private final long expirationMinutes;

    /**
     * Resolves the signing key and the token lifetime from configuration.
     *
     * <p>Both values are read once here, replacing the per-call {@code get_settings()} at
     * {@code backend/app/core/config.py:L17-18} that the source invoked at
     * {@code backend/app/core/security.py:L7}.
     *
     * @param properties the bound configuration root; its {@code scanner.jwt} group supplies the
     *     secret, the algorithm name and the lifetime
     * @throws NullPointerException if {@code properties} is {@code null}
     * @throws IllegalStateException if {@code scanner.jwt.secret} is absent or blank, or if
     *     {@code scanner.jwt.algorithm} names an algorithm outside the HS family
     * @throws io.jsonwebtoken.security.WeakKeyException if the secret is shorter than the 256 bits
     *     HS256 requires
     */
    public JwtService(ScannerProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        // A null jwt group and a null secret are both read here as an unsupplied secret.
        ScannerProperties.Jwt jwtProperties = properties.jwt();
        String secret = (jwtProperties == null) ? null : jwtProperties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "scanner.jwt.secret is not configured; supply it through the SECRET_KEY "
                            + "environment variable. It has no default value.");
        }

        requireSupportedAlgorithm(jwtProperties.algorithm());

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = jwtProperties.expirationMinutes();

        log.info("JwtService initialised; minted tokens are signed with {} and live {} minute(s)",
                Jwts.SIG.HS256.getId(), this.expirationMinutes);
    }

    /**
     * Mints a signed token for the given principal name.
     *
     * <p>The token carries exactly the {@code sub}, {@code iat} and {@code exp} claims — DL-018. The
     * source copied an arbitrary caller-supplied dictionary into the claim set at
     * {@code backend/app/core/security.py:L8}. {@code exp} is {@code iat} advanced by the configured
     * lifetime, which reproduces {@code datetime.utcnow() + expires_delta} at
     * {@code backend/app/core/security.py:L9-10}. {@link Instant#now()} is read once, and both
     * claims derive from that single instant.
     *
     * @param username the principal name to carry in the {@code sub} claim
     * @return the compact JWS serialization
     * @throws NullPointerException if {@code username} is {@code null}
     */
    public String generateToken(String username) {
        Objects.requireNonNull(username, "username must not be null");

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(expirationMinutes));

        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Reads the {@code sub} claim from a token whose signature verifies against the signing key.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the principal name, or {@link Optional#empty()} if the token is absent, unverifiable
     *     or expired, or if it carries no {@code sub} claim
     */
    public Optional<String> extractUsername(String token) {
        return parseClaims(token).map(Claims::getSubject);
    }

    /**
     * Reads the {@code exp} claim from a token whose signature verifies against the signing key.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the expiry instant, or {@link Optional#empty()} if the token is absent, unverifiable
     *     or expired, or if it carries no {@code exp} claim
     */
    public Optional<Instant> extractExpiration(String token) {
        return parseClaims(token)
                .map(Claims::getExpiration)
                .map(Date::toInstant);
    }

    /**
     * Reports the configured token lifetime in seconds.
     *
     * <p>{@code scanner.jwt.expiration-minutes} is expressed in minutes and
     * {@code dto/TokenResponse.expiresIn} serializes as {@code expires_in} in seconds; this method
     * is the one place that converts between them — DL-017.
     *
     * @return the lifetime of a minted token in seconds
     */
    public long getExpirationSeconds() {
        return expirationMinutes * SECONDS_PER_MINUTE;
    }

    /**
     * Verifies a token's signature and returns its claim set.
     *
     * <p>This is the single parsing path behind {@link #extractUsername(String)} and
     * {@link #extractExpiration(String)}. Every failure jjwt reports — a bad signature, an expired
     * token, a malformed token, an unsupported token — is answered with {@link Optional#empty()};
     * no jjwt exception type escapes this class. The failure is logged without the token itself.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the verified claim set, or {@link Optional#empty()} if the token cannot be verified
     */
    private Optional<Claims> parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Verification of a presented JWT failed: {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Confirms that a configured algorithm name belongs to the HS family.
     *
     * <p>{@code scanner.jwt.algorithm} carries the {@code ALGORITHM} key read at
     * {@code backend/app/core/security.py:L11}. It guards the configured value and never selects the
     * signing algorithm, which is fixed at HS256 — DL-015. An absent or blank value passes.
     *
     * @param configuredAlgorithm value of {@code scanner.jwt.algorithm}, which may be {@code null}
     * @throws IllegalStateException if the value names an algorithm outside the HS family
     */
    private static void requireSupportedAlgorithm(String configuredAlgorithm) {
        if (configuredAlgorithm == null || configuredAlgorithm.isBlank()) {
            return;
        }
        String normalised = configuredAlgorithm.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ALGORITHMS.contains(normalised)) {
            throw new IllegalStateException(
                    "scanner.jwt.algorithm is set to '" + configuredAlgorithm
                            + "'; this service supports the HS family only: HS256, HS384, HS512.");
        }
    }
}
