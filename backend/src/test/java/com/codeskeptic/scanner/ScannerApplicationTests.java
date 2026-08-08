package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;
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
import com.codeskeptic.scanner.config.DataSourceConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.AiTool;
import com.codeskeptic.scanner.entity.Setting;
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
import reactor.core.publisher.Mono;

// Replaces backend/tests/test_api.py, which imported fastapi.testclient against a Flask application
// at :L2 and could not be executed — see docs/DECISION_LOG.md DL-239
/**
 * Proves the whole application context assembles and that the route surface is the one the retired
 * blueprints served.
 *
 * <p>This is the widest test in the suite: it starts every bean the application declares under the
 * {@code test} profile, and nothing is mocked or stubbed out. The web environment is
 * {@link SpringBootTest.WebEnvironment#MOCK} and requests are issued through {@link MockMvc}, so the
 * real {@code SecurityFilterChain}, the real dispatcher and the real controllers all run without a
 * connector, a port or a server thread — DL-274.
 *
 * <p>The {@code test} profile supplies an in-memory database, a JWT secret, one build-only
 * {@code scanner.auth} principal, and blank X consumer credentials, so ingestion contacts nothing. No
 * test here resolves an OpenAI, Notion or Google credential: {@code service.LlmService},
 * {@code service.NotionService} and {@code service.SentimentAnalysisService} each create their client
 * on first use, and no request made here reaches one.
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
    private static final String PASSWORD = "test-password";

    /** Key of the seeded settings row that sets the response-generation interval — DL-227, DL-309. */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

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

        long freshGeneration = generation.incrementAndGet();
        running.set(true);

        assertThat(ingestionCycle.invoke(client, staleGeneration)).isInstanceOf(Mono.class);
        Mono<?> staleCycle = (Mono<?>) ingestionCycle.invoke(client, staleGeneration);
        staleCycle.subscribe().dispose();
        assertThat(running.get())
                .as("running state of the fresh cycle after the stale cycle terminated").isTrue();

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
        for (int index = 0; index < ruleCap; index++) {
            seeded.add(aiToolNamed(switch (index % 4) {
                case 0 -> "   ";
                case 1 -> "-leading" + index;
                case 2 -> "trailing" + index + "-";
                default -> "hash#" + index;
            }));
        }
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

        // The one task is registered against a trigger, so the interval is recomputed once per pass
        // and the settings row can set it — DL-227, DL-228, DL-309 — see docs/DECISION_LOG.md
        ScheduledTask pass = holder.getScheduledTasks().iterator().next();
        assertThat(pass.getTask()).as("the registered task")
                .isInstanceOf(TriggerTask.class)
                .isNotInstanceOf(FixedDelayTask.class)
                .isNotInstanceOf(FixedRateTask.class)
                .isNotInstanceOf(CronTask.class);

        // The interval in force under the test profile is a day, seeded onto the row from
        // scanner.response-generation-delay-seconds, and no second pass falls inside the suite window.
        assertThat(context.getBean(ScannerProperties.class).responseGenerationDelaySeconds())
                .isEqualTo(86_400L);
        assertThat(context.getBean(ResponseGenerationScheduler.class)
                .responseGenerationDelaySecondsInForce())
                .as("the interval in force")
                .isEqualTo(86_400L);

        Instant nextExecution = pass.nextExecution();
        assertThat(nextExecution).isNotNull();
        assertThat(nextExecution).isBefore(Instant.now().plus(Duration.ofHours(25)));
    }

    // IR10 and the work-then-sleep order of backend/app/tasks/response_generation.py:L41-50: the
    // first pass runs at the startup instant and each later pass is spaced from the previous
    // completion by the interval in force — DL-047, DL-309 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("runs the first pass at the current instant and spaces each later pass from the "
            + "previous completion by the interval the settings row sets")
    void spacesEachPassFromThePreviousCompletionByTheIntervalInForce() {
        Trigger trigger = registeredResponseGenerationTrigger();
        Instant now = Instant.parse("2026-04-05T06:07:08Z");
        Instant completion = now.minusSeconds(30L);

        assertThat(trigger.nextExecution(triggerContext(now, null)))
                .as("first pass, with no completion to measure from")
                .isEqualTo(now);
        assertThat(trigger.nextExecution(triggerContext(now, completion)))
                .as("a later pass, spaced from the previous completion by the day-long interval")
                .isEqualTo(completion.plus(Duration.ofSeconds(86_400L)));

        // The row sets the interval: an edit is picked up by the next computation — DL-227, DL-309
        SettingRepository settings = context.getBean(SettingRepository.class);
        Setting stored = settings.findById(RESPONSE_GENERATION_DELAY_KEY).orElseThrow();
        String seeded = stored.getValue();
        try {
            stored.setValue("11");
            settings.saveAndFlush(stored);

            assertThat(trigger.nextExecution(triggerContext(now, completion)))
                    .as("a later pass, once the row holds eleven seconds")
                    .isEqualTo(completion.plus(Duration.ofSeconds(11L)));
        } finally {
            stored.setValue(seeded);
            settings.saveAndFlush(stored);
        }

        assertThat(trigger.nextExecution(triggerContext(now, completion)))
                .as("a later pass, once the row holds the seeded value again")
                .isEqualTo(completion.plus(Duration.ofSeconds(86_400L)));
    }

    /**
     * Resolves the trigger the scheduling registrar received for the response-generation pass.
     *
     * @return the registered trigger; never {@code null}
     */
    private Trigger registeredResponseGenerationTrigger() {
        ScheduledTaskHolder holder =
                context.getBeansOfType(ScheduledTaskHolder.class).values().iterator().next();
        ScheduledTask pass = holder.getScheduledTasks().iterator().next();
        assertThat(pass.getTask()).isInstanceOf(TriggerTask.class);
        Trigger trigger = ((TriggerTask) pass.getTask()).getTrigger();
        assertThat(trigger).isNotNull();
        return trigger;
    }

    /**
     * Builds a trigger context reporting a fixed clock and a chosen previous completion.
     *
     * @param now            the instant the context's clock reports
     * @param lastCompletion the previous pass's completion, or {@code null} before the first pass
     * @return the context; never {@code null}
     */
    private static TriggerContext triggerContext(Instant now, Instant lastCompletion) {
        return new TriggerContext() {
            @Override
            public Clock getClock() {
                return Clock.fixed(now, ZoneOffset.UTC);
            }

            @Override
            public Instant lastScheduledExecution() {
                return lastCompletion;
            }

            @Override
            public Instant lastActualExecution() {
                return lastCompletion;
            }

            @Override
            public Instant lastCompletion() {
                return lastCompletion;
            }
        };
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

    private static MockHttpServletRequestBuilder jsonRequest(HttpMethod method, String path) {
        MockHttpServletRequestBuilder request = request(method, path);
        if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }

    // ---------------------------------------------------------------------------------------------
    // Startup diagnostic for a failure of the JDBC and dialect chain. Registered on the
    // SpringApplication by main() rather than through a META-INF/spring.factories resource, because a
    // database failure happens before the context becomes active — DL-305 — see docs/DECISION_LOG.md
    // ---------------------------------------------------------------------------------------------

    /** Message Hibernate raises when it cannot read a dialect from the connection metadata. */
    private static final String DIALECT_FAILURE_MESSAGE =
            "Unable to determine Dialect without JDBC metadata (please set 'jakarta.persistence.jdbc.url')";

    @Test
    @DisplayName("turns a driver failure into a diagnostic naming the configuration key, carrying "
            + "neither the jdbc url nor a credential")
    void turnsADriverFailureIntoADiagnosticNamingTheConfigurationKey() {
        SQLException driverFailure = new SQLException(
                "Connection to db.internal:5432 refused for jdbc:postgresql://scanner:s3cret@db.internal:5432/codeskeptic",
                "08001");
        Throwable failure = new IllegalStateException("Failed to initialize pool", driverFailure);

        FailureAnalysis analysis =
                new DataSourceConfig.DatabaseStartupFailureAnalyzer().analyze(failure);

        assertThat(analysis).as("the analysis for a driver failure").isNotNull();
        assertThat(analysis.getAction())
                .contains("DATABASE_URL")
                .contains("scanner.database-url");
        assertThat(analysis.getDescription()).contains("08001");
        assertThat(analysis.getDescription())
                .as("no jdbc url and no credential may reach the diagnostic — DL-052")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("s3cret");
        assertThat(analysis.getCause()).isSameAs(failure);
    }

    @Test
    @DisplayName("turns a dialect-determination failure into the same diagnostic")
    void turnsADialectDeterminationFailureIntoTheSameDiagnostic() {
        Throwable failure = new IllegalStateException("Error creating bean with name 'entityManagerFactory'",
                new RuntimeException(DIALECT_FAILURE_MESSAGE));

        FailureAnalysis analysis =
                new DataSourceConfig.DatabaseStartupFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction()).contains("DATABASE_URL");
    }

    @Test
    @DisplayName("recognises no other failure, so the framework's own report stands alone")
    void recognisesNoOtherFailure() {
        DataSourceConfig.DatabaseStartupFailureAnalyzer diagnostic =
                new DataSourceConfig.DatabaseStartupFailureAnalyzer();

        assertThat(diagnostic.analyze(new IllegalStateException("Web server failed to start. Port 5000 was already in use.")))
                .isNull();
        assertThat(diagnostic.analyze(null)).isNull();
    }

    @Test
    @DisplayName("is an application listener for the failure event, which is the path a database "
            + "failure takes before any context is active")
    void isAnApplicationListenerForTheFailureEvent() {
        DataSourceConfig.DatabaseStartupFailureAnalyzer diagnostic =
                new DataSourceConfig.DatabaseStartupFailureAnalyzer();

        assertThat(diagnostic).isInstanceOf(ApplicationListener.class);

        // Both shapes are safe to hand to the listener: the recognised one reports, the unrecognised
        // one reports nothing, and neither throws out of the framework's multicast.
        SpringApplication application = new SpringApplication(ScannerApplication.class);
        assertThatCode(() -> {
            diagnostic.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null,
                    new IllegalStateException("boot", new SQLException("refused", "08001"))));
            diagnostic.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null,
                    new IllegalStateException("unrelated")));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is reached without a META-INF/spring.factories resource: the module's source tree "
            + "carries exactly two resources, application.yml and application-test.yml")
    void isReachedWithoutASpringFactoriesResource() throws Exception {
        // The module root is derived from this module's own compiled output — target/classes — rather
        // than from the working directory, so the assertion does not depend on where the runner starts.
        Path moduleRoot = Path.of(ScannerApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getParent().getParent();
        assertThat(moduleRoot.resolve("pom.xml")).as("the module root").isRegularFile();

        List<String> resources = new ArrayList<>();
        for (String tree : List.of("src/main/resources", "src/test/resources")) {
            Path root = moduleRoot.resolve(tree);
            assertThat(root).as(tree).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .map(file -> tree + "/"
                                + root.relativize(file).toString().replace(File.separatorChar, '/'))
                        .sorted()
                        .forEach(resources::add);
            }
        }

        assertThat(resources)
                .as("the accepted resource inventory of this module is exactly two files, and it "
                        + "carries no META-INF resource of its own — DL-305")
                .containsExactly("src/main/resources/application.yml",
                        "src/test/resources/application-test.yml");
    }

}
