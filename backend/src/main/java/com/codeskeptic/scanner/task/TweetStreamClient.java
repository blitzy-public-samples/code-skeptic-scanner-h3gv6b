package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.util.LogSafe;
import com.codeskeptic.scanner.util.StreamRuleTerms;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.PageRequest;
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
 *   <li>{@link #consumeStream(String, int)} subscribes to the chunked newline-delimited body and
 *       calls {@link TweetStreamListener#onStatus(JsonNode, int)} once per complete record, against
 *       the popularity threshold this cycle resolved once — DL-255.</li>
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
 * <p>Every term is held to the one shared grammar of {@link StreamRuleTerms} before it is rendered as
 * a rule expression: a term outside that allowlist is dropped, and no term value is written to the
 * log — DL-257. The
 * collection is bounded by {@code scanner.ingestion.max-stream-rules}, only {@code ai_tools.name} is
 * selected, and each mutation is sent as consecutive requests of at most
 * {@value #MAX_RULES_PER_REQUEST} rules — DL-254. A registered rule whose tag differs from the wanted
 * one is replaced — see docs/DECISION_LOG.md DL-261.
 *
 * <p>At most {@value #STREAM_DISPATCH_PREFETCH} record and {@value #STREAM_CHUNK_PREFETCH} body chunk
 * are queued ahead of the work in progress, so the memory a connection holds is bounded however long a
 * provider or a database call takes — DL-258. A dropped record is counted and reported at most once per
 * {@value #DROPPED_RECORD_REPORT_INTERVAL} — DL-260.
 *
 * <p>The body carries a signal-idle bound of
 * {@code scanner.ingestion.stream-idle-timeout-seconds}: any chunk resets it, the keep-alive chunk
 * included, so a silent half-open connection fails and reconnects rather than appearing healthy —
 * DL-256.
 *
 * <p>{@link #start()} schedules the cycle on {@link Schedulers#boundedElastic()} and returns without
 * blocking, issuing no request on the calling thread and raising nothing. Every delivered record is
 * handed to the listener on {@link Schedulers#boundedElastic()} as well, so no work the listener
 * performs runs on a connection thread. Every failure inside the cycle is recorded and confined to the
 * reactive chain. {@link #stop()} waits up to {@value #SHUTDOWN_DRAIN_MILLIS} milliseconds for a
 * record already handed to the listener and then disposes the subscription, which closes the
 * connection; {@link #stop(Runnable)} reports that transition to the container. {@link #start()} and
 * {@link #stop()} hold one lock for the whole transition, so the subscription handle is published and
 * cancelled under one mutual exclusion — DL-259.
 *
 * <p>{@link #start()} opens no connection at all, and {@link #isAutoStartup()} reports
 * {@code false}, when {@code scanner.background.enabled} or {@code scanner.background.stream-enabled}
 * is {@code false}: only the process designated as the background worker streams — see
 * docs/DECISION_LOG.md DL-250.
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
 * The held token, the subscription handle, the two lifecycle flags, the in-flight dispatch count and
 * the four dropped-record counters are the only mutable state and each is {@code volatile} or atomic.
 * The lifecycle operations are safe to call from any thread and are serialised against one another.
 *
 * <p>No credential, no term value and no whole record body is written to the log. Decisions covering
 * this file are recorded in {@code docs/DECISION_LOG.md} DL-012, DL-044, DL-045, DL-046, DL-052,
 * DL-250 and DL-254 through DL-261; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @see TweetStreamListener#onStatus(JsonNode, int)
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

    /** Response header carrying the UTC epoch second at which a rate-limit window resets. */
    private static final String RATE_LIMIT_RESET_HEADER = "x-rate-limit-reset";

    // Rule set: configured base terms union ai_tools.name, overridable by the stream_keywords
    // setting row — see docs/DECISION_LOG.md DL-044
    /** {@code settings} row whose value replaces the whole composed term collection. */
    private static final String STREAM_KEYWORDS_SETTING_KEY = "stream_keywords";


    /** Property naming the base terms, reported when the composed collection resolves to nothing. */
    private static final String STREAM_BASE_KEYWORDS_PROPERTY =
            "scanner.ingestion.stream-base-keywords";

    /** Property naming the app-only consumer key, reported when it is unset or blank. */
    private static final String CONSUMER_KEY_PROPERTY = "scanner.twitter.consumer-key";

    /** Property naming the app-only consumer secret, reported when it is unset or blank. */
    private static final String CONSUMER_SECRET_PROPERTY = "scanner.twitter.consumer-secret";

    /** Property governing every background path of this process — DL-250. */
    private static final String BACKGROUND_ENABLED_PROPERTY = "scanner.background.enabled";

    /** Property governing this background path — DL-250. */
    private static final String STREAM_ENABLED_PROPERTY = "scanner.background.stream-enabled";

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

    // Rules-mutation outcome members read to surface a refused rule — DL-275 — see
    // docs/DECISION_LOG.md
    /** Rules-response member carrying one entry per refused rule. */
    private static final String RULES_KEY_ERRORS = "errors";

    /** Rules-response member carrying the outcome summary. */
    private static final String RULES_KEY_META = "meta";

    /** {@value #RULES_KEY_META} member carrying the per-outcome counts. */
    private static final String RULES_KEY_SUMMARY = "summary";

    /** {@value #RULES_KEY_SUMMARY} member counting the rules the endpoint did not create. */
    private static final String RULES_KEY_NOT_CREATED = "not_created";

    /** {@value #RULES_KEY_SUMMARY} member counting the rules the endpoint read as invalid. */
    private static final String RULES_KEY_INVALID = "invalid";

    /** Most rules one mutation request carries; a larger set is sent as consecutive requests. */
    private static final int MAX_RULES_PER_REQUEST = 25;

    // Bounded queueing between the connection and the dispatch worker — DL-258 — see
    // docs/DECISION_LOG.md
    /** Records the dispatch worker may hold ahead of the one it is handling. */
    private static final int STREAM_DISPATCH_PREFETCH = 1;

    /** Body chunks requested ahead of the one being drained. */
    private static final int STREAM_CHUNK_PREFETCH = 1;

    // Bounded lifecycle transitions and dispatch drain — DL-259 — see docs/DECISION_LOG.md
    /** Longest {@link #stop(Runnable)} waits for a record being dispatched, in milliseconds. */
    private static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

    /** Interval between two checks of the in-flight dispatch count, in milliseconds. */
    private static final long DRAIN_POLL_MILLIS = 10L;

    // Bounded reporting of dropped records — DL-260 — see docs/DECISION_LOG.md
    /** Shortest interval between two records of the same dropped-record condition. */
    private static final Duration DROPPED_RECORD_REPORT_INTERVAL = Duration.ofMinutes(1L);

    /** Byte on which the response body is split into records. */
    private static final byte LINE_FEED = (byte) '\n';

    // Bound on the bytes held for one record — see docs/DECISION_LOG.md DL-222
    /**
     * Most bytes the accumulator holds for a single record. A record that reaches this size without a
     * terminating {@value #LINE_FEED} is discarded rather than accumulated further; the connection
     * stays open. One post of the X API v2 filtered stream, with the requested fields, is orders of
     * magnitude smaller.
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
     * Serialises {@link #start()} against {@link #stop()}, so the subscription handle is published and
     * cancelled under one mutual exclusion — DL-259.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /** Records handed to the listener that have not yet returned — DL-259. */
    private final AtomicInteger inFlightDispatches = new AtomicInteger();

    /** Records dropped for reaching {@link #MAX_RECORD_BYTES} since the last report — DL-260. */
    private final AtomicLong unreportedOverlongRecords = new AtomicLong();

    /** Nanosecond stamp of the last over-long-record report, {@code 0} until one is made — DL-260. */
    private final AtomicLong lastOverlongReportNanos = new AtomicLong();

    /** Records dropped for not parsing as JSON since the last report — DL-260. */
    private final AtomicLong unreportedUnreadableRecords = new AtomicLong();

    /** Nanosecond stamp of the last unreadable-record report, {@code 0} until one is made — DL-260. */
    private final AtomicLong lastUnreadableReportNanos = new AtomicLong();

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
     * <p>The method returns without contacting X when this process does not run the stream, which
     * {@link #isAutoStartup()} reports — DL-250.
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
        // Only the designated background worker streams — DL-250 — see docs/DECISION_LOG.md
        if (!isAutoStartup()) {
            log.info("X filtered stream ingestion not started in this process: {} and {} must both "
                    + "hold", BACKGROUND_ENABLED_PROPERTY, STREAM_ENABLED_PROPERTY);
            return;
        }

        // One transition at a time; the handle is published under this lock and cancelled under it —
        // DL-259 — see docs/DECISION_LOG.md
        lifecycleLock.lock();
        try {
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
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Disposes the subscribed cycle, which closes the connection.
     *
     * <p>The held token is discarded and {@link #isRunning()} reports {@code false} once this method
     * returns. A call made when no cycle is subscribed records the shutdown and returns.
     *
     * <p>The transition is serialised against {@link #start()}, so a handle published by a concurrent
     * start is either cancelled by this call or published after it and cancelled by the next one; no
     * handle is left subscribed — DL-259.
     *
     * <p>A record already handed to the listener is awaited before the subscription is cancelled, for
     * at most {@value #SHUTDOWN_DRAIN_MILLIS} milliseconds, so a reply being generated or stored is not
     * cut short by the cancellation. A record still in flight at that bound is reported at {@code WARN}
     * and the cancellation proceeds — DL-259.
     */
    @Override
    public void stop() {
        lifecycleLock.lock();
        try {
            stopRequested = true;
            running.set(false);

            // The connection is cancelled only once no record is being handled — DL-259 — see
            // docs/DECISION_LOG.md
            awaitDispatchDrain();

            Disposable current = this.subscription;
            this.subscription = null;
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
            this.bearerToken = null;
        } finally {
            lifecycleLock.unlock();
        }

        log.info("Stopped X filtered stream ingestion");
    }

    // Net-new bounded dispatch drain — DL-259 — see docs/DECISION_LOG.md
    /**
     * Stops the cycle and then reports completion to the container.
     *
     * <p>{@link #stop()} performs the whole transition, the bounded drain included, so {@code callback}
     * runs once no record is in flight or once the drain bound has passed. {@code callback} runs even
     * when the transition raises.
     *
     * @param callback the container's completion callback, must not be {@code null}
     */
    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null.");
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    /**
     * Waits until no record is being dispatched, for at most {@value #SHUTDOWN_DRAIN_MILLIS}
     * milliseconds.
     *
     * <p>An interrupt ends the wait and restores the interrupt status of the calling thread.
     */
    // Net-new bounded dispatch drain — DL-259 — see docs/DECISION_LOG.md
    private void awaitDispatchDrain() {
        long deadline = System.nanoTime() + Duration.ofMillis(SHUTDOWN_DRAIN_MILLIS).toNanos();
        while (inFlightDispatches.get() > 0) {
            if (System.nanoTime() - deadline >= 0L) {
                log.warn("{} record(s) were still being handled after the {}ms shutdown bound; the "
                        + "shutdown continues", inFlightDispatches.get(), SHUTDOWN_DRAIN_MILLIS);
                return;
            }
            try {
                Thread.sleep(DRAIN_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Waiting for {} record(s) to finish was interrupted; the shutdown continues",
                        inFlightDispatches.get());
                return;
            }
        }
    }

    /**
     * Reports whether this process runs the X filtered stream.
     *
     * <p>The container calls this before {@link #start()}, and {@link #start()} applies the same
     * answer, so a process for which {@code scanner.background.enabled} or
     * {@code scanner.background.stream-enabled} is {@code false} reaches X in no way — see
     * docs/DECISION_LOG.md DL-250.
     *
     * @return {@code true} when both properties hold, which is their default
     */
    // Net-new ownership switch — DL-250 — see docs/DECISION_LOG.md
    @Override
    public boolean isAutoStartup() {
        ScannerProperties.Background background = properties.background();
        return background == null || background.runsStream();
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
                            // The popularity threshold is resolved once for this cycle — DL-255 —
                            // see docs/DECISION_LOG.md
                            .flatMap(token -> Mono
                                    .fromCallable(tweetStreamListener::popularityThresholdInForce)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(threshold -> consumeStream(token, threshold)));
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
     *       holds a non-blank value, is split on {@value StreamRuleTerms#TERM_DELIMITER} and
     *       replaces the whole collection. An absent row and a blank value both fall through to the
     *       next step.</li>
     *   <li>Otherwise the configured {@value #STREAM_BASE_KEYWORDS_PROPERTY} terms are joined with
     *       the {@code name} of every {@code ai_tools} row.</li>
     *   <li>A collection that still resolves to nothing yields the configured base terms.</li>
     * </ol>
     *
     * <p>Every step trims each term, drops a {@code null} or blank term, drops a term that
     * {@link StreamRuleTerms#isUsable(String)} refuses, and removes a repeat without regard to letter
     * case while keeping the order in which terms were first seen and the letter case of the first
     * occurrence.
     *
     * <p>The result holds at most {@code scanner.ingestion.max-stream-rules} terms. A composition that
     * yields more is truncated to the first that many and the two counts are recorded at {@code WARN},
     * so a term collection can never exceed the rule cap of the account tier — DL-254.
     *
     * <p>The two repository reads block. This method runs only on
     * {@link Schedulers#boundedElastic()}.
     *
     * @return an ordered collection holding no repeat, no blank term and no rejected term, of at most
     *     {@code scanner.ingestion.max-stream-rules} entries; empty only when no term is configured,
     *     no {@code ai_tools} row holds an acceptable name and the override row holds nothing
     */
    private List<String> composeRuleTerms() {
        ScannerProperties.Ingestion ingestion = properties.ingestion();
        List<String> configuredTerms =
                distinctTerms(ingestion == null ? List.of() : ingestion.streamBaseKeywords());

        List<String> overrideTerms = readOverrideTerms();
        if (!overrideTerms.isEmpty()) {
            log.info("Stream rule terms taken from the `{}` settings row: {} term(s)",
                    STREAM_KEYWORDS_SETTING_KEY, overrideTerms.size());
            return boundedToRuleCap(overrideTerms);
        }

        Map<String, String> merged = new LinkedHashMap<>();
        mergeTerms(merged, configuredTerms);
        mergeTerms(merged, readAiToolNames(maxStreamRules()));
        List<String> composed = List.copyOf(merged.values());

        return boundedToRuleCap(composed.isEmpty() ? configuredTerms : composed);
    }

    // Net-new client-side rule cap — DL-254 — see docs/DECISION_LOG.md
    /**
     * Truncates a term collection to {@code scanner.ingestion.max-stream-rules} entries.
     *
     * @param terms the composed terms, must not be {@code null}
     * @return {@code terms} when it holds no more than the cap, and its first cap entries otherwise,
     *     which is recorded at {@code WARN} with the two counts and no term value
     */
    private List<String> boundedToRuleCap(List<String> terms) {
        int cap = maxStreamRules();
        if (terms.size() <= cap) {
            return terms;
        }

        // Counts only; no term value is recorded — DL-052, DL-257 — see docs/DECISION_LOG.md
        log.warn("Composed {} stream rule term(s) against a cap of {}; the first {} are registered "
                + "and the rest are dropped", terms.size(), cap, cap);
        return List.copyOf(terms.subList(0, cap));
    }

    /**
     * Reports the number of rules the account tier accepts.
     *
     * @return {@code scanner.ingestion.max-stream-rules}, and {@link #MAX_RULES_PER_REQUEST} when the
     *     {@code scanner.ingestion} group is unbound
     */
    private int maxStreamRules() {
        ScannerProperties.Ingestion ingestion = properties.ingestion();
        return ingestion == null ? MAX_RULES_PER_REQUEST : ingestion.maxStreamRules();
    }

    /**
     * Reports the span without any byte from the stream after which the connection is treated as dead.
     *
     * @return {@code scanner.ingestion.stream-idle-timeout-seconds} as a duration, and one minute when
     *     the {@code scanner.ingestion} group is unbound
     */
    // Net-new signal-idle bound — DL-256 — see docs/DECISION_LOG.md
    private Duration streamIdleTimeout() {
        ScannerProperties.Ingestion ingestion = properties.ingestion();
        return Duration.ofSeconds(ingestion == null ? 60L : ingestion.streamIdleTimeoutSeconds());
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

        // The one shared split, bounded at StreamRuleTerms.MAX_SEGMENTS so an over-long row cannot
        // produce an unbounded collection — DL-254, DL-257 — see docs/DECISION_LOG.md
        return distinctTerms(StreamRuleTerms.split(stored));
    }

    // Only ai_tools.name is selected, bounded by the rule cap — DL-254 — see docs/DECISION_LOG.md
    /**
     * Reads at most {@code bound} {@code ai_tools} names.
     *
     * <p>Only the {@code name} column is selected, so no other column of the table is transferred, and
     * the query returns at most {@code bound} rows in {@code id} order.
     *
     * @param bound the greatest number of names to read, at least {@code 1}
     * @return the names the bounded query returned, with a {@code null} or blank name omitted
     */
    private List<String> readAiToolNames(int bound) {
        List<String> selected = aiToolRepository.findNames(PageRequest.of(0, bound));
        List<String> names = new ArrayList<>(selected.size());
        for (String name : selected) {
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
     * <p>A registered rule is replaced when its match expression is no longer wanted and when its
     * {@value #RULES_KEY_TAG} differs from the wanted one — see docs/DECISION_LOG.md DL-261.
     *
     * <p>Each mutation is sent as consecutive requests of at most {@value #MAX_RULES_PER_REQUEST}
     * rules, and the composed collection is already bounded by
     * {@code scanner.ingestion.max-stream-rules} — DL-254. No term value is recorded — DL-257.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param terms the composed terms, must hold at least one term
     * @return a sequence that completes once the registered rules agree with {@code terms}
     */
    private Mono<Void> reconcileStreamRules(String token, List<String> terms) {
        Map<String, String> desired = new LinkedHashMap<>();
        for (String term : terms) {
            desired.put(StreamRuleTerms.expressionOf(term), term);
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
     * @return the registered rules keyed by match expression, each carrying its identifier and its
     *     tag; empty when the endpoint reports no rule
     */
    private Mono<Map<String, RegisteredRule>> listStreamRules(String token) {
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
     * <p>A registered rule is surplus when its match expression is not wanted and when the tag it
     * carries differs from the wanted tag; the second case is deleted here and re-added below, so a
     * stale tag is corrected — DL-261.
     *
     * <p>Both mutations are sent as consecutive requests of at most {@value #MAX_RULES_PER_REQUEST}
     * rules each — DL-254.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param desired the wanted rules keyed by match expression with the tag as value, must not be
     *     {@code null}
     * @param existing the registered rules keyed by match expression, must not be {@code null}
     * @return a sequence that completes once both mutations have been applied or skipped
     */
    private Mono<Void> applyRuleDifference(String token,
            Map<String, String> desired,
            Map<String, RegisteredRule> existing) {
        List<String> surplusIds = new ArrayList<>();
        List<String> staleTagExpressions = new ArrayList<>();
        for (Map.Entry<String, RegisteredRule> registered : existing.entrySet()) {
            String expression = registered.getKey();
            String wantedTag = desired.get(expression);
            if (wantedTag == null) {
                surplusIds.add(registered.getValue().id());
                continue;
            }
            // The tag reaches tweets.ai_tools_mentioned, so a rule carrying a stale one is replaced —
            // DL-261 — see docs/DECISION_LOG.md
            if (!wantedTag.equals(registered.getValue().tag())) {
                surplusIds.add(registered.getValue().id());
                staleTagExpressions.add(expression);
            }
        }

        List<Map<String, String>> missingRules = new ArrayList<>();
        for (Map.Entry<String, String> wanted : desired.entrySet()) {
            if (!existing.containsKey(wanted.getKey())
                    || staleTagExpressions.contains(wanted.getKey())) {
                missingRules.add(Map.of(
                        RULES_KEY_VALUE, wanted.getKey(),
                        RULES_KEY_TAG, wanted.getValue()));
            }
        }

        int replaced = staleTagExpressions.size();
        return deleteRules(token, surplusIds)
                .then(addRules(token, missingRules))
                .doOnSuccess(ignored -> log.info("Stream rules reconciled: {} rule(s) registered, "
                        + "{} added, {} removed, {} replaced for a changed tag", desired.size(),
                        missingRules.size(), surplusIds.size(), replaced));
    }

    // Net-new batching of both mutations — DL-254 — see docs/DECISION_LOG.md
    /**
     * Deletes the supplied rule identifiers in consecutive bounded requests.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param surplusIds the identifiers to delete, must not be {@code null}
     * @return a sequence that completes once every batch has been sent, and at once when the
     *     collection is empty
     */
    private Mono<Void> deleteRules(String token, List<String> surplusIds) {
        List<Mono<Void>> batches = new ArrayList<>();
        for (int from = 0; from < surplusIds.size(); from += MAX_RULES_PER_REQUEST) {
            int to = Math.min(from + MAX_RULES_PER_REQUEST, surplusIds.size());
            List<String> batch = List.copyOf(surplusIds.subList(from, to));
            batches.add(mutateStreamRules(token,
                    Map.of(RULES_KEY_DELETE, Map.of(RULES_KEY_IDS, batch))));
        }
        return Flux.concat(batches).then();
    }

    // Net-new batching of both mutations — DL-254 — see docs/DECISION_LOG.md
    /**
     * Adds the supplied rules in consecutive bounded requests.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param missingRules the rules to add, must not be {@code null}
     * @return a sequence that completes once every batch has been sent, and at once when the
     *     collection is empty
     */
    private Mono<Void> addRules(String token, List<Map<String, String>> missingRules) {
        List<Mono<Void>> batches = new ArrayList<>();
        for (int from = 0; from < missingRules.size(); from += MAX_RULES_PER_REQUEST) {
            int to = Math.min(from + MAX_RULES_PER_REQUEST, missingRules.size());
            List<Map<String, String>> batch = List.copyOf(missingRules.subList(from, to));
            batches.add(mutateStreamRules(token, Map.of(RULES_KEY_ADD, batch)));
        }
        return Flux.concat(batches).then();
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
                // A rule the endpoint refused is surfaced rather than discarded — DL-275 — see
                // docs/DECISION_LOG.md
                .doOnNext(TweetStreamClient::reportRefusedRules)
                .then();
    }

    // Net-new: the rules-mutation answer is read so a partial refusal is observable — DL-275 — see
    // docs/DECISION_LOG.md
    /**
     * Records the rules the mutation endpoint refused.
     *
     * <p>Nothing is recorded when the answer carries no {@value #RULES_KEY_ERRORS} entry and both the
     * {@value #RULES_KEY_NOT_CREATED} and {@value #RULES_KEY_INVALID} summary members are absent or
     * zero. A refusal does not fail the mutation: the stream still connects with the rules the
     * endpoint did accept.
     *
     * <p>The record names the two summary counts and a fingerprint of each refused expression, and
     * never the expression the provider reflected or any free text it returned — DL-275, DL-197. A
     * fingerprint is {@link LogSafe#correlation(Object)} of the reflected value, so it is fixed in
     * shape and length however long that value is, and it matches
     * {@code LogSafe.correlation(StreamRuleTerms.expressionOf(term))} for the term this application
     * composed. At most {@value #MAX_RULES_PER_REQUEST} fingerprints are listed — one request's
     * worth, which is every rule the answered request could refuse — while the count is reported in
     * full.
     *
     * @param payload the mutation answer, may be {@code null}
     */
    private static void reportRefusedRules(JsonNode payload) {
        if (payload == null) {
            return;
        }

        JsonNode summary = payload.path(RULES_KEY_META).path(RULES_KEY_SUMMARY);
        int notCreated = summary.path(RULES_KEY_NOT_CREATED).asInt(0);
        int invalid = summary.path(RULES_KEY_INVALID).asInt(0);

        JsonNode errors = payload.path(RULES_KEY_ERRORS);
        int refused = 0;
        List<String> fingerprints = new ArrayList<>();
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String expression = error.path(RULES_KEY_VALUE).asText("");
                if (expression.isBlank()) {
                    continue;
                }
                refused++;
                // A provider-reflected expression reaches the record as a fingerprint only — DL-275 —
                // see docs/DECISION_LOG.md
                if (fingerprints.size() < MAX_RULES_PER_REQUEST) {
                    fingerprints.add(LogSafe.correlation(expression));
                }
            }
        }

        if (refused == 0 && notCreated == 0 && invalid == 0) {
            return;
        }

        log.warn("X refused {} stream rule(s) ({} not created, {} invalid); refused expression "
                + "fingerprint(s): {}",
                Math.max(refused, notCreated + invalid), notCreated, invalid, fingerprints);
    }

    /**
     * Reads the registered rules out of a rules-endpoint payload.
     *
     * <p>The {@value #RULES_KEY_TAG} member is read alongside the identifier: a rule whose tag
     * no longer matches the wanted one is replaced — see docs/DECISION_LOG.md DL-261. A rule carrying
     * no tag is read as carrying an empty one.
     *
     * @param payload the endpoint payload, may be {@code null}
     * @return the registered rules keyed by match expression, each carrying its identifier and its
     *     tag; empty when the payload is {@code null}, carries no {@value #RULES_KEY_DATA} array, or
     *     carries only entries missing an identifier or a match expression
     */
    private static Map<String, RegisteredRule> readRegisteredRules(JsonNode payload) {
        Map<String, RegisteredRule> registered = new LinkedHashMap<>();
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
            String tag = rule.path(RULES_KEY_TAG).asText("");
            if (!expression.isBlank() && !identifier.isBlank()) {
                registered.put(expression, new RegisteredRule(identifier, tag));
            }
        }
        return registered;
    }

    // Net-new: the identifier and the tag of one registered rule — DL-261 — see docs/DECISION_LOG.md
    /**
     * One rule as X reports it.
     *
     * @param id the rule identifier, used to delete the rule, never {@code null}
     * @param tag the rule tag, which reaches {@code tweets.ai_tools_mentioned}, never {@code null}
     */
    private record RegisteredRule(String id, String tag) {
    }

    // The bound applied to every short request/response call on the X API — DL-230 — see
    // docs/DECISION_LOG.md
    /**
     * Reports the bound applied to a control-plane call.
     *
     * <p>The value is {@code scanner.twitter.request-timeout-seconds}, read on every call and never
     * shorter than {@value #MINIMUM_REQUEST_TIMEOUT_SECONDS} second, so a configuration change takes
     * effect on the next call with no restart. The filtered-stream subscription is not bounded by
     * it.
     *
     * @return the bound for the app-only token exchange and the stream-rules calls, never
     *     {@code null} and never shorter than {@value #MINIMUM_REQUEST_TIMEOUT_SECONDS} second
     */
    private Duration controlPlaneTimeout() {
        long configured = properties.twitter().requestTimeoutSeconds();
        return Duration.ofSeconds(Math.max(configured, MINIMUM_REQUEST_TIMEOUT_SECONDS));
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
     * <p>Records are handed to {@link #dispatchRecord(String, int)} on
     * {@link Schedulers#boundedElastic()}, not on the thread the response body is emitted on, so the
     * listener's persistence and external calls never run on a connection thread. Delivery stays in
     * arrival order on a single worker, and the connection thread is free to keep reading while a
     * record is being handled.
     *
     * <p>The hand-over requests {@value #STREAM_DISPATCH_PREFETCH} record at a time, so at most that
     * many records are queued between the connection and the dispatch worker however long a provider or
     * a database call takes — DL-258.
     *
     * @param token the app-only bearer token, must not be {@code null}
     * @param popularityThreshold the popularity threshold resolved for this cycle — DL-255
     * @return a sequence that completes when the listener reports that streaming should stop and
     *     fails when the connection ends for any other reason
     */
    // Per-record dispatch runs on a blocking-capable scheduler, not on the connection thread — see
    // docs/DECISION_LOG.md DL-220
    private Mono<Void> consumeStream(String token, int popularityThreshold) {
        return streamRecords(token)
                // Bounded queueing between the connection and the dispatch worker — DL-258 — see
                // docs/DECISION_LOG.md
                .publishOn(Schedulers.boundedElastic(), STREAM_DISPATCH_PREFETCH)
                .map(record -> dispatchRecord(record, popularityThreshold))
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
     * filtered-stream connection is long-lived and the bound of
     * {@code scanner.twitter.request-timeout-seconds} applies only to the token exchange and the
     * stream-rules calls — DL-230.
     *
     * <p>The body does carry a signal-idle bound of
     * {@code scanner.ingestion.stream-idle-timeout-seconds}: any chunk resets it, the periodic
     * keep-alive chunk included, and a connection that delivers no byte at all for that span fails with
     * a timeout and reconnects rather than appearing healthy — DL-256.
     *
     * <p>At most {@value #STREAM_CHUNK_PREFETCH} chunk is requested ahead of the one being drained, so
     * no unreleased buffer is queued — DL-258.
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
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + token)
                    .exchangeToFlux(TweetStreamClient::openStreamBody)
                    // Any chunk resets this bound; it is not a bound on the whole response — DL-256 —
                    // see docs/DECISION_LOG.md
                    .timeout(streamIdleTimeout())
                    .concatMap(chunk ->
                            Flux.fromIterable(drainCompleteRecords(chunk, pending, discarding)),
                            STREAM_CHUNK_PREFETCH);
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
     * accumulator is emptied, the condition is counted and reported at most once per
     * {@value #DROPPED_RECORD_REPORT_INTERVAL} — DL-260 — and {@code discarding} is raised, under which
     * the remaining bytes of that record are dropped up to and including its next line feed.
     * Accumulation of the following record then resumes normally and the connection is never ended.
     *
     * @param chunk one body chunk, released before this method returns; must not be {@code null}
     * @param pending the accumulator holding the bytes of the record in progress, must not be
     *     {@code null}
     * @param discarding raised while the remainder of an over-long record is being dropped, must not be
     *     {@code null}
     * @return the records the chunk completed, in arrival order; empty when the chunk completed none
     */
    // Bounded accumulation: log and skip, never end the connection — see docs/DECISION_LOG.md DL-222
    private List<String> drainCompleteRecords(DataBuffer chunk,
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
                    // Neither the accumulated bytes nor any part of them is written: the counts and
                    // the bound only — DL-222, DL-260 — see docs/DECISION_LOG.md
                    reportDroppedRecords(unreportedOverlongRecords, lastOverlongReportNanos,
                            "reached the {} byte bound with no line feed", MAX_RECORD_BYTES);
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
     * <p>A record that cannot be read as JSON is counted and reported at most once per
     * {@value #DROPPED_RECORD_REPORT_INTERVAL} — DL-260.
     *
     * <p>A record handed to the listener is counted as in flight until the listener returns, so
     * {@link #stop(Runnable)} can wait for it — DL-259.
     *
     * @param record one complete record, may be {@code null}
     * @param popularityThreshold the popularity threshold resolved for this cycle — DL-255
     * @return {@code true} to keep the connection open, and {@code false} once the listener has
     *     reported that streaming should stop
     */
    private boolean dispatchRecord(String record, int popularityThreshold) {
        String candidate = record == null ? "" : record.trim();
        if (candidate.isEmpty()) {
            return true;
        }

        JsonNode payload;
        try {
            payload = OBJECT_MAPPER.readTree(candidate);
        } catch (JsonProcessingException failure) {
            // Neither the record nor the parse failure's message is written: the counts and the
            // failure's type only — DL-149, DL-260 — see docs/DECISION_LOG.md
            reportDroppedRecords(unreportedUnreadableRecords, lastUnreadableReportNanos,
                    "could not be read as JSON ({})", LogSafe.type(failure));
            return true;
        }

        // The record is in flight until the listener returns — DL-259 — see docs/DECISION_LOG.md
        inFlightDispatches.incrementAndGet();
        try {
            // Honours the documented contract of on_status at
            // documentation/Code Structure.md:L1393 — see docs/DECISION_LOG.md
            boolean keepStreaming = tweetStreamListener.onStatus(payload, popularityThreshold);
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
        } finally {
            inFlightDispatches.decrementAndGet();
        }
    }

    // Net-new bounded reporting of dropped records — DL-260 — see docs/DECISION_LOG.md
    /**
     * Counts one dropped record and reports the running count at most once per
     * {@value #DROPPED_RECORD_REPORT_INTERVAL}.
     *
     * <p>The count is raised on every call. A report is emitted at {@code WARN} when no report of the
     * same condition has been emitted within the interval, carries the number of records dropped since
     * the previous report, and resets that number; every other call records the condition at
     * {@code DEBUG} only. No part of a dropped record is ever written.
     *
     * @param unreported the running count of this condition, must not be {@code null}
     * @param lastReportNanos the nanosecond stamp of the last report of this condition, must not be
     *     {@code null}
     * @param reason a fixed description of the condition, holding one {@code {}} placeholder for
     *     {@code detail}, must not be {@code null}
     * @param detail the value substituted into {@code reason}, must not be {@code null}
     */
    private static void reportDroppedRecords(AtomicLong unreported, AtomicLong lastReportNanos,
            String reason, Object detail) {
        unreported.incrementAndGet();

        long now = System.nanoTime();
        long previous = lastReportNanos.get();
        boolean due = previous == 0L
                || now - previous >= DROPPED_RECORD_REPORT_INTERVAL.toNanos();
        if (!due || !lastReportNanos.compareAndSet(previous, now)) {
            log.debug("Skipping an X filtered stream record that " + reason, detail);
            return;
        }

        long dropped = unreported.getAndSet(0L);
        log.warn("Skipped {} X filtered stream record(s) in the last {}s that " + reason,
                dropped, DROPPED_RECORD_REPORT_INTERVAL.toSeconds(), detail);
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
     * <p>A {@code null} term, a term that is blank once trimmed, and a term that
     * {@link StreamRuleTerms#isUsable(String)} refuses are all dropped. A dropped term is recorded at
     * {@code WARN} with its length and a correlation token only, never with its characters — DL-257.
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
            // The one shared grammar decides which terms reach the rule DSL — DL-257 — see
            // docs/DECISION_LOG.md
            if (!StreamRuleTerms.isUsable(trimmed)) {
                log.warn("Dropping a stream rule term that holds a character outside letters, digits "
                        + "and '{}', that opens or closes with one of those, or that exceeds {} "
                        + "character(s); term {} is {} character(s) long",
                        StreamRuleTerms.ADDITIONAL_TERM_CHARACTERS, StreamRuleTerms.MAX_TERM_CHARS,
                        LogSafe.correlation(trimmed), trimmed.length());
                continue;
            }
            target.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
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


    // Every failure rendering passes the shared log guard — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders a failure for a log event.
     *
     * <p>The runtime type comes from {@link LogSafe#type(Throwable)} and is carried literally. A
     * message is carried only through {@link LogSafe#logSafe(String)}, so every character outside
     * printable ASCII — the carriage return and the line feed included — becomes {@code ?} and the
     * rendering is bounded. A message a remote peer, a proxy, a TLS stack or a URL contributed
     * forges no record boundary and floods no record — DL-197.
     *
     * @param failure the failure to render, may be {@code null}
     * @return the simple type name of {@code failure} followed by its guarded message when it carries
     *     one, the simple type name alone otherwise, and {@code an unreported failure} when
     *     {@code failure} is {@code null}
     */
    private static String describe(Throwable failure) {
        if (failure == null) {
            return "an unreported failure";
        }
        String message = failure.getMessage();
        return isBlank(message)
                ? LogSafe.type(failure)
                : LogSafe.type(failure) + ": " + LogSafe.logSafe(message);
    }
}
