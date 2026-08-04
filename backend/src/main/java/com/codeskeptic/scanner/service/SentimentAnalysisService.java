package com.codeskeptic.scanner.service;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.cloud.language.v1.AnalyzeSentimentResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.LanguageServiceClient;

import jakarta.annotation.PreDestroy;

// Ported from backend/app/services/sentiment_analysis.py:L5-35 (faithful port) — see docs/DECISION_LOG.md
/**
 * Adapter for the Google Cloud Natural Language API and the single home of the
 * doubt-rating calculation.
 *
 * <p>Two operations are exposed. {@link #analyzeSentiment(String)} returns the
 * document sentiment score the Natural Language API reports for a piece of text.
 * {@link #calculateDoubtRating(double)} converts such a score into the doubt
 * rating persisted on the {@code tweets.doubt_rating} column. Neither signature
 * exposes a Google SDK type.
 *
 * <p>The underlying {@link LanguageServiceClient} authenticates with Application
 * Default Credentials and is created on first use by {@link #languageClient()};
 * constructing this bean resolves no credential and opens no connection.
 *
 * <p>The bean is a singleton reached from request threads, the response
 * generation scheduler and the tweet stream, and every member declared here is
 * safe for concurrent use.
 */
@Service
public class SentimentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    /**
     * Natural Language client, created on first use by {@link #languageClient()}
     * and cleared when the bean is destroyed. Written only inside a
     * {@code synchronized (this)} block and read through a {@code volatile} field
     * access.
     */
    private volatile LanguageServiceClient client;

    /**
     * Creates the service.
     *
     * <p>Takes no argument. The Python constructor at
     * backend/app/services/sentiment_analysis.py:L6-8 assigned a settings object
     * that neither of its two methods ever read. This adapter holds no
     * collaborator, reads no configuration key and touches no repository.
     */
    public SentimentAnalysisService() {
        // Intentionally empty; the Natural Language client is created lazily by languageClient().
    }

    /**
     * Returns the document sentiment score the Natural Language API reports for
     * the supplied text.
     *
     * <p>The request carries the text as {@code PLAIN_TEXT} with language
     * {@code en}. The score is returned exactly as received — unrounded,
     * unscaled and unclamped — and is the value carried by the
     * {@code analysis_result} field of the tweet analyze endpoint. A failure
     * reported by the API propagates to the caller unchanged; no substitute score
     * is returned.
     *
     * @param text the tweet text to analyse; must not be {@code null}
     * @return the document sentiment score, conventionally between {@code -1.0}
     *         (negative) and {@code 1.0} (positive)
     * @throws NullPointerException  if {@code text} is {@code null}
     * @throws IllegalStateException if the Natural Language client cannot be
     *                               created from Application Default Credentials
     */
    // Ported from backend/app/services/sentiment_analysis.py:L12-24 (faithful port). Parameter is the tweet
    // text, reconciling api/tweets.py:L46 with sentiment_analysis.py:L14 — see docs/DECISION_LOG.md DL-036.
    // Return value is the bare document score — see docs/DECISION_LOG.md DL-037.
    public double analyzeSentiment(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank()) {
            log.warn("Analysing blank text; the Natural Language API request is issued unchanged");
        }
        log.info("Requesting document sentiment for {} character(s) of text", text.length());

        Document document = Document.newBuilder()
                .setContent(text)
                .setType(Document.Type.PLAIN_TEXT)
                .setLanguage("en")
                .build();

        try {
            AnalyzeSentimentResponse response = languageClient().analyzeSentiment(document);
            // getScore() is declared float; the widening to double is lossless.
            double score = response.getDocumentSentiment().getScore();
            log.info("Natural Language API returned document sentiment score {}", score);
            return score;
        } catch (RuntimeException e) {
            log.error("Sentiment analysis failed for {} character(s) of text", text.length(), e);
            throw e;
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
     * @param sentimentScore a document sentiment score, such as one returned by
     *                       {@link #analyzeSentiment(String)}
     * @return the doubt rating, always between {@code 0.0} and {@code 10.0}
     *         inclusive
     */
    // Ported from backend/app/services/sentiment_analysis.py:L26-35 (faithful port) — see docs/DECISION_LOG.md
    public double calculateDoubtRating(double sentimentScore) {
        double doubtRating = (1 - sentimentScore) * 5;          // sentiment_analysis.py:L29
        return Math.max(0.0d, Math.min(10.0d, doubtRating));    // sentiment_analysis.py:L32
    }

    /**
     * Returns the Natural Language client, creating it from Application Default
     * Credentials on first use and reusing it thereafter.
     *
     * <p>Creation happens on first use rather than during bean construction.
     * Access uses double-checked locking over the {@code volatile} field. At most
     * one client is created however many threads call this method concurrently.
     *
     * <p>This method is the seam tests override: a Mockito spy stubs it, or a
     * subclass returns a stub. It is declared neither {@code private} nor
     * {@code final}.
     *
     * @return the Natural Language client, never {@code null}
     * @throws IllegalStateException if the client cannot be created, including
     *                               when no Application Default Credentials are
     *                               available
     */
    // Replaces the eager `self.client = LanguageServiceClient()` at
    // backend/app/services/sentiment_analysis.py:L8 — see docs/DECISION_LOG.md
    protected LanguageServiceClient languageClient() {
        LanguageServiceClient local = this.client;
        if (local == null) {
            synchronized (this) {
                local = this.client;
                if (local == null) {
                    log.info("Creating the Natural Language API client from Application Default Credentials");
                    try {
                        local = LanguageServiceClient.create();
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
     * Releases the Natural Language client when the bean is destroyed, and only
     * if {@link #languageClient()} ever created one.
     *
     * <p>A failure to close is logged at {@code WARN} and not propagated.
     */
    @PreDestroy
    void closeLanguageClient() {
        LanguageServiceClient local;
        synchronized (this) {
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
            log.warn("Failed to close the Natural Language API client", e);
        }
    }
}
