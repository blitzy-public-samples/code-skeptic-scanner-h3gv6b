package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.annotation.PreDestroy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.UnaryCallSettings;
import com.google.cloud.language.v1.AnalyzeSentimentRequest;
import com.google.cloud.language.v1.AnalyzeSentimentResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.LanguageServiceClient;
import com.google.cloud.language.v1.LanguageServiceSettings;
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
    private static final double TOLERANCE = 1e-9d;

    private static final String TWEET_TEXT = "AI coding tools still cannot get this right";

    private static final String DESTROYED_MESSAGE =
            "SentimentAnalysisService has been destroyed; the Natural Language API client is closed";

    // Bounds of the shutdown-drain tests — DL-268 — see docs/DECISION_LOG.md

    private static final long LATCH_LIMIT_SECONDS = 10L;

    private static final long SETTLE_MILLIS = 300L;

    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final String PROVIDER_FAILURE_MESSAGE = "the provider rejected the request";

    /**
     * Window one analyze request was measured occupying a request thread against an unreachable
     * provider before the call was bounded. The delivered total deadline is asserted well inside it
     * — DL-298 — see docs/DECISION_LOG.md.
     */
    private static final Duration MEASURED_PARKED_WINDOW = Duration.ofSeconds(30L);

    /** Operation names no method of the service may carry. */
    private static final String[] PUBLISHING_NAMES = { "publish", "post", "send", "tweet", "reply" };

    @Mock
    private LanguageServiceClient languageServiceClient;

    private SeamedService service;

    @BeforeEach
    void createService() {
        service = new SeamedService(languageServiceClient);
    }

    // The client is reached on first use and not at construction — DL-288 — see
    // docs/DECISION_LOG.md
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

    // A non-finite provider score has no numeric wire form — DL-233 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a provider score of {0} is reported as a failure")
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    @DisplayName("reports a document sentiment score that is not a finite number as a failure")
    void reportsADocumentSentimentScoreThatIsNotAFiniteNumberAsAFailure(float score) {
        stubDocumentSentimentScore(score);

        assertThatThrownBy(() -> service.analyzeSentiment(TWEET_TEXT))
                .isInstanceOf(IllegalStateException.class);
    }

    // A finite score outside the documented range is still returned unchanged — DL-233
    @ParameterizedTest(name = "a provider score of {0} is returned unchanged")
    @ValueSource(floats = {-2.0f, -1.0f, 1.0f, 2.0f, 3.4028235E38f})
    @DisplayName("returns a finite document sentiment score unchanged, in range or not")
    void returnsAFiniteDocumentSentimentScoreUnchangedInRangeOrNot(float score) {
        stubDocumentSentimentScore(score);

        assertThat(service.analyzeSentiment(TWEET_TEXT)).isCloseTo(score, within(TOLERANCE));
    }

    // A non-finite provider score has no numeric wire form — DL-233 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("remains usable after a non-finite score has been reported as a failure")
    void remainsUsableAfterANonFiniteScoreHasBeenReportedAsAFailure() {
        AnalyzeSentimentResponse nonFinite = AnalyzeSentimentResponse.newBuilder()
                .setDocumentSentiment(Sentiment.newBuilder().setScore(Float.NaN).build())
                .build();
        AnalyzeSentimentResponse finite = AnalyzeSentimentResponse.newBuilder()
                .setDocumentSentiment(Sentiment.newBuilder().setScore(-0.5f).build())
                .build();
        when(languageServiceClient.analyzeSentiment(any(Document.class)))
                .thenReturn(nonFinite)
                .thenReturn(finite);

        assertThatThrownBy(() -> service.analyzeSentiment(TWEET_TEXT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.analyzeSentiment(TWEET_TEXT)).isCloseTo(-0.5d, within(TOLERANCE));
    }

    @Test
    @DisplayName("declares a single string parameter and a double return type on analyzeSentiment")
    void declaresASingleStringParameterAndADoubleReturnTypeOnAnalyzeSentiment() throws NoSuchMethodException {
        Method analyzeSentiment =
                SentimentAnalysisService.class.getDeclaredMethod("analyzeSentiment", String.class);

        assertThat(analyzeSentiment.getParameterTypes()).containsExactly(String.class);
        assertThat(analyzeSentiment.getReturnType()).isEqualTo(double.class);
    }

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
    @DisplayName("awaits an in-flight analysis before releasing the language client")
    void awaitsAnInFlightAnalysisBeforeReleasingTheLanguageClient() throws Exception {
        CountDownLatch analysisEntered = new CountDownLatch(1);
        CountDownLatch releaseAnalysis = new CountDownLatch(1);
        SentimentAnalysisService holdingAClient = serviceHoldingClient(languageServiceClient);
        stubLatchedDocumentSentiment(analysisEntered, releaseAnalysis, 0.25f);

        ExecutorService analyser = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            Future<Double> analysis =
                    analyser.submit(() -> holdingAClient.analyzeSentiment(TWEET_TEXT));
            assertThat(analysisEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdown = closer.submit(holdingAClient::closeLanguageClient);
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> shutdown.get(SETTLE_MILLIS, TimeUnit.MILLISECONDS));
            verify(languageServiceClient, never()).close();

            releaseAnalysis.countDown();
            assertThat(analysis.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS))
                    .isCloseTo(0.25d, within(1e-6d));
            shutdown.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS);

            verify(languageServiceClient, times(1)).close();
        } finally {
            releaseAnalysis.countDown();
            awaitTermination(analyser, closer);
        }
    }

    @Test
    @DisplayName("rejects an analysis that arrives while a shutdown is waiting, without queueing it")
    void rejectsAnAnalysisThatArrivesWhileAShutdownIsWaiting() throws Exception {
        CountDownLatch analysisEntered = new CountDownLatch(1);
        CountDownLatch releaseAnalysis = new CountDownLatch(1);
        SentimentAnalysisService holdingAClient = serviceHoldingClient(languageServiceClient);
        stubLatchedDocumentSentiment(analysisEntered, releaseAnalysis, 0.25f);

        ExecutorService analyser = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            analyser.submit(() -> holdingAClient.analyzeSentiment(TWEET_TEXT));
            assertThat(analysisEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdown = closer.submit(holdingAClient::closeLanguageClient);
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> shutdown.get(SETTLE_MILLIS, TimeUnit.MILLISECONDS));

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> holdingAClient.analyzeSentiment(TWEET_TEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(DESTROYED_MESSAGE);
            long elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;

            assertThat(elapsedMillis)
                    .as("the rejection did not queue behind the release")
                    .isLessThan(SETTLE_MILLIS);

            releaseAnalysis.countDown();
            shutdown.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseAnalysis.countDown();
            awaitTermination(analyser, closer);
        }
    }

    @Test
    @DisplayName("releases the language client and restores the interrupt when the wait is interrupted")
    void releasesTheLanguageClientAndRestoresTheInterruptWhenTheWaitIsInterrupted() throws Exception {
        CountDownLatch analysisEntered = new CountDownLatch(1);
        CountDownLatch releaseAnalysis = new CountDownLatch(1);
        SentimentAnalysisService holdingAClient = serviceHoldingClient(languageServiceClient);
        stubLatchedDocumentSentiment(analysisEntered, releaseAnalysis, 0.25f);

        ExecutorService analyser = Executors.newSingleThreadExecutor();
        try {
            analyser.submit(() -> holdingAClient.analyzeSentiment(TWEET_TEXT));
            assertThat(analysisEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread closer = new Thread(() -> {
                holdingAClient.closeLanguageClient();
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }, "sentiment-shutdown-under-interrupt");
            closer.start();
            closer.interrupt();
            closer.join(LATCH_LIMIT_SECONDS * MILLIS_PER_SECOND);

            assertThat(closer.isAlive()).as("the interrupted shutdown returned").isFalse();
            assertThat(interruptRestored).isTrue();
            verify(languageServiceClient, times(1)).close();
        } finally {
            releaseAnalysis.countDown();
            awaitTermination(analyser);
        }
    }

    // The accessor a subclass supplies the client through — DL-288 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("leaves the client accessor open to a subclass")
    void leavesTheClientAccessorOpenToASubclass() throws NoSuchMethodException {
        Method accessor = SentimentAnalysisService.class.getDeclaredMethod("languageClient");

        assertThat(Modifier.isPrivate(accessor.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(accessor.getModifiers())).isFalse();
    }

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
    // Bounded provider deadlines and retry policy — DL-298
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("bounds one AnalyzeSentiment attempt and the whole call, and caps the attempts")
    void boundsOneAnalyzeSentimentAttemptAndTheWholeCall() throws Exception {
        RetrySettings bounded = analyzeSentimentSettings().getRetrySettings();

        assertThat(bounded.getInitialRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(5L));
        assertThat(bounded.getMaxRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(5L));
        assertThat(bounded.getTotalTimeoutDuration()).isEqualTo(Duration.ofSeconds(10L));
        assertThat(bounded.getMaxAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("re-attempts an unavailable provider and does not re-attempt an exceeded deadline")
    void reAttemptsAnUnavailableProviderAndNotAnExceededDeadline() throws Exception {
        assertThat(analyzeSentimentSettings().getRetryableCodes())
                .containsExactly(StatusCode.Code.UNAVAILABLE)
                .doesNotContain(StatusCode.Code.DEADLINE_EXCEEDED);
    }

    @Test
    @DisplayName("cannot occupy its calling thread for the window an unbounded call occupied")
    void cannotOccupyItsCallingThreadForTheWindowAnUnboundedCallOccupied() throws Exception {
        RetrySettings bounded = analyzeSentimentSettings().getRetrySettings();

        assertThat(bounded.getTotalTimeoutDuration())
                .as("the whole call stays well inside the window that parked a request thread")
                .isLessThanOrEqualTo(MEASURED_PARKED_WINDOW.dividedBy(3L));
        assertThat(bounded.getMaxRpcTimeoutDuration().multipliedBy(bounded.getMaxAttempts()))
                .as("every attempt the cap allows fits inside the total")
                .isLessThanOrEqualTo(bounded.getTotalTimeoutDuration());
    }

    @Test
    @DisplayName("narrows AnalyzeSentiment alone and leaves another operation's settings in place")
    void narrowsAnalyzeSentimentAloneAndLeavesAnotherOperationsSettingsInPlace() throws Exception {
        LanguageServiceSettings settings = languageServiceSettings();

        assertThat(settings.analyzeEntitiesSettings().getRetrySettings().getTotalTimeoutDuration())
                .as("another operation keeps the generated client's own total timeout")
                .isNotEqualTo(settings.analyzeSentimentSettings().getRetrySettings()
                        .getTotalTimeoutDuration());
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------
    /**
     * Builds the client settings the service applies, reaching its private factory reflectively so the
     * delivered settings object is asserted rather than the constants behind it — DL-298.
     *
     * @return the settings the service builds, never {@code null}
     * @throws ReflectiveOperationException if the factory cannot be reached or fails
     */
    private static LanguageServiceSettings languageServiceSettings()
            throws ReflectiveOperationException {
        Method factory = SentimentAnalysisService.class.getDeclaredMethod("languageServiceSettings");
        factory.setAccessible(true);
        return (LanguageServiceSettings) factory.invoke(new SentimentAnalysisService());
    }

    /**
     * The {@code AnalyzeSentiment} call settings of {@link #languageServiceSettings()}.
     *
     * @return the settings of the one operation this service narrows, never {@code null}
     * @throws ReflectiveOperationException if the factory cannot be reached or fails
     */
    private static UnaryCallSettings<AnalyzeSentimentRequest, AnalyzeSentimentResponse>
            analyzeSentimentSettings() throws ReflectiveOperationException {
        return languageServiceSettings().analyzeSentimentSettings();
    }


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
     * Makes the stubbed client report it has been entered and then block until it is released,
     * answering with a document sentiment carrying {@code score}.
     *
     * @param entered counted down once the stubbed call is running
     * @param release awaited by the stubbed call; counting it down completes the analysis
     * @param score   the document sentiment score reported once released — DL-268
     */
    private void stubLatchedDocumentSentiment(CountDownLatch entered, CountDownLatch release,
            float score) {
        AnalyzeSentimentResponse response = AnalyzeSentimentResponse.newBuilder()
                .setDocumentSentiment(Sentiment.newBuilder().setScore(score).build())
                .build();
        when(languageServiceClient.analyzeSentiment(any(Document.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latched analysis was never released");
            }
            return response;
        });
    }

    private static SentimentAnalysisService serviceHoldingClient(LanguageServiceClient heldClient)
            throws ReflectiveOperationException {
        SentimentAnalysisService service = new SentimentAnalysisService();
        Field field = SentimentAnalysisService.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(service, heldClient);
        return service;
    }

    private static void awaitTermination(ExecutorService... workers) throws InterruptedException {
        for (ExecutorService worker : workers) {
            worker.shutdown();
        }
        for (ExecutorService worker : workers) {
            if (!worker.awaitTermination(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS))
                        .as("the test executor terminated").isTrue();
            }
        }
    }

    private static final class SeamedService extends SentimentAnalysisService {
        private final LanguageServiceClient suppliedClient;

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

        private int languageClientAccessorCalls() {
            return accessorCalls().get();
        }
    }
}
