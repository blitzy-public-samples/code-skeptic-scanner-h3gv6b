package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.codeskeptic.scanner.config.ContainerErrorResponseConfig.ErrorEnvelopeReportValve;
import com.codeskeptic.scanner.config.ContainerErrorResponseConfig.ErrorEnvelopeReportValve.RejectedRequestView;
import com.codeskeptic.scanner.security.SecurityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Net-new (no Python counterpart) — DL-222 — see docs/DECISION_LOG.md
/**
 * Exercises the container-level error surface: the valve that answers a request the connector
 * rejects before any filter runs, the view the valve applies its policies against, and the
 * customizer that installs it in place of the container's HTML error-report valve.
 */
@DisplayName("ContainerErrorResponseConfig")
class ContainerErrorResponseConfigTest {

    private static final String BAD_REQUEST = "Bad request";

    private static final String NOT_FOUND = "Not found";

    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private static final String HSTS = "Strict-Transport-Security";

    /** The permissive policy config/CorsConfig declares. */
    private static final CorsConfigurationSource PERMISSIVE_CORS = permissiveCorsSource();

    @Nested
    @DisplayName("the forwarded-scheme predicate")
    class ForwardedSchemePredicate {

        @Test
        @DisplayName("honours the forwarded scheme for the framework and native strategies only")
        void honoursTheForwardedSchemeForFrameworkAndNativeOnly() {
            assertThat(ContainerErrorResponseConfig
                    .honoursForwardedScheme(ForwardHeadersStrategy.FRAMEWORK)).isTrue();
            assertThat(ContainerErrorResponseConfig
                    .honoursForwardedScheme(ForwardHeadersStrategy.NATIVE)).isTrue();
            assertThat(ContainerErrorResponseConfig
                    .honoursForwardedScheme(ForwardHeadersStrategy.NONE)).isFalse();
            assertThat(ContainerErrorResponseConfig.honoursForwardedScheme(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the rejected-request view")
    class RejectedRequestViewBehaviour {

        @Test
        @DisplayName("reports a resolvable target in place of the one the connector rejected")
        void reportsAResolvableTarget() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings%00");
            rejected.setContextPath("/rejected");
            rejected.setPathInfo("/nonsense");

            RejectedRequestView view = new RejectedRequestView(rejected, true);

            assertThat(view.getRequestURI()).isEqualTo("/");
            assertThat(view.getServletPath()).isEqualTo("/");
            assertThat(view.getPathInfo()).isNull();
            assertThat(view.getContextPath()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] X-Forwarded-Proto {0} is secure: {1}")
        @CsvSource({"https,true", "HTTPS,true", "'https, http',true", "' https ',true",
                "http,false", "'http,https',false", "'',false"})
        @DisplayName("reads the forwarded scheme when the configuration honours it")
        void readsTheForwardedSchemeWhenHonoured(String forwarded, boolean expectedSecure) {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            rejected.addHeader(FORWARDED_PROTO, forwarded);

            RejectedRequestView view = new RejectedRequestView(rejected, true);

            assertThat(view.isSecure()).isEqualTo(expectedSecure);
            assertThat(view.getScheme()).isEqualTo(expectedSecure ? "https" : "http");
        }

        @Test
        @DisplayName("ignores the forwarded scheme when the configuration does not honour it")
        void ignoresTheForwardedSchemeWhenNotHonoured() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            rejected.addHeader(FORWARDED_PROTO, "https");

            RejectedRequestView view = new RejectedRequestView(rejected, false);

            assertThat(view.isSecure()).isFalse();
            assertThat(view.getScheme()).isEqualTo("http");
        }

        @Test
        @DisplayName("keeps a request that already arrived over TLS secure")
        void keepsARequestThatArrivedOverTlsSecure() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            rejected.setSecure(true);
            rejected.setScheme("https");

            RejectedRequestView view = new RejectedRequestView(rejected, false);

            assertThat(view.isSecure()).isTrue();
        }

        @Test
        @DisplayName("keeps every request header reachable")
        void keepsEveryRequestHeaderReachable() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            rejected.addHeader(HttpHeaders.ORIGIN, "https://evil.example.com");

            RejectedRequestView view = new RejectedRequestView(rejected, true);

            assertThat(view.getHeader(HttpHeaders.ORIGIN)).isEqualTo("https://evil.example.com");
        }

        @Test
        @DisplayName("resolves the application's CORS policy, which the rejected target does not")
        void resolvesTheApplicationsCorsPolicy() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            RejectedRequestView view = new RejectedRequestView(rejected, false);

            CorsConfiguration resolved = PERMISSIVE_CORS.getCorsConfiguration(view);

            assertThat(resolved).isNotNull();
            assertThat(resolved.checkOrigin("https://evil.example.com")).isEqualTo("*");
        }
    }

