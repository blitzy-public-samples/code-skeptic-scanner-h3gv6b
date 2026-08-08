package com.codeskeptic.scanner.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.service.AnalyticsService;

// Endpoint contract ported from backend/app/api/analytics.py:L7-25 (faithful port) — see
// docs/DECISION_LOG.md DL-021, DL-041, DL-042, DL-059
/**
 * Serves the unprefixed {@code GET /analytics/trends} and {@code GET /analytics/summary} routes from
 * {@code backend/app/api/analytics.py:L7-25}, each answering 200 with one JSON object.
 *
 * <p>Neither route declares a query parameter, a path variable, a request body or a header:
 * {@code get_trends()} at {@code :L14} and {@code get_summary()} at {@code :L24} are argument-less, as
 * are both operations of {@link AnalyticsService}, so a query parameter a client appends is not bound
 * and leaves the body unchanged — DL-042. The blueprint declared these two routes and no third, so
 * {@code GET /analytics} and {@code GET /analytics/} answer 404 with {@code {"error": "Not found"}} —
 * the literal of {@code backend/app/main.py:L33}.
 *
 * <p>Each handler selects status 200 and renders the record {@link AnalyticsService} returned, matching
 * {@code jsonify(trend_data)} at {@code :L15} and {@code jsonify(summary_data)} at {@code :L25}. This
 * class computes, rounds, formats, reorders, filters and paginates nothing and holds no memoised
 * result. Neither source route has a 400 or a 404 branch; a failure beneath either handler propagates
 * to {@link GlobalExceptionHandler}, which answers 500 with {@code {"error": "Internal server error"}}.
 *
 * <p>The injected singleton stands in for the per-request {@code AnalyticsService()} at {@code :L13}
 * and {@code :L23}, and authentication is enforced by the security filter chain in place of the bare
 * {@code @jwt_required} at {@code :L8} and {@code :L18} — DL-021. The {@code tweets},
 * {@code responses} and {@code ai_tools} tables are reached through {@code service.AnalyticsService}
 * only.
 *
 * <p>Singleton bean holding one collaborator in a final field and no other state, so every member
 * declared here is safe for concurrent use.
 *
 * @see AnalyticsService
 * @see TrendsDto
 * @see SummaryDto
 */
@RestController
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = Objects.requireNonNull(analyticsService,
                "analyticsService must not be null.");
    }

    // Ported from backend/app/api/analytics.py:L7-15 (faithful port) — DL-042 — see
    // docs/DECISION_LOG.md
    /**
     * Renders the day-bucketed trend series.
     *
     * <p>Reproduces {@code GET /analytics/trends} at {@code backend/app/api/analytics.py:L7-15}. The
     * body is the single-key {@code trends} envelope of {@link TrendsDto}; each element carries
     * {@code date}, {@code tweet_count}, {@code average_doubt_rating} and {@code total_likes}, in
     * ascending {@code date} order. The status is 200 whether the series holds elements or not, and an
     * empty series renders {@code {"trends":[]}}.
     *
     * <p>The route reads nothing from the request, as at {@code backend/app/api/analytics.py:L9}. The
     * observation window is the {@code scanner.analytics.trend-window-days} property, default 30, read
     * by {@link AnalyticsService}.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"trends":[
     *   {"date":"2026-07-31","tweet_count":4,"average_doubt_rating":6.5,"total_likes":812},
     *   {"date":"2026-08-01","tweet_count":12,"average_doubt_rating":7.25,"total_likes":1480}
     * ]}
     * }</pre>
     *
     * @return 200 carrying the {@code trends} envelope; the array holds no element when no tweet falls
     *         inside the observation window
     */
    @GetMapping("/analytics/trends")
    public ResponseEntity<TrendsDto> getTrends() {
        return ResponseEntity.ok(analyticsService.getTrends());
    }

    // Ported from backend/app/api/analytics.py:L17-25 (faithful port) — DL-041 — see
    // docs/DECISION_LOG.md
    /**
     * Renders the seven summary metrics.
     *
     * <p>Reproduces {@code GET /analytics/summary} at {@code backend/app/api/analytics.py:L17-25}. The
     * body is one {@link SummaryDto} object carrying {@code total_tweets}, {@code total_responses},
     * {@code approved_responses}, {@code pending_responses}, {@code average_doubt_rating},
     * {@code average_like_count} and {@code tracked_ai_tools} — see docs/DECISION_LOG.md DL-041. Member
     * names are snake_case.
     *
     * <p>The route reads nothing from the request.
     *
     * <p>Every count is rendered as a number and is never {@code null}. An empty database renders 0 for
     * each of the five counts; {@code average_doubt_rating} and {@code average_like_count} render
     * {@code null} when no row carries the averaged column. The status is 200 in each case.
     *
     * @return 200 carrying the seven metrics; the two means are {@code null} when no row carries the
     *         averaged column
     */
    @GetMapping("/analytics/summary")
    public ResponseEntity<SummaryDto> getSummary() {
        return ResponseEntity.ok(analyticsService.getSummary());
    }
}
