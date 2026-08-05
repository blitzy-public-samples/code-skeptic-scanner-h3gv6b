package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.TweetRepository;

// Net-new (no Python module existed; signatures dictated by backend/app/api/analytics.py:L13-14,L23-24) — see docs/DECISION_LOG.md DL-041, DL-042
/**
 * Computes the two read-only aggregate reports that back {@code GET /analytics/summary} and
 * {@code GET /analytics/trends}.
 *
 * <p>{@code backend/app/api/analytics.py:L3} imported {@code AnalyticsService} from
 * {@code app.services.analytics_service}, a module the source tree never contained. The two
 * operations declared here carry the signatures its call sites already fixed:
 * {@code analytics_service.get_summary()} at {@code :L24} and {@code analytics_service.get_trends()}
 * at {@code :L14}, both argument-less, as {@code documentation/Code Structure.md:L557,L586} declares
 * them. Neither analytics route declares a query parameter — see docs/DECISION_LOG.md DL-042.
 *
 * <p>Every value the two operations report is an aggregate the database computes: a row count, a
 * derived count, an {@code avg} or a {@code sum}. The reports read the pre-existing {@code tweets},
 * {@code responses} and {@code ai_tools} tables through their repositories and contribute no column,
 * table, index, view or memoised result.
 *
 * <p>Each operation demarcates its own read-only transaction and constructs its wire record inside it;
 * {@code spring.jpa.open-in-view} is {@code false}.
 *
 * <p>Where an {@code avg} or a {@code sum} yields {@code null} — the state of an empty table, and of a
 * bucket in which no row carries the aggregated column — the reported value is {@code null} — see
 * docs/DECISION_LOG.md DL-075. Every count is a {@code count(...)} and is never {@code null}, so an
 * empty database reports zero for each count and {@code null} for both means, and an empty observation
 * window reports an empty series.
 *
 * <p>This class selects no HTTP status and mints no client-visible message; a failure raised by the
 * persistence layer propagates to {@code api.GlobalExceptionHandler}.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-041, DL-042,
 * DL-052, DL-075 and DL-088; this file's target-to-source row in {@code docs/TRACEABILITY_MATRIX.md} reads
 * "no source construct — net-new".
 *
 * <p>Usage:
 *
 * <pre>{@code
 * SummaryDto summary = analyticsService.getSummary();
 * TrendsDto trends = analyticsService.getTrends();
 * }</pre>
 *
 * <p>This is a singleton bean. Its four collaborators are held in final fields and are themselves
 * singletons, and this class holds no other state, so both operations are safe for concurrent use.
 *
 * @see SummaryDto
 * @see TrendsDto
 */
