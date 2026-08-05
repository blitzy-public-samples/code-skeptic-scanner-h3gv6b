package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.api.AnalyticsController;
import com.codeskeptic.scanner.api.AuthController;
import com.codeskeptic.scanner.api.GlobalExceptionHandler;
import com.codeskeptic.scanner.api.ResponseController;
import com.codeskeptic.scanner.api.SettingController;
import com.codeskeptic.scanner.api.TweetController;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.AnalyticsService;
import com.codeskeptic.scanner.service.LlmService;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.SettingsService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;
import com.codeskeptic.scanner.task.TweetStreamClient;
import com.codeskeptic.scanner.task.TweetStreamListener;

// Replaces backend/tests/test_api.py, which imported fastapi.testclient at :L2 against a Flask
// application and could not be collected \u2014 see docs/DECISION_LOG.md DL-021, DL-115
/**
 * Proves the whole application context assembles and that the route surface is the one the retired
 * blueprints served.
 *
 * <p>This is the widest test in the suite: it starts every bean the application declares, on a
 * random port, under the {@code test} profile. Nothing is mocked and nothing is stubbed out.
 *
 * <p>The {@code test} profile supplies an in-memory database, a JWT secret, one
 * {@code scanner.auth} principal whose password is {@code test-password}, and blank X consumer
 * credentials, so ingestion contacts nothing. No test here resolves an OpenAI, Notion or Google
 * credential: {@code service.LlmService}, {@code service.NotionService} and
 * {@code service.SentimentAnalysisService} each create their client on first use, and no request
 * made here reaches one.
 *
 * <p>Each of the eleven routes the four Flask blueprints served at
 * {@code backend/app/main.py:L26-29} is addressed without a token and must answer 401 with an empty
 * body and a {@code WWW-Authenticate: Bearer} challenge. {@code POST /auth/token} is addressed with
 * the configured credentials and must answer 200 \u2014 DL-019, DL-021, DL-115.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("ScannerApplication")
class ScannerApplicationTests {

    /** Plaintext of the {@code scanner.auth.password-hash} declared by the {@code test} profile. */
    private static final String PASSWORD = "test-password";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("assembles the application context")
    void assemblesTheApplicationContext() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(ScannerProperties.class)).isNotNull();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBean("corsConfigurationSource", CorsConfigurationSource.class))
                .isNotNull();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("declaredBeanTypes")
    @DisplayName("publishes one bean of every type the application declares")
    void publishesOneBeanOfEveryTypeTheApplicationDeclares(String name, Class<?> type) {
        assertThat(context.getBeansOfType(type)).as(name).hasSize(1);
    }

    /**
     * Names the bean types the context must publish exactly once.
     *
     * @return one argument pair per type
     */
    private static List<Arguments> declaredBeanTypes() {
        return List.of(
                Arguments.of("TweetController", TweetController.class),
                Arguments.of("ResponseController", ResponseController.class),
                Arguments.of("SettingController", SettingController.class),
                Arguments.of("AnalyticsController", AnalyticsController.class),
                Arguments.of("AuthController", AuthController.class),
                Arguments.of("GlobalExceptionHandler", GlobalExceptionHandler.class),
                Arguments.of("TwitterService", TwitterService.class),
                Arguments.of("SentimentAnalysisService", SentimentAnalysisService.class),
                Arguments.of("NotionService", NotionService.class),
                Arguments.of("LlmService", LlmService.class),
                Arguments.of("ResponseService", ResponseService.class),
                Arguments.of("SettingsService", SettingsService.class),
                Arguments.of("AnalyticsService", AnalyticsService.class),
                Arguments.of("TweetRepository", TweetRepository.class),
                Arguments.of("ResponseRepository", ResponseRepository.class),
                Arguments.of("AiToolRepository", AiToolRepository.class),
                Arguments.of("SettingRepository", SettingRepository.class),
                Arguments.of("TweetStreamClient", TweetStreamClient.class),
                Arguments.of("TweetStreamListener", TweetStreamListener.class),
                Arguments.of("ResponseGenerationScheduler", ResponseGenerationScheduler.class));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("protectedRoutes")
    @DisplayName("answers every pre-existing route with a bare 401 for a caller carrying no token")
    void answersEveryPreExistingRouteWithABare401(HttpMethod method, String path) {
        ResponseEntity<String> response = rest.exchange(path, method,
                new HttpEntity<>(bodyFor(method), jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer");
    }

    /**
     * Names the eleven routes the four retired Flask blueprints served.
     *
     * @return one argument pair per route
     */
    private static List<Arguments> protectedRoutes() {
        return List.of(
                Arguments.of(HttpMethod.GET, "/tweets"),
                Arguments.of(HttpMethod.GET, "/tweets/1"),
                Arguments.of(HttpMethod.POST, "/tweets/1/analyze"),
                Arguments.of(HttpMethod.GET, "/responses"),
                Arguments.of(HttpMethod.GET, "/responses/1"),
                Arguments.of(HttpMethod.POST, "/responses"),
                Arguments.of(HttpMethod.PUT, "/responses/1"),
                Arguments.of(HttpMethod.GET, "/settings"),
                Arguments.of(HttpMethod.PUT, "/settings/tweet_popularity_threshold"),
                Arguments.of(HttpMethod.GET, "/analytics/trends"),
                Arguments.of(HttpMethod.GET, "/analytics/summary"));
    }

    @Test
    @DisplayName("answers the token route with 200 for the configured credentials")
    void answersTheTokenRouteWith200ForTheConfiguredCredentials() {
        ResponseEntity<String> response = rest.exchange("/auth/token", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}",
                        jsonHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("access_token")
                .contains("\"token_type\":\"bearer\"")
                .contains("expires_in");
    }

    @Test
    @DisplayName("serves an authenticated read once a token is presented")
    void servesAnAuthenticatedReadOnceATokenIsPresented() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());

        ResponseEntity<String> response =
                rest.exchange("/settings", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[").contains("tweet_popularity_threshold");
    }

    @Test
    @DisplayName("leaves ingestion stopped when the X consumer credentials carry nothing")
    void leavesIngestionStoppedWhenTheXConsumerCredentialsCarryNothing() {
        assertThat(context.getBean(TweetStreamClient.class).isRunning()).isFalse();
    }

    // The error-dispatch strategy of DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("keeps the framework error controller and replaces only its attribute source")
    void keepsTheFrameworkErrorControllerAndReplacesOnlyItsAttributeSource() {
        assertThat(context.getBeansOfType(ErrorController.class).values())
                .singleElement()
                .isInstanceOf(BasicErrorController.class);
        assertThat(context.getBeansOfType(ErrorAttributes.class)).hasSize(1);
        assertThat(context.getBean(ErrorAttributes.class).getClass().getEnclosingClass())
                .isEqualTo(GlobalExceptionHandler.class);
    }

    // The api package of AAP 0.3.1 — DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("declares no request-mapped class in the api package beyond the five controllers")
    void declaresNoRequestMappedClassInTheApiPackageBeyondTheFiveControllers() {
        assertThat(context.getBeanNamesForAnnotation(RestController.class))
                .containsExactlyInAnyOrder("tweetController", "responseController",
                        "settingController", "analyticsController", "authController");
    }

    // The measured consequence DL-183 records for a direct request to the error path — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("answers a direct request to the error path with the internal server error envelope")
    void answersADirectRequestToTheErrorPathWithTheInternalServerErrorEnvelope() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());

        ResponseEntity<String> response =
                rest.exchange("/error", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Internal server error\"}");
    }

    @Test
    @DisplayName("answers a direct unauthenticated request to the error path with a bare 401")
    void answersADirectUnauthenticatedRequestToTheErrorPathWithABare401() {
        ResponseEntity<String> response = rest.getForEntity("/error", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    /**
     * Authenticates the single configured principal and returns the token minted for it.
     *
     * @return the value of the {@code access_token} member of the token response
     */
    private String accessToken() {
        ResponseEntity<String> response = rest.exchange("/auth/token", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}",
                        jsonHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        int start = body.indexOf("\"access_token\":\"") + "\"access_token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * Builds a minimal JSON body for a method that carries one.
     *
     * @param method the request method
     * @return a body, or {@code null} for a method that carries none
     */
    private static String bodyFor(HttpMethod method) {
        if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
            return "{}";
        }
        return null;
    }

    /**
     * Builds headers declaring a JSON request body.
     *
     * @return the headers
     */
    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
