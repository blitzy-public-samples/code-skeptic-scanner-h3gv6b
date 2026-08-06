package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

// Net-new (no Python counterpart; notion_client built its own headers) — DL-151, DL-289 — see
// docs/DECISION_LOG.md
/**
 * Verifies the bean-level defaults {@link RestClientConfig} applies to the Notion transport.
 *
 * <p>Headers are observed by driving a request through a {@link RestClient} built by the bean method
 * over a builder carrying a capturing interceptor. The interceptor answers without calling the
 * execution, so the assertions read the headers the bean installed and no request leaves the process.
 */
class RestClientConfigTest {

    private static final String API_KEY = "secret-notion-key";

    private static final String DEFAULT_API_VERSION = "2022-06-28";

    /** Address the in-process server binds to. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Port number that asks the operating system for a free port. */
    private static final int EPHEMERAL_PORT = 0;

    /** Longest the in-process exchange is waited for. */
    private static final Duration REQUEST_LIMIT = Duration.ofSeconds(20);

    private final List<HttpHeaders> captured = new ArrayList<>();

    /** Every configuration built by a test, released by {@link #releaseTransports()} — DL-264. */
    private final List<RestClientConfig> built = new ArrayList<>();

    @AfterEach
    void releaseTransports() {
        built.forEach(RestClientConfig::shutdownNotionHttpClient);
        built.clear();
    }

    @Test
    @DisplayName("sends the configured Notion API version")
    void sendsTheConfiguredNotionApiVersion() {
        HttpHeaders headers = headersFor(notion(API_KEY, "2025-09-03"));

        assertThat(headers.getFirst("Notion-Version")).isEqualTo("2025-09-03");
    }

