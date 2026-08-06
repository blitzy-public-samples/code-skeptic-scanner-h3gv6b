package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.LoginRequest;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.dto.TokenResponse;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.util.LogSafe;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019
/**
 * Exercises {@link AuthController} — {@code POST /auth/token} — behind the application's
 * servlet security chain.
 *
 * <p>The route under test has no counterpart in the retired tree: {@code backend/app/main.py:L26-29}
 * registered four blueprints and none declared an authentication route, and
 * {@code backend/tests/test_api.py:L1-59} carried no authentication test.
 *
 * <p>The slice registers {@link AuthController} and {@link TweetController}, the real
 * {@link SecurityConfig} filter chain, the real {@link CorsConfig} policy, the real
 * {@link JwtService} and the bound {@link ScannerProperties}, under the {@code test} profile of
 * {@code src/test/resources/application-test.yml}. {@link GlobalExceptionHandler} is a
 * {@code @RestControllerAdvice} and is part of every web slice.
 *
 * <p>{@link TweetController} is registered, so a minted token reaches a mapped production handler and
 * not an unmapped path: {@code GET /tweets} is one of the eleven routes the chain
 * requires an authenticated principal on, and the assertions on it compare an exact 200 response
 * body. Only its two business collaborators, {@link TwitterService} and
 * {@link SentimentAnalysisService}, are replaced by mocks; nothing on the security path is.
 *
 * <p>Each credential check runs through the {@link BCryptPasswordEncoder} of
 * {@link SecurityConfig} against {@code scanner.auth.password-hash}, and each token is minted and
 * verified by the real {@link JwtService}.
 *
 * <p>Decisions covered by the assertions here are recorded in {@code docs/DECISION_LOG.md} DL-017,
 * DL-018, DL-019, DL-020, DL-021, DL-050 and DL-112 … DL-118.
 */
@WebMvcTest({ AuthController.class, TweetController.class })
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("AuthController POST /auth/token")
class AuthControllerTest {

    private static final String TOKEN_ENDPOINT = "/auth/token";

    /** The 500 envelope the advice serves — see docs/DECISION_LOG.md DL-210. */
    private static final String INTERNAL_ERROR_BODY = "{\"error\":\"Internal server error\"}";

    /** The 404 envelope the advice serves — see docs/DECISION_LOG.md DL-210, DL-247. */
    private static final String NOT_FOUND_BODY = "{\"error\":\"Not found\"}";

    /** The token route spelled with a percent-encoded letter; it decodes to {@value #TOKEN_ENDPOINT}. */
    private static final java.net.URI ENCODED_TOKEN_ENDPOINT =
            java.net.URI.create("/auth/%74oken");

    private static final String PROTECTED_ENDPOINT = "/tweets";

    private static final String LOGOUT_ENDPOINT = "/logout";

    private static final String TEST_PASSWORD = "test-password";

    private static final String WRONG_PASSWORD = "wrong-password";

    private static final String UNKNOWN_USERNAME = "nobody";

    private static final String BUILT_IN_USERNAME = "admin";

    private static final long EXPECTED_EXPIRES_IN_SECONDS = 3_600L;

    private static final long EXPECTED_EXPIRATION_MINUTES = 60L;

    private static final int CREDENTIAL_LENGTH_CEILING = 256;

    /** Rejected attempts issued back to back by the bounded-reporting tests — DL-272. */
    private static final int REJECTION_BURST = 25;

    private static final int LOGIN_BODY_BYTE_CEILING = 4_096;

    private static final int BCRYPT_TAIL_LENGTH = 53;

    private static final Duration EXPIRY_TOLERANCE = Duration.ofSeconds(30);

    private static final String ACCESS_TOKEN = "access_token";
    private static final String TOKEN_TYPE = "token_type";
    private static final String EXPIRES_IN = "expires_in";

    /** Wire identifier of the single row the stubbed protected route renders. */
    private static final String PROTECTED_TWEET_ID = "4711";

    /** {@code content} of that row. */
    private static final String PROTECTED_TWEET_CONTENT = "AI coding tools are overhyped";

    /** {@code like_count} of that row. */
    private static final int PROTECTED_TWEET_LIKE_COUNT = 142;

    /** {@code doubt_rating} of that row. */
    private static final double PROTECTED_TWEET_DOUBT_RATING = 7.5d;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Business collaborator of {@link TweetController}; the only mock on the protected route. */
    @MockitoBean
    private TwitterService twitterService;

    /** Second business collaborator of {@link TweetController}, unused by {@code GET /tweets}. */
    @MockitoBean
    private SentimentAnalysisService sentimentAnalysisService;

