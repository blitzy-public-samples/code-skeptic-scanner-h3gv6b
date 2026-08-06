package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.repository.AiToolRepository;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.fasterxml.jackson.annotation.JsonProperty;

// Net-new coverage of the zero-argument call sites at backend/app/api/analytics.py:L14,L24 — see
// docs/DECISION_LOG.md DL-041, DL-042, DL-075, DL-180
/**
 * Exercises the two operations {@link AnalyticsService} exposes: {@link AnalyticsService#getSummary()}
 * and {@link AnalyticsService#getTrends()}.
 *
 * <p>Every collaborator is a Mockito double, no Spring context is started and no database, network,
 * filesystem or credential resource is reached. Each stub is declared by the test that consumes it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsService")
class AnalyticsServiceTest {

    // -----------------------------------------------------------------------
    // Measured values — one distinct sentinel per reported metric
    // -----------------------------------------------------------------------

    private static final long TOTAL_TWEETS = 12L;

    private static final long TOTAL_RESPONSES = 7L;

    private static final long APPROVED_RESPONSES = 3L;

    private static final long PENDING_RESPONSES = 4L;

    private static final double AVERAGE_DOUBT_RATING = 6.5d;

    private static final double AVERAGE_LIKE_COUNT = 148.25d;

    private static final long TRACKED_AI_TOOLS = 5L;

    private static final long WIDER_TOTAL_RESPONSES = 10L;

    private static final long WIDER_APPROVED_RESPONSES = 3L;

    private static final long WIDER_PENDING_RESPONSES = 7L;

    // -----------------------------------------------------------------------
    // Observation window — scanner.analytics.trend-window-days
    // -----------------------------------------------------------------------

    private static final int CONFIGURED_TREND_WINDOW_DAYS = 30;

    private static final int SHORTER_TREND_WINDOW_DAYS = 7;

    // -----------------------------------------------------------------------
    // Fixed clock — the UTC basis the window is measured from (DL-278)
    // -----------------------------------------------------------------------

    /**
     * The instant every case measures the window from: 2026-08-06T10:30:00Z. Its UTC date is
     * 2026-08-06, its date in {@code Pacific/Midway} (UTC-11:00) is 2026-08-05 and its date in
     * {@code Pacific/Kiritimati} (UTC+14:00) is 2026-08-07, so a cutoff computed on the JVM default
     * zone rather than on UTC lands a day early in the first zone and a day late in the second.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:30:00Z");

    /** UTC date of {@link #FIXED_INSTANT}. */
    private static final LocalDate FIXED_UTC_DATE = LocalDate.of(2026, 8, 6);

    /** Clock every case injects, fixed at {@link #FIXED_INSTANT} and reading in UTC. */
    private static final Clock FIXED_UTC_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    // -----------------------------------------------------------------------
    // Day buckets of the trend series
    // -----------------------------------------------------------------------

    private static final LocalDate FIRST_BUCKET_DAY = LocalDate.of(2026, 8, 1);

    private static final LocalDate UNBUCKETED_DAY = LocalDate.of(2026, 8, 2);

    private static final LocalDate SECOND_BUCKET_DAY = LocalDate.of(2026, 8, 3);

    private static final long FIRST_BUCKET_TWEET_COUNT = 12L;

    private static final double FIRST_BUCKET_AVERAGE_DOUBT_RATING = 6.5d;

    private static final long FIRST_BUCKET_TOTAL_LIKES = 1480L;

    private static final long SECOND_BUCKET_TWEET_COUNT = 3L;

    private static final double SECOND_BUCKET_AVERAGE_DOUBT_RATING = 2.25d;

    private static final long SECOND_BUCKET_TOTAL_LIKES = 47L;

    // -----------------------------------------------------------------------
    // Structural inventories
    // -----------------------------------------------------------------------

    private static final List<String> SCHEMA_CAPABLE_TYPE_NAMES = List.of(
            "EntityManager",
            "EntityManagerFactory",
            "SessionFactory",
            "Session",
            "DataSource",
            "JdbcTemplate",
            "NamedParameterJdbcTemplate",
            "JdbcClient",
            "JdbcOperations",
            "Connection",
            "Statement",
            "Flyway",
            "Liquibase");

    private static final List<String> CACHE_TYPE_NAMES = List.of(
            "Cache",
            "CacheManager",
            "ConcurrentMapCache",
            "ConcurrentMapCacheManager",
            "CaffeineCache",
            "CaffeineCacheManager",
            "RedisCacheManager");

    private static final String CACHE_ANNOTATION_PACKAGE_PREFIX = "org.springframework.cache";

    private static final String REPOSITORY_TYPE_SUFFIX = "Repository";

    private static final String SUMMARY_OPERATION = "getSummary";

    private static final String TRENDS_OPERATION = "getTrends";

    // -----------------------------------------------------------------------
    // Collaborators
    // -----------------------------------------------------------------------

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private AiToolRepository aiToolRepository;

    @Mock
    private ScannerProperties properties;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(tweetRepository, responseRepository, aiToolRepository,
                properties, FIXED_UTC_CLOCK);
    }

    /**
     * Returns the inclusive lower bound a window of {@code windowDays} UTC dates opens at, measured
     * from {@link #FIXED_INSTANT}.
     *
     * @param windowDays the configured window width in UTC calendar dates
     * @return the expected cutoff
     */
    private static LocalDateTime expectedCutoff(int windowDays) {
        return FIXED_UTC_DATE.minusDays((long) windowDays - 1L).atStartOfDay();
    }

    // -----------------------------------------------------------------------
    // The declared surface
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("takes no argument to report the summary")
    void takesNoArgumentToReportTheSummary() throws NoSuchMethodException {
        Method summary = AnalyticsService.class.getMethod(SUMMARY_OPERATION);

        assertThat(summary.getParameterCount()).isZero();
        assertThat(summary.getParameterTypes()).isEmpty();
        assertThat(summary.getReturnType()).isEqualTo(SummaryDto.class);
    }

    @Test
    @DisplayName("takes no argument to report the trend series")
    void takesNoArgumentToReportTheTrendSeries() throws NoSuchMethodException {
        Method trends = AnalyticsService.class.getMethod(TRENDS_OPERATION);

        assertThat(trends.getParameterCount()).isZero();
        assertThat(trends.getParameterTypes()).isEmpty();
        assertThat(trends.getReturnType()).isEqualTo(TrendsDto.class);
    }

    @Test
    @DisplayName("declares one summary operation one trend operation and no other public operation")
    void declaresOneSummaryOperationOneTrendOperationAndNoOtherPublicOperation() {
        List<Method> declared = Arrays.stream(AnalyticsService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(declared).extracting(Method::getName)
                .containsExactlyInAnyOrder(SUMMARY_OPERATION, TRENDS_OPERATION);
        assertThat(declared).allSatisfy(method -> {
            assertThat(method.getParameterCount()).isZero();
            assertThat(Modifier.isStatic(method.getModifiers())).isFalse();
        });
    }

    // The clock is the fifth constructor parameter — DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("takes its three repositories, its configuration and its clock through its only "
            + "constructor")
    void takesItsThreeRepositoriesAndItsConfigurationThroughItsOnlyConstructor() {
        List<Constructor<?>> constructors = List.of(AnalyticsService.class.getDeclaredConstructors());

        assertThat(constructors).hasSize(1);
        assertThat(constructors.get(0).getParameterTypes()).containsExactly(TweetRepository.class,
                ResponseRepository.class,
                AiToolRepository.class,
                ScannerProperties.class,
                Clock.class);
    }

    // No reader of the current instant bypasses the injected clock — DL-278 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("rejects a null clock rather than falling back to the system default zone")
    void rejectsANullClock() {
        assertThatThrownBy(() -> new AnalyticsService(tweetRepository, responseRepository,
                aiToolRepository, properties, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }

    @Test
    @DisplayName("holds no data access collaborator besides the tweet response and ai tool "
            + "repositories")
    void holdsNoDataAccessCollaboratorBesidesTheTweetResponseAndAiToolRepositories() {
        assertThat(declaredCollaboratorTypes())
                .filteredOn(type -> type.getSimpleName().endsWith(REPOSITORY_TYPE_SUFFIX))
                .isNotEmpty()
                .containsOnly(TweetRepository.class, ResponseRepository.class,
                        AiToolRepository.class);
    }

    @Test
    @DisplayName("holds no collaborator that can contribute a table column an index or a view")
    void holdsNoCollaboratorThatCanContributeATableColumnAnIndexOrAView() {
        assertThat(declaredCollaboratorTypes())
                .extracting(Class::getSimpleName)
                .doesNotContainAnyElementsOf(SCHEMA_CAPABLE_TYPE_NAMES);
    }

    @Test
    @DisplayName("holds no cache collaborator and marks neither operation for caching")
    void holdsNoCacheCollaboratorAndMarksNeitherOperationForCaching() throws NoSuchMethodException {
        assertThat(declaredCollaboratorTypes())
                .extracting(Class::getSimpleName)
                .doesNotContainAnyElementsOf(CACHE_TYPE_NAMES);
        assertThat(annotationPackagesOf(AnalyticsService.class.getAnnotations()))
                .noneMatch(packageName -> packageName.startsWith(CACHE_ANNOTATION_PACKAGE_PREFIX));
        assertThat(annotationPackagesOf(
                AnalyticsService.class.getMethod(SUMMARY_OPERATION).getAnnotations()))
                .noneMatch(packageName -> packageName.startsWith(CACHE_ANNOTATION_PACKAGE_PREFIX));
        assertThat(annotationPackagesOf(
                AnalyticsService.class.getMethod(TRENDS_OPERATION).getAnnotations()))
                .noneMatch(packageName -> packageName.startsWith(CACHE_ANNOTATION_PACKAGE_PREFIX));
    }

    @Test
    @DisplayName("reads every summary metric from one repeatable read snapshot")
    void readsEverySummaryMetricFromOneRepeatableReadSnapshot() throws NoSuchMethodException {
        Transactional declared = AnalyticsService.class.getMethod(SUMMARY_OPERATION)
                .getAnnotation(Transactional.class);

        assertThat(declared).isNotNull();
        assertThat(declared.readOnly()).isTrue();
        assertThat(declared.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    @DisplayName("reads the trend series inside a read only transaction")
    void readsTheTrendSeriesInsideAReadOnlyTransaction() throws NoSuchMethodException {
        Transactional declared = AnalyticsService.class.getMethod(TRENDS_OPERATION)
                .getAnnotation(Transactional.class);

        assertThat(declared).isNotNull();
        assertThat(declared.readOnly()).isTrue();
    }

    // -----------------------------------------------------------------------
    // getSummary() — the reported metric set
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports seven metrics and no eighth")
    void reportsSevenMetricsAndNoEighth() {
        List<RecordComponent> components = List.of(SummaryDto.class.getRecordComponents());

        assertThat(components).hasSize(7);
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly("totalTweets",
                        "totalResponses",
                        "approvedResponses",
                        "pendingResponses",
                        "averageDoubtRating",
                        "averageLikeCount",
                        "trackedAiTools");
    }

    @Test
    @DisplayName("reports every aggregate the database computed")
    void reportsEveryAggregateTheDatabaseComputed() {
        stubTheMeasuredDatabase();

        SummaryDto summary = service.getSummary();

        assertThat(summary.totalTweets()).isEqualTo(TOTAL_TWEETS);
        assertThat(summary.totalResponses()).isEqualTo(TOTAL_RESPONSES);
        assertThat(summary.approvedResponses()).isEqualTo(APPROVED_RESPONSES);
        assertThat(summary.pendingResponses()).isEqualTo(PENDING_RESPONSES);
        assertThat(summary.averageDoubtRating()).isEqualTo(AVERAGE_DOUBT_RATING);
        assertThat(summary.averageLikeCount()).isEqualTo(AVERAGE_LIKE_COUNT);
        assertThat(summary.trackedAiTools()).isEqualTo(TRACKED_AI_TOOLS);
        assertThat(summary).isEqualTo(new SummaryDto(TOTAL_TWEETS,
                TOTAL_RESPONSES,
                APPROVED_RESPONSES,
                PENDING_RESPONSES,
                AVERAGE_DOUBT_RATING,
                AVERAGE_LIKE_COUNT,
                TRACKED_AI_TOOLS));
    }

    @Test
    @DisplayName("reports the tweet total under total_tweets and the response total under "
            + "total_responses")
    void reportsTheTweetTotalUnderTotalTweetsAndTheResponseTotalUnderTotalResponses() {
        stubTweetTotals(TOTAL_TWEETS, null, null);
        stubResponseTotals(TOTAL_RESPONSES, 0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.totalTweets()).isEqualTo(TOTAL_TWEETS);
        assertThat(summary.totalResponses()).isEqualTo(TOTAL_RESPONSES);
        assertThat(jsonNameOf(SummaryDto.class, "totalTweets")).isEqualTo("total_tweets");
        assertThat(jsonNameOf(SummaryDto.class, "totalResponses")).isEqualTo("total_responses");
    }

    @Test
    @DisplayName("reports every metric under its snake case key")
    void reportsEveryMetricUnderItsSnakeCaseKey() {
        assertThat(jsonNamesOf(SummaryDto.class))
                .containsExactly("total_tweets",
                        "total_responses",
                        "approved_responses",
                        "pending_responses",
                        "average_doubt_rating",
                        "average_like_count",
                        "tracked_ai_tools");
    }

    @Test
    @DisplayName("reports the pending count as the total less the approved count")
    void reportsThePendingCountAsTheTotalLessTheApprovedCount() {
        stubTweetTotals(0L, null, null);
        stubResponseTotals(WIDER_TOTAL_RESPONSES, WIDER_APPROVED_RESPONSES);

        SummaryDto summary = service.getSummary();

        assertThat(summary.pendingResponses()).isEqualTo(WIDER_PENDING_RESPONSES);
        assertThat(summary.pendingResponses())
                .isEqualTo(summary.totalResponses() - summary.approvedResponses());
        assertThat(summary.pendingResponses()).isNotNegative();
        verify(responseRepository).findApprovalCounts();
        verifyNoMoreInteractions(responseRepository);
    }

    @Test
    @DisplayName("reports the approved count from the approval flag alone")
    void reportsTheApprovedCountFromTheApprovalFlagAlone() {
        stubTweetTotals(0L, null, null);
        stubResponseTotals(TOTAL_RESPONSES, APPROVED_RESPONSES);

        SummaryDto summary = service.getSummary();

        assertThat(summary.approvedResponses()).isEqualTo(APPROVED_RESPONSES);
        verify(responseRepository).findApprovalCounts();
        assertThat(booleanFieldNamesOf(Response.class)).containsExactly("isApproved");
        assertThat(recordComponentTypesOf(SummaryDto.class))
                .noneMatch(type -> type == Boolean.class || type == boolean.class);
    }

    @Test
    @DisplayName("issues one aggregate query per measured table and no other query")
    void issuesOneAggregateQueryPerMeasuredTableAndNoOtherQuery() {
        stubTheMeasuredDatabase();

        service.getSummary();

        verify(tweetRepository).findAggregates();
        verify(responseRepository).findApprovalCounts();
        verify(aiToolRepository).count();
        verifyNoMoreInteractions(tweetRepository, responseRepository, aiToolRepository);
        verifyNoInteractions(properties);
    }

    @Test
    @DisplayName("reports the counts each call reads rather than repeating an earlier report")
    void reportsTheCountsEachCallReadsRatherThanRepeatingAnEarlierReport() {
        when(tweetRepository.findAggregates()).thenReturn(
                new TweetTotals(TOTAL_TWEETS, null, null),
                new TweetTotals(WIDER_TOTAL_RESPONSES, null, null));
        when(responseRepository.findApprovalCounts()).thenReturn(
                new ResponseTotals(TOTAL_RESPONSES, APPROVED_RESPONSES),
                new ResponseTotals(WIDER_TOTAL_RESPONSES, WIDER_APPROVED_RESPONSES));
        when(aiToolRepository.count()).thenReturn(TRACKED_AI_TOOLS, TRACKED_AI_TOOLS + 2L);

        SummaryDto first = service.getSummary();
        SummaryDto second = service.getSummary();

        assertThat(first.totalTweets()).isEqualTo(TOTAL_TWEETS);
        assertThat(second.totalTweets()).isEqualTo(WIDER_TOTAL_RESPONSES);
        assertThat(first.totalResponses()).isEqualTo(TOTAL_RESPONSES);
        assertThat(second.totalResponses()).isEqualTo(WIDER_TOTAL_RESPONSES);
        assertThat(first.pendingResponses()).isEqualTo(PENDING_RESPONSES);
        assertThat(second.pendingResponses()).isEqualTo(WIDER_PENDING_RESPONSES);
        assertThat(first.trackedAiTools()).isEqualTo(TRACKED_AI_TOOLS);
        assertThat(second.trackedAiTools()).isEqualTo(TRACKED_AI_TOOLS + 2L);
        assertThat(second).isNotEqualTo(first);
        verify(tweetRepository, times(2)).findAggregates();
        verify(responseRepository, times(2)).findApprovalCounts();
        verify(aiToolRepository, times(2)).count();
    }

    // -----------------------------------------------------------------------
    // getSummary() — the empty database
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports the summary without raising when no tweet is stored")
    void reportsTheSummaryWithoutRaisingWhenNoTweetIsStored() {
        stubTheEmptyDatabase();

        assertThatNoException().isThrownBy(service::getSummary);
    }

    @Test
    @DisplayName("reports no average when the empty tweets table measures none")
    void reportsNoAverageWhenTheEmptyTweetsTableMeasuresNone() {
        stubTheEmptyDatabase();

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isNull();
        assertThat(summary.averageLikeCount()).isNull();
    }

    @Test
    @DisplayName("reports zero for every count the empty database holds")
    void reportsZeroForEveryCountTheEmptyDatabaseHolds() {
        stubTheEmptyDatabase();

        SummaryDto summary = service.getSummary();

        assertThat(summary.totalTweets()).isZero();
        assertThat(summary.totalResponses()).isZero();
        assertThat(summary.approvedResponses()).isZero();
        assertThat(summary.pendingResponses()).isZero();
        assertThat(summary.trackedAiTools()).isZero();
    }

    @Test
    @DisplayName("reports a measured zero average as zero and an unmeasured average as absent")
    void reportsAMeasuredZeroAverageAsZeroAndAnUnmeasuredAverageAsAbsent() {
        stubTweetTotals(TOTAL_TWEETS, null, 0.0d);
        stubResponseTotals(0L, 0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isNull();
        assertThat(summary.averageLikeCount()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("reports the measured average unchanged")
    void reportsTheMeasuredAverageUnchanged() {
        stubTweetTotals(TOTAL_TWEETS, AVERAGE_DOUBT_RATING, AVERAGE_LIKE_COUNT);
        stubResponseTotals(0L, 0L);

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isEqualTo(AVERAGE_DOUBT_RATING);
        assertThat(summary.averageLikeCount()).isEqualTo(AVERAGE_LIKE_COUNT);
    }

    // -----------------------------------------------------------------------
    // getTrends() — the day-bucketed series
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports one series element for each day bucket the query returned")
    void reportsOneSeriesElementForEachDayBucketTheQueryReturned() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets(firstBucket(), secondBucket());

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).containsExactly(
                new TrendsDto.TrendPoint(FIRST_BUCKET_DAY, FIRST_BUCKET_TWEET_COUNT,
                        FIRST_BUCKET_AVERAGE_DOUBT_RATING, FIRST_BUCKET_TOTAL_LIKES),
                new TrendsDto.TrendPoint(SECOND_BUCKET_DAY, SECOND_BUCKET_TWEET_COUNT,
                        SECOND_BUCKET_AVERAGE_DOUBT_RATING, SECOND_BUCKET_TOTAL_LIKES));
    }

    @Test
    @DisplayName("reports each bucket day its row count its mean doubt rating and its like total")
    void reportsEachBucketDayItsRowCountItsMeanDoubtRatingAndItsLikeTotal() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets(firstBucket());

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).hasSize(1);
        TrendsDto.TrendPoint reported = trends.trends().get(0);
        assertThat(reported.date()).isEqualTo(FIRST_BUCKET_DAY);
        assertThat(reported.tweetCount()).isEqualTo(FIRST_BUCKET_TWEET_COUNT);
        assertThat(reported.averageDoubtRating()).isEqualTo(FIRST_BUCKET_AVERAGE_DOUBT_RATING);
        assertThat(reported.totalLikes()).isEqualTo(FIRST_BUCKET_TOTAL_LIKES);
    }

    @Test
    @DisplayName("reports each series element under its snake case keys")
    void reportsEachSeriesElementUnderItsSnakeCaseKeys() {
        assertThat(jsonNameOf(TrendsDto.class, "trends")).isEqualTo("trends");
        assertThat(jsonNamesOf(TrendsDto.TrendPoint.class))
                .containsExactly("date", "tweet_count", "average_doubt_rating", "total_likes");
    }

    @Test
    @DisplayName("reports the elements in the order the query returned them")
    void reportsTheElementsInTheOrderTheQueryReturnedThem() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets(secondBucket(), firstBucket());

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).extracting(TrendsDto.TrendPoint::date)
                .containsExactly(SECOND_BUCKET_DAY, FIRST_BUCKET_DAY);
    }

    @Test
    @DisplayName("reports no element for a day the query returned no bucket for")
    void reportsNoElementForADayTheQueryReturnedNoBucketFor() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets(firstBucket(), secondBucket());

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).hasSize(2);
        assertThat(trends.trends()).extracting(TrendsDto.TrendPoint::date)
                .containsExactly(FIRST_BUCKET_DAY, SECOND_BUCKET_DAY)
                .doesNotContain(UNBUCKETED_DAY);
    }

    @Test
    @DisplayName("reports an empty series when the window holds no row")
    void reportsAnEmptySeriesWhenTheWindowHoldsNoRow() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("reports an unmeasured bucket average and an unmeasured bucket total as absent")
    void reportsAnUnmeasuredBucketAverageAndAnUnmeasuredBucketTotalAsAbsent() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets(new Bucket(FIRST_BUCKET_DAY, FIRST_BUCKET_TWEET_COUNT, null, null));

        TrendsDto trends = service.getTrends();

        assertThat(trends.trends()).hasSize(1);
        TrendsDto.TrendPoint reported = trends.trends().get(0);
        assertThat(reported.date()).isEqualTo(FIRST_BUCKET_DAY);
        assertThat(reported.tweetCount()).isEqualTo(FIRST_BUCKET_TWEET_COUNT);
        assertThat(reported.averageDoubtRating()).isNull();
        assertThat(reported.totalLikes()).isNull();
    }

    // The window is a count of UTC calendar dates read from the injected clock — DL-278 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("opens the window at the start of the UTC day the configured number of dates back")
    void opensTheWindowTheConfiguredNumberOfDaysBeforeTheCall() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        service.getTrends();

        // 2026-08-06 minus 29 dates, at midnight: the 30th UTC date counting back from the clock.
        assertThat(capturedCutoff()).isEqualTo(LocalDateTime.of(2026, 7, 8, 0, 0));
        assertThat(capturedCutoff()).isEqualTo(expectedCutoff(CONFIGURED_TREND_WINDOW_DAYS));
    }

    // DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("opens the window seven UTC dates back when seven days are configured")
    void opensTheWindowSevenDaysBeforeTheCallWhenSevenDaysAreConfigured() {
        stubTheConfiguredWindow(SHORTER_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        service.getTrends();

        LocalDateTime cutoff = capturedCutoff();
        assertThat(cutoff).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        assertThat(cutoff).isEqualTo(expectedCutoff(SHORTER_TREND_WINDOW_DAYS));
        assertThat(cutoff).isAfter(expectedCutoff(CONFIGURED_TREND_WINDOW_DAYS));
        verify(properties).analytics();
        verifyNoMoreInteractions(properties);
    }

    // The cutoff is read in UTC, never in the JVM's default zone — DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("opens the window on the UTC date even when the default JVM zone is on another date")
    void opensTheWindowOnTheUtcDateWhateverTheDefaultJvmZone() {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            // At 2026-08-06T10:30:00Z the local date in Pacific/Midway (UTC-11:00) is 2026-08-05,
            // one day earlier, so a cutoff read from the default zone would open a day early.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"));
            stubTheConfiguredWindow(SHORTER_TREND_WINDOW_DAYS);
            stubTheReturnedBuckets();

            service.getTrends();

            assertThat(capturedCutoff()).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    // DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("opens the window on the UTC date even when the default JVM zone is a day ahead")
    void opensTheWindowOnTheUtcDateWhenTheDefaultJvmZoneIsADayAhead() {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            // At 2026-08-06T10:30:00Z the local date in Pacific/Kiritimati (UTC+14:00) is
            // 2026-08-07, one day later, so a cutoff read from the default zone would open a day late.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            stubTheConfiguredWindow(SHORTER_TREND_WINDOW_DAYS);
            stubTheReturnedBuckets();

            service.getTrends();

            assertThat(capturedCutoff()).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    // A window of one UTC date opens at the start of the current UTC day — DL-278 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("a one-day window opens at the start of the current UTC day, never mid-day")
    void aOneDayWindowOpensAtTheStartOfTheCurrentUtcDay() {
        stubTheConfiguredWindow(1);
        stubTheReturnedBuckets();

        service.getTrends();

        LocalDateTime cutoff = capturedCutoff();
        assertThat(cutoff).isEqualTo(FIXED_UTC_DATE.atStartOfDay());
        assertThat(cutoff.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
    }

    // The cutoff is always midnight, so the series spans whole UTC dates and never a partial day —
    // DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the window spans exactly the configured number of whole UTC dates for every width")
    void theWindowSpansExactlyTheConfiguredNumberOfWholeUtcDates() {
        for (int windowDays : new int[] {1, 2, 7, 30, 365}) {
            reset(tweetRepository, properties);
            stubTheConfiguredWindow(windowDays);
            stubTheReturnedBuckets();

            service.getTrends();

            LocalDateTime cutoff = capturedCutoff();
            assertThat(cutoff.toLocalTime()).as("cutoff time for a %d-date window", windowDays)
                    .isEqualTo(LocalTime.MIDNIGHT);
            assertThat(ChronoUnit.DAYS.between(cutoff.toLocalDate(), FIXED_UTC_DATE) + 1L)
                    .as("UTC dates the %d-date window spans", windowDays)
                    .isEqualTo(windowDays);
        }
    }

    // A non-positive window observes nothing — DL-278 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses a zero or negative observation window when the property binds")
    void refusesANonPositiveObservationWindowWhenThePropertyBinds() {
        for (int windowDays : new int[] {0, -1}) {
            assertThatThrownBy(() -> new ScannerProperties.Analytics(windowDays))
                    .as("binding a %d-day window", windowDays)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scanner.analytics.trend-window-days");
        }

        verifyNoInteractions(tweetRepository, responseRepository, aiToolRepository);
    }

    @Test
    @DisplayName("closes the window at the instant of the call and opens it the configured span "
            + "earlier")
    void closesTheWindowAtTheInstantOfTheCallAndOpensItTheConfiguredSpanEarlier() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        service.getTrends();

        // Both bounds are read from the injected fixed clock, so both are deterministic — DL-278,
        // DL-247
        WindowBounds window = capturedWindow();
        assertThat(window.until())
                .isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        assertThat(window.since()).isEqualTo(expectedCutoff(CONFIGURED_TREND_WINDOW_DAYS));
        assertThat(window.since()).isBefore(window.until());
    }

    @Test
    @DisplayName("issues one trend query and reads no other table")
    void issuesOneTrendQueryAndReadsNoOtherTable() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        service.getTrends();

        verify(tweetRepository)
                .findDailyTrendsBetween(any(LocalDateTime.class), any(LocalDateTime.class));
        verifyNoMoreInteractions(tweetRepository);
        verifyNoInteractions(responseRepository, aiToolRepository);
    }

    @Test
    @DisplayName("reads the window and the series again on each call")
    void readsTheWindowAndTheSeriesAgainOnEachCall() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        when(tweetRepository.findDailyTrendsBetween(any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(List.of(firstBucket()))
                .thenReturn(List.of());

        TrendsDto first = service.getTrends();
        TrendsDto second = service.getTrends();

        assertThat(first.trends()).hasSize(1);
        assertThat(second.trends()).isEmpty();
        ArgumentCaptor<LocalDateTime> cutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tweetRepository, times(2))
                .findDailyTrendsBetween(cutoffs.capture(), any(LocalDateTime.class));
        assertThat(cutoffs.getAllValues()).hasSize(2);
        assertThat(cutoffs.getAllValues().get(1)).isAfterOrEqualTo(cutoffs.getAllValues().get(0));
        verify(properties, times(2)).analytics();
    }

    // -----------------------------------------------------------------------
    // The configured observation window
    // -----------------------------------------------------------------------

    // The window property is a finite positive number of days — DL-247 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("accepts a window of one day and a window of one hundred years")
    void acceptsAWindowOfOneDayAndAWindowOfOneHundredYears() {
        assertThatNoException().isThrownBy(() -> new ScannerProperties.Analytics(
                ScannerProperties.Analytics.MINIMUM_TREND_WINDOW_DAYS));
        assertThatNoException().isThrownBy(() -> new ScannerProperties.Analytics(
                ScannerProperties.Analytics.MAXIMUM_TREND_WINDOW_DAYS));
        assertThatNoException()
                .isThrownBy(() -> new ScannerProperties.Analytics(CONFIGURED_TREND_WINDOW_DAYS));
    }

    @Test
    @DisplayName("refuses a window that is not a finite positive number of days")
    void refusesAWindowThatIsNotAFinitePositiveNumberOfDays() {
        assertThatThrownBy(() -> new ScannerProperties.Analytics(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.analytics.trend-window-days");
        assertThatThrownBy(() -> new ScannerProperties.Analytics(-1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.analytics.trend-window-days");
        assertThatThrownBy(() -> new ScannerProperties.Analytics(Integer.MIN_VALUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.analytics.trend-window-days");
        assertThatThrownBy(() -> new ScannerProperties.Analytics(
                ScannerProperties.Analytics.MAXIMUM_TREND_WINDOW_DAYS + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.analytics.trend-window-days");
        assertThatThrownBy(() -> new ScannerProperties.Analytics(Integer.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scanner.analytics.trend-window-days");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * Stubs the three aggregate statements with the sentinel of every metric they back.
     */
    private void stubTheMeasuredDatabase() {
        stubTweetTotals(TOTAL_TWEETS, AVERAGE_DOUBT_RATING, AVERAGE_LIKE_COUNT);
        stubResponseTotals(TOTAL_RESPONSES, APPROVED_RESPONSES);
        when(aiToolRepository.count()).thenReturn(TRACKED_AI_TOOLS);
    }

    private void stubTheEmptyDatabase() {
        stubTweetTotals(0L, null, null);
        stubResponseTotals(0L, 0L);
        when(aiToolRepository.count()).thenReturn(0L);
    }

    /**
     * Stubs the single {@code tweets} aggregate statement.
     *
     * @param tweetCount         the row count to report
     * @param averageDoubtRating the mean doubt rating to report, {@code null} for unmeasured
     * @param averageLikeCount   the mean like count to report, {@code null} for unmeasured
     */
    private void stubTweetTotals(Long tweetCount, Double averageDoubtRating,
            Double averageLikeCount) {
        when(tweetRepository.findAggregates())
                .thenReturn(new TweetTotals(tweetCount, averageDoubtRating, averageLikeCount));
    }

    /**
     * Stubs the single {@code responses} aggregate statement.
     *
     * @param responseCount         the row count to report
     * @param approvedResponseCount the approved row count to report
     */
    private void stubResponseTotals(Long responseCount, Long approvedResponseCount) {
        when(responseRepository.findApprovalCounts())
                .thenReturn(new ResponseTotals(responseCount, approvedResponseCount));
    }

    /** The {@code tweets} aggregate projection, carrying the three values it reports. */
    private record TweetTotals(Long tweetCount, Double averageDoubtRating, Double averageLikeCount)
            implements TweetRepository.TweetAggregate {

        @Override
        public Long getTweetCount() {
            return tweetCount;
        }

        @Override
        public Double getAverageDoubtRating() {
            return averageDoubtRating;
        }

        @Override
        public Double getAverageLikeCount() {
            return averageLikeCount;
        }
    }

    /** The {@code responses} aggregate projection, carrying the two values it reports. */
    private record ResponseTotals(Long responseCount, Long approvedResponseCount)
            implements ResponseRepository.ApprovalCounts {

        @Override
        public Long getResponseCount() {
            return responseCount;
        }

        @Override
        public Long getApprovedResponseCount() {
            return approvedResponseCount;
        }
    }

    private void stubTheConfiguredWindow(int days) {
        when(properties.analytics()).thenReturn(new ScannerProperties.Analytics(days));
    }

    private void stubTheReturnedBuckets(TweetRepository.DailyTrend... buckets) {
        when(tweetRepository.findDailyTrendsBetween(any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(List.of(buckets));
    }

    private static Bucket firstBucket() {
        return new Bucket(FIRST_BUCKET_DAY, FIRST_BUCKET_TWEET_COUNT,
                FIRST_BUCKET_AVERAGE_DOUBT_RATING, FIRST_BUCKET_TOTAL_LIKES);
    }

    private static Bucket secondBucket() {
        return new Bucket(SECOND_BUCKET_DAY, SECOND_BUCKET_TWEET_COUNT,
                SECOND_BUCKET_AVERAGE_DOUBT_RATING, SECOND_BUCKET_TOTAL_LIKES);
    }

    /**
     * Captures the opening bound the daily trend query was called with.
     *
     * @return the captured lower bound on {@code tweets.created_at}
     */
    private LocalDateTime capturedCutoff() {
        return capturedWindow().since();
    }

    /**
     * Captures both bounds the daily trend query was called with.
     *
     * @return the captured closed interval on {@code tweets.created_at}
     */
    private WindowBounds capturedWindow() {
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tweetRepository).findDailyTrendsBetween(since.capture(), until.capture());
        return new WindowBounds(since.getValue(), until.getValue());
    }

    /** The closed interval a trend query was issued over — DL-247. */
    private record WindowBounds(LocalDateTime since, LocalDateTime until) {
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    /**
     * Collects the declared field types and constructor parameter types of
     * {@link AnalyticsService}, excluding synthetic fields.
     *
     * @return every type the class holds or accepts
     */
    private static List<Class<?>> declaredCollaboratorTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : AnalyticsService.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                types.add(field.getType());
            }
        }
        for (Constructor<?> constructor : AnalyticsService.class.getDeclaredConstructors()) {
            types.addAll(List.of(constructor.getParameterTypes()));
        }
        return types;
    }

    private static List<String> annotationPackagesOf(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getPackageName())
                .toList();
    }

    private static List<String> booleanFieldNamesOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> field.getType() == Boolean.class || field.getType() == boolean.class)
                .map(Field::getName)
                .toList();
    }

    private static List<Class<?>> recordComponentTypesOf(Class<?> recordType) {
        List<Class<?>> types = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            types.add(component.getType());
        }
        return types;
    }

    private static List<String> jsonNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> jsonNameOf(recordType, component.getName()))
                .toList();
    }

    /**
     * Reads the JSON key a record component serialises under.
     *
     * @param recordType    the record class to inspect
     * @param componentName the component whose key is read
     * @return the value of the {@link JsonProperty} annotation carried by the component, its
     *         accessor or its backing field
     * @throws AssertionError if the component is absent or carries no such annotation
     */
    private static String jsonNameOf(Class<?> recordType, String componentName) {
        RecordComponent component = recordComponentOf(recordType, componentName);
        JsonProperty declared = component.getAnnotation(JsonProperty.class);
        if (declared == null) {
            declared = component.getAccessor().getAnnotation(JsonProperty.class);
        }
        if (declared == null) {
            declared = fieldOf(recordType, componentName).getAnnotation(JsonProperty.class);
        }
        assertThat(declared).isNotNull();
        return declared.value();
    }

    private static RecordComponent recordComponentOf(Class<?> recordType, String componentName) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        recordType.getSimpleName() + " declares no component named "
                                + componentName));
    }

    private static Field fieldOf(Class<?> type, String fieldName) {
        try {
            return type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError(
                    type.getSimpleName() + " declares no field named " + fieldName, absent);
        }
    }

    /**
     * One calendar-day bucket the daily trend query reports. The component names mirror the select
     * aliases the projection binds to.
     *
     * @param bucketDate         the day the bucket covers
     * @param tweetCount         the number of rows created on that day
     * @param averageDoubtRating the mean doubt rating over those rows, which may be {@code null}
     * @param totalLikes         the summed like count over those rows, which may be {@code null}
     */
    private record Bucket(LocalDate bucketDate, Long tweetCount, Double averageDoubtRating,
            Long totalLikes) implements TweetRepository.DailyTrend {

        @Override
        public LocalDate getBucketDate() {
            return bucketDate;
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
    }
}