    @Test
    @DisplayName("sends the declared default version when none is configured")
    void sendsTheDeclaredDefaultVersionWhenNoneIsConfigured() {
        HttpHeaders headers = headersFor(notion(API_KEY, DEFAULT_API_VERSION));

        assertThat(headers.getFirst("Notion-Version")).isEqualTo(DEFAULT_API_VERSION);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("falls back to the declared default when the configured version is blank")
    void fallsBackToTheDeclaredDefaultWhenTheConfiguredVersionIsBlank(String configured) {
        HttpHeaders headers = headersFor(notion(API_KEY, configured));

        assertThat(headers.getFirst("Notion-Version")).isEqualTo(DEFAULT_API_VERSION);
    }

    @Test
    @DisplayName("strips surrounding whitespace from the configured version")
    void stripsSurroundingWhitespaceFromTheConfiguredVersion() {
        HttpHeaders headers = headersFor(notion(API_KEY, "  2025-09-03  "));

        assertThat(headers.getFirst("Notion-Version")).isEqualTo("2025-09-03");
    }

    @Test
    @DisplayName("sends the configured key as a bearer credential")
    void sendsTheConfiguredKeyAsABearerCredential() {
        HttpHeaders headers = headersFor(notion(API_KEY, DEFAULT_API_VERSION));

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    @DisplayName("strips surrounding whitespace from the configured key")
    void stripsSurroundingWhitespaceFromTheConfiguredKey() {
        HttpHeaders headers = headersFor(notion("  " + API_KEY + "\n", DEFAULT_API_VERSION));

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + API_KEY);
    }

    // CWE-113: strip() removes a leading or trailing CR or LF but leaves an embedded one — DL-289
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "key\r\nX-Injected: 1",
        "key\nX-Injected: 1",
        "key\rX-Injected: 1",
        "key\u0000tail",
        "key\u007Ftail",
        "key\u001Ftail",
    })
    @DisplayName("discards a key carrying a character that is illegal in a header value")
    void discardsAKeyCarryingACharacterThatIsIllegalInAHeaderValue(String configured) {
        HttpHeaders headers = headersFor(notion(configured, DEFAULT_API_VERSION));

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer ");
        assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Injected"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "2022-06-28\r\nX-Injected: 1",
        "2022-06-28\nX-Injected: 1",
        "2022-06-28\u0000",
    })
    @DisplayName("falls back to the declared default when the configured version is header-unsafe")
    void fallsBackToTheDeclaredDefaultWhenTheConfiguredVersionIsHeaderUnsafe(String configured) {
        HttpHeaders headers = headersFor(notion(API_KEY, configured));

        assertThat(headers.getFirst("Notion-Version")).isEqualTo(DEFAULT_API_VERSION);
        assertThat(headers.keySet()).noneMatch(name -> name.equalsIgnoreCase("X-Injected"));
    }

    @Test
    @DisplayName("sends an empty bearer credential when no key is configured")
    void sendsAnEmptyBearerCredentialWhenNoKeyIsConfigured() {
        HttpHeaders headers = headersFor(notion(null, DEFAULT_API_VERSION));

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer ");
    }

    @Test
    @DisplayName("sends JSON content type and accept")
    void sendsJsonContentTypeAndAccept() {
        HttpHeaders headers = headersFor(notion(API_KEY, DEFAULT_API_VERSION));

        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(headers.getFirst(HttpHeaders.ACCEPT)).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    }

    // Named transport, so a call behaves the same on any thread — see docs/DECISION_LOG.md DL-221
    @Test
    @DisplayName("carries a request issued from a reactive non-blocking thread")
    void carriesARequestIssuedFromAReactiveNonBlockingThread() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0);
        List<String> paths = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = "{\"id\":\"page-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE,
                    MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION));
            RestClient client = config
                    .notionRestClient(RestClient.builder(), config.notionHttpClient())
                    .mutate()
                    .baseUrl("http://" + LOOPBACK + ":" + server.getAddress().getPort())
                    .build();

            String body = Mono.fromCallable(() -> client.post()
                            .uri("/v1/pages")
                            .body("{}")
                            .retrieve()
                            .body(String.class))
                    .subscribeOn(Schedulers.parallel())
                    .block(REQUEST_LIMIT);

            assertThat(body).isEqualTo("{\"id\":\"page-1\"}");
            assertThat(paths).containsExactly("/v1/pages");
        } finally {
            server.stop(0);
        }
    }

    // The transport is a retained bean with a bounded shutdown — DL-264 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("publishes a transport carrying the configured connect timeout")
    void publishesATransportCarryingTheConfiguredConnectTimeout() {
        HttpClient transport = configFor(notion(API_KEY, DEFAULT_API_VERSION, 7L, 11L))
                .notionHttpClient();

        assertThat(transport.connectTimeout()).contains(Duration.ofSeconds(7L));
    }

    @Test
    @DisplayName("publishes a transport carrying the declared default connect timeout when the group is unbound")
    void publishesATransportCarryingTheDeclaredDefaultConnectTimeoutWhenTheGroupIsUnbound() {
        HttpClient transport = configFor(null).notionHttpClient();

        assertThat(transport.connectTimeout()).contains(Duration.ofSeconds(5L));
    }

    @ParameterizedTest(name = "[{index}] {0}s")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("rejects a connect timeout below one second, naming the key only")
    void rejectsAConnectTimeoutBelowOneSecondNamingTheKeyOnly(long configured) {
        RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION, configured, 11L));

        assertThatThrownBy(config::notionHttpClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scanner.notion.connect-timeout-seconds must be at least 1.");
    }

    @ParameterizedTest(name = "[{index}] {0}s")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("rejects a read timeout below one second, naming the key only")
    void rejectsAReadTimeoutBelowOneSecondNamingTheKeyOnly(long configured) {
        RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION, 7L, configured));
        HttpClient transport = config.notionHttpClient();

        assertThatThrownBy(() -> config.notionRestClient(RestClient.builder(), transport))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scanner.notion.read-timeout-seconds must be at least 1.");
    }

    @Test
    @DisplayName("terminates the published transport at shutdown")
    void terminatesThePublishedTransportAtShutdown() {
        RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION));
        HttpClient transport = config.notionHttpClient();
        assertThat(transport.isTerminated()).isFalse();

        config.shutdownNotionHttpClient();

        assertThat(transport.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("leaves the transport terminated when shutdown runs a second time")
    void leavesTheTransportTerminatedWhenShutdownRunsASecondTime() {
        RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION));
        HttpClient transport = config.notionHttpClient();
        config.shutdownNotionHttpClient();

        assertThatCode(config::shutdownNotionHttpClient).doesNotThrowAnyException();

        assertThat(transport.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("returns at once when no transport was ever published")
    void returnsAtOnceWhenNoTransportWasEverPublished() {
        RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION));

        assertThatCode(config::shutdownNotionHttpClient).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("issues every Notion request through the published transport")
    void issuesEveryNotionRequestThroughThePublishedTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, EPHEMERAL_PORT), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(HttpStatus.NO_CONTENT.value(), -1);
            exchange.close();
        });
        server.start();
        try {
            RestClientConfig config = configFor(notion(API_KEY, DEFAULT_API_VERSION));
            RestClient client = config
                    .notionRestClient(RestClient.builder(), config.notionHttpClient())
                    .mutate()
                    .baseUrl("http://" + LOOPBACK + ":" + server.getAddress().getPort())
                    .build();
            // The published transport carries the request, so releasing it stops the client.
            config.shutdownNotionHttpClient();

            assertThatThrownBy(() -> client.get().uri("/v1/pages").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("declares no inferred destroy method for the transport bean")
    void declaresNoInferredDestroyMethodForTheTransportBean() throws Exception {
        Bean bean = RestClientConfig.class.getMethod("notionHttpClient").getAnnotation(Bean.class);

        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEmpty();
    }

    @Test
    @DisplayName("runs the bounded shutdown on the destruction callback")
    void runsTheBoundedShutdownOnTheDestructionCallback() throws Exception {
        assertThat(RestClientConfig.class.getMethod("shutdownNotionHttpClient")
                .getAnnotation(PreDestroy.class)).isNotNull();
    }

    /**
     * Builds the transport over the supplied group and returns the headers it installed.
     *
     * @param notion the {@code scanner.notion} group to bind
     * @return the headers of the single request the transport prepared
     */
    private HttpHeaders headersFor(ScannerProperties.Notion notion) {
        RestClientConfig config = configFor(notion);
        RestClient client = config.notionRestClient(capturingBuilder(), config.notionHttpClient());

        client.get().uri("/v1/pages").retrieve().toBodilessEntity();

        assertThat(captured).hasSize(1);
        return captured.get(0);
    }

    /**
     * Builds a configuration over the supplied group and registers it for release.
     *
     * @param notion the {@code scanner.notion} group to bind
     * @return the configuration under test
     */
    private RestClientConfig configFor(ScannerProperties.Notion notion) {
        RestClientConfig config = new RestClientConfig(propertiesCarrying(notion));
        built.add(config);
        return config;
    }

    /**
     * Builds a request-scoped builder whose interceptor records the prepared headers and answers
     * without calling the execution, so no request leaves the process.
     *
     * @return the capturing builder
     */
    private RestClient.Builder capturingBuilder() {
        return RestClient.builder().requestInterceptor((request, body, execution) -> {
            captured.add(HttpHeaders.readOnlyHttpHeaders(request.getHeaders()));
            return emptyOkResponse();
        });
    }

    private ClientHttpResponse emptyOkResponse() throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        response.getHeaders().setContentLength(0L);
        return response;
    }

    private static ScannerProperties.Notion notion(String apiKey, String apiVersion) {
        return notion(apiKey, apiVersion, 5L, 10L);
    }

    private static ScannerProperties.Notion notion(String apiKey, String apiVersion,
            long connectTimeoutSeconds, long readTimeoutSeconds) {
        return new ScannerProperties.Notion(apiKey, "database-id", apiVersion, connectTimeoutSeconds,
                readTimeoutSeconds, 0, 0L);
    }

    private static ScannerProperties propertiesCarrying(ScannerProperties.Notion notion) {
        return new ScannerProperties(null, 100, 60L, null, notion, null, null, null, null, null, null);
    }
}
