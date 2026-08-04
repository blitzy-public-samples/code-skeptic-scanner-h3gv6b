package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body of {@code POST /tweets/{tweetId}/analyze}.
 *
 * <p>Carries exactly two keys, in declaration order, named in snake_case:
 *
 * <pre>{@code {"tweet_id":"1","analysis_result":-0.4}}</pre>
 *
 * <p>The doubt rating derived from the score — {@code (1 - sentimentScore) * 5}
 * clamped to {@code [0, 10]}, transcribed at
 * backend/app/services/sentiment_analysis.py:L26-35 — is applied by the
 * analysis service and is not a component of this body.
 *
 * @param tweetId        the {@code tweet_id} value: the request path segment
 *                       exactly as received, neither parsed nor normalised
 *                       (backend/app/api/tweets.py:L53). A non-numeric segment
 *                       is carried through unchanged.
 * @param analysisResult the {@code analysis_result} value: the document
 *                       sentiment score produced by the analysis service
 *                       (backend/app/api/tweets.py:L54;
 *                       backend/app/services/sentiment_analysis.py:L24).
 *                       May be {@code null}.
 */
// Ported from backend/app/api/tweets.py:L52-55 (faithful port) — see docs/DECISION_LOG.md DL-037
public record AnalysisResultDto(
        @JsonProperty("tweet_id") String tweetId,
        @JsonProperty("analysis_result") Double analysisResult) {
}
