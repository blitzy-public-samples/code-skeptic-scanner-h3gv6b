package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body served with HTTP 200 by {@code GET /analytics/summary}.
 *
 * <p>The Python route instantiated {@code AnalyticsService}
 * (backend/app/api/analytics.py:L23), called the zero-argument {@code get_summary()} (:L24) and
 * returned {@code jsonify(summary_data)} (:L25). {@code AnalyticsService} was imported at :L3;
 * no such class existed anywhere in the repository, and the source declares no shape for this
 * body.
 *
 * <p>{@code total_tweets} and {@code total_responses} are asserted by name at
 * backend/tests/test_api.py:L50-51.
 *
 * <p>All seven metrics are aggregates over the pre-existing {@code tweets}, {@code responses} and
 * {@code ai_tools} tables. They are computed by {@code service/AnalyticsService}; this record
 * carries the computed values unchanged and declares no behaviour of its own.
 *
 * <p>The producing route takes no query parameters; {@code get_summary()} is zero-argument. JSON
 * keys are snake_case, as served by the Python route.
 *
 * @param totalTweets        {@code COUNT(*)} over {@code tweets}
 * @param totalResponses     {@code COUNT(*)} over {@code responses}
 * @param approvedResponses  {@code COUNT(*)} over {@code responses} where {@code is_approved} is
 *                           {@code true} (backend/app/db/models.py:L26)
 * @param pendingResponses   {@code totalResponses} minus {@code approvedResponses}, derived by the
 *                           service rather than separately queried
 * @param averageDoubtRating {@code AVG(tweets.doubt_rating)} (backend/app/db/models.py:L14);
 *                           {@code null} when {@code tweets} holds no rows
 * @param averageLikeCount   {@code AVG(tweets.like_count)} (backend/app/db/models.py:L12);
 *                           {@code null} when {@code tweets} holds no rows
 * @param trackedAiTools     {@code COUNT(*)} over {@code ai_tools} (backend/app/db/models.py:L32-37)
 */
// Response shape for backend/app/api/analytics.py:L23-25; metric set defined here (AnalyticsService absent in source) — see docs/DECISION_LOG.md DL-041
public record SummaryDto(

        @JsonProperty("total_tweets")
        Long totalTweets,

        @JsonProperty("total_responses")
        Long totalResponses,

        @JsonProperty("approved_responses")
        Long approvedResponses,

        @JsonProperty("pending_responses")
        Long pendingResponses,

        @JsonProperty("average_doubt_rating")
        Double averageDoubtRating,

        @JsonProperty("average_like_count")
        Double averageLikeCount,

        @JsonProperty("tracked_ai_tools")
        Long trackedAiTools) {
}
