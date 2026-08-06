package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskExecutionOutcome.Status;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.api.AnalyticsController;
import com.codeskeptic.scanner.api.AuthController;
import com.codeskeptic.scanner.api.GlobalExceptionHandler;
import com.codeskeptic.scanner.api.ResponseController;
import com.codeskeptic.scanner.api.SettingController;
import com.codeskeptic.scanner.api.TweetController;
import com.codeskeptic.scanner.config.DataSourcePoolProperties;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.service.AnalyticsService;
import com.codeskeptic.scanner.service.LlmService;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.SettingsService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.task.BackgroundOwnership;
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;
import com.codeskeptic.scanner.task.TweetStreamClient;
import com.codeskeptic.scanner.task.TweetStreamListener;
import com.codeskeptic.scanner.util.QueryParameters;

// Replaces backend/tests/test_api.py, which imported fastapi.testclient at :L2 against a Flask
// application and could not be collected — see docs/DECISION_LOG.md DL-021, DL-115
/**
 * Proves the whole application context assembles and that the route surface is the one the retired
 * blueprints served.
 *
 * <p>This is the widest test in the suite: it starts every bean the application declares under the
 * {@code test} profile, and nothing is mocked or stubbed out. The web environment is
 * {@link SpringBootTest.WebEnvironment#MOCK} and requests are issued through {@link MockMvc}, so the
 * real {@code SecurityFilterChain}, the real dispatcher and the real controllers all run without a
 * connector, a port or a server thread — DL-274. The connector itself is exercised by
 * {@code security.RequestBodyLimitIntegrationTest}, the one narrowly scoped test in this suite that
 * needs a running server.
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
 * the configured credentials and must answer 200 — DL-019, DL-021, DL-115.
 *
 * <p>The composition root itself is asserted here too: one application object, the two annotations
 * {@code ScannerApplication} declares and no other, and the {@code scanner.popularity-threshold} and
 * {@code scanner.analytics.trend-window-days} values the {@code test} profile publishes — DL-042,
 * DL-209.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ScannerApplication")
class ScannerApplicationTests {

    /** Plaintext of the {@code scanner.auth.password-hash} declared by the {@code test} profile. */
    private static final String PASSWORD = "test-password";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("assembles the application context")
    void assemblesTheApplicationContext() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(ScannerProperties.class)).isNotNull();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBean("corsConfigurationSource", CorsConfigurationSource.class))
                .isNotNull();
    }

    // The composition root of backend/app/main.py:L13-39 — DL-209 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("declares one application object carrying exactly two annotations")
    void declaresOneApplicationObjectCarryingExactlyTwoAnnotations() {
        assertThat(context.getBeansOfType(ScannerApplication.class)).hasSize(1);
        assertThat(context.getBeanNamesForAnnotation(SpringBootApplication.class)).hasSize(1);
        assertThat(ScannerApplication.class.getDeclaredAnnotations())
                .extracting(annotation -> annotation.annotationType().getName())
                .containsExactlyInAnyOrder(SpringBootApplication.class.getName(),
                        ConfigurationPropertiesScan.class.getName());
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
                Arguments.of("JwtService", JwtService.class),
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
                Arguments.of("ResponseGenerationScheduler", ResponseGenerationScheduler.class),
                Arguments.of("BackgroundOwnership", BackgroundOwnership.class));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("protectedRoutes")
    @DisplayName("answers every pre-existing route with a bare 401 for a caller carrying no token")
    void answersEveryPreExistingRouteWithABare401(HttpMethod method, String path) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(jsonRequest(method, path))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
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
    void answersTheTokenRouteWith200ForTheConfiguredCredentials() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString())
                .contains("access_token")
                .contains("\"token_type\":\"bearer\"")
                .contains("expires_in");
    }

    @Test
    @DisplayName("serves an authenticated read once a token is presented")
    void servesAnAuthenticatedReadOnceATokenIsPresented() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).startsWith("[")
                .contains("tweet_popularity_threshold");
    }

    // The finite page-size bound on the wire — DL-123 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("serves at most the maximum page size for a list request naming a larger one")
    void servesAtMostTheMaximumPageSizeForAListRequestNamingALargerOne() throws Exception {
        String token = accessToken();

        for (String route : List.of("/tweets", "/responses")) {
            MockHttpServletResponse response = mockMvc.perform(get(route)
                            .param("per_page", String.valueOf(Integer.MAX_VALUE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as("status of %s", route)
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(response.getContentAsString()).as("body of %s", route)
                    .contains("\"per_page\":" + QueryParameters.MAXIMUM_PAGE_SIZE)
                    .doesNotContain("\"per_page\":" + Integer.MAX_VALUE);
        }
    }

    @Test
    @DisplayName("leaves ingestion stopped when the X consumer credentials carry nothing")
    void leavesIngestionStoppedWhenTheXConsumerCredentialsCarryNothing() {
        assertThat(context.getBean(TweetStreamClient.class).isRunning()).isFalse();
    }

    // Full-context scheduler state under the test profile — DL-239, DL-281 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("registers the response-generation trigger and the ownership renewal, and keeps the "
            + "trigger pending beyond the suite window")
    void registersTheResponseGenerationTriggerAndTheOwnershipRenewal() {
        Map<String, ScheduledTaskHolder> holders =
                context.getBeansOfType(ScheduledTaskHolder.class);
        assertThat(holders).hasSize(1);

        ScheduledTaskHolder holder = holders.values().iterator().next();
        // Two tasks: the completion-based generation pass and the fixed-delay ownership renewal
        assertThat(holder.getScheduledTasks()).hasSize(2);
        assertThat(holder.getScheduledTasks())
                .extracting(scheduledTask -> scheduledTask.getTask().getClass().getSimpleName())
                .containsExactlyInAnyOrder("TriggerTask", "FixedDelayTask");

        ScheduledTask pass = holder.getScheduledTasks().stream()
                .filter(scheduledTask -> scheduledTask.getTask() instanceof TriggerTask)
                .findFirst()
                .orElseThrow();

        // The first pass runs at startup, which is the work-then-sleep order of
        // backend/app/tasks/response_generation.py:L41-50 — DL-251. The interval in force under the
        // test profile is a day, and no second pass falls inside the suite window.
        assertThat(context.getBean(ScannerProperties.class).responseGenerationDelaySeconds())
                .isEqualTo(86_400L);

        Instant nextExecution = pass.nextExecution();
        assertThat(nextExecution).isNotNull();
        assertThat(nextExecution).isBefore(Instant.now().plus(Duration.ofHours(25)));

        // The renewal is paced by the bound scanner.background.lease-renew-seconds — DL-281
        assertThat(context.getBean(ScannerProperties.class).background().leaseRenewSeconds())
                .isEqualTo(5L);
    }

    // The background-ownership lease under the test profile — DL-281 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("holds the background-ownership lease in the one running process")
    void holdsTheBackgroundOwnershipLeaseInTheOneRunningProcess() {
        BackgroundOwnership ownership = context.getBean(BackgroundOwnership.class);

        assertThat(ownership.isRunning()).isTrue();
        assertThat(ownership.isOwner()).isTrue();
        assertThat(ownership.instanceId()).isNotBlank();
        assertThat(context.getBean(SettingRepository.class)
                .findById(BackgroundOwnership.OWNER_SETTING_KEY))
                .isPresent()
                .get()
                .extracting(Setting::getValue, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(ownership.instanceId());
    }

    // The reserved coordination key — DL-284 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("withholds the ownership lease row from GET /settings and refuses to write it")
    void withholdsTheOwnershipLeaseRowFromTheSettingsRoute() throws Exception {
        MockHttpServletResponse listed = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andReturn()
                .getResponse();

        assertThat(listed.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(listed.getContentAsString())
                .doesNotContain(BackgroundOwnership.OWNER_SETTING_KEY);

        MockHttpServletResponse written = mockMvc.perform(
                        put("/settings/" + BackgroundOwnership.OWNER_SETTING_KEY)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"stolen\"}"))
                .andReturn()
                .getResponse();

        assertThat(written.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(written.getContentAsString()).isEqualTo("{\"error\":\"Setting not found\"}");
    }

    // The error-dispatch strategy of DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("serves the error path with the framework controller over this application's "
            + "attribute source")
    void servesTheErrorPathWithTheFrameworkControllerOverThisApplicationsAttributeSource() {
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
        // The error path is mapped by basicErrorController, which the framework declares — DL-183
        assertThat(context.getBeanNamesForAnnotation(RequestMapping.class))
                .containsExactly("basicErrorController");
        assertThat(context.getBeansOfType(ErrorController.class).keySet())
                .containsExactly("basicErrorController");
    }

    // A direct request to the error path — DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("answers a direct request to the error path with the internal server error envelope")
    void answersADirectRequestToTheErrorPathWithTheInternalServerErrorEnvelope() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"Internal server error\"}");
    }

    // The error path answers the JSON envelope for every representation that admits JSON — DL-183 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/json", "*/*", "application/*+json",
        "application/json;q=0.9,*/*;q=0.1"})
    @DisplayName("answers the error path with the JSON envelope for every representation admitting "
            + "JSON")
    void answersTheErrorPathWithTheJsonEnvelopeForEveryRepresentationAdmittingJson(String accept)
            throws Exception {

        MockHttpServletResponse response = mockMvc.perform(get("/error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .header(HttpHeaders.ACCEPT, accept))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"Internal server error\"}");
    }

    // An Accept header admitting no JSON representation carries the status alone — DL-183 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/plain", "application/xml"})
    @DisplayName("answers the error path with the status alone when the request admits no JSON")
    void answersTheErrorPathWithTheStatusAloneWhenTheRequestAdmitsNoJson(String accept)
            throws Exception {

        MockHttpServletResponse response = mockMvc.perform(get("/error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .header(HttpHeaders.ACCEPT, accept))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentAsString()).isEmpty();
    }

    // An HTML-only error dispatch renders the framework view, which carries no attribute value this
    // application withholds — DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("renders the error path for an HTML-only request without any framework attribute "
            + "value")
    void rendersTheErrorPathForAnHtmlOnlyRequestWithoutAnyFrameworkAttributeValue() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .accept(MediaType.TEXT_HTML))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);
        assertThat(response.getContentAsString())
                .contains("<div id='created'>null</div>")
                .contains("type=Internal server error, status=null")
                .doesNotContain("java.lang", "Exception", "at com.codeskeptic",
                        "org.springframework");
    }

    @Test
    @DisplayName("answers a direct unauthenticated request to the error path with a bare 401")
    void answersADirectUnauthenticatedRequestToTheErrorPathWithABare401() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/error")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).isEmpty();
    }

    // The popularity gate of backend/app/core/config.py:L10 and
    // backend/app/services/twitter_service.py:L43,L46 is a faithful port; the observation window of
    // the argument-less get_trends() at backend/app/api/analytics.py:L14 is net-new — DL-042 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("binds the popularity threshold and the analytics window from the test profile")
    void bindsThePopularityThresholdAndTheAnalyticsWindowFromTheTestProfile() {
        ScannerProperties properties = context.getBean(ScannerProperties.class);

        assertThat(properties.popularityThreshold()).isEqualTo(100);
        assertThat(properties.analytics().trendWindowDays()).isEqualTo(30);
    }

    // The three call literals of backend/app/services/llm_service.py:L22-25 are the shipped
    // defaults — see docs/DECISION_LOG.md DL-034, DL-145, DL-200, DL-202
    @Test
    @DisplayName("binds the source's OpenAI call parameters as the shipped defaults")
    void bindsTheSourcesOpenaiCallParametersAsTheShippedDefaults() {
        ScannerProperties.Openai openai = context.getBean(ScannerProperties.class).openai();

        assertThat(openai.maxCompletionTokens()).isEqualTo(150L);
        assertThat(openai.temperature()).isEqualTo(0.7d);
        assertThat(openai.n()).isEqualTo(1L);
        assertThat(openai.reasoningEffort()).isEqualTo("none");
    }

    // Net-new background ownership group — DL-250 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("binds the background ownership group from the test profile")
    void bindsTheBackgroundOwnershipGroupFromTheTestProfile() {
        ScannerProperties.Background background =
                context.getBean(ScannerProperties.class).background();

        assertThat(background).isNotNull();
        assertThat(background.enabled()).isTrue();
        assertThat(background.streamEnabled()).isTrue();
        assertThat(background.responseGenerationEnabled()).isTrue();
        assertThat(background.runsStream()).isTrue();
        assertThat(background.runsResponseGeneration()).isTrue();
    }

    // Net-new bounded mirror retry — DL-253 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("binds the notion mirror retry group from the test profile, so no test waits")
    void bindsTheNotionMirrorRetryGroupFromTheTestProfile() {
        ScannerProperties.Notion notion = context.getBean(ScannerProperties.class).notion();

        assertThat(notion.mirrorMaxRetries()).isZero();
        assertThat(notion.mirrorRetryBackoffMillis()).isZero();
    }

    // The allowlisted pool surface and the bounded graceful shutdown — DL-270, DL-271 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("publishes one pool carrying the geometry the test profile declares")
    void publishesOnePoolCarryingTheGeometryTheTestProfileDeclares() {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        HikariDataSource pool = context.getBean(HikariDataSource.class);

        assertThat(pool.getPoolName()).isEqualTo("code-skeptic-scanner-test-pool");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(pool.getMinimumIdle()).isEqualTo(1);
        assertThat(pool.getMinimumIdle()).isLessThan(pool.getMaximumPoolSize());
        assertThat(pool.getJdbcUrl()).startsWith("jdbc:h2:mem:scanner_test");
    }

    @Test
    @DisplayName("binds the allowlisted pool group and reads no spring.datasource key")
    void bindsTheAllowlistedPoolGroupAndReadsNoSpringDataSourceKey() {
        DataSourcePoolProperties pool = context.getBean(DataSourcePoolProperties.class);

        assertThat(pool.maximumSize()).isEqualTo(4);
        assertThat(pool.connectionTimeoutMillis()).isEqualTo(30_000L);
        assertThat(pool.leakDetectionThresholdMillis()).isZero();
        assertThat(context.getEnvironment().containsProperty("spring.datasource.url")).isFalse();
        assertThat(context.getEnvironment().containsProperty("spring.datasource.hikari.jdbc-url"))
                .isFalse();
    }

    @Test
    @DisplayName("declares a bounded graceful shutdown so the pool closes after the last request")
    void declaresABoundedGracefulShutdown() {
        assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(context.getEnvironment()
                .getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
    }

    /**
     * Authenticates the single configured principal and returns the token minted for it.
     *
     * @return the value of the {@code access_token} member of the token response
     */
    private String accessToken() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        String body = response.getContentAsString();
        assertThat(body).isNotEmpty();
        int start = body.indexOf("\"access_token\":\"") + "\"access_token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * Builds a request for one route, carrying a minimal JSON body for a method that takes one.
     *
     * @param method the request method
     * @param path   the route to address
     * @return the request, carrying no {@code Authorization} header
     */
    private static MockHttpServletRequestBuilder jsonRequest(HttpMethod method, String path) {
        MockHttpServletRequestBuilder request = request(method, path);
        if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }
}
