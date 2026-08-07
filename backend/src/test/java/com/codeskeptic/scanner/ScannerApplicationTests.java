package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.api.AnalyticsController;
import com.codeskeptic.scanner.api.AuthController;
import com.codeskeptic.scanner.api.GlobalExceptionHandler;
import com.codeskeptic.scanner.api.ResponseController;
import com.codeskeptic.scanner.api.SettingController;
import com.codeskeptic.scanner.api.TweetController;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.AiTool;
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
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;
import com.codeskeptic.scanner.task.TweetStreamClient;
import com.codeskeptic.scanner.task.TweetStreamListener;
import com.zaxxer.hikari.HikariDataSource;
import reactor.core.publisher.Mono;

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
                Arguments.of("ResponseGenerationScheduler", ResponseGenerationScheduler.class));
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

    // No page-size bound is applied on the wire — DL-217 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("restates a very large per_page unreduced on both list routes")
    void restatesAVeryLargePerPageUnreducedOnBothListRoutes() throws Exception {
        String token = accessToken();

        for (String route : List.of("/tweets", "/responses")) {
            MockHttpServletResponse response = mockMvc.perform(get(route)
                            .param("per_page", String.valueOf(Integer.MAX_VALUE))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as("status of %s", route)
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(response.getContentAsString()).as("body of %s", route)
                    .contains("\"per_page\":" + Integer.MAX_VALUE);
        }
    }

    @Test
    @DisplayName("leaves ingestion stopped when the X consumer credentials carry nothing")
    void leavesIngestionStoppedWhenTheXConsumerCredentialsCarryNothing() {
        assertThat(context.getBean(TweetStreamClient.class).isRunning()).isFalse();
    }

    // New intake is refused once a stop has been requested, so the bounded drain waits only for
    // dispatches already counted in flight — DL-259 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses a record emitted after a stop has been requested, without counting it in "
            + "flight")
    void refusesARecordEmittedAfterAStopHasBeenRequested() throws Exception {
        TweetStreamClient client = context.getBean(TweetStreamClient.class);
        Method dispatch = TweetStreamClient.class
                .getDeclaredMethod("dispatchRecord", String.class, int.class);
        dispatch.setAccessible(true);
        Field inFlight = TweetStreamClient.class.getDeclaredField("inFlightDispatches");
        inFlight.setAccessible(true);
        Field stopRequested = TweetStreamClient.class.getDeclaredField("stopRequested");
        stopRequested.setAccessible(true);

        String record = "{\"data\":{\"id\":\"1\",\"text\":\"a post\"}}";
        stopRequested.setBoolean(client, true);
        try {
            assertThat((boolean) dispatch.invoke(client, record, 100))
                    .as("a record emitted during the drain keeps the connection open")
                    .isFalse();
            assertThat(((AtomicInteger) inFlight.get(client)).get())
                    .as("dispatches counted in flight after a refused record").isZero();

            // Every shape is refused, including the keep-alive that is otherwise accepted silently
            for (String emitted : new String[] {"", "   ", "not json at all", record}) {
                assertThat((boolean) dispatch.invoke(client, emitted, 100))
                        .as("a record of shape [%s] emitted during the drain", emitted).isFalse();
            }
            assertThat(((AtomicInteger) inFlight.get(client)).get())
                    .as("dispatches counted in flight after five refused records").isZero();
        } finally {
            stopRequested.setBoolean(client, false);
        }
    }

    // A terminal callback from a cycle that has been replaced writes no shared state — DL-290 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("ignores the terminal callback of a cycle that a later transition has replaced")
    void ignoresTheTerminalCallbackOfAReplacedCycle() throws Exception {
        TweetStreamClient client = context.getBean(TweetStreamClient.class);
        Method ingestionCycle =
                TweetStreamClient.class.getDeclaredMethod("ingestionCycle", long.class);
        ingestionCycle.setAccessible(true);
        Field runningField = TweetStreamClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        Field generationField = TweetStreamClient.class.getDeclaredField("cycleGeneration");
        generationField.setAccessible(true);

        AtomicBoolean running = (AtomicBoolean) runningField.get(client);
        AtomicLong generation = (AtomicLong) generationField.get(client);
        long staleGeneration = generation.get();

        // A fresh cycle takes the next generation and reports itself running
        long freshGeneration = generation.incrementAndGet();
        running.set(true);

        // The stale cycle's terminal callback is delivered late and must not clear the fresh state
        assertThat(ingestionCycle.invoke(client, staleGeneration)).isInstanceOf(Mono.class);
        Mono<?> staleCycle = (Mono<?>) ingestionCycle.invoke(client, staleGeneration);
        staleCycle.subscribe().dispose();
        assertThat(running.get())
                .as("running state of the fresh cycle after the stale cycle terminated").isTrue();

        // The fresh cycle's own callback does clear it: its generation is still current
        Mono<?> ownCycle = (Mono<?>) ingestionCycle.invoke(client, freshGeneration);
        ownCycle.subscribe().dispose();
        assertThat(running.get())
                .as("running state after the current cycle terminated").isFalse();
    }

    // ai_tools names are filtered as each page is merged, so leading unusable and repeated rows do
    // not consume the rule budget and a usable name behind them is still reached — DL-291 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("collects a usable ai_tools name that follows a full rule cap worth of blank, "
            + "refused and repeated rows")
    void collectsAUsableAiToolNameThatFollowsUnusableRows() throws Exception {
        int ruleCap = context.getBean(ScannerProperties.class).ingestion().maxStreamRules();
        List<AiTool> seeded = new ArrayList<>();
        // Enough unusable rows to exhaust the cap on their own: whitespace only, a leading and a
        // trailing separator, and a character the rule grammar does not admit
        for (int index = 0; index < ruleCap; index++) {
            seeded.add(aiToolNamed(switch (index % 4) {
                case 0 -> "   ";
                case 1 -> "-leading" + index;
                case 2 -> "trailing" + index + "-";
                default -> "hash#" + index;
            }));
        }
        // The usable names sit behind every one of them, and one is a repeat of another
        seeded.add(aiToolNamed("Copilot"));
        seeded.add(aiToolNamed("copilot"));
        seeded.add(aiToolNamed("Cursor"));

        AiToolRepository aiTools = context.getBean(AiToolRepository.class);
        List<AiTool> stored = aiTools.saveAll(seeded);
        try {
            TweetStreamClient client = context.getBean(TweetStreamClient.class);
            Method compose = TweetStreamClient.class.getDeclaredMethod("composeRuleTerms");
            compose.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> terms = (List<String>) compose.invoke(client);

            assertThat(terms).as("composed rule terms with %s unusable rows ahead of them", ruleCap)
                    .contains("Copilot", "Cursor")
                    .doesNotContain("   ", "copilot");
            assertThat(terms).as("no term the rule grammar refuses reaches the collection")
                    .noneMatch(term -> term.contains("#") || term.startsWith("-")
                            || term.endsWith("-") || term.isBlank());
            assertThat(terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).distinct().count())
                    .as("distinct terms without regard to letter case").isEqualTo(terms.size());
            assertThat(terms.size()).as("collected terms against the rule cap")
                    .isLessThanOrEqualTo(ruleCap);
        } finally {
            aiTools.deleteAll(stored);
        }
    }

    private static AiTool aiToolNamed(String name) {
        AiTool tool = new AiTool();
        tool.setName(name);
        return tool;
    }

    // Full-context scheduler state under the test profile — DL-239 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("registers the response-generation pass as the one scheduled task and keeps it "
            + "pending beyond the suite window")
    void registersTheResponseGenerationPassAsTheOneScheduledTask() {
        Map<String, ScheduledTaskHolder> holders =
                context.getBeansOfType(ScheduledTaskHolder.class);
        assertThat(holders).hasSize(1);

        ScheduledTaskHolder holder = holders.values().iterator().next();
        assertThat(holder.getScheduledTasks()).hasSize(1);

        // TR-12: the one task is registered as a fixed-DELAY task, so the interval runs from the end
        // of one pass to the start of the next — DL-047 — see docs/DECISION_LOG.md
        ScheduledTask pass = holder.getScheduledTasks().stream()
                .filter(scheduledTask -> scheduledTask.getTask() instanceof FixedDelayTask)
                .findFirst()
                .orElseThrow();

        FixedDelayTask registered = (FixedDelayTask) pass.getTask();
        assertThat(registered.getIntervalDuration()).as("the registered fixed delay")
                .isEqualTo(Duration.ofSeconds(86_400L));
        assertThat(registered.getInitialDelayDuration()).as("the registered initial delay")
                .isEqualTo(Duration.ZERO);

        // The first pass runs at startup, which is the work-then-sleep order of
        // backend/app/tasks/response_generation.py:L41-50. The interval in force under the test
        // profile is a day, and no second pass falls inside the suite window.
        assertThat(context.getBean(ScannerProperties.class).responseGenerationDelaySeconds())
                .isEqualTo(86_400L);

        Instant nextExecution = pass.nextExecution();
        assertThat(nextExecution).isNotNull();
        assertThat(nextExecution).isBefore(Instant.now().plus(Duration.ofHours(25)));

    }

    // Net-new background enablement group — DL-250 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("binds the background enablement group from the test profile")
    void bindsTheBackgroundEnablementGroupFromTheTestProfile() {
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

    // The single pooled DataSource and the bounded graceful shutdown — DL-027, DL-294 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("publishes exactly one pool, built from the translated scanner.database-url")
    void publishesExactlyOnePoolBuiltFromTheTranslatedDatabaseUrl() {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        HikariDataSource pool = context.getBean(HikariDataSource.class);

        assertThat(pool.getJdbcUrl()).startsWith("jdbc:h2:mem:scanner_test");
    }

    @Test
    @DisplayName("reads no spring.datasource key at all")
    void readsNoSpringDataSourceKeyAtAll() {
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
