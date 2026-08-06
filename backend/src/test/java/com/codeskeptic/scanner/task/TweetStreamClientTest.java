package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Pageable;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

// Supersedes backend/tests/test_tasks.py, whose `from backend.tasks import monitor_tweets` at :L3
// named a module and symbols that never existed — DL-214, DL-216 — see docs/DECISION_LOG.md
/**
 * Verifies {@link TweetStreamClient} against a controlled {@link ExchangeFunction}. No test reaches a
 * network.
 *
 * <p>Every exchange is recorded, and the recording carries the structural claims: the only X paths
 * reached are {@value #TOKEN_PATH}, {@value #RULES_PATH} and {@value #STREAM_PATH}; {@code POST}
 * reaches the token and rules paths alone; and no publish, reply or retweet path is reached — DL-046,
 * DL-214.
 *
 * <p>The class under test replaces {@code start_tweet_stream()} at
 * {@code backend/app/tasks/tweet_monitoring.py:L36-55} and the {@code pass} stub
 * {@code stream_tweets} at {@code backend/app/services/twitter_service.py:L16-23}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TweetStreamClient")
class TweetStreamClientTest {

    /** Rule cap {@code application.yml} configures — DL-254. */
    private static final int MAX_STREAM_RULES = 25;

    /** Signal-idle bound {@code application.yml} configures, in seconds — DL-256. */
    private static final long STREAM_IDLE_TIMEOUT_SECONDS = 60L;

    /** Host root the transport is rooted at, matching {@code config.WebClientConfig}. */
    private static final String BASE_URL = "https://api.x.com";

    /** Path of the app-only token exchange. */
    private static final String TOKEN_PATH = "/oauth2/token";

    /** Path of the filtered-stream rule collection. */
    private static final String RULES_PATH = "/2/tweets/search/stream/rules";

    /** Path of the filtered stream. */
    private static final String STREAM_PATH = "/2/tweets/search/stream";

    /** Primary key of the row that overrides the composed rule set. */
    private static final String STREAM_KEYWORDS_KEY = "stream_keywords";

    /** Configured consumer key supplied to the client. */
    private static final String CONSUMER_KEY = "consumer-key-value";

    /** Configured consumer secret supplied to the client. */
    private static final String CONSUMER_SECRET = "consumer-secret-value";

    /** Token the canned exchange reports. */
    private static final String TOKEN = "app-only-bearer-token";

    /** Second token the canned exchange reports after a 401. */
    private static final String REFRESHED_TOKEN = "second-app-only-bearer-token";

    /** Base terms configured through {@code scanner.ingestion.stream-base-keywords}. */
    private static final List<String> BASE_TERMS =
            List.of("AI coding tool", "AI code assistant", "AI generated code", "GPT-4");

    /** Longest a bounded wait allows. */
    private static final Duration WAIT = Duration.ofSeconds(20);

    /** Bytes carried by a record that passes the accumulator bound of {@code MAX_RECORD_BYTES}. */
    private static final int OVER_THE_RECORD_BOUND = 1_100_000;

    /** Shortest reconnection delay the client applies. */
    private static final Duration MINIMUM_BACKOFF = Duration.ofSeconds(5);

    /** Characters {@code util.LogSafe.logSafe(String)} carries before it truncates — DL-197. */
    private static final int GUARDED_VALUE_LIMIT = 64;

    /**
     * A failure message carrying a record boundary, a forged level and an unbounded tail — the shape
     * a remote peer, a proxy or a TLS stack can contribute to a transport failure.
     */
    private static final String HOSTILE_FAILURE_MESSAGE =
            "connection reset\r\nWARN forged record " + "x".repeat(200);

    /**
     * A rules-mutation answer refusing one rule, whose reflected {@code value} carries a record
     * boundary and a forged level.
     */
    private static final String REFUSAL_BODY =
            "{\"errors\":[{\"value\":\"smuggled term\\r\\nWARN forged record\","
                    + "\"title\":\"DuplicateRule\"}],"
                    + "\"meta\":{\"summary\":{\"created\":0,\"not_created\":1,\"valid\":0,"
                    + "\"invalid\":1}}}";

    /**
     * Bound every test but the two of {@code the control-plane bound} gives the token exchange and the
     * rules calls, long enough not to interfere — DL-230.
     */
    private static final long CONTROL_PLANE_TIMEOUT_SECONDS = 30L;

    /** Data access for {@code ai_tools}. */
    @Mock
    private AiToolRepository aiToolRepository;

    /** Data access for {@code settings}. */
    @Mock
    private SettingRepository settingRepository;

    /** Recipient of every delivered record. */
    @Mock
    private TweetStreamListener tweetStreamListener;

    /** Every exchange the client issued, in order. */
    private final List<RecordedExchange> exchanges = new CopyOnWriteArrayList<>();

    /** Instance under test, assembled per test. */
    private TweetStreamClient client;

    @BeforeEach
    void stubTheDefaultRuleSources() {
        when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of());
        when(settingRepository.findById(STREAM_KEYWORDS_KEY)).thenReturn(Optional.empty());
        when(tweetStreamListener.onStatus(any(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void stopTheClient() {
        if (client != null) {
            client.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Clean-checkout guard — backend/src/test/resources/application-test.yml
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the blank-credential guard")
    class BlankCredentialGuard {

        @ParameterizedTest(name = "consumer key [{0}]")
        @NullSource
        @ValueSource(strings = {"", " ", "\t", "\n"})
        @DisplayName("contacts no X endpoint when the consumer key carries nothing")
        void contactsNoXEndpointWhenTheConsumerKeyCarriesNothing(String consumerKey) {
            client = clientWith(consumerKey, CONSUMER_SECRET, request -> {
                throw new AssertionError("No X endpoint may be contacted.");
            });

            client.start();

            assertThat(client.isRunning()).isFalse();
            assertThat(exchanges).isEmpty();
        }

        @ParameterizedTest(name = "consumer secret [{0}]")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("contacts no X endpoint when the consumer secret carries nothing")
        void contactsNoXEndpointWhenTheConsumerSecretCarriesNothing(String consumerSecret) {
            client = clientWith(CONSUMER_KEY, consumerSecret, request -> {
                throw new AssertionError("No X endpoint may be contacted.");
            });

            client.start();

            assertThat(client.isRunning()).isFalse();
            assertThat(exchanges).isEmpty();
        }

        @Test
        @DisplayName("reads no rule source when the credentials carry nothing")
        void readsNoRuleSourceWhenTheCredentialsCarryNothing() {
            client = clientWith("", "", request -> {
                throw new AssertionError("No X endpoint may be contacted.");
            });

            client.start();

            verify(settingRepository, never()).findById(any());
            verify(aiToolRepository, never()).findNames(any(Pageable.class));
        }

        @Test
        @DisplayName("tolerates an absent scanner.twitter group")
        void toleratesAnAbsentScannerTwitterGroup() {
            client = new TweetStreamClient(webClient(request -> {
                throw new AssertionError("No X endpoint may be contacted.");
            }), properties(null, ingestion(BASE_TERMS)), aiToolRepository, settingRepository,
                    tweetStreamListener, heldLease());

            assertThatCode(() -> client.start()).doesNotThrowAnyException();
            assertThat(client.isRunning()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Token exchange — DL-046
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the app-only token exchange")
    class TokenExchange {

        @Test
        @DisplayName("posts the client-credentials grant to the token path")
        void postsTheClientCredentialsGrantToTheTokenPath() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));

            RecordedExchange token = awaitExchange(TOKEN_PATH);
            assertThat(token.method()).isEqualTo(HttpMethod.POST);
            assertThat(token.body()).isEqualTo("grant_type=client_credentials");
            assertThat(token.contentType())
                    .isEqualTo(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        }

        @Test
        @DisplayName("authorizes the exchange with the base64 of the encoded credential pair")
        void authorizesTheExchangeWithTheBase64OfTheEncodedCredentialPair() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));

            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    ("consumer-key-value:consumer-secret-value").getBytes(StandardCharsets.UTF_8));
            assertThat(awaitExchange(TOKEN_PATH).authorization()).isEqualTo(expected);
        }

        @Test
        @DisplayName("percent-encodes a credential carrying reserved characters")
        void percentEncodesACredentialCarryingReservedCharacters() {
            client = clientWith("key with space", "secret:/?#[]@",
                    routes(streamOf("{\"data\":{\"text\":\"a post\"}}\n")));
            client.start();

            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    ("key%20with%20space:secret%3A%2F%3F%23%5B%5D%40")
                            .getBytes(StandardCharsets.UTF_8));
            assertThat(awaitExchange(TOKEN_PATH).authorization()).isEqualTo(expected);
        }

        @Test
        @DisplayName("carries the token as a bearer credential on the rules and stream requests")
        void carriesTheTokenAsABearerCredentialOnTheRulesAndStreamRequests() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));

            awaitExchange(STREAM_PATH);
            assertThat(exchanges).filteredOn(exchange -> !exchange.path().equals(TOKEN_PATH))
                    .isNotEmpty()
                    .allSatisfy(exchange ->
                            assertThat(exchange.authorization()).isEqualTo("Bearer " + TOKEN));
        }

        @Test
        @DisplayName("exchanges the credentials once and reuses the token")
        void exchangesTheCredentialsOnceAndReusesTheToken() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));

            awaitExchange(STREAM_PATH);
            assertThat(exchanges).filteredOn(exchange -> exchange.path().equals(TOKEN_PATH))
                    .hasSize(1);
        }

        @ParameterizedTest(name = "token_type [{0}]")
        @ValueSource(strings = {"bearer", "Bearer", "BEARER"})
        @DisplayName("accepts the token type without regard to case")
        void acceptsTheTokenTypeWithoutRegardToCase(String tokenType) {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                if (request.url().getPath().equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"" + tokenType
                            + "\",\"access_token\":\"" + TOKEN + "\"}"));
                }
                return routes(streamOf("{\"data\":{\"text\":\"a post\"}}\n")).exchange(request);
            });
            client.start();

            assertThat(awaitExchange(STREAM_PATH).authorization()).isEqualTo("Bearer " + TOKEN);
        }

        @ParameterizedTest(name = "unusable body [{0}]")
        @ValueSource(strings = {
            "{\"token_type\":\"mac\",\"access_token\":\"t\"}",
            "{\"access_token\":\"t\"}",
            "{\"token_type\":\"bearer\"}",
            "{\"token_type\":\"bearer\",\"access_token\":\"\"}",
            "{\"token_type\":\"bearer\",\"access_token\":\"   \"}",
            "{}"
        })
        @DisplayName("reaches neither the rules path nor the stream on an unusable token body")
        void reachesNeitherTheRulesPathNorTheStreamOnAnUnusableTokenBody(String body) {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request ->
                    request.url().getPath().equals(TOKEN_PATH)
                            ? Mono.just(json(body))
                            : Mono.error(new AssertionError("Only the token path may be reached.")));

            client.start();
            awaitExchange(TOKEN_PATH);

            assertThat(exchanges).extracting(RecordedExchange::path).containsOnly(TOKEN_PATH);
        }

        // The bound of scanner.twitter.request-timeout-seconds — DL-230 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("abandons a token exchange that never answers and exchanges again")
        void abandonsATokenExchangeThatNeverAnswers() {
            AtomicInteger tokenAttempts = new AtomicInteger();
            client = clientBoundedAt(1L, request -> {
                if (request.url().getPath().equals(TOKEN_PATH)) {
                    tokenAttempts.incrementAndGet();
                    return Mono.never();
                }
                return Mono.error(new AssertionError("Only the token path may be reached."));
            });

            client.start();

            // A second attempt can only happen if the first was abandoned: with no bound the client
            // waits on the first answer for as long as the connection stays open.
            awaitCondition(() -> tokenAttempts.get() >= 2);
            assertThat(exchanges).extracting(RecordedExchange::path).containsOnly(TOKEN_PATH);
        }

        // The bound of scanner.twitter.request-timeout-seconds — DL-230 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("abandons a rules call that never answers and reaches the token path again")
        void abandonsARulesCallThatNeverAnswers() {
            AtomicInteger rulesAttempts = new AtomicInteger();
            client = clientBoundedAt(1L, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    rulesAttempts.incrementAndGet();
                    return Mono.never();
                }
                return Mono.error(new AssertionError("The stream must not be reached."));
            });

            client.start();

            awaitCondition(() -> rulesAttempts.get() >= 2);
            assertThat(exchanges).extracting(RecordedExchange::path)
                    .containsOnly(TOKEN_PATH, RULES_PATH);
        }

        // The stream body carries no total-response bound — DL-230 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("does not bound the filtered stream: a connection quieter than the bound keeps "
                + "delivering")
        void doesNotBoundTheFilteredStream() {
            client = clientBoundedAt(1L, routes(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Flux.concat(
                            Mono.just(DefaultDataBufferFactory.sharedInstance
                                    .wrap("{\"data\":{\"text\":\"first\"}}\n"
                                            .getBytes(StandardCharsets.UTF_8))),
                            Mono.just(DefaultDataBufferFactory.sharedInstance
                                            .wrap("{\"data\":{\"text\":\"second\"}}\n"
                                                    .getBytes(StandardCharsets.UTF_8)))
                                    .delaySubscription(Duration.ofSeconds(3))))
                    .build()));

            client.start();

            assertThat(awaitDelivery(2))
                    .extracting(node -> node.path("data").path("text").asText())
                    .containsExactly("first", "second");
        }
    }

    // -----------------------------------------------------------------------
    // Rule-set composition — DL-044
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the rule set")
    class RuleSet {

        @Test
        @DisplayName("unions the configured base terms with every ai_tools name")
        void unionsTheConfiguredBaseTermsWithEveryAiToolsName() {
            when(aiToolRepository.findNames(any(Pageable.class)))
                    .thenReturn(List.of("Copilot", "Cursor"));
            client = startedAgainst(streamOf(""));

            assertThat(registeredValues()).containsExactly("\"AI coding tool\"",
                    "\"AI code assistant\"", "\"AI generated code\"", "GPT-4", "Copilot", "Cursor");
        }

        @Test
        @DisplayName("tags every rule with the term in its unquoted form")
        void tagsEveryRuleWithTheTermInItsUnquotedForm() {
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(BASE_TERMS);
        }

        @Test
        @DisplayName("replaces the whole set with the stream_keywords row when it carries a value")
        void replacesTheWholeSetWithTheStreamKeywordsRowWhenItCarriesAValue() {
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of("Copilot"));
            when(settingRepository.findById(STREAM_KEYWORDS_KEY))
                    .thenReturn(Optional.of(setting(" vibe coding , GPT-4 ,, ")));
            client = startedAgainst(streamOf(""));

            assertThat(registeredValues()).containsExactly("\"vibe coding\"", "GPT-4");
            verify(aiToolRepository, never()).findNames(any(Pageable.class));
        }

        @ParameterizedTest(name = "row value [{0}]")
        @NullSource
        @ValueSource(strings = {"", "   ", ",", ",,,", " , , "})
        @DisplayName("falls through to the union when the stream_keywords row carries no term")
        void fallsThroughToTheUnionWhenTheStreamKeywordsRowCarriesNoTerm(String stored) {
            when(settingRepository.findById(STREAM_KEYWORDS_KEY))
                    .thenReturn(Optional.of(setting(stored)));
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(BASE_TERMS);
        }

        @Test
        @DisplayName("drops an ai_tools row carrying no usable name")
        void dropsAnAiToolsRowCarryingNoUsableName() {
            List<String> names = new ArrayList<>();
            names.add("Copilot");
            names.add(null);
            names.add("   ");
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(names);
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(
                    concat(BASE_TERMS, List.of("Copilot")));
        }

        @Test
        @DisplayName("de-duplicates terms without regard to case, keeping the first spelling")
        void deDuplicatesTermsWithoutRegardToCaseKeepingTheFirstSpelling() {
            when(aiToolRepository.findNames(any(Pageable.class)))
                    .thenReturn(List.of("gpt-4", "GPT-4", "Copilot"));
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(
                    concat(BASE_TERMS, List.of("Copilot")));
        }

        @Test
        @DisplayName("contacts no X endpoint when no term can be composed")
        void contactsNoXEndpointWhenNoTermCanBeComposed() {
            client = new TweetStreamClient(webClient(request -> {
                throw new AssertionError("No X endpoint may be contacted.");
            }), properties(twitter(CONSUMER_KEY, CONSUMER_SECRET), ingestion(List.of())),
                    aiToolRepository, settingRepository, tweetStreamListener, heldLease());

            client.start();

            awaitCondition(() -> !client.isRunning());
            assertThat(exchanges).isEmpty();
        }

        @Test
        @DisplayName("tolerates an absent scanner.ingestion group")
        void toleratesAnAbsentScannerIngestionGroup() {
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of("Copilot"));
            client = new TweetStreamClient(
                    webClient(routes(streamOf(""))),
                    properties(twitter(CONSUMER_KEY, CONSUMER_SECRET), null),
                    aiToolRepository, settingRepository, tweetStreamListener, heldLease());
            client.start();

            assertThat(registeredTags()).containsExactly("Copilot");
        }
    }

    // -----------------------------------------------------------------------
    // Bounded queueing, lifecycle drain, idle detection and log suppression
    // — DL-256, DL-258, DL-259, DL-260
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("bounded queueing, drain, idle detection and log suppression")
    class BoundsAndLifecycle {

        @Test
        @DisplayName("requests one record and one chunk at a time, so a blocked handler bounds the "
                + "queue")
        void requestsOneRecordAndOneChunkAtATime() throws Exception {
            int records = 50;
            AtomicInteger emittedChunks = new AtomicInteger();
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            AtomicInteger handled = new AtomicInteger();
            when(tweetStreamListener.onStatus(any(), anyInt())).thenAnswer(invocation -> {
                if (handled.getAndIncrement() == 0) {
                    handlerEntered.countDown();
                    assertThat(releaseHandler.await(20, TimeUnit.SECONDS)).isTrue();
                }
                return true;
            });

            client = startedAgainst(countedChunks(records, emittedChunks));

            assertThat(handlerEntered.await(20, TimeUnit.SECONDS)).isTrue();
            // A default prefetch of 256 pulls every chunk while the handler blocks
            assertThat(emittedChunks.get()).isLessThanOrEqualTo(5);

            releaseHandler.countDown();
            awaitCondition(() -> handled.get() >= records);
            assertThat(emittedChunks.get()).isGreaterThanOrEqualTo(records);
        }

        @Test
        @DisplayName("waits for a record the handler is still holding before reporting the stop")
        void waitsForARecordTheHandlerIsStillHoldingBeforeReportingTheStop() throws Exception {
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            when(tweetStreamListener.onStatus(any(), anyInt())).thenAnswer(invocation -> {
                handlerEntered.countDown();
                assertThat(releaseHandler.await(20, TimeUnit.SECONDS)).isTrue();
                return true;
            });

            client = startedAgainst(streamOf("{\"data\":{\"text\":\"held\"}}\n"));
            assertThat(handlerEntered.await(20, TimeUnit.SECONDS)).isTrue();

            CountDownLatch reported = new CountDownLatch(1);
            Thread stopping = new Thread(() -> client.stop(reported::countDown), "stop-caller");
            stopping.start();
            try {
                assertThat(reported.await(500, TimeUnit.MILLISECONDS))
                        .as("the stop is reported while a record is still held")
                        .isFalse();

                releaseHandler.countDown();
                assertThat(reported.await(20, TimeUnit.SECONDS))
                        .as("the stop is reported once the record is released")
                        .isTrue();
            } finally {
                releaseHandler.countDown();
                stopping.join(TimeUnit.SECONDS.toMillis(20));
            }

            assertThat(client.isRunning()).isFalse();
        }

        @Test
        @DisplayName("reports the stop at once when no record is being handled")
        void reportsTheStopAtOnceWhenNoRecordIsBeingHandled() {
            client = startedAgainst(streamOf(""));
            awaitExchange(STREAM_PATH);

            AtomicInteger reported = new AtomicInteger();
            client.stop(reported::incrementAndGet);

            assertThat(reported.get()).isEqualTo(1);
            assertThat(client.isRunning()).isFalse();
        }

        @Test
        @DisplayName("leaves nothing subscribed after repeated starts and stops")
        void leavesNothingSubscribedAfterRepeatedStartsAndStops() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, routes(streamOf("")));

            for (int attempt = 0; attempt < 20; attempt++) {
                client.start();
                client.stop();
            }

            assertThat(client.isRunning()).isFalse();
        }

        @Test
        @DisplayName("reconnects when the connection delivers no byte for the idle bound")
        void reconnectsWhenTheConnectionDeliversNoByteForTheIdleBound() {
            AtomicInteger streamAttempts = new AtomicInteger();
            ExchangeFunction exchange = request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json(request.method() == HttpMethod.GET
                            ? "{\"data\":[]}"
                            : "{\"meta\":{\"summary\":{\"created\":1}}}"));
                }
                streamAttempts.incrementAndGet();
                return Mono.just(silentStream());
            };
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, exchange,
                    ingestion(BASE_TERMS, MAX_STREAM_RULES, 1L));
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 2);
            assertThat(streamAttempts.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("keeps a connection that keeps delivering keep-alive bytes")
        void keepsAConnectionThatKeepsDeliveringKeepAliveBytes() {
            AtomicInteger streamAttempts = new AtomicInteger();
            ExchangeFunction exchange = request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json(request.method() == HttpMethod.GET
                            ? "{\"data\":[]}"
                            : "{\"meta\":{\"summary\":{\"created\":1}}}"));
                }
                streamAttempts.incrementAndGet();
                return Mono.just(keepAliveStream());
            };
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, exchange,
                    ingestion(BASE_TERMS, MAX_STREAM_RULES, 2L));
            client.start();

            awaitExchange(STREAM_PATH);
            sleep(Duration.ofSeconds(4));
            assertThat(streamAttempts.get())
                    .as("a keep-alive resets the idle bound, so no reconnection happens")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("reports several unreadable records without one record per warning")
        void reportsSeveralUnreadableRecordsWithoutOneRecordPerWarning() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = startedAgainst(chunks("not json\n", "still not json\n",
                        "nor this\n", "{\"data\":{\"text\":\"a post\"}}\n"));

                assertThat(awaitDelivery(1))
                        .extracting(node -> node.path("data").path("text").asText())
                        .containsExactly("a post");

                List<String> warnings = recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("could not be read as JSON"))
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.get(0))
                        .contains("in the last 60s")
                        .doesNotContain("not json")
                        .doesNotContain("nor this");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("resolves the popularity threshold once per cycle and hands it to every record")
        void resolvesThePopularityThresholdOncePerCycleAndHandsItToEveryRecord() {
            when(tweetStreamListener.popularityThresholdInForce()).thenReturn(250);

            client = startedAgainst(chunks("{\"data\":{\"text\":\"one\"}}\n",
                    "{\"data\":{\"text\":\"two\"}}\n",
                    "{\"data\":{\"text\":\"three\"}}\n"));

            awaitDelivery(3);
            verify(tweetStreamListener, times(1)).popularityThresholdInForce();
            verify(tweetStreamListener, times(3)).onStatus(any(JsonNode.class), eq(250));
        }
    }

    // -----------------------------------------------------------------------
    // Term validation and rule bounds — DL-254, DL-257
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("term validation and rule bounds")
    class TermValidationAndBounds {

        @ParameterizedTest(name = "[{index}] a term holding {0} is dropped")
        @ValueSource(strings = {
            "quote\" OR spam",
            "back\\slash",
            "group (a)",
            "operator:value",
            "-negated",
            "#hashtag",
            "@handle",
            "brace{a}",
            "star*",
            "tilde~a",
            "trailing hyphen -",
        })
        @DisplayName("drops a term holding a character the rule syntax reserves")
        void dropsATermHoldingAReservedCharacter(String rejected) {
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of(rejected));
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(BASE_TERMS);
            assertThat(mutationBodyContaining("add")).doesNotContain("OR spam");
        }

        @Test
        @DisplayName("drops a term longer than the accepted bound")
        void dropsATermLongerThanTheAcceptedBound() {
            when(aiToolRepository.findNames(any(Pageable.class)))
                    .thenReturn(List.of("a".repeat(129)));
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).containsExactlyElementsOf(BASE_TERMS);
        }

        @ParameterizedTest(name = "[{index}] a term of {0} is registered")
        @ValueSource(strings = {"AI-generated code", "GPT 4.5", "snake_case tool", "Cody"})
        @DisplayName("registers a term holding only accepted characters")
        void registersATermHoldingOnlyAcceptedCharacters(String accepted) {
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of(accepted));
            client = startedAgainst(streamOf(""));

            assertThat(registeredTags()).contains(accepted);
        }

        @Test
        @DisplayName("records no term value when a term is dropped")
        void recordsNoTermValueWhenATermIsDropped() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                when(aiToolRepository.findNames(any(Pageable.class)))
                        .thenReturn(List.of("secret\" OR spam"));
                client = startedAgainst(streamOf(""));
                registeredTags();

                List<String> warnings = recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("Dropping a stream rule term"))
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.getFirst())
                        .doesNotContain("secret")
                        .doesNotContain("OR spam")
                        .contains("exceeds 128 character(s)")
                        .contains("is 15 character(s) long");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("bounds the composed collection to the configured rule cap")
        void boundsTheComposedCollectionToTheConfiguredRuleCap() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = startedAgainst(streamOf(""), ingestion(BASE_TERMS, 2,
                        STREAM_IDLE_TIMEOUT_SECONDS));

                assertThat(registeredTags()).containsExactly("AI coding tool", "AI code assistant");
                List<String> warnings = recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("against a cap of"))
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.get(0))
                        .contains("Composed 4 stream rule term(s) against a cap of 2")
                        .doesNotContain("AI coding tool");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("reads the ai_tools names bounded by the rule cap, ordered and projected")
        void readsTheAiToolsNamesBoundedByTheRuleCap() {
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(List.of());
            client = startedAgainst(streamOf(""), ingestion(BASE_TERMS, 7,
                    STREAM_IDLE_TIMEOUT_SECONDS));
            registeredTags();

            ArgumentCaptor<Pageable> bound = ArgumentCaptor.forClass(Pageable.class);
            verify(aiToolRepository, atLeastOnce()).findNames(bound.capture());
            assertThat(bound.getAllValues()).allSatisfy(page -> {
                assertThat(page.getPageNumber()).isZero();
                assertThat(page.getPageSize()).isEqualTo(7);
            });
            verify(aiToolRepository, never()).findAll();
        }

        @Test
        @DisplayName("bounds the number of segments the stream_keywords row is split into")
        void boundsTheNumberOfSegmentsTheStreamKeywordsRowIsSplitInto() {
            String stored = java.util.stream.IntStream.rangeClosed(1, 600)
                    .mapToObj(index -> "term" + index)
                    .collect(Collectors.joining(","));
            when(settingRepository.findById(STREAM_KEYWORDS_KEY))
                    .thenReturn(Optional.of(setting(stored)));
            client = startedAgainst(streamOf(""), ingestion(BASE_TERMS, 1_000,
                    STREAM_IDLE_TIMEOUT_SECONDS));

            awaitCondition(() -> allRegisteredValues().size() >= 511);
            assertThat(allRegisteredValues()).hasSizeLessThanOrEqualTo(512);
            assertThat(allRegisteredValues()).contains("term1", "term511");
        }

        @Test
        @DisplayName("sends the additions as consecutive requests of at most twenty-five rules")
        void sendsTheAdditionsAsConsecutiveRequestsOfAtMostTwentyFiveRules() {
            List<String> names = java.util.stream.IntStream.rangeClosed(1, 60)
                    .mapToObj(index -> "Tool" + index)
                    .toList();
            when(aiToolRepository.findNames(any(Pageable.class))).thenReturn(names);
            client = startedAgainst(streamOf(""), ingestion(List.of(), 60,
                    STREAM_IDLE_TIMEOUT_SECONDS));

            awaitCondition(() -> allRegisteredValues().size() >= 60);
            assertThat(allRegisteredValues()).hasSize(60);
            assertThat(mutationRequestsCarrying("add")).isEqualTo(3);
        }

        @Test
        @DisplayName("sends the deletions as consecutive requests of at most twenty-five identifiers")
        void sendsTheDeletionsAsConsecutiveRequestsOfAtMostTwentyFiveIdentifiers() {
            String registered = java.util.stream.IntStream.rangeClosed(1, 30)
                    .mapToObj(index -> "{\"id\":\"" + index + "\",\"value\":\"stale" + index
                            + "\",\"tag\":\"stale" + index + "\"}")
                    .collect(Collectors.joining(","));
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET,
                    routesWithRegisteredRules("{\"data\":[" + registered + "]}", streamOf("")));
            client.start();

            awaitCondition(() -> deletedRuleIds().size() >= 30);
            assertThat(deletedRuleIds()).hasSize(30);
            assertThat(mutationRequestsCarrying("delete")).isEqualTo(2);
        }

        @Test
        @DisplayName("replaces a registered rule whose tag no longer matches the wanted one")
        void replacesARegisteredRuleWhoseTagNoLongerMatches() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, routesWithRegisteredRules(
                    "{\"data\":[{\"id\":\"77\",\"value\":\"GPT-4\",\"tag\":\"outdated\"}]}",
                    streamOf("")));
            client.start();

            awaitCondition(() -> !mutationBodyContaining("add").isEmpty());
            assertThat(deletedRuleIds()).containsExactly("77");
            assertThat(registeredTags()).contains("GPT-4");
            assertThat(allRegisteredValues()).contains("GPT-4");
        }

        @Test
        @DisplayName("reports the number of rules it replaced for a changed tag")
        void reportsTheNumberOfRulesItReplacedForAChangedTag() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, routesWithRegisteredRules(
                        "{\"data\":[{\"id\":\"77\",\"value\":\"GPT-4\","
                                + "\"tag\":\"outdated\"}]}",
                        streamOf("")));
                client.start();
                awaitCondition(() -> recorded.list.stream()
                        .anyMatch(event -> event.getFormattedMessage()
                                .startsWith("Stream rules reconciled")));

                assertThat(recorded.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("Stream rules reconciled"))
                        .findFirst()
                        .orElseThrow())
                        .contains("1 replaced for a changed tag");
            } finally {
                detachClientAppender(recorded);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rule reconciliation — DL-045
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("rule reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("lists the registered rules before mutating them")
        void listsTheRegisteredRulesBeforeMutatingThem() {
            client = startedAgainst(streamOf(""));
            awaitExchange(STREAM_PATH);

            List<RecordedExchange> rules = exchanges.stream()
                    .filter(exchange -> exchange.path().equals(RULES_PATH))
                    .toList();
            assertThat(rules).isNotEmpty();
            assertThat(rules.get(0).method()).isEqualTo(HttpMethod.GET);
        }

        @Test
        @DisplayName("issues no mutation when the registered rules already match")
        void issuesNoMutationWhenTheRegisteredRulesAlreadyMatch() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET,
                    routesWithRegisteredRules(registeredRulesBody(), streamOf("")));
            client.start();
            awaitExchange(STREAM_PATH);

            assertThat(exchanges).noneSatisfy(exchange -> {
                assertThat(exchange.path()).isEqualTo(RULES_PATH);
                assertThat(exchange.method()).isEqualTo(HttpMethod.POST);
            });
        }

        @Test
        @DisplayName("deletes only the rules the composed set no longer carries")
        void deletesOnlyTheRulesTheComposedSetNoLongerCarries() {
            String registered = "{\"data\":[{\"id\":\"9001\",\"value\":\"retired term\","
                    + "\"tag\":\"retired term\"},{\"id\":\"9002\",\"value\":\"GPT-4\","
                    + "\"tag\":\"GPT-4\"}]}";
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET,
                    routesWithRegisteredRules(registered, streamOf("")));
            client.start();
            awaitExchange(STREAM_PATH);

            String deletion = mutationBodyContaining("delete");
            assertThat(deletion).contains("\"ids\"").contains("9001").doesNotContain("9002");
            assertThat(mutationBodyContaining("add")).doesNotContain("\"GPT-4\",\"tag\":\"GPT-4\"");
        }

        @Test
        @DisplayName("registers every composed rule when X holds none")
        void registersEveryComposedRuleWhenXHoldsNone() {
            client = startedAgainst(streamOf(""));

            assertThat(registeredValues()).hasSize(BASE_TERMS.size());
            assertThat(mutationBodies()).noneSatisfy(body -> assertThat(body).contains("delete"));
        }

        @Test
        @DisplayName("never asks X for a dry run")
        void neverAsksXForADryRun() {
            client = startedAgainst(streamOf(""));
            awaitExchange(STREAM_PATH);

            assertThat(exchanges).allSatisfy(exchange -> {
                assertThat(exchange.body()).doesNotContain("dry_run");
                assertThat(exchange.query()).doesNotContain("dry_run");
            });
        }

        // A provider-reflected expression reaches the record as a fingerprint only — DL-275 — see
        // docs/DECISION_LOG.md
        @Test
        @DisplayName("records a refused rule as a fingerprint, never as the expression X reflected")
        void recordsARefusedRuleAsAFingerprintNeverAsTheExpressionXReflected() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                    String path = request.url().getPath();
                    if (path.equals(TOKEN_PATH)) {
                        return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                                + TOKEN + "\"}"));
                    }
                    if (path.equals(RULES_PATH)) {
                        return Mono.just(json(request.method() == HttpMethod.GET
                                ? "{\"data\":[]}"
                                : REFUSAL_BODY));
                    }
                    return Mono.just(streamOf(""));
                });
                client.start();
                awaitExchange(STREAM_PATH);

                List<String> warnings = awaitWarningsContaining(recorded, "X refused");
                assertThat(warnings).hasSize(1);
                assertThat(warnings.getFirst())
                        .doesNotContain("forged")
                        .doesNotContain("smuggled term")
                        .doesNotContain("\n")
                        .doesNotContain("\r")
                        .contains("(1 not created, 1 invalid)")
                        .contains("hmac256:");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("records nothing when the mutation answer refuses no rule")
        void recordsNothingWhenTheMutationAnswerRefusesNoRule() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = startedAgainst(streamOf(""));
                awaitExchange(STREAM_PATH);

                assertThat(recorded.list).noneSatisfy(event ->
                        assertThat(event.getFormattedMessage()).contains("X refused"));
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("tolerates a rules response carrying no data member")
        void toleratesARulesResponseCarryingNoDataMember() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET,
                    routesWithRegisteredRules("{\"meta\":{\"sent\":\"now\"}}", streamOf("")));
            client.start();

            assertThat(registeredTags()).containsExactlyElementsOf(BASE_TERMS);
        }
    }

    // -----------------------------------------------------------------------
    // Stream consumption
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("stream consumption")
    class StreamConsumption {

        // Only the fields the listener maps are requested; no expansion is — DL-262
        @Test
        @DisplayName("requests the fields the listener reads and no expansion it ignores")
        void requestsTheFieldsTheListenerReadsAndNoExpansion() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));

            RecordedExchange stream = awaitExchange(STREAM_PATH);
            assertThat(stream.method()).isEqualTo(HttpMethod.GET);
            assertThat(stream.query())
                    .contains("tweet.fields=created_at,public_metrics,referenced_tweets,"
                            + "attachments,author_id")
                    .doesNotContain("expansions");
        }

        @Test
        @DisplayName("reassembles a record split across three chunks")
        void reassemblesARecordSplitAcrossThreeChunks() {
            client = startedAgainst(chunks("{\"data\":{\"te", "xt\":\"split reco", "rd\"}}\n"));

            JsonNode delivered = awaitDelivery(1).get(0);
            assertThat(delivered.path("data").path("text").asText()).isEqualTo("split record");
        }

        @Test
        @DisplayName("reassembles a multi-byte character split across a chunk boundary")
        void reassemblesAMultiByteCharacterSplitAcrossAChunkBoundary() {
            byte[] payload = "{\"data\":{\"text\":\"emoji \uD83D\uDE00 end\"}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            int cut = new String(payload, StandardCharsets.UTF_8).indexOf('\uD83D');
            byte[] head = java.util.Arrays.copyOfRange(payload, 0, cut + 1);
            byte[] tail = java.util.Arrays.copyOfRange(payload, cut + 1, payload.length);
            client = startedAgainst(byteChunks(head, tail));

            JsonNode delivered = awaitDelivery(1).get(0);
            assertThat(delivered.path("data").path("text").asText())
                    .isEqualTo("emoji \uD83D\uDE00 end");
        }

        @Test
        @DisplayName("delivers three records carried by one chunk, CRLF delimited")
        void deliversThreeRecordsCarriedByOneChunkCrlfDelimited() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"one\"}}\r\n"
                    + "{\"data\":{\"text\":\"two\"}}\r\n{\"data\":{\"text\":\"three\"}}\r\n"));

            assertThat(awaitDelivery(3))
                    .extracting(node -> node.path("data").path("text").asText())
                    .containsExactly("one", "two", "three");
        }

        @Test
        @DisplayName("drops keep-alive lines without handing them to the listener")
        void dropsKeepAliveLinesWithoutHandingThemToTheListener() {
            client = startedAgainst(streamOf("\r\n\r\n   \r\n{\"data\":{\"text\":\"after keep "
                    + "alive\"}}\n\r\n"));

            assertThat(awaitDelivery(1))
                    .extracting(node -> node.path("data").path("text").asText())
                    .containsExactly("after keep alive");
        }

        @Test
        @DisplayName("skips an unparseable record and keeps consuming")
        void skipsAnUnparseableRecordAndKeepsConsuming() {
            client = startedAgainst(streamOf("{not json at all\n{\"data\":{\"text\":\"good\"}}\n"));

            assertThat(awaitDelivery(1))
                    .extracting(node -> node.path("data").path("text").asText())
                    .containsExactly("good");
        }

        @Test
        @DisplayName("keeps consuming after the listener raises")
        void keepsConsumingAfterTheListenerRaises() {
            AtomicInteger seen = new AtomicInteger();
            when(tweetStreamListener.onStatus(any(), anyInt())).thenAnswer(invocation -> {
                if (seen.incrementAndGet() == 1) {
                    throw new IllegalStateException("listener failure");
                }
                return true;
            });
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"first\"}}\n"
                    + "{\"data\":{\"text\":\"second\"}}\n"));

            awaitCondition(() -> seen.get() >= 2);
            assertThat(seen.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("stops when the listener answers false")
        void stopsWhenTheListenerAnswersFalse() {
            AtomicInteger seen = new AtomicInteger();
            when(tweetStreamListener.onStatus(any(), anyInt())).thenAnswer(invocation -> {
                seen.incrementAndGet();
                return false;
            });
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"first\"}}\n"
                    + "{\"data\":{\"text\":\"second\"}}\n"));

            awaitCondition(() -> !client.isRunning());
            assertThat(seen.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("tolerates a record carrying no payload member")
        void toleratesARecordCarryingNoPayloadMember() {
            client = startedAgainst(streamOf("{}\n"));

            assertThat(awaitDelivery(1)).hasSize(1);
        }

        // Per-record dispatch runs on a blocking-capable scheduler — see docs/DECISION_LOG.md DL-220
        @Test
        @DisplayName("hands the record to the listener on a blocking-capable thread, not on the "
                + "thread the body is emitted on")
        void handsTheRecordToTheListenerOnABlockingCapableThread() {
            List<String> emittingThreads = new CopyOnWriteArrayList<>();
            List<String> listenerThreads = new CopyOnWriteArrayList<>();
            List<Boolean> listenerOnNonBlockingThread = new CopyOnWriteArrayList<>();
            when(tweetStreamListener.onStatus(any(), anyInt())).thenAnswer(invocation -> {
                listenerThreads.add(Thread.currentThread().getName());
                listenerOnNonBlockingThread.add(Schedulers.isInNonBlockingThread());
                return true;
            });

            client = startedAgainst(streamEmittedOnANonBlockingThread(
                    "{\"data\":{\"text\":\"a post\"}}\n", emittingThreads));

            awaitDelivery(1);
            awaitCondition(() -> !listenerThreads.isEmpty());
            assertThat(emittingThreads).isNotEmpty();
            assertThat(listenerOnNonBlockingThread)
                    .as("no listener call runs on a non-blocking thread")
                    .containsOnly(Boolean.FALSE);
            assertThat(listenerThreads.get(0)).startsWith("boundedElastic-");
            assertThat(listenerThreads.get(0)).isNotEqualTo(emittingThreads.get(0));
        }

        // Bounded accumulation — see docs/DECISION_LOG.md DL-222
        @Test
        @DisplayName("drops a record that reaches the accumulator bound, reports it once and keeps "
                + "consuming")
        void dropsARecordThatReachesTheAccumulatorBound() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                client = startedAgainst(chunks("y".repeat(OVER_THE_RECORD_BOUND),
                        "\n{\"data\":{\"text\":\"after the bound\"}}\n"));

                assertThat(awaitDelivery(1))
                        .extracting(node -> node.path("data").path("text").asText())
                        .containsExactly("after the bound");
                assertThat(client.isRunning()).isTrue();
                List<String> warnings = recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("with no line feed"))
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.get(0))
                        // Counts and the bound only — DL-260
                        .contains("Skipped 1 X filtered stream record(s)")
                        .contains("1048576 byte bound")
                        .doesNotContain("yyy");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("keeps delivering a record that stays inside the accumulator bound")
        void keepsDeliveringARecordInsideTheAccumulatorBound() {
            String text = "z".repeat(300_000);
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"" + text + "\"}}\n"));

            assertThat(awaitDelivery(1).get(0).path("data").path("text").asText())
                    .hasSize(300_000);
        }
    }

    // -----------------------------------------------------------------------
    // Resilience — DL-045, DL-046, DL-052
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resilience")
    class Resilience {

        @Test
        @DisplayName("clears the token and exchanges a new one after HTTP 401")
        void clearsTheTokenAndExchangesANewOneAfterHttp401() {
            AtomicInteger tokenExchanges = new AtomicInteger();
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    String token = tokenExchanges.incrementAndGet() == 1 ? TOKEN : REFRESHED_TOKEN;
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + token + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                if (streamAttempts.incrementAndGet() == 1) {
                    return Mono.just(status(HttpStatus.UNAUTHORIZED));
                }
                return Mono.just(streamOf("{\"data\":{\"text\":\"after refresh\"}}\n"));
            });
            client.start();

            awaitCondition(() -> tokenExchanges.get() >= 2);
            assertThat(awaitDelivery(1)).hasSize(1);
            assertThat(exchanges).filteredOn(exchange -> exchange.path().equals(STREAM_PATH))
                    .last()
                    .satisfies(exchange ->
                            assertThat(exchange.authorization())
                                    .isEqualTo("Bearer " + REFRESHED_TOKEN));
        }

        @Test
        @DisplayName("reconnects after a transport failure without giving up")
        void reconnectsAfterATransportFailureWithoutGivingUp() {
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                if (streamAttempts.incrementAndGet() == 1) {
                    return Mono.error(new java.io.IOException("connection reset"));
                }
                return Mono.just(streamOf("{\"data\":{\"text\":\"after reconnect\"}}\n"));
            });
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 2);
            assertThat(awaitDelivery(1)).hasSize(1);
        }

        // Every failure rendering passes the shared log guard — DL-197 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("guards a failure message before it reaches a reconnection record")
        void guardsAFailureMessageBeforeItReachesAReconnectionRecord() {
            ListAppender<ILoggingEvent> recorded = attachClientAppender();
            try {
                AtomicInteger streamAttempts = new AtomicInteger();
                client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                    String path = request.url().getPath();
                    if (path.equals(TOKEN_PATH)) {
                        return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                                + TOKEN + "\"}"));
                    }
                    if (path.equals(RULES_PATH)) {
                        return Mono.just(json("{\"data\":[]}"));
                    }
                    streamAttempts.incrementAndGet();
                    return Mono.error(new java.io.IOException(HOSTILE_FAILURE_MESSAGE));
                });
                client.start();
                awaitCondition(() -> streamAttempts.get() >= 1);

                List<String> warnings = awaitWarningsContaining(recorded, "Reconnecting to the X");
                assertThat(warnings).isNotEmpty();
                assertThat(warnings.getFirst())
                        .contains("IOException")
                        .doesNotContain("\n")
                        .doesNotContain("\r")
                        .doesNotContain("x".repeat(GUARDED_VALUE_LIMIT))
                        .contains("connection reset??");
            } finally {
                detachClientAppender(recorded);
            }
        }

        @Test
        @DisplayName("recomposes the rule set before every reconnection")
        void recomposesTheRuleSetBeforeEveryReconnection() {
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                if (streamAttempts.incrementAndGet() == 1) {
                    return Mono.error(new java.io.IOException("connection reset"));
                }
                return Mono.just(streamOf("{\"data\":{\"text\":\"after reconnect\"}}\n"));
            });
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 2);
            verify(settingRepository, org.mockito.Mockito.atLeast(2))
                    .findById(STREAM_KEYWORDS_KEY);
        }

        @Test
        @DisplayName("waits for the rate-limit reset instant a 429 names")
        void waitsForTheRateLimitResetInstantA429Names() {
            AtomicInteger streamAttempts = new AtomicInteger();
            long resetFarAhead = Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                streamAttempts.incrementAndGet();
                return Mono.just(rateLimited(String.valueOf(resetFarAhead)));
            });
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 1);
            sleep(MINIMUM_BACKOFF.plusSeconds(2));
            assertThat(streamAttempts.get())
                    .as("the reset instant is honoured rather than the backoff delay")
                    .isEqualTo(1);
        }

        @ParameterizedTest(name = "reset header [{0}]")
        @ValueSource(strings = {"", "99999999999999999999", "9223372036854775807"})
        @DisplayName("falls back to the backoff delay when the reset header is unusable")
        void fallsBackToTheBackoffDelayWhenTheResetHeaderIsUnusable(String header) {
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                if (streamAttempts.incrementAndGet() == 1) {
                    return Mono.just(rateLimited(header));
                }
                return Mono.just(streamOf("{\"data\":{\"text\":\"after the window\"}}\n"));
            });
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 2);
            assertThat(awaitDelivery(1)).hasSize(1);
        }

        @ParameterizedTest(name = "reset header [{0}]")
        @ValueSource(strings = {"   ", "not-a-number", "31556889864403200"})
        @DisplayName("applies the backoff delay rather than reconnecting at once on an unusable "
                + "reset header")
        void appliesTheBackoffDelayRatherThanReconnectingAtOnceOnAnUnusableResetHeader(
                String header) {
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                streamAttempts.incrementAndGet();
                return Mono.just(rateLimited(header));
            });
            client.start();

            awaitCondition(() -> streamAttempts.get() >= 1);
            sleep(Duration.ofMillis(1500));

            assertThat(streamAttempts.get()).as("no immediate reconnection").isEqualTo(1);
        }

        @Test
        @DisplayName("issues no further exchange once it is stopped")
        void issuesNoFurtherExchangeOnceItIsStopped() {
            AtomicInteger streamAttempts = new AtomicInteger();
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request -> {
                String path = request.url().getPath();
                if (path.equals(TOKEN_PATH)) {
                    return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                            + TOKEN + "\"}"));
                }
                if (path.equals(RULES_PATH)) {
                    return Mono.just(json("{\"data\":[]}"));
                }
                streamAttempts.incrementAndGet();
                return Mono.error(new java.io.IOException("connection reset"));
            });
            client.start();
            awaitCondition(() -> streamAttempts.get() >= 1);

            client.stop();
            int afterStop = streamAttempts.get();
            sleep(MINIMUM_BACKOFF.plusSeconds(3));

            assertThat(streamAttempts.get()).isEqualTo(afterStop);
            assertThat(client.isRunning()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // The reconnect delay a rate-limit answer produces
    // -----------------------------------------------------------------------

    // Bounded rate-limit reset horizon — DL-280 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("the rate-limit reset horizon")
    class RateLimitResetHorizon {

        /** Delay the caller computed, standing in for the jittered exponential backoff. */
        private static final Duration BACKOFF = Duration.ofSeconds(5L);

        /** Longest delay a reported reset may produce. */
        private static final Duration HORIZON = Duration.ofMinutes(15L);

        @Test
        @DisplayName("waits until a reset that falls inside the horizon")
        void waitsUntilAResetThatFallsInsideTheHorizon() throws Exception {
            long reset = Instant.now().plusSeconds(120L).getEpochSecond();

            Duration delay = rateLimitDelayFor(String.valueOf(reset), BACKOFF);

            assertThat(delay).isBetween(Duration.ofSeconds(110L), Duration.ofSeconds(121L));
        }

        @Test
        @DisplayName("applies the horizon to a reset ten years away")
        void appliesTheHorizonToAResetTenYearsAway() throws Exception {
            long reset = Instant.now().plus(Duration.ofDays(3650L)).getEpochSecond();

            assertThat(rateLimitDelayFor(String.valueOf(reset), BACKOFF)).isEqualTo(HORIZON);
        }

        @Test
        @DisplayName("applies the horizon to the largest reset an instant represents")
        void appliesTheHorizonToTheLargestResetAnInstantRepresents() throws Exception {
            assertThat(rateLimitDelayFor(String.valueOf(Instant.MAX.getEpochSecond()), BACKOFF))
                    .isEqualTo(HORIZON);
        }

        @Test
        @DisplayName("reports the horizon it applied and no header value")
        void reportsTheHorizonItAppliedAndNoHeaderValue() throws Exception {
            long reset = Instant.now().plus(Duration.ofDays(3650L)).getEpochSecond();
            Logger clientLogger = (Logger) LoggerFactory.getLogger(TweetStreamClient.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            clientLogger.addAppender(appender);
            try {
                rateLimitDelayFor(String.valueOf(reset), BACKOFF);
            } finally {
                clientLogger.detachAppender(appender);
                appender.stop();
            }

            List<String> rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
            assertThat(rendered).anySatisfy(record -> assertThat(record)
                    .contains("beyond the 900s reconnect horizon")
                    .doesNotContain(String.valueOf(reset)));
        }

        @Test
        @DisplayName("leaves the backoff delay in force for a reset already past")
        void leavesTheBackoffDelayInForceForAResetAlreadyPast() throws Exception {
            long reset = Instant.now().minusSeconds(3600L).getEpochSecond();

            assertThat(rateLimitDelayFor(String.valueOf(reset), BACKOFF)).isEqualTo(BACKOFF);
        }

        @Test
        @DisplayName("leaves the backoff delay in force for an absent header")
        void leavesTheBackoffDelayInForceForAnAbsentHeader() throws Exception {
            assertThat(rateLimitDelayFor(null, BACKOFF)).isEqualTo(BACKOFF);
        }

        @ParameterizedTest(name = "reset header [{0}]")
        @ValueSource(strings = {"9223372036854775807", "-9223372036854775808",
            "31556889864403200", "99999999999999999999", "not-a-number", "", "   ", "1e9",
            "0x7fffffff", "9223372036854775808"})
        @DisplayName("leaves the backoff delay in force for a header it cannot read")
        void leavesTheBackoffDelayInForceForAHeaderItCannotRead(String header) throws Exception {
            assertThat(rateLimitDelayFor(header, BACKOFF)).isEqualTo(BACKOFF);
        }

        @ParameterizedTest(name = "reset header [{0}]")
        @ValueSource(strings = {"9223372036854775807", "31556889864403200", "99999999999999999999",
            "not-a-number", "", "   ", "-1", "0", "2147483647", "31556889864403199"})
        @DisplayName("produces a delay within the horizon for every header value")
        void producesADelayWithinTheHorizonForEveryHeaderValue(String header) throws Exception {
            Duration delay = rateLimitDelayFor(header, BACKOFF);

            assertThat(delay).isGreaterThanOrEqualTo(BACKOFF).isLessThanOrEqualTo(HORIZON);
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle and structural invariants
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("the lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("returns from start without waiting for the stream")
        void returnsFromStartWithoutWaitingForTheStream() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request ->
                    Mono.delay(Duration.ofSeconds(30))
                            .then(Mono.error(new AssertionError("never awaited"))));

            long before = System.nanoTime();
            client.start();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            assertThat(client.isRunning()).isTrue();
        }

        @Test
        @DisplayName("reports it is not running before it is started")
        void reportsItIsNotRunningBeforeItIsStarted() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET, request ->
                    Mono.error(new AssertionError("not started")));

            assertThat(client.isRunning()).isFalse();
        }

        @Test
        @DisplayName("tolerates a stop with nothing subscribed and a repeated stop")
        void toleratesAStopWithNothingSubscribedAndARepeatedStop() {
            client = clientWith(CONSUMER_KEY, CONSUMER_SECRET,
                    routes(streamOf("{\"data\":{\"text\":\"a post\"}}\n")));

            assertThatCode(() -> {
                client.stop();
                client.start();
                client.stop();
                client.stop();
            }).doesNotThrowAnyException();
            assertThat(client.isRunning()).isFalse();
        }

        @Test
        @DisplayName("ignores a repeated start")
        void ignoresARepeatedStart() {
            client = startedAgainst(streamOf(""));
            awaitExchange(STREAM_PATH);
            int afterFirstStart = exchanges.size();

            client.start();
            sleep(Duration.ofMillis(300));

            assertThat(exchanges).hasSize(afterFirstStart);
        }

        @Test
        @DisplayName("rejects every absent collaborator")
        void rejectsEveryAbsentCollaborator() {
            WebClient transport = webClient(request -> Mono.empty());
            ScannerProperties bound =
                    properties(twitter(CONSUMER_KEY, CONSUMER_SECRET), ingestion(BASE_TERMS));

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TweetStreamClient(null, bound, aiToolRepository, settingRepository,
                            tweetStreamListener, heldLease()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TweetStreamClient(transport, null, aiToolRepository, settingRepository,
                            tweetStreamListener, heldLease()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TweetStreamClient(transport, bound, null, settingRepository,
                            tweetStreamListener, heldLease()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TweetStreamClient(transport, bound, aiToolRepository, null,
                            tweetStreamListener, heldLease()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new TweetStreamClient(transport, bound, aiToolRepository, settingRepository,
                            null, heldLease()));
        }
    }

    @Nested
    @DisplayName("the X surface")
    class XSurface {

        @Test
        @DisplayName("reaches only the token, rules and stream paths across a full cycle")
        void reachesOnlyTheTokenRulesAndStreamPathsAcrossAFullCycle() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));
            awaitDelivery(1);

            assertThat(exchanges).extracting(RecordedExchange::path)
                    .containsAnyOf(TOKEN_PATH, RULES_PATH, STREAM_PATH)
                    .allSatisfy(path -> assertThat(path)
                            .isIn(TOKEN_PATH, RULES_PATH, STREAM_PATH));
        }

        @Test
        @DisplayName("posts only to the token and rules paths")
        void postsOnlyToTheTokenAndRulesPaths() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));
            awaitDelivery(1);

            assertThat(exchanges).filteredOn(exchange -> exchange.method() == HttpMethod.POST)
                    .isNotEmpty()
                    .allSatisfy(exchange ->
                            assertThat(exchange.path()).isIn(TOKEN_PATH, RULES_PATH));
        }

        @Test
        @DisplayName("issues no request that could publish a post")
        void issuesNoRequestThatCouldPublishAPost() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));
            awaitDelivery(1);

            assertThat(exchanges).allSatisfy(exchange -> {
                assertThat(exchange.path()).isNotEqualTo("/2/tweets");
                assertThat(exchange.method())
                        .isIn(HttpMethod.GET, HttpMethod.POST);
            });
            assertThat(exchanges).filteredOn(exchange -> exchange.path().equals("/2/tweets"))
                    .isEmpty();
        }

        @Test
        @DisplayName("signs nothing with the OAuth 1.0a user-context credentials")
        void signsNothingWithTheOauth1aUserContextCredentials() {
            client = startedAgainst(streamOf("{\"data\":{\"text\":\"a post\"}}\n"));
            awaitDelivery(1);

            assertThat(exchanges).allSatisfy(exchange -> {
                assertThat(exchange.authorization()).doesNotContain("OAuth");
                assertThat(exchange.authorization()).doesNotContain("oauth_signature");
                assertThat(exchange.headerNames()).doesNotContain("oauth_token");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * Assembles a started client against a canned stream response.
     *
     * @param stream the canned stream response
     * @return the started client
     */
    private TweetStreamClient startedAgainst(ClientResponse stream) {
        TweetStreamClient started = clientWith(CONSUMER_KEY, CONSUMER_SECRET, routes(stream));
        started.start();
        return started;
    }

    /**
     * Assembles a client with the supplied credentials and exchange function.
     *
     * @param consumerKey    value bound to {@code scanner.twitter.consumer-key}
     * @param consumerSecret value bound to {@code scanner.twitter.consumer-secret}
     * @param exchange       the controlled exchange function
     * @return the assembled client
     */
    private TweetStreamClient clientWith(String consumerKey, String consumerSecret,
            ExchangeFunction exchange) {
        return clientWith(consumerKey, consumerSecret, exchange, ingestion(BASE_TERMS));
    }

    /**
     * Assembles a client with the supplied credentials, exchange function and ingestion group.
     *
     * @param consumerKey    value bound to {@code scanner.twitter.consumer-key}
     * @param consumerSecret value bound to {@code scanner.twitter.consumer-secret}
     * @param exchange       the controlled exchange function
     * @param ingestion      the {@code scanner.ingestion} group, possibly {@code null}
     * @return the assembled client
     */
    private TweetStreamClient clientWith(String consumerKey, String consumerSecret,
            ExchangeFunction exchange, ScannerProperties.Ingestion ingestion) {
        return new TweetStreamClient(webClient(exchange),
                properties(twitter(consumerKey, consumerSecret), ingestion),
                aiToolRepository, settingRepository, tweetStreamListener, heldLease());
    }

    /**
     * Builds a {@code scanner.ingestion} group carrying the supplied base terms and bounds — DL-254,
     * DL-256.
     *
     * @param terms        value of {@code stream-base-keywords}
     * @param maxRules     value of {@code max-stream-rules}
     * @param idleSeconds  value of {@code stream-idle-timeout-seconds}
     * @return the group
     */
    private static ScannerProperties.Ingestion ingestion(List<String> terms, int maxRules,
            long idleSeconds) {
        return new ScannerProperties.Ingestion(terms, maxRules, idleSeconds);
    }

    /**
     * Starts a client against the supplied stream response and ingestion group.
     *
     * @param stream    the canned stream response
     * @param ingestion the {@code scanner.ingestion} group
     * @return the started client
     */
    private TweetStreamClient startedAgainst(ClientResponse stream,
            ScannerProperties.Ingestion ingestion) {
        TweetStreamClient started =
                clientWith(CONSUMER_KEY, CONSUMER_SECRET, routes(stream), ingestion);
        started.start();
        return started;
    }

    /**
     * Reads the {@code value} of every rule the client registered, across every add batch — DL-254.
     *
     * @return the rule expressions, in the order the batches were sent
     */
    private List<String> allRegisteredValues() {
        return mutationBodies().stream()
                .filter(body -> body.contains("\"add\""))
                .flatMap(body -> membersOf(body, "\"value\":\"").stream())
                .toList();
    }

    /**
     * Counts the rules-mutation requests carrying {@code member}.
     *
     * @param member the member the body must carry
     * @return the number of such requests
     */
    private long mutationRequestsCarrying(String member) {
        return mutationBodies().stream()
                .filter(body -> body.contains("\"" + member + "\""))
                .count();
    }

    /**
     * Reads the {@code id} values of every delete mutation the client issued.
     *
     * @return the identifiers, in the order the batches were sent
     */
    private List<String> deletedRuleIds() {
        List<String> ids = new ArrayList<>();
        for (String body : mutationBodies()) {
            if (!body.contains("\"delete\"")) {
                continue;
            }
            int from = body.indexOf('[');
            int to = body.indexOf(']', from);
            if (from < 0 || to < 0) {
                continue;
            }
            for (String raw : body.substring(from + 1, to).split(",")) {
                String trimmed = raw.trim();
                if (trimmed.length() >= 2) {
                    ids.add(trimmed.substring(1, trimmed.length() - 1));
                }
            }
        }
        return ids;
    }

    /**
     * Builds a client whose control-plane calls carry the supplied bound — DL-230.
     *
     * @param timeoutSeconds value of {@code scanner.twitter.request-timeout-seconds}
     * @param exchange       the controlled exchange function
     * @return the client under test
     */
    private TweetStreamClient clientBoundedAt(long timeoutSeconds, ExchangeFunction exchange) {
        return new TweetStreamClient(webClient(exchange),
                properties(twitter(CONSUMER_KEY, CONSUMER_SECRET, timeoutSeconds),
                        ingestion(BASE_TERMS)),
                aiToolRepository, settingRepository, tweetStreamListener, heldLease());
    }

    /**
     * Wraps an exchange function in a recording {@link WebClient} rooted at {@value #BASE_URL}.
     *
     * @param exchange the controlled exchange function
     * @return the transport handed to the client
     */
    private WebClient webClient(ExchangeFunction exchange) {
        return WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeFunction(request -> {
                    exchanges.add(RecordedExchange.of(request));
                    return exchange.exchange(request);
                })
                .build();
    }

    /**
     * Answers the token and rules paths with usable bodies and the stream path with {@code stream}.
     *
     * @param stream the canned stream response
     * @return the exchange function
     */
    private static ExchangeFunction routes(ClientResponse stream) {
        return routesWithRegisteredRules("{\"data\":[]}", stream);
    }

    /**
     * Answers the rules listing with {@code registered} and the stream path with {@code stream}.
     *
     * @param registered body the rules listing answers with
     * @param stream     the canned stream response
     * @return the exchange function
     */
    private static ExchangeFunction routesWithRegisteredRules(String registered,
            ClientResponse stream) {
        return request -> {
            String path = request.url().getPath();
            if (path.equals(TOKEN_PATH)) {
                return Mono.just(json("{\"token_type\":\"bearer\",\"access_token\":\""
                        + TOKEN + "\"}"));
            }
            if (path.equals(RULES_PATH)) {
                return Mono.just(json(request.method() == HttpMethod.GET
                        ? registered
                        : "{\"meta\":{\"summary\":{\"created\":1}}}"));
            }
            return Mono.just(stream);
        };
    }

    /**
     * Renders the rules listing that already matches the composed base terms.
     *
     * @return a rules-listing body carrying one rule per configured base term
     */
    private static String registeredRulesBody() {
        String rules = BASE_TERMS.stream()
                .map(term -> "{\"id\":\"" + Math.abs(term.hashCode()) + "\",\"value\":\""
                        + (term.contains(" ") ? "\\\"" + term + "\\\"" : term) + "\",\"tag\":\""
                        + term + "\"}")
                .collect(Collectors.joining(","));
        return "{\"data\":[" + rules + "]}";
    }

    /**
     * Builds a JSON response carrying {@code body}.
     *
     * @param body the response body
     * @return the canned response
     */
    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /**
     * Builds a bare status response.
     *
     * @param status the status to answer with
     * @return the canned response
     */
    private static ClientResponse status(HttpStatus status) {
        return ClientResponse.create(status).build();
    }

    /**
     * Builds a {@code 429} response carrying a reset header.
     *
     * @param resetHeader value of {@code x-rate-limit-reset}
     * @return the canned response
     */
    /**
     * Invokes the delay computation of a rate-limit answer directly.
     *
     * @param resetHeader the reset header value to present, or {@code null} to present none
     * @param backoff the delay the caller computed
     * @return the delay the client applies
     * @throws Exception when the declared method cannot be invoked
     */
    private static Duration rateLimitDelayFor(String resetHeader, Duration backoff)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (resetHeader != null) {
            headers.add("x-rate-limit-reset", resetHeader);
        }
        WebClientResponseException failure = new WebClientResponseException(
                HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", headers,
                new byte[0], StandardCharsets.UTF_8);
        Method method = TweetStreamClient.class.getDeclaredMethod(
                "rateLimitDelay", WebClientResponseException.class, Duration.class);
        method.setAccessible(true);
        return (Duration) method.invoke(null, failure, backoff);
    }

    private static ClientResponse rateLimited(String resetHeader) {
        return ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("x-rate-limit-reset", resetHeader)
                .build();
    }

    /**
     * Builds a stream response delivering {@code body} as one chunk.
     *
     * @param body the newline-delimited body
     * @return the canned response
     */
    private static ClientResponse streamOf(String body) {
        return body.isEmpty() ? byteChunks() : chunks(body);
    }

    /**
     * Builds a stream response delivering each argument as its own chunk.
     *
     * @param parts the chunks, in order
     * @return the canned response
     */
    private static ClientResponse chunks(String... parts) {
        byte[][] bytes = new byte[parts.length][];
        for (int index = 0; index < parts.length; index++) {
            bytes[index] = parts[index].getBytes(StandardCharsets.UTF_8);
        }
        return byteChunks(bytes);
    }

    /**
     * Builds a stream response that emits {@code body} on a non-blocking thread, as a connection
     * thread does.
     *
     * @param body            the newline-delimited body
     * @param emittingThreads collects the name of the thread each chunk is emitted on
     * @return the canned response
     */
    private static ClientResponse streamEmittedOnANonBlockingThread(String body,
            List<String> emittingThreads) {
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Flux.just(buffer)
                        .publishOn(Schedulers.parallel())
                        .doOnNext(chunk -> emittingThreads.add(Thread.currentThread().getName())))
                .build();
    }

    /**
     * Builds a stream response delivering one record per chunk, counting each emitted chunk — DL-258.
     *
     * @param records number of records to deliver
     * @param emitted counter raised once per emitted chunk
     * @return the canned response
     */
    private static ClientResponse countedChunks(int records, AtomicInteger emitted) {
        List<DataBuffer> buffers = new ArrayList<>(records);
        for (int index = 1; index <= records; index++) {
            String record = "{\"data\":{\"text\":\"record " + index + "\"}}\n";
            buffers.add(DefaultDataBufferFactory.sharedInstance
                    .wrap(record.getBytes(StandardCharsets.UTF_8)));
        }
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Flux.fromIterable(buffers).doOnNext(chunk -> emitted.incrementAndGet()))
                .build();
    }

    /**
     * Builds a stream response that opens and then delivers nothing at all — DL-256.
     *
     * @return the canned response
     */
    private static ClientResponse silentStream() {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Flux.never())
                .build();
    }

    /**
     * Builds a stream response that delivers a blank keep-alive record twice a second — DL-256.
     *
     * @return the canned response
     */
    private static ClientResponse keepAliveStream() {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Flux.interval(Duration.ofMillis(500))
                        .map(tick -> DefaultDataBufferFactory.sharedInstance
                                .wrap("\r\n".getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    /**
     * Attaches a recording appender to the logger of the class under test.
     *
     * @return the attached appender
     */
    /**
     * Waits for at least one {@code WARN} record whose formatted message carries {@code fragment}.
     *
     * @param recorded  the attached appender
     * @param fragment  the fragment to wait for
     * @return every matching formatted message, in the order recorded
     */
    private static List<String> awaitWarningsContaining(ListAppender<ILoggingEvent> recorded,
            String fragment) {
        awaitCondition(() -> recorded.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains(fragment)));
        return recorded.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(fragment))
                .toList();
    }

    private static ListAppender<ILoggingEvent> attachClientAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TweetStreamClient.class)).addAppender(appender);
        return appender;
    }

    /**
     * Detaches a recording appender from the logger of the class under test.
     *
     * @param appender the appender to detach
     */
    private static void detachClientAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TweetStreamClient.class)).detachAppender(appender);
        appender.stop();
    }

    /**
     * Builds a stream response delivering each byte array as its own chunk.
     *
     * @param parts the chunks, in order
     * @return the canned response
     */
    private static ClientResponse byteChunks(byte[]... parts) {
        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
        List<DataBuffer> buffers = new ArrayList<>(parts.length);
        for (byte[] part : parts) {
            buffers.add(factory.wrap(part));
        }
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Flux.fromIterable(buffers))
                .build();
    }

    /**
     * Builds a {@code scanner} root carrying the supplied groups.
     *
     * @param twitter   the {@code scanner.twitter} group, possibly {@code null}
     * @param ingestion the {@code scanner.ingestion} group, possibly {@code null}
     * @return the bound root
     */
    private static ScannerProperties properties(ScannerProperties.Twitter twitter,
            ScannerProperties.Ingestion ingestion) {
        return new ScannerProperties(null, 100, 60L, twitter, null, null, null, null, null,
                ingestion, null);
    }

    /**
     * Builds a lease reporting this process as the background owner — DL-281.
     *
     * @return the lease every client of this class is constructed with
     */
    private static BackgroundOwnership heldLease() {
        BackgroundOwnership lease = org.mockito.Mockito.mock(BackgroundOwnership.class);
        when(lease.isOwner()).thenReturn(true);
        return lease;
    }

    /**
     * Builds a {@code scanner.twitter} group carrying the supplied consumer credentials.
     *
     * @param consumerKey    value of {@code consumer-key}
     * @param consumerSecret value of {@code consumer-secret}
     * @return the group
     */
    private static ScannerProperties.Twitter twitter(String consumerKey, String consumerSecret) {
        return twitter(consumerKey, consumerSecret, CONTROL_PLANE_TIMEOUT_SECONDS);
    }

    /**
     * Builds a {@code scanner.twitter} group carrying the supplied consumer credentials and the
     * supplied control-plane bound.
     *
     * @param consumerKey    value of {@code consumer-key}
     * @param consumerSecret value of {@code consumer-secret}
     * @param timeoutSeconds value of {@code request-timeout-seconds}
     * @return the group
     */
    private static ScannerProperties.Twitter twitter(String consumerKey, String consumerSecret,
            long timeoutSeconds) {
        return new ScannerProperties.Twitter("api-key", "api-secret", "api-secret-key",
                consumerKey, consumerSecret, "access-token", "access-token-secret",
                timeoutSeconds);
    }

    /**
     * Builds a {@code scanner.ingestion} group carrying the supplied base terms.
     *
     * @param terms value of {@code stream-base-keywords}
     * @return the group
     */
    private static ScannerProperties.Ingestion ingestion(List<String> terms) {
        return new ScannerProperties.Ingestion(terms, MAX_STREAM_RULES, STREAM_IDLE_TIMEOUT_SECONDS);
    }

    /**
     * Builds a {@code settings} row carrying the supplied value.
     *
     * @param value value of {@code settings.value}, possibly {@code null}
     * @return the row
     */
    private static Setting setting(String value) {
        return new Setting(STREAM_KEYWORDS_KEY, value, "Tracked terms.");
    }

    /**
     * Joins two term sequences.
     *
     * @param first  the first sequence
     * @param second the second sequence
     * @return the elements of both, in order
     */
    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return Collections.unmodifiableList(all);
    }

    // -----------------------------------------------------------------------
    // Assertions over the recorded exchanges
    // -----------------------------------------------------------------------

    /**
     * Waits for the first exchange against {@code path} and returns it.
     *
     * @param path the path to wait for
     * @return the first recorded exchange against {@code path}
     */
    private RecordedExchange awaitExchange(String path) {
        awaitCondition(() -> exchanges.stream()
                .anyMatch(exchange -> exchange.path().equals(path)));
        return exchanges.stream()
                .filter(exchange -> exchange.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Waits until the listener has been handed {@code expected} records and returns them.
     *
     * @param expected the number of records to wait for
     * @return the records the listener was handed, in order
     */
    private List<JsonNode> awaitDelivery(int expected) {
        awaitCondition(() -> deliveredRecords().size() >= expected);
        return deliveredRecords();
    }

    /**
     * Reads the records the listener was handed.
     *
     * @return the records, in order
     */
    private List<JsonNode> deliveredRecords() {
        org.mockito.ArgumentCaptor<JsonNode> captor =
                org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        org.mockito.Mockito.verify(tweetStreamListener, org.mockito.Mockito.atLeast(0))
                .onStatus(captor.capture(), anyInt());
        return List.copyOf(captor.getAllValues());
    }

    /**
     * Reads the {@code value} of every rule the client registered.
     *
     * @return the rule expressions, in order
     */
    private List<String> registeredValues() {
        return ruleMembers("\"value\":\"");
    }

    /**
     * Reads the {@code tag} of every rule the client registered.
     *
     * @return the rule tags, in order
     */
    private List<String> registeredTags() {
        return ruleMembers("\"tag\":\"");
    }

    /**
     * Reads one member of every rule the client registered.
     *
     * @param marker the member prefix to read after
     * @return the member values, in order
     */
    private List<String> ruleMembers(String marker) {
        awaitCondition(() -> !mutationBodyContaining("add").isEmpty());
        return membersOf(mutationBodyContaining("add"), marker);
    }

    /**
     * Reads one member of every rule a mutation body carries.
     *
     * @param body   the mutation body
     * @param marker the member prefix to read after
     * @return the member values, in order
     */
    private static List<String> membersOf(String body, String marker) {
        List<String> values = new ArrayList<>();
        int index = body.indexOf(marker);
        while (index >= 0) {
            int start = index + marker.length();
            int end = body.indexOf('"', start);
            while (end > start && body.charAt(end - 1) == '\\') {
                end = body.indexOf('"', end + 1);
            }
            values.add(body.substring(start, end).replace("\\\"", "\""));
            index = body.indexOf(marker, end);
        }
        return values;
    }

    /**
     * Reads the first rules-mutation body carrying {@code member}.
     *
     * @param member the member the body must carry
     * @return the body, or an empty string when no such mutation was issued
     */
    private String mutationBodyContaining(String member) {
        return mutationBodies().stream()
                .filter(body -> body.contains("\"" + member + "\""))
                .findFirst()
                .orElse("");
    }

    /**
     * Reads every rules-mutation body the client issued.
     *
     * @return the bodies, in order
     */
    private List<String> mutationBodies() {
        return exchanges.stream()
                .filter(exchange -> exchange.path().equals(RULES_PATH))
                .filter(exchange -> exchange.method() == HttpMethod.POST)
                .map(RecordedExchange::body)
                .toList();
    }

    /**
     * Waits until {@code condition} holds, for at most {@link #WAIT}.
     *
     * @param condition the condition to wait for
     */
    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(25));
        }
        assertThat(condition.getAsBoolean()).as("the awaited condition held within %s", WAIT)
                .isTrue();
    }

    /**
     * Sleeps for the supplied duration.
     *
     * @param duration the duration to sleep for
     */
    private static void sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The wait was interrupted.", interrupted);
        }
    }

    /**
     * One exchange the client issued, captured before the canned response is produced.
     *
     * @param method        the request method
     * @param path          the request path
     * @param query         the request query, or an empty string
     * @param authorization the {@code Authorization} header value, or an empty string
     * @param contentType   the {@code Content-Type} header value, or an empty string
     * @param headerNames   the names of every header the request carried
     * @param body          the request body as written, or an empty string
     */
    private record RecordedExchange(HttpMethod method, String path, String query,
            String authorization, String contentType, List<String> headerNames, String body) {

        /**
         * Captures one request.
         *
         * @param request the request as issued
         * @return the captured exchange
         */
        static RecordedExchange of(ClientRequest request) {
            MockClientHttpRequest written =
                    new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(written, org.springframework.web.reactive.function.client
                    .ExchangeStrategies.withDefaults()).block(Duration.ofSeconds(5));
            String body = written.getBodyAsString().block(Duration.ofSeconds(5));
            HttpHeaders headers = request.headers();
            return new RecordedExchange(request.method(),
                    request.url().getPath(),
                    request.url().getQuery() == null ? "" : request.url().getQuery(),
                    headers.getFirst(HttpHeaders.AUTHORIZATION) == null
                            ? "" : headers.getFirst(HttpHeaders.AUTHORIZATION),
                    headers.getFirst(HttpHeaders.CONTENT_TYPE) == null
                            ? "" : headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    List.copyOf(headers.keySet()),
                    body == null ? "" : body);
        }
    }
}
