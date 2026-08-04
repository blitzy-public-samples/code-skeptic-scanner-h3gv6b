package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /analytics/trends}.
 *
 * <p>A single-key envelope carrying the day-bucketed series:
 *
 * <pre>{@code {"trends":[{"date":"2026-08-01","tweet_count":12,"average_doubt_rating":6.5,"total_likes":1480}]}}</pre>
 *
 * <p>The observation window is the configuration property
 * {@code scanner.analytics.trend-window-days}, default 30. Keys are snake_case. This type is outbound
 * only and declares no validation constraint.
 *
 * <p>{@code trends} is never {@code null}: an empty series serialises as {@code {"trends":[]}}. The
 * canonical constructor rejects {@code null} and replaces the supplied list with an unmodifiable
 * copy. A list the caller later mutates does not change this record.
 *
 * @param trends the day-bucketed series in ascending {@code date} order; an empty list when no tweet
 *               falls inside the observation window
 */
// Net-new (the source imported AnalyticsService at backend/app/api/analytics.py:L3 but no such class
// existed; the body shape at :L13-15 was undeclared and the series shape is defined here) — see
// docs/DECISION_LOG.md DL-042
public record TrendsDto(@JsonProperty("trends") List<TrendPoint> trends) {

    /**
     * Rejects a null {@code trends} and replaces it with an unmodifiable copy.
     *
     * @throws NullPointerException if {@code trends} is null or holds a null element
     */
    public TrendsDto {
        Objects.requireNonNull(trends, "trends must not be null.");
        trends = List.copyOf(trends);
    }

    /**
     * One day bucket of the trend series. Every component is an aggregate over the {@code tweets}
     * table alone: the bucket key derives from {@code tweets.created_at} and the three measures are
     * {@code COUNT(*)}, {@code AVG(tweets.doubt_rating)} and {@code SUM(tweets.like_count)}.
     *
     * @param date               the day this bucket covers, taken from
     *                           {@code tweets.created_at}
     * @param tweetCount         {@code COUNT(*)} of the tweets in this bucket
     * @param averageDoubtRating {@code AVG(tweets.doubt_rating)} for this bucket;
     *                           {@code null} when the bucket holds no rated tweet
     * @param totalLikes         {@code SUM(tweets.like_count)} for this bucket
     */
    // Net-new (series element over the tweets columns at backend/app/db/models.py:L12-14; no source
    // construct declared it) — see docs/DECISION_LOG.md DL-042
    public record TrendPoint(
            @JsonProperty("date") LocalDate date,
            @JsonProperty("tweet_count") Long tweetCount,
            @JsonProperty("average_doubt_rating") Double averageDoubtRating,
            @JsonProperty("total_likes") Long totalLikes) {
    }
}
