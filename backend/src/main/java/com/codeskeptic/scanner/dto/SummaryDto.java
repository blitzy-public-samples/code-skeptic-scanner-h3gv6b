package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body served with HTTP 200 by {@code GET /analytics/summary}.
 *
 * <p>Seven metrics, each an aggregate over the pre-existing {@code tweets}, {@code responses} and
 * {@code ai_tools} tables. JSON keys are snake_case, as served by the source route. The producing
 * route takes no query parameters — {@code get_summary()} at
 * {@code backend/app/api/analytics.py:L24} is zero-argument.
 *
 * @param totalTweets        {@code COUNT(*)} over {@code tweets}
 * @param totalResponses     {@code COUNT(*)} over {@code responses}
 * @param approvedResponses  {@code COUNT(*)} over {@code responses} where {@code is_approved} is
 *                           {@code true} (backend/app/db/models.py:L26)
 * @param pendingResponses   {@code totalResponses} minus {@code approvedResponses}
 * @param averageDoubtRating {@code AVG(tweets.doubt_rating)} (backend/app/db/models.py:L14);
 *                           {@code null} when {@code tweets} holds no rows
 * @param averageLikeCount   {@code AVG(tweets.like_count)} (backend/app/db/models.py:L12);
 *                           {@code null} when {@code tweets} holds no rows
 * @param trackedAiTools     {@code COUNT(*)} over {@code ai_tools} (backend/app/db/models.py:L32-37)
 */
// Net-new (the source imported AnalyticsService at backend/app/api/analytics.py:L3 but no such class
// existed, so the body shape at :L23-25 was undeclared and the metric set is defined here) — see
// docs/DECISION_LOG.md DL-041
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
