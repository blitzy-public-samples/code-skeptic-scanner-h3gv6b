package com.codeskeptic.scanner.api;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;

/**
 * Exercises the token endpoint together with the servlet security chain.
 */
@WebMvcTest(AuthController.class)
@EnableConfigurationProperties(ScannerProperties.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
@DisplayName("AuthController and SecurityConfig")
class AuthControllerTest {

    private static final String TOKEN_ENDPOINT = "/auth/token";
    private static final String TEST_USERNAME = "admin";
    private static final String TEST_PASSWORD = "test-password";
    private static final String COMPACT_TOKEN = "header.payload.signature";
    private static final long EXPIRATION_SECONDS = 3_600L;
    private static final String TEST_PASSWORD_HASH =
            "$2a$10$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean(name = "corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("issues a bearer token without requiring an Authorization header")
    void issuesABearerTokenWithoutRequiringAnAuthorizationHeader() throws Exception {
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
                TEST_USERNAME, null, emptyList());
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authenticated);
        when(jwtService.generateToken(TEST_USERNAME)).thenReturn(COMPACT_TOKEN);
        when(jwtService.getExpirationSeconds()).thenReturn(EXPIRATION_SECONDS);

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value(COMPACT_TOKEN))
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.expires_in").value(EXPIRATION_SECONDS));

        verify(jwtService).generateToken(TEST_USERNAME);
    }

    @Test
    @DisplayName("accepts credential components at the declared length ceiling")
    void acceptsCredentialComponentsAtTheDeclaredLengthCeiling() throws Exception {
        String username = "u".repeat(256);
        String password = "p".repeat(256);
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
                TEST_USERNAME, null, emptyList());
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authenticated);
        when(jwtService.generateToken(TEST_USERNAME)).thenReturn(COMPACT_TOKEN);
        when(jwtService.getExpirationSeconds()).thenReturn(EXPIRATION_SECONDS);

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk());

        verify(authenticationManager).authenticate(any(Authentication.class));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("credentialRejections")
    @DisplayName("maps only expected credential rejections to an empty 401")
    void mapsExpectedCredentialRejectionsToAnEmpty401(
            String description, AuthenticationException rejection) throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class))).thenThrow(rejection);

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_USERNAME, "incorrect")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(jwtService);
    }

    private static Stream<Arguments> credentialRejections() {
        return Stream.of(
                Arguments.of("bad password", new BadCredentialsException("rejected")),
                Arguments.of("unknown user", new UsernameNotFoundException("rejected")),
                Arguments.of("disabled account", new DisabledException("rejected")));
    }

    @Test
    @DisplayName("lets authentication infrastructure failures reach the generic 500 handler")
    void letsAuthenticationInfrastructureFailuresReachTheGeneric500Handler() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new AuthenticationServiceException("provider unavailable"));

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Internal server error"));

        verifyNoInteractions(jwtService);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("oversizedCredentials")
    @DisplayName("rejects an oversized credential before authentication")
    void rejectsAnOversizedCredentialBeforeAuthentication(
            String description, String username, String password) throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(authenticationManager, jwtService);
    }

    private static Stream<Arguments> oversizedCredentials() {
        return Stream.of(
                Arguments.of("oversized username", "u".repeat(257), TEST_PASSWORD),
                Arguments.of("oversized password", TEST_USERNAME, "p".repeat(257)));
    }

    @Test
    @DisplayName("rejects an oversized encoded body before authentication")
    void rejectsAnOversizedEncodedBodyBeforeAuthentication() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"test-password\",\"padding\":\""
                + "x".repeat(4_096)
                + "\"}";

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(authenticationManager, jwtService);
    }

    @Test
    @DisplayName("returns a bearer challenge and an empty body for an unauthenticated route")
    void returnsABearerChallengeForAnUnauthenticatedRoute() throws Exception {
        mockMvc.perform(get("/protected-resource"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("returns the safe 400 envelope for malformed JSON")
    void returnsTheSafe400EnvelopeForMalformedJson() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad request"));
    }

    @Test
    @DisplayName("returns the safe 415 envelope for an unsupported request media type")
    void returnsTheSafe415EnvelopeForAnUnsupportedRequestMediaType() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("admin:test-password"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Unsupported media type"));
    }

    @Test
    @DisplayName("returns the safe 405 envelope for an authenticated method mismatch")
    void returnsTheSafe405EnvelopeForAnAuthenticatedMethodMismatch() throws Exception {
        when(jwtService.extractUsername(COMPACT_TOKEN)).thenReturn(Optional.of(TEST_USERNAME));

        mockMvc.perform(get(TOKEN_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMPACT_TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Method not allowed"));
    }

    @Test
    @DisplayName("returns the safe 406 envelope for an unacceptable response media type")
    void returnsTheSafe406EnvelopeForAnUnacceptableResponseMediaType() throws Exception {
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
                TEST_USERNAME, null, emptyList());
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authenticated);
        when(jwtService.generateToken(TEST_USERNAME)).thenReturn(COMPACT_TOKEN);
        when(jwtService.getExpirationSeconds()).thenReturn(EXPIRATION_SECONDS);

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content(loginBody(TEST_USERNAME, TEST_PASSWORD)))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error").value("Not acceptable"));
    }

    @ParameterizedTest(name = "[{index}] {0} /logout")
    @MethodSource("logoutMethods")
    @DisplayName("does not expose a public logout endpoint")
    void doesNotExposeAPublicLogoutEndpoint(HttpMethod method) throws Exception {
        mockMvc.perform(request(method, "/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(""));
    }

    private static Stream<HttpMethod> logoutMethods() {
        return Stream.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"bearer", "BEARER", "BeArEr"})
    @DisplayName("accepts the bearer scheme without regard to case")
    void acceptsTheBearerSchemeWithoutRegardToCase(String scheme) throws Exception {
        when(jwtService.extractUsername(COMPACT_TOKEN)).thenReturn(Optional.of(TEST_USERNAME));

        mockMvc.perform(get("/protected-resource")
                        .header(HttpHeaders.AUTHORIZATION, scheme + " " + COMPACT_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        verify(jwtService, atLeastOnce()).extractUsername(COMPACT_TOKEN);
    }

    @Test
    @DisplayName("saves bearer authentication in the configured request repository")
    void savesBearerAuthenticationInTheConfiguredRequestRepository() throws Exception {
        when(jwtService.extractUsername(COMPACT_TOKEN)).thenReturn(Optional.of(TEST_USERNAME));

        MvcResult result = mockMvc.perform(get("/protected-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMPACT_TOKEN))
                .andExpect(status().isNotFound())
                .andReturn();

        SecurityContext savedContext =
                securityContextRepository.loadDeferredContext(result.getRequest()).get();
        assertThat(savedContext.getAuthentication()).isNotNull();
        assertThat(savedContext.getAuthentication().getName()).isEqualTo(TEST_USERNAME);
        assertThat(savedContext.getAuthentication().isAuthenticated()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("redispatchTypes")
    @DisplayName("authenticates bearer tokens on async and error dispatches")
    void authenticatesBearerTokensOnAsyncAndErrorDispatches(DispatcherType dispatcherType)
            throws Exception {
        when(jwtService.extractUsername(COMPACT_TOKEN)).thenReturn(Optional.of(TEST_USERNAME));

        mockMvc.perform(get("/protected-resource")
                        .with(request -> {
                            request.setDispatcherType(dispatcherType);
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + COMPACT_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        verify(jwtService, atLeastOnce()).extractUsername(COMPACT_TOKEN);
    }

    private static Stream<DispatcherType> redispatchTypes() {
        return Stream.of(DispatcherType.ASYNC, DispatcherType.ERROR);
    }

    @Nested
    @DisplayName("bcrypt configuration validation")
    class BcryptConfigurationValidation {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "$2a$10$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS",
            "$2b$12$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS",
            "$2y$14$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS"
        })
        @DisplayName("accepts supported bcrypt prefixes and costs")
        void acceptsSupportedBcryptPrefixesAndCosts(String passwordHash) {
            UserDetailsService userDetailsService = securityConfig(passwordHash).userDetailsService();

            UserDetails principal = userDetailsService.loadUserByUsername(TEST_USERNAME);

            assertThat(principal.getPassword()).isEqualTo(passwordHash);
            assertThat(principal.getAuthorities()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "not-a-bcrypt-hash",
            "$2x$10$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS",
            "$2a$09$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS",
            "$2a$15$Lt3iVHWKlZC2hu2Nyh/NtekAa.gCoG3qZM.xoMT7w1b/zk3o7SBJS",
            "$2a$10$too-short"
        })
        @DisplayName("rejects malformed or impractical bcrypt configuration without echoing it")
        void rejectsMalformedOrImpracticalBcryptConfiguration(String passwordHash) {
            assertThatThrownBy(() -> securityConfig(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain(passwordHash));
        }

        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("rejects an absent bcrypt hash without reproducing a configured value")
        void rejectsAnEmptyBcryptHash(String passwordHash) {
            assertThatThrownBy(() -> securityConfig(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash")
                    .satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain(TEST_PASSWORD_HASH));
        }

        @Test
        @DisplayName("rejects a null bcrypt hash")
        void rejectsANullBcryptHash() {
            assertThatThrownBy(() -> securityConfig(null).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.auth.password-hash");
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
            assertThatThrownBy(() -> securityConfig(passwordHash).userDetailsService())
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
                assertThatThrownBy(() -> securityConfig(unsupplied).userDetailsService())
                        .as("unsupplied value [%s]", unsupplied)
                        .hasMessageContaining("is not configured")
                        .satisfies(failure -> assertThat(failure.getMessage())
                                .doesNotContain("does not carry a bcrypt hash"));
            }

            assertThatThrownBy(() -> securityConfig("not-a-bcrypt-hash").userDetailsService())
                    .hasMessageContaining("does not carry a bcrypt hash")
                    .satisfies(failure ->
                            assertThat(failure.getMessage()).doesNotContain("is not configured"));
        }

        // Net-new (no Python counterpart) — DL-189 — see docs/DECISION_LOG.md
        @ParameterizedTest(name = "[{index}] [{0}]")
        @ValueSource(strings = {"${AUTH_PASSWORD_HASH", "AUTH_PASSWORD_HASH}", "pre${X}post", "$2a$10$"})
        @DisplayName("leaves a value that is not wholly a placeholder to the bcrypt format check")
        void leavesAValueThatIsNotWhollyAPlaceholderToTheFormatCheck(String passwordHash) {
            assertThatThrownBy(() -> securityConfig(passwordHash).userDetailsService())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not carry a bcrypt hash")
                    .satisfies(failure -> {
                        assertThat(failure.getMessage()).doesNotContain("is not configured");
                        assertThat(failure.getMessage()).doesNotContain(passwordHash);
                    });
        }
    }

    private SecurityConfig securityConfig(String passwordHash) {
        ScannerProperties properties = new ScannerProperties(
                null, 0, 0L, null, null, null, null,
                new ScannerProperties.Auth(TEST_USERNAME, passwordHash), null, null);
        return new SecurityConfig(properties, jwtService, corsConfigurationSource);
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}