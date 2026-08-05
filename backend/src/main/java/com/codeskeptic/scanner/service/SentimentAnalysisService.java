package com.codeskeptic.scanner.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codeskeptic.scanner.util.LogSafe;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.UnaryCallSettings;
import com.google.cloud.language.v1.AnalyzeSentimentRequest;
import com.google.cloud.language.v1.AnalyzeSentimentResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.LanguageServiceClient;
import com.google.cloud.language.v1.LanguageServiceSettings;

import jakarta.annotation.PreDestroy;

// The two public operations are ported from backend/app/services/sentiment_analysis.py:L12-24 and
// :L26-35 (faithful port) — see docs/DECISION_LOG.md DL-036, DL-037. The client lifecycle below is
// net-new: the source constructed the client eagerly at :L8 and closed it nowhere — see
// docs/DECISION_LOG.md DL-207.
/**
 * Adapter for the Google Cloud Natural Language API and the single home of the
 * doubt-rating calculation.
 *
 * <p>Three operations are exposed. {@link #analyzeSentiment(String)} returns the
 * document sentiment score the Natural Language API reports for a piece of text.
 * {@link #calculateDoubtRating(double)} converts such a score into the doubt
 * rating persisted on the {@code tweets.doubt_rating} column, and
 * <p>The underlying {@link LanguageServiceClient} authenticates with Application
 * Default Credentials and is created on first use by {@link #languageClient()};
 * constructing this bean resolves no credential and opens no connection. Every
 * {@code AnalyzeSentiment} call the client issues carries a bounded deadline.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md}
 * DL-010, DL-036, DL-037, DL-052 and DL-207; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This class is thread-safe. It is a singleton bean, and client acquisition,
 * client use and client release are coordinated by a read/write lock and a
 * destroyed flag:
 *
 * <ul>
 *   <li>{@link #analyzeSentiment(String)} holds the read lock for the whole
 *       acquisition-and-call sequence. The client it obtains is not closed while
 *       the call is in flight.</li>
 *   <li>{@link #closeLanguageClient()} takes the write lock. It waits for every
 *       in-flight call to return, for at most
 *       {@value #AWAIT_ACTIVE_USE_SECONDS} seconds, before releasing the
 *       client.</li>
 *   <li>Once the bean is destroyed, {@link #languageClient()} and
 *       {@link #analyzeSentiment(String)} both throw
 *       {@link IllegalStateException}: no client is created and no request is
 *       issued after shutdown.</li>
 * </ul>
 */
