package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.codeskeptic.scanner.util.QueryParameters;

// Net-new completion coverage: three of the four operations under test were called by
// backend/app/api/tweets.py:L16,L27,L50 but absent from the source class, and
// backend/tests/test_services.py:L8-22 carried two bare `pass` stubs — see
// docs/DECISION_LOG.md DL-037, DL-038, DL-040, DL-048
// The popularity comparison is a faithful port of
// backend/app/services/twitter_service.py:L46 — see docs/DECISION_LOG.md
/**
 * Exercises the four operations {@link TwitterService} exposes:
 * {@link TwitterService#getPaginatedTweets(int, int)}, {@link TwitterService#getTweet(String)},
 * {@link TwitterService#updateTweetAnalysis(String, double)} and
 * {@link TwitterService#meetsPopularityThreshold(Integer)}.
 *
 * <p>Four collaborators are Mockito doubles and the fifth is a real {@link ScannerProperties} record
 * carrying the value of {@code scanner.popularity-threshold} under test. No Spring context is
 * started, no database is reached and no network call is made; stubbing is declared per test.
 *
 * <p>The popularity gate is asserted at the boundary of
 * {@code backend/app/services/twitter_service.py:L46}, whose threshold is read at {@code :L43} and
 * whose default of {@code 100} is declared at {@code backend/app/core/config.py:L10}.
 *
 * @see TwitterService
 */
@ExtendWith(MockitoExtension.class)
class TwitterServiceTest {

    private static final String POPULARITY_THRESHOLD_KEY = "tweet_popularity_threshold";

    private static final String TWEET_NOT_FOUND = "Tweet not found";

    /** Value of {@code scanner.popularity-threshold} declared at {@code core/config.py:L10}. */
    private static final int CONFIGURED_THRESHOLD = 100;

    private static final int TWEET_ID = 7;

    private static final String TWEET_ID_PATH_VALUE = "7";

    private static final double ANALYSIS_SCORE = 0.0d;

    private static final double STUBBED_DOUBT_RATING = 42.0d;

    private static final String CONTENT = "AI coding tools still write code I have to rewrite.";

