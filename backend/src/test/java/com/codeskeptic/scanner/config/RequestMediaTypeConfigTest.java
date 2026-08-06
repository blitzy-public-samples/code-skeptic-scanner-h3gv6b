package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfiguration;

import com.codeskeptic.scanner.api.ResponseController;
import com.codeskeptic.scanner.config.RequestMediaTypeConfig.ContentTypeWithheldRequest;
import com.codeskeptic.scanner.config.RequestMediaTypeConfig.NonConcreteContentTypeFilter;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.ResponseService;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

// Net-new (no Python counterpart) — DL-236 — see docs/DECISION_LOG.md
/**
 * Asserts the contract of {@link RequestMediaTypeConfig}: which {@code Content-Type} values are
 * treated as naming no concrete media type, that such a header is withheld from the rest of the
 * request path, that a concrete media type is passed through untouched, and that the filter is
 * registered ahead of the security filter chain.
 */
class RequestMediaTypeConfigTest {

    private static final String CONTENT_TYPE = HttpHeaders.CONTENT_TYPE;

    private final RequestMediaTypeConfig configuration = new RequestMediaTypeConfig();

    @Nested
    @DisplayName("the media-type predicate")
    class MediaTypePredicate {

        @ParameterizedTest(name = "[{index}] {0} names no concrete media type")
        @ValueSource(strings = {"*/*", "application/*", "text/*", "multipart/*",
                "*/*;charset=UTF-8", "application/*+json"})
        void reportsAWildcardMediaType(String declared) {
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType(declared)).isTrue();
        }

