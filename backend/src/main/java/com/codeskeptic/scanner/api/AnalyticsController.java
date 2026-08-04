package com.codeskeptic.scanner.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.service.AnalyticsService;

// Ported from backend/app/api/analytics.py:L1-25 (faithful port) — see docs/DECISION_LOG.md
/**
 * Serves the two HTTP routes of the {@code analytics} resource.
 *
 * <p>Replaces the Flask blueprint {@code analytics_bp}, declared at
 * {@code backend/app/api/analytics.py:L5} and registered on the application object at
 * {@code backend/app/main.py:L29}. The two routes declared here are the two the blueprint declared.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code GET /analytics/trends}</td>
 *   <td>{@link #getTrends()}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/analytics.py:L7-15}</td>
 * </tr>
 * <tr>
 *   <td>{@code GET /analytics/summary}</td>
 *   <td>{@link #getSummary()}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/analytics.py:L17-25}</td>
 * </tr>
 * </table>
 *
 * <p>Both paths are unprefixed, as the blueprint spelled them: no {@code /api} segment and no version
 * segment. Each path is spelled in full on its own handler.
 * {@code documentation/Technical Specifications.md:L386-387} documents the same two routes carrying an
 * {@code /api} prefix; the routes served here carry none — see docs/DECISION_LOG.md DL-059.
 *
 * <p>Neither route declares a query parameter, a path variable, a request body or a header.
 * {@code backend/app/api/analytics.py:L1} imports {@code Blueprint} and {@code jsonify} from Flask and
 * does not import {@code request}; {@code get_trends()} at {@code :L14} and {@code get_summary()} at
 * {@code :L24} are argument-less, as are both operations of {@link AnalyticsService}. A query parameter
 * a client appends is not bound and leaves the body unchanged — see docs/DECISION_LOG.md DL-042.
 *
 * <p>The blueprint declared these two routes and no third. {@code GET /analytics} and
 * {@code GET /analytics/} are not declared here; {@link GlobalExceptionHandler} answers each with 404
 * and {@code {"error": "Not found"}}, the literal of {@code backend/app/main.py:L33}. The "Top AI Tools
 * Mentioned" and "Response Effectiveness" panels at
 * {@code documentation/Technical Specifications.md:L437-441} are interface descriptions, and no route
 * here carries them.
 *
 * <p>Each handler is a pass-through: it selects the status 200 and renders the record
 * {@link AnalyticsService} returned, matching {@code jsonify(trend_data)} at
 * {@code backend/app/api/analytics.py:L15} and {@code jsonify(summary_data)} at {@code :L25}. This
 * class computes, rounds, formats, reorders, filters and paginates nothing, and holds no memoised
 * result.
 *
 * <p>This class selects no other status and builds no error body. {@code backend/app/api/analytics.py}
 * declares no {@code try}, no {@code except} and no status other than the 200 its {@code jsonify}
 * carries at {@code :L15} and {@code :L25}, and neither route has a 400 or a 404 branch. A failure
 * raised beneath either handler propagates to {@link GlobalExceptionHandler}, which answers 500 with
 * {@code {"error": "Internal server error"}}, the literal of {@code backend/app/main.py:L37}.
 *
 * <p>{@code AnalyticsService()} was instantiated per request at
 * {@code backend/app/api/analytics.py:L13} and {@code :L23}; the injected singleton below stands in for
 * both sites.
 *
 * <p>Authentication is enforced by the security filter chain, which runs ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/analytics.py:L8} and {@code :L18} — see docs/DECISION_LOG.md DL-021. The
 * chain's {@code anyRequest().authenticated()} rule covers both routes, and no authorization
 * annotation is declared on this class or on either handler.
 *
 * <p>This class reaches the {@code tweets}, {@code responses} and {@code ai_tools} tables through
 * {@code service.AnalyticsService} only.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-041, DL-042
 * and DL-059; construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Its one collaborator is held in a final field and is itself a singleton,
 * and this class holds no other state, so every member declared here is safe for concurrent use.
 *
 * @see AnalyticsService
 * @see TrendsDto
 * @see SummaryDto
 */
@RestController
public class AnalyticsController {

    /**
     * Computes both reports as aggregates over the {@code tweets}, {@code responses} and
     * {@code ai_tools} tables.
     */
    private final AnalyticsService analyticsService;

    /**
     * Creates the controller with its one collaborator, replacing the per-request
     * {@code AnalyticsService()} instantiation at {@code backend/app/api/analytics.py:L13} and
     * {@code :L23}.
     *
     * @param analyticsService the service serving both routes, must not be {@code null}
     * @throws NullPointerException when {@code analyticsService} is {@code null}
     */
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = Objects.requireNonNull(analyticsService,
                "analyticsService must not be null.");
    }

    // backend/app/api/analytics.py:L7 — observation window is the scanner.analytics.trend-window-days
    // property, default 30, read by service.AnalyticsService — DL-042
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
     * by {@link AnalyticsService} — see docs/DECISION_LOG.md DL-042.
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
        // backend/app/api/analytics.py:L14 get_trends() takes no arguments; :L15 renders it verbatim
        return ResponseEntity.ok(analyticsService.getTrends());
    }

    // backend/app/api/analytics.py:L17 — the seven metrics are the set of DL-041; total_tweets and
    // total_responses are named at backend/tests/test_api.py:L50-51
    /**
     * Renders the seven summary metrics.
     *
     * <p>Reproduces {@code GET /analytics/summary} at {@code backend/app/api/analytics.py:L17-25}. The
     * body is one {@link SummaryDto} object carrying {@code total_tweets}, {@code total_responses},
     * {@code approved_responses}, {@code pending_responses}, {@code average_doubt_rating},
     * {@code average_like_count} and {@code tracked_ai_tools} — see docs/DECISION_LOG.md DL-041. Member
     * names are snake_case, as the source route served them.
     *
     * <p>The route reads nothing from the request, as at {@code backend/app/api/analytics.py:L19}.
     *
     * <p>Every count is rendered as a number and is never {@code null}. An empty database renders 0 for
     * each of the five counts; {@code average_doubt_rating} and {@code average_like_count} render
     * {@code null} when no row carries the averaged column. The status is 200 in each case.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"total_tweets":128,"total_responses":94,"approved_responses":61,"pending_responses":33,
     *  "average_doubt_rating":6.84,"average_like_count":247.5,"tracked_ai_tools":7}
     * }</pre>
     *
     * @return 200 carrying the seven metrics; the two means are {@code null} when no row carries the
     *         averaged column
     */
    @GetMapping("/analytics/summary")
    public ResponseEntity<SummaryDto> getSummary() {
        // backend/app/api/analytics.py:L24 get_summary() takes no arguments; :L25 renders it verbatim
        return ResponseEntity.ok(analyticsService.getSummary());
    }
}
