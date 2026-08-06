package com.codeskeptic.scanner.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.codeskeptic.scanner.config.ScannerProperties;

// Ported from the bare flask_jwt_extended @jwt_required decorators at
// backend/app/api/tweets.py:L10, responses.py:L9,L23,L34,L52, settings.py:L8,L14 and
// analytics.py:L8,L18, which registered the decorator factory and enforced nothing (faithful port of
// intent) — see docs/DECISION_LOG.md DL-021
/**
 * Exercises {@link JwtAuthenticationFilter} directly, over mock servlet objects.
 *
 * <p>Assertions cover the header forms the filter accepts, the outcome for a token it does not accept,
 * the resolution of the verified {@code sub} claim against the credential store, and the fact that the
 * chain always continues exactly once.
 */
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    /** Principal the credential store holds in these tests. */
    private static final String CONFIGURED_USERNAME = "admin";

    /** Principal the credential store does not hold. */
    private static final String RETIRED_USERNAME = "retired-admin";

    /** Signing secret of the tokens these tests mint; 75 bytes. */
    private static final String SECRET =
            "test-only-jwt-secret-for-code-skeptic-scanner-build-verification-0123456789";

    /** A route the chain protects; the filter reads the method only. */
    private static final String PROTECTED_PATH = "/tweets";

    private final SecurityContextRepository contextRepository =
            new RequestAttributeSecurityContextRepository();

    private JwtService jwtService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void createFilter() {
        jwtService = new JwtService(properties());
        filter = new JwtAuthenticationFilter(jwtService, contextRepository, credentialStore());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("subject resolution")
    class SubjectResolution {

        @Test
        @DisplayName("authenticates a token whose subject the credential store holds")
        void authenticatesAKnownSubject() throws Exception {
            MockHttpServletRequest request = requestBearing(mint(CONFIGURED_USERNAME));
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            Authentication authentication = authenticationOf(request);
            assertThat(authentication).isNotNull();
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getName()).isEqualTo(CONFIGURED_USERNAME);
            assertThat(authentication.getAuthorities()).isEmpty();
            assertThat(authentication.getCredentials()).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("leaves a token whose subject the credential store no longer holds unauthenticated")
        void rejectsARetiredSubject() throws Exception {
            MockHttpServletRequest request = requestBearing(mint(RETIRED_USERNAME));
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authenticationOf(request)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("continues the chain when the credential store raises")
        void survivesAStoreThatRaises() throws Exception {
            UserDetailsService raising = username -> {
                throw new UsernameNotFoundException("no principal of that name");
            };
            JwtAuthenticationFilter guarded =
                    new JwtAuthenticationFilter(jwtService, contextRepository, raising);
            MockHttpServletRequest request = requestBearing(mint(CONFIGURED_USERNAME));
            MockFilterChain chain = new MockFilterChain();

            guarded.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authenticationOf(request)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("header handling")
    class HeaderHandling {

        @ParameterizedTest
        @ValueSource(strings = {"Bearer", "bearer", "BEARER", "BeArEr"})
        @DisplayName("reads the bearer scheme without regard to case")
        void readsTheSchemeWithoutRegardToCase(String scheme) throws Exception {
            MockHttpServletRequest request = request();
            request.addHeader("Authorization", scheme + " " + mint(CONFIGURED_USERNAME));

            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(authenticationOf(request)).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Token abc", ""})
        @DisplayName("authenticates nothing for a header carrying no bearer token")
        void authenticatesNothingWithoutABearerToken(String header) throws Exception {
            MockHttpServletRequest request = request();
            request.addHeader("Authorization", header);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authenticationOf(request)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("authenticates nothing when no Authorization header is present")
        void authenticatesNothingWithoutAHeader() throws Exception {
            MockHttpServletRequest request = request();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authenticationOf(request)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("authenticates nothing for a token the service does not accept")
        void authenticatesNothingForAnUnacceptedToken() throws Exception {
            MockHttpServletRequest request = requestBearing("not.a.token");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authenticationOf(request)).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects a null credential store")
        void rejectsANullCredentialStore() {
            assertThatThrownBy(() ->
                    new JwtAuthenticationFilter(jwtService, contextRepository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("userDetailsService");
        }

        @Test
        @DisplayName("rejects a null token service")
        void rejectsANullTokenService() {
            assertThatThrownBy(() ->
                    new JwtAuthenticationFilter(null, contextRepository, credentialStore()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("jwtService");
        }
    }

    private String mint(String username) {
        return jwtService.generateToken(username);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
        request.setRequestURI(PROTECTED_PATH);
        return request;
    }

    private static MockHttpServletRequest requestBearing(String token) {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private Authentication authenticationOf(MockHttpServletRequest request) {
        return contextRepository.loadDeferredContext(request).get().getAuthentication();
    }

    private static UserDetailsService credentialStore() {
        UserDetails principal = User.withUsername(CONFIGURED_USERNAME)
                .password("{noop}unused-by-this-filter")
                .authorities(Collections.emptyList())
                .build();
        return new InMemoryUserDetailsManager(List.of(principal));
    }

    private static ScannerProperties properties() {
        ScannerProperties.Jwt jwt = new ScannerProperties.Jwt(SECRET, "HS256", 60L);
        return new ScannerProperties("jdbc:h2:mem:unused", 100, 60, null, null, null, jwt, null, null,
                null, null);
    }

    @Test
    @DisplayName("mints a token this filter accepts")
    void mintsATokenThisFilterAccepts() {
        Optional<String> subject = jwtService.extractUsername(mint(CONFIGURED_USERNAME));

        assertThat(subject).contains(CONFIGURED_USERNAME);
    }
}
