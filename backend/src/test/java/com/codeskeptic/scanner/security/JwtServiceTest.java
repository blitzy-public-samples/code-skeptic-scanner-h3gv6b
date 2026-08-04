package com.codeskeptic.scanner.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.codeskeptic.scanner.config.ScannerProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Ported from backend/app/core/security.py:L6-12 (faithful port) — see docs/DECISION_LOG.md DL-014,
// DL-015, DL-016, DL-017, DL-018
// Coverage of the parsing path is net-new: PyJWT `decode` was imported at
// backend/app/core/security.py:L1 and never called — see docs/DECISION_LOG.md DL-014
/**
 * Exercises the four operations {@link JwtService} exposes —
 * {@link JwtService#generateToken(String)}, {@link JwtService#extractUsername(String)},
 * {@link JwtService#extractExpiration(String)} and {@link JwtService#getExpirationSeconds()} —
 * together with the construction-time failures its constructor declares.
 *
 * <p>Every test builds a {@link ScannerProperties} value in this class and hands it to the
 * constructor directly: no Spring context is started, no property file is read, and no network,
 * database, filesystem or credential resource is reached. {@link #SIGNING_KEY} is derived from
 * {@link #SECRET}, the value every service under test is configured with; {@link #FOREIGN_KEY} is
 * derived from {@link #FOREIGN_SECRET}, which is never bound to {@code scanner.jwt.secret}.
 *
 * <p>Both secret literals declared here are test-only values. Construct-level provenance is
 * recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    /** Value carried in the {@code sub} claim of every token this class mints or builds. */
    private static final String USERNAME = "someuser";

    /** Value bound to {@code scanner.jwt.algorithm} by every service this class constructs. */
    private static final String HS256 = "HS256";


    /**
     * Value bound to {@code scanner.jwt.expiration-minutes}; the value
     * {@code src/test/resources/application-test.yml} declares.
     */
    private static final long EXPIRATION_MINUTES = 60L;

    /** {@link #EXPIRATION_MINUTES} expressed as a {@link Duration}. */
    private static final Duration LIFETIME = Duration.ofMinutes(EXPIRATION_MINUTES);

    /**
     * Value bound to {@code scanner.jwt.secret}; 48 bytes, above the 256-bit floor jjwt 0.13.0
     * enforces for HS256.
     */
    private static final String SECRET = "jwt-service-test-signing-secret-0123456789abcdef";

    /** A second 56-byte value, never bound to {@code scanner.jwt.secret}. */
    private static final String FOREIGN_SECRET =
            "jwt-service-test-foreign-signing-secret-fedcba9876543210";

    /**
     * A 64-byte value bound to {@code scanner.jwt.secret} by the HS512 rejection test only; 512 bits
     * is the floor jjwt 0.13.0 enforces for HS512, so a token can be signed with HS512 using the
     * very key the service under test verifies with.
     */
    private static final String LONG_SECRET =
            "jwt-service-test-signing-secret-that-is-sixty-four-bytes-00000000";

    /** Key derived from {@link #SECRET}, matching the key every service under test derives. */
    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /** Key derived from {@link #FOREIGN_SECRET}. */
    private static final SecretKey FOREIGN_KEY =
            Keys.hmacShaKeyFor(FOREIGN_SECRET.getBytes(StandardCharsets.UTF_8));

    /** Key derived from {@link #LONG_SECRET}. */
    private static final SecretKey LONG_SIGNING_KEY =
            Keys.hmacShaKeyFor(LONG_SECRET.getBytes(StandardCharsets.UTF_8));

    /**
     * Margin applied to both wall-clock bounds of the expiration window assertion. The {@code iat}
     * and {@code exp} claims are NumericDate values carrying whole seconds.
     */
    private static final Duration TOLERANCE = Duration.ofSeconds(2L);

    /** How far in the past the {@code exp} claim of the expired token sits. */
    private static final Duration EXPIRED_BY = Duration.ofSeconds(60L);

    /** A value that is not a compact JWS. */
    private static final String MALFORMED_TOKEN = "not-a-jwt";

    /** A three-segment value whose segments are not JWS components. */
    private static final String DOTTED_MALFORMED_TOKEN = "aaa.bbb.ccc";

    /** A value carrying whitespace only. */
    private static final String BLANK_TOKEN = "   ";

    @Test
    @DisplayName("extracts the subject from a token it minted")
    void extractsTheSubjectFromATokenItMinted() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        String token = service.generateToken(USERNAME);

        assertThat(service.extractUsername(token)).contains(USERNAME);
    }

    @Test
    @DisplayName("mints a token whose expiration lands the configured lifetime after the mint")
    void mintsATokenWhoseExpirationLandsTheConfiguredLifetimeAfterTheMint() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        Instant before = Instant.now();
        String token = service.generateToken(USERNAME);
        Instant after = Instant.now();

        Optional<Instant> expiration = service.extractExpiration(token);

        assertThat(expiration).isPresent();
        assertThat(expiration.get()).isBetween(
                before.plus(LIFETIME).minus(TOLERANCE),
                after.plus(LIFETIME).plus(TOLERANCE));
    }

    @Test
    @DisplayName("mints a token whose expiration is the configured lifetime after its issued-at claim")
    void mintsATokenWhoseExpirationIsTheConfiguredLifetimeAfterItsIssuedAtClaim() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        Claims claims = claimsOf(service.generateToken(USERNAME));

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(Duration.between(
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()))
                .isEqualTo(LIFETIME);
    }

    @ParameterizedTest
    @CsvSource({"60, 3600", "15, 900"})
    @DisplayName("reports the configured lifetime in seconds")
    void reportsTheConfiguredLifetimeInSeconds(long configuredMinutes, long expectedSeconds) {
        JwtService service = serviceWith(SECRET, HS256, configuredMinutes);

        assertThat(service.getExpirationSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName("mints a token carrying exactly the subject, issued-at and expiration claims")
    void mintsATokenCarryingExactlyTheSubjectIssuedAtAndExpirationClaims() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        Claims claims = claimsOf(service.generateToken(USERNAME));

        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iat", "exp");
        assertThat(claims.getSubject()).isEqualTo(USERNAME);
    }

    @Test
    @DisplayName("mints a token carrying no role, scope, authority or permission claim")
    void mintsATokenCarryingNoRoleScopeAuthorityOrPermissionClaim() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        Claims claims = claimsOf(service.generateToken(USERNAME));

        assertThat(claims.keySet())
                .doesNotContain("roles", "role", "scope", "scp", "authorities", "permissions");
    }

    @Test
    @DisplayName("returns an empty optional for a malformed token")
    void returnsAnEmptyOptionalForAMalformedToken() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        assertThatCode(() -> service.extractUsername(MALFORMED_TOKEN)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(MALFORMED_TOKEN)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractUsername(DOTTED_MALFORMED_TOKEN))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(DOTTED_MALFORMED_TOKEN))
                .doesNotThrowAnyException();

        assertThat(service.extractUsername(MALFORMED_TOKEN)).isEmpty();
        assertThat(service.extractExpiration(MALFORMED_TOKEN)).isEmpty();
        assertThat(service.extractUsername(DOTTED_MALFORMED_TOKEN)).isEmpty();
        assertThat(service.extractExpiration(DOTTED_MALFORMED_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty optional for a token signed with a different key")
    void returnsAnEmptyOptionalForATokenSignedWithADifferentKey() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String foreignToken = tokenSignedWith(FOREIGN_KEY, issuedAt, issuedAt.plus(LIFETIME));

        assertThatCode(() -> service.extractUsername(foreignToken)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(foreignToken)).doesNotThrowAnyException();

        assertThat(service.extractUsername(foreignToken)).isEmpty();
        assertThat(service.extractExpiration(foreignToken)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty optional for an expired token")
    void returnsAnEmptyOptionalForAnExpiredToken() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant now = Instant.now();
        String expiredToken =
                tokenSignedWith(SIGNING_KEY, now.minus(LIFETIME), now.minus(EXPIRED_BY));

        assertThatCode(() -> service.extractUsername(expiredToken)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(expiredToken)).doesNotThrowAnyException();

        assertThat(service.extractUsername(expiredToken)).isEmpty();
        assertThat(service.extractExpiration(expiredToken)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty optional for an absent token")
    void returnsAnEmptyOptionalForAnAbsentToken() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        assertThatCode(() -> service.extractUsername(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractUsername(BLANK_TOKEN)).doesNotThrowAnyException();
        assertThatCode(() -> service.extractExpiration(BLANK_TOKEN)).doesNotThrowAnyException();

        assertThat(service.extractUsername(null)).isEmpty();
        assertThat(service.extractExpiration(null)).isEmpty();
        assertThat(service.extractUsername(BLANK_TOKEN)).isEmpty();
        assertThat(service.extractExpiration(BLANK_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("rejects a blank secret at construction")
    void rejectsABlankSecretAtConstruction() {
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt("   ", HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret");
    }

    @Test
    @DisplayName("rejects a null secret at construction")
    void rejectsANullSecretAtConstruction() {
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(null, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret");
    }

    @Test
    @DisplayName("rejects an absent jwt group at construction")
    void rejectsAnAbsentJwtGroupAtConstruction() {
        ScannerProperties properties = propertiesWith(null);

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret");
    }

    @ParameterizedTest(name = "an algorithm of {0} is rejected at construction")
    @CsvSource({"RS256", "HS384", "HS512", "ES256", "none", "hs384"})
    @DisplayName("rejects every configured algorithm other than HS256 at construction")
    void rejectsEveryConfiguredAlgorithmOtherThanHs256AtConstruction(String configuredAlgorithm) {
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(SECRET, configuredAlgorithm, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.algorithm");
    }

    @Test
    @DisplayName("accepts an absent algorithm at construction and still signs with HS256")
    void acceptsAnAbsentAlgorithmAtConstructionAndStillSignsWithHs256() {
        JwtService service = serviceWith(SECRET, null, EXPIRATION_MINUTES);

        assertThat(headerAlgorithmOf(service.generateToken(USERNAME))).isEqualTo(HS256);
    }

    // ---------------------------------------------------------------------
    // Adversarial verification policy — see docs/DECISION_LOG.md DL-108, DL-109, DL-110
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("rejects an HS384 token signed with the very key it verifies with")
    void rejectsAnHs384TokenSignedWithTheVeryKeyItVerifiesWith() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String hs384Token = Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(LIFETIME)))
                .signWith(SIGNING_KEY, Jwts.SIG.HS384)
                .compact();

        assertThat(headerAlgorithmOf(hs384Token)).isEqualTo("HS384");
        assertThat(service.extractUsername(hs384Token)).isEmpty();
        assertThat(service.extractExpiration(hs384Token)).isEmpty();
    }

    @Test
    @DisplayName("rejects an HS512 token signed with the very key it verifies with")
    void rejectsAnHs512TokenSignedWithTheVeryKeyItVerifiesWith() {
        JwtService service = serviceWith(LONG_SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String hs512Token = Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(LIFETIME)))
                .signWith(LONG_SIGNING_KEY, Jwts.SIG.HS512)
                .compact();

        assertThat(headerAlgorithmOf(hs512Token)).isEqualTo("HS512");
        assertThat(service.extractUsername(hs512Token)).isEmpty();
        assertThat(service.extractExpiration(hs512Token)).isEmpty();
    }

    @Test
    @DisplayName("rejects a correctly signed token carrying no expiration claim")
    void rejectsACorrectlySignedTokenCarryingNoExpirationClaim() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        String noExpiry = Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(Instant.now()))
                .signWith(SIGNING_KEY, Jwts.SIG.HS256)
                .compact();

        assertThat(service.extractUsername(noExpiry)).isEmpty();
        assertThat(service.extractExpiration(noExpiry)).isEmpty();
    }

    @Test
    @DisplayName("rejects a correctly signed token carrying no issued-at claim")
    void rejectsACorrectlySignedTokenCarryingNoIssuedAtClaim() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        String noIssuedAt = Jwts.builder()
                .subject(USERNAME)
                .expiration(Date.from(Instant.now().plus(LIFETIME)))
                .signWith(SIGNING_KEY, Jwts.SIG.HS256)
                .compact();

        assertThat(service.extractUsername(noIssuedAt)).isEmpty();
        assertThat(service.extractExpiration(noIssuedAt)).isEmpty();
    }

    @Test
    @DisplayName("rejects a correctly signed token carrying no subject claim")
    void rejectsACorrectlySignedTokenCarryingNoSubjectClaim() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String noSubject = Jwts.builder()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(LIFETIME)))
                .signWith(SIGNING_KEY, Jwts.SIG.HS256)
                .compact();

        assertThat(service.extractUsername(noSubject)).isEmpty();
        assertThat(service.extractExpiration(noSubject)).isEmpty();
    }

    @ParameterizedTest(name = "a subject of [{0}] is rejected")
    @CsvSource({"''", "'   '", "'\t'"})
    @DisplayName("rejects a correctly signed token carrying a blank subject")
    void rejectsACorrectlySignedTokenCarryingABlankSubject(String blankSubject) {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String blankSubjectToken = Jwts.builder()
                .subject(blankSubject)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(LIFETIME)))
                .signWith(SIGNING_KEY, Jwts.SIG.HS256)
                .compact();

        assertThat(service.extractUsername(blankSubjectToken)).isEmpty();
        assertThat(service.extractExpiration(blankSubjectToken)).isEmpty();
    }

    @Test
    @DisplayName("rejects a correctly signed token carrying a claim beyond the accepted set")
    void rejectsACorrectlySignedTokenCarryingAClaimBeyondTheAcceptedSet() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String extraClaimToken = Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(LIFETIME)))
                .claim("roles", "ROLE_ADMIN")
                .signWith(SIGNING_KEY, Jwts.SIG.HS256)
                .compact();

        assertThat(service.extractUsername(extraClaimToken)).isEmpty();
        assertThat(service.extractExpiration(extraClaimToken)).isEmpty();
    }

    @Test
    @DisplayName("rejects a correctly signed token whose expiration does not follow its issued-at")
    void rejectsACorrectlySignedTokenWhoseExpirationDoesNotFollowItsIssuedAt() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now().plus(LIFETIME);
        String sameInstant = tokenSignedWith(SIGNING_KEY, issuedAt, issuedAt);

        assertThat(service.extractUsername(sameInstant)).isEmpty();
        assertThat(service.extractExpiration(sameInstant)).isEmpty();
    }

    @ParameterizedTest(name = "an expiration-minutes of {0} is rejected at construction")
    @CsvSource({"0", "-1", "-60", "61", "1440", "9223372036854775807"})
    @DisplayName("rejects a token lifetime outside one to sixty minutes at construction")
    void rejectsATokenLifetimeOutsideOneToSixtyMinutesAtConstruction(long configuredMinutes) {
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(SECRET, HS256, configuredMinutes));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.expiration-minutes");
    }

    @ParameterizedTest(name = "an expiration-minutes of {0} is accepted at construction")
    @CsvSource({"1, 60", "60, 3600"})
    @DisplayName("accepts the boundary token lifetimes and reports them in seconds")
    void acceptsTheBoundaryTokenLifetimesAndReportsThemInSeconds(long configuredMinutes,
            long expectedSeconds) {

        JwtService service = serviceWith(SECRET, HS256, configuredMinutes);

        assertThat(service.getExpirationSeconds()).isEqualTo(expectedSeconds);
    }

    @ParameterizedTest(name = "a username of [{0}] is rejected")
    @CsvSource({"''", "'   '"})
    @DisplayName("rejects a blank principal name when minting")
    void rejectsABlankPrincipalNameWhenMinting(String blankUsername) {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        assertThatThrownBy(() -> service.generateToken(blankUsername))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    @DisplayName("rejects a null principal name when minting")
    void rejectsANullPrincipalNameWhenMinting() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        assertThatThrownBy(() -> service.generateToken(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("username");
    }

    @Test
    @DisplayName("mints a token whose header names HS256")
    void mintsATokenWhoseHeaderNamesHs256() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);

        assertThat(headerAlgorithmOf(service.generateToken(USERNAME))).isEqualTo(HS256);
    }

    /**
     * Constructs the unit under test over a {@code scanner.jwt} group carrying the given values.
     *
     * @param secret value of {@code scanner.jwt.secret}
     * @param algorithm value of {@code scanner.jwt.algorithm}
     * @param expirationMinutes value of {@code scanner.jwt.expiration-minutes}
     * @return the constructed service
     */
    private static JwtService serviceWith(String secret, String algorithm, long expirationMinutes) {
        return new JwtService(
                propertiesWith(new ScannerProperties.Jwt(secret, algorithm, expirationMinutes)));
    }

    /**
     * Builds a configuration root carrying the given {@code scanner.jwt} group. Every other group is
     * absent; {@link JwtService} reads none of them.
     *
     * @param jwt the {@code scanner.jwt} group, which may be {@code null}
     * @return the configuration root
     */
    private static ScannerProperties propertiesWith(ScannerProperties.Jwt jwt) {
        return new ScannerProperties(null, 0, 0L, null, null, null, jwt, null, null, null);
    }

    /**
     * Builds a compact JWS carrying {@link #USERNAME} as its subject, signed with HS256.
     *
     * @param key the signing key
     * @param issuedAt value of the {@code iat} claim
     * @param expiresAt value of the {@code exp} claim
     * @return the compact JWS serialization
     */
    private static String tokenSignedWith(SecretKey key, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies a token against {@link #SIGNING_KEY} and returns its claim set.
     *
     * @param token the compact JWS to verify
     * @return the verified claim set
     */
    private static Claims claimsOf(String token) {
        return Jwts.parser()
                .verifyWith(SIGNING_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Reads the {@code alg} header of a compact JWS without verifying its signature.
     *
     * @param token the compact JWS to inspect
     * @return the value of the {@code alg} header
     */
    private static String headerAlgorithmOf(String token) {
        String encodedHeader = token.substring(0, token.indexOf('.'));
        String header = new String(Base64.getUrlDecoder().decode(encodedHeader),
                StandardCharsets.UTF_8);
        Matcher algorithm = Pattern.compile("\"alg\"\\s*:\\s*\"([^\"]+)\"").matcher(header);
        assertThat(algorithm.find()).as("the compact JWS header declares an alg member").isTrue();
        return algorithm.group(1);
    }
}
