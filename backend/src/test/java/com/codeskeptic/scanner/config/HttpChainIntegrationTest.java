package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.security.JwtService;

// Net-new (no Python counterpart) — DL-051, DL-183, DL-237, DL-238 — see
// docs/DECISION_LOG.md
/**
 * Exercises the running HTTP chain at its connector, filter, security, CORS, dispatcher and
 * controller boundaries.
 *
 * <p>The raw-socket cases send protocol bytes to the embedded Tomcat connector. The HTTP-client
 * cases issue a production preflight and an authenticated cross-origin controller request. The
 * production {@link CorsConfigurationSource} and {@link ServerProperties} beans are inspected
 * directly as part of the same running context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Running HTTP chain")
class HttpChainIntegrationTest {

    /** Header-size default declared by {@code application.yml}. */
    private static final DataSize DEFAULT_HEADER_LIMIT = DataSize.ofKilobytes(8);

    /** Header size that crosses the configured eight-kibibyte connector bound. */
    private static final int OVERSIZED_HEADER_BYTES = 9_000;

    /** Timeout applied to one connector exchange. */
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    /** Cross-origin caller used by the CORS cases. */
    private static final String ORIGIN = "https://client.example";

    /** Error envelope served for a connector rejection. */
    private static final String BAD_REQUEST_BODY = "{\"error\":\"Bad request\"}";

    /** Forwarded scheme header read by the configured transport-security policy. */
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    /** Header written when a forwarded HTTPS request is treated as secure. */
    private static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";

    /** Browser MIME-sniffing policy header. */
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** Browser framing policy header. */
    private static final String X_FRAME_OPTIONS = "X-Frame-Options";

    /** Three cache-key dimensions required on every CORS-aware response. */
    private static final List<String> EXPECTED_VARY = List.of(
            HttpHeaders.ORIGIN,
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

    /** Port of the embedded connector. */
    @LocalServerPort
    private int port;

    /** HTTP client bound to the embedded connector. */
    @Autowired
    private TestRestTemplate rest;

    /** Effective server settings bound in the running context. */
    @Autowired
    private ServerProperties serverProperties;

    /** Production CORS source published by {@link CorsConfig}. */
    @Autowired
    @Qualifier("corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    /** Token service verified by the running security chain. */
    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("binds the eight-kibibyte header default and an environment override")
    void bindsTheDefaultAndOverrideHeaderSize() throws IOException {
        assertThat(serverProperties.getMaxHttpRequestHeaderSize())
                .isEqualTo(DEFAULT_HEADER_LIMIT);
        assertThat(bindServerProperties(null).getMaxHttpRequestHeaderSize())
                .isEqualTo(DEFAULT_HEADER_LIMIT);
        assertThat(bindServerProperties("12KB").getMaxHttpRequestHeaderSize())
                .isEqualTo(DataSize.ofKilobytes(12));
    }

    @Test
    @DisplayName("answers a header above the connector limit with the sanctioned JSON envelope")
    void answersAnOversizedHeaderWithTheSanctionedJsonEnvelope() throws IOException {
        RawResponse response = exchangeRaw("GET /settings HTTP/1.1\r\n"
                + hostHeader()
                + "X-Pad: " + "a".repeat(OVERSIZED_HEADER_BYTES) + "\r\n"
                + "Connection: close\r\n\r\n");

        assertConnectorBadRequest(response);
        assertThat(response.headerValues(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEmpty();
        assertThat(response.headerValues(STRICT_TRANSPORT_SECURITY)).isEmpty();
    }

    @Test
    @DisplayName("answers an invalid request target with the sanctioned JSON envelope")
    void answersAnInvalidRequestTargetWithTheSanctionedJsonEnvelope() throws IOException {
        RawResponse response = exchangeRaw("GET /%zz HTTP/1.1\r\n"
                + hostHeader()
                + "Connection: close\r\n\r\n");

        assertConnectorBadRequest(response);
        assertThat(response.headerValues(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEmpty();
        assertThat(response.headerValues(STRICT_TRANSPORT_SECURITY)).isEmpty();
    }

    @Test
    @DisplayName("applies CORS and transport-security headers to a connector rejection")
    void appliesCorsAndTransportSecurityHeadersToAConnectorRejection() throws IOException {
        RawResponse response = exchangeRaw("GET /settings HTTP/1.1\r\n"
                + hostHeader()
                + "Origin: " + ORIGIN + "\r\n"
                + FORWARDED_PROTO + ": https\r\n"
                + "X-Pad: " + "a".repeat(OVERSIZED_HEADER_BYTES) + "\r\n"
                + "Connection: close\r\n\r\n");

        assertConnectorBadRequest(response);
        assertThat(response.headerValues(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .containsExactly(CorsConfiguration.ALL);
        assertThat(response.headerValues(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEmpty();
        assertThat(response.headerValues(STRICT_TRANSPORT_SECURITY))
                .containsExactly("max-age=31536000 ; includeSubDomains");
    }

    @Test
    @DisplayName("publishes the production wildcard CORS policy with credentials disabled")
    void publishesTheProductionWildcardCorsPolicyWithCredentialsDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/settings");
        request.addHeader(HttpHeaders.ORIGIN, ORIGIN);

        CorsConfiguration configuration =
                corsConfigurationSource.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(CorsConfiguration.ALL);
        assertThat(configuration.getAllowedMethods()).containsExactly(CorsConfiguration.ALL);
        assertThat(configuration.getAllowedHeaders()).containsExactly(CorsConfiguration.ALL);
        assertThat(configuration.getAllowCredentials()).isNull();
        assertThat(configuration.getMaxAge()).isNull();
        assertThat(configuration.getExposedHeaders()).isNull();
    }

    @Test
    @DisplayName("answers a real preflight with the requested method and headers")
    void answersARealPreflightWithTheRequestedMethodAndHeaders() {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setOrigin(ORIGIN);
        requestHeaders.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT");
        requestHeaders.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                "authorization,content-type");

        ResponseEntity<String> response = rest.exchange("/settings", HttpMethod.OPTIONS,
                new HttpEntity<>(requestHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(CorsConfiguration.ALL);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .isEqualTo("PUT");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("authorization, content-type");
        assertThat(response.getHeaders()
                .containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isFalse();
        assertVary(response.getHeaders().getOrEmpty(HttpHeaders.VARY));
    }

    @Test
    @DisplayName("answers an authenticated cross-origin controller request with wildcard origin")
    void answersAnAuthenticatedCrossOriginControllerRequestWithWildcardOrigin() {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setOrigin(ORIGIN);
        requestHeaders.setBearerAuth(jwtService.generateToken("admin"));

        ResponseEntity<String> response = rest.exchange("/settings", HttpMethod.GET,
                new HttpEntity<>(requestHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).startsWith("[");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(CorsConfiguration.ALL);
        assertThat(response.getHeaders()
                .containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isFalse();
        assertVary(response.getHeaders().getOrEmpty(HttpHeaders.VARY));
    }

    /**
     * Applies the production YAML's {@code server} block with an optional environment value.
     *
     * @param headerLimit environment value for {@code MAX_HTTP_REQUEST_HEADER_SIZE}, or
     *                    {@code null} to use the YAML default
     * @return bound server properties
     * @throws IOException when {@code application.yml} cannot be loaded
     */
    private static ServerProperties bindServerProperties(String headerLimit) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        if (headerLimit != null) {
            propertySources.addFirst(new MapPropertySource("header-limit-override",
                    Map.of("MAX_HTTP_REQUEST_HEADER_SIZE", headerLimit)));
        }

        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        yaml.forEach(propertySources::addLast);

        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources));
        return binder.bind("server", Bindable.of(ServerProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "application.yml did not bind the server property group."));
    }

    /**
     * Sends one HTTP/1.1 request as protocol bytes to the embedded connector.
     *
     * @param request request bytes represented as ISO-8859-1 text
     * @return parsed status, headers, body and original response
     * @throws IOException when the connector cannot be reached or read
     */
    private RawResponse exchangeRaw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            try {
                return RawResponse.parse(readAll(socket.getInputStream()));
            } catch (SocketTimeoutException exception) {
                throw new AssertionError("The connector did not close its response within "
                        + SOCKET_TIMEOUT_MILLIS + " ms.", exception);
            }
        }
    }

    /**
     * Reads one connector response to end of stream.
     *
     * @param input connector input stream
     * @return response bytes represented as ISO-8859-1 text
     * @throws IOException when the stream cannot be read
     */
    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            sink.write(buffer, 0, read);
        }
        return sink.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * Verifies the common connector-level bad-request contract.
     *
     * @param response connector response
     */
    private static void assertConnectorBadRequest(RawResponse response) {
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.body()).isEqualTo(BAD_REQUEST_BODY);
        assertThat(response.headerValues(HttpHeaders.CONTENT_TYPE))
                .containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.headerValues(HttpHeaders.CONTENT_LENGTH)).containsExactly("23");
        assertThat(response.headerValues(HttpHeaders.TRANSFER_ENCODING)).isEmpty();
        assertThat(response.headerValues(X_CONTENT_TYPE_OPTIONS))
                .containsExactly("nosniff");
        assertThat(response.headerValues("X-XSS-Protection")).containsExactly("0");
        assertThat(response.headerValues(HttpHeaders.CACHE_CONTROL))
                .containsExactly("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.headerValues(HttpHeaders.PRAGMA)).containsExactly("no-cache");
        assertThat(response.headerValues(HttpHeaders.EXPIRES)).containsExactly("0");
        assertThat(response.headerValues(X_FRAME_OPTIONS)).containsExactly("DENY");
        assertThat(response.headerValues(HttpHeaders.SERVER)).isEmpty();
        assertVary(response.headerValues(HttpHeaders.VARY));

        assertThat(response.raw().toLowerCase(Locale.ROOT))
                .doesNotContain("<html", "<!doctype", "apache tomcat", "stack trace",
                        "stacktrace", "exception");
    }

    /**
     * Verifies the three CORS cache-key values with no duplicate.
     *
     * @param declared one or more header lines, each optionally carrying comma-separated values
     */
    private static void assertVary(List<String> declared) {
        List<String> values = splitHeaderValues(declared);
        assertThat(values)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_VARY)
                .doesNotHaveDuplicates();
    }

    /**
     * Splits comma-separated HTTP header values while preserving their declared order.
     *
     * @param declared header lines
     * @return trimmed individual values
     */
    private static List<String> splitHeaderValues(List<String> declared) {
        List<String> values = new ArrayList<>();
        for (String line : declared) {
            for (String value : line.split(",")) {
                values.add(value.strip());
            }
        }
        return values;
    }

    /**
     * Builds the mandatory HTTP/1.1 host header for the embedded connector.
     *
     * @return one terminated header line
     */
    private static String hostHeader() {
        return "Host: 127.0.0.1\r\n";
    }

    /**
     * Parsed response from one raw connector exchange.
     *
     * @param status  numeric status code
     * @param headers case-normalised response headers
     * @param body    response body
     * @param raw     original response text
     */
    private record RawResponse(int status, Map<String, List<String>> headers, String body,
            String raw) {

        /**
         * Parses one HTTP/1.1 response carrying a non-chunked connector error.
         *
         * @param raw original response text
         * @return parsed response
         */
        private static RawResponse parse(String raw) {
            int bodyStart = raw.indexOf("\r\n\r\n");
            if (bodyStart < 0) {
                throw new AssertionError("The connector response contained no header terminator.");
            }

            String[] lines = raw.substring(0, bodyStart).split("\r\n");
            String[] statusLine = lines[0].split(" ", 3);
            if (statusLine.length < 2) {
                throw new AssertionError("The connector response contained no numeric status.");
            }

            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int separator = lines[index].indexOf(':');
                if (separator < 1) {
                    throw new AssertionError("Malformed response header: " + lines[index]);
                }
                String name = lines[index].substring(0, separator)
                        .strip()
                        .toLowerCase(Locale.ROOT);
                String value = lines[index].substring(separator + 1).strip();
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }

            return new RawResponse(Integer.parseInt(statusLine[1]), headers,
                    raw.substring(bodyStart + 4), raw);
        }

        /**
         * Reads every value declared for one response header.
         *
         * @param name header name
         * @return immutable values, or an empty list
         */
        private List<String> headerValues(String name) {
            return List.copyOf(headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
        }
    }
}