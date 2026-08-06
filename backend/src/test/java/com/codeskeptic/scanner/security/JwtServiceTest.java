package com.codeskeptic.scanner.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import org.junit.jupiter.params.provider.ValueSource;

import com.codeskeptic.scanner.config.ScannerProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

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
 * database, filesystem or credential resource is reached. Every secret constant below is a Base64
 * encoding of key material, which is the form {@code scanner.jwt.secret} carries — DL-186.
 * {@link #SIGNING_KEY} is decoded from {@link #SECRET}, the value every service under test is
 * configured with; {@link #FOREIGN_KEY} is decoded from {@link #FOREIGN_SECRET}, which is never bound
 * to {@code scanner.jwt.secret}.
 *
 * <p>One test asserts the content of the log record a verification failure produces. It attaches a
 * {@link ListAppender} to the {@link JwtService} logger for the duration of the call under test and
 * detaches it again, so no other test observes it.
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
     * {@code src/test/resources/application-test.yml} declares, and the largest the service accepts.
     */
    private static final long EXPIRATION_MINUTES = 60L;

    /** Smallest value {@code scanner.jwt.expiration-minutes} may carry. */
    private static final long MINIMUM_EXPIRATION_MINUTES = 1L;

    /** Property key named by the message the lifetime guard raises. */
    private static final String LIFETIME_PROPERTY = "scanner.jwt.expiration-minutes";

    /** Property key named by the message the algorithm guard raises. */
    private static final String ALGORITHM_PROPERTY = "scanner.jwt.algorithm";

    /** {@link #EXPIRATION_MINUTES} expressed as a {@link Duration}. */
    private static final Duration LIFETIME = Duration.ofMinutes(EXPIRATION_MINUTES);

    /**
     * Value bound to {@code scanner.jwt.secret}: the standard-alphabet Base64 encoding of 48 bytes of
     * key material, above the 256-bit floor jjwt 0.13.0 enforces for HS256 — DL-186.
     */
    private static final String SECRET =
            "and0LXNlcnZpY2UtdGVzdC1zaWduaW5nLXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVm";

    /**
     * Base64 encoding of exactly 32 bytes of key material, which is the shortest accepted secret.
     * None of its characters appears in any failure message this class asserts on.
     */
    private static final String SHORTEST_ACCEPTED_SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /**
     * A URL-safe Base64 encoding of 48 bytes of key material, carrying both {@code -} and {@code _}
     * — the two characters the standard alphabet does not hold — DL-186.
     */
    private static final String URL_SAFE_SECRET =
            "-Pn6-_z9_gABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJico";

    /**
     * A 32-character passphrase drawn from the Base64 alphabet. It is long enough as text and decodes
     * to 24 bytes of key material, which is below the floor — DL-186.
     */
    private static final String PASSPHRASE_SECRET = "abcdefghijklmnopqrstuvwxyz012345";

    /** Encoding of a second 56 bytes of key material, never bound to {@code scanner.jwt.secret}. */
    private static final String FOREIGN_SECRET =
            "and0LXNlcnZpY2UtdGVzdC1mb3JlaWduLXNpZ25pbmctc2VjcmV0LWZlZGNiYTk4NzY1NDMyMTA=";

    /**
     * Encoding of 65 bytes of key material, bound to {@code scanner.jwt.secret} by the HS512
     * rejection test only. It meets the 512-bit floor jjwt 0.13.0 enforces for HS512, and a token
     * signed with HS512 under it carries the very key the service under test verifies with.
     */
    private static final String LONG_SECRET =
            "and0LXNlcnZpY2UtdGVzdC1zaWduaW5nLXNlY3JldC10aGF0LWlzLXNpeHR5LWZvdXItYnl0ZXMtMDAwMDAwMDA=";

    /** Key derived from {@link #SECRET}, matching the key every service under test derives. */
    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));

    /** Key derived from {@link #FOREIGN_SECRET}. */
    private static final SecretKey FOREIGN_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(FOREIGN_SECRET));

    /** Key derived from {@link #LONG_SECRET}. */
    private static final SecretKey LONG_SIGNING_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(LONG_SECRET));

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
    @DisplayName("holds one parser instance and reuses it across verifications")
    void holdsOneParserInstanceAndReusesItAcrossVerifications() throws ReflectiveOperationException {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        String token = service.generateToken(USERNAME);

        Field parserField = JwtService.class.getDeclaredField("parser");
        parserField.setAccessible(true);
        Object beforeAnyVerification = parserField.get(service);

        assertThat(beforeAnyVerification).as("parser held before any verification").isNotNull();
        assertThat(Modifier.isFinal(parserField.getModifiers())).as("parser field is final").isTrue();
        assertThat(JwtParser.class).as("parser field type")
                .isAssignableFrom(beforeAnyVerification.getClass());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(service.extractUsername(token)).as("subject on attempt %d", attempt)
                    .contains(USERNAME);
            assertThat(service.extractUsername(MALFORMED_TOKEN))
                    .as("malformed token on attempt %d", attempt).isEmpty();
        }

        assertThat(parserField.get(service)).as("parser held after repeated verification")
                .isSameAs(beforeAnyVerification);
    }

    @Test
    @DisplayName("verifies every token outcome identically however many tokens it has already seen")
    void verifiesEveryTokenOutcomeIdenticallyHoweverManyTokensItHasAlreadySeen() {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Instant issuedAt = Instant.now();
        String valid = service.generateToken(USERNAME);
        String foreign = tokenSignedWith(FOREIGN_KEY, issuedAt, issuedAt.plus(LIFETIME));
        String expired = tokenSignedWith(SIGNING_KEY, issuedAt.minus(LIFETIME).minusSeconds(60),
                issuedAt.minusSeconds(60));

        for (int round = 0; round < 3; round++) {
            assertThat(service.extractUsername(valid)).as("valid token in round %d", round)
                    .contains(USERNAME);
            assertThat(service.extractExpiration(valid)).as("expiry of a valid token in round %d",
                    round).isPresent();
            assertThat(service.extractUsername(foreign))
                    .as("foreign-signed token in round %d", round).isEmpty();
            assertThat(service.extractUsername(expired)).as("expired token in round %d", round)
                    .isEmpty();
            assertThat(service.extractUsername(MALFORMED_TOKEN))
                    .as("malformed token in round %d", round).isEmpty();
            assertThat(service.extractUsername(null)).as("absent token in round %d", round)
                    .isEmpty();
            assertThat(service.extractUsername(BLANK_TOKEN)).as("blank token in round %d", round)
                    .isEmpty();
        }
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

    @ParameterizedTest(name = "a secret still holding {0} is rejected at construction")
    @ValueSource(strings = {
            "${SECRET_KEY}",
            "  ${SECRET_KEY}  ",
            "${scanner.jwt.secret}",
            "${JWT_SECRET:}",
            "${SECRET_KEY:${JWT_SECRET}}",
            "${}",
            "${A_VERY_LONG_UNRESOLVED_ENVIRONMENT_VARIABLE_NAME_WELL_OVER_THIRTY_TWO_BYTES}",
    })
    @DisplayName("rejects a secret that is still an unresolved property placeholder")
    void rejectsASecretThatIsStillAnUnresolvedPropertyPlaceholder(String unresolved) {
        // DL-185, DL-186 — see docs/DECISION_LOG.md
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(unresolved, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret")
                .hasMessageContaining("SECRET_KEY")
                .hasMessageContaining("no default value");
    }

    @ParameterizedTest(name = "an unresolved placeholder of {0} is rejected at construction")
    @ValueSource(strings = {"${SECRET_KEY}", "${SECRET_KEY:${JWT_SECRET}}", "${}"})
    @DisplayName("rejects an unresolved secret placeholder and names the key and its variable")
    void rejectsAnUnresolvedSecretPlaceholderAtConstruction(String unresolvedPlaceholder) {
        // DL-186 — see docs/DECISION_LOG.md
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(unresolvedPlaceholder, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scanner.jwt.secret is not configured; supply it through the SECRET_KEY "
                        + "environment variable. It has no default value.");
    }

    @ParameterizedTest(name = "key material of {0} byte(s) is rejected at construction")
    @ValueSource(ints = {1, 13, 16, 24, 31})
    @DisplayName("rejects key material that decodes to fewer than 32 bytes and names the key and its "
            + "variable")
    void rejectsKeyMaterialShorterThanThirtyTwoBytes(int materialBytes) {
        // DL-186 — see docs/DECISION_LOG.md
        String encoded = base64Of(materialBytes);
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(encoded, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret")
                .hasMessageContaining("SECRET_KEY")
                .hasMessageContaining("256 bits")
                .hasMessageNotContaining(encoded);
    }

    @Test
    @DisplayName("reads an encoding of no key material as an unconfigured secret")
    void readsAnEncodingOfNoKeyMaterialAsAnUnconfiguredSecret() {
        // DL-185, DL-186 — see docs/DECISION_LOG.md
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(base64Of(0), HS256, EXPIRATION_MINUTES));

        assertThat(base64Of(0)).isEmpty();
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not configured");
    }

    @Test
    @DisplayName("accepts key material of exactly 32 bytes at construction")
    void acceptsKeyMaterialOfExactlyThirtyTwoBytes() {
        JwtService service = serviceWith(SHORTEST_ACCEPTED_SECRET, HS256, EXPIRATION_MINUTES);

        assertThat(Decoders.BASE64.decode(SHORTEST_ACCEPTED_SECRET)).hasSize(32);
        assertThat(service.extractUsername(service.generateToken(USERNAME))).contains(USERNAME);
    }

    @Test
    @DisplayName("accepts a URL-safe Base64 secret carrying the two characters the standard alphabet "
            + "does not hold")
    void acceptsAUrlSafeBase64Secret() {
        // DL-186 — see docs/DECISION_LOG.md
        assertThat(URL_SAFE_SECRET).contains("-").contains("_");

        JwtService service = serviceWith(URL_SAFE_SECRET, HS256, EXPIRATION_MINUTES);

        assertThat(Decoders.BASE64URL.decode(URL_SAFE_SECRET)).hasSize(48);
        assertThat(service.extractUsername(service.generateToken(USERNAME))).contains(USERNAME);
    }

    @Test
    @DisplayName("rejects a 32-character passphrase, whose decoded key material is 24 bytes")
    void rejectsAPassphraseWhoseDecodedKeyMaterialIsTooShort() {
        // DL-186 — see docs/DECISION_LOG.md
        assertThat(PASSPHRASE_SECRET).hasSize(32);
        assertThat(Decoders.BASE64.decode(PASSPHRASE_SECRET)).hasSize(24);

        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(PASSPHRASE_SECRET, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits")
                .hasMessageNotContaining(PASSPHRASE_SECRET);
    }

    @ParameterizedTest(name = "a secret carrying {0} is rejected as undecodable")
    @ValueSource(strings = {
            "not+base64!because+of+the+exclamation+mark+and+long+enough",
            "spaces are not in either alphabet and this value is long enough",
            "trailing=pad=in=the=middle=is=not=an=encoding=and=long=enough",
            "mixed-alphabet+value_with_both/kinds/of/character/and/padding",
    })
    @DisplayName("rejects a secret that is not an encoding in either Base64 alphabet")
    void rejectsASecretThatIsNotAnEncodingInEitherAlphabet(String secret) {
        // DL-186 — see docs/DECISION_LOG.md
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(secret, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret is not Base64-encoded")
                .hasMessageContaining("SECRET_KEY")
                .hasMessageNotContaining(secret);
    }

    @Test
    @DisplayName("accepts a secret padded with surrounding whitespace")
    void acceptsASecretPaddedWithSurroundingWhitespace() {
        JwtService service = serviceWith("  " + SECRET + "  ", HS256, EXPIRATION_MINUTES);

        assertThat(service.extractUsername(service.generateToken(USERNAME))).contains(USERNAME);
    }

    @Test
    @DisplayName("reports an unresolved placeholder without echoing the configured value")
    void reportsAnUnresolvedPlaceholderWithoutEchoingTheConfiguredValue() {
        String unresolved = "${A_DISTINCTIVE_UNRESOLVED_PLACEHOLDER_NAME}";
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(unresolved, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("A_DISTINCTIVE_UNRESOLVED_PLACEHOLDER_NAME");
    }

    @Test
    @DisplayName("reports an unresolved placeholder as an unconfigured secret and not as a weak key")
    void reportsAnUnresolvedPlaceholderAsAnUnconfiguredSecret() {
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt("${SECRET_KEY}", HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(WeakKeyException.class)
                .hasMessageContaining("is not configured");
    }

    @ParameterizedTest(name = "a secret of {0} is reported as undecodable")
    @ValueSource(strings = {
            "${not-closed-so-not-a-placeholder-and-long-enough-for-hs256",
            "not-opened-so-not-a-placeholder-and-long-enough-for-hs256}",
            "a-secret-that-merely-contains-${EMBEDDED}-text-and-is-long-enough",
    })
    @DisplayName("reports a secret that only resembles a placeholder in part as undecodable and not "
            + "as unconfigured")
    void reportsASecretThatOnlyResemblesAPlaceholderInPartAsUndecodable(String secret) {
        // DL-185, DL-186 — see docs/DECISION_LOG.md
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(secret, HS256, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.jwt.secret is not Base64-encoded")
                .hasMessageNotContaining("is not configured")
                .hasMessageNotContaining(secret);
    }

    @ParameterizedTest(name = "an algorithm of {0} is rejected at construction")
    @CsvSource({"RS256", "HS384", "HS512", "ES256", "none", "hs384"})
    @DisplayName("rejects every configured algorithm other than HS256 at construction")
    void rejectsEveryConfiguredAlgorithmOtherThanHs256AtConstruction(String configuredAlgorithm) {
        ScannerProperties properties = propertiesWith(
                new ScannerProperties.Jwt(SECRET, configuredAlgorithm, EXPIRATION_MINUTES));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ALGORITHM_PROPERTY)
                .hasMessageContaining(HS256);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS256", "hs256", "Hs256", "  HS256  "})
    @DisplayName("accepts HS256 whatever its case and surrounding whitespace")
    void acceptsHs256WhateverItsCaseAndSurroundingWhitespace(String configuredAlgorithm) {
        assertThatCode(() -> serviceWith(SECRET, configuredAlgorithm, EXPIRATION_MINUTES))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts an absent or blank algorithm at construction")
    void acceptsAnAbsentOrBlankAlgorithmAtConstruction() {
        assertThatCode(() -> serviceWith(SECRET, null, EXPIRATION_MINUTES))
                .doesNotThrowAnyException();
        assertThatCode(() -> serviceWith(SECRET, "   ", EXPIRATION_MINUTES))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("signs with HS256 whatever the configured algorithm permits")
    void signsWithHs256WhateverTheConfiguredAlgorithmPermits() {
        JwtService service = serviceWith(SECRET, null, EXPIRATION_MINUTES);

        String header = service.generateToken(USERNAME).split("\\.")[0];

        assertThat(new String(Base64.getUrlDecoder().decode(header), StandardCharsets.UTF_8))
                .contains("\"alg\":\"" + HS256 + "\"");
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 15L, 30L, 59L, 60L})
    @DisplayName("accepts a lifetime from one minute to sixty minutes inclusive")
    void acceptsALifetimeFromOneMinuteToSixtyMinutesInclusive(long configuredMinutes) {
        assertThatCode(() -> serviceWith(SECRET, HS256, configuredMinutes))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -60L, Long.MIN_VALUE})
    @DisplayName("rejects a lifetime that is not positive at construction")
    void rejectsALifetimeThatIsNotPositiveAtConstruction(long configuredMinutes) {
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(SECRET, HS256, configuredMinutes));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LIFETIME_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(longs = {61L, 120L, 1440L, Long.MAX_VALUE})
    @DisplayName("rejects a lifetime above the documented sixty-minute maximum at construction")
    void rejectsALifetimeAboveTheDocumentedSixtyMinuteMaximumAtConstruction(long configuredMinutes) {
        ScannerProperties properties =
                propertiesWith(new ScannerProperties.Jwt(SECRET, HS256, configuredMinutes));

        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LIFETIME_PROPERTY);
    }

    @Test
    @DisplayName("reports a lifetime in seconds for every accepted lifetime without overflowing")
    void reportsALifetimeInSecondsForEveryAcceptedLifetimeWithoutOverflowing() {
        for (long minutes = MINIMUM_EXPIRATION_MINUTES; minutes <= EXPIRATION_MINUTES; minutes++) {
            JwtService service = serviceWith(SECRET, HS256, minutes);

            assertThat(service.getExpirationSeconds())
                    .isPositive()
                    .isEqualTo(minutes * 60L);
        }
    }

    @Test
    @DisplayName("mints a token no more than an hour after its issued-at claim for every accepted "
            + "lifetime")
    void mintsATokenNoMoreThanAnHourAfterItsIssuedAtClaimForEveryAcceptedLifetime() {
        for (long minutes = MINIMUM_EXPIRATION_MINUTES; minutes <= EXPIRATION_MINUTES; minutes++) {
            Claims claims = claimsOf(serviceWith(SECRET, HS256, minutes).generateToken(USERNAME));

            Duration lifetime = Duration.between(
                    claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());

            assertThat(lifetime).isEqualTo(Duration.ofMinutes(minutes));
            assertThat(lifetime).isLessThanOrEqualTo(LIFETIME);
        }
    }

    @ParameterizedTest
    @MethodSource("unverifiableTokens")
    @DisplayName("logs a verification failure as a category only, never the parser message")
    void logsAVerificationFailureAsACategoryOnlyNeverTheParserMessage(String unverifiableToken) {
        JwtService service = serviceWith(SECRET, HS256, EXPIRATION_MINUTES);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(JwtService.class);
        Level restoreLevel = serviceLogger.getLevel();
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        serviceLogger.addAppender(records);
        serviceLogger.setLevel(Level.DEBUG);
        try {
            assertThat(service.extractUsername(unverifiableToken)).isEmpty();
        } finally {
            serviceLogger.setLevel(restoreLevel);
            serviceLogger.detachAppender(records);
            records.stop();
        }

        assertThat(records.list).hasSize(1);
        ILoggingEvent record = records.list.get(0);
        assertThat(record.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(record.getThrowableProxy()).isNull();
        assertThat(record.getArgumentArray()).isNullOrEmpty();
        assertThat(record.getFormattedMessage())
                .isEqualTo("A presented JWT did not verify")
                .doesNotContain(unverifiableToken);
    }

    /**
     * Tokens {@link JwtService#extractUsername(String)} cannot verify, each reaching the parser and
     * producing one log record each.
     *
     * @return one unverifiable compact JWS or JWS-shaped value per invocation
     */
    private static Stream<String> unverifiableTokens() {
        Instant now = Instant.now();
        return Stream.of(
                MALFORMED_TOKEN,
                DOTTED_MALFORMED_TOKEN,
                tokenSignedWith(FOREIGN_KEY, now, now.plus(LIFETIME)),
                tokenSignedWith(SIGNING_KEY, now.minus(LIFETIME), now.minus(EXPIRED_BY)));
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
     * Encodes a deterministic run of key material of the requested length as standard Base64.
     *
     * @param materialBytes number of bytes of key material to encode; never negative
     * @return the encoding, which is the empty string for a length of {@code 0}
     */
    private static String base64Of(int materialBytes) {
        byte[] material = new byte[materialBytes];
        for (int index = 0; index < materialBytes; index++) {
            material[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(material);
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
        return new ScannerProperties(null, 0, 0L, null, null, null, jwt, null, null, null, null);
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
