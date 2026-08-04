package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * Exercises the two operations {@link AnalyticsService} exposes: {@link AnalyticsService#getSummary()}
 * and {@link AnalyticsService#getTrends()}.
 *
 * <p>Every collaborator is a Mockito double, so no Spring context is started and no database,
 * network, filesystem or credential resource is reached. Each test drives an instance this class
 * constructs directly.
 *
 * <p>Construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsService")
class AnalyticsServiceTest {

    /** Value bound to {@code scanner.analytics.trend-window-days} by every test. */
    private static final int TREND_WINDOW_DAYS = 30;

    /** Day the single trend bucket covers. */
    private static final LocalDate BUCKET_DAY = LocalDate.of(2026, 8, 1);

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private AiToolRepository aiToolRepository;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(tweetRepository, responseRepository, aiToolRepository,
                properties());
    }

    // -----------------------------------------------------------------------
    // getSummary() — absent measurements
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports a null average for each aggregate the empty tweets table yields null for")
    void reportsANullAverageForEachAggregateTheEmptyTweetsTableYieldsNullFor() {
        when(tweetRepository.count()).thenReturn(0L);
        when(responseRepository.count()).thenReturn(0L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(0L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(null);
        when(aiToolRepository.count()).thenReturn(0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isNull();
        assertThat(summary.averageLikeCount()).isNull();
    }

    @Test
    @DisplayName("reports zero for every count the empty database holds")
    void reportsZeroForEveryCountTheEmptyDatabaseHolds() {
        when(tweetRepository.count()).thenReturn(0L);
        when(responseRepository.count()).thenReturn(0L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(0L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(null);
        when(aiToolRepository.count()).thenReturn(0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.totalTweets()).isZero();
        assertThat(summary.totalResponses()).isZero();
        assertThat(summary.approvedResponses()).isZero();
        assertThat(summary.pendingResponses()).isZero();
        assertThat(summary.trackedAiTools()).isZero();
    }

    @Test
    @DisplayName("does not report a measured zero in place of an absent average")
    void doesNotReportAMeasuredZeroInPlaceOfAnAbsentAverage() {
        when(tweetRepository.count()).thenReturn(3L);
        when(responseRepository.count()).thenReturn(0L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(0L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(0.0d);
        when(aiToolRepository.count()).thenReturn(0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isNull();
        assertThat(summary.averageLikeCount()).isEqualTo(0.0d);
    }

    // -----------------------------------------------------------------------
    // getSummary() — measured values
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports every aggregate the database computed")
    void reportsEveryAggregateTheDatabaseComputed() {
        when(tweetRepository.count()).thenReturn(12L);
        when(responseRepository.count()).thenReturn(7L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(3L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(6.5d);
        when(tweetRepository.findAverageLikeCount()).thenReturn(148.25d);
        when(aiToolRepository.count()).thenReturn(4L);

        SummaryDto summary = service.getSummary();

        assertThat(summary).isEqualTo(
                new SummaryDto(12L, 7L, 3L, 4L, 6.5d, 148.25d, 4L));
    }

    @Test
    @DisplayName("reports the pending count as the total less the approved count")
    void reportsThePendingCountAsTheTotalLessTheApprovedCount() {
        when(tweetRepository.count()).thenReturn(0L);
        when(responseRepository.count()).thenReturn(7L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(3L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(null);
        when(aiToolRepository.count()).thenReturn(0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.pendingResponses()).isEqualTo(4L);
        assertThat(summary.pendingResponses())
                .isEqualTo(summary.totalResponses() - summary.approvedResponses());
        assertThat(summary.pendingResponses()).isNotNegative();
    }

    @Test
    @DisplayName("reads every summary metric from one repeatable snapshot")
    void readsEverySummaryMetricFromOneRepeatableSnapshot() throws NoSuchMethodException {
        Method getSummary = AnalyticsService.class.getDeclaredMethod("getSummary");
        Transactional declared = getSummary.getAnnotation(Transactional.class);

        assertThat(declared).isNotNull();
        assertThat(declared.readOnly()).isTrue();
        assertThat(declared.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    // -----------------------------------------------------------------------
    // getTrends()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports an empty series when the window holds no row")
    void reportsAnEmptySeriesWhenTheWindowHoldsNoRow() {
        when(tweetRepository.findDailyTrendsSince(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of());

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).isEmpty();
    }

    @Test
    @DisplayName("reports a null bucket average and a null bucket total as they stand")
    void reportsANullBucketAverageAndANullBucketTotalAsTheyStand() {
        when(tweetRepository.findDailyTrendsSince(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(bucket(BUCKET_DAY, 2L, null, null)));

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).hasSize(1);
        TrendsDto.TrendPoint point = trends.trends().get(0);
        assertThat(point.date()).isEqualTo(BUCKET_DAY);
        assertThat(point.tweetCount()).isEqualTo(2L);
        assertThat(point.averageDoubtRating()).isNull();
        assertThat(point.totalLikes()).isNull();
    }

    @Test
    @DisplayName("reports the bucket aggregates the database computed")
    void reportsTheBucketAggregatesTheDatabaseComputed() {
        when(tweetRepository.findDailyTrendsSince(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(bucket(BUCKET_DAY, 12L, 6.5d, 1480L)));

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).containsExactly(
                new TrendsDto.TrendPoint(BUCKET_DAY, 12L, 6.5d, 1480L));
    }

    /**
     * Builds the configuration root every test hands to the service. Only the
     * {@code scanner.analytics} group is populated; the service reads no other group.
     *
     * @return the configuration root
     */
    private static ScannerProperties properties() {
        return new ScannerProperties(null, 0, 0L, null, null, null, null, null,
                new ScannerProperties.Analytics(TREND_WINDOW_DAYS), null);
    }

    /**
     * Builds one projection row of the daily trend query.
     *
     * @param day                the bucket day
     * @param tweetCount         the row count of the bucket
     * @param averageDoubtRating the mean doubt rating, which may be {@code null}
     * @param totalLikes         the summed like count, which may be {@code null}
     * @return the projection row
     */
    private static TweetRepository.DailyTrend bucket(LocalDate day, Long tweetCount,
            Double averageDoubtRating, Long totalLikes) {
        return new TweetRepository.DailyTrend() {

            @Override
            public LocalDate getBucketDate() {
                return day;
            }

            @Override
            public Long getTweetCount() {
                return tweetCount;
            }

            @Override
            public Double getAverageDoubtRating() {
                return averageDoubtRating;
            }

            @Override
            public Long getTotalLikes() {
                return totalLikes;
            }
        };
    }
}
