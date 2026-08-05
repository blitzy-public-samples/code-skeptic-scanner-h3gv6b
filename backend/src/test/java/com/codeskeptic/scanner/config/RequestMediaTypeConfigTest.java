package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.codeskeptic.scanner.config.RequestMediaTypeConfig.ContentTypeWithheldRequest;
import com.codeskeptic.scanner.config.RequestMediaTypeConfig.NonConcreteContentTypeFilter;

import jakarta.servlet.http.HttpServletRequest;

// Net-new (no Python counterpart) — DL-221 — see docs/DECISION_LOG.md
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
    }
}