@Service
public class AnalyticsService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /** Data access for the {@code tweets} table. */
    private final TweetRepository tweetRepository;

    /** Data access for the {@code responses} table. */
    private final ResponseRepository responseRepository;

    /** Data access for the {@code ai_tools} table. */
    private final AiToolRepository aiToolRepository;

    /** Source of the {@code scanner.analytics.trend-window-days} observation window. */
    private final ScannerProperties properties;

    /**
     * Creates the bean with its collaborators, replacing the per-request {@code AnalyticsService()}
     * instantiation at {@code backend/app/api/analytics.py:L13} and {@code :L23}.
     *
     * @param tweetRepository    data access for the {@code tweets} table, must not be {@code null}
     * @param responseRepository data access for the {@code responses} table, must not be
     *                           {@code null}
     * @param aiToolRepository   data access for the {@code ai_tools} table, must not be {@code null}
     * @param properties         bound configuration supplying the observation window, must not be
     *                           {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public AnalyticsService(TweetRepository tweetRepository,
            ResponseRepository responseRepository,
            AiToolRepository aiToolRepository,
            ScannerProperties properties) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseRepository = Objects.requireNonNull(responseRepository,
                "responseRepository must not be null.");
        this.aiToolRepository = Objects.requireNonNull(aiToolRepository,
                "aiToolRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    // Call site backend/app/api/analytics.py:L23-24 — see docs/DECISION_LOG.md DL-041
    /**
     * Returns the seven summary metrics of {@code GET /analytics/summary}.
     *
     * <p>The metrics, in the order {@link SummaryDto} declares them:
     *
     * <ul>
     *   <li>{@code total_tweets} — the number of {@code tweets} rows, the metric named at
     *       {@code backend/tests/test_api.py:L50}.
     *   <li>{@code total_responses} — the number of {@code responses} rows, the metric named at
     *       {@code backend/tests/test_api.py:L51}.
     *   <li>{@code approved_responses} — the number of {@code responses} rows whose
     *       {@code is_approved} column holds {@code true}
     *       ({@code backend/app/db/models.py:L26}).
     *   <li>{@code pending_responses} — {@code total_responses} minus {@code approved_responses},
     *       which counts both a row whose {@code is_approved} is {@code false} and a row whose
     *       {@code is_approved} is {@code null}.
     *   <li>{@code average_doubt_rating} — the mean of {@code tweets.doubt_rating}
     *       ({@code backend/app/db/models.py:L14}), and {@code null} when no row carries one.
     *   <li>{@code average_like_count} — the mean of {@code tweets.like_count}
     *       ({@code backend/app/db/models.py:L12}), and {@code null} when no row carries one.
     *   <li>{@code tracked_ai_tools} — the number of {@code ai_tools} rows
     *       ({@code backend/app/db/models.py:L32-37}).
     * </ul>
     *
     * <p>Six of the seven are issued as separate aggregate queries against the three tables and
     * {@code pending_responses} is arithmetic over two of them. All six read one repeatable-read
     * snapshot, so {@code approved_responses} never exceeds {@code total_responses} and
     * {@code pending_responses} is never negative — see docs/DECISION_LOG.md DL-091.
     *
     * <p>An empty database yields {@code 0} for all five counts and {@code null} for both means.
     *
     * @return the seven metrics, never {@code null}; the two means are {@code null} when no row
     *         carries the averaged column
     */
    // One repeatable-read snapshot spans the six aggregates — DL-091 — see docs/DECISION_LOG.md
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryDto getSummary() {
        long totalTweets = tweetRepository.count();
        long totalResponses = responseRepository.count();
        long approvedResponses = responseRepository.countByIsApprovedTrue();

        // pending_responses is the complement of the approved count over the is_approved column at
        // backend/app/db/models.py:L26 — DL-041
        long pendingResponses = totalResponses - approvedResponses;

        // Both avg(...) results are null when no row carries the column and are reported as null —
        // DL-075 — see docs/DECISION_LOG.md
        Double averageDoubtRating = tweetRepository.findAverageDoubtRating();
        Double averageLikeCount = tweetRepository.findAverageLikeCount();

        long trackedAiTools = aiToolRepository.count();

        SummaryDto summary = new SummaryDto(totalTweets,
                totalResponses,
                approvedResponses,
                pendingResponses,
                averageDoubtRating,
                averageLikeCount,
                trackedAiTools);

        log.debug("Analytics summary: total_tweets={}, total_responses={}, approved_responses={}, "
                        + "pending_responses={}, average_doubt_rating={}, average_like_count={}, "
                        + "tracked_ai_tools={}.",
                totalTweets,
                totalResponses,
                approvedResponses,
                pendingResponses,
                averageDoubtRating,
                averageLikeCount,
                trackedAiTools);

        return summary;
    }

    // Call site backend/app/api/analytics.py:L13-14 — see docs/DECISION_LOG.md DL-042
    /**
     * Returns the day-bucketed trend series of {@code GET /analytics/trends}.
     *
     * <p>The observation window is the {@code scanner.analytics.trend-window-days} property, default
     * 30, read through {@link ScannerProperties} — DL-042. Its cutoff is that many days before the
     * current instant and is computed here on each call.
     *
     * <p>One element is produced per calendar day on which at least one {@code tweets} row was created
     * at or after the cutoff, in ascending day order. A day on which no row was created produces no
     * element, and a row whose {@code created_at} is {@code null} appears in no bucket.
     *
     * <p>Each element carries the bucket day taken from {@code tweets.created_at}
     * ({@code backend/app/db/models.py:L13}), the number of rows created on it, the mean of
     * {@code tweets.doubt_rating} over them — {@code null} when none carries one — and the total of
     * {@code tweets.like_count} over them — {@code null} when none carries one.
     *
     * <p>A window containing no row yields an envelope holding an empty list. An element's day and row
     * count are never {@code null} and its two measures are {@code null} exactly when no row in the
     * bucket carries the aggregated column — see docs/DECISION_LOG.md DL-075. A window configured as
     * zero or negative places the cutoff at or after the current instant.
     *
     * @return the series in ascending day order, never {@code null}
     */
    @Transactional(readOnly = true)
    public TrendsDto getTrends() {
        int windowDays = properties.analytics().trendWindowDays();
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        List<TrendsDto.TrendPoint> trends = tweetRepository.findDailyTrendsSince(since).stream()
                // avg(...) and sum(...) are reported as they stand, null included — DL-075
                .map(bucket -> new TrendsDto.TrendPoint(bucket.getBucketDate(),
                        bucket.getTweetCount(),
                        bucket.getAverageDoubtRating(),
                        bucket.getTotalLikes()))
                .toList();

        log.debug("Analytics trends: {} daily bucket(s) over the {}-day window opening at {}.",
                trends.size(),
                windowDays,
                since);

        return new TrendsDto(trends);
    }
}
