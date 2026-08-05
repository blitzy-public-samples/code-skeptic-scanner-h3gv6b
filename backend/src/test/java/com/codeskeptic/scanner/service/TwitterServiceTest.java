package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

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
            "meetsPopularityThreshold");

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
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.empty());
        TwitterService service = serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD);

        assertThatThrownBy(() -> service.updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE))
                .isExactlyInstanceOf(NotFoundException.class)
                .hasMessage(TWEET_NOT_FOUND);

        verify(tweetRepository, never()).save(any(Tweet.class));
        verifyNoInteractions(sentimentAnalysisService);
    }

    // -----------------------------------------------------------------------
    // updateTweetAnalysis(String, double) — the doubt rating written
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("writes the doubt rating the calculation returned")
    void writesTheDoubtRatingTheCalculationReturned() {
        Tweet row = fullyPopulatedTweet();
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        assertThat(savedRow().getDoubtRating()).isEqualTo(STUBBED_DOUBT_RATING);
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
        Tweet row = fullyPopulatedTweet();
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble())).thenReturn(doubtRating);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        assertThat(savedRow().getDoubtRating()).isEqualTo(doubtRating);
    }

    @Test
    @DisplayName("forwards the score it received to the doubt rating calculation")
    void forwardsTheScoreItReceivedToTheDoubtRatingCalculation() {
        Tweet row = fullyPopulatedTweet();
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble()))
                .thenReturn(STUBBED_DOUBT_RATING);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, -0.625d);

        ArgumentCaptor<Double> forwardedScore = ArgumentCaptor.forClass(Double.class);
        verify(sentimentAnalysisService).calculateDoubtRating(forwardedScore.capture());
        assertThat(forwardedScore.getValue()).isEqualTo(-0.625d);
    }

    @Test
    @DisplayName("saves the instance it loaded")
    void savesTheInstanceItLoaded() {
        Tweet row = fullyPopulatedTweet();
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        assertThat(savedRow()).isSameAs(row);
    }

    @Test
    @DisplayName("leaves every column other than the doubt rating unchanged")
    void leavesEveryColumnOtherThanTheDoubtRatingUnchanged() {
        Tweet row = fullyPopulatedTweet();
        when(tweetRepository.findById(TWEET_ID)).thenReturn(Optional.of(row));
        when(sentimentAnalysisService.calculateDoubtRating(ANALYSIS_SCORE))
                .thenReturn(STUBBED_DOUBT_RATING);

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD)
                .updateTweetAnalysis(TWEET_ID_PATH_VALUE, ANALYSIS_SCORE);

        Tweet written = savedRow();
        assertThat(written.getId()).isEqualTo(TWEET_ID);
        assertThat(written.getContent()).isEqualTo(CONTENT);
        assertThat(written.getLikeCount()).isEqualTo(LIKE_COUNT);
        assertThat(written.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(written.getMedia()).containsExactly(MEDIA_URL);
        assertThat(written.getQuotedTweetId()).isEqualTo(QUOTED_TWEET_ID);
        assertThat(written.getUserId()).isEqualTo(USER_ID);
        assertThat(written.getAiToolsMentioned()).containsExactly(AI_TOOL);
        assertThat(written.getResponses()).isEmpty();
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

    @ParameterizedTest(name = "a per_page of {0} reaches the repository unreduced")
    @ValueSource(ints = {100, 101, 500, 10_000, Integer.MAX_VALUE})
    @DisplayName("applies no upper bound to per_page")
    void appliesNoUpperBoundToPerPage(int perPage) {
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(perPage);
    }

    @Test
    @DisplayName("restates the unreduced per_page in the pagination block")
    void restatesTheUnreducedPerPageInThePaginationBlock() {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0L));
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto envelope =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 500);

        assertThat(envelope.pagination().perPage()).isEqualTo(500);
    }

    // -----------------------------------------------------------------------
    // getPaginatedTweets(int, int) — the envelope
    // -----------------------------------------------------------------------

    // No upper bound is applied to per_page — IR9 — see docs/DECISION_LOG.md DL-200
    @ParameterizedTest(name = "per_page {0} reads page size {1}")
    @CsvSource({
            "99,99",
            "100,100",
            "101,101",
            "1000,1000",
            "2147483647,2147483647"
    })
    @DisplayName("reads a per_page above the former upper bound unreduced")
    void readsAPerPageAboveTheUpperBoundAsTheUpperBound(int perPage, int expectedSize) {
        when(tweetRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable requested = invocation.getArgument(0);
                    return new PageImpl<>(List.of(), requested, 0L);
                });
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        PaginatedTweetsDto rendered =
                serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize())
                .as("page size the repository was asked for").isEqualTo(expectedSize);
        assertThat(rendered.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(expectedSize);
    }

    // Lower bounds applied to page and per_page — DL-077 — see docs/DECISION_LOG.md
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

    // backend/app/api/tweets.py:L12-13 declares a default for an absent parameter and no bound —
    // DL-077 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("requests the page size asked for, however large, applying no upper bound")
    void requestsThePageSizeAskedForHoweverLarge() {
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, 99999);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageNumber()).isZero();
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(99999);
    }

    // The per_page cap of DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} reads a page of size {1}")
    @CsvSource({
            "99,99",
            "100,100",
            "101,101",
            "250,250",
            "2147483647,2147483647"
    })
    @DisplayName("passes the page size through with no upper bound")
    void passesThePageSizeThroughWithNoUpperBound(int perPage, int expectedSize) {
        when(tweetRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(tweetMapper.toDtoList(anyList())).thenReturn(List.of());

        serviceWithConfiguredThreshold(CONFIGURED_THRESHOLD).getPaginatedTweets(1, perPage);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(tweetRepository).findAll(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(expectedSize);
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
    @DisplayName("declares exactly four public operations")
    void declaresExactlyFourPublicOperations() {
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

    private static Page<Tweet> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0L);
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
                null, popularityThreshold, 0L, null, null, null, null, null, null, null);
    }
}
