package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.AiTool;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.util.LogSafe;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Consumes the X API v2 filtered stream and hands every delivered record to
 * {@link TweetStreamListener}.
 *
 * <p>One connection cycle runs four steps in this order:
 *
 * <ol>
 *   <li>{@link #composeRuleTerms()} composes the rule terms.</li>
 *   <li>{@link #acquireBearerToken()} supplies the app-only bearer token, exchanging a new one when
 *       none is held.</li>
 *   <li>{@link #reconcileStreamRules(String, List)} brings the registered rules in line with the
 *       composed terms.</li>
 *   <li>{@link #consumeStream(String)} subscribes to the chunked newline-delimited body and calls
 *       {@link TweetStreamListener#onStatus(JsonNode)} once per complete record.</li>
 * </ol>
 *
 * <p>The whole cycle runs again on every reconnection. An edit to the {@code stream_keywords}
 * {@code settings} row and a new {@code ai_tools} row both take effect on the next connection, with
 * no redeployment.
 *
 * <p>This class reaches exactly four X paths: {@code POST /oauth2/token},
 * {@code GET /2/tweets/search/stream/rules}, {@code POST /2/tweets/search/stream/rules} and
 * {@code GET /2/tweets/search/stream}. It declares no other X path and no path that publishes or
 * replies to a post. The flowchart step {@code Q[Post Response to Twitter]} at
 * {@code documentation/Software Requirements Specifications (SRS).md:L235-237} is not implemented
 * here or anywhere else in this module.
 *
 * <p>The composed term collection is never empty when a connection is opened. When it resolves to
 * nothing the cycle records the condition at {@code ERROR}, opens no connection and leaves
 * {@link #isRunning()} reporting {@code false}.
 *
 * <p>{@link #start()} schedules the cycle on {@link Schedulers#boundedElastic()} and returns without
 * blocking, issuing no request on the calling thread and raising nothing. Every delivered record is
 * handed to the listener on {@link Schedulers#boundedElastic()} as well, so no work the listener
 * performs runs on a connection thread. Every failure inside the cycle is recorded and confined to the
 * reactive chain. {@link #stop()} disposes the subscription, which closes the connection.
 *
 * <p>{@link #start()} opens no connection at all when {@code scanner.twitter.consumer-key} or
 * {@code scanner.twitter.consumer-secret} is unset or blank; it records the condition at
 * {@code WARN} and leaves {@link #isRunning()} reporting {@code false}.
 *
 * <p>Reconnection is unbounded, matching the {@code "This function runs indefinitely"} contract
 * documented for the retired {@code start_tweet_stream} at
 * {@code documentation/Code Structure.md:L1426}. Each reconnection is recorded at {@code WARN} with
 * its attempt number and applied delay. An HTTP 429 delay is taken from the
 * {@code x-rate-limit-reset} response header, which carries UTC epoch seconds, and is never shorter
 * than the computed backoff. An HTTP 401 discards the held token, and the next attempt exchanges a
 * new one.
 *
 * <p>The class holds no rate limiter, throttle, token bucket, semaphore, cache, metric registry or
 * health indicator, and performs no de-duplication. It writes to no table: every per-record
 * side effect belongs to {@link TweetStreamListener}. It reads and writes no approval flag.
 *
 * <p>Collaborators arrive through the constructor and are held for the lifetime of the singleton.
 * The held token, the subscription handle and the two lifecycle flags are the only mutable state and
 * each is {@code volatile} or atomic. The lifecycle operations are safe to call from any thread.
 *
 * <p>No credential and no whole record body is written to the log. Decisions covering this file are
 * recorded in {@code docs/DECISION_LOG.md} DL-012, DL-044, DL-045, DL-046 and DL-052;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @see TweetStreamListener#onStatus(JsonNode)
 */
// Ported from start_tweet_stream() at backend/app/tasks/tweet_monitoring.py:L36-55 and
// stream_tweets() at backend/app/services/twitter_service.py:L16-23 (faithful port of intent) — see
// docs/DECISION_LOG.md
// X API v2 filtered stream over WebClient, replacing the v1.1 statuses/filter target of
// `Stream(...).filter(track=...)` at backend/app/tasks/tweet_monitoring.py:L45-55 — see
// docs/DECISION_LOG.md DL-012, DL-045
// The scaffolding annotations at backend/app/tasks/tweet_monitoring.py:L36-37 and
// backend/app/services/twitter_service.py:L16-18 are resolved by this implementation and are not
// carried forward — see docs/DECISION_LOG.md
@Component
public class TweetStreamClient implements SmartLifecycle {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    /** Records the lifecycle, each reconciliation, each reconnection and each skipped record. */
    private static final Logger log = LoggerFactory.getLogger(TweetStreamClient.class);

    /** Reads one complete newline-delimited record into a tree. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // X API v2 filtered stream — see docs/DECISION_LOG.md DL-045
    /** Path of the OAuth 2 client-credentials exchange, relative to the X API host root. */
    private static final String TOKEN_PATH = "/oauth2/token";

    /** Path that lists and mutates the registered filtered-stream rules. */
    private static final String STREAM_RULES_PATH = "/2/tweets/search/stream/rules";

    /** Path of the filtered stream itself. */
    private static final String STREAM_PATH = "/2/tweets/search/stream";

    // App-only bearer token — see docs/DECISION_LOG.md DL-046
    /** Form field name of the grant type. */
    private static final String GRANT_TYPE_PARAM = "grant_type";

    /** Grant type of the app-only exchange. */
    private static final String CLIENT_CREDENTIALS_GRANT = "client_credentials";

    /** Authorization scheme of the token exchange request. */
    private static final String BASIC_SCHEME = "Basic ";

    /** Authorization scheme of every request that carries the app-only token. */
    private static final String BEARER_SCHEME = "Bearer ";

    /** Only accepted value of the {@value #KEY_TOKEN_TYPE} member, compared without letter case. */
    private static final String BEARER_TOKEN_TYPE = "bearer";

    /** Token-exchange response member naming the kind of token returned. */
    private static final String KEY_TOKEN_TYPE = "token_type";

    /** Token-exchange response member carrying the token. */
    private static final String KEY_ACCESS_TOKEN = "access_token";

    /** Query parameter naming the post fields the listener reads. */
    private static final String TWEET_FIELDS_PARAM = "tweet.fields";

    /**
     * Post fields mapped by {@link TweetStreamListener} onto {@code tweets.created_at},
     * {@code tweets.like_count}, {@code tweets.quoted_tweet_id}, {@code tweets.media} and
     * {@code tweets.user_id}.
     */
    private static final String TWEET_FIELDS_VALUE =
            "created_at,public_metrics,referenced_tweets,attachments,author_id";

    /** Query parameter naming the expansions the listener reads. */
    private static final String EXPANSIONS_PARAM = "expansions";

    /** Expansions that resolve the author and the media references of a delivered post. */
    private static final String EXPANSIONS_VALUE = "author_id,attachments.media_keys";

    /** Response header carrying the UTC epoch second at which a rate-limit window resets. */
    private static final String RATE_LIMIT_RESET_HEADER = "x-rate-limit-reset";

    // Rule set: configured base terms union ai_tools.name, overridable by the stream_keywords
    // setting row — see docs/DECISION_LOG.md DL-044
    /** {@code settings} row whose value replaces the whole composed term collection. */
    private static final String STREAM_KEYWORDS_SETTING_KEY = "stream_keywords";

    /** Separator of the terms held in the {@value #STREAM_KEYWORDS_SETTING_KEY} row. */
    private static final String TERM_DELIMITER = ",";

    /** Property naming the base terms, reported when the composed collection resolves to nothing. */
    private static final String STREAM_BASE_KEYWORDS_PROPERTY =
            "scanner.ingestion.stream-base-keywords";

    /** Property naming the app-only consumer key, reported when it is unset or blank. */
    private static final String CONSUMER_KEY_PROPERTY = "scanner.twitter.consumer-key";

    /** Property naming the app-only consumer secret, reported when it is unset or blank. */
    private static final String CONSUMER_SECRET_PROPERTY = "scanner.twitter.consumer-secret";

    /** Rules-response member carrying the registered rule array. */
    private static final String RULES_KEY_DATA = "data";

    /** Registered-rule member carrying the identifier a deletion names. */
    private static final String RULES_KEY_ID = "id";

    /** Rule member carrying the match expression, on which reconciliation compares. */
    private static final String RULES_KEY_VALUE = "value";

    /** Rule member carrying the term {@link TweetStreamListener} reads from a matched rule. */
    private static final String RULES_KEY_TAG = "tag";

    /** Rules-request member carrying the rules to register. */
    private static final String RULES_KEY_ADD = "add";

    /** Rules-request member carrying the deletion instruction. */
    private static final String RULES_KEY_DELETE = "delete";

    /** Deletion member carrying the identifiers to remove. */
    private static final String RULES_KEY_IDS = "ids";

    /** Term count from which a registration is reported at {@code WARN} with its terms. */
    private static final int LARGE_RULE_SET_THRESHOLD = 25;

    /** Byte on which the response body is split into records. */
    private static final byte LINE_FEED = (byte) '\n';

    // Bound on the bytes held for one record — see docs/DECISION_LOG.md DL-222
    /**
     * Most bytes the accumulator holds for a single record. A record that reaches this size without a
     * terminating {@value #LINE_FEED} is discarded rather than accumulated further; the connection
     * stays open. One post of the X API v2 filtered stream, with the requested fields and expansions,
     * is orders of magnitude smaller.
     */
    private static final int MAX_RECORD_BYTES = 1_048_576;

    /** Shortest delay applied before a reconnection. */
    private static final Duration MIN_RECONNECT_BACKOFF = Duration.ofSeconds(5L);

    /** Longest delay the computed backoff reaches. */
    private static final Duration MAX_RECONNECT_BACKOFF = Duration.ofMinutes(5L);

    /** Highest doubling applied to {@link #MIN_RECONNECT_BACKOFF}. */
    private static final int MAX_BACKOFF_EXPONENT = 8;

    /** Fraction of the computed backoff the applied delay varies by, in either direction. */
    private static final double BACKOFF_JITTER_FACTOR = 0.5D;

    /**
     * Shortest bound applied to a control-plane call, in seconds. A configured
     * {@code scanner.twitter.request-timeout-seconds} below this is read as this — DL-230.
     */
    private static final long MINIMUM_REQUEST_TIMEOUT_SECONDS = 1L;

    /** Transport for the four X paths, published by {@code config/WebClientConfig}. */
    private final WebClient webClient;

    /** Source of the app-only credentials and of the configured base terms. */
    private final ScannerProperties properties;

    /** Supplies the {@code ai_tools} rows whose names join the composed term collection. */
    private final AiToolRepository aiToolRepository;

    /** Supplies the {@value #STREAM_KEYWORDS_SETTING_KEY} row that overrides the composition. */
    private final SettingRepository settingRepository;

    /** Handles one delivered record. */
    private final TweetStreamListener tweetStreamListener;

    /** Reports whether {@link #start()} has taken effect and the cycle has not yet terminated. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Handle of the subscribed cycle, {@code null} until {@link #start()} subscribes. */
    private volatile Disposable subscription;

    /** App-only bearer token held between requests, {@code null} when none is held. */
    private volatile String bearerToken;

    /** Set when {@link #stop()} runs or the listener reports that streaming should stop. */
    private volatile boolean stopRequested;

    /**
     * Binds the five collaborators one connection cycle uses.
     *
     * @param webClient transport rooted at the X API host, must not be {@code null}
     * @param properties bound configuration carrying the app-only credentials and the base terms,
     *     must not be {@code null}
     * @param aiToolRepository data access for the {@code ai_tools} table, must not be {@code null}
     * @param settingRepository data access for the {@code settings} table, must not be {@code null}
     * @param tweetStreamListener handler of one delivered record, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Constructor injection replaces the in-function TwitterService() and SentimentAnalysis()
    // instantiation at backend/app/tasks/tweet_monitoring.py:L40-41 and the get_settings() call at
    // :L39 — see docs/DECISION_LOG.md
    public TweetStreamClient(WebClient webClient,
            ScannerProperties properties,
            AiToolRepository aiToolRepository,
            SettingRepository settingRepository,
            TweetStreamListener tweetStreamListener) {
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.aiToolRepository =
                Objects.requireNonNull(aiToolRepository, "aiToolRepository must not be null.");
        this.settingRepository =
                Objects.requireNonNull(settingRepository, "settingRepository must not be null.");
        this.tweetStreamListener =
                Objects.requireNonNull(tweetStreamListener, "tweetStreamListener must not be null.");
    }

    /**
     * Schedules the ingestion cycle and returns.
     *
     * <p>The method reads {@code scanner.twitter.consumer-key} and
     * {@code scanner.twitter.consumer-secret} first. When either is unset or blank it records both
     * property names at {@code WARN}, contacts X in no way and returns with {@link #isRunning()}
     * reporting {@code false}.
     *
     * <p>Otherwise the cycle is subscribed on {@link Schedulers#boundedElastic()} and the method
     * returns at once: no request is issued on the calling thread and no reactive result is awaited.
     * A second call made while the cycle is already scheduled has no effect.
     *
     * <p>The method raises nothing. Every failure the cycle produces is recorded inside the reactive
     * chain.
     */
    // Replaces the blocking `stream.filter(track=keywords)` call at
    // backend/app/tasks/tweet_monitoring.py:L55, which was invoked synchronously from
    // initialize_background_tasks() at backend/app/main.py:L45 and left
    // schedule_response_generation() at backend/app/main.py:L48 unreached — see
    // docs/DECISION_LOG.md
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        ScannerProperties.Twitter twitter = properties.twitter();
        String consumerKey = twitter == null ? null : twitter.consumerKey();
        String consumerSecret = twitter == null ? null : twitter.consumerSecret();

        // Blank-credential guard — see docs/DECISION_LOG.md DL-046
        if (isBlank(consumerKey) || isBlank(consumerSecret)) {
            running.set(false);
            log.warn("X filtered stream ingestion not started: both {} and {} must hold a value",
                    CONSUMER_KEY_PROPERTY, CONSUMER_SECRET_PROPERTY);
            return;
        }

        stopRequested = false;
        bearerToken = null;
        log.info("Starting X filtered stream ingestion");

        subscription = ingestionCycle()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> {
                            // Mono<Void>: the cycle emits no element.
                        },
                        failure -> log.error("X filtered stream ingestion stopped after {}",
                                describe(failure)),
                        () -> log.info("X filtered stream ingestion completed"));
    }

    /**
     * Disposes the subscribed cycle, which closes the connection.
     *
     * <p>The held token is discarded and {@link #isRunning()} reports {@code false} once this method
     * returns. A call made when no cycle is subscribed records the shutdown and returns.
     */
    @Override
    public void stop() {
        stopRequested = true;
        running.set(false);

        Disposable current = this.subscription;
        this.subscription = null;
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
        this.bearerToken = null;

        log.info("Stopped X filtered stream ingestion");
    }

    /**
     * Reports the state of the subscribed cycle.
     *
     * @return {@code true} while a cycle is subscribed and has not terminated, {@code false} before
     *     {@link #start()} takes effect, after {@link #stop()}, after the blank-credential guard
     *     declines to start, and once the cycle has terminated for any reason
     */
    @Override
    public boolean isRunning() {
        Disposable current = this.subscription;
        return running.get() && current != null && !current.isDisposed();
    }

    /**
     * Assembles the cycle and its reconnection policy.
     *
     * @return a sequence that emits nothing, completes when the listener reports that streaming
     *     should stop or when the composed term collection resolves to nothing, and otherwise
     *     reconnects without bound
     */
    private Mono<Void> ingestionCycle() {
        return Mono.defer(this::runOneConnection)
                .retryWhen(reconnectPolicy())
                .doFinally(signal -> running.set(false));
    }

    /**
     * Runs the four steps of one connection cycle in order.
     *
     * <p>The term collection is composed on {@link Schedulers#boundedElastic()}, where the two
     * repository reads block. A collection that resolves to nothing is recorded at {@code ERROR}
     * naming {@value #STREAM_BASE_KEYWORDS_PROPERTY} and completes the cycle with no request issued.
     *
     * @return a sequence that completes when the listener reports that streaming should stop and
     *     fails when the connection ends for any other reason
     */
    private Mono<Void> runOneConnection() {
        return Mono.fromCallable(this::composeRuleTerms)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(terms -> {
                    if (terms.isEmpty()) {
                        log.error("X filtered stream ingestion not started: the composed rule term "
                                + "collection is empty. Declare at least one term under {}, add at "
                                + "least one ai_tools row, or set the `{}` settings row.",
                                STREAM_BASE_KEYWORDS_PROPERTY, STREAM_KEYWORDS_SETTING_KEY);
                        return Mono.empty();
                    }
                    return acquireBearerToken()
                            .flatMap(token -> reconcileStreamRules(token, terms).thenReturn(token))
                            .flatMap(this::consumeStream);
                });
    }

    // Closes the deferred keyword definition at backend/app/tasks/tweet_monitoring.py:L53 and
    // replaces the empty `keywords = []` list at :L54 — see docs/DECISION_LOG.md DL-044
    /**
     * Composes the terms the filtered stream matches on.
     *
     * <p>Composition follows one precedence order:
     *
     * <ol>
     *   <li>The {@value #STREAM_KEYWORDS_SETTING_KEY} {@code settings} row, when it is present and
     *       holds a non-blank value, is split on {@value #TERM_DELIMITER} and replaces the whole
     *       collection. An absent row and a blank value both fall through to the next step.</li>
     *   <li>Otherwise the configured {@value #STREAM_BASE_KEYWORDS_PROPERTY} terms are joined with
     *       the {@code name} of every {@code ai_tools} row.</li>
     *   <li>A collection that still resolves to nothing yields the configured base terms.</li>
     * </ol>
     *
     * <p>Every step trims each term, drops a {@code null} or blank term, and removes a repeat
     * without regard to letter case while keeping the order in which terms were first seen and the
     * letter case of the first occurrence.
     *
     * <p>The two repository reads block. This method runs only on
     * {@link Schedulers#boundedElastic()}.
     *
     * @return an ordered collection holding no repeat and no blank term; empty only when no term is
     *     configured, no {@code ai_tools} row holds a name and the override row holds nothing
     */
    private List<String> composeRuleTerms() {
        ScannerProperties.Ingestion ingestion = properties.ingestion();
        List<String> configuredTerms =
                distinctTerms(ingestion == null ? List.of() : ingestion.streamBaseKeywords());

        List<String> overrideTerms = readOverrideTerms();
        if (!overrideTerms.isEmpty()) {
            log.info("Stream rule terms taken from the `{}` settings row: {} term(s)",
                    STREAM_KEYWORDS_SETTING_KEY, overrideTerms.size());
            return overrideTerms;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        mergeTerms(merged, configuredTerms);
        mergeTerms(merged, readAiToolNames());
        List<String> composed = List.copyOf(merged.values());

        return composed.isEmpty() ? configuredTerms : composed;
    }

    /**
     * Reads the override terms held by the {@value #STREAM_KEYWORDS_SETTING_KEY} {@code settings}
     * row.
     *
     * <p>The row is seeded on startup by {@code service/SettingsService} and is editable through
     * {@code PUT /settings/{key}}. This method assumes nothing about the row being present.
     *
     * @return the terms the row holds, or an empty collection when the row is absent, holds
     *     {@code null}, holds a blank value or holds only blank terms
     */
    private List<String> readOverrideTerms() {
        Optional<Setting> row = settingRepository.findById(STREAM_KEYWORDS_SETTING_KEY);
        if (row.isEmpty()) {
            return List.of();
        }

        String stored = row.get().getValue();
        if (isBlank(stored)) {
            return List.of();
        }

        return distinctTerms(List.of(stored.split(TERM_DELIMITER)));
    }

    /**
     * Reads the {@code name} of every {@code ai_tools} row.
     *
     * @return the names held by the table, in the order the repository returns them, with a
     *     {@code null} or blank name omitted
     */
    private List<String> readAiToolNames() {
        List<AiTool> tools = aiToolRepository.findAll();
        List<String> names = new ArrayList<>(tools.size());
        for (AiTool tool : tools) {
            if (tool == null) {
                continue;
            }
            String name = tool.getName();
            if (!isBlank(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // X API v2 filtered stream rule reconciliation — see docs/DECISION_LOG.md DL-045
    /**
     * Brings the rules registered with X in line with the composed terms.
     *
     * <p>Three calls carry out the reconciliation: the registered rules are listed, the surplus is
     * deleted when at least one identifier is surplus, and the missing rules are added when at least
     * one is missing. A rule already registered with the same match expression is neither deleted nor
     * re-added, and both mutations are skipped when the two collections already agree. The dry-run
     * facility of the rules endpoint is not used.
     *
     * <p>Every request body is built from a {@link Map} and serialised by the transport, never
     * assembled as text. Each term becomes one rule whose {@value #RULES_KEY_TAG} is the term in its
     * unquoted form, which is the value {@link TweetStreamListener} reads from a matched rule.
     *
     * <p>A term count of {@value #LARGE_RULE_SET_THRESHOLD} or more is recorded at {@code WARN} with
     * the count and the terms. No partitioning, expression grouping or cap-specific retry is applied:
     * an account-tier rule cap surfaces as the API's own error through the reconnection path.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param terms the composed terms, must hold at least one term
     * @return a sequence that completes once the registered rules agree with {@code terms}
     */
    private Mono<Void> reconcileStreamRules(String token, List<String> terms) {
        Map<String, String> desired = new LinkedHashMap<>();
        for (String term : terms) {
            desired.put(ruleExpression(term), term);
        }

        if (terms.size() >= LARGE_RULE_SET_THRESHOLD) {
            log.warn("Registering {} stream rule term(s), which may exceed the rule cap of the "
                    + "account tier: {}", terms.size(), terms);
        }

        return listStreamRules(token)
                .flatMap(existing -> applyRuleDifference(token, desired, existing))
                .doOnError(failure -> log.error("Stream rule reconciliation failed after {}",
                        describe(failure)));
    }

    /**
     * Lists the rules currently registered with X.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @return the registered rules keyed by match expression with the identifier as value; empty
     *     when the endpoint reports no rule
     */
    private Mono<Map<String, String>> listStreamRules(String token) {
        return webClient.get()
                .uri(STREAM_RULES_PATH)
                .header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // Bounded control-plane call — DL-230 — see docs/DECISION_LOG.md
                .timeout(controlPlaneTimeout())
                .map(TweetStreamClient::readRegisteredRules)
                .defaultIfEmpty(Map.of());
    }

    /**
     * Deletes the surplus rules and adds the missing ones.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param desired the wanted rules keyed by match expression with the tag as value, must not be
     *     {@code null}
     * @param existing the registered rules keyed by match expression with the identifier as value,
     *     must not be {@code null}
     * @return a sequence that completes once both mutations have been applied or skipped
     */
    private Mono<Void> applyRuleDifference(String token,
            Map<String, String> desired,
            Map<String, String> existing) {
        List<String> surplusIds = new ArrayList<>();
        for (Map.Entry<String, String> registered : existing.entrySet()) {
            if (!desired.containsKey(registered.getKey())) {
                surplusIds.add(registered.getValue());
            }
        }

        List<Map<String, String>> missingRules = new ArrayList<>();
        for (Map.Entry<String, String> wanted : desired.entrySet()) {
            if (!existing.containsKey(wanted.getKey())) {
                missingRules.add(Map.of(
                        RULES_KEY_VALUE, wanted.getKey(),
                        RULES_KEY_TAG, wanted.getValue()));
            }
        }

        Mono<Void> deletion = surplusIds.isEmpty()
                ? Mono.empty()
                : mutateStreamRules(token, Map.of(
                        RULES_KEY_DELETE, Map.of(RULES_KEY_IDS, List.copyOf(surplusIds))));

        Mono<Void> addition = missingRules.isEmpty()
                ? Mono.empty()
                : mutateStreamRules(token, Map.of(RULES_KEY_ADD, List.copyOf(missingRules)));

        return deletion.then(addition)
                .doOnSuccess(ignored -> log.info("Stream rules reconciled: {} rule(s) registered, "
                        + "{} added, {} removed", desired.size(), missingRules.size(),
                        surplusIds.size()));
    }

    /**
     * Applies one rules mutation.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param body the mutation body, holding either {@value #RULES_KEY_ADD} or
     *     {@value #RULES_KEY_DELETE}, must not be {@code null}
     * @return a sequence that completes once the endpoint has answered
     */
    private Mono<Void> mutateStreamRules(String token, Map<String, Object> body) {
        return webClient.post()
                .uri(STREAM_RULES_PATH)
                .header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // Bounded control-plane call — DL-230 — see docs/DECISION_LOG.md
                .timeout(controlPlaneTimeout())
                .then();
    }

    /**
     * Reads the registered rules out of a rules-endpoint payload.
     *
     * @param payload the endpoint payload, may be {@code null}
     * @return the registered rules keyed by match expression with the identifier as value; empty
     *     when the payload is {@code null}, carries no {@value #RULES_KEY_DATA} array, or carries
     *     only entries missing an identifier or a match expression
     */
    private static Map<String, String> readRegisteredRules(JsonNode payload) {
        Map<String, String> registered = new LinkedHashMap<>();
        if (payload == null) {
            return registered;
        }

        JsonNode rules = payload.path(RULES_KEY_DATA);
        if (!rules.isArray()) {
            return registered;
        }

        for (JsonNode rule : rules) {
            String expression = rule.path(RULES_KEY_VALUE).asText("");
            String identifier = rule.path(RULES_KEY_ID).asText("");
            if (!expression.isBlank() && !identifier.isBlank()) {
                registered.put(expression, identifier);
            }
        }
        return registered;
    }

    // The bound applied to every short request/response call on the X API — DL-230 — see
    // docs/DECISION_LOG.md
    /**
     * Reports the bound applied to a control-plane call.
     *
     * <p>The value is {@code scanner.twitter.request-timeout-seconds}, read on every call so that a
     * configuration change needs no restart, and never shorter than
     * {@value #MINIMUM_REQUEST_TIMEOUT_SECONDS} second. The filtered-stream subscription is not
     * bounded by it.
     *
     * @return the bound for the app-only token exchange and the stream-rules calls, never
     *     {@code null} and never shorter than {@value #MINIMUM_REQUEST_TIMEOUT_SECONDS} second
     */
    private Duration controlPlaneTimeout() {
        long configured = properties.twitter().requestTimeoutSeconds();
        return Duration.ofSeconds(Math.max(configured, MINIMUM_REQUEST_TIMEOUT_SECONDS));
    }

    /**
     * Renders one term as an X rule match expression.
     *
     * @param term a trimmed, non-blank term, must not be {@code null}
     * @return the term wrapped in double quotes when it holds whitespace, and the term unchanged
     *     otherwise
     */
    private static String ruleExpression(String term) {
        return holdsWhitespace(term) ? '"' + term + '"' : term;
    }

    // App-only bearer token — see docs/DECISION_LOG.md DL-046
    /**
     * Supplies the app-only bearer token, exchanging a new one when none is held.
     *
     * <p>One token is held at a time. It is discarded by {@link #stop()} and by an HTTP 401 answer,
     * and the next call exchanges a replacement.
     *
     * @return a sequence carrying the token, failing when the exchange fails or when its answer
     *     cannot be verified
     */
    private Mono<String> acquireBearerToken() {
        return Mono.defer(() -> {
            String held = this.bearerToken;
            return isBlank(held) ? exchangeAppOnlyBearerToken() : Mono.just(held);
        });
    }

    /**
     * Exchanges {@code scanner.twitter.consumer-key} and {@code scanner.twitter.consumer-secret} for
     * an app-only bearer token.
     *
     * <p>The request is a {@code POST} to {@value #TOKEN_PATH} carrying
     * {@code grant_type=client_credentials} as form data, authorized with the Basic scheme over the
     * Base64 encoding of the two credentials joined by a colon after each half has been percent-encoded
     * per RFC 3986.
     *
     * <p>The answer is accepted only when its {@value #KEY_TOKEN_TYPE} member equals
     * {@value #BEARER_TOKEN_TYPE} without regard to letter case and its {@value #KEY_ACCESS_TOKEN}
     * member is non-blank. Any other answer is recorded at {@code ERROR} and fails the attempt with no
     * token held.
     *
     * <p>The two OAuth 1.0a user-context properties {@code scanner.twitter.access-token} and
     * {@code scanner.twitter.access-token-secret}, read by the retired code at
     * {@code backend/app/tasks/tweet_monitoring.py:L48-49}, take no part in this exchange or in any
     * request this class issues. No request is signed.
     *
     * <p>Neither credential nor the returned token is written to the log.
     *
     * @return a sequence carrying the verified token
     */
    private Mono<String> exchangeAppOnlyBearerToken() {
        ScannerProperties.Twitter twitter = properties.twitter();
        String joined = UriUtils.encode(twitter.consumerKey(), StandardCharsets.UTF_8)
                + ':'
                + UriUtils.encode(twitter.consumerSecret(), StandardCharsets.UTF_8);
        String credential =
                Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));

        return webClient.post()
                .uri(TOKEN_PATH)
                .header(HttpHeaders.AUTHORIZATION, BASIC_SCHEME + credential)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(GRANT_TYPE_PARAM, CLIENT_CREDENTIALS_GRANT))
                .retrieve()
                .bodyToMono(JsonNode.class)
                // Bounded control-plane call — DL-230 — see docs/DECISION_LOG.md
                .timeout(controlPlaneTimeout())
                .flatMap(TweetStreamClient::verifyTokenPayload)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("The X token endpoint answered with no body.")))
                .doOnNext(token -> {
                    this.bearerToken = token;
                    log.info("Acquired an app-only bearer token for the X API");
                })
                .doOnError(failure -> log.error("X app-only token exchange failed after {}",
                        describe(failure)));
    }

    /**
     * Verifies a token-exchange answer and extracts the token.
     *
     * @param payload the endpoint answer, must not be {@code null}
     * @return a sequence carrying the token, failing when {@value #KEY_TOKEN_TYPE} is not
     *     {@value #BEARER_TOKEN_TYPE} or {@value #KEY_ACCESS_TOKEN} is absent or blank
     */
    private static Mono<String> verifyTokenPayload(JsonNode payload) {
        String tokenType = payload.path(KEY_TOKEN_TYPE).asText("");
        if (!BEARER_TOKEN_TYPE.equalsIgnoreCase(tokenType)) {
            log.error("The X token endpoint answered with an unexpected {}; the answer is discarded "
                    + "and no token is held", KEY_TOKEN_TYPE);
            return Mono.error(new IllegalStateException(
                    "The X token endpoint answered with an unexpected " + KEY_TOKEN_TYPE + "."));
        }

        String accessToken = payload.path(KEY_ACCESS_TOKEN).asText("");
        if (accessToken.isBlank()) {
            log.error("The X token endpoint answered with no {}", KEY_ACCESS_TOKEN);
            return Mono.error(new IllegalStateException(
                    "The X token endpoint answered with no " + KEY_ACCESS_TOKEN + "."));
        }

        return Mono.just(accessToken);
    }

    /**
     * Consumes the filtered stream until the listener reports that streaming should stop or the
     * connection ends.
     *
     * <p>Records are handed to {@link #dispatchRecord(String)} on {@link Schedulers#boundedElastic()},
     * not on the thread the response body is emitted on, so the listener's persistence and external
     * calls never run on a connection thread. Delivery stays in arrival order on a single worker, and
     * the connection thread is free to keep reading while a record is being handled.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @return a sequence that completes when the listener reports that streaming should stop and
     *     fails when the connection ends for any other reason
     */
    // Per-record dispatch runs on a blocking-capable scheduler, not on the connection thread — see
    // docs/DECISION_LOG.md DL-220
    private Mono<Void> consumeStream(String token) {
        return streamRecords(token)
                .publishOn(Schedulers.boundedElastic())
                .map(this::dispatchRecord)
                .takeWhile(Boolean::booleanValue)
                .then(Mono.defer(() -> stopRequested
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException(
                                "The X filtered stream connection ended."))));
    }

    /**
     * Subscribes to the filtered stream and emits one complete record at a time.
     *
     * <p>The response body is chunked newline-delimited JSON and a chunk boundary does not fall on a
     * record boundary. Bytes are accumulated across chunks and a record is emitted only once its
     * terminating line feed has arrived. A record split across two or more chunks arrives whole, and a
     * multi-byte character straddling a chunk boundary stays intact. One accumulator is created per
     * subscription. A reconnection carries no partial record forward.
     *
     * <p>The accumulator holds at most {@value #MAX_RECORD_BYTES} bytes for one record. A record that
     * grows past that without a terminating line feed is discarded, recorded at {@code WARN} once, and
     * the bytes up to the next line feed are dropped with it; the connection stays open and the next
     * record is delivered normally.
     *
     * <p>The request carries no response timeout, no read timeout and no reduced codec buffer limit: a
     * filtered-stream connection is long-lived by design and the bound of
     * {@code scanner.twitter.request-timeout-seconds} applies only to the token exchange and the
     * stream-rules calls — DL-230.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @return the complete records the connection delivers, including the blank keep-alive records X
     *     sends periodically
     */
    private Flux<String> streamRecords(String token) {
        return Flux.defer(() -> {
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            AtomicBoolean discarding = new AtomicBoolean(false);
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(STREAM_PATH)
                            .queryParam(TWEET_FIELDS_PARAM, TWEET_FIELDS_VALUE)
                            .queryParam(EXPANSIONS_PARAM, EXPANSIONS_VALUE)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + token)
                    .exchangeToFlux(TweetStreamClient::openStreamBody)
                    .concatMap(chunk ->
                            Flux.fromIterable(drainCompleteRecords(chunk, pending, discarding)));
        });
    }

    /**
     * Reports the answer of the stream request and opens its body.
     *
     * @param response the stream answer, must not be {@code null}
     * @return the body chunks of a successful answer, and a failure carrying the status and headers
     *     of an unsuccessful one
     */
    private static Flux<DataBuffer> openStreamBody(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.createException().flatMapMany(Mono::error);
        }
        log.info("X filtered stream connection established with status {}",
                response.statusCode().value());
        return response.bodyToFlux(DataBuffer.class);
    }

    /**
     * Accumulates one body chunk and returns every record it completes.
     *
     * <p>The chunk is drained and released, its bytes are appended to {@code pending}, and a record is
     * cut at every {@value #LINE_FEED} byte. Bytes following the final line feed stay in
     * {@code pending} for the next chunk.
     *
     * <p>A record whose accumulated bytes reach {@value #MAX_RECORD_BYTES} is discarded: the
     * accumulator is emptied, the condition is recorded once at {@code WARN}, and {@code discarding} is
     * raised so that the remaining bytes of that record are dropped up to and including its next line
     * feed. Accumulation of the following record then resumes normally and the connection is never
     * ended.
     *
     * @param chunk one body chunk, released before this method returns; must not be {@code null}
     * @param pending the accumulator holding the bytes of the record in progress, must not be
     *     {@code null}
     * @param discarding raised while the remainder of an over-long record is being dropped, must not be
     *     {@code null}
     * @return the records the chunk completed, in arrival order; empty when the chunk completed none
     */
    // Bounded accumulation: log and skip, never end the connection — see docs/DECISION_LOG.md DL-222
    private static List<String> drainCompleteRecords(DataBuffer chunk,
            ByteArrayOutputStream pending, AtomicBoolean discarding) {
        byte[] bytes;
        try {
            bytes = new byte[chunk.readableByteCount()];
            chunk.read(bytes);
        } finally {
            DataBufferUtils.release(chunk);
        }

        List<String> records = new ArrayList<>();
        for (byte value : bytes) {
            if (discarding.get()) {
                if (value == LINE_FEED) {
                    discarding.set(false);
                }
                continue;
            }
            if (value == LINE_FEED) {
                records.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else {
                pending.write(value);
                if (pending.size() >= MAX_RECORD_BYTES) {
                    // Neither the accumulated bytes nor any part of them is written: the byte count
                    // and the bound only — see docs/DECISION_LOG.md DL-222
                    log.warn("Skipping an X filtered stream record that reached {} byte(s) with no "
                            + "line feed; the bound is {} byte(s) and the bytes up to the next line "
                            + "feed are dropped", pending.size(), MAX_RECORD_BYTES);
                    pending.reset();
                    discarding.set(true);
                }
            }
        }
        return records;
    }

    /**
     * Hands one complete record to {@link TweetStreamListener#onStatus(JsonNode)}.
     *
     * <p>A blank record is a keep-alive: it is discarded without being parsed and without a log event.
     * A record that cannot be read as JSON is recorded at {@code WARN} as a correlation token and the
     * failure's type, never as its content, and skipped. A listener failure is recorded at
     * {@code ERROR} and skipped. Neither ends the connection.
     *
     * @param record one complete record, may be {@code null}
     * @return {@code true} to keep the connection open, and {@code false} once the listener has
     *     reported that streaming should stop
     */
    private boolean dispatchRecord(String record) {
        String candidate = record == null ? "" : record.trim();
        if (candidate.isEmpty()) {
            return true;
        }

        JsonNode payload;
        try {
            payload = OBJECT_MAPPER.readTree(candidate);
        } catch (JsonProcessingException failure) {
            // Neither the record nor the parse failure's message is written: a non-reversible
            // correlation token and the failure's type only — DL-149 — see docs/DECISION_LOG.md
            log.warn("Skipping an unreadable X filtered stream record; record {}: {}",
                    LogSafe.correlation(candidate), LogSafe.type(failure));
            return true;
        }

        try {
            // Honours the documented contract of on_status at
            // documentation/Code Structure.md:L1393 — see docs/DECISION_LOG.md
            boolean keepStreaming = tweetStreamListener.onStatus(payload);
            if (!keepStreaming) {
                stopRequested = true;
                log.info("Closing the X filtered stream connection: the listener reported that "
                        + "streaming should stop");
            }
            return keepStreaming;
        } catch (RuntimeException failure) {
            // The listener failure can quote a value it was handed, so only its type is recorded —
            // DL-149 — see docs/DECISION_LOG.md
            log.error("Skipping an X filtered stream record: the listener failed after {}",
                    LogSafe.type(failure));
            return true;
        }
    }

    // Reconnection with exponential backoff, one WARN per reconnection — see
    // docs/DECISION_LOG.md DL-045, DL-052
    /**
     * Builds the reconnection policy.
     *
     * <p>Retries are unbounded. Each reconnection is recorded at {@code WARN} with its attempt number,
     * the delay applied and the failure that triggered it. The delay comes from
     * {@link #reconnectDelay(Throwable, long)}. Once {@link #stop()} has run, the cycle terminates on
     * the next failure and no further attempt is made.
     *
     * <p>The delay throttles nothing this application sends of its own accord; it governs only when a
     * dropped connection is re-established.
     *
     * @return a policy that re-subscribes the cycle after the computed delay
     */
    private Retry reconnectPolicy() {
        return Retry.from(signals -> signals
                .takeWhile(signal -> !stopRequested && running.get())
                .concatMap(signal -> {
                    Throwable failure = signal.failure();
                    long attempt = signal.totalRetries() + 1L;
                    Duration delay = reconnectDelay(failure, attempt);

                    log.warn("Reconnecting to the X filtered stream in {} ms (attempt {}) after {}",
                            delay.toMillis(), attempt, describe(failure));
                    return Mono.delay(delay);
                }));
    }

    /**
     * Computes the delay to apply before one reconnection.
     *
     * <p>An HTTP 429 answer yields the delay to the instant named by the
     * {@value #RATE_LIMIT_RESET_HEADER} header, never shorter than the computed backoff. An HTTP 401
     * answer discards the held token and yields the computed backoff; the next attempt exchanges a new
     * token. Every other failure yields the computed backoff.
     *
     * @param failure the failure that ended the previous attempt, may be {@code null}
     * @param attempt the one-based attempt number
     * @return the delay to apply, never shorter than {@link #MIN_RECONNECT_BACKOFF}
     */
    private Duration reconnectDelay(Throwable failure, long attempt) {
        Duration backoff = exponentialBackoff(attempt);
        if (!(failure instanceof WebClientResponseException response)) {
            return backoff;
        }

        int status = response.getStatusCode().value();
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return rateLimitDelay(response, backoff);
        }
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            this.bearerToken = null;
            log.warn("The X API rejected the app-only bearer token; the held token is discarded and "
                    + "the next attempt exchanges a new one");
        }
        return backoff;
    }

    /**
     * Reads the rate-limit reset instant out of an HTTP 429 answer.
     *
     * @param response the HTTP 429 answer, must not be {@code null}
     * @param backoff the computed backoff, must not be {@code null}
     * @return the longer of the delay until the reset instant and {@code backoff}; {@code backoff}
     *     when the header is absent, blank or unreadable as UTC epoch seconds
     */
    private static Duration rateLimitDelay(WebClientResponseException response, Duration backoff) {
        String header = response.getHeaders().getFirst(RATE_LIMIT_RESET_HEADER);
        if (isBlank(header)) {
            log.warn("The X API reported a rate limit without a {} header; the computed backoff "
                    + "delay applies", RATE_LIMIT_RESET_HEADER);
            return backoff;
        }

        Duration untilReset;
        try {
            Instant reset = Instant.ofEpochSecond(Long.parseLong(header.trim()));
            untilReset = Duration.between(Instant.now(), reset);
        } catch (ArithmeticException | NumberFormatException failure) {
            log.warn("The X API {} header could not be read as UTC epoch seconds; the computed "
                    + "backoff delay applies", RATE_LIMIT_RESET_HEADER);
            return backoff;
        }

        return untilReset.compareTo(backoff) > 0 ? untilReset : backoff;
    }

    /**
     * Doubles {@link #MIN_RECONNECT_BACKOFF} once per elapsed attempt, caps the result at
     * {@link #MAX_RECONNECT_BACKOFF} and varies it by {@value #BACKOFF_JITTER_FACTOR} of itself in
     * either direction.
     *
     * @param attempt the one-based attempt number
     * @return a delay within {@link #MIN_RECONNECT_BACKOFF} and {@link #MAX_RECONNECT_BACKOFF}
     *     inclusive
     */
    private static Duration exponentialBackoff(long attempt) {
        long elapsed = Math.max(attempt - 1L, 0L);
        int exponent = (int) Math.min(elapsed, MAX_BACKOFF_EXPONENT);

        long floorMillis = MIN_RECONNECT_BACKOFF.toMillis();
        long ceilingMillis = MAX_RECONNECT_BACKOFF.toMillis();
        long baseMillis = Math.min(ceilingMillis, floorMillis << exponent);

        long spread = (long) (baseMillis * BACKOFF_JITTER_FACTOR);
        long jittered = spread <= 0L
                ? baseMillis
                : baseMillis - spread + ThreadLocalRandom.current().nextLong((2L * spread) + 1L);

        return Duration.ofMillis(Math.min(ceilingMillis, Math.max(floorMillis, jittered)));
    }

    /**
     * Removes repeats and blanks from a term sequence.
     *
     * @param terms the terms to normalise, must not be {@code null}; individual elements may be
     *     {@code null}
     * @return the trimmed, non-blank terms in first-seen order, holding one entry per term compared
     *     without regard to letter case
     */
    private static List<String> distinctTerms(List<String> terms) {
        Map<String, String> distinct = new LinkedHashMap<>();
        mergeTerms(distinct, terms);
        return List.copyOf(distinct.values());
    }

    /**
     * Adds every usable term of {@code terms} to {@code target}.
     *
     * @param target accumulator keyed by the lower-cased term with the trimmed term as value, must
     *     not be {@code null}
     * @param terms the terms to add, must not be {@code null}; individual elements may be
     *     {@code null}
     */
    private static void mergeTerms(Map<String, String> target, List<String> terms) {
        for (String term : terms) {
            if (term == null) {
                continue;
            }
            String trimmed = term.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            target.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
    }

    /**
     * Reports whether a term holds a whitespace character.
     *
     * @param term the term to inspect, must not be {@code null}
     * @return {@code true} when at least one character is whitespace
     */
    private static boolean holdsWhitespace(String term) {
        return term.chars().anyMatch(Character::isWhitespace);
    }

    /**
     * Reports whether a value is absent or holds only whitespace.
     *
     * @param value the value to inspect, may be {@code null}
     * @return {@code true} when {@code value} is {@code null}, empty or entirely whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }


    /**
     * Renders a failure for a log event.
     *
     * @param failure the failure to render, may be {@code null}
     * @return the simple type name of {@code failure} followed by its message when it carries one,
     *     the simple type name alone otherwise, and {@code an unreported failure} when
     *     {@code failure} is {@code null}
     */
    private static String describe(Throwable failure) {
        if (failure == null) {
            return "an unreported failure";
        }
        String message = failure.getMessage();
        return isBlank(message)
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
