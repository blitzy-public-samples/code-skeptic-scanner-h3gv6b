package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                properties);
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

    @Test
    @DisplayName("takes its three repositories and its configuration through its only constructor")
    void takesItsThreeRepositoriesAndItsConfigurationThroughItsOnlyConstructor() {
        List<Constructor<?>> constructors = List.of(AnalyticsService.class.getDeclaredConstructors());

        assertThat(constructors).hasSize(1);
        assertThat(constructors.get(0).getParameterTypes()).containsExactly(TweetRepository.class,
                ResponseRepository.class,
                AiToolRepository.class,
                ScannerProperties.class);
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
        when(tweetRepository.count()).thenReturn(TOTAL_TWEETS);
        when(responseRepository.count()).thenReturn(TOTAL_RESPONSES);

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
        when(responseRepository.count()).thenReturn(WIDER_TOTAL_RESPONSES);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(WIDER_APPROVED_RESPONSES);

        SummaryDto summary = service.getSummary();

        assertThat(summary.pendingResponses()).isEqualTo(WIDER_PENDING_RESPONSES);
        assertThat(summary.pendingResponses())
                .isEqualTo(summary.totalResponses() - summary.approvedResponses());
        assertThat(summary.pendingResponses()).isNotNegative();
        verify(responseRepository).count();
        verify(responseRepository).countByIsApprovedTrue();
        verifyNoMoreInteractions(responseRepository);
    }

    @Test
    @DisplayName("reports the approved count from the approval flag alone")
    void reportsTheApprovedCountFromTheApprovalFlagAlone() {
        when(responseRepository.countByIsApprovedTrue()).thenReturn(APPROVED_RESPONSES);

        SummaryDto summary = service.getSummary();

        assertThat(summary.approvedResponses()).isEqualTo(APPROVED_RESPONSES);
        verify(responseRepository).countByIsApprovedTrue();
        assertThat(booleanFieldNamesOf(Response.class)).containsExactly("isApproved");
        assertThat(recordComponentTypesOf(SummaryDto.class))
                .noneMatch(type -> type == Boolean.class || type == boolean.class);
    }

    @Test
    @DisplayName("issues one query for each measured metric and no other query")
    void issuesOneQueryForEachMeasuredMetricAndNoOtherQuery() {
        stubTheMeasuredDatabase();

        service.getSummary();

        verify(tweetRepository).count();
        verify(tweetRepository).findAverageDoubtRating();
        verify(tweetRepository).findAverageLikeCount();
        verify(responseRepository).count();
        verify(responseRepository).countByIsApprovedTrue();
        verify(aiToolRepository).count();
        verifyNoMoreInteractions(tweetRepository, responseRepository, aiToolRepository);
        verifyNoInteractions(properties);
    }

    @Test
    @DisplayName("reports the counts each call reads rather than repeating an earlier report")
    void reportsTheCountsEachCallReadsRatherThanRepeatingAnEarlierReport() {
        when(tweetRepository.count()).thenReturn(TOTAL_TWEETS, WIDER_TOTAL_RESPONSES);
        when(responseRepository.count()).thenReturn(TOTAL_RESPONSES, WIDER_TOTAL_RESPONSES);
        when(responseRepository.countByIsApprovedTrue())
                .thenReturn(APPROVED_RESPONSES, WIDER_APPROVED_RESPONSES);
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
        verify(tweetRepository, times(2)).count();
        verify(responseRepository, times(2)).count();
        verify(responseRepository, times(2)).countByIsApprovedTrue();
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
        when(tweetRepository.count()).thenReturn(TOTAL_TWEETS);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(0.0d);

        SummaryDto summary = service.getSummary();

        assertThat(summary.averageDoubtRating()).isNull();
        assertThat(summary.averageLikeCount()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("reports the measured average unchanged")
    void reportsTheMeasuredAverageUnchanged() {
        when(tweetRepository.findAverageDoubtRating()).thenReturn(AVERAGE_DOUBT_RATING);
        when(tweetRepository.findAverageLikeCount()).thenReturn(AVERAGE_LIKE_COUNT);

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

    @Test
    @DisplayName("opens the window the configured number of days before the call")
    void opensTheWindowTheConfiguredNumberOfDaysBeforeTheCall() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        LocalDateTime beforeTheCall = LocalDateTime.now();
        service.getTrends();
        LocalDateTime afterTheCall = LocalDateTime.now();

        assertThat(capturedCutoff()).isBetween(
                beforeTheCall.minusDays(CONFIGURED_TREND_WINDOW_DAYS),
                afterTheCall.minusDays(CONFIGURED_TREND_WINDOW_DAYS));
    }

    @Test
    @DisplayName("opens the window seven days before the call when seven days are configured")
    void opensTheWindowSevenDaysBeforeTheCallWhenSevenDaysAreConfigured() {
        stubTheConfiguredWindow(SHORTER_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        LocalDateTime beforeTheCall = LocalDateTime.now();
        service.getTrends();
        LocalDateTime afterTheCall = LocalDateTime.now();

        LocalDateTime cutoff = capturedCutoff();
        assertThat(cutoff).isBetween(beforeTheCall.minusDays(SHORTER_TREND_WINDOW_DAYS),
                afterTheCall.minusDays(SHORTER_TREND_WINDOW_DAYS));
        assertThat(cutoff).isAfter(afterTheCall.minusDays(CONFIGURED_TREND_WINDOW_DAYS));
        verify(properties).analytics();
        verifyNoMoreInteractions(properties);
    }

    @Test
    @DisplayName("issues one trend query and reads no other table")
    void issuesOneTrendQueryAndReadsNoOtherTable() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        stubTheReturnedBuckets();

        service.getTrends();

        verify(tweetRepository).findDailyTrendsSince(any(LocalDateTime.class));
        verifyNoMoreInteractions(tweetRepository);
        verifyNoInteractions(responseRepository, aiToolRepository);
    }

    @Test
    @DisplayName("reads the window and the series again on each call")
    void readsTheWindowAndTheSeriesAgainOnEachCall() {
        stubTheConfiguredWindow(CONFIGURED_TREND_WINDOW_DAYS);
        when(tweetRepository.findDailyTrendsSince(any(LocalDateTime.class)))
                .thenReturn(List.of(firstBucket()))
                .thenReturn(List.of());

        TrendsDto first = service.getTrends();
        TrendsDto second = service.getTrends();

        assertThat(first.trends()).hasSize(1);
        assertThat(second.trends()).isEmpty();
        ArgumentCaptor<LocalDateTime> cutoffs = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tweetRepository, times(2)).findDailyTrendsSince(cutoffs.capture());
        assertThat(cutoffs.getAllValues()).hasSize(2);
        assertThat(cutoffs.getAllValues().get(1)).isAfterOrEqualTo(cutoffs.getAllValues().get(0));
        verify(properties, times(2)).analytics();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * Stubs each of the six aggregate queries with the sentinel of the metric it backs.
     */
    private void stubTheMeasuredDatabase() {
        when(tweetRepository.count()).thenReturn(TOTAL_TWEETS);
        when(responseRepository.count()).thenReturn(TOTAL_RESPONSES);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(APPROVED_RESPONSES);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(AVERAGE_DOUBT_RATING);
        when(tweetRepository.findAverageLikeCount()).thenReturn(AVERAGE_LIKE_COUNT);
        when(aiToolRepository.count()).thenReturn(TRACKED_AI_TOOLS);
    }

    private void stubTheEmptyDatabase() {
        when(tweetRepository.count()).thenReturn(0L);
        when(responseRepository.count()).thenReturn(0L);
        when(responseRepository.countByIsApprovedTrue()).thenReturn(0L);
        when(tweetRepository.findAverageDoubtRating()).thenReturn(null);
        when(tweetRepository.findAverageLikeCount()).thenReturn(null);
        when(aiToolRepository.count()).thenReturn(0L);
    }

    private void stubTheConfiguredWindow(int days) {
        when(properties.analytics()).thenReturn(new ScannerProperties.Analytics(days));
    }

    private void stubTheReturnedBuckets(TweetRepository.DailyTrend... buckets) {
        when(tweetRepository.findDailyTrendsSince(any(LocalDateTime.class)))
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
     * Captures the single cutoff the daily trend query was called with.
     *
     * @return the captured lower bound on {@code tweets.created_at}
     */
    private LocalDateTime capturedCutoff() {
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tweetRepository).findDailyTrendsSince(cutoff.capture());
        return cutoff.getValue();
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
