package com.codeskeptic.scanner.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codeskeptic.scanner.config.ScannerProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Minting is ported from backend/app/core/security.py:L6-12 (faithful port) — see
// docs/DECISION_LOG.md DL-014, DL-015, DL-016, DL-017, DL-018.
// Token parsing: net-new (PyJWT decode imported at backend/app/core/security.py:L1, never used) —
// see docs/DECISION_LOG.md DL-014, DL-108, DL-109, DL-110, DL-111.
/**
 * Mints and parses the compact JWS this service issues to its own clients.
 *
 * <p>A minted token carries exactly {@code sub}, {@code iat} and {@code exp}, signed with
 * {@value #REQUIRED_ALGORITHM}; {@code exp} is {@code iat} advanced by the configured lifetime —
 * DL-015, DL-018.
 *
 * <p>A presented token is accepted only when its signature verifies, its {@code alg} header names
 * {@value #REQUIRED_ALGORITHM}, its claim set is exactly those three names, its {@code sub} is
 * non-blank and its {@code exp} lies strictly after its {@code iat} — DL-108, DL-109. Anything else
 * yields {@link Optional#empty()}: no jjwt type appears in a signature here and no jjwt exception
 * leaves this class.
 *
 * <p>Neither the secret, the derived key, a token value, a claim value nor a parser failure message
 * is written to the log — DL-111.
 *
 * <p>Singleton bean, thread-safe: all three fields are {@code final} and hold immutable or
 * thread-safe state.
 */
@Service
public class JwtService {

    /** Binding leaves an unresolved placeholder in place as literal text — DL-186. */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String REQUIRED_ALGORITHM = "HS256";

    private static final long MINIMUM_EXPIRATION_MINUTES = 1L;

    private static final long MAXIMUM_EXPIRATION_MINUTES = 60L;

    private static final long SECONDS_PER_MINUTE = 60L;

    /** Floor applied to the UTF-8 bytes of the configured text, not to any decoded form — DL-186. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private static final int BITS_PER_BYTE = 8;

    private static final String MISSING_SECRET_MESSAGE =
            "scanner.jwt.secret is not configured; supply it through the SECRET_KEY environment "
                    + "variable. It has no default value.";

    private static final Set<String> REQUIRED_CLAIM_NAMES = Set.of(
            Claims.SUBJECT, Claims.ISSUED_AT, Claims.EXPIRATION);

    private final SecretKey signingKey;

    /**
     * Registry reduced to {@value #REQUIRED_ALGORITHM}, so another algorithm is rejected before the
     * signature is checked — DL-108.
     */
    private final JwtParser parser;

    private final long expirationMinutes;

    /**
     * Resolves the signing key, the parser and the token lifetime from configuration.
     *
     * <p>The signing key is the trimmed {@code scanner.jwt.secret} read as raw UTF-8 bytes, with no
     * decoding step of any kind — DL-186. All three are resolved once here, replacing the per-call
     * {@code get_settings()} at {@code backend/app/core/config.py:L17-18} that the source invoked at
     * {@code backend/app/core/security.py:L7}.
     *
     * @param properties the bound configuration root; its {@code scanner.jwt} group supplies the
     *     secret, the algorithm name and the lifetime
     * @throws NullPointerException if {@code properties} is {@code null}
     * @throws IllegalStateException if {@code scanner.jwt.secret} is absent, blank or an unresolved
     *     {@code ${SECRET_KEY}} placeholder; if its UTF-8 bytes number fewer than
     *     {@value #MINIMUM_SECRET_BYTES}; if {@code scanner.jwt.algorithm} names anything other than
     *     {@value #REQUIRED_ALGORITHM}; or if {@code scanner.jwt.expiration-minutes} lies outside
     *     {@value #MINIMUM_EXPIRATION_MINUTES}..{@value #MAXIMUM_EXPIRATION_MINUTES}
     */
    public JwtService(ScannerProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        ScannerProperties.Jwt jwtProperties = properties.jwt();
        byte[] keyMaterial = requireConfiguredSecret(
                (jwtProperties == null) ? null : jwtProperties.secret());

        requireSupportedAlgorithm(jwtProperties.algorithm());

        this.signingKey = Keys.hmacShaKeyFor(keyMaterial);
        this.expirationMinutes = requireSupportedLifetime(jwtProperties.expirationMinutes());
        this.parser = Jwts.parser()
                .verifyWith(this.signingKey)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .build();

        log.info("JwtService initialised; tokens are signed and verified with {} only and live {} "
                + "minute(s)", REQUIRED_ALGORITHM, this.expirationMinutes);
    }