    @Nested
    @DisplayName("the valve")
    class ValveBehaviour {

        @ParameterizedTest(name = "[{index}] a recorded {0} answers {1} carrying {2}")
        @CsvSource({"400,400,Bad request", "404,404,Not found", "405,405,Method not allowed",
                "406,406,Not acceptable", "415,415,Unsupported media type",
                "414,414,Bad request", "431,431,Bad request", "500,500,Internal server error",
                "505,500,Internal server error"})
        @DisplayName("writes the sanctioned envelope as JSON")
        void writesTheSanctionedEnvelopeAsJson(int recorded, int expectedStatus,
                String expectedMessage) {

            StringWriter written = new StringWriter();
            Response response = responseRecording(recorded, written);

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response, null);

            verify(response).setStatus(expectedStatus);
            verify(response).setContentType(MediaType.APPLICATION_JSON_VALUE);
            assertThat(written.toString()).isEqualTo("{\"error\":\"" + expectedMessage + "\"}");
        }

        @ParameterizedTest(name = "[{index}] a recorded {0} carries no body")
        @ValueSource(ints = {401, 403})
        @DisplayName("keeps a bodyless status bodyless")
        void keepsABodylessStatusBodyless(int recorded) {
            StringWriter written = new StringWriter();
            Response response = responseRecording(recorded, written);

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response, null);

            verify(response).setStatus(recorded);
            verify(response).setContentLength(0);
            verify(response, never()).setContentType(anyString());
            assertThat(written.toString()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] a recorded {0} writes nothing")
        @ValueSource(ints = {100, 200, 204, 302, 399})
        @DisplayName("writes nothing for a status that is not an error")
        void writesNothingForANonErrorStatus(int recorded) {
            StringWriter written = new StringWriter();
            Response response = responseRecording(recorded, written);

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response, null);

