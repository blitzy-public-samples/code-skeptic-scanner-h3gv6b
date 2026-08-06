package com.codeskeptic.scanner.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.codeskeptic.scanner.ScannerApplication;

// Net-new (no Python counterpart: the retired suite declared no configuration and started no
// server) — see docs/DECISION_LOG.md DL-118
/**
 * Exercises the one request-body bound the security chain enforces, against a running server.
 *
 * <p>The bound applies to {@code POST /auth/token} alone and is enforced inside the chain, ahead of
 * the {@code DispatcherServlet}. The eleven pre-existing routes carry no body-size bound of the
 * chain's making, which this class also asserts.
 *
 * <p>This class and {@code config/HttpChainIntegrationTest} are the two classes in the suite that
 * start a container — see docs/DECISION_LOG.md DL-274.
 *
 * <p>The context this class starts is the whole application context, on a random port, under the
 * {@code test} profile: an in-memory database, a JWT secret, the single {@code scanner.auth}
 * principal whose password is {@code test-password}, and blank X consumer credentials so ingestion
 * contacts nothing.
 */
@SpringBootTest(classes = ScannerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Request body bound")
class RequestBodyLimitIntegrationTest {

    /** Bound the chain places on {@code POST /auth/token} — DL-118. */
    private static final int MAXIMUM_LOGIN_REQUEST_BYTES = 4_096;

    /** Encoded size of the unbounded body the pre-existing routes are asserted to accept. */
    private static final int UNBOUNDED_BODY_BYTES = 131_072;

    /** Plaintext of the {@code scanner.auth.password-hash} declared by the {@code test} profile. */
    private static final String PASSWORD = "test-password";

    /** Client bound to the running server. */
    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("carries an accepted body through to the route it addresses")
    void carriesAnAcceptedBodyThroughToTheRouteItAddresses() {
        ResponseEntity<String> response = exchange(HttpMethod.PUT, "/responses/1",
                "{\"content\":\"A revised reply\",\"is_approved\":true}", bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Response not found or update failed\"}");
    }

    @Test
    @DisplayName("carries a body far larger than the token route's bound through to the route it addresses")
    void carriesABodyFarLargerThanTheTokenRoutesBoundThroughToTheRouteItAddresses() {
        String body = largeUpdateBody();
        assertThat(body).hasSize(UNBOUNDED_BODY_BYTES);

        ResponseEntity<String> response =
                exchange(HttpMethod.PUT, "/responses/1", body, bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Response not found or update failed\"}");
    }

    @Test
    @DisplayName("answers a large body carrying no token with the chain's bare 401")
    void answersALargeBodyCarryingNoTokenWithTheChainsBare401() {
        ResponseEntity<String> response =
                exchange(HttpMethod.PUT, "/responses/1", largeUpdateBody(), new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("answers an oversized login body with the token route's own empty 401")
    void answersAnOversizedLoginBodyWithTheTokenRoutesOwnEmpty401() {
        String body = "{\"username\":\"admin\",\"password\":\""
                + "p".repeat(MAXIMUM_LOGIN_REQUEST_BYTES) + "\"}";

        ResponseEntity<String> response =
                exchange(HttpMethod.POST, "/auth/token", body, new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("leaves a read route with no body unaffected")
    void leavesAReadRouteWithNoBodyUnaffected() {
        ResponseEntity<String> response =
                exchange(HttpMethod.GET, "/settings", null, bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[");
    }

    /**
     * Authenticates the single configured principal and returns the token minted for it.
     *
     * @return the value of the {@code access_token} member of the token response
     */
    private String accessToken() {
        ResponseEntity<Map<String, Object>> response = rest.exchange("/auth/token", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}",
                        jsonHeaders(new HttpHeaders())),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull().containsKey("access_token");
        return String.valueOf(body.get("access_token"));
    }

    /**
     * Builds a JSON body for {@code PUT /responses/{responseId}} far larger than the token route's
     * bound.
     *
     * @return a body whose encoded length is {@link #UNBOUNDED_BODY_BYTES}
     */
    private static String largeUpdateBody() {
        String envelope = "{\"content\":\"\"}";
        return "{\"content\":\""
                + "a".repeat(UNBOUNDED_BODY_BYTES - envelope.length())
                + "\"}";
    }

    /**
     * Issues one request against the running server.
     *
     * @param method  the request method
     * @param path    the request path
     * @param body    the request body, or {@code null} for a request that carries none
     * @param headers the headers to send; a JSON content type is added when a body is present
     * @return the response, with the body read as text
     */
    private ResponseEntity<String> exchange(HttpMethod method, String path, String body,
            HttpHeaders headers) {

        HttpEntity<String> entity = (body == null)
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(body, jsonHeaders(headers));
        return rest.exchange(path, method, entity, String.class);
    }

    /**
     * Adds the JSON content type to the supplied headers.
     *
     * @param headers the headers to extend
     * @return the same instance, carrying {@code Content-Type: application/json}
     */
    private static HttpHeaders jsonHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Builds headers carrying the bearer token.
     *
     * @param token the token to present
     * @return headers carrying {@code Authorization: Bearer <token>}
     */
    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