    /**
     * Mints a signed token for the given principal name.
     *
     * <p>The three claims replace the caller-supplied dictionary copied into the claim set at
     * {@code backend/app/core/security.py:L8} — DL-018 — and reproduce
     * {@code datetime.utcnow() + expires_delta} at {@code :L9-10}. {@link Instant#now()} is read once,
     * so both time claims derive from a single instant.
     *
     * @param username the principal name to carry in the {@code sub} claim; must be neither
     *     {@code null} nor blank
     * @return the compact JWS serialization
     * @throws NullPointerException if {@code username} is {@code null}
     * @throws IllegalArgumentException if {@code username} is blank
     */
    public String generateToken(String username) {
        Objects.requireNonNull(username, "username must not be null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }

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
     * Reads the {@code sub} claim from a token this service accepts.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the principal name, or {@link Optional#empty()} if the token is absent, is signed with
     *     any algorithm other than {@value #REQUIRED_ALGORITHM}, does not verify, is expired, or
     *     does not carry exactly a non-blank {@code sub} with a consistent {@code iat} and
     *     {@code exp}
     */
    public Optional<String> extractUsername(String token) {
        return parseClaims(token).map(Claims::getSubject);
    }

    /**
     * Reads the {@code exp} claim from a token this service accepts.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the expiry instant, or {@link Optional#empty()} under the same conditions
     *     {@link #extractUsername(String)} documents
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
     * is the one place that converts between them — DL-017. Checked arithmetic throws on overflow
     * — DL-110.
     *
     * @return the lifetime of a minted token in seconds
     */
    public long getExpirationSeconds() {
        return Math.multiplyExact(expirationMinutes, SECONDS_PER_MINUTE);
    }

    /**
     * Verifies a token once and returns its claim set only when the token satisfies every policy this
     * service enforces.
     *
     * <p>The single parsing path behind {@link #extractUsername(String)} and
     * {@link #extractExpiration(String)}, so a token is parsed exactly once per call. Checks run in
     * the order signature, {@code alg} header, claim set, claim values — DL-108, DL-109.
     *
     * @param token the compact JWS to verify, which may be {@code null}
     * @return the accepted claim set, or {@link Optional#empty()} otherwise
     */
    private Optional<Claims> parseClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Jws<Claims> jws;
        try {
            jws = parser.parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            // Only the failure category is recorded, at DEBUG. Neither the token, nor any header or
            // claim value, nor the parser's own message reaches the log — DL-111.
            log.debug("A presented JWT did not verify");
            return Optional.empty();
        }

        if (!REQUIRED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            log.debug("A presented JWT declared an algorithm this service does not accept");
            return Optional.empty();
        }

        Claims claims = jws.getPayload();
        if (!REQUIRED_CLAIM_NAMES.equals(claims.keySet())) {
            log.debug("A presented JWT did not carry exactly the accepted claim set");
            return Optional.empty();
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            log.debug("A presented JWT carried a blank subject");
            return Optional.empty();
        }

        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null || expiration == null || !expiration.after(issuedAt)) {
            log.debug("A presented JWT carried an inconsistent issued-at and expiration pair");
            return Optional.empty();
        }

        return Optional.of(claims);
    }

    /**
     * Reads a configured secret as the key material {@value #REQUIRED_ALGORITHM} signs and verifies
     * with.
     *
     * <p>The trimmed value's own UTF-8 bytes are the key material: no encoding is applied and none is
     * expected, so any character a configuration value can carry is accepted — DL-186. {@code null},
     * a blank value and an unresolved {@code ${SECRET_KEY}} placeholder all raise the same message.
     * No failure message reproduces any part of the value or states its length — DL-111.
     *
     * @param configuredSecret value of {@code scanner.jwt.secret}, which may be {@code null}
     * @return the key material, at least {@value #MINIMUM_SECRET_BYTES} bytes long
     * @throws IllegalStateException if the value carries nothing, or its UTF-8 bytes number fewer than
     *     {@value #MINIMUM_SECRET_BYTES}
     */
    // The raw configured secret is the key material, as at backend/app/core/security.py:L11 — DL-186
    // — see docs/DECISION_LOG.md
    private static byte[] requireConfiguredSecret(String configuredSecret) {
        if (isUnset(configuredSecret)) {
            throw new IllegalStateException(MISSING_SECRET_MESSAGE);
        }

        byte[] keyMaterial = configuredSecret.trim().getBytes(StandardCharsets.UTF_8);

        if (keyMaterial.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "scanner.jwt.secret carries fewer than "
                            + (MINIMUM_SECRET_BYTES * BITS_PER_BYTE) + " bits, which is what "
                            + REQUIRED_ALGORITHM + " requires. Set the SECRET_KEY environment "
                            + "variable to at least " + MINIMUM_SECRET_BYTES + " bytes of key "
                            + "material, for example the output of `openssl rand -base64 "
                            + MINIMUM_SECRET_BYTES + "`. The configured value is not reproduced "
                            + "here.");
        }
        return keyMaterial;
    }

    /**
     * Confirms that a configured algorithm name is {@value #REQUIRED_ALGORITHM}.
     *
     * <p>{@code scanner.jwt.algorithm} carries the {@code ALGORITHM} key read at
     * {@code backend/app/core/security.py:L11}. An absent or blank value passes and leaves
     * {@value #REQUIRED_ALGORITHM} in force — DL-108.
     *
     * @param configuredAlgorithm value of {@code scanner.jwt.algorithm}, which may be {@code null}
     * @throws IllegalStateException if the value names any other algorithm
     */
    private static void requireSupportedAlgorithm(String configuredAlgorithm) {
        if (configuredAlgorithm == null || configuredAlgorithm.isBlank()) {
            return;
        }
        String normalised = configuredAlgorithm.trim().toUpperCase(Locale.ROOT);
        if (!REQUIRED_ALGORITHM.equals(normalised)) {
            throw new IllegalStateException(
                    "scanner.jwt.algorithm is set to '" + configuredAlgorithm
                            + "'; this service supports " + REQUIRED_ALGORITHM + " only.");
        }
    }

    /**
     * Confirms that a configured token lifetime lies within the accepted range.
     *
     * @param configuredMinutes value of {@code scanner.jwt.expiration-minutes}
     * @return the validated lifetime
     * @throws IllegalStateException if the value lies outside that range
     */
    private static long requireSupportedLifetime(long configuredMinutes) {
        if (configuredMinutes < MINIMUM_EXPIRATION_MINUTES
                || configuredMinutes > MAXIMUM_EXPIRATION_MINUTES) {
            throw new IllegalStateException(
                    "scanner.jwt.expiration-minutes is set to " + configuredMinutes
                            + "; it must lie between " + MINIMUM_EXPIRATION_MINUTES + " and "
                            + MAXIMUM_EXPIRATION_MINUTES + " inclusive.");
        }
        return configuredMinutes;
    }

    /**
     * Reports whether a bound configuration value carries no usable configuration.
     *
     * <p>Binding leaves an unresolved {@code ${...}} placeholder in place as literal text when the
     * environment variable behind it is absent, so such a value is neither {@code null} nor blank and
     * is treated as unset here — DL-186.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is {@code null}, blank, or an unresolved placeholder
     */
    private static boolean isUnset(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

}
