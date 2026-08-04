package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Response body of {@code POST /tweets/{tweetId}/analyze}.
 *
 * <p>Carries exactly two snake_case keys, in declaration order:
 *
 * <pre>{@code {"tweet_id":"1","analysis_result":-0.4}}</pre>
 *
 * @param tweetId        the decimal {@code tweet_id} value of the row that was analysed; a
 *                       non-numeric or unknown path value is rejected before this record is built
 * @param analysisResult the {@code analysis_result} value: the document
 *                       sentiment score
 *                       (backend/app/api/tweets.py:L54;
 *                       backend/app/services/sentiment_analysis.py:L24). The
 *                       producing method
 *                       {@code service.SentimentAnalysisService.analyzeSentiment}
 *                       returns a primitive {@code double}.
 */
// Ported from backend/app/api/tweets.py:L52-55 (faithful port) — see docs/DECISION_LOG.md DL-037
public record AnalysisResultDto(
        @JsonProperty("tweet_id") String tweetId,
        @JsonProperty("analysis_result") double analysisResult) {

    /**
     * Rejects a null {@code tweetId}.
     *
     * @throws NullPointerException if {@code tweetId} is null
     */
    public AnalysisResultDto {
        Objects.requireNonNull(tweetId, "tweetId must not be null.");
    }
}
