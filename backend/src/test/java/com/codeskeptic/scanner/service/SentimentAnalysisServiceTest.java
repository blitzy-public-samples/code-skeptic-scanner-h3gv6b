package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.annotation.PreDestroy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.cloud.language.v1.AnalyzeSentimentResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.LanguageServiceClient;
import com.google.cloud.language.v1.Sentiment;

// Faithful-port coverage: the plain-text english document and document-sentiment score read at
// backend/app/services/sentiment_analysis.py:L12-24, and the doubt-rating formula and clamp at
// :L29,L32 — see docs/DECISION_LOG.md DL-036
// Net-new completion coverage: non-finite and out-of-range sentiment scores, null and blank text,
// provider-failure recovery, the client lifecycle, the accessor seam and the absence of any
// publishing operation — see docs/DECISION_LOG.md DL-062
// Replaces backend/tests/test_services.py:L54-65 — see docs/DECISION_LOG.md
/**
 * Exercises the two operations {@link SentimentAnalysisService} exposes:
 * {@link SentimentAnalysisService#analyzeSentiment(String)} and
 * {@link SentimentAnalysisService#calculateDoubtRating(double)}.
 *
 * <p>Every test obtains its Natural Language client from {@link SeamedService},
 * which overrides the protected
 * {@link SentimentAnalysisService#languageClient()} accessor and returns a
 * stubbed client. No test resolves a credential, opens a connection or reaches
 * Google Cloud.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SentimentAnalysisService")
class SentimentAnalysisServiceTest {

    /** Tolerance applied to every floating-point comparison in this class. */
    private static final double TOLERANCE = 1e-9d;

    /** Text passed to {@link SentimentAnalysisService#analyzeSentiment(String)}. */
    private static final String TWEET_TEXT = "AI coding tools still cannot get this right";

    /** Message of the {@link IllegalStateException} raised once the bean has been destroyed. */
    private static final String DESTROYED_MESSAGE =
            "SentimentAnalysisService has been destroyed; the Natural Language API client is closed";

    /** Message carried by the provider failure the stubbed client raises. */
    private static final String PROVIDER_FAILURE_MESSAGE = "the provider rejected the request";

    /** Operation names no method of the service may carry. */
    private static final String[] PUBLISHING_NAMES = { "publish", "post", "send", "tweet", "reply" };

    /** Stubbed Natural Language client; reached only through the protected accessor. */
    @Mock
    private LanguageServiceClient languageServiceClient;

    /** Unit under test, holding {@link #languageServiceClient} behind the accessor. */
    private SeamedService service;

    @BeforeEach
    void createService() {
        service = new SeamedService(languageServiceClient);
    }

    // ---------------------------------------------------------------------
    // Natural Language client acquisition
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("constructing the service reaches the language client accessor zero times")
    void constructingTheServiceReachesTheLanguageClientAccessorZeroTimes() {
        SeamedService freshlyConstructed = new SeamedService(languageServiceClient);

        assertThat(freshlyConstructed.languageClientAccessorCalls()).isZero();
        verifyNoInteractions(languageServiceClient);
    }

    @Test
    @DisplayName("analysing text reaches the language client accessor once")
    void analysingTextReachesTheLanguageClientAccessorOnce() {
        stubDocumentSentimentScore(0.25f);

        service.analyzeSentiment(TWEET_TEXT);

        assertThat(service.languageClientAccessorCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("calculating a doubt rating reaches the language client accessor zero times")
    void calculatingADoubtRatingReachesTheLanguageClientAccessorZeroTimes() {
        service.calculateDoubtRating(-0.5d);

        assertThat(service.languageClientAccessorCalls()).isZero();
        verifyNoInteractions(languageServiceClient);
    }

    // ---------------------------------------------------------------------
    // analyzeSentiment(String)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("sends a plain text english document carrying the given text")
    void sendsAPlainTextEnglishDocumentCarryingTheGivenText() {
        stubDocumentSentimentScore(0.25f);
        ArgumentCaptor<Document> sentDocument = ArgumentCaptor.forClass(Document.class);

        service.analyzeSentiment(TWEET_TEXT);

        verify(languageServiceClient).analyzeSentiment(sentDocument.capture());
        Document document = sentDocument.getValue();
        assertThat(document.getContent()).isEqualTo(TWEET_TEXT);
        assertThat(document.getType()).isEqualTo(Document.Type.PLAIN_TEXT);
        assertThat(document.getLanguage()).isEqualTo("en");
        assertThat(document.getAllFields()).hasSize(3);
        assertThat(document.hasContent()).isTrue();
        assertThat(document.hasGcsContentUri()).isFalse();
    }

    @Test
    @DisplayName("returns the document sentiment score")
    void returnsTheDocumentSentimentScore() {
        stubDocumentSentimentScore(0.25f);

        double score = service.analyzeSentiment(TWEET_TEXT);

        assertThat(score).isCloseTo(0.25d, within(TOLERANCE));
    }

    @Test
    @DisplayName("returns a negative document sentiment score unchanged")
    void returnsANegativeDocumentSentimentScoreUnchanged() {
        stubDocumentSentimentScore(-0.75f);

        double score = service.analyzeSentiment("This is terrible, I hate it.");

        assertThat(score).isCloseTo(-0.75d, within(TOLERANCE));
    }

    @Test
    @DisplayName("returns a positive document sentiment score unchanged")
    void returnsAPositiveDocumentSentimentScoreUnchanged() {
        stubDocumentSentimentScore(0.75f);

        double score = service.analyzeSentiment("I love this product, it's amazing!");

        assertThat(score).isCloseTo(0.75d, within(TOLERANCE));
    }

    @Test
    @DisplayName("returns a zero document sentiment score unchanged")
    void returnsAZeroDocumentSentimentScoreUnchanged() {
        stubDocumentSentimentScore(0.0f);

        double score = service.analyzeSentiment("The sky is blue.");

        assertThat(score).isCloseTo(0.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("declares a single string parameter and a double return type on analyzeSentiment")
    void declaresASingleStringParameterAndADoubleReturnTypeOnAnalyzeSentiment() throws NoSuchMethodException {
        Method analyzeSentiment =
                SentimentAnalysisService.class.getDeclaredMethod("analyzeSentiment", String.class);

        assertThat(analyzeSentiment.getParameterTypes()).containsExactly(String.class);
        assertThat(analyzeSentiment.getReturnType()).isEqualTo(double.class);
    }

    // ---------------------------------------------------------------------
    // calculateDoubtRating(double)
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "a sentiment score of {0} yields a doubt rating of {1}")
    @CsvSource({
            "-1.0,10.0",
            "1.0,0.0",
            "0.0,5.0",
            "-0.5,7.5",
            "0.5,2.5"
    })
    @DisplayName("maps a sentiment score between negative one and one to a doubt rating")
    void mapsASentimentScoreBetweenNegativeOneAndOneToADoubtRating(
            double sentimentScore, double expectedDoubtRating) {

        double doubtRating = service.calculateDoubtRating(sentimentScore);

        assertThat(doubtRating).isCloseTo(expectedDoubtRating, within(TOLERANCE));
    }

    @Test
    @DisplayName("clamps a sentiment score below negative one to a doubt rating of ten")
    void clampsASentimentScoreBelowNegativeOneToADoubtRatingOfTen() {
        double doubtRating = service.calculateDoubtRating(-2.0d);

        assertThat(doubtRating).isCloseTo(10.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("clamps a sentiment score above one to a doubt rating of zero")
    void clampsASentimentScoreAboveOneToADoubtRatingOfZero() {
        double doubtRating = service.calculateDoubtRating(2.0d);

        assertThat(doubtRating).isCloseTo(0.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("maps a NaN sentiment score to a doubt rating of ten")
    void mapsANaNSentimentScoreToADoubtRatingOfTen() {
        double doubtRating = service.calculateDoubtRating(Double.NaN);

        assertThat(doubtRating).isCloseTo(10.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("maps positive infinity to a doubt rating of zero")
    void mapsPositiveInfinityToADoubtRatingOfZero() {
        double doubtRating = service.calculateDoubtRating(Double.POSITIVE_INFINITY);

        assertThat(doubtRating).isCloseTo(0.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("maps negative infinity to a doubt rating of ten")
    void mapsNegativeInfinityToADoubtRatingOfTen() {
        double doubtRating = service.calculateDoubtRating(Double.NEGATIVE_INFINITY);

        assertThat(doubtRating).isCloseTo(10.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("returns a doubt rating for sentiment scores outside negative one to one without throwing")
    void returnsADoubtRatingForSentimentScoresOutsideNegativeOneToOneWithoutThrowing() {
        assertThatCode(() -> service.calculateDoubtRating(-2.0d)).doesNotThrowAnyException();
        assertThatCode(() -> service.calculateDoubtRating(2.0d)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("declares a single double parameter and a double return type on calculateDoubtRating")
    void declaresASingleDoubleParameterAndADoubleReturnTypeOnCalculateDoubtRating() throws NoSuchMethodException {
        Method calculateDoubtRating =
                SentimentAnalysisService.class.getDeclaredMethod("calculateDoubtRating", double.class);

        assertThat(calculateDoubtRating.getParameterTypes()).containsExactly(double.class);
        assertThat(calculateDoubtRating.getReturnType()).isEqualTo(double.class);
    }


    // ---------------------------------------------------------------------
    // Rejected and unusual input
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("rejects null text and reaches the client zero times")
    void rejectsNullTextAndReachesTheClientZeroTimes() {
        assertThatThrownBy(() -> service.analyzeSentiment(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("text must not be null");

        verifyNoInteractions(languageServiceClient);
        assertThat(service.languageClientAccessorCalls()).isZero();
    }

    @Test
    @DisplayName("sends blank text to the client unchanged")
    void sendsBlankTextToTheClientUnchanged() {
        stubDocumentSentimentScore(0.0f);

        double score = service.analyzeSentiment("   ");

        ArgumentCaptor<Document> sent = ArgumentCaptor.forClass(Document.class);
        verify(languageServiceClient).analyzeSentiment(sent.capture());
        assertThat(sent.getValue().getContent()).isEqualTo("   ");
        assertThat(score).isCloseTo(0.0d, within(TOLERANCE));
    }

    @Test
    @DisplayName("sends empty text to the client unchanged")
    void sendsEmptyTextToTheClientUnchanged() {
        stubDocumentSentimentScore(0.0f);

        service.analyzeSentiment("");

        ArgumentCaptor<Document> sent = ArgumentCaptor.forClass(Document.class);
        verify(languageServiceClient).analyzeSentiment(sent.capture());
        assertThat(sent.getValue().getContent()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // A provider failure
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("propagates a provider failure unchanged")
    void propagatesAProviderFailureUnchanged() {
        IllegalStateException raised = new IllegalStateException(PROVIDER_FAILURE_MESSAGE);
        when(languageServiceClient.analyzeSentiment(any(Document.class))).thenThrow(raised);

        assertThatThrownBy(() -> service.analyzeSentiment(TWEET_TEXT)).isSameAs(raised);
    }

    @Test
    @DisplayName("remains usable after a provider failure")
    void remainsUsableAfterAProviderFailure() {
        AnalyzeSentimentResponse response = AnalyzeSentimentResponse.newBuilder()
                .setDocumentSentiment(Sentiment.newBuilder().setScore(-0.5f).build())
                .build();
        when(languageServiceClient.analyzeSentiment(any(Document.class)))
                .thenThrow(new IllegalStateException(PROVIDER_FAILURE_MESSAGE))
                .thenReturn(response);

        assertThatThrownBy(() -> service.analyzeSentiment(TWEET_TEXT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.analyzeSentiment(TWEET_TEXT)).isCloseTo(-0.5d, within(TOLERANCE));
    }

    // ---------------------------------------------------------------------
    // The client lifecycle
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("rejects analysis once the bean has been destroyed")
    void rejectsAnalysisOnceTheBeanHasBeenDestroyed() {
        service.closeLanguageClient();

        assertThatThrownBy(() -> service.analyzeSentiment(TWEET_TEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DESTROYED_MESSAGE);

        verifyNoInteractions(languageServiceClient);
    }

    @Test
    @DisplayName("closing the client more than once raises nothing")
    void closingTheClientMoreThanOnceRaisesNothing() {
        assertThatCode(() -> {
            service.closeLanguageClient();
            service.closeLanguageClient();
            service.closeLanguageClient();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("still converts a sentiment score to a doubt rating once the bean has been destroyed")
    void stillConvertsASentimentScoreOnceTheBeanHasBeenDestroyed() {
        service.closeLanguageClient();

        assertThat(service.calculateDoubtRating(0.0d)).isCloseTo(5.0d, within(TOLERANCE));
        verifyNoInteractions(languageServiceClient);
    }

    @Test
    @DisplayName("declares the client release as its destruction callback")
    void declaresTheClientReleaseAsItsDestructionCallback() throws NoSuchMethodException {
        Method close = SentimentAnalysisService.class.getDeclaredMethod("closeLanguageClient");

        assertThat(close.isAnnotationPresent(PreDestroy.class)).isTrue();
        assertThat(close.getReturnType()).isEqualTo(void.class);
        assertThat(close.getParameterCount()).isZero();
    }

    @Test
    @DisplayName("leaves the client accessor open to a subclass")
    void leavesTheClientAccessorOpenToASubclass() throws NoSuchMethodException {
        Method accessor = SentimentAnalysisService.class.getDeclaredMethod("languageClient");

        assertThat(Modifier.isPrivate(accessor.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(accessor.getModifiers())).isFalse();
    }

    // ---------------------------------------------------------------------
    // Non-finite sentiment scores
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "a non-finite sentiment score of {0} yields a doubt rating of {1}")
    @CsvSource({
            "NaN,10.0",
            "-Infinity,10.0",
            "Infinity,0.0"
    })
    @DisplayName("bounds a non-finite sentiment score to the doubt rating range")
    void boundsANonFiniteSentimentScoreToTheDoubtRatingRange(
            double sentimentScore, double expectedDoubtRating) {

        double doubtRating = service.calculateDoubtRating(sentimentScore);

        assertThat(doubtRating).isCloseTo(expectedDoubtRating, within(TOLERANCE));
        assertThat(Double.isFinite(doubtRating)).isTrue();
    }

    @ParameterizedTest(name = "a sentiment score of {0} yields a doubt rating inside 0.0 to 10.0")
    @CsvSource({
            "-1000.0", "-2.0", "-1.0", "-0.5", "0.0", "0.5", "1.0", "2.0", "1000.0",
            "NaN", "-Infinity", "Infinity"
    })
    @DisplayName("returns a doubt rating inside the persisted range for every sentiment score")
    void returnsADoubtRatingInsideThePersistedRangeForEverySentimentScore(double sentimentScore) {
        double doubtRating = service.calculateDoubtRating(sentimentScore);

        assertThat(doubtRating).isBetween(0.0d, 10.0d);
        assertThat(Double.isFinite(doubtRating)).isTrue();
    }

    // ---------------------------------------------------------------------
    // The reported score
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("widens the reported float score to a double without rounding it")
    void widensTheReportedFloatScoreToADoubleWithoutRoundingIt() {
        stubDocumentSentimentScore(0.1f);

        double score = service.analyzeSentiment(TWEET_TEXT);

        assertThat(score).isEqualTo((double) 0.1f);
    }

    @Test
    @DisplayName("declares no operation that publishes to X")
    void declaresNoOperationThatPublishesToX() {
        assertThat(Arrays.stream(SentimentAnalysisService.class.getDeclaredMethods())
                .map(Method::getName)
                .toList())
                .noneSatisfy(name -> assertThat(name.toLowerCase(java.util.Locale.ROOT))
                        .containsAnyOf(PUBLISHING_NAMES));
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /**
     * Makes the stubbed client answer any {@link Document} with a response whose
     * document sentiment carries {@code score}.
     *
     * @param score the document sentiment score the stubbed client reports
     */
    private void stubDocumentSentimentScore(float score) {
        AnalyzeSentimentResponse response = AnalyzeSentimentResponse.newBuilder()
                .setDocumentSentiment(Sentiment.newBuilder().setScore(score).build())
                .build();
        when(languageServiceClient.analyzeSentiment(any(Document.class))).thenReturn(response);
    }

    /**
     * {@link SentimentAnalysisService} with the protected Natural Language client
     * accessor overridden to return a supplied client and to count how often the
     * accessor is reached.
     */
    private static final class SeamedService extends SentimentAnalysisService {

        private final LanguageServiceClient suppliedClient;

        /**
         * Accessor invocation count, created on first use by
         * {@link #accessorCalls()}. Calls made while the superclass constructor is
         * still running are counted and retained.
         */
        private AtomicInteger accessorCalls;

        private SeamedService(LanguageServiceClient suppliedClient) {
            this.suppliedClient = suppliedClient;
        }

        @Override
        protected LanguageServiceClient languageClient() {
            accessorCalls().incrementAndGet();
            return suppliedClient;
        }

        private synchronized AtomicInteger accessorCalls() {
            if (accessorCalls == null) {
                accessorCalls = new AtomicInteger();
            }
            return accessorCalls;
        }

        /**
         * Returns how often {@link #languageClient()} has been reached on this
         * instance.
         *
         * @return the accessor invocation count
         */
        private int languageClientAccessorCalls() {
            return accessorCalls().get();
        }
    }
}