@Service
public class SentimentAnalysisService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    /**
     * Deadline applied to a single {@code AnalyzeSentiment} RPC attempt. An attempt that has not
     * completed within this window fails with {@code DEADLINE_EXCEEDED}.
     */
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Ceiling on the wall-clock time one {@link #analyzeSentiment(String)} call may occupy its
     * calling thread, retries included. No further attempt starts once this window has elapsed.
     */
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Seconds {@link #closeLanguageClient()} waits for in-flight calls to return before releasing
     * the client regardless.
     */
    private static final int AWAIT_ACTIVE_USE_SECONDS = 30;

    /** Message of the {@link IllegalStateException} raised once the bean has been destroyed. */
    private static final String DESTROYED_MESSAGE =
            "SentimentAnalysisService has been destroyed; the Natural Language API client is closed";

    // The score the API documents is a finite float — DL-233 — see docs/DECISION_LOG.md
    /**
     * Message of the {@link IllegalStateException} raised for a document sentiment score that is not
     * a finite number.
     */
    private static final String NON_FINITE_SCORE_MESSAGE =
            "The Natural Language API returned a document sentiment score that is not a finite "
                    + "number; the analysis has no reportable result";

    /**
     * Guards the client against release while it is in use. Callers hold the read lock for the
     * duration of a request; {@link #closeLanguageClient()} holds the write lock.
     */
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    /**
     * Natural Language client, created on first use by {@link #languageClient()}
     * and cleared when the bean is destroyed. Written only inside a
     * {@code synchronized (this)} block and read through a {@code volatile} field
     * access.
     */
    private volatile LanguageServiceClient client;

    /**
     * Set once when the bean is destroyed. Written only inside a {@code synchronized (this)} block
     * and read through a {@code volatile} field access.
     */
    private volatile boolean destroyed;

    /**
     * Creates the service. It holds no collaborator, reads no configuration key and touches no
     * repository; the Natural Language client is created later by {@link #languageClient()}.
     */
    public SentimentAnalysisService() {
    }

    /**
     * Returns the document sentiment score the Natural Language API reports for
     * the supplied text.
     *
     * <p>The request carries the text as {@code PLAIN_TEXT} with language
     * {@code en}. The score is returned exactly as received — unrounded,
     * unscaled and unclamped. A failure reported by the API propagates to the
     * caller unchanged; no substitute score is returned.
     *
     * <p>The returned score is always finite. The API documents a score between
     * {@code -1.0} and {@code 1.0}; a value that is not a finite number —
     * {@link Double#NaN} or either infinity — is reported as a failure and is
     * never returned. No score is clamped, rounded or substituted: a finite score
     * outside that range is returned unchanged — see docs/DECISION_LOG.md DL-233.
     *
     * <p>The call is bounded. One RPC attempt may take at most {@link #RPC_TIMEOUT} and
     * the call as a whole at most {@link #TOTAL_TIMEOUT}, retries included; past
     * that the call fails and the calling thread is released. Such a failure is
     * logged at {@code ERROR} and rethrown unchanged like any other.
     *
     * <p>The read lock of {@link #lifecycleLock} is held for the whole
     * acquisition-and-call sequence. The client is not released mid-call.
     *
     * @param text the tweet text to analyse; must not be {@code null}
     * @return the document sentiment score, a finite value conventionally between
     *         {@code -1.0} (negative) and {@code 1.0} (positive)
     * @throws NullPointerException  if {@code text} is {@code null}
     * @throws IllegalStateException if the bean has been destroyed, if the
     *                               Natural Language client cannot be created
     *                               from Application Default Credentials, or if
     *                               the API reports a score that is not a finite
     *                               number
     */
    // Ported from backend/app/services/sentiment_analysis.py:L12-24 (faithful port). The parameter is
    // the tweet text, reconciling backend/app/api/tweets.py:L46 with
    // backend/app/services/sentiment_analysis.py:L14 — see docs/DECISION_LOG.md DL-036. The return
    // value is the bare document score — see docs/DECISION_LOG.md DL-037.
    public double analyzeSentiment(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank()) {
            log.warn("Analysing blank text; the Natural Language API request is issued unchanged");
        }
        log.debug("Requesting document sentiment for {} character(s) of text", text.length());

        Document document = Document.newBuilder()
                .setContent(text)
                .setType(Document.Type.PLAIN_TEXT)
                .setLanguage("en")
                .build();

        Lock activeUse = lifecycleLock.readLock();
        activeUse.lock();
        try {
            if (destroyed) {
                throw new IllegalStateException(DESTROYED_MESSAGE);
            }
            AnalyzeSentimentResponse response = languageClient().analyzeSentiment(document);
            // getScore() is declared float; the widening to double is lossless.
            double score = response.getDocumentSentiment().getScore();
            // The API documents a finite score; a non-finite one has no wire form — DL-233 — see
            // docs/DECISION_LOG.md
            if (!Double.isFinite(score)) {
                throw new IllegalStateException(NON_FINITE_SCORE_MESSAGE);
            }
            log.info("Natural Language API returned document sentiment score {}", score);
            return score;
        } catch (RuntimeException e) {
            // Provider seam: type only, never the provider message — see docs/DECISION_LOG.md DL-197
            log.error("Sentiment analysis failed for {} character(s) of text: {}",
                    text.length(), LogSafe.type(e));
            throw e;
        } finally {
            activeUse.unlock();
        }
    }

    /**
     * Converts a document sentiment score into the doubt rating persisted on the
     * {@code tweets.doubt_rating} column.
     *
     * <p>The rating is {@code (1 - sentimentScore) * 5}, bounded to the inclusive
     * range {@code 0.0} to {@code 10.0}. A score of {@code -1.0} yields
     * {@code 10.0}, {@code 0.0} yields {@code 5.0} and {@code 1.0} yields
     * {@code 0.0}. Scores outside {@code -1.0} to {@code 1.0} land outside
     * {@code 0.0} to {@code 10.0} before the bound is applied: a score of
     * {@code -2.0} yields {@code 10.0} and {@code 2.0} yields {@code 0.0}.
     *
     * <p>Non-finite scores are bounded to the same range as the source
     * expression bounds them: {@link Double#NaN} yields {@code 10.0},
     * {@link Double#NEGATIVE_INFINITY} yields {@code 10.0} and
     * {@link Double#POSITIVE_INFINITY} yields {@code 0.0}. The returned value is
     * always finite; the value written to {@code tweets.doubt_rating} is always a
     * number.
     *
     * @param sentimentScore a document sentiment score, such as one returned by
     *                       {@link #analyzeSentiment(String)}
     * @return the doubt rating, always between {@code 0.0} and {@code 10.0}
     *         inclusive
     */
    // Ported from backend/app/services/sentiment_analysis.py:L26-35 (faithful port) — see docs/DECISION_LOG.md
    public double calculateDoubtRating(double sentimentScore) {
        if (Double.isNaN(sentimentScore)) {
            // backend/app/services/sentiment_analysis.py:L32 — max(0, min(10, nan)) evaluates to 10 in Python.
            return 10.0d;
        }
        double doubtRating = (1 - sentimentScore) * 5;          // backend/app/services/sentiment_analysis.py:L29
        return Math.max(0.0d, Math.min(10.0d, doubtRating));    // backend/app/services/sentiment_analysis.py:L32
    }

    // Net-new (no Python counterpart): the inverse of calculateDoubtRating, whose forward
    // expression is at backend/app/services/sentiment_analysis.py:L26-35 — see
    // docs/DECISION_LOG.md DL-037

    /**
     * Returns the Natural Language client, creating it from Application Default
     * Credentials on first use and reusing it thereafter.
     *
     * <p>Access uses double-checked locking over the {@code volatile} field. At
     * most one client is created however many threads call this method
     * concurrently, and no client is created once the bean has been destroyed.
     * The client carries the bounded {@code AnalyzeSentiment} call settings built
     * by {@link #languageServiceSettings()}.
     *
     * <p>Declared neither {@code private} nor {@code final}. A subclass can supply the client.
     *
     * @return the Natural Language client, never {@code null}
     * @throws IllegalStateException if the bean has been destroyed, or if the
     *                               client cannot be created, including when no
     *                               Application Default Credentials are available
     */
    // Replaces the eager `self.client = LanguageServiceClient()` at
    // backend/app/services/sentiment_analysis.py:L8 (net-new lifecycle) — see docs/DECISION_LOG.md
    // DL-207
    protected LanguageServiceClient languageClient() {
        LanguageServiceClient local = this.client;
        if (local == null) {
            synchronized (this) {
                if (destroyed) {
                    throw new IllegalStateException(DESTROYED_MESSAGE);
                }
                local = this.client;
                if (local == null) {
                    log.info("Creating the Natural Language API client from Application Default Credentials");
                    try {
                        local = LanguageServiceClient.create(languageServiceSettings());
                        this.client = local;
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Unable to create LanguageServiceClient; Application Default Credentials are required",
                                e);
                    }
                }
            }
        }
        return local;
    }

    /**
     * Builds the client settings, bounding how long one {@link #analyzeSentiment(String)} call may
     * occupy its calling thread.
     *
     * <p>The generated client defaults every {@code AnalyzeSentiment} timeout — initial RPC, maximum
     * RPC and total — to ten minutes and sets no attempt limit. This method replaces those three
     * values with {@link #RPC_TIMEOUT} per attempt and {@link #TOTAL_TIMEOUT} across all attempts.
     * Every other setting is left as the client declares it: the retryable status codes stay
     * {@code DEADLINE_EXCEEDED} and {@code UNAVAILABLE} and the retry delays are unchanged. Only
     * {@code AnalyzeSentiment} is narrowed; the settings of every other operation are untouched.
     *
     * <p>No credential is resolved here; that happens inside
     * {@link LanguageServiceClient#create(LanguageServiceSettings)}.
     *
     * @return the settings used to create the Natural Language client, never {@code null}
     * @throws IOException if the settings cannot be built
     */
    // Net-new (no Python counterpart: backend/app/services/sentiment_analysis.py:L8 created the
    // client with no call settings) — see docs/DECISION_LOG.md DL-207
    private LanguageServiceSettings languageServiceSettings() throws IOException {
        LanguageServiceSettings.Builder builder = LanguageServiceSettings.newBuilder();
        UnaryCallSettings.Builder<AnalyzeSentimentRequest, AnalyzeSentimentResponse> callSettings =
                builder.analyzeSentimentSettings();

        RetrySettings boundedRetrySettings = callSettings.getRetrySettings().toBuilder()
                .setInitialRpcTimeoutDuration(RPC_TIMEOUT)
                .setMaxRpcTimeoutDuration(RPC_TIMEOUT)
                .setTotalTimeoutDuration(TOTAL_TIMEOUT)
                .build();
        callSettings.setRetrySettings(boundedRetrySettings);

        log.info(
                "Natural Language client bounded to {} per AnalyzeSentiment attempt and {} in total",
                RPC_TIMEOUT,
                TOTAL_TIMEOUT);

        return builder.build();
    }

    /**
     * Marks the bean destroyed and releases the Natural Language client, and only
     * if {@link #languageClient()} ever created one.
     *
     * <p>The write lock of {@link #lifecycleLock} is acquired first. The method
     * waits up to {@value #AWAIT_ACTIVE_USE_SECONDS} seconds for in-flight calls
     * to return; if the wait elapses the client is released anyway and the wait is
     * reported at {@code WARN}. A failure to close is logged at {@code WARN} and
     * not propagated. Calling this method more than once has no further effect.
     */
    // Net-new (the source closed the client nowhere) — see docs/DECISION_LOG.md DL-207; the
    // log-and-suppress close policy is the logging baseline — see docs/DECISION_LOG.md DL-052
    @PreDestroy
    void closeLanguageClient() {
        Lock exclusive = lifecycleLock.writeLock();
        boolean acquired = false;
        try {
            acquired = exclusive.tryLock(AWAIT_ACTIVE_USE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Sentiment analysis still in flight after {}s; releasing the Natural "
                        + "Language API client anyway", AWAIT_ACTIVE_USE_SECONDS);
            }
            releaseClient();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for sentiment analysis to finish; releasing the "
                    + "Natural Language API client");
            releaseClient();
        } finally {
            if (acquired) {
                exclusive.unlock();
            }
        }
    }

    /**
     * Sets the destroyed flag and closes the client if one was created.
     *
     * <p>The flag and the field are read and written together inside a
     * {@code synchronized (this)} block. A concurrent {@link #languageClient()}
     * either creates the client before the flag is set or observes the flag and
     * creates nothing.
     */
    private void releaseClient() {
        LanguageServiceClient local;
        synchronized (this) {
            destroyed = true;
            local = this.client;
            this.client = null;
        }
        if (local == null) {
            return;
        }
        try {
            local.close();
            log.info("Closed the Natural Language API client");
        } catch (RuntimeException e) {
            log.warn("Closing the Natural Language API client did not complete: {}",
                    LogSafe.type(e));
        }
    }
}