            verify(response, never()).setStatus(recorded);
            verify(response, never()).setContentType(anyString());
            assertThat(written.toString()).isEmpty();
        }

        @Test
        @DisplayName("writes nothing when the response already carries content")
        void writesNothingWhenTheResponseAlreadyCarriesContent() {
            StringWriter written = new StringWriter();
            Response response = responseRecording(400, written);
            when(response.getContentWritten()).thenReturn(23L);

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response, null);

            assertThat(written.toString()).isEmpty();
            verify(response, never()).setContentType(anyString());
        }

        @Test
        @DisplayName("applies the shared transport-security policy against the rejected-request view")
        void appliesTheSharedTransportSecurityPolicy() {
            StringWriter written = new StringWriter();
            Response response = responseRecording(400, written);
            List<HttpServletRequest> policyRequests = new ArrayList<>();
            HeaderWriter recordingWriter = (request, written2) -> policyRequests.add(request);

            new ErrorEnvelopeReportValve(recordingWriter, PERMISSIVE_CORS, true)
                    .report(requestWithoutHeaders(), response, null);

            assertThat(policyRequests).hasSize(1);
            assertThat(policyRequests.get(0)).isInstanceOf(RejectedRequestView.class);
        }

        @Test
        @DisplayName("writes the CORS Vary headers on every response it writes")
        void writesTheCorsVaryHeaders() {
            Response response = responseRecording(400, new StringWriter());

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response, null);

            verify(response).addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            verify(response).addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            verify(response).addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        }

        @Test
        @DisplayName("writes the shared security header set, the Vary headers and the allowed origin")
        void writesTheSharedSecurityHeaderSetAndTheAllowedOrigin() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings%00");
            rejected.addHeader(HttpHeaders.ORIGIN, "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            valve(PERMISSIVE_CORS, false).applyHeaders(rejected, response);

            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-XSS-Protection")).isEqualTo("0");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
            assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
            assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeaders(HttpHeaders.VARY)).containsExactly(HttpHeaders.ORIGIN,
                    HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        }

        @Test
        @DisplayName("adds no second value to a Vary header the chain already wrote")
        void addsNoSecondValueToAVaryHeaderTheChainAlreadyWrote() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

            valve(PERMISSIVE_CORS, false).applyHeaders(rejected, response);

            assertThat(response.getHeaders(HttpHeaders.VARY)).containsExactly(HttpHeaders.ORIGIN,
                    HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        }

        @Test
        @DisplayName("writes no allowed origin when the request carries none")
        void writesNoAllowedOriginWhenTheRequestCarriesNone() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            valve(PERMISSIVE_CORS, false)
                    .applyHeaders(new MockHttpServletRequest("GET", "/settings%00"), response);

            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
            assertThat(response.getHeaders(HttpHeaders.VARY)).hasSize(3);
        }

        @Test
        @DisplayName("writes Strict-Transport-Security only for a request the configuration reports "
                + "as secure")
        void writesStrictTransportSecurityOnlyForASecureRequest() {
            MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/settings%00");
            MockHttpServletResponse plainResponse = new MockHttpServletResponse();
            valve(PERMISSIVE_CORS, true).applyHeaders(plain, plainResponse);
            assertThat(plainResponse.getHeader(HSTS)).isNull();

            MockHttpServletRequest forwarded = new MockHttpServletRequest("GET", "/settings%00");
            forwarded.addHeader(FORWARDED_PROTO, "https");
            MockHttpServletResponse forwardedResponse = new MockHttpServletResponse();
            valve(PERMISSIVE_CORS, true).applyHeaders(forwarded, forwardedResponse);
            assertThat(forwardedResponse.getHeader(HSTS))
                    .contains("max-age=31536000");

            MockHttpServletResponse notHonoured = new MockHttpServletResponse();
            valve(PERMISSIVE_CORS, false).applyHeaders(forwarded, notHonoured);
            assertThat(notHonoured.getHeader(HSTS)).isNull();
        }

        @Test
        @DisplayName("writes no allowed origin when the CORS policy cannot be resolved")
        void writesNoAllowedOriginWhenThePolicyCannotBeResolved() {
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/settings%00");
            rejected.addHeader(HttpHeaders.ORIGIN, "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();
            CorsConfigurationSource failing = request -> {
                throw new IllegalStateException("no parsed request path");
            };

            valve(failing, false).applyHeaders(rejected, response);

            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }

        @Test
        @DisplayName("carries no HTML, no server token and no failure detail")
        void carriesNoHtmlNoServerTokenAndNoFailureDetail() {
            StringWriter written = new StringWriter();
            Response response = responseRecording(400, written);

            valve(PERMISSIVE_CORS, false).report(requestWithoutHeaders(), response,
                    new IllegalStateException("a detail that must not reach the wire"));

            assertThat(written.toString())
                    .isEqualTo("{\"error\":\"" + BAD_REQUEST + "\"}")
                    .doesNotContain("<html", "<h1", "Tomcat", "Apache", "IllegalStateException",
                            "a detail that must not reach the wire");
        }

        @Test
        @DisplayName("carries only the literals the error surface already declares")
        void carriesOnlyTheLiteralsTheErrorSurfaceDeclares() {
            for (int recorded : new int[] {400, 404, 405, 406, 415, 418, 500, 503}) {
                StringWriter written = new StringWriter();
                valve(PERMISSIVE_CORS, false)
                        .report(requestWithoutHeaders(), responseRecording(recorded, written), null);

                assertThat(written.toString()).isIn(
                        "{\"error\":\"" + BAD_REQUEST + "\"}",
                        "{\"error\":\"" + NOT_FOUND + "\"}",
                        "{\"error\":\"Method not allowed\"}",
                        "{\"error\":\"Not acceptable\"}",
                        "{\"error\":\"Unsupported media type\"}",
                        "{\"error\":\"" + INTERNAL_SERVER_ERROR + "\"}");
            }
        }
    }

    @Nested
    @DisplayName("the customizer")
    class Customizer {

        @Test
        @DisplayName("replaces the container's error-report valve and suppresses the host default")
        void replacesTheContainersErrorReportValve() {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            configuration().errorEnvelopeValveCustomizer().customize(factory);

            StandardHost host = new StandardHost();
            host.getPipeline().addValve(new ErrorReportValve());
            StandardContext context = new StandardContext();
            context.setParent(host);

            for (TomcatContextCustomizer customizer : factory.getTomcatContextCustomizers()) {
                customizer.customize(context);
            }

            List<Valve> errorReportValves = errorReportValvesOf(host);
            assertThat(errorReportValves).hasSize(1);
            assertThat(errorReportValves.get(0)).isInstanceOf(ErrorEnvelopeReportValve.class);
            assertThat(host.getErrorReportValveClass()).isEmpty();
        }

        @Test
        @DisplayName("installs the valve even when the container carries no default one")
        void installsTheValveEvenWithoutADefaultOne() {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            configuration().errorEnvelopeValveCustomizer().customize(factory);

            StandardHost host = new StandardHost();
            StandardContext context = new StandardContext();
            context.setParent(host);

            for (TomcatContextCustomizer customizer : factory.getTomcatContextCustomizers()) {
                customizer.customize(context);
            }

            assertThat(errorReportValvesOf(host)).hasSize(1);
            assertThat(errorReportValvesOf(host).get(0))
                    .isInstanceOf(ErrorEnvelopeReportValve.class);
        }

        @Test
        @DisplayName("leaves the pipeline untouched when the context has no host parent")
        void leavesThePipelineUntouchedWithoutAHostParent() {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            configuration().errorEnvelopeValveCustomizer().customize(factory);

            StandardContext orphan = new StandardContext();

            for (TomcatContextCustomizer customizer : factory.getTomcatContextCustomizers()) {
                customizer.customize(orphan);
            }

            assertThat(orphan.getParent()).isNull();
        }

        private List<Valve> errorReportValvesOf(StandardHost host) {
            List<Valve> found = new ArrayList<>();
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve instanceof ErrorReportValve) {
                    found.add(valve);
                }
            }
            return found;
        }

        private ContainerErrorResponseConfig configuration() {
            ServerProperties serverProperties = new ServerProperties();
            serverProperties.setForwardHeadersStrategy(ForwardHeadersStrategy.FRAMEWORK);
            return new ContainerErrorResponseConfig(
                    SecurityConfig.defaultTransportSecurityHeaderWriter(), PERMISSIVE_CORS,
                    serverProperties);
        }
    }

    private static ErrorEnvelopeReportValve valve(CorsConfigurationSource corsConfigurationSource,
            boolean forwardedSchemeHonoured) {

        return new ErrorEnvelopeReportValve(SecurityConfig.defaultTransportSecurityHeaderWriter(),
                corsConfigurationSource, forwardedSchemeHonoured);
    }

    private static Request requestWithoutHeaders() {
        Request request = mock(Request.class);
        when(request.getScheme()).thenReturn("http");
        return request;
    }

    private static Response responseRecording(int recorded, StringWriter written) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(recorded);
        when(response.getContentWritten()).thenReturn(0L);
        try {
            when(response.getReporter()).thenReturn(new PrintWriter(written, true));
        } catch (Exception ex) {
            throw new IllegalStateException("the mocked reporter could not be prepared", ex);
        }
        return response;
    }

    private static CorsConfigurationSource permissiveCorsSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(CorsConfiguration.ALL);
        configuration.addAllowedMethod(CorsConfiguration.ALL);
        configuration.addAllowedHeader(CorsConfiguration.ALL);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
