package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
 * at {@code :L14}. Both are argument-less, and {@code documentation/Code Structure.md:L557,L586}
 * records {@code "parameters": []} for each. Neither analytics route declares a query parameter.
 *
 * <p>Every value the two operations report is an aggregate the database computes: a row count, a
 * derived count, an {@code avg} or a {@code sum}. No entity is loaded to be counted in Java, no
 * collection is traversed and no association is initialised. The reports read the pre-existing
 * {@code tweets}, {@code responses} and {@code ai_tools} tables through their repositories and
 * contribute no column, table, index, view or memoised result.
 *
 * <p>Each operation demarcates its own read-only transaction and constructs its wire record inside
 * it; {@code spring.jpa.open-in-view} is {@code false}. What leaves this class is an immutable
 * record of boxed numbers, plus one {@code LocalDate} per trend bucket.
 *
 * <p>Where an {@code avg} or a {@code sum} yields {@code null} — the state of an empty table, and of
 * a bucket in which no row carries the aggregated column — the reported value is
 * {@value #ABSENT_AVERAGE} or {@value #ABSENT_TOTAL} respectively. An empty database reports zeros
 * for every metric and an empty observation window reports an empty series. No value either
 * operation reports is {@code null}.
 *
 * <p>This class selects no HTTP status, mints no client-visible message and catches no exception. A
 * failure raised by the persistence layer propagates to {@code api.GlobalExceptionHandler}. It opens
 * no connection to an external system, holds no scheduled or asynchronous entry point, and declares
 * no operation that publishes to X.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-041, DL-042,
 * DL-052 and DL-075; this file's target-to-source row in {@code docs/TRACEABILITY_MATRIX.md} reads
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
 * Each reports the rows committed at the moment its own transaction reads them.
 *
 * @see SummaryDto
 * @see TrendsDto
 */
@Service
public class AnalyticsService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /**
     * Reported in place of an {@code avg} aggregate that yielded {@code null}: the mean of
     * {@code tweets.doubt_rating} or {@code tweets.like_count} over an empty {@code tweets} table,
     * and the mean of {@code tweets.doubt_rating} over a trend bucket in which no row carries one —
     * DL-075 — see docs/DECISION_LOG.md.
     */
    private static final double ABSENT_AVERAGE = 0.0d;

    /**
     * Reported in place of a {@code sum} aggregate that yielded {@code null}: the total of
     * {@code tweets.like_count} over a trend bucket in which no row carries one — DL-075 — see
     * docs/DECISION_LOG.md.
     */
    private static final long ABSENT_TOTAL = 0L;

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
     *       ({@code backend/app/db/models.py:L14}), and {@value #ABSENT_AVERAGE} when no row
     *       carries one.
     *   <li>{@code average_like_count} — the mean of {@code tweets.like_count}
     *       ({@code backend/app/db/models.py:L12}), and {@value #ABSENT_AVERAGE} when no row
     *       carries one.
     *   <li>{@code tracked_ai_tools} — the number of {@code ai_tools} rows
     *       ({@code backend/app/db/models.py:L32-37}).
     * </ul>
     *
     * <p>Six of the seven are issued as separate aggregate queries against the three tables;
     * {@code pending_responses} is arithmetic over two values already read. Every count is
     * non-negative, and {@code pending_responses} is non-negative for any pair of counts read from
     * one consistent snapshot.
     *
     * <p>An empty database yields {@code 0} for all five counts and {@value #ABSENT_AVERAGE} for
     * both means. No component of the returned record is {@code null}.
     *
     * @return the seven metrics, never {@code null}
     */
    @Transactional(readOnly = true)
    public SummaryDto getSummary() {
        long totalTweets = tweetRepository.count();
        long totalResponses = responseRepository.count();
        long approvedResponses = responseRepository.countByIsApprovedTrue();

        // pending_responses is the complement of the approved count over the is_approved column at
        // backend/app/db/models.py:L26 — DL-041
        long pendingResponses = totalResponses - approvedResponses;

        // TweetRepository documents both avg(...) results as null when no row carries the column
        double averageDoubtRating = averageOrAbsent(tweetRepository.findAverageDoubtRating());
        double averageLikeCount = averageOrAbsent(tweetRepository.findAverageLikeCount());

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
     * 30, read through {@link ScannerProperties}. Its cutoff is that many days before the current
     * instant and is computed here on each call; this operation takes no argument, and neither
     * analytics route declares a query parameter.
     *
     * <p>One element is produced per calendar day on which at least one {@code tweets} row was
     * created at or after the cutoff, in ascending day order. A day on which no row was created
     * produces no element, so the series is sparse and holds at most one element per day of the
     * window. A row whose {@code created_at} is {@code null} appears in no bucket.
     *
     * <p>Each element carries the bucket day taken from {@code tweets.created_at}
     * ({@code backend/app/db/models.py:L13}), the number of rows created on it, the mean of
     * {@code tweets.doubt_rating} over them — {@value #ABSENT_AVERAGE} when none carries one — and
     * the total of {@code tweets.like_count} over them — {@value #ABSENT_TOTAL} when none carries
     * one.
     *
     * <p>A window containing no row yields an envelope holding an empty list. Neither the envelope,
     * nor its list, nor any component of any element is {@code null}. A window configured as zero or
     * negative places the cutoff at or after the current instant.
     *
     * @return the series in ascending day order, never {@code null}
     */
    @Transactional(readOnly = true)
    public TrendsDto getTrends() {
        int windowDays = properties.analytics().trendWindowDays();
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        List<TrendsDto.TrendPoint> trends = tweetRepository.findDailyTrendsSince(since).stream()
                .map(bucket -> new TrendsDto.TrendPoint(bucket.getBucketDate(),
                        bucket.getTweetCount(),
                        averageOrAbsent(bucket.getAverageDoubtRating()),
                        totalOrAbsent(bucket.getTotalLikes())))
                .toList();

        log.debug("Analytics trends: {} daily bucket(s) over the {}-day window opening at {}.",
                trends.size(),
                windowDays,
                since);

        return new TrendsDto(trends);
    }

    /**
     * Returns {@code average} unboxed, and {@value #ABSENT_AVERAGE} when it is {@code null}.
     *
     * <p>{@link TweetRepository#findAverageDoubtRating()},
     * {@link TweetRepository#findAverageLikeCount()} and
     * {@link TweetRepository.DailyTrend#getAverageDoubtRating()} each yield {@code null} when no row
     * in their scope carries the averaged column — DL-075 — see docs/DECISION_LOG.md.
     *
     * @param average the mean an {@code avg} aggregate yielded, possibly {@code null}
     * @return the mean, and {@value #ABSENT_AVERAGE} when {@code average} is {@code null}
     */
    private static double averageOrAbsent(Double average) {
        return (average == null) ? ABSENT_AVERAGE : average;
    }

    /**
     * Returns {@code total} unboxed, and {@value #ABSENT_TOTAL} when it is {@code null}.
     *
     * <p>{@link TweetRepository.DailyTrend#getTotalLikes()} yields {@code null} when no row in the
     * bucket carries {@code tweets.like_count} — DL-075 — see docs/DECISION_LOG.md.
     *
     * @param total the total a {@code sum} aggregate yielded, possibly {@code null}
     * @return the total, and {@value #ABSENT_TOTAL} when {@code total} is {@code null}
     */
    private static long totalOrAbsent(Long total) {
        return (total == null) ? ABSENT_TOTAL : total;
    }
}
