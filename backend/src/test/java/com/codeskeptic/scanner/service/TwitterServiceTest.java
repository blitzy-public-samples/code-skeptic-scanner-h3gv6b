package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.TweetMapper;

// Ported from check_popularity_threshold at backend/app/services/twitter_service.py:L42-50 and from
// the two pass stubs at backend/tests/test_services.py:L12-22 — see docs/DECISION_LOG.md DL-040
/**
 * Exercises {@link TwitterService#meetsPopularityThreshold(Integer)}, the popularity gate of
 * {@code backend/app/services/twitter_service.py:L42-50}.
 *
 * <p>The gate is one of the two business rules the port freezes: the comparison is
 * {@code likeCount >= threshold}, inclusive, and the configured default threshold is {@code 100}, so
 * a like count of {@code 99} does not reach it while {@code 100} and {@code 101} do. That boundary is
 * asserted directly, and then again at two other thresholds so the assertion pins the comparison
 * rather than the constant.
 *
 * <p>The threshold itself is resolved on every call, and the {@code settings} row named
 * {@code tweet_popularity_threshold} takes precedence over {@code scanner.popularity-threshold} when
 * it holds an integer. A row holding text that is not an integer is ignored in favour of the
 * configured value rather than failing the call — DL-040.
 *
 * <p>The service is constructed directly with mocked collaborators. No Spring context is started, no
 * database is opened and no configuration file is read. Only {@link SettingRepository} and
 * {@link ScannerProperties} participate in the gate; {@link TweetRepository}, {@link TweetMapper} and
 * {@link SentimentAnalysisService} are supplied because the constructor requires them and are asserted
 * never to be touched.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TwitterService popularity gate")
class TwitterServiceTest {

    /** Primary key of the {@code settings} row the gate reads — DL-040. */
    private static final String THRESHOLD_KEY = "tweet_popularity_threshold";

    /** Configured threshold, the default declared at {@code backend/app/core/config.py:L10}. */
    private static final int CONFIGURED_THRESHOLD = 100;

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private SettingRepository settingRepository;

    @Mock
    private TweetMapper tweetMapper;

    @Mock
    private SentimentAnalysisService sentimentAnalysisService;

    private TwitterService twitterService;

    @BeforeEach
    void setUp() {
        twitterService = new TwitterService(tweetRepository, settingRepository,
                propertiesWithThreshold(CONFIGURED_THRESHOLD), tweetMapper, sentimentAnalysisService);
    }

    // backend/app/services/twitter_service.py:L46 — the inclusive comparison — AAP G3
    @ParameterizedTest(name = "[{index}] like count {0} meets threshold 100: {1}")
    @CsvSource({
        "98,  false",
        "99,  false",
        "100, true",
        "101, true",
        "102, true"
    })
    @DisplayName("reports the boundary of the configured threshold inclusively")
    void reportsTheBoundaryOfTheConfiguredThresholdInclusively(int likeCount, boolean expected) {
        when(settingRepository.findById(THRESHOLD_KEY)).thenReturn(Optional.empty());

        assertThat(twitterService.meetsPopularityThreshold(likeCount)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] like count {0} meets stored threshold 250: {1}")
    @CsvSource({
        "100, false",
        "249, false",
        "250, true",
        "251, true"
    })
    @DisplayName("compares against the stored threshold and not the configured one")
    void comparesAgainstTheStoredThreshold(int likeCount, boolean expected) {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding("250")));

        assertThat(twitterService.meetsPopularityThreshold(likeCount)).isEqualTo(expected);
    }

    // Setting row overrides configuration — DL-040
    @Test
    @DisplayName("lets a stored row override the configured threshold at the same like count")
    void letsAStoredRowOverrideTheConfiguredThreshold() {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding("175")))
                .thenReturn(Optional.empty());

        assertThat(twitterService.meetsPopularityThreshold(100)).isFalse();
        assertThat(twitterService.meetsPopularityThreshold(100)).isTrue();
    }

    @Test
    @DisplayName("resolves the threshold on every call rather than caching it")
    void resolvesTheThresholdOnEveryCall() {
        when(settingRepository.findById(THRESHOLD_KEY)).thenReturn(Optional.empty());

        twitterService.meetsPopularityThreshold(100);
        twitterService.meetsPopularityThreshold(100);
        twitterService.meetsPopularityThreshold(100);

        verify(settingRepository, times(3)).findById(THRESHOLD_KEY);
    }

    @ParameterizedTest(name = "[{index}] stored [{0}]")
    @ValueSource(strings = {"  100  ", "\t100", "100\n"})
    @DisplayName("discards whitespace surrounding a stored threshold")
    void discardsWhitespaceSurroundingAStoredThreshold(String stored) {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding(stored)));

        assertThat(twitterService.meetsPopularityThreshold(99)).isFalse();
        assertThat(twitterService.meetsPopularityThreshold(100)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] stored [{0}]")
    @ValueSource(strings = {"not-a-number", "", "   ", "12.5", "1e3", "100abc", "9999999999999"})
    @DisplayName("applies the configured threshold when a stored row holds no integer")
    void appliesTheConfiguredThresholdWhenAStoredRowHoldsNoInteger(String stored) {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding(stored)));

        assertThat(twitterService.meetsPopularityThreshold(99)).isFalse();
        assertThat(twitterService.meetsPopularityThreshold(100)).isTrue();
        assertThat(twitterService.meetsPopularityThreshold(101)).isTrue();
    }

    @Test
    @DisplayName("applies the configured threshold when a stored row holds a null value")
    void appliesTheConfiguredThresholdWhenAStoredRowHoldsANullValue() {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding(null)));

        assertThat(twitterService.meetsPopularityThreshold(99)).isFalse();
        assertThat(twitterService.meetsPopularityThreshold(100)).isTrue();
    }

    // A negative stored threshold is honoured as stored — DL-040
    @ParameterizedTest(name = "[{index}] stored threshold {0}, like count {1} meets it: {2}")
    @CsvSource({
        "-1,  0,   true",
        "-1,  -5,  false",
        "0,   0,   true",
        "1,   0,   false"
    })
    @DisplayName("honours a stored threshold at or below zero exactly as stored")
    void honoursAStoredThresholdAtOrBelowZero(String stored, int likeCount, boolean expected) {
        when(settingRepository.findById(THRESHOLD_KEY))
                .thenReturn(Optional.of(settingHolding(stored)));

        assertThat(twitterService.meetsPopularityThreshold(likeCount)).isEqualTo(expected);
    }

    @Test
    @DisplayName("reports false for an absent like count without reading the settings table")
    void reportsFalseForAnAbsentLikeCountWithoutReadingTheSettingsTable() {
        assertThat(twitterService.meetsPopularityThreshold(null)).isFalse();

        verifyNoInteractions(settingRepository);
    }

    @Test
    @DisplayName("reads no table other than settings while resolving the gate")
    void readsNoTableOtherThanSettings() {
        when(settingRepository.findById(THRESHOLD_KEY)).thenReturn(Optional.empty());

        twitterService.meetsPopularityThreshold(100);

        verify(settingRepository).findById(THRESHOLD_KEY);
        verifyNoInteractions(tweetRepository, tweetMapper, sentimentAnalysisService);
    }

    @Test
    @DisplayName("compares the like count alone and never a second quantity")
    void comparesTheLikeCountAloneAndNeverASecondQuantity() {
        when(settingRepository.findById(THRESHOLD_KEY)).thenReturn(Optional.empty());

        assertThat(twitterService.meetsPopularityThreshold(Integer.MAX_VALUE)).isTrue();
        assertThat(twitterService.meetsPopularityThreshold(Integer.MIN_VALUE)).isFalse();
    }

    /**
     * Builds the bound configuration with the popularity threshold this test needs.
     *
     * @param popularityThreshold value of {@code scanner.popularity-threshold}
     * @return bound configuration carrying that threshold and nothing else
     */
    private static ScannerProperties propertiesWithThreshold(int popularityThreshold) {
        return new ScannerProperties(null, popularityThreshold, 0L, null, null, null, null, null,
                null, null);
    }

    /**
     * Builds a {@code settings} row holding {@code value} under the threshold key.
     *
     * @param value the stored value, which may be {@code null}
     * @return the row the mocked repository returns
     */
    private static Setting settingHolding(String value) {
        Setting setting = new Setting();
        setting.setKey(THRESHOLD_KEY);
        setting.setValue(value);
        setting.setDescription("Minimum like count for a monitored post to be processed.");
        return setting;
    }
}
