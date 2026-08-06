package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

// Net-new (no Python counterpart) — DL-012, DL-045, DL-046, DL-247 — see docs/DECISION_LOG.md
/**
 * Verifies the production X API {@link WebClient} bean through a recording exchange function.
 */
@DisplayName("WebClientConfig")
class WebClientConfigTest {

    /** Host root configured by the production bean. */
    private static final String X_API_BASE_URL = "https://api.x.com";

    /** User-Agent configured by the production bean. */
    private static final String USER_AGENT = "code-skeptic-scanner-backend";

    /** Longest a recording exchange is waited for. */
    private static final Duration REQUEST_LIMIT = Duration.ofSeconds(5);

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "/oauth2/token",
        "/2/tweets/search/stream/rules",
        "/2/tweets/search/stream"
    })
    @DisplayName("resolves each X consumer path against the API host root")
    void resolvesEachXConsumerPathAgainstTheApiHostRoot(String path) {
        ClientRequest request = requestFor(path);

        assertThat(request.url().toString()).isEqualTo(X_API_BASE_URL + path);
    }

    @Test
    @DisplayName("applies JSON Accept and the backend User-Agent as defaults")
    void appliesJsonAcceptAndTheBackendUserAgentAsDefaults() {
        ClientRequest request = requestFor("/2/tweets/search/stream");

        assertThat(request.headers().get(HttpHeaders.ACCEPT))
                .containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(request.headers().get(HttpHeaders.USER_AGENT)).containsExactly(USER_AGENT);
    }

    @Test
    @DisplayName("preinstalls no credential or request content type")
    void preinstallsNoCredentialOrRequestContentType() {
        ClientRequest request = requestFor("/2/tweets/search/stream");

        assertThat(request.headers()).containsOnlyKeys(HttpHeaders.ACCEPT, HttpHeaders.USER_AGENT);
        assertThat(request.headers()).doesNotContainKeys(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);
    }

    /**
     * Builds the production bean and records one prepared request.
     *
     * @param path request path
     * @return prepared request
     */
    private static ClientRequest requestFor(String path) {
        List<ClientRequest> captured = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            captured.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };

        WebClient client = new WebClientConfig()
                .twitterWebClient(WebClient.builder().exchangeFunction(exchange));
        HttpStatus status = client.get()
                .uri(path)
                .exchangeToMono(response -> Mono.just(HttpStatus.valueOf(
                        response.statusCode().value())))
                .block(REQUEST_LIMIT);

        assertThat(status).isEqualTo(HttpStatus.OK);
        assertThat(captured).hasSize(1);
        return captured.get(0);
    }
}