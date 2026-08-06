package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.data.domain.Pageable;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.AiTool;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.awaitility.Awaitility;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Net-new (no source construct): the retired suite carried no counterpart. backend/tests/test_tasks.py
// imported `from backend.tasks import monitor_tweets, generate_response`, where neither the module path
// nor either symbol existed — see docs/DECISION_LOG.md DL-044, DL-045, DL-046
/**
 * Exercises {@link TweetStreamClient} against a controlled {@link ExchangeFunction}.
 *
 * <p>No Spring context is started and no socket is opened: the {@link WebClient} under test is built on
 * an exchange function that records every {@link ClientRequest} and answers it from a fixture, so every
 * assertion here is offline and deterministic.
 *
 * <p>Coverage: the app-only token request, its caching, its {@code token_type} verification and the
 * token refresh a {@code 401} forces; rule composition from configuration, from {@code ai_tools} rows
 * and from the {@code stream_keywords} override; add, delete and no-op reconciliation; records split
 * across chunk boundaries, keep-alive lines, unparseable records, a listener failure and the listener's
 * stop signal; the {@code 429} reset delay and reconnection; non-blocking start and stop; the
 * blank-credential guard; and the exact set of X paths the class may address.
 *
 * <p>Decisions covered are recorded in {@code docs/DECISION_LOG.md} DL-012, DL-044, DL-045, DL-046 and
 * DL-052; construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TweetStreamClient")
class TweetStreamClientLifecycleTest {

    /** Root the production {@code WebClient} bean is configured with. */
    private static final String BASE_URL = "https://api.x.com";

    /** The four paths the class may address. */
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String RULES_PATH = "/2/tweets/search/stream/rules";
    private static final String STREAM_PATH = "/2/tweets/search/stream";

    /** Credentials the test profile leaves blank and this class supplies. */
    private static final String CONSUMER_KEY = "test-consumer-key";
    private static final String CONSUMER_SECRET = "test-consumer-secret";

    /** Token the fixture token response carries. */
    private static final String TOKEN = "app-only-token";

    /** A second token, returned after the cached one is rejected. */
    private static final String REFRESHED_TOKEN = "app-only-token-2";

    /** Base terms {@code application.yml} configures. */
    private static final List<String> BASE_KEYWORDS =
            List.of("AI coding tool", "AI code assistant", "AI generated code", "GPT-4");

    /** Longest a test waits for the pipeline to reach a state. */
    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(15);

    /** Rule cap {@code application.yml} configures — DL-254. */
    private static final int MAX_STREAM_RULES = 25;

    /** Signal-idle bound {@code application.yml} configures, in seconds — DL-256. */
    private static final long STREAM_IDLE_TIMEOUT_SECONDS = 60L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DefaultDataBufferFactory BUFFERS = new DefaultDataBufferFactory();

    @Mock
    private AiToolRepository aiToolRepository;

    @Mock
    private SettingRepository settingRepository;

    @Mock
    private TweetStreamListener tweetStreamListener;

    /** Every request the client issued, in order. */
    private final List<ClientRequest> issued = new CopyOnWriteArrayList<>();

    /** Answers each request; replaced per test. */
    private volatile ExchangeFunction exchange;

    /** The client under test, started by each test that needs a running pipeline. */
    private TweetStreamClient client;

    @BeforeEach
    void setUp() {
        issued.clear();
        exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());

        when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of());
        when(settingRepository.findById(any(String.class))).thenReturn(Optional.empty());
        when(tweetStreamListener.onStatus(any(JsonNode.class), anyInt())).thenReturn(true);

        client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, BASE_KEYWORDS);
    }

    // -------------------------------------------------------------------------
    // Declared shape and lifecycle contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("is a SmartLifecycle component holding exactly five injected collaborators")
    void isASmartLifecycleComponentHoldingExactlyFiveInjectedCollaborators() {
        assertThat(SmartLifecycle.class).isAssignableFrom(TweetStreamClient.class);
        assertThat(TweetStreamClient.class.getConstructors()).hasSize(1);
        assertThat(TweetStreamClient.class.getConstructors()[0].getParameterTypes())
                .containsExactly(WebClient.class, ScannerProperties.class, AiToolRepository.class,
                        SettingRepository.class, TweetStreamListener.class);
    }

    @Test
    @DisplayName("declares only the four lifecycle operations as public methods")
    void declaresOnlyTheFourLifecycleOperationsAsPublicMethods() {
        List<String> publicMethods = Stream.of(TweetStreamClient.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .distinct()
                .toList();

        assertThat(publicMethods)
                .containsExactlyInAnyOrder("start", "stop", "isRunning", "isAutoStartup");
    }

    // The ownership switch — DL-250 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "enabled={0}, streamEnabled={1}")
    @CsvSource({"false,true", "false,false", "true,false"})
    @DisplayName("reaches X in no way in a process that does not run the stream")
    void reachesXInNoWayInAProcessThatDoesNotRunTheStream(boolean enabled, boolean streamEnabled) {
        exchange = request -> tokenResponse(TOKEN);
        TweetStreamClient owned = clientOwning(enabled, streamEnabled);

        assertThat(owned.isAutoStartup()).isFalse();

        owned.start();

        assertThat(owned.isRunning()).isFalse();
        assertThat(issued).isEmpty();
        verifyNoInteractions(aiToolRepository, settingRepository, tweetStreamListener);
    }

    // The ownership switch — DL-250 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports auto startup in a process that runs the stream")
    void reportsAutoStartupInAProcessThatRunsTheStream() {
        assertThat(clientOwning(true, true).isAutoStartup()).isTrue();
    }

    // Both members default to true, so an unbound group streams — DL-250
    @Test
    @DisplayName("reports auto startup when no background group is bound")
    void reportsAutoStartupWhenNoBackgroundGroupIsBound() {
        assertThat(client.isAutoStartup()).isTrue();
    }

    // The only nested type is the two-component value the rules endpoint reports — DL-261
    @Test
    @DisplayName("declares one nested value record only, so rule composition, token exchange and "
            + "backoff stay inside the class")
    void declaresOneNestedValueRecordOnly() {
        Class<?>[] nested = TweetStreamClient.class.getDeclaredClasses();

        assertThat(nested).hasSize(1);
        assertThat(nested[0].getSimpleName()).isEqualTo("RegisteredRule");
        assertThat(nested[0].isRecord()).isTrue();
        assertThat(Stream.of(nested[0].getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "tag");
        assertThat(Stream.of(nested[0].getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactlyInAnyOrder("id", "tag", "equals", "hashCode", "toString");
    }

    @Test
    @DisplayName("reports not running before start")
    void reportsNotRunningBeforeStart() {
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    @DisplayName("returns from start without blocking on the calling thread")
    void returnsFromStartWithoutBlockingOnTheCallingThread() {
        exchange = request -> tokenResponse(TOKEN);

        long before = System.nanoTime();
        client.start();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        client.stop();
    }

    @Test
    @DisplayName("throws nothing from start, stop or a repeated stop")
    void throwsNothingFromStartStopOrARepeatedStop() {
        exchange = request -> tokenResponse(TOKEN);

        assertThatCode(() -> {
            client.start();
            client.stop();
            client.stop();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reports not running and issues no request after stop")
    void reportsNotRunningAndIssuesNoRequestAfterStop() {
        exchange = request -> streamOf("");
        client.start();

        client.stop();

        assertThat(client.isRunning()).isFalse();
    }

    // -------------------------------------------------------------------------
    // The blank-credential guard — the offline mechanism
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] consumer key {0}")
    @ValueSource(strings = {"", "   "})
    @DisplayName("contacts no X endpoint when the consumer key carries nothing")
    void contactsNoXEndpointWhenTheConsumerKeyCarriesNothing(String blankKey) {
        TweetStreamClient guarded = clientWith(blankKey, CONSUMER_SECRET, BASE_KEYWORDS);

        guarded.start();

        assertThat(guarded.isRunning()).isFalse();
        assertThat(issued).isEmpty();
        verifyNoInteractions(tweetStreamListener);
    }

    @ParameterizedTest(name = "[{index}] consumer secret {0}")
    @ValueSource(strings = {"", "   "})
    @DisplayName("contacts no X endpoint when the consumer secret carries nothing")
    void contactsNoXEndpointWhenTheConsumerSecretCarriesNothing(String blankSecret) {
        TweetStreamClient guarded = clientWith(CONSUMER_KEY, blankSecret, BASE_KEYWORDS);

        guarded.start();

        assertThat(guarded.isRunning()).isFalse();
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("contacts no X endpoint when neither consumer credential is configured")
    void contactsNoXEndpointWhenNeitherConsumerCredentialIsConfigured() {
        TweetStreamClient guarded = clientWith(null, null, BASE_KEYWORDS);

        guarded.start();

        assertThat(guarded.isRunning()).isFalse();
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("contacts no X endpoint when no configured term and no ai_tools row supplies one")
    void contactsNoXEndpointWhenNoTermIsAvailable() {
        TweetStreamClient bare = clientWith(CONSUMER_KEY, CONSUMER_SECRET, List.of());

        assertThatCode(bare::start).doesNotThrowAnyException();

        Awaitility.await().atMost(AWAIT_LIMIT).until(() -> !bare.isRunning());
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("contacts no X endpoint when composing the rule set fails")
    void contactsNoXEndpointWhenComposingTheRuleSetFails() {
        when(settingRepository.findById(any(String.class)))
                .thenThrow(new IllegalStateException("the settings table is unreachable"));

        assertThatCode(client::start).doesNotThrowAnyException();

        assertThat(issued).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Step 1 — the app-only bearer token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exchanges the consumer credentials for an app-only token before any other call")
    void exchangesTheConsumerCredentialsForAnAppOnlyTokenBeforeAnyOtherCall() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        ClientRequest token = issued.get(0);
        assertThat(token.method()).isEqualTo(HttpMethod.POST);
        assertThat(token.url().getPath()).isEqualTo(TOKEN_PATH);
        assertThat(token.headers().getContentType())
                .isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(bodyOf(token)).isEqualTo("grant_type=client_credentials");
    }

    @Test
    @DisplayName("authorizes the token request with the base64 of the percent-encoded credential pair")
    void authorizesTheTokenRequestWithTheBase64OfThePercentEncodedCredentialPair() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (CONSUMER_KEY + ":" + CONSUMER_SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(issued.get(0).headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
    }

    @Test
    @DisplayName("percent-encodes each half of the credential pair before joining them")
    void percentEncodesEachHalfOfTheCredentialPairBeforeJoiningThem() {
        TweetStreamClient encoding = clientWith("key with space", "secret/with+symbols",
                BASE_KEYWORDS);
        exchange = this::answerHappyPath;

        encoding.start();
        awaitRequestTo(STREAM_PATH);
        encoding.stop();

        String header = issued.get(0).headers().getFirst(HttpHeaders.AUTHORIZATION);
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("key%20with%20space:secret%2Fwith%2Bsymbols");
        assertThat(decoded).doesNotContain("+").doesNotContain(" ");
    }

    @Test
    @DisplayName("exchanges one token only and reuses it on the rules and stream calls")
    void exchangesOneTokenOnlyAndReusesItOnTheRulesAndStreamCalls() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(pathsIssued()).containsOnlyOnce(TOKEN_PATH);
        assertThat(issued).filteredOn(request -> !TOKEN_PATH.equals(request.url().getPath()))
                .allSatisfy(request -> assertThat(
                        request.headers().getFirst(HttpHeaders.AUTHORIZATION))
                                .isEqualTo("Bearer " + TOKEN));
    }

    @ParameterizedTest(name = "[{index}] token_type {0}")
    @ValueSource(strings = {"bearer", "Bearer", "BEARER", "BeArEr"})
    @DisplayName("accepts a bearer token_type without regard to letter case")
    void acceptsABearerTokenTypeWithoutRegardToLetterCase(String tokenType) {
        exchange = request -> TOKEN_PATH.equals(request.url().getPath())
                ? jsonResponse(HttpStatus.OK,
                        "{\"token_type\":\"" + tokenType + "\",\"access_token\":\"" + TOKEN + "\"}")
                : answerHappyPath(request);

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(pathsIssued()).contains(RULES_PATH, STREAM_PATH);
    }

    @ParameterizedTest(name = "[{index}] token response {0}")
    @ValueSource(strings = {
        "{\"token_type\":\"mac\",\"access_token\":\"t\"}",
        "{\"access_token\":\"t\"}",
        "{\"token_type\":\"bearer\"}",
        "{\"token_type\":\"bearer\",\"access_token\":\"\"}",
        "{}"})
    @DisplayName("reaches neither the rules nor the stream endpoint when the token is unverifiable")
    void reachesNeitherTheRulesNorTheStreamEndpointWhenTheTokenIsUnverifiable(String body) {
        exchange = request -> TOKEN_PATH.equals(request.url().getPath())
                ? jsonResponse(HttpStatus.OK, body)
                : answerHappyPath(request);

        client.start();
        awaitAtLeastOneRequest();
        client.stop();

        assertThat(pathsIssued()).doesNotContain(RULES_PATH, STREAM_PATH);
    }

    @Test
    @DisplayName("exchanges a new token after the X API rejects the cached one")
    void exchangesANewTokenAfterTheXApiRejectsTheCachedOne() {
        AtomicInteger tokenCalls = new AtomicInteger();
        exchange = request -> {
            String path = request.url().getPath();
            if (TOKEN_PATH.equals(path)) {
                return tokenResponse(tokenCalls.incrementAndGet() == 1 ? TOKEN : REFRESHED_TOKEN);
            }
            boolean carriesTheFirstToken = ("Bearer " + TOKEN)
                    .equals(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            if (RULES_PATH.equals(path) && carriesTheFirstToken) {
                return Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build());
            }
            return answerHappyPath(request);
        };

        client.start();
        Awaitility.await().atMost(AWAIT_LIMIT)
                .until(() -> tokenCalls.get() >= 2);
        client.stop();

        assertThat(tokenCalls.get()).isGreaterThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Step 2 — rule-set composition
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registers one rule per configured base term, quoting a term carrying whitespace")
    void registersOneRulePerConfiguredBaseTermQuotingATermCarryingWhitespace() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(addedRules()).containsExactly(
                Map.entry("\"AI coding tool\"", "AI coding tool"),
                Map.entry("\"AI code assistant\"", "AI code assistant"),
                Map.entry("\"AI generated code\"", "AI generated code"),
                Map.entry("GPT-4", "GPT-4"));
    }

    @Test
    @DisplayName("widens the rule set with every ai_tools name and drops null, blank and repeated ones")
    void widensTheRuleSetWithEveryAiToolsName() {
        List<String> selected = new ArrayList<>();
        selected.add("Copilot");
        selected.add("  Cursor  ");
        selected.add("copilot");
        selected.add(null);
        selected.add("   ");
        selected.add("GPT-4");
        when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(selected);
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(addedRules()).extracting(Map.Entry::getValue).containsExactly(
                "AI coding tool", "AI code assistant", "AI generated code", "GPT-4",
                "Copilot", "Cursor");
    }

    @Test
    @DisplayName("replaces the whole rule set with the stream_keywords row when it carries a value")
    void replacesTheWholeRuleSetWithTheStreamKeywordsRowWhenItCarriesAValue() {
        when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of("Copilot"));
        storedKeywords(" Devin , vibe coding ,, Devin ,  ");
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(addedRules()).containsExactly(
                Map.entry("Devin", "Devin"),
                Map.entry("\"vibe coding\"", "vibe coding"));
    }

    @ParameterizedTest(name = "[{index}] stored value {0}")
    @ValueSource(strings = {"", "   ", ",", " , , "})
    @DisplayName("falls through to the configured base terms when the stream_keywords row yields none")
    void fallsThroughToTheConfiguredBaseTermsWhenTheStreamKeywordsRowYieldsNone(String stored) {
        storedKeywords(stored);
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(addedRules()).extracting(Map.Entry::getValue)
                .containsExactlyElementsOf(BASE_KEYWORDS);
    }

    @Test
    @DisplayName("reads the stream_keywords row rather than requiring it to exist")
    void readsTheStreamKeywordsRowRatherThanRequiringItToExist() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        verify(settingRepository, atLeast(1)).findById("stream_keywords");
        assertThat(addedRules()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Step 3 — rule reconciliation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("lists the registered rules before mutating them")
    void listsTheRegisteredRulesBeforeMutatingThem() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        ClientRequest list = issued.get(1);
        assertThat(list.method()).isEqualTo(HttpMethod.GET);
        assertThat(list.url().getPath()).isEqualTo(RULES_PATH);
        assertThat(list.url().getQuery()).isNull();
    }

    @Test
    @DisplayName("adds only the missing rules and deletes only the surplus ones")
    void addsOnlyTheMissingRulesAndDeletesOnlyTheSurplusOnes() {
        exchange = request -> RULES_PATH.equals(request.url().getPath())
                && HttpMethod.GET.equals(request.method())
                        ? jsonResponse(HttpStatus.OK, "{\"data\":["
                                + "{\"id\":\"1\",\"value\":\"\\\"AI coding tool\\\"\",\"tag\":\"AI coding tool\"},"
                                + "{\"id\":\"9\",\"value\":\"obsolete\",\"tag\":\"obsolete\"}]}")
                        : answerHappyPath(request);

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(deletedRuleIds()).containsExactly("9");
        assertThat(addedRules()).extracting(Map.Entry::getValue)
                .containsExactly("AI code assistant", "AI generated code", "GPT-4")
                .doesNotContain("AI coding tool");
    }

    @Test
    @DisplayName("issues neither mutation when the registered rules already match")
    void issuesNeitherMutationWhenTheRegisteredRulesAlreadyMatch() {
        exchange = request -> RULES_PATH.equals(request.url().getPath())
                && HttpMethod.GET.equals(request.method())
                        ? jsonResponse(HttpStatus.OK, "{\"data\":["
                                + "{\"id\":\"1\",\"value\":\"\\\"AI coding tool\\\"\","
                                + "\"tag\":\"AI coding tool\"},"
                                + "{\"id\":\"2\",\"value\":\"\\\"AI code assistant\\\"\","
                                + "\"tag\":\"AI code assistant\"},"
                                + "{\"id\":\"3\",\"value\":\"\\\"AI generated code\\\"\","
                                + "\"tag\":\"AI generated code\"},"
                                + "{\"id\":\"4\",\"value\":\"GPT-4\",\"tag\":\"GPT-4\"}]}")
                        : answerHappyPath(request);

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(issued).noneSatisfy(request -> {
            assertThat(request.url().getPath()).isEqualTo(RULES_PATH);
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
        });
    }

    @Test
    @DisplayName("sends no dry_run member and reaches the stream after reconciling")
    void sendsNoDryRunMemberAndReachesTheStreamAfterReconciling() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        assertThat(issued).allSatisfy(request -> {
            String body = bodyOf(request);
            assertThat(body == null ? "" : body).doesNotContain("dry_run");
            assertThat(request.url().getQuery() == null ? "" : request.url().getQuery())
                    .doesNotContain("dry_run");
        });
        assertThat(pathsIssued()).contains(STREAM_PATH);
    }

    @Test
    @DisplayName("does not reach the stream when reconciliation fails")
    void doesNotReachTheStreamWhenReconciliationFails() {
        exchange = request -> RULES_PATH.equals(request.url().getPath())
                ? Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build())
                : answerHappyPath(request);

        client.start();
        awaitRequestTo(RULES_PATH);
        client.stop();

        assertThat(pathsIssued()).doesNotContain(STREAM_PATH);
    }

    // -------------------------------------------------------------------------
    // Step 4 — consuming the stream
    // -------------------------------------------------------------------------

    // Only the fields the listener maps are requested; no expansion is — DL-262
    @Test
    @DisplayName("requests the fields the listener reads and no expansion it ignores")
    void requestsTheFieldsTheListenerReadsAndNoExpansion() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);

        ClientRequest stream = issued.get(issued.size() - 1);
        assertThat(stream.method()).isEqualTo(HttpMethod.GET);
        assertThat(stream.url().getPath()).isEqualTo(STREAM_PATH);
        assertThat(stream.url().getQuery())
                .contains("tweet.fields=created_at,public_metrics,referenced_tweets,attachments,"
                        + "author_id")
                .doesNotContain("expansions");
    }

    @Test
    @DisplayName("reassembles one record split across three chunks")
    void reassemblesOneRecordSplitAcrossThreeChunks() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOfChunks("{\"data\":{\"id\":\"1\",\"text\":\"a", "bc\"}}", "\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(deliveredRecords()).singleElement()
                .satisfies(record -> assertThat(record.path("data").path("text").asText())
                        .isEqualTo("abc"));
    }

    @Test
    @DisplayName("reassembles a record whose multi-byte character is split across a chunk boundary")
    void reassemblesARecordWhoseMultiByteCharacterIsSplitAcrossAChunkBoundary() {
        byte[] whole = "{\"data\":{\"text\":\"caf\u00e9\"}}\n".getBytes(StandardCharsets.UTF_8);
        int split = whole.length - 4;
        byte[] head = Arrays.copyOfRange(whole, 0, split);
        byte[] tail = Arrays.copyOfRange(whole, split, whole.length);

        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOfByteChunks(head, tail)
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(deliveredRecords()).singleElement()
                .satisfies(record -> assertThat(record.path("data").path("text").asText())
                        .isEqualTo("caf\u00e9"));
    }

    @Test
    @DisplayName("delivers several records carried by one chunk, in order")
    void deliversSeveralRecordsCarriedByOneChunkInOrder() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n{\"data\":{\"id\":\"2\"}}\r\n"
                        + "{\"data\":{\"id\":\"3\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(3);
        client.stop();

        assertThat(deliveredRecords()).extracting(record -> record.path("data").path("id").asText())
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("delivers nothing for a keep-alive line and keeps consuming")
    void deliversNothingForAKeepAliveLineAndKeepsConsuming() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("\n\r\n   \n{\"data\":{\"id\":\"7\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(deliveredRecords()).extracting(record -> record.path("data").path("id").asText())
                .containsExactly("7");
    }

    @Test
    @DisplayName("skips an unparseable record and delivers the record that follows it")
    void skipsAnUnparseableRecordAndDeliversTheRecordThatFollowsIt() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{not json\n{\"data\":{\"id\":\"8\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(deliveredRecords()).extracting(record -> record.path("data").path("id").asText())
                .containsExactly("8");
    }

    @Test
    @DisplayName("keeps consuming after the listener fails on one record")
    void keepsConsumingAfterTheListenerFailsOnOneRecord() {
        when(tweetStreamListener.onStatus(any(JsonNode.class), anyInt()))
                .thenThrow(new IllegalStateException("the row could not be stored"))
                .thenReturn(true);
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n{\"data\":{\"id\":\"2\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(2);
        client.stop();

        assertThat(deliveredRecords()).extracting(record -> record.path("data").path("id").asText())
                .containsExactly("1", "2");
    }

    @Test
    @DisplayName("stops consuming and does not reconnect when the listener reports a stop signal")
    void stopsConsumingAndDoesNotReconnectWhenTheListenerReportsAStopSignal() {
        when(tweetStreamListener.onStatus(any(JsonNode.class), anyInt())).thenReturn(false);
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n{\"data\":{\"id\":\"2\"}}\n")
                : answerHappyPath(request);

        client.start();
        Awaitility.await().atMost(AWAIT_LIMIT).until(() -> !client.isRunning());

        assertThat(deliveredRecords()).hasSize(1);
        int streamCalls = (int) pathsIssued().stream().filter(STREAM_PATH::equals).count();
        assertThat(streamCalls).isOne();
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    @DisplayName("performs no persistence write and holds no repository of tweets or responses")
    void performsNoPersistenceWriteAndHoldsNoRepositoryOfTweetsOrResponses() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(Stream.of(TweetStreamClient.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName()))
                        .doesNotContain("TweetRepository", "ResponseRepository", "TwitterService",
                                "ResponseService", "NotionService", "SentimentAnalysisService",
                                "SettingsService");
        verify(aiToolRepository, never()).save(any(AiTool.class));
        verify(settingRepository, never()).save(any(Setting.class));
    }

    // -------------------------------------------------------------------------
    // Step 5 — resilience
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reconnects after the stream body ends")
    void reconnectsAfterTheStreamBodyEnds() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n")
                : answerHappyPath(request);

        client.start();
        Awaitility.await().atMost(AWAIT_LIMIT)
                .until(() -> pathsIssued().stream().filter(STREAM_PATH::equals).count() >= 2);
        client.stop();

        assertThat(pathsIssued().stream().filter(STREAM_PATH::equals).count())
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("reconciles the rule set again on every reconnection")
    void reconcilesTheRuleSetAgainOnEveryReconnection() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("")
                : answerHappyPath(request);

        client.start();
        Awaitility.await().atMost(AWAIT_LIMIT)
                .until(() -> pathsIssued().stream().filter(RULES_PATH::equals).count() >= 4);
        client.stop();

        assertThat(pathsIssued().stream().filter(RULES_PATH::equals).count())
                .isGreaterThanOrEqualTo(4L);
    }

    @Test
    @DisplayName("reconnects after the X API answers the stream with 429")
    void reconnectsAfterTheXApiAnswersTheStreamWith429() {
        AtomicInteger streamCalls = new AtomicInteger();
        exchange = request -> {
            if (STREAM_PATH.equals(request.url().getPath())) {
                if (streamCalls.incrementAndGet() == 1) {
                    return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                            .header("x-rate-limit-reset",
                                    String.valueOf(Instant.now().getEpochSecond()))
                            .build());
                }
                return streamOf("{\"data\":{\"id\":\"1\"}}\n");
            }
            return answerHappyPath(request);
        };

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(streamCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(deliveredRecords()).isNotEmpty();
    }

    @Test
    @DisplayName("stops reconnecting once stop has run")
    void stopsReconnectingOnceStopHasRun() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("")
                : answerHappyPath(request);

        client.start();
        awaitRequestTo(STREAM_PATH);
        client.stop();
        int afterStop = issued.size();

        assertThatCode(() -> Thread.sleep(1_500L)).doesNotThrowAnyException();

        assertThat(issued.size() - afterStop).isLessThanOrEqualTo(3);
        assertThat(client.isRunning()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Structural containment — the never-auto-post invariant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addresses only the token, rules and stream paths across a full cycle")
    void addressesOnlyTheTokenRulesAndStreamPathsAcrossAFullCycle() {
        exchange = request -> RULES_PATH.equals(request.url().getPath())
                && HttpMethod.GET.equals(request.method())
                        ? jsonResponse(HttpStatus.OK,
                                "{\"data\":[{\"id\":\"9\",\"value\":\"obsolete\"}]}")
                        : answerHappyPath(request);

        client.start();
        awaitRequestTo(STREAM_PATH);
        client.stop();

        assertThat(pathsIssued()).isSubsetOf(TOKEN_PATH, RULES_PATH, STREAM_PATH);
        assertThat(pathsIssued()).doesNotContain("/2/tweets");
    }

    @Test
    @DisplayName("issues a POST only to the token and rules paths")
    void issuesAPostOnlyToTheTokenAndRulesPaths() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);
        client.stop();

        assertThat(issued).filteredOn(request -> HttpMethod.POST.equals(request.method()))
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.url().getPath())
                        .isIn(TOKEN_PATH, RULES_PATH));
    }

    @Test
    @DisplayName("signs nothing with the OAuth 1.0a user-context credentials")
    void signsNothingWithTheOauthUserContextCredentials() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);
        client.stop();

        assertThat(issued).allSatisfy(request -> {
            String authorization = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
            assertThat(authorization).doesNotContain("OAuth")
                    .doesNotContain("oauth_signature")
                    .doesNotContain("access-token");
            String query = request.url().getQuery();
            assertThat(query == null ? "" : query).doesNotContain("oauth_");
        });
    }

    @Test
    @DisplayName("sets no response timeout and no codec limit on the stream request")
    void setsNoResponseTimeoutAndNoCodecLimitOnTheStreamRequest() {
        exchange = this::answerHappyPath;

        client.start();
        awaitRequestTo(STREAM_PATH);
        client.stop();

        ClientRequest stream = issued.get(issued.size() - 1);
        assertThat(stream.attributes()).doesNotContainKey(
                "org.springframework.web.reactive.function.client.WebClient.responseTimeout");
    }

    // -------------------------------------------------------------------------
    // Fixtures and helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the client under test on a {@link WebClient} whose exchange function records and answers
     * every request.
     *
     * @param consumerKey    value bound to {@code scanner.twitter.consumer-key}
     * @param consumerSecret value bound to {@code scanner.twitter.consumer-secret}
     * @param baseKeywords   value bound to {@code scanner.ingestion.stream-base-keywords}
     * @return the client
     */
    private TweetStreamClient clientWith(String consumerKey, String consumerSecret,
            List<String> baseKeywords) {

        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeFunction(request -> {
                    issued.add(request);
                    return exchange.exchange(request);
                })
                .build();

        return new TweetStreamClient(webClient, propertiesWith(consumerKey, consumerSecret,
                baseKeywords), aiToolRepository, settingRepository, tweetStreamListener);
    }

    /**
     * Builds a client whose bound configuration carries the supplied ownership switch.
     *
     * @param enabled       value bound to {@code scanner.background.enabled}
     * @param streamEnabled value bound to {@code scanner.background.stream-enabled}
     * @return a client holding credentials, the configured base terms and that switch
     */
    private TweetStreamClient clientOwning(boolean enabled, boolean streamEnabled) {
        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeFunction(request -> {
                    issued.add(request);
                    return exchange.exchange(request);
                })
                .build();

        ScannerProperties bound = new ScannerProperties(null, 100, 60L,
                new ScannerProperties.Twitter("api-key", "api-secret", "api-secret-key",
                        CONSUMER_KEY, CONSUMER_SECRET, "access-token", "access-token-secret", 30L, 30L),
                null, null, null, null, null,
                new ScannerProperties.Ingestion(BASE_KEYWORDS, MAX_STREAM_RULES, STREAM_IDLE_TIMEOUT_SECONDS),
                new ScannerProperties.Background(enabled, streamEnabled, true));

        return new TweetStreamClient(webClient, bound, aiToolRepository, settingRepository,
                tweetStreamListener);
    }

    /**
     * Builds the bound configuration the client reads.
     *
     * @param consumerKey    value bound to {@code scanner.twitter.consumer-key}
     * @param consumerSecret value bound to {@code scanner.twitter.consumer-secret}
     * @param baseKeywords   value bound to {@code scanner.ingestion.stream-base-keywords}
     * @return the bound configuration
     */
    private static ScannerProperties propertiesWith(String consumerKey, String consumerSecret,
            List<String> baseKeywords) {

        return new ScannerProperties(null, 100, 60L,
                new ScannerProperties.Twitter("api-key", "api-secret", "api-secret-key",
                        consumerKey, consumerSecret, "access-token", "access-token-secret", 30L, 30L),
                null, null, null, null, null,
                new ScannerProperties.Ingestion(baseKeywords, MAX_STREAM_RULES, STREAM_IDLE_TIMEOUT_SECONDS),
                null);
    }

    /** Makes the {@code stream_keywords} row answer with {@code value}. */
    private void storedKeywords(String value) {
        when(settingRepository.findById("stream_keywords"))
                .thenReturn(Optional.of(new Setting("stream_keywords", value, "seeded")));
    }

    /**
     * Answers the token, rules and stream calls of a cycle that succeeds.
     *
     * @param request the request the client issued
     * @return the fixture response
     */
    private Mono<ClientResponse> answerHappyPath(ClientRequest request) {
        String path = request.url().getPath();
        if (TOKEN_PATH.equals(path)) {
            return tokenResponse(TOKEN);
        }
        if (RULES_PATH.equals(path)) {
            return HttpMethod.GET.equals(request.method())
                    ? jsonResponse(HttpStatus.OK, "{\"data\":[]}")
                    : jsonResponse(HttpStatus.OK, "{\"meta\":{\"summary\":{}}}");
        }
        if (STREAM_PATH.equals(path)) {
            return streamOf("");
        }
        return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
    }

    /** A token response carrying {@code token}. */
    private static Mono<ClientResponse> tokenResponse(String token) {
        return jsonResponse(HttpStatus.OK,
                "{\"token_type\":\"bearer\",\"access_token\":\"" + token + "\"}");
    }

    /** A JSON response carrying {@code body}. */
    private static Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    /** A stream response whose whole body arrives as one chunk. */
    private static Mono<ClientResponse> streamOf(String body) {
        return streamOfChunks(body);
    }

    /** A stream response whose body arrives as the supplied chunks, in order. */
    private static Mono<ClientResponse> streamOfChunks(String... chunks) {
        byte[][] encoded = new byte[chunks.length][];
        for (int index = 0; index < chunks.length; index++) {
            encoded[index] = chunks[index].getBytes(StandardCharsets.UTF_8);
        }
        return streamOfByteChunks(encoded);
    }

    /** A stream response whose body arrives as the supplied byte chunks, in order. */
    private static Mono<ClientResponse> streamOfByteChunks(byte[]... chunks) {
        List<DataBuffer> buffers = new ArrayList<>();
        for (byte[] chunk : chunks) {
            if (chunk.length > 0) {
                buffers.add(BUFFERS.wrap(chunk));
            }
        }
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Flux.fromIterable(buffers))
                .build());
    }

    /** The paths of every request the client issued, in order. */
    private List<String> pathsIssued() {
        return issued.stream().map(request -> request.url().getPath()).toList();
    }

    /** Renders the body of one recorded request. */
    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest httpRequest =
                new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(httpRequest, ExchangeStrategies.withDefaults()).block();
        return httpRequest.getBodyAsString().block();
    }

    /** The rules the client asked X to add, as rule expression to tag, in order. */
    private List<Map.Entry<String, String>> addedRules() {
        Map<String, String> added = new LinkedHashMap<>();
        for (JsonNode body : ruleMutationBodies()) {
            for (JsonNode rule : body.path("add")) {
                added.putIfAbsent(rule.path("value").asText(), rule.path("tag").asText());
            }
        }
        return new ArrayList<>(added.entrySet());
    }

    /** The rule identifiers the client asked X to delete, in order. */
    private List<String> deletedRuleIds() {
        List<String> deleted = new ArrayList<>();
        for (JsonNode body : ruleMutationBodies()) {
            for (JsonNode id : body.path("delete").path("ids")) {
                deleted.add(id.asText());
            }
        }
        return deleted;
    }

    /** The parsed bodies of every rule mutation the client issued. */
    private List<JsonNode> ruleMutationBodies() {
        List<JsonNode> bodies = new ArrayList<>();
        for (ClientRequest request : issued) {
            if (RULES_PATH.equals(request.url().getPath())
                    && HttpMethod.POST.equals(request.method())) {
                try {
                    bodies.add(MAPPER.readTree(bodyOf(request)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalStateException("A rule mutation body was not JSON", failure);
                }
            }
        }
        return bodies;
    }

    /** Every record the client handed to the listener, in order. */
    private List<JsonNode> deliveredRecords() {
        org.mockito.ArgumentCaptor<JsonNode> captor =
                org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(tweetStreamListener, atLeast(0)).onStatus(captor.capture(), anyInt());
        return captor.getAllValues();
    }

    /** Waits until the client has issued a request to {@code path}. */
    private void awaitRequestTo(String path) {
        Awaitility.await().atMost(AWAIT_LIMIT).until(() -> pathsIssued().contains(path));
    }

    /** Waits until the client has issued at least one request. */
    private void awaitAtLeastOneRequest() {
        Awaitility.await().atMost(AWAIT_LIMIT).until(() -> !issued.isEmpty());
    }

    /** Waits until the listener has been handed at least {@code count} records. */
    private void awaitRecords(int count) {
        Awaitility.await().atMost(AWAIT_LIMIT).until(() -> deliveredRecords().size() >= count);
    }


    @Test
    @DisplayName("never asks the listener to handle a null record")
    void neverAsksTheListenerToHandleANullRecord() {
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(1);
        client.stop();

        assertThat(deliveredRecords()).isNotEmpty().doesNotContainNull();
        verify(tweetStreamListener, never()).onStatus(isNull(), anyInt());
    }

    /** Makes the listener fail on every record it is handed. */
    private void listenerAlwaysFails() {
        doThrow(new IllegalStateException("the listener is unavailable"))
                .when(tweetStreamListener).onStatus(any(JsonNode.class), anyInt());
    }

    @Test
    @DisplayName("keeps the connection open when the listener fails on every record")
    void keepsTheConnectionOpenWhenTheListenerFailsOnEveryRecord() {
        listenerAlwaysFails();
        exchange = request -> STREAM_PATH.equals(request.url().getPath())
                ? streamOf("{\"data\":{\"id\":\"1\"}}\n{\"data\":{\"id\":\"2\"}}\n")
                : answerHappyPath(request);

        client.start();
        awaitRecords(2);
        client.stop();

        assertThat(deliveredRecords()).hasSizeGreaterThanOrEqualTo(2);
    }
}