        @ParameterizedTest(name = "[{index}] {0} cannot be parsed")
        @ValueSource(strings = {"not a media type", "*/json", "application", "/json", "application/"})
        void reportsAnUnparseableMediaType(String declared) {
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType(declared)).isTrue();
        }

        @ParameterizedTest(name = "[{index}] {0} names one concrete media type")
        @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "text/plain",
                "application/xml", "multipart/mixed", "multipart/form-data;boundary=x",
                "application/x-www-form-urlencoded"})
        void passesAConcreteMediaType(String declared) {
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType(declared)).isFalse();
        }

        @Test
        @DisplayName("treats an absent or blank header as naming no media type at all")
        void treatsAnAbsentOrBlankHeaderAsNamingNoMediaTypeAtAll() {
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType(null)).isFalse();
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType("")).isFalse();
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("the filter")
    class Filter {

        private final NonConcreteContentTypeFilter filter = new NonConcreteContentTypeFilter();

        @ParameterizedTest(name = "[{index}] withholds {0}")
        @ValueSource(strings = {"*/*", "application/*", "text/*", "multipart/*", "not a media type"})
        void withholdsANonConcreteContentType(String declared) throws Exception {
            MockHttpServletRequest request = requestCarrying(declared);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            HttpServletRequest seenDownstream = (HttpServletRequest) chain.getRequest();
            assertThat(seenDownstream).isInstanceOf(ContentTypeWithheldRequest.class);
            assertThat(seenDownstream.getContentType()).isNull();
            assertThat(seenDownstream.getHeader(CONTENT_TYPE)).isNull();
            assertThat(Collections.list(seenDownstream.getHeaders(CONTENT_TYPE))).isEmpty();
            assertThat(Collections.list(seenDownstream.getHeaderNames()))
                    .doesNotContain(CONTENT_TYPE);
        }

        @ParameterizedTest(name = "[{index}] passes {0} through")
        @ValueSource(strings = {"application/json", "text/plain", "multipart/mixed",
                "multipart/form-data;boundary=x"})
        void passesAConcreteContentTypeThrough(String declared) throws Exception {
            MockHttpServletRequest request = requestCarrying(declared);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(((HttpServletRequest) chain.getRequest()).getContentType())
                    .isEqualTo(declared);
        }

        @Test
        @DisplayName("passes a request carrying no Content-Type through untouched")
        void passesARequestCarryingNoContentTypeThrough() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tweets");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(request);
        }

        @Test
        @DisplayName("keeps every other header and the request body reachable downstream")
        void keepsEveryOtherHeaderAndTheBodyReachable() throws Exception {
            MockHttpServletRequest request = requestCarrying("*/*");
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token-value");
            request.addHeader("Origin", "https://evil.example.com");
            request.setContent("{\"username\":\"a\",\"password\":\"b\"}".getBytes());
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            HttpServletRequest seenDownstream = (HttpServletRequest) chain.getRequest();
            assertThat(seenDownstream.getHeader(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer token-value");
            assertThat(seenDownstream.getHeader("Origin")).isEqualTo("https://evil.example.com");
            assertThat(Collections.list(seenDownstream.getHeaderNames()))
                    .contains(HttpHeaders.AUTHORIZATION, "Origin");
            assertThat(new String(seenDownstream.getInputStream().readAllBytes()))
                    .isEqualTo("{\"username\":\"a\",\"password\":\"b\"}");
        }

        @Test
        @DisplayName("lets the header copy every component of the request path performs succeed")
        void letsTheHeaderCopyOfTheRequestPathSucceed() throws Exception {
            MockHttpServletRequest request = requestCarrying("*/*");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            HttpServletRequest seenDownstream = (HttpServletRequest) chain.getRequest();
            HttpHeaders copied = new ServletServerHttpRequest(seenDownstream).getHeaders();

            assertThat(copied.getContentType()).isNull();
            assertThat(copied.containsKey(CONTENT_TYPE)).isFalse();
        }

        @Test
        @DisplayName("the unwrapped request is the failure the wrapper prevents")
        void theUnwrappedRequestIsTheFailureTheWrapperPrevents() {
            MockHttpServletRequest request = requestCarrying("*/*");

            assertThat(request.getContentType()).isEqualTo("*/*");
            assertThat(RequestMediaTypeConfig.namesNoConcreteMediaType(request.getContentType()))
                    .isTrue();
        }

        private MockHttpServletRequest requestCarrying(String declared) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/token");
            request.setContentType(declared);
            return request;
        }
    }

    @Nested
    @DisplayName("the registration")
    class Registration {

        @Test
        @DisplayName("runs ahead of the security filter chain for every path")
        void runsAheadOfTheSecurityFilterChain() {
            FilterRegistrationBean<NonConcreteContentTypeFilter> registration =
                    configuration.nonConcreteContentTypeFilterRegistration();

            assertThat(registration.getFilter()).isInstanceOf(NonConcreteContentTypeFilter.class);
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(registration.getOrder())
                    .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        }

        @Test
        @DisplayName("carries the registration name the filter is installed under")
        void carriesTheRegistrationName() {
            assertThat(configuration.nonConcreteContentTypeFilterRegistration().getFilterName())
                    .isEqualTo("nonConcreteContentTypeFilter");
        }

        @Test
        @DisplayName("names the REQUEST dispatch alone")
        void namesTheRequestDispatchAlone() {
            assertThat(configuration.nonConcreteContentTypeFilterRegistration()
                    .determineDispatcherTypes())
                    .containsExactly(DispatcherType.REQUEST);
        }

        @Test
        @DisplayName("uses the earliest representable order ahead of the security chain")
        void registersAheadOfEveryOtherFilter() {
            FilterRegistrationBean<NonConcreteContentTypeFilter> registration =
                    configuration.nonConcreteContentTypeFilterRegistration();

            assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);
            assertThat(SecurityProperties.DEFAULT_FILTER_ORDER).isEqualTo(-100);
            assertThat(registration.getOrder())
                    .isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        }

        @Test
        @DisplayName("is enabled, matches before declared filters, and is bound to no servlet name")
        void isEnabledAndBoundToNoServletName() {
            FilterRegistrationBean<NonConcreteContentTypeFilter> registration =
                    configuration.nonConcreteContentTypeFilterRegistration();

            assertThat(registration.isEnabled()).isTrue();
            assertThat(registration.isMatchAfter()).isFalse();
            assertThat(registration.getServletNames()).isEmpty();
            assertThat(registration.getServletRegistrationBeans()).isEmpty();
            assertThat(registration.getInitParameters()).isEmpty();
        }

        @Test
        @DisplayName("publishes a distinct filter instance per registration and holds no state")
        void publishesADistinctFilterInstancePerRegistration() {
            assertThat(configuration.nonConcreteContentTypeFilterRegistration().getFilter())
                    .isNotSameAs(configuration.nonConcreteContentTypeFilterRegistration()
                            .getFilter());
        }
    }

    // Registered request-path coverage — DL-236 — see docs/DECISION_LOG.md
    /**
     * Drives requests through the filter as the container runs it: ahead of the security chain, the
     * {@code CorsFilter} inside it, the {@code DispatcherServlet} and
     * {@code api/GlobalExceptionHandler}.
     *
     * <p>{@code service/ResponseService} is mocked. Every refused request asserts no service
     * interaction.
     */
    @Nested
    @WebMvcTest(ResponseController.class)
    @ActiveProfiles("test")
    @Import({ SecurityConfig.class, CorsConfig.class, JwtService.class,
            RequestMediaTypeConfig.class })
    @EnableConfigurationProperties(ScannerProperties.class)
    @DisplayName("the registered filter inside the request path")
    class RegisteredFilterInThePath {

        /** Principal named by {@code scanner.auth.username} under the {@code test} profile. */
        private static final String PRINCIPAL = "admin";

        /** The envelope {@code api/GlobalExceptionHandler} serves for a refused media type. */
        private static final String UNSUPPORTED_MEDIA_TYPE =
                "{\"error\":\"Unsupported media type\"}";

        /** Cross-origin caller carried by the CORS cases. */
        private static final String ORIGIN = "https://client.example";

        /** Body of a well-formed generation request. */
        private static final String GENERATION_BODY = "{\"tweet_id\":\"1\"}";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JwtService jwtService;

        @MockitoBean
        private ResponseService responseService;

        @ParameterizedTest(name = "[{index}] Content-Type: {0}")
        @ValueSource(strings = {"*/*", "application/*", "text/*", "*/*;charset=UTF-8",
                "not a media type", "application/"})
        @DisplayName("answers an authenticated request naming no concrete media type 415 and reaches no service")
        void answersAnAuthenticatedNonConcreteContentTypeWith415(String declared) throws Exception {
            mockMvc.perform(post("/responses")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .header(CONTENT_TYPE, declared)
                            .content(GENERATION_BODY))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json(UNSUPPORTED_MEDIA_TYPE, JsonCompareMode.STRICT));

            verifyNoInteractions(responseService);
        }

        @ParameterizedTest(name = "[{index}] Content-Type: {0}")
        @ValueSource(strings = {"*/*", "application/*", "not a media type"})
        @DisplayName("answers an unauthenticated request naming no concrete media type with the bare 401")
        void answersAnUnauthenticatedNonConcreteContentTypeWithTheBare401(String declared)
                throws Exception {

            mockMvc.perform(post("/responses")
                            .header(CONTENT_TYPE, declared)
                            .content(GENERATION_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

            verifyNoInteractions(responseService);
        }

        @ParameterizedTest(name = "[{index}] Content-Type: {0}")
        @ValueSource(strings = {"*/*", "application/*", "not a media type"})
        @DisplayName("answers a cross-origin request naming no concrete media type 415 with the CORS headers")
        void answersACrossOriginNonConcreteContentTypeWith415(String declared) throws Exception {
            MvcResult result = mockMvc.perform(post("/responses")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(CONTENT_TYPE, declared)
                            .content(GENERATION_BODY))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().json(UNSUPPORTED_MEDIA_TYPE, JsonCompareMode.STRICT))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                            CorsConfiguration.ALL))
                    .andReturn();

            assertThat(result.getResponse().getHeaders(HttpHeaders.VARY))
                    .containsExactlyInAnyOrder(HttpHeaders.ORIGIN,
                            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            verifyNoInteractions(responseService);
        }

        @Test
        @DisplayName("answers a preflight naming no concrete media type without reaching a service")
        void answersAPreflightNamingNoConcreteMediaType() throws Exception {
            mockMvc.perform(options("/responses")
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(CONTENT_TYPE, "*/*"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                            CorsConfiguration.ALL))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST"));

            verifyNoInteractions(responseService);
        }

        @Test
        @DisplayName("carries a concrete media type through the whole path to the handler")
        void carriesAConcreteMediaTypeThroughToTheHandler() throws Exception {
            when(responseService.generateResponse("1")).thenReturn(null);

            mockMvc.perform(post("/responses")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(GENERATION_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                            CorsConfiguration.ALL));

            verify(responseService).generateResponse("1");
        }

        @Test
        @DisplayName("is installed in the request path under its registration name")
        void isInstalledInTheRequestPathUnderItsRegistrationName() throws Exception {
            MvcResult result = mockMvc.perform(post("/responses")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .header(CONTENT_TYPE, "*/*")
                            .content(GENERATION_BODY))
                    .andReturn();

            assertThat(result.getRequest().getContentType()).isEqualTo("*/*");
            assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        }

        /**
         * Builds the {@code Authorization} header value of an authenticated request.
         *
         * <p>The token is minted by the same {@code security/JwtService} the imported
         * {@code security/SecurityConfig} chain verifies — DL-021, DL-115.
         *
         * @return the {@code Bearer} credential of the principal {@value #PRINCIPAL}
         */
        private String bearer() {
            return "Bearer " + jwtService.generateToken(PRINCIPAL);
        }
    }
}
