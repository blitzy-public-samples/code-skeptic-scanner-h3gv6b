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

// Net-new (no source construct): the retired suite had no configuration and started no server \u2014
// see docs/DECISION_LOG.md DL-118, DL-183
/**
 * Exercises the two request-body bounds the security chain enforces, against a running server.
 *
 * <p>The bounds are enforced inside the chain, ahead of the {@code DispatcherServlet}, and a breach
 * on an authenticated route is reported by {@link jakarta.servlet.http.HttpServletResponse#sendError(int)}.
 * A rejection therefore becomes an {@code ERROR} dispatch that only a servlet container performs,
 * which is why this class starts one instead of using {@code MockMvc}.
 *
 * <p>The context this class starts is the whole application context, on a random port, under the
 * {@code test} profile: an in-memory database, a JWT secret, the single {@code scanner.auth}
 * principal whose password is {@code test-password}, and blank X consumer credentials so ingestion
 * contacts nothing.
 */
@SpringBootTest(classes = ScannerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Request body bounds")
class RequestBodyLimitIntegrationTest {

    /** Bound the chain places on an authenticated request that carries a body. */
    private static final int MAXIMUM_REQUEST_BODY_BYTES = 65_536;

    /** Bound the chain places on {@code POST /auth/token}. */
    private static final int MAXIMUM_LOGIN_REQUEST_BYTES = 4_096;

    /** Body the wire carries for a rejected request that is answered 400. */
    private static final String BAD_REQUEST_BODY = "{\"error\":\"Bad request\"}";

    /** Plaintext of the {@code scanner.auth.password-hash} declared by the {@code test} profile. */
    private static final String PASSWORD = "test-password";

    /** Client bound to the running server. */
    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("answers an oversized body on an authenticated write route with 400 and the bad request envelope")
    void answersAnOversizedBodyOnAnAuthenticatedWriteRouteWith400AndTheBadRequestEnvelope() {
        ResponseEntity<String> response = exchange(HttpMethod.PUT, "/responses/1",
                oversizedUpdateBody(), bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(BAD_REQUEST_BODY);
    }

    @Test
    @DisplayName("answers an oversized body on the response creation route with 400 without reaching the generator")
    void answersAnOversizedBodyOnTheResponseCreationRouteWith400WithoutReachingTheGenerator() {
        String body = "{\"tweet_id\":\"" + "1".repeat(MAXIMUM_REQUEST_BODY_BYTES) + "\"}";

        ResponseEntity<String> response =
                exchange(HttpMethod.POST, "/responses", body, bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(BAD_REQUEST_BODY);
    }

    @Test
    @DisplayName("carries an accepted body through to the route it addresses")
    void carriesAnAcceptedBodyThroughToTheRouteItAddresses() {
        ResponseEntity<String> response = exchange(HttpMethod.PUT, "/responses/1",
                "{\"content\":\"A revised reply\",\"is_approved\":true}", bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Response not found or update failed\"}");
    }

    @Test
    @DisplayName("carries a body of exactly the maximum size through to the route it addresses")
    void carriesABodyOfExactlyTheMaximumSizeThroughToTheRouteItAddresses() {
        String envelope = "{\"content\":\"\"}";
        String body = "{\"content\":\""
                + "a".repeat(MAXIMUM_REQUEST_BODY_BYTES - envelope.length())
                + "\"}";
        assertThat(body).hasSize(MAXIMUM_REQUEST_BODY_BYTES);

        ResponseEntity<String> response =
                exchange(HttpMethod.PUT, "/responses/1", body, bearer(accessToken()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Response not found or update failed\"}");
    }

    @Test
    @DisplayName("answers an oversized body carrying no token with the chain's bare 401")
    void answersAnOversizedBodyCarryingNoTokenWithTheChainsBare401() {
        ResponseEntity<String> response =
                exchange(HttpMethod.PUT, "/responses/1", oversizedUpdateBody(), new HttpHeaders());

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
     * Builds a JSON body for {@code PUT /responses/{responseId}} one byte past the bound.
     *
     * @return a body whose encoded length is {@link #MAXIMUM_REQUEST_BODY_BYTES} plus one
     */
    private static String oversizedUpdateBody() {
        String envelope = "{\"content\":\"\"}";
        return "{\"content\":\""
                + "a".repeat(MAXIMUM_REQUEST_BODY_BYTES + 1 - envelope.length())
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
