package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /analytics/trends}.
 *
 * <p>Serialises as a single-key envelope carrying the day-bucketed series:
 *
 * <pre>{@code
 * {
 *   "trends": [
 *     {"date": "2026-08-01", "tweet_count": 12, "average_doubt_rating": 6.5,  "total_likes": 1480},
 *     {"date": "2026-08-02", "tweet_count": 0,  "average_doubt_rating": null, "total_likes": 0}
 *   ]
 * }
 * }</pre>
 *
 * <p>The series is carried verbatim from the {@code getTrends()} method of
 * {@code com.codeskeptic.scanner.service.AnalyticsService}, which takes no
 * arguments. The observation window is the configuration property
 * {@code scanner.analytics.trend-window-days}, default 30.
 *
 * <p>Keys are snake_case, matching every other body this service serves. This
 * type is outbound only: no client submits it, and it declares no validation
 * constraints.
 *
 * @param trends the day-bucketed series, in ascending {@code date} order as
 *               supplied by the producing service; an empty list when no tweet
 *               falls inside the observation window
 */
// Response shape for backend/app/api/analytics.py:L13-15; series shape defined here (AnalyticsService absent in source) — see docs/DECISION_LOG.md DL-042
public record TrendsDto(@JsonProperty("trends") List<TrendPoint> trends) {

    /**
     * One day bucket of the trend series.
     *
     * <p>Every component is an aggregate over the {@code tweets} table alone:
     * the bucket key is derived from {@code tweets.created_at}, and the three
     * measures are {@code COUNT(*)}, {@code AVG(tweets.doubt_rating)} and
     * {@code SUM(tweets.like_count)}.
     *
     * @param date               the day this bucket covers, taken from
     *                           {@code tweets.created_at}
     * @param tweetCount         {@code COUNT(*)} of the tweets in this bucket
     * @param averageDoubtRating {@code AVG(tweets.doubt_rating)} for this bucket;
     *                           {@code null} when the bucket holds no rated tweet
     * @param totalLikes         {@code SUM(tweets.like_count)} for this bucket
     */
    // Series element over the tweets columns at backend/app/db/models.py:L12-14 — see docs/DECISION_LOG.md DL-042
    public record TrendPoint(
            @JsonProperty("date") LocalDate date,
            @JsonProperty("tweet_count") Long tweetCount,
            @JsonProperty("average_doubt_rating") Double averageDoubtRating,
            @JsonProperty("total_likes") Long totalLikes) {
    }
}
