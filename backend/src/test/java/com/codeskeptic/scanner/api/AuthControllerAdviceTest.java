package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;

// Net-new (no Python counterpart: the source registered no token route) — see docs/DECISION_LOG.md
// DL-019, DL-117, DL-257
/**
 * Proves that a failure of the authentication provider behind {@code POST /auth/token} is rendered
 * by {@link GlobalExceptionHandler} rather than by the controller.
 *
 * <p>This slice replaces the assembled {@link AuthenticationManager} with a mock, and
 * {@code AuthControllerTest} keeps the assembled one — see docs/DECISION_LOG.md DL-257. Only the
 * token route is mapped here, and {@link #theMockReplacesTheAssembledManager()} asserts the
 * replacement took effect, so no case in this class can pass without it.
 *
 * <p>{@link AuthenticationServiceException} is the provider-failure type: it is an
 * {@code AuthenticationException} that the controller deliberately does not fold into its 401
 * mapping, so it leaves the handler method and reaches the advice, which answers 500 with the
 * single-key envelope — see docs/DECISION_LOG.md DL-092, DL-117, DL-210.
 */
@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("AuthController POST /auth/token — the advice renders a provider failure")
class AuthControllerAdviceTest {

    /** The route under test. */
    private static final String TOKEN_ENDPOINT = "/auth/token";

    /** The 500 envelope the advice serves — see docs/DECISION_LOG.md DL-210. */
    private static final String INTERNAL_ERROR_BODY = "{\"error\":\"Internal server error\"}";

    /** A submitted principal name; it must never reach the answered body. */
    private static final String SUBMITTED_USERNAME = "admin";

    /** A submitted password; it must never reach the answered body. */
    private static final String SUBMITTED_PASSWORD = "test-password";

    /** The provider's own message; it must never reach the answered body. */
    private static final String PROVIDER_MESSAGE = "provider unavailable";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    /** Replaces the assembled manager so the failure arises inside the dispatch, not in the test. */
    @MockitoBean
    private AuthenticationManager authenticationManager;

    @Test
    @DisplayName("the mock replaces the assembled manager")
    void theMockReplacesTheAssembledManager() {
        assertThat(applicationContext.getBean(AuthenticationManager.class))
                .isSameAs(authenticationManager);
    }

    @Test
    @DisplayName("answers 500 and the internal-error envelope for a provider failure")
    void answers500AndTheInternalErrorEnvelopeForAProviderFailure() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new AuthenticationServiceException(PROVIDER_MESSAGE));

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(INTERNAL_ERROR_BODY));
    }

    @Test
    @DisplayName("answers 500 for an internal provider failure raised by the credential store")
    void answers500ForAnInternalProviderFailureRaisedByTheCredentialStore() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(
                new InternalAuthenticationServiceException(PROVIDER_MESSAGE));

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(INTERNAL_ERROR_BODY));
    }

    @Test
    @DisplayName("answers 500 for an unchecked failure that is not an authentication exception")
    void answers500ForAnUncheckedFailureThatIsNotAnAuthenticationException() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new IllegalStateException(PROVIDER_MESSAGE));

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(INTERNAL_ERROR_BODY));
    }

    @Test
    @DisplayName("names neither the submitted credential nor the provider message in the envelope")
    void namesNeitherTheSubmittedCredentialNorTheProviderMessageInTheEnvelope() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new AuthenticationServiceException(PROVIDER_MESSAGE));

        String body = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialBody()))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .isEqualTo(INTERNAL_ERROR_BODY)
                .doesNotContain(SUBMITTED_PASSWORD)
                .doesNotContain(PROVIDER_MESSAGE);
    }

    /**
     * Renders the request body carrying the submitted credential.
     *
     * @return a {@code LoginRequest} body, never {@code null}
     */
    private static String credentialBody() {
        return "{\"username\":\"" + SUBMITTED_USERNAME + "\",\"password\":\""
                + SUBMITTED_PASSWORD + "\"}";
    }
}