    @Autowired
    private ScannerProperties properties;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("returns 200 and a bearer token for the configured credential")
    void returnsBearerTokenForValidCredential() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$." + ACCESS_TOKEN).isString())
                .andExpect(jsonPath("$." + ACCESS_TOKEN).isNotEmpty())
                .andExpect(jsonPath("$." + TOKEN_TYPE).isString())
                .andExpect(jsonPath("$." + TOKEN_TYPE).value("bearer"))
                .andExpect(jsonPath("$." + EXPIRES_IN).isNumber())
                .andExpect(jsonPath("$." + EXPIRES_IN).value(EXPECTED_EXPIRES_IN_SECONDS));
    }

    // The success record carries no principal text — DL-242 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a successful issuance without writing the principal's own text")
    void recordsASuccessfulIssuanceWithoutWritingThePrincipalsOwnText() throws Exception {
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(AuthController.class);
        ListAppender<ILoggingEvent> recorded = new ListAppender<>();
        recorded.start();
        controllerLogger.addAppender(recorded);
        List<ILoggingEvent> events;
        try {
            requestToken(configuredUsername(), TEST_PASSWORD);
            events = List.copyOf(recorded.list);
        } finally {
            controllerLogger.detachAppender(recorded);
            recorded.stop();
        }

        assertThat(events).as("records written while a token was issued").hasSize(1);
        ILoggingEvent success = events.get(0);
        assertThat(success.getLevel()).isEqualTo(Level.INFO);
        assertThat(success.getThrowableProxy()).isNull();

        String written = success.getFormattedMessage();
        assertThat(written).contains("Issued a bearer token to principal ");
        assertThat(written).contains(LogSafe.correlation(configuredUsername()));
        assertThat(written).startsWith("Issued a bearer token to principal sha256:");
        assertThat(written).doesNotContain(configuredUsername());
        assertThat(written).doesNotContain(TEST_PASSWORD);
        assertThat(written).doesNotContain(mintedToken());
    }

    @Test
    @DisplayName("returns the token with no Authorization header on the request")
    void returnsTokenWithNoAuthorizationHeaderOnTheRequest() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertThat(result.getRequest().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("names exactly access_token, token_type and expires_in in the body")
    void namesExactlyThreeMembersInTheBody() throws Exception {
        Map<String, Object> body = readBody(requestToken(configuredUsername(), TEST_PASSWORD));

        assertThat(body).hasSize(3);
        assertThat(body).containsOnlyKeys(ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN);
        assertThat(body)
                .doesNotContainKeys("refresh_token", "scope", "id_token", "token", "expires");
    }

    @Test
    @DisplayName("carries the lowercase literal bearer as the token type")
    void carriesTheLowercaseLiteralBearerAsTheTokenType() throws Exception {
        Map<String, Object> body = readBody(requestToken(configuredUsername(), TEST_PASSWORD));

        assertThat(body.get(TOKEN_TYPE)).isInstanceOf(String.class);
        assertThat((String) body.get(TOKEN_TYPE)).isEqualTo("bearer");
        assertThat((String) body.get(TOKEN_TYPE)).isNotEqualTo("Bearer");
        assertThat((String) body.get(TOKEN_TYPE)).isNotEqualTo("BEARER");
    }

    @Test
    @DisplayName("reports a lifetime of 3600 seconds as a JSON number for a 60 minute setting")
    void reportsALifetimeOf3600SecondsAsAJsonNumber() throws Exception {
        MvcResult result = requestToken(configuredUsername(), TEST_PASSWORD);
        Map<String, Object> body = readBody(result);

        assertThat(properties.jwt().expirationMinutes()).isEqualTo(EXPECTED_EXPIRATION_MINUTES);
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(EXPECTED_EXPIRES_IN_SECONDS);
        assertThat(body.get(EXPIRES_IN)).isInstanceOf(Number.class);
        assertThat(body.get(EXPIRES_IN)).isNotInstanceOf(String.class);
        assertThat(((Number) body.get(EXPIRES_IN)).longValue())
                .isEqualTo(EXPECTED_EXPIRES_IN_SECONDS)
                .isEqualTo(jwtService.getExpirationSeconds());
        assertThat(result.getResponse().getContentAsString())
                .contains("\"" + EXPIRES_IN + "\":" + EXPECTED_EXPIRES_IN_SECONDS);
    }

    @Test
    @DisplayName("mints a three segment compact JWS naming the configured principal")
    void mintsAThreeSegmentCompactJwsNamingTheConfiguredPrincipal() throws Exception {
        Instant beforeRequest = Instant.now();

        String token = mintedToken();

        assertThat(token).isNotBlank();
        String[] segments = token.split("\\.", -1);
        assertThat(segments).hasSize(3);
        assertThat(segments).noneMatch(String::isBlank);

        Optional<String> subject = jwtService.extractUsername(token);
        assertThat(subject).contains(configuredUsername());

        Optional<Instant> expiry = jwtService.extractExpiration(token);
        assertThat(expiry).isPresent();
        Instant earliest = beforeRequest.plusSeconds(EXPECTED_EXPIRES_IN_SECONDS)
                .minus(EXPIRY_TOLERANCE);
        Instant latest = Instant.now().plusSeconds(EXPECTED_EXPIRES_IN_SECONDS)
                .plus(EXPIRY_TOLERANCE);
        assertThat(expiry.get()).isBetween(earliest, latest);
    }

    @Test
    @DisplayName("mints a verifiable token on every successful request")
    void mintsAVerifiableTokenOnEverySuccessfulRequest() throws Exception {
        String first = mintedToken();
        String second = mintedToken();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(jwtService.extractUsername(first)).contains(configuredUsername());
        assertThat(jwtService.extractUsername(second)).contains(configuredUsername());
        assertThat(jwtService.extractExpiration(first)).isPresent();
        assertThat(jwtService.extractExpiration(second)).isPresent();
    }

    @Test
    @DisplayName("does not answer a GET on the token route with the success status")
    void doesNotAnswerAGetOnTheTokenRouteWithTheSuccessStatus() throws Exception {
        MvcResult result = mockMvc.perform(get(TOKEN_ENDPOINT)).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("answers a GET on the token route with the method envelope for a bearer caller")
    void answersAGetOnTheTokenRouteWithTheMethodEnvelopeForABearerCaller() throws Exception {
        mockMvc.perform(get(TOKEN_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mintedToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Method not allowed"));
    }

    @Test
    @DisplayName("returns 401 with an empty body for an incorrect password")
    void returns401WithAnEmptyBodyForAnIncorrectPassword() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), WRONG_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("returns 401 with an empty body for an unknown username")
    void returns401WithAnEmptyBodyForAnUnknownUsername() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(UNKNOWN_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("returns 401 and not 400 for an empty password")
    void returns401AndNot400ForAnEmptyPassword() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), "")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("returns 401 and no validation envelope for an empty JSON object")
    void returns401AndNoValidationEnvelopeForAnEmptyJsonObject() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("username");
        assertThat(body).doesNotContain("password");
        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("returns 401 with an empty body when the username member is absent")
    void returns401WithAnEmptyBodyWhenTheUsernameMemberIsAbsent() throws Exception {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("password", TEST_PASSWORD);

        assertBareUnauthorized(postToken(objectMapper.writeValueAsString(members)));
    }

    @Test
    @DisplayName("returns 401 with an empty body when the password member is absent")
    void returns401WithAnEmptyBodyWhenThePasswordMemberIsAbsent() throws Exception {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("username", configuredUsername());

        assertBareUnauthorized(postToken(objectMapper.writeValueAsString(members)));
    }

    @Test
    @DisplayName("returns 401 with an empty body when both members are null")
    void returns401WithAnEmptyBodyWhenBothMembersAreNull() throws Exception {
        assertBareUnauthorized(postToken(credentialBody(null, null)));
    }

    @Test
    @DisplayName("returns 401 with an empty body when the request carries no body")
    void returns401WithAnEmptyBodyWhenTheRequestCarriesNoBody() throws Exception {
        assertBareUnauthorized(postToken(""));
    }

    @ParameterizedTest(name = "[{index}] incorrect password")
    @ValueSource(strings = {WRONG_PASSWORD, "   ", "TEST-PASSWORD", "test-password ",
        "test_password"})
    @DisplayName("answers every incorrect password with 401 and never 403")
    void answersEveryIncorrectPasswordWith401AndNever403(String password) throws Exception {
        MvcResult result = postToken(credentialBody(configuredUsername(), password));

        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("returns 401 with an empty body for an empty username")
    void returns401WithAnEmptyBodyForAnEmptyUsername() throws Exception {
        assertBareUnauthorized(postToken(credentialBody("", TEST_PASSWORD)));
    }

    @Test
    @DisplayName("returns 401 with an empty body for a username above the length ceiling")
    void returns401WithAnEmptyBodyForAUsernameAboveTheLengthCeiling() throws Exception {
        String oversized = "u".repeat(CREDENTIAL_LENGTH_CEILING + 1);

        assertBareUnauthorized(postToken(credentialBody(oversized, TEST_PASSWORD)));
    }

    @Test
    @DisplayName("returns 401 with an empty body for a password above the length ceiling")
    void returns401WithAnEmptyBodyForAPasswordAboveTheLengthCeiling() throws Exception {
        String oversized = "p".repeat(CREDENTIAL_LENGTH_CEILING + 1);

        assertBareUnauthorized(postToken(credentialBody(configuredUsername(), oversized)));
    }

    @Test
    @DisplayName("returns 401 with an empty body for an encoded body above the byte ceiling")
    void returns401WithAnEmptyBodyForAnEncodedBodyAboveTheByteCeiling() throws Exception {
        String padded = "{\"username\":\"" + configuredUsername()
                + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"padding\":\"" + "x".repeat(LOGIN_BODY_BYTE_CEILING) + "\"}";

        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(padded))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(padded.length()).isGreaterThan(LOGIN_BODY_BYTE_CEILING);
        assertBareUnauthorized(result);
    }

    // The ceiling is driven by the same matcher the permitAll rule consults; an encoded spelling of
    // the path is bounded identically — DL-118 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("bounds an oversized body on a percent-encoded spelling of the token path")
    void boundsAnOversizedBodyOnAnEncodedTokenPath() throws Exception {
        String padded = "{\"username\":\"" + configuredUsername()
                + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"padding\":\"" + "x".repeat(LOGIN_BODY_BYTE_CEILING) + "\"}";

        MvcResult result = mockMvc.perform(post(ENCODED_TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(padded))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(padded.length()).isGreaterThan(LOGIN_BODY_BYTE_CEILING);
        assertBareUnauthorized(result);
    }

    // The encoded spelling reaches the same handler, so the ceiling is the only thing under test
    // above — DL-118 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("issues a token on a percent-encoded spelling of the token path")
    void issuesATokenOnAnEncodedTokenPath() throws Exception {
        mockMvc.perform(post(ENCODED_TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("bearer"));
    }

    @Test
    @DisplayName("returns the bad request envelope for malformed JSON")
    void returnsTheBadRequestEnvelopeForMalformedJson() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad request"));
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"username\":\"admin\",\"password\":\"a\",\"password\":\"b\"}",
        "{\"username\":\"admin\",\"password\":\"a\",\"username\":\"root\"}"
    })
    @DisplayName("reports a body repeating a credential member after the record is complete with 400 "
            + "and never 500, and repeats no submitted value")
    void reportsABodyRepeatingACredentialMemberAfterCompletionWith400(String body) throws Exception {
        String rendered = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value("Bad request"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(rendered).doesNotContain("password");
        assertThat(rendered).doesNotContain("username");
        assertThat(rendered).doesNotContain("fallback");
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"username\":\"admin\",\"username\":\"root\",\"password\":\"a\"}",
        "{\"password\":\"a\",\"password\":\"b\"}"
    })
    @DisplayName("answers a body repeating a credential member as an unreadable body, never with a "
            + "validation envelope and never leaking the member")
    void bindsABodyRepeatingACredentialMemberBeforeCompletion(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        String rendered = result.getResponse().getContentAsString();
        assertThat(rendered).isEqualTo("{\"error\":\"Bad request\"}");
        assertThat(rendered).doesNotContain("password");
        assertThat(rendered).doesNotContain("username");
    }

    @Test
    @DisplayName("returns the unsupported media type envelope for a plain text body")
    void returnsTheUnsupportedMediaTypeEnvelopeForAPlainTextBody() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(configuredUsername() + ":" + TEST_PASSWORD))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Unsupported media type"));
    }

    @Test
    @DisplayName("returns the not acceptable envelope when only XML is accepted")
    void returnsTheNotAcceptableEnvelopeWhenOnlyXmlIsAccepted() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content(credentialBody(configuredUsername(), TEST_PASSWORD)))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error").value("Not acceptable"));
    }

    @Test
    @DisplayName("answers a protected route with 401, an empty body and a bearer challenge "
            + "when no token is presented")
    void answersAProtectedRouteWith401WhenNoTokenIsPresented() throws Exception {
        MvcResult result = mockMvc.perform(get(PROTECTED_ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBareUnauthorized(result);
    }

    @Test
    @DisplayName("carries a minted token into the mapped handler of a protected route and answers it "
            + "with 200 and the handler's own body")
    void carriesAMintedTokenIntoTheMappedHandlerOfAProtectedRoute() throws Exception {
        when(twitterService.getPaginatedTweets(1, 10)).thenReturn(onePageOfTweets());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mintedToken()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tweets", hasSize(1)))
                .andExpect(jsonPath("$.tweets[0].id").value(PROTECTED_TWEET_ID))
                .andExpect(jsonPath("$.tweets[0].content").value(PROTECTED_TWEET_CONTENT))
                .andExpect(jsonPath("$.tweets[0].like_count").value(PROTECTED_TWEET_LIKE_COUNT))
                .andExpect(jsonPath("$.tweets[0].doubt_rating").value(PROTECTED_TWEET_DOUBT_RATING))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10))
                .andExpect(jsonPath("$.pagination.total").value(1))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));

        verify(twitterService).getPaginatedTweets(1, 10);
    }

    @Test
    @DisplayName("reaches no handler of a protected route when no token is presented")
    void reachesNoHandlerOfAProtectedRouteWhenNoTokenIsPresented() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT)).andExpect(status().isUnauthorized());

        verifyNoInteractions(twitterService, sentimentAnalysisService);
    }

    @Test
    @DisplayName("reaches no handler of a protected route when the token is not the one this service "
            + "minted")
    void reachesNoHandlerOfAProtectedRouteWhenTheTokenIsNotTheOneThisServiceMinted() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer header.payload.signature"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(twitterService, sentimentAnalysisService);
    }

    @ParameterizedTest(name = "[{index}] Authorization: {0}")
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer header.payload.signature", "Bearer ",
        "Basic not-a-bearer-scheme", "not-a-jwt"})
    @DisplayName("answers a protected route with 401 and an empty body for an unusable "
            + "Authorization header")
    void answersAProtectedRouteWith401ForAnUnusableAuthorizationHeader(String headerValue)
            throws Exception {
        MvcResult result = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, headerValue))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBareUnauthorized(result);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Bearer", "bearer", "BEARER", "BeArEr"})
    @DisplayName("reads the bearer scheme name without regard to letter case")
    void readsTheBearerSchemeNameWithoutRegardToLetterCase(String scheme) throws Exception {
        String token = mintedToken();

        MvcResult result = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, scheme + " " + token))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("saves the bearer authentication in the configured context repository")
    void savesTheBearerAuthenticationInTheConfiguredContextRepository() throws Exception {
        String token = mintedToken();

        MvcResult result = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        SecurityContext savedContext =
                securityContextRepository.loadDeferredContext(result.getRequest()).get();
        assertThat(savedContext.getAuthentication()).isNotNull();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo(configuredUsername());
        assertThat(savedContext.getAuthentication().isAuthenticated()).isTrue();
        assertThat(savedContext.getAuthentication().getAuthorities()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0} dispatch")
    @MethodSource("redispatchTypes")
    @DisplayName("authenticates a bearer token on async and error dispatches")
    void authenticatesABearerTokenOnAsyncAndErrorDispatches(DispatcherType dispatcherType)
            throws Exception {
        String token = mintedToken();

        MvcResult result = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .with(servletRequest -> {
                            servletRequest.setDispatcherType(dispatcherType);
                            return servletRequest;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private static Stream<DispatcherType> redispatchTypes() {
        return Stream.of(DispatcherType.ASYNC, DispatcherType.ERROR);
    }

    @ParameterizedTest(name = "[{index}] {0} " + LOGOUT_ENDPOINT)
    @MethodSource("logoutMethods")
    @DisplayName("serves no logout route")
    void servesNoLogoutRoute(HttpMethod method) throws Exception {
        MvcResult result = mockMvc.perform(request(method, LOGOUT_ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBareUnauthorized(result);
    }

    private static Stream<HttpMethod> logoutMethods() {
        return Stream.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("permits the token route alone without authentication")
    void permitsTheTokenRouteAloneWithoutAuthentication() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(configuredUsername(), TEST_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get(PROTECTED_ENDPOINT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/responses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/settings"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/analytics/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("resolves the credential store to an in-memory manager holding one bcrypt "
            + "principal")
    void resolvesTheCredentialStoreToAnInMemoryManagerHoldingOneBcryptPrincipal() {
        assertThat(userDetailsService).isInstanceOf(InMemoryUserDetailsManager.class);
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(applicationContext.getBeanNamesForType(UserDetailsService.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(PasswordEncoder.class)).hasSize(1);

        UserDetails principal = userDetailsService.loadUserByUsername(configuredUsername());

        assertThat(principal.getUsername()).isEqualTo(configuredUsername());
        assertThat(principal.getAuthorities()).isEmpty();
        assertThat(principal.getPassword()).isEqualTo(properties.auth().passwordHash());
        assertThat(passwordEncoder.matches(TEST_PASSWORD, principal.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(WRONG_PASSWORD, principal.getPassword())).isFalse();
    }

    @Test
    @DisplayName("holds no principal beyond the configured one")
    void holdsNoPrincipalBeyondTheConfiguredOne() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(UNKNOWN_USERNAME))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("registers no data source, entity manager or data repository bean")
    void registersNoDataSourceEntityManagerOrDataRepositoryBean() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(Class.forName("javax.sql.DataSource")))
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(
                Class.forName("jakarta.persistence.EntityManager"))).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(
                Class.forName("jakarta.persistence.EntityManagerFactory"))).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(
                Class.forName("org.springframework.data.repository.Repository"))).isEmpty();
    }

    @Test
    @DisplayName("carries a credential at the length ceiling to the authentication manager")
    void carriesACredentialAtTheLengthCeilingToTheAuthenticationManager() {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.accepting(configuredUsername());
        AuthController controller = new AuthController(manager, jwtService);
        LoginRequest atCeiling = new LoginRequest("u".repeat(CREDENTIAL_LENGTH_CEILING),
                "p".repeat(CREDENTIAL_LENGTH_CEILING));

        ResponseEntity<TokenResponse> response = controller.issueToken(atCeiling);

        assertThat(manager.invocations()).isEqualTo(1);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tokenType()).isEqualTo("bearer");
        assertThat(response.getBody().expiresIn()).isEqualTo(EXPECTED_EXPIRES_IN_SECONDS);
        assertThat(jwtService.extractUsername(response.getBody().accessToken()))
                .contains(configuredUsername());
    }

    @ParameterizedTest(name = "[{index}] {0} characters")
    @ValueSource(ints = {CREDENTIAL_LENGTH_CEILING + 1, CREDENTIAL_LENGTH_CEILING + 64})
    @DisplayName("stops a credential above the length ceiling before the authentication manager")
    void stopsACredentialAboveTheLengthCeilingBeforeTheAuthenticationManager(int length) {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.accepting(configuredUsername());
        AuthController controller = new AuthController(manager, jwtService);

        ResponseEntity<TokenResponse> byUsername = controller.issueToken(
                new LoginRequest("u".repeat(length), TEST_PASSWORD));
        ResponseEntity<TokenResponse> byPassword = controller.issueToken(
                new LoginRequest(configuredUsername(), "p".repeat(length)));

        assertThat(manager.invocations()).isZero();
        assertThat(byUsername.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(byUsername.getBody()).isNull();
        assertThat(byPassword.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(byPassword.getBody()).isNull();
    }

    // Bounded reporting of rejected credentials — DL-272 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports a burst of rejected credentials with one warning carrying the count")
    void reportsABurstOfRejectedCredentialsWithOneWarningCarryingTheCount() {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.rejecting(new BadCredentialsException("no"));
        AuthController controller = new AuthController(manager, jwtService);
        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            for (int attempt = 0; attempt < REJECTION_BURST; attempt++) {
                assertThat(controller.issueToken(new LoginRequest(configuredUsername(), "wrong"))
                        .getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }

            assertThat(manager.invocations()).isEqualTo(REJECTION_BURST);
            List<String> warnings = warningRecords(recorded);
            assertThat(warnings).as("one warning for the whole burst").hasSize(1);
            assertThat(warnings.get(0))
                    .contains("credential(s) submitted to POST /auth/token")
                    .contains("did not authenticate")
                    .doesNotContain("wrong");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("reports a burst of over-length credentials with one warning naming the bound only")
    void reportsABurstOfOverLengthCredentialsWithOneWarningNamingTheBoundOnly() {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.accepting(configuredUsername());
        AuthController controller = new AuthController(manager, jwtService);
        String oversized = "u".repeat(CREDENTIAL_LENGTH_CEILING + 1);
        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            for (int attempt = 0; attempt < REJECTION_BURST; attempt++) {
                assertThat(controller.issueToken(new LoginRequest(oversized, TEST_PASSWORD))
                        .getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }

            assertThat(manager.invocations()).isZero();
            List<String> warnings = warningRecords(recorded);
            assertThat(warnings).as("one warning for the whole burst").hasSize(1);
            assertThat(warnings.get(0))
                    .contains("exceeded the accepted length of " + CREDENTIAL_LENGTH_CEILING)
                    .doesNotContain(oversized);
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("counts the two rejection reasons independently")
    void countsTheTwoRejectionReasonsIndependently() {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.rejecting(new BadCredentialsException("no"));
        AuthController controller = new AuthController(manager, jwtService);
        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            controller.issueToken(new LoginRequest(configuredUsername(), "wrong"));
            controller.issueToken(new LoginRequest(
                    "u".repeat(CREDENTIAL_LENGTH_CEILING + 1), TEST_PASSWORD));

            List<String> warnings = warningRecords(recorded);
            assertThat(warnings).as("one warning per reason").hasSize(2);
            assertThat(warnings).anySatisfy(record ->
                    assertThat(record).contains("did not authenticate"));
            assertThat(warnings).anySatisfy(record ->
                    assertThat(record).contains("exceeded the accepted length"));
        } finally {
            detachAppender(recorded);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("credentialRejections")
    @DisplayName("answers a credential rejection with 401 and no body")
    void answersACredentialRejectionWith401AndNoBody(String description,
            AuthenticationException rejection) {
        RecordingAuthenticationManager manager =
                RecordingAuthenticationManager.rejecting(rejection);
        AuthController controller = new AuthController(manager, jwtService);

        ResponseEntity<TokenResponse> response = controller.issueToken(
                new LoginRequest(configuredUsername(), WRONG_PASSWORD));

        assertThat(manager.invocations()).isEqualTo(1);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getStatusCode().value()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getBody()).isNull();
    }

    private static Stream<Arguments> credentialRejections() {
        return Stream.of(
                Arguments.of("incorrect password", new BadCredentialsException("rejected")),
                Arguments.of("unknown principal", new UsernameNotFoundException("rejected")),
                Arguments.of("disabled principal", new DisabledException("rejected")));
    }

    @Test
    @DisplayName("answers an undeclared auth route with 404 and the not-found envelope")
    void answersAnUndeclaredAuthRouteWith404AndTheNotFoundEnvelope() throws Exception {
        for (String path : List.of("/auth", "/auth/", "/auth/tokens", "/auth/token/refresh",
                "/api/auth/token")) {

            mockMvc.perform(get(path).with(user(BUILT_IN_USERNAME)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(NOT_FOUND_BODY));
        }
    }

    @Test
    @DisplayName("refuses an unauthenticated undeclared auth route before the dispatcher")
    void refusesAnUnauthenticatedUndeclaredAuthRouteBeforeTheDispatcher() throws Exception {
        mockMvc.perform(get("/auth/tokens"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("propagates an authentication service failure past the 401 mapping")
    void propagatesAnAuthenticationServiceFailurePastThe401Mapping() {
        RecordingAuthenticationManager manager = RecordingAuthenticationManager.rejecting(
                new AuthenticationServiceException("provider unavailable"));
        AuthController controller = new AuthController(manager, jwtService);
        LoginRequest request = new LoginRequest(configuredUsername(), TEST_PASSWORD);

        assertThatThrownBy(() -> controller.issueToken(request))
                .isInstanceOf(AuthenticationServiceException.class);
        assertThat(manager.invocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("reads an absent request body as an unsupplied credential")
    void readsAnAbsentRequestBodyAsAnUnsuppliedCredential() {
        RecordingAuthenticationManager manager = RecordingAuthenticationManager.rejecting(
                new BadCredentialsException("rejected"));
        AuthController controller = new AuthController(manager, jwtService);

        ResponseEntity<TokenResponse> response = controller.issueToken(null);

        assertThat(manager.invocations()).isEqualTo(1);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getBody()).isNull();
    }

    @Nested
    @DisplayName("the record a token request leaves")
    class TokenRequestLogRecords {

        @Test
        @DisplayName("names no principal in the record a successful issuance leaves")
        void namesNoPrincipalInTheRecordASuccessfulIssuanceLeaves() {
            AuthController controller = new AuthController(
                    RecordingAuthenticationManager.accepting(configuredUsername()), jwtService);
            ListAppender<ILoggingEvent> records = attachRecorder();
            try {
                controller.issueToken(new LoginRequest(configuredUsername(), TEST_PASSWORD));

                assertThat(rendered(records)).hasSize(1);
                assertThat(rendered(records).get(0))
                        .contains(String.valueOf(EXPECTED_EXPIRES_IN_SECONDS))
                        .doesNotContain(configuredUsername())
                        .doesNotContain(BUILT_IN_USERNAME);
            } finally {
                detachRecorder(records);
            }
        }

        @Test
        @DisplayName("records the first rejection of a window at WARN and every later one at DEBUG")
        void recordsTheFirstRejectionOfAWindowAtWarnAndEveryLaterOneAtDebug() {
            AuthController controller = new AuthController(
                    RecordingAuthenticationManager.rejecting(
                            new BadCredentialsException("rejected")),
                    jwtService);
            ListAppender<ILoggingEvent> records = attachRecorder();
            try {
                for (int attempt = 0; attempt < 25; attempt++) {
                    controller.issueToken(new LoginRequest(UNKNOWN_USERNAME, WRONG_PASSWORD));
                }

                assertThat(levels(records, Level.WARN)).hasSize(1);
                assertThat(levels(records, Level.DEBUG)).hasSize(24);
                assertThat(levels(records, Level.WARN).get(0))
                        .contains("BadCredentialsException")
                        .doesNotContain(UNKNOWN_USERNAME)
                        .doesNotContain(WRONG_PASSWORD);
            } finally {
                detachRecorder(records);
            }
        }

        @Test
        @DisplayName("names no submitted credential in the record an over-length member leaves")
        void namesNoSubmittedCredentialInTheRecordAnOverLengthMemberLeaves() {
            String overLength = "p".repeat(CREDENTIAL_LENGTH_CEILING + 1);
            RecordingAuthenticationManager manager = RecordingAuthenticationManager.rejecting(
                    new BadCredentialsException("rejected"));
            AuthController controller = new AuthController(manager, jwtService);
            ListAppender<ILoggingEvent> records = attachRecorder();
            try {
                controller.issueToken(new LoginRequest(configuredUsername(), overLength));

                assertThat(manager.invocations()).isZero();
                assertThat(levels(records, Level.WARN)).hasSize(1);
                assertThat(levels(records, Level.WARN).get(0))
                        .contains("exceeded the accepted length")
                        .doesNotContain(overLength);
            } finally {
                detachRecorder(records);
            }
        }

        private ListAppender<ILoggingEvent> attachRecorder() {
            ListAppender<ILoggingEvent> records = new ListAppender<>();
            records.start();
            Logger logger = (Logger) LoggerFactory.getLogger(AuthController.class);
            logger.setLevel(Level.DEBUG);
            logger.addAppender(records);
            return records;
        }

        private void detachRecorder(ListAppender<ILoggingEvent> records) {
            Logger logger = (Logger) LoggerFactory.getLogger(AuthController.class);
            logger.detachAppender(records);
            logger.setLevel(null);
        }

        private List<String> rendered(ListAppender<ILoggingEvent> records) {
            return records.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        private List<String> levels(ListAppender<ILoggingEvent> records, Level level) {
            return records.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    @Nested
    @DisplayName("credential store configuration")
    class CredentialStoreConfiguration {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"$2a$10$", "$2b$12$", "$2y$14$"})
        @DisplayName("accepts a bcrypt hash of an accepted prefix and cost")
        void acceptsABcryptHashOfAnAcceptedPrefixAndCost(String prefix) {
            String passwordHash = prefix + configuredHashTail();

            UserDetails principal = securityConfigWith(passwordHash)
                    .userDetailsService()
                    .loadUserByUsername(configuredUsername());

            assertThat(principal.getPassword()).isEqualTo(passwordHash);
            assertThat(principal.getAuthorities()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"$2x$10$", "$2a$09$", "$2a$15$", "$2a$60$"})
        @DisplayName("rejects a bcrypt hash of an unaccepted prefix or cost")
        void rejectsABcryptHashOfAnUnacceptedPrefixOrCost(String prefix) {
            String passwordHash = prefix + configuredHashTail();

            assertThatThrownBy(() -> securityConfigWith(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain(passwordHash));
        }

        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {"not-a-bcrypt-hash", "$2a$10$too-short", "   ", ""})
        @DisplayName("rejects a configured value that is not a bcrypt hash")
        void rejectsAConfiguredValueThatIsNotABcryptHash(String passwordHash) {
            assertThatThrownBy(() -> securityConfigWith(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(properties.auth().passwordHash()));
        }

        @Test
        @DisplayName("rejects an absent bcrypt hash and names the supplying variable")
        void rejectsAnAbsentBcryptHashAndNamesTheSupplyingVariable() {
            assertThatThrownBy(() -> securityConfigWith(null).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .hasMessageContaining("AUTH_PASSWORD_HASH");
        }

        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("names the built-in principal when the configured name carries nothing")
        void namesTheBuiltInPrincipalWhenTheConfiguredNameCarriesNothing(String username) {
            UserDetails principal =
                    securityConfigWith(username, properties.auth().passwordHash())
                            .userDetailsService()
                            .loadUserByUsername(BUILT_IN_USERNAME);

            assertThat(principal.getUsername()).isEqualTo(BUILT_IN_USERNAME);
            assertThat(principal.getPassword()).isEqualTo(properties.auth().passwordHash());
        }

        // Net-new (no Python counterpart) — DL-189 — see docs/DECISION_LOG.md
        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {
            "${AUTH_PASSWORD_HASH}",
            "   ${AUTH_PASSWORD_HASH}   ",
            "${SOMETHING_ELSE}",
            "${}"
        })
        @DisplayName("reads an unresolved property placeholder as an unsupplied bcrypt hash")
        void readsAnUnresolvedPlaceholderAsAnUnsuppliedBcryptHash(String passwordHash) {
            assertThatThrownBy(() -> securityConfigWith(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .hasMessageContaining("is not configured")
                    .hasMessageContaining("AUTH_PASSWORD_HASH")
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).doesNotContain("does not carry a bcrypt hash");
                        assertThat(failure.getMessage()).doesNotContain(passwordHash.trim());
                    });
        }

        // Net-new (no Python counterpart) — DL-189 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("separates an unsupplied bcrypt hash from a malformed one by message")
        void separatesAnUnsuppliedBcryptHashFromAMalformedOneByMessage() {
            for (String unsupplied : new String[] {null, "", "   ", "${AUTH_PASSWORD_HASH}"}) {
                assertThatThrownBy(() -> securityConfigWith(unsupplied).userDetailsService())
                        .as("unsupplied value [%s]", unsupplied)
                        .hasMessageContaining("is not configured")
                        .satisfies(failure -> assertThat(failure.getMessage())
                                .doesNotContain("does not carry a bcrypt hash"));
            }

            assertThatThrownBy(() -> securityConfigWith("not-a-bcrypt-hash").userDetailsService())
                    .hasMessageContaining("does not carry a bcrypt hash")
                    .satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain("is not configured"));
        }

        // Net-new (no Python counterpart) — DL-189 — see docs/DECISION_LOG.md
        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {"${AUTH_PASSWORD_HASH", "AUTH_PASSWORD_HASH}", "pre${X}post", "$2a$10$"})
        @DisplayName("leaves a value that is not wholly a placeholder to the bcrypt format check")
        void leavesAValueThatIsNotWhollyAPlaceholderToTheFormatCheck(String passwordHash) {
            assertThatThrownBy(() -> securityConfigWith(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not carry a bcrypt hash")
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).doesNotContain("is not configured");
                        assertThat(failure.getMessage()).doesNotContain(passwordHash);
                    });
        }
    }

    private String configuredUsername() {
        return properties.auth().username();
    }

    private String configuredHashTail() {
        String configured = properties.auth().passwordHash();
        return configured.substring(configured.length() - BCRYPT_TAIL_LENGTH);
    }

    private SecurityConfig securityConfigWith(String passwordHash) {
        return securityConfigWith(configuredUsername(), passwordHash);
    }

    /**
     * A {@link SecurityConfig} over the bound configuration with the {@code scanner.auth} group
     * replaced.
     *
     * @param username the value to bind as {@code scanner.auth.username}
     * @param passwordHash the value to bind as {@code scanner.auth.password-hash}
     * @return a configuration instance holding the real {@link JwtService} and CORS policy
     */
    private SecurityConfig securityConfigWith(String username, String passwordHash) {
        ScannerProperties overridden = new ScannerProperties(
                properties.databaseUrl(),
                properties.popularityThreshold(),
                properties.responseGenerationDelaySeconds(),
                properties.twitter(),
                properties.notion(),
                properties.openai(),
                properties.jwt(),
                new ScannerProperties.Auth(username, passwordHash),
                properties.analytics(),
                properties.ingestion(),
                properties.background());
        return new SecurityConfig(overridden, jwtService, corsConfigurationSource);
    }

    /**
     * Serializes a credential request body with both members present, {@code null} included.
     *
     * @param username the value of the {@code username} member, which may be {@code null}
     * @param password the value of the {@code password} member, which may be {@code null}
     * @return the encoded JSON object
     * @throws Exception if serialization fails
     */
    private String credentialBody(String username, String password) throws Exception {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("username", username);
        members.put("password", password);
        return objectMapper.writeValueAsString(members);
    }

    /**
     * Performs {@code POST /auth/token} with a JSON content type and the given body.
     *
     * @param content the encoded request body
     * @return the completed result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult postToken(String content) throws Exception {
        return mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andReturn();
    }

    /**
     * Performs {@code POST /auth/token} with the given credential and requires a 200.
     *
     * @param username the value of the {@code username} member
     * @param password the value of the {@code password} member
     * @return the completed result
     * @throws Exception if the request cannot be performed or the status is not 200
     */
    private MvcResult requestToken(String username, String password) throws Exception {
        return mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String mintedToken() throws Exception {
        Object accessToken = readBody(requestToken(configuredUsername(), TEST_PASSWORD))
                .get(ACCESS_TOKEN);

        assertThat(accessToken).isInstanceOf(String.class);

        return (String) accessToken;
    }

    private Map<String, Object> readBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    private void assertBareUnauthorized(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN.value());

        String body = result.getResponse().getContentAsString();

        assertThat(body).isEmpty();
        assertThat(body).doesNotContain("error");
    }

    /**
     * An {@link AuthenticationManager} that counts its invocations and either returns an
     * authenticated token for a fixed principal name or raises the {@link AuthenticationException}
     * it was built with.
     */
    private static final class RecordingAuthenticationManager implements AuthenticationManager {

            private final AtomicInteger invocations = new AtomicInteger();

            private final String resolvedPrincipalName;

            private final AuthenticationException rejection;

        private RecordingAuthenticationManager(String resolvedPrincipalName,
                AuthenticationException rejection) {
            this.resolvedPrincipalName = resolvedPrincipalName;
            this.rejection = rejection;
        }

            private static RecordingAuthenticationManager accepting(String resolvedPrincipalName) {
            return new RecordingAuthenticationManager(resolvedPrincipalName, null);
        }

            private static RecordingAuthenticationManager rejecting(AuthenticationException rejection) {
            return new RecordingAuthenticationManager(null, rejection);
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            invocations.incrementAndGet();
            if (rejection != null) {
                throw rejection;
            }
            return new UsernamePasswordAuthenticationToken(resolvedPrincipalName, null, List.of());
        }

            private int invocations() {
            return invocations.get();
        }
    }


    /**
     * Builds the envelope the stubbed {@link TwitterService} returns for {@code GET /tweets}.
     *
     * @return one page carrying one row
     */
    private static PaginatedTweetsDto onePageOfTweets() {
        TweetDto row = new TweetDto(PROTECTED_TWEET_ID, PROTECTED_TWEET_CONTENT,
                PROTECTED_TWEET_LIKE_COUNT, LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                PROTECTED_TWEET_DOUBT_RATING, List.of("media-key-1"), null, "9001",
                List.of("GPT-4"));
        return new PaginatedTweetsDto(List.of(row), new PaginationDto(1, 10, 1L, 1));
    }

    /**
     * Attaches a recording appender to this controller's logger — DL-272.
     *
     * @return the attached appender
     */
    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuthController.class)).addAppender(appender);
        return appender;
    }

    /**
     * Detaches a recording appender from this controller's logger.
     *
     * @param appender the appender to detach
     */
    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuthController.class)).detachAppender(appender);
    }

    /**
     * Returns the formatted message of every {@code WARN} record the appender holds.
     *
     * @param appender the appender to read
     * @return the warning messages in the order they were written
     */
    private static List<String> warningRecords(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