    private static final Integer LIKE_COUNT = 128;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2024, 3, 14, 9, 26, 53);

    private static final Double INITIAL_DOUBT_RATING = 3.5d;

    private static final String MEDIA_URL = "https://example.invalid/media/1.png";

    private static final String QUOTED_TWEET_ID = "1234567890";

    private static final String USER_ID = "9876543210";

    private static final String AI_TOOL = "GPT-4";

    private static final List<Class<?>> HTTP_CLIENT_TYPES = List.of(
            WebClient.class,
            WebClient.Builder.class,
            RestClient.class,
            RestClient.Builder.class,
            RestTemplate.class);

    private static final List<String> PUBLIC_OPERATIONS = List.of(
            "getPaginatedTweets",
            "getTweet",
            "updateTweetAnalysis",
            "meetsPopularityThreshold",
            // The overload taking a threshold the caller resolved — DL-255
            "meetsPopularityThreshold",
            "popularityThresholdInForce",
            // The single orchestration of the analyze route — DL-263
            "analyzeTweet");

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private SettingRepository settingRepository;

    @Mock
    private TweetMapper tweetMapper;

    @Mock
    private SentimentAnalysisService sentimentAnalysisService;

    // -----------------------------------------------------------------------
    // meetsPopularityThreshold(Integer) — the comparison
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "a like count of {0} reports {1} against a threshold of 100")
    @CsvSource({
            "99,false",
            "100,true",
            "101,true"
    })
    @DisplayName("reports the gate outcome for like counts either side of one hundred")
    void reportsTheGateOutcomeForLikeCountsEitherSideOfOneHundred(int likeCount, boolean expected) {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(likeCount);

        assertThat(meetsThreshold).isEqualTo(expected);
    }

    @ParameterizedTest(name = "a like count of {0} reports {1} against a threshold of 250")
    @CsvSource({
            "249,false",
            "250,true",
            "251,true"
    })
    @DisplayName("compares the like count with the configured threshold")
    void comparesTheLikeCountWithTheConfiguredThreshold(int likeCount, boolean expected) {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        boolean meetsThreshold = serviceWithConfiguredThreshold(250).meetsPopularityThreshold(likeCount);

        assertThat(meetsThreshold).isEqualTo(expected);
    }

    @Test
    @DisplayName("reports false for an absent like count and reads no threshold")
    void reportsFalseForAnAbsentLikeCount() {
        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(null);

        assertThat(meetsThreshold).isFalse();
        verifyNoInteractions(settingRepository);
    }

    @Test
    @DisplayName("reports true for a like count above the threshold by a wide margin")
    void reportsTrueForALikeCountWellAboveTheThreshold() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        boolean meetsThreshold = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .meetsPopularityThreshold(Integer.MAX_VALUE);

        assertThat(meetsThreshold).isTrue();
    }

    @Test
    @DisplayName("reports false for a like count of zero against the configured threshold")
    void reportsFalseForALikeCountOfZero() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(0);

        assertThat(meetsThreshold).isFalse();
    }

    // -----------------------------------------------------------------------
    // meetsPopularityThreshold(Integer) — threshold resolution
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("prefers the stored setting row over the configured threshold")
    void prefersTheStoredRowOverTheConfiguredThreshold() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("50")));

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        assertThat(meetsThreshold).isTrue();
    }

    @Test
    @DisplayName("applies the configured threshold when no row is stored")
    void appliesTheConfiguredThresholdWhenNoRowIsStored() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        assertThat(meetsThreshold).isFalse();
    }

    @Test
    @DisplayName("reads the stored threshold from the key tweet_popularity_threshold")
    void readsTheStoredThresholdFromItsKey() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("50")));

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        ArgumentCaptor<String> queriedKey = ArgumentCaptor.forClass(String.class);
        verify(settingRepository).findById(queriedKey.capture());
        assertThat(queriedKey.getValue()).isEqualTo("tweet_popularity_threshold");
    }

    @Test
    @DisplayName("applies the stored threshold once whitespace around it is discarded")
    void appliesTheStoredThresholdOnceWhitespaceIsDiscarded() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("  50  ")));

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        assertThat(meetsThreshold).isTrue();
    }

    @Test
    @DisplayName("applies the configured threshold when the stored row holds no integer")
    void appliesTheConfiguredThresholdWhenTheStoredRowHoldsNoInteger() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("not-a-number")));

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        assertThat(meetsThreshold).isFalse();
    }

    @Test
    @DisplayName("applies the configured threshold when the stored row holds no value")
    void appliesTheConfiguredThresholdWhenTheStoredRowHoldsNoValue() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow(null)));

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(60);

        assertThat(meetsThreshold).isFalse();
    }

    @Test
    @DisplayName("reports true for any like count when the stored threshold is negative")
    void reportsTrueForAnyLikeCountWhenTheStoredThresholdIsNegative() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("-5")));

        boolean meetsThreshold =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).meetsPopularityThreshold(0);

        assertThat(meetsThreshold).isTrue();
    }

    @Test
    @DisplayName("reads the stored threshold once for every call")
    void readsTheStoredThresholdOnceForEveryCall() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("50")));
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        service.meetsPopularityThreshold(60);
        service.meetsPopularityThreshold(60);

        verify(settingRepository, times(2)).findById(POPULARITY_THRESHOLD_KEY);
    }

    // -----------------------------------------------------------------------
    // One threshold resolution per ingestion cycle — DL-255
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolves the threshold in force from the stored row")
    void resolvesTheThresholdInForceFromTheStoredRow() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("250")));

        int inForce = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .popularityThresholdInForce();

        assertThat(inForce).isEqualTo(250);
    }

    @Test
    @DisplayName("resolves the threshold in force from the configured value when no row is stored")
    void resolvesTheThresholdInForceFromTheConfiguredValue() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY)).thenReturn(Optional.empty());

        int inForce = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .popularityThresholdInForce();

        assertThat(inForce).isEqualTo(CONFIGURED_THRESHOLD);
    }

    @ParameterizedTest(name = "a like count of {0} against a supplied threshold of 100")
    @CsvSource({"99,false", "100,true", "101,true"})
    @DisplayName("compares a like count against a supplied threshold inclusively")
    void comparesALikeCountAgainstASuppliedThresholdInclusively(int likeCount, boolean expected) {
        boolean meetsThreshold = serviceWithConfiguredThreshold(1)
                .meetsPopularityThreshold(likeCount, 100);

        assertThat(meetsThreshold).isEqualTo(expected);
    }

    @Test
    @DisplayName("reads no table when the threshold is supplied")
    void readsNoTableWhenTheThresholdIsSupplied() {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        service.meetsPopularityThreshold(60, 50);
        service.meetsPopularityThreshold(60, 50);
        service.meetsPopularityThreshold(60, 50);

        verifyNoInteractions(settingRepository);
    }

    @Test
    @DisplayName("reports false for an absent like count against a supplied threshold")
    void reportsFalseForAnAbsentLikeCountAgainstASuppliedThreshold() {
        assertThat(serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .meetsPopularityThreshold(null, 0)).isFalse();
    }

    @Test
    @DisplayName("warns once while the stored threshold keeps holding the same unparseable value")
    void warnsOnceWhileTheStoredThresholdKeepsHoldingTheSameUnparseableValue() {
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("not a number")));
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        ListAppender<ILoggingEvent> recorded = attachServiceAppender();
        try {
            service.popularityThresholdInForce();
            service.popularityThresholdInForce();
            service.popularityThresholdInForce();

            assertThat(unparseableWarnings(recorded)).hasSize(1);
            assertThat(unparseableWarnings(recorded).get(0))
                    .contains("tweet_popularity_threshold")
                    .doesNotContain("not a number");
        } finally {
            detachServiceAppender(recorded);
        }
    }

    @Test
    @DisplayName("warns again when the stored threshold holds a different unparseable value")
    void warnsAgainWhenTheStoredThresholdHoldsADifferentUnparseableValue() {
        // Consecutive answers are chained and are not passed as varargs: a generic varargs array of
        // Optional<Setting> cannot be created without an unchecked warning.
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("first")))
                .thenReturn(Optional.of(thresholdRow("second")));
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        ListAppender<ILoggingEvent> recorded = attachServiceAppender();
        try {
            service.popularityThresholdInForce();
            service.popularityThresholdInForce();

            assertThat(unparseableWarnings(recorded)).hasSize(2);
        } finally {
            detachServiceAppender(recorded);
        }
    }

    @Test
    @DisplayName("warns again when a value that parses is stored between two unparseable ones")
    void warnsAgainWhenAValueThatParsesIsStoredBetweenTwoUnparseableOnes() {
        // Consecutive answers are chained and are not passed as varargs: a generic varargs array of
        // Optional<Setting> cannot be created without an unchecked warning.
        when(settingRepository.findById(POPULARITY_THRESHOLD_KEY))
                .thenReturn(Optional.of(thresholdRow("bad")))
                .thenReturn(Optional.of(thresholdRow("50")))
                .thenReturn(Optional.of(thresholdRow("bad")));
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        ListAppender<ILoggingEvent> recorded = attachServiceAppender();
        try {
            service.popularityThresholdInForce();
            service.popularityThresholdInForce();
            service.popularityThresholdInForce();

            assertThat(unparseableWarnings(recorded)).hasSize(2);
        } finally {
            detachServiceAppender(recorded);
        }
    }

    /**
     * Attaches a recording appender to the logger of the unit under test.
     *
     * @return the attached appender
     */
    private static ListAppender<ILoggingEvent> attachServiceAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TwitterService.class)).addAppender(appender);
        return appender;
    }

    /**
     * Detaches a recording appender from the logger of the unit under test.
     *
     * @param appender the appender to detach
     */
    private static void detachServiceAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TwitterService.class)).detachAppender(appender);
        appender.stop();
    }

    /**
     * Reads the unparseable-threshold warnings the appender recorded.
     *
     * @param appender the appender that recorded the calls
     * @return the formatted messages, in order
     */
    private static List<String> unparseableWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("does not hold an integer"))
                .toList();
    }

    // -----------------------------------------------------------------------
    // getTweet(String)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "the identifier [{0}] reports a row that is not present")
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-number", "12abc", "3.5", " 7 ", "-", "99999999999999999999"})
    @DisplayName("throws not found when reading an identifier that is not a number")
    void throwsNotFoundWhenReadingAnIdentifierThatIsNotANumber(String tweetId) {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.getTweet(tweetId))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verifyNoInteractions(tweetRepository, tweetMapper);
    }

    @Test
    @DisplayName("throws neither a bad request nor an illegal argument for an identifier that is not a number")
    void throwsNeitherABadRequestNorAnIllegalArgumentForAnIdentifierThatIsNotANumber() {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.getTweet("not-a-number"))
                .isExactlyInstanceOf(NotFoundException.class)
                .isNotInstanceOf(BadRequestException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NumberFormatException.class)
                .hasMessage(TWEET_NOT_FOUND);
    }

    @Test
    @DisplayName("carries the number parsing failure as the cause and leaves the message unchanged")
    void carriesTheNumberParsingFailureAsTheCause() {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.getTweet("not-a-number"))
                .hasMessage(TWEET_NOT_FOUND)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("throws not found when no row carries the identifier")
    void throwsNotFoundWhenNoRowCarriesTheIdentifier() {
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.empty());
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.getTweet(TWEET_ID_PATH_VALUE))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verifyNoInteractions(tweetMapper);
    }

    @Test
    @DisplayName("renders the row carrying the identifier")
    void rendersTheRowCarryingTheIdentifier() {
        Tweet row = tweetWithIdentifier(TWEET_ID);
        TweetDto rendered = dtoWithIdentifier(TWEET_ID_PATH_VALUE);
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(tweetMapper.toDto(row)).thenReturn(rendered);

        TweetDto result =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getTweet(TWEET_ID_PATH_VALUE);

        assertThat(result).isSameAs(rendered);
        assertThat(result.id()).isEqualTo(TWEET_ID_PATH_VALUE);
    }

    // -----------------------------------------------------------------------
    // updateTweetAnalysis(String, double) — identifier handling
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "the identifier [{0}] reports a row that is not present")
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-number", "12abc", "3.5", " 7 ", "-", "99999999999999999999"})
    @DisplayName("throws not found when analysing an identifier that is not a number")
    void throwsNotFoundWhenAnalysingAnIdentifierThatIsNotANumber(String tweetId) {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.updateTweetAnalysis(tweetId, ANALYSIS_SCORE))
                .isExactlyInstanceOf(NotFoundException.class)
                .isNotInstanceOf(BadRequestException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verifyNoInteractions(tweetRepository, sentimentAnalysisService);
    }

    @Test
    @DisplayName("throws not found and writes nothing when analysing a row that is not present")
    void throwsNotFoundAndWritesNothingWhenAnalysingARowThatIsNotPresent() {
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble()))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(0);
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verify(tweetRepository, never()).save(any(Tweet.class));
        verify(tweetRepository, never()).findById(any());
    }

    // -----------------------------------------------------------------------
    // updateTweetAnalysis(String, double) — the doubt rating written
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("writes the doubt rating the calculation returned")
    void writesTheDoubtRatingTheCalculationReturned() {
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        verify(tweetRepository).updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING);
    }

    @ParameterizedTest(name = "a returned doubt rating of {0} is written unchanged")
    @CsvSource({
            "0.0",
            "10.0",
            "-1.5",
            "7.25"
    })
    @DisplayName("writes every doubt rating the calculation returns unchanged")
    void writesEveryDoubtRatingTheCalculationReturnsUnchanged(double doubtRating) {
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble())).thenReturn(doubtRating);
        when(tweetRepository.updateDoubtRating(eq(TWEET_ID), anyDouble())).thenReturn(1);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        ArgumentCaptor<Double> written = ArgumentCaptor.forClass(Double.class);
        verify(tweetRepository).updateDoubtRating(eq(TWEET_ID), written.capture());
        assertThat(written.getValue()).isEqualTo(doubtRating);
    }

    @Test
    @DisplayName("forwards the score it received to the doubt rating calculation")
    void forwardsTheScoreItReceivedToTheDoubtRatingCalculation() {
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble()))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, -0.625d);

        ArgumentCaptor<Double> forwardedScore = ArgumentCaptor.forClass(Double.class);
        verify(sentimentAnalysisService).calculateDoubtRating(forwardedScore.capture());
        assertThat(forwardedScore.getValue()).isEqualTo(-0.625d);
    }

    // One statement writes one column by identifier; the row is neither read nor re-saved — DL-263
    @Test
    @DisplayName("writes one column by identifier without reading or saving the row")
    void writesOneColumnByIdentifierWithoutReadingOrSavingTheRow() {
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        verify(tweetRepository, times(1)).updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING);
        verify(tweetRepository, never()).findById(any());
        verify(tweetRepository, never()).findAnalysisSubjectById(any());
        verify(tweetRepository, never()).save(any(Tweet.class));
        verifyNoInteractions(tweetMapper);
    }

    // -----------------------------------------------------------------------
    // analyzeTweet(String) — one read, one provider call, one write — DL-263
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reads the row once, scores its text and writes the derived rating once")
    void readsTheRowOnceScoresItsTextAndWritesTheDerivedRatingOnce() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID))
                .thenReturn(Optional.of(analysisSubject(TWEET_ID, CONTENT)));
        when(sentimentAnalysisService.analyzeSentiment(CONTENT)).thenReturn(ANALYSIS_SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);

        double score = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .analyzeTweet(TWEET_ID_PATH_VALUE);

        assertThat(score).isEqualTo(ANALYSIS_SCORE);
        verify(tweetRepository, times(1)).findAnalysisSubjectById(TWEET_ID);
        verify(sentimentAnalysisService, times(1)).analyzeSentiment(CONTENT);
        verify(tweetRepository, times(1)).updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING);
        // The nine-column read and the second read of the same row are both gone — DL-263
        verify(tweetRepository, never()).findById(any());
        verify(tweetRepository, never()).save(any(Tweet.class));
        verifyNoInteractions(tweetMapper);
    }

    @Test
    @DisplayName("orders the read before the provider call and the provider call before the write")
    void ordersTheReadBeforeTheProviderCallAndTheProviderCallBeforeTheWrite() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID))
                .thenReturn(Optional.of(analysisSubject(TWEET_ID, CONTENT)));
        when(sentimentAnalysisService.analyzeSentiment(CONTENT)).thenReturn(ANALYSIS_SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).analyzeTweet(TWEET_ID_PATH_VALUE);

        InOrder ordering = inOrder(tweetRepository, sentimentAnalysisService);
        ordering.verify(tweetRepository).findAnalysisSubjectById(TWEET_ID);
        ordering.verify(sentimentAnalysisService).analyzeSentiment(CONTENT);
        ordering.verify(sentimentAnalysisService).calculateDoubtRating(ANALYSIS_SCORE);
        ordering.verify(tweetRepository).updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING);
        ordering.verifyNoMoreInteractions();
    }

    @ParameterizedTest(name = "the identifier [{0}] is reported as a row that is not present")
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-number", "12abc", "3.5", " 7 "})
    @DisplayName("reports an identifier that holds no number without scoring anything")
    void reportsAnIdentifierThatHoldsNoNumberWithoutScoringAnything(String tweetId) {
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.analyzeTweet(tweetId))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verifyNoInteractions(sentimentAnalysisService, tweetMapper);
        verify(tweetRepository, never()).findAnalysisSubjectById(any());
    }

    @Test
    @DisplayName("reports a row that is not present before any provider call is made")
    void reportsARowThatIsNotPresentBeforeAnyProviderCallIsMade() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID)).thenReturn(Optional.empty());
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.analyzeTweet(TWEET_ID_PATH_VALUE))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verifyNoInteractions(sentimentAnalysisService);
        verify(tweetRepository, never()).updateDoubtRating(any(), any());
    }

    @Test
    @DisplayName("reports a row deleted between the read and the write as a row that is not present")
    void reportsARowDeletedBetweenTheReadAndTheWrite() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID))
                .thenReturn(Optional.of(analysisSubject(TWEET_ID, CONTENT)));
        when(sentimentAnalysisService.analyzeSentiment(CONTENT)).thenReturn(ANALYSIS_SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(0);
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.analyzeTweet(TWEET_ID_PATH_VALUE))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);
    }

    @Test
    @DisplayName("rejects a row whose content column holds nothing, as the wire form does")
    void rejectsARowWhoseContentColumnHoldsNothing() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID))
                .thenReturn(Optional.of(analysisSubject(TWEET_ID, null)));
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.analyzeTweet(TWEET_ID_PATH_VALUE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("content must not be null.");

        verifyNoInteractions(sentimentAnalysisService);
        verify(tweetRepository, never()).updateDoubtRating(any(), any());
    }

    @Test
    @DisplayName("writes the rating again for a row that already carried one")
    void writesTheRatingAgainForARowThatAlreadyCarriedOne() {
        when(tweetRepository.findAnalysisSubjectById(TWEET_ID))
                .thenReturn(Optional.of(analysisSubject(TWEET_ID, CONTENT)));
        when(sentimentAnalysisService.analyzeSentiment(CONTENT)).thenReturn(ANALYSIS_SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);
        when(tweetRepository.updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING)).thenReturn(1);
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        service.analyzeTweet(TWEET_ID_PATH_VALUE);
        service.analyzeTweet(TWEET_ID_PATH_VALUE);

        verify(tweetRepository, times(2)).updateDoubtRating(TWEET_ID, STUBBED_DOUBT_RATING);
    }

    /**
     * Builds the analyze projection carrying the supplied column values.
     *
     * @param id      value of {@code tweets.id}
     * @param content value of {@code tweets.content}, possibly {@code null}
     * @return the projection
     */
    private static TweetRepository.AnalysisSubject analysisSubject(Integer id, String content) {
        return new TweetRepository.AnalysisSubject() {
            @Override
            public Integer getId() {
                return id;
            }

            @Override
            public String getContent() {
                return content;
            }
        };
    }

    // -----------------------------------------------------------------------
    // getPaginatedTweets(int, int) — the page request
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "page {0} of size {1} reads page index {2} of size {3}")
    @CsvSource({
            "1,10,0,10",
            "2,10,1,10",
            "3,25,2,25",
            "7,1,6,1"
    })
    @DisplayName("reads the page index one below the page number it was given")
    void readsThePageIndexOneBelowThePageNumberItWasGiven(int page, int perPage, int expectedIndex,
            int expectedSize) {
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageNumber()).isEqualTo(expectedIndex);
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(expectedSize);
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} is queried, its offset being at most 2147483647")
    @CsvSource({
            "214748365,10",
            "2,1000",
            "1,1000",
            "214748364,10"
    })
    @DisplayName("queries a page whose offset the paged query can express")
    void queriesAPageWhoseOffsetThePagedQueryCanExpress(int page, int perPage) {
        stubEveryWindowRead(3L);

        PaginatedTweetsDto envelope =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allMatch(window -> window.getOffset() <= Integer.MAX_VALUE);
        assertThat(envelope.pagination().page()).isEqualTo(page);
        assertThatEveryWindowIsBounded();
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} is answered empty without a paged query")
    @CsvSource({
            "2147483647,10",
            "2147483646,10",
            "99999999,1000",
            "214748366,10",
            "2147485,1000"
    })
    @DisplayName("answers a page beyond the queryable offset with an empty page and no query")
    void answersAPageBeyondTheQueryableOffsetWithAnEmptyPageAndNoQuery(int page, int perPage) {
        when(tweetRepository.count()).thenReturn(3L);

        PaginatedTweetsDto envelope =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        assertThat(envelope.tweets()).isEmpty();
        assertThat(envelope.pagination().page()).isEqualTo(page);
        assertThat(envelope.pagination().perPage()).isEqualTo(perPage);
        assertThat(envelope.pagination().total()).isEqualTo(3L);
        assertThat(envelope.pagination().totalPages()).isEqualTo(1);
        verify(tweetRepository, never()).findAll(any(Pageable.class));
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports an empty table for a page beyond the queryable offset")
    void reportsAnEmptyTableForAPageBeyondTheQueryableOffset() {
        when(tweetRepository.count()).thenReturn(0L);

        PaginatedTweetsDto envelope = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .getPaginatedTweets(Integer.MAX_VALUE, 10);

        assertThat(envelope.tweets()).isEmpty();
        assertThat(envelope.pagination().total()).isZero();
        assertThat(envelope.pagination().totalPages()).isZero();
    }

    @ParameterizedTest(name = "a per_page of {0} is served as {1} and read in bounded windows")
    @CsvSource({
            "100,100",
            "101,101",
            "500,500",
            "1000,1000",
            "1001,1000",
            "10000,1000",
            "2147483647,1000"
    })
    @DisplayName("serves per_page up to the maximum and reduces a larger one to it")
    void servesPerPageUpToTheMaximumAndReducesALargerOneToIt(int perPage, int servedSize) {
        stubEveryWindowRead(0L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        assertThat(rendered.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(servedSize);
        assertThat(servedSize).isLessThanOrEqualTo(QueryParameters.MAXIMUM_PAGE_SIZE);
        assertThatEveryWindowIsBounded();
    }

    @Test
    @DisplayName("restates a per_page within the maximum in the pagination block")
    void restatesAPerPageWithinTheMaximumInThePaginationBlock() {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto envelope =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 500);

        assertThat(envelope.pagination().perPage()).isEqualTo(500);
    }

    // A page whose first row lies at most Integer.MAX_VALUE rows in is read as any other page —
    // DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} still reaches the repository")
    @CsvSource({
            "214748365,10",
            "2147483647,1",
            "1000000,10"
    })
    @DisplayName("reads a page whose first row lies within the largest addressable offset")
    void readsAPageWhoseFirstRowLiesWithinTheLargestAddressableOffset(int page, int perPage) {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(page - 1, perPage), 30L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getOffset())
                .as("offset of the page request").isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThat(rendered.tweets()).as("rows of a page beyond the last one").isEmpty();
        verify(tweetRepository, never()).count();
    }

    // A page whose first row lies beyond Integer.MAX_VALUE rows holds no row — DL-225 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} renders the empty page")
    @CsvSource({
            "214748366,10",
            "2147483647,10",
            "2147483647,1000",
            "2147485,1000"
    })
    @DisplayName("renders the empty page for a page whose first row lies beyond the largest "
            + "addressable offset, without asking the repository for it")
    void rendersTheEmptyPageBeyondTheLargestAddressableOffset(int page, int perPage) {
        when(tweetRepository.count()).thenReturn(30L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        assertThat(rendered.tweets()).as("rows of the rendered page").isEmpty();
        assertThat(rendered.pagination().page()).as("page the envelope restates").isEqualTo(page);
        assertThat(rendered.pagination().perPage()).as("per_page the envelope restates")
                .isEqualTo(perPage);
        assertThat(rendered.pagination().total()).as("total the envelope reports").isEqualTo(30L);
        assertThat(rendered.pagination().totalPages()).as("total_pages the envelope reports")
                .isEqualTo((int) Math.ceil(30.0d / perPage));
        verify(tweetRepository, never()).findAll(any(Pageable.class));
    }

    // The empty table reports the same total_pages a repository page reports — DL-225 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("reports a total of zero and no page for an empty table beyond the largest "
            + "addressable offset")
    void reportsAnEmptyTableBeyondTheLargestAddressableOffset() {
        when(tweetRepository.count()).thenReturn(0L);

        PaginatedTweetsDto rendered = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .getPaginatedTweets(Integer.MAX_VALUE, 10);

        assertThat(rendered.tweets()).as("rows of the rendered page").isEmpty();
        assertThat(rendered.pagination().total()).as("total the envelope reports").isZero();
        assertThat(rendered.pagination().totalPages()).as("total_pages the envelope reports").isZero();
    }

    // One page larger than the chunk bound is read as consecutive bounded chunks — DL-249 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("reads a page larger than the chunk bound as consecutive chunks that cover it once")
    void readsAPageLargerThanTheChunkBoundAsConsecutiveChunks() {
        int chunkBound = declaredChunkBound();
        int perPage = chunkBound * 2;
        List<Tweet> firstChunk = rowsNumbered(chunkBound);
        List<Tweet> secondChunk = rowsNumbered(chunkBound / 2);
        when(tweetRepository.findChunk(any(Pageable.class)))
                .thenReturn(firstChunk)
                .thenReturn(secondChunk);
        when(tweetRepository.count()).thenReturn((long) chunkBound + secondChunk.size());
        when(tweetMapper.toDtoList(anyList()))
                .thenAnswer(invocation -> dtosFor(invocation.getArgument(0)));

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        assertThat(rendered.tweets()).as("rows the page rendered")
                .hasSize(chunkBound + secondChunk.size());
        assertThat(rendered.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(perPage);
        assertThat(rendered.pagination().total()).as("total the envelope reports")
                .isEqualTo((long) chunkBound + secondChunk.size());
        assertThat(pageRequestsIssued()).as("windows the page read asked for").hasSize(2);
        assertThat(pageRequestsIssued()).extracting(Pageable::getOffset)
                .as("first row of each window").containsExactly(0L, (long) chunkBound);
        verify(tweetMapper, times(2)).toDtoList(anyList());
        verify(tweetRepository, never()).findAll(any(Pageable.class));
        assertThatEveryWindowIsBounded();
    }

    // A page at or below the chunk bound is read by one statement — DL-249 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} is read by the single page statement")
    @ValueSource(ints = {1, 10, 499, 500})
    @DisplayName("reads a page at or below the chunk bound with one page statement and no row count")
    void readsAPageAtOrBelowTheChunkBoundWithOnePageStatement(int perPage) {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(0), 0L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize())
                .as("rows the single statement was asked for").isEqualTo(perPage);
        verify(tweetRepository, never()).findChunk(any(Pageable.class));
        verify(tweetRepository, never()).count();
    }

    // Every page read carries a total order — DL-249 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("orders every page read by identifier ascending")
    void ordersEveryPageReadByIdentifierAscending() {
        stubEveryWindowRead(0L);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(2, 10);

        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allSatisfy(window -> assertThat(window.getSort())
                        .as("sort of one window").isEqualTo(Sort.by(Sort.Direction.ASC, "id")));
    }

    // -----------------------------------------------------------------------
    // getPaginatedTweets(int, int) — the envelope
    // -----------------------------------------------------------------------

    // The finite page-size bound — DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "per_page {0} reads page size {1}")
    @CsvSource({
            "99,99",
            "100,100",
            "101,101",
            "1000,1000",
            "1001,1000",
            "2147483647,1000"
    })
    @DisplayName("reads a per_page at or below the maximum unreduced and a larger one as the maximum")
    void readsAPerPageAtOrBelowTheMaximumUnreducedAndALargerOneAsTheMaximum(int perPage,
            int expectedSize) {
        stubEveryWindowRead(0L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        assertThat(rendered.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(expectedSize);
        assertThatEveryWindowIsBounded();
    }

    // Lower bounds applied to page and per_page — DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} reads page index {2} of size {3}")
    @CsvSource({
            "0,10,0,10",
            "-1,10,0,10",
            "-2147483648,10,0,10",
            "1,0,0,10",
            "1,-1,0,10",
            "0,0,0,10"
    })
    @DisplayName("reads a page or per_page below the lower bound as its default")
    void readsAPageOrPerPageBelowTheLowerBoundAsItsDefault(int page, int perPage,
            int expectedIndex, int expectedSize) {

        when(tweetRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable requested = invocation.getArgument(0);
                    return new PageImpl<>(List.of(), requested, 0L);
                });
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .getPaginatedTweets(page, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageNumber())
                .as("page index the repository was asked for").isEqualTo(expectedIndex);
        assertThat(pageRequest.getValue().getPageSize())
                .as("page size the repository was asked for").isEqualTo(expectedSize);
        assertThat(rendered.pagination().page()).as("page the envelope restates").isEqualTo(1);
        assertThat(rendered.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(expectedSize);
    }

    @Test
    @DisplayName("renders the pagination block from the page it read")
    void rendersThePaginationBlockFromThePageItRead() {
        Page<Tweet> page = new PageImpl<>(
                List.of(tweetWithIdentifier(TWEET_ID), tweetWithIdentifier(8)),
                PageRequest.of(2, 5),
                42L);
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(3, 5);

        assertThat(rendered.pagination().page()).isEqualTo(3);
        assertThat(rendered.pagination().perPage()).isEqualTo(5);
        assertThat(rendered.pagination().total()).isEqualTo(42L);
        assertThat(rendered.pagination().totalPages()).isEqualTo(9);
    }

    @Test
    @DisplayName("renders the rows the mapper returned in the order the page held them")
    void rendersTheRowsTheMapperReturnedInTheOrderThePageHeldThem() {
        Tweet firstRow = tweetWithIdentifier(TWEET_ID);
        Tweet secondRow = tweetWithIdentifier(8);
        TweetDto firstRendered = dtoWithIdentifier(TWEET_ID_PATH_VALUE);
        TweetDto secondRendered = dtoWithIdentifier("8");
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(firstRow, secondRow), PageRequest.of(0, 10), 2L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of(firstRendered, secondRendered));

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 10);

        assertThat(rendered.tweets()).containsExactly(firstRendered, secondRendered);
        assertThat(rendered.tweets()).extracting(TweetDto::id).containsExactly("7", "8");
    }

    @Test
    @DisplayName("hands the mapper the rows the page held")
    void handsTheMapperTheRowsThePageHeld() {
        Tweet firstRow = tweetWithIdentifier(TWEET_ID);
        Tweet secondRow = tweetWithIdentifier(8);
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(firstRow, secondRow), PageRequest.of(0, 10), 2L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 10);

        ArgumentCaptor<List<Tweet>> converted = ArgumentCaptor.captor();
        verify(tweetMapper).toDtoList(converted.capture());
        assertThat(converted.getValue()).hasSize(2);
        assertThat(converted.getValue().get(0)).isSameAs(firstRow);
        assertThat(converted.getValue().get(1)).isSameAs(secondRow);
    }

    @Test
    @DisplayName("declares the wire identifier of a tweet as a string")
    void declaresTheWireIdentifierOfATweetAsAString() {
        RecordComponent identifier = Arrays.stream(TweetDto.class.getRecordComponents())
                .filter(component -> "id".equals(component.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(identifier.getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("renders an empty page as an empty list with a total of zero")
    void rendersAnEmptyPageAsAnEmptyListWithATotalOfZero() {
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 10);

        assertThat(rendered.tweets()).isEmpty();
        assertThat(rendered.pagination().page()).isEqualTo(1);
        assertThat(rendered.pagination().perPage()).isEqualTo(10);
        assertThat(rendered.pagination().total()).isZero();
        assertThat(rendered.pagination().totalPages()).isZero();
    }

    // backend/app/api/tweets.py:L12-13 declares a default for an absent parameter; the maximum is
    // DL-123 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("serves the maximum page size for a request naming a larger one")
    void servesTheMaximumPageSizeForARequestNamingALargerOne() {
        stubEveryWindowRead(0L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 99999);

        assertThat(rendered.pagination().page()).as("page the envelope restates").isEqualTo(1);
        assertThat(rendered.pagination().perPage()).as("per_page the envelope restates")
                .isEqualTo(QueryParameters.MAXIMUM_PAGE_SIZE);
        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allSatisfy(window -> assertThat(window.getOffset()).isZero());
        assertThatEveryWindowIsBounded();
    }

    // The per_page maximum of DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} reads a page of size {1}")
    @CsvSource({
            "99,99",
            "100,100",
            "101,101",
            "250,250",
            "1000,1000",
            "1001,1000",
            "2147483647,1000"
    })
    @DisplayName("passes a page size within the maximum through and reduces a larger one")
    void passesAPageSizeWithinTheMaximumThroughAndReducesALargerOne(int perPage, int expectedSize) {
        stubEveryWindowRead(0L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        assertThat(rendered.pagination().perPage()).isEqualTo(expectedSize);
        assertThatEveryWindowIsBounded();
    }

    // The per_page cap of DL-123 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports the supplied page size in the pagination block it renders")
    void reportsTheSuppliedPageSizeInThePaginationBlockItRenders() {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 500);

        assertThat(rendered.pagination().perPage()).isEqualTo(500);
    }

    // The page size the route serves for a requested one — DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "per_page {0} is served as at most the maximum")
    @ValueSource(ints = {1, 99, 100, 101, 1_000, 1_001, 10_000, Integer.MAX_VALUE})
    @DisplayName("restates the page size it serves, which is the smaller of the request and the "
            + "maximum")
    void restatesThePageSizeItServes(int perPage) {
        stubEveryWindowRead(0L);

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        assertThat(rendered.pagination().perPage())
                .isEqualTo(Math.min(perPage, QueryParameters.MAXIMUM_PAGE_SIZE));
        assertThatEveryWindowIsBounded();
    }

    // The values api.TweetController derives from a malformed page or per_page — DL-217
    @ParameterizedTest(name = "\"{0}\" reads as page {1} of size {2}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,NULL,1,10",
            "'','',1,10",
            "'  ','  ',1,10",
            "abc,abc,1,10",
            "3.5,7.5,1,10",
            "99999999999999999999,99999999999999999999,1,10",
            "' 3 ',' 25 ',3,25",
            "+4,+5,4,5"
    })
    @DisplayName("reads a malformed page or per_page as the default the source declared")
    void readsAMalformedPageOrPerPageAsTheDefaultTheSourceDeclared(String rawPage, String rawPerPage,
            int expectedPage, int expectedPerPage) {

        int page = QueryParameters.intOrDefault(rawPage, 1);
        int perPage = QueryParameters.intOrDefault(rawPerPage, 10);

        assertThat(page).isEqualTo(expectedPage);
        assertThat(perPage).isEqualTo(expectedPerPage);

        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(page, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageNumber()).isEqualTo(expectedPage - 1);
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(expectedPerPage);
    }

    // -----------------------------------------------------------------------
    // Declared surface
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("declares no HTTP client among its fields")
    void declaresNoHttpClientAmongItsFields() {
        List<Class<?>> fieldTypes = Arrays.stream(TwitterService.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(fieldTypes).isNotEmpty();
        assertThat(fieldTypes).allSatisfy(fieldType -> assertThat(HTTP_CLIENT_TYPES)
                .noneMatch(clientType -> clientType.isAssignableFrom(fieldType)));
    }

    @Test
    @DisplayName("takes exactly the five collaborators through a single constructor")
    void takesExactlyTheFiveCollaboratorsThroughASingleConstructor() {
        Constructor<?>[] constructors = TwitterService.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(
                TweetRepository.class,
                SettingRepository.class,
                ScannerProperties.class,
                TweetMapper.class,
                SentimentAnalysisService.class);
    }

    @Test
    @DisplayName("takes no HTTP client among its constructor parameters")
    void takesNoHttpClientAmongItsConstructorParameters() {
        List<Class<?>> parameterTypes = Arrays.stream(TwitterService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList();

        assertThat(parameterTypes).isNotEmpty();
        assertThat(parameterTypes).allSatisfy(parameterType -> assertThat(HTTP_CLIENT_TYPES)
                .noneMatch(clientType -> clientType.isAssignableFrom(parameterType)));
    }

    @Test
    @DisplayName("declares exactly the six public operations, the gate carrying two overloads")
    void declaresExactlyTheSixPublicOperations() {
        List<String> declaredOperations = Arrays.stream(TwitterService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();

        assertThat(declaredOperations).containsExactlyInAnyOrderElementsOf(PUBLIC_OPERATIONS);
    }

    private static Setting thresholdRow(String value) {
        return new Setting(POPULARITY_THRESHOLD_KEY, value, "Popularity threshold");
    }

    private static Tweet tweetWithIdentifier(int identifier) {
        Tweet row = new Tweet();
        row.setId(identifier);
        return row;
    }

    private static Tweet fullyPopulatedTweet() {
        Tweet row = new Tweet();
        row.setId(TWEET_ID);
        row.setContent(CONTENT);
        row.setLikeCount(LIKE_COUNT);
        row.setCreatedAt(CREATED_AT);
        row.setDoubtRating(INITIAL_DOUBT_RATING);
        row.setMedia(List.of(MEDIA_URL));
        row.setQuotedTweetId(QUOTED_TWEET_ID);
        row.setUserId(USER_ID);
        row.setAiToolsMentioned(List.of(AI_TOOL));
        return row;
    }

    private static TweetDto dtoWithIdentifier(String identifier) {
        return new TweetDto(identifier, CONTENT, LIKE_COUNT, CREATED_AT, INITIAL_DOUBT_RATING,
                List.of(MEDIA_URL), null, USER_ID, List.of(AI_TOOL));
    }

    /**
     * Builds the requested number of {@code tweets} rows carrying consecutive identifiers.
     *
     * @param rows the number of rows to build
     * @return the rows
     */
    private static List<Tweet> rowsNumbered(int rows) {
        List<Tweet> built = new ArrayList<>(rows);
        for (int row = 1; row <= rows; row++) {
            built.add(tweetWithIdentifier(row));
        }
        return List.copyOf(built);
    }

    /**
     * Builds one wire form per supplied row, as the mapper does.
     *
     * @param rows the rows to render
     * @return one wire form per row
     */
    private static List<TweetDto> dtosFor(List<Tweet> rows) {
        return rows.stream()
                .map(row -> new TweetDto(String.valueOf(row.getId()), "content", 1,
                        LocalDateTime.of(2026, 1, 1, 12, 0), 5.0, List.of(), null, "42", List.of()))
                .toList();
    }

    private static Page<Tweet> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0L);
    }

    /**
     * Answers every read one page request can issue with an empty result and the supplied row total:
     * the single page statement, the bounded chunk statement and the row count.
     *
     * @param total the value {@code count()} and the page's {@code total} report
     */
    private void stubEveryWindowRead(long total) {
        lenient().when(tweetRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(0), total));
        lenient().when(tweetRepository.findChunk(any(Pageable.class))).thenReturn(List.of());
        lenient().when(tweetRepository.count()).thenReturn(total);
        lenient().when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());
    }

    /**
     * Collects every {@link Pageable} the service handed to the repository during this test.
     *
     * @return the windows asked for, in call order
     */
    private List<Pageable> pageRequestsIssued() {
        return mockingDetails(tweetRepository).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .filter(Pageable.class::isInstance)
                .map(Pageable.class::cast)
                .toList();
    }

    /**
     * Asserts that no statement was asked for more rows than the declared chunk bound — DL-249.
     */
    private void assertThatEveryWindowIsBounded() {
        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allSatisfy(window -> assertThat(window.getPageSize())
                        .as("rows one statement was asked for")
                        .isLessThanOrEqualTo(declaredChunkBound()));
    }

    /**
     * Reads the chunk bound the service declares, so these assertions and the service cannot drift.
     *
     * @return the value of the service's declared chunk bound
     */
    private static int declaredChunkBound() {
        try {
            Field bound = TwitterService.class.getDeclaredField("PAGE_FETCH_CHUNK_ROWS");
            bound.setAccessible(true);
            return (int) bound.get(null);
        } catch (ReflectiveOperationException absent) {
            throw new AssertionError("TwitterService must declare PAGE_FETCH_CHUNK_ROWS.", absent);
        }
    }

    /**
     * Captures the single row the service asked the repository to write.
     *
     * @return the written row
     */
    private Tweet savedRow() {
        ArgumentCaptor<Tweet> written = ArgumentCaptor.forClass(Tweet.class);
        verify(tweetRepository).save(written.capture());
        return written.getValue();
    }

    /**
     * Builds the unit under test with the four doubles and a real configuration record.
     *
     * @param popularityThreshold the value {@code scanner.popularity-threshold} carries
     * @return the service under test
     */
    private TwitterService serviceWithConfiguredThreshold(int popularityThreshold) {
        return new TwitterService(
                tweetRepository,
                settingRepository,
                propertiesWithPopularityThreshold(popularityThreshold),
                tweetMapper,
                sentimentAnalysisService);
    }

    /**
     * Builds a {@link ScannerProperties} whose {@code popularityThreshold} component carries the given
     * value and whose remaining components are unset.
     *
     * @param popularityThreshold the value of {@code scanner.popularity-threshold}
     * @return the bound configuration handed to the service under test
     */
    private static ScannerProperties propertiesWithPopularityThreshold(int popularityThreshold) {
        return new ScannerProperties(
                null, popularityThreshold, 0L, null, null, null, null, null, null, null, null);
    }
}
