package com.codeskeptic.scanner.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>Four operations are exposed. {@link #generateToken(String)} mints a token for a principal name,
 * replacing {@code create_access_token(data, expires_delta)} at
 * {@code backend/app/core/security.py:L6-12}. {@link #extractUsername(String)} and
 * {@link #extractExpiration(String)} verify a presented token and read one claim from it.
 * {@link #getExpirationSeconds()} reports the configured token lifetime converted to seconds.
 *
 * <p>Every minted token carries exactly three claims — {@code sub}, {@code iat} and {@code exp} —
 * and is signed with HS256 — DL-015, DL-018. The signing key is derived once, at construction, from
 * {@code scanner.jwt.secret}.
 *
 * <p>Verification accepts HS256 and nothing else — DL-108. The parser is built once, with its
 * signature-algorithm registry reduced to HS256, and every verified token's {@code alg} header is
 * additionally compared against {@value #REQUIRED_ALGORITHM}.
 *
 * <p>A verified token is accepted only when its claim set is exactly {@code sub}, {@code iat} and
 * {@code exp}, its {@code sub} is non-blank and its {@code exp} lies strictly after its
 * {@code iat} — DL-109.
 *
 * <p>Construction fails with {@link IllegalStateException} when {@code scanner.jwt.secret} carries
 * nothing — it is absent, blank, or still holds the unresolved {@code ${SECRET_KEY}} placeholder
 * text an unset environment variable leaves behind — DL-016, DL-185, DL-186 — when it supplies
 * fewer than the {@value #MINIMUM_SECRET_BYTES} bytes {@value #REQUIRED_ALGORITHM} requires —
 * DL-141, DL-186 — when {@code scanner.jwt.algorithm} names anything other than
 * {@value #REQUIRED_ALGORITHM} — DL-108 — and when {@code scanner.jwt.expiration-minutes} lies
 * outside {@value #MINIMUM_EXPIRATION_MINUTES}..{@value #MAXIMUM_EXPIRATION_MINUTES} — DL-110.
 * Every one of those messages names the property at fault together with the environment variable
 * that supplies it, and none reproduces the configured value — DL-111, DL-186.
 *
 * <p>No jjwt type appears in any signature here and no jjwt exception leaves this class: both
 * extraction methods answer {@link Optional#empty()} for every token they cannot accept. Neither the
 * secret, the derived key, any token value, any claim value nor any parser failure message is
 * written to the log — DL-111.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-014 … DL-018 and
 * DL-108 … DL-111; construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. All three fields are {@code final} and hold immutable or
 * thread-safe state — {@link JwtParser} instances are immutable once built — so every member
 * declared here is safe for concurrent use.
 */
@Service
public class JwtService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * The one algorithm {@code scanner.jwt.algorithm} may name, the one algorithm this service signs
     * with and the one algorithm its parser verifies — DL-108.
     */
    private static final String REQUIRED_ALGORITHM = "HS256";

    /** Smallest accepted value of {@code scanner.jwt.expiration-minutes} — DL-110. */
    private static final long MINIMUM_EXPIRATION_MINUTES = 1L;

    /** Largest accepted value of {@code scanner.jwt.expiration-minutes} — DL-110. */
    private static final long MAXIMUM_EXPIRATION_MINUTES = 60L;

    /** Multiplier applied by {@link #getExpirationSeconds()} to the configured lifetime in minutes. */
    private static final long SECONDS_PER_MINUTE = 60L;

    /**
     * Shortest accepted {@code scanner.jwt.secret}, in bytes of its UTF-8 encoding. HS256 requires a
     * key of {@value #MINIMUM_SECRET_BYTES} bytes — DL-186.
     */
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Bits per byte, applied when a rejected secret's length is reported in bits — DL-186. */
    private static final int BITS_PER_BYTE = 8;

    /**
     * Shape of a Spring property placeholder that resolved to nothing. Configuration binding leaves
     * such a placeholder in place as literal text when the environment variable behind it is absent,
     * so the bound value is neither {@code null} nor blank — DL-186.
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

    /**
     * Message of the {@link IllegalStateException} raised when {@code scanner.jwt.secret} carries
     * nothing: it is absent, blank, or an unresolved {@code ${SECRET_KEY}} placeholder — DL-016,
     * DL-186.
     */
    private static final String MISSING_SECRET_MESSAGE =
            "scanner.jwt.secret is not configured; supply it through the SECRET_KEY environment "
                    + "variable. It has no default value.";

    /** Names of the only claims a token this service accepts may carry — DL-109. */
    private static final Set<String> REQUIRED_CLAIM_NAMES = Set.of(
            Claims.SUBJECT, Claims.ISSUED_AT, Claims.EXPIRATION);

    /**
     * HMAC-SHA key derived from {@code scanner.jwt.secret}, used to sign every minted token and to
     * verify every presented one.
     */
    private final SecretKey signingKey;

    /**
     * Parser built once at construction. Its signature-algorithm registry holds
     * {@value #REQUIRED_ALGORITHM} only, so a token whose header names any other algorithm is
     * rejected before its signature is checked — DL-108.
     */
    private final JwtParser parser;

    /**
     * Token lifetime in minutes, bound from {@code scanner.jwt.expiration-minutes} and guaranteed to
     * lie within {@value #MINIMUM_EXPIRATION_MINUTES}..{@value #MAXIMUM_EXPIRATION_MINUTES}.
     */
    private final long expirationMinutes;

    /**
     * Resolves the signing key, the parser and the token lifetime from configuration.
     *
     * <p>All three are resolved once here, replacing the per-call {@code get_settings()} at
     * {@code backend/app/core/config.py:L17-18} that the source invoked at
     * {@code backend/app/core/security.py:L7}.
     *
     * @param properties the bound configuration root; its {@code scanner.jwt} group supplies the
     *     secret, the algorithm name and the lifetime
     * @throws NullPointerException if {@code properties} is {@code null}
     * @throws IllegalStateException if {@code scanner.jwt.secret} is absent, blank or an unresolved
     *     {@code ${SECRET_KEY}} placeholder, if it supplies fewer than
     *     {@value #MINIMUM_SECRET_BYTES} bytes, if {@code scanner.jwt.algorithm} names anything
     *     other than {@value #REQUIRED_ALGORITHM}, or if
     *     {@code scanner.jwt.expiration-minutes} lies outside
     *     {@value #MINIMUM_EXPIRATION_MINUTES}..{@value #MAXIMUM_EXPIRATION_MINUTES}
     */
    public JwtService(ScannerProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        // A null jwt group, a null secret, a blank secret and a secret still holding the unresolved
        // placeholder text are all read here as an unsupplied secret — DL-185, DL-186.
        ScannerProperties.Jwt jwtProperties = properties.jwt();
        String secret = requireConfiguredSecret(
                (jwtProperties == null) ? null : jwtProperties.secret());

        requireSupportedAlgorithm(jwtProperties.algorithm());

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
     * <p>The token carries exactly the {@code sub}, {@code iat} and {@code exp} claims — DL-018. The
     * source copied an arbitrary caller-supplied dictionary into the claim set at
     * {@code backend/app/core/security.py:L8}. {@code exp} is {@code iat} advanced by the configured
     * lifetime, which reproduces {@code datetime.utcnow() + expires_delta} at
     * {@code backend/app/core/security.py:L9-10}. {@link Instant#now()} is read once, and both
     * claims derive from that single instant.
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
     * Verifies a token once and returns its claim set only when the token satisfies every policy
     * this service enforces.
     *
     * <p>This is the single parsing path behind {@link #extractUsername(String)} and
     * {@link #extractExpiration(String)}: a token is parsed exactly once per call. Verification
     * covers the signature, the {@code alg} header, the claim set and the claim values, in that
     * order — DL-108, DL-109. Every failure is answered with {@link Optional#empty()}; no jjwt
     * exception type escapes this class.
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
     * Confirms that a configured secret carries key material HS256 can use.
     *
     * <p>Three values are read as an unsupplied secret and all three raise the same message:
     * {@code null}, a blank value, and an unresolved {@code ${SECRET_KEY}} placeholder. The third
     * arrives when the environment variable behind the placeholder is absent, because configuration
     * binding leaves the placeholder in place as literal text rather than failing — DL-186.
     *
     * <p>A value present but shorter than {@value #MINIMUM_SECRET_BYTES} bytes is rejected before it
     * reaches {@link Keys#hmacShaKeyFor(byte[])} — DL-141, DL-186. Neither failure message reproduces
     * any part of the configured value — DL-111.
     *
     * @param configuredSecret value of {@code scanner.jwt.secret}, which may be {@code null}
     * @return {@code configuredSecret}, guaranteed non-blank and at least
     *     {@value #MINIMUM_SECRET_BYTES} bytes long in UTF-8
     * @throws IllegalStateException if the value carries nothing or is shorter than
     *     {@value #MINIMUM_SECRET_BYTES} bytes
     */
    private static String requireConfiguredSecret(String configuredSecret) {
        if (configuredSecret == null
                || configuredSecret.isBlank()
                || isUnresolvedPlaceholder(configuredSecret)) {
            throw new IllegalStateException(MISSING_SECRET_MESSAGE);
        }

        int suppliedBytes = configuredSecret.getBytes(StandardCharsets.UTF_8).length;
        if (suppliedBytes < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "scanner.jwt.secret supplies " + (suppliedBytes * BITS_PER_BYTE) + " bits, and "
                            + REQUIRED_ALGORITHM + " requires "
                            + (MINIMUM_SECRET_BYTES * BITS_PER_BYTE) + ". Set the SECRET_KEY "
                            + "environment variable to a value of at least " + MINIMUM_SECRET_BYTES
                            + " bytes. The configured value is not reproduced here.");
        }
        return configuredSecret;
    }

    /**
     * Reports whether a configured value still holds the property placeholder that should have
     * supplied it.
     *
     * <p>{@code scanner.jwt.secret} is declared as {@code ${SECRET_KEY}} with no default. The
     * {@code @ConfigurationProperties} binder resolves placeholders through a resolver that leaves an
     * unresolvable one in place rather than failing, so an unset {@code SECRET_KEY} binds the literal
     * thirteen-character text {@code ${SECRET_KEY}} — a value that is neither {@code null} nor blank.
     * Recognising that shape, ignoring surrounding whitespace, is what keeps the guard in
     * {@link #requireConfiguredSecret(String)} reachable — DL-185, DL-186.
     *
     * @param value the bound value, never {@code null} when this is called
     * @return {@code true} when the value, ignoring surrounding whitespace, opens with a dollar sign
     *     followed by an opening brace and closes with a closing brace
     */
    private static boolean isUnresolvedPlaceholder(String value) {
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
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
     * @return {@code configuredMinutes}, guaranteed to lie within
     *     {@value #MINIMUM_EXPIRATION_MINUTES}..{@value #MAXIMUM_EXPIRATION_MINUTES}
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
}
