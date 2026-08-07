package com.codeskeptic.scanner.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.web.reactive.function.client.WebClient;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import com.openai.models.ReasoningEffort;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.services.blocking.ChatService;

import jakarta.annotation.PreDestroy;
import com.openai.services.blocking.chat.ChatCompletionService;

// Ported from backend/app/services/llm_service.py:L6-32 (faithful port) — see docs/DECISION_LOG.md
// Replaces backend/tests/test_services.py:L40-52 — see docs/DECISION_LOG.md
/**
 * Exercises the single operation {@link LlmService} exposes,
 * {@link LlmService#generateResponse(TweetDto)}, and the request it sends.
 *
 * <p>Every test obtains its OpenAI client from {@link SeamedService}, which overrides the protected
 * {@link LlmService#openAiClient()} accessor and returns a stubbed client. No test resolves a
 * credential, opens a connection or reaches OpenAI.
 *
 * <p>Requests are captured with an {@link ArgumentCaptor} over {@link ChatCompletionCreateParams}.
 * Responses are real {@link ChatCompletion} values built here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmService")
class LlmServiceTest {
    private static final double TOLERANCE = 1e-9d;

    // Bounds of the shutdown-drain tests — DL-266 — see docs/DECISION_LOG.md

    private static final long LATCH_LIMIT_SECONDS = 10L;

    private static final long SETTLE_MILLIS = 300L;

    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final String API_KEY = "not-a-real-openai-credential";

    private static final String MODEL = "gpt-5.6-terra";

    private static final long MAX_COMPLETION_TOKENS = 150L;

    private static final Double TEMPERATURE = null;

    private static final long N = 1L;

    private static final String REASONING_EFFORT = "low";

    private static final String UNACCEPTED_REASONING_EFFORT = "exhaustive";

    /**
     * The shipped {@code scanner.openai.reasoning-effort} value, and the only one under which the
     * configured model accepts an explicit temperature — DL-145, DL-200.
     */
    private static final String SHIPPED_REASONING_EFFORT = "none";

    private static final Double SHIPPED_TEMPERATURE = 0.7d;

    private static final long REQUEST_TIMEOUT_SECONDS = 30L;

    private static final int MAX_RETRIES = 2;

    private static final long DISTINCTIVE_REQUEST_TIMEOUT_SECONDS = 17L;

    private static final int DISTINCTIVE_MAX_RETRIES = 5;

    private static final String OTHER_MODEL = "a-different-chat-model-identifier";

    private static final long OTHER_MAX_COMPLETION_TOKENS = 77L;

    private static final Double OTHER_TEMPERATURE = 0.11d;

    private static final String TWEET_ID = "4711";

    private static final String TWEET_CONTENT = "AI coding tools still cannot get this right";

    private static final int LIKE_COUNT = 250;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 31, 9, 15);

    private static final double DOUBT_RATING = 7.5d;

    private static final List<String> MEDIA = List.of("https://pbs.example/media/1.png");

    private static final String USER_ID = "user-99";

    private static final List<String> AI_TOOLS_MENTIONED = List.of("GitHub Copilot", "Cursor");

    private static final String EXPECTED_PROMPT =
            "Generate a response to the following tweet: 'AI coding tools still cannot get this right'"
                    + "\n\n"
                    + "Context: AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"
                    + "\n\n"
                    + "Response:";

    private static final String PADDED_GENERATED_TEXT = "  Even seasoned reviewers disagree.  ";

    private static final int GUARDED_VALUE_LIMIT = 64;

    private static final String TRIMMED_GENERATED_TEXT = "Even seasoned reviewers disagree.";

    @Mock
    private OpenAIClient openAiClient;

    @Mock
    private ChatService chatService;

    @Mock
    private ChatCompletionService chatCompletionService;

    private SeamedService service;

    @BeforeEach
    void createService() {
        service = seamedServiceCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N));
    }

    @Test
    @DisplayName("constructing the service reaches the openai client accessor zero times")
    void constructingTheServiceReachesTheOpenaiClientAccessorZeroTimes() {
        SeamedService freshlyConstructed = seamedServiceCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N));

        assertThat(freshlyConstructed.openAiClientAccessorCalls()).isZero();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("constructing the service with an unset api key does not throw")
    void constructingTheServiceWithAnUnsetApiKeyDoesNotThrow() {
        ScannerProperties withoutApiKey = propertiesCarrying(
                new ScannerProperties.Openai(null, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                        REASONING_EFFORT, REQUEST_TIMEOUT_SECONDS, MAX_RETRIES));

        assertThatCode(() -> new LlmService(withoutApiKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("generating a reply with an unset api key reports the api key configuration key")
    void generatingAReplyWithAnUnsetApiKeyReportsTheApiKeyConfigurationKey() {
        LlmService withoutApiKey = new LlmService(propertiesCarrying(
                new ScannerProperties.Openai(null, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                        REASONING_EFFORT, REQUEST_TIMEOUT_SECONDS, MAX_RETRIES)));

        assertThatIllegalStateException()
                .isThrownBy(() -> withoutApiKey.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.api-key");
    }

    @Test
    @DisplayName("rejects a request timeout below one second rather than building an unbounded client")
    void rejectsARequestTimeoutBelowOneSecondRatherThanBuildingAnUnboundedClient() {
        LlmService withoutATimeout = new LlmService(propertiesCarrying(
                new ScannerProperties.Openai(API_KEY, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                        REASONING_EFFORT, 0L, MAX_RETRIES)));

        assertThatIllegalStateException()
                .isThrownBy(() -> withoutATimeout.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.request-timeout-seconds");
    }

    @Test
    @DisplayName("rejects a negative retry limit")
    void rejectsANegativeRetryLimit() {
        LlmService withANegativeRetryLimit = new LlmService(propertiesCarrying(
                new ScannerProperties.Openai(API_KEY, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                        REASONING_EFFORT, REQUEST_TIMEOUT_SECONDS, -1)));

        assertThatIllegalStateException()
                .isThrownBy(() -> withANegativeRetryLimit.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.max-retries");
    }

    @Test
    @DisplayName("generating a reply reaches the openai client accessor once")
    void generatingAReplyReachesTheOpenaiClientAccessorOnce() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(service.openAiClientAccessorCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects a null post without reaching the openai client")
    void rejectsANullPostWithoutReachingTheOpenaiClient() {
        assertThatNullPointerException().isThrownBy(() -> service.generateResponse(null));

        assertThat(service.openAiClientAccessorCalls()).isZero();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("sends one chat completions request and no streaming request")
    void sendsOneChatCompletionsRequestAndNoStreamingRequest() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        verify(openAiClient).chat();
        verify(chatService).completions();
        verify(chatCompletionService).create(any(ChatCompletionCreateParams.class));
        verify(chatCompletionService, never()).createStreaming(any(ChatCompletionCreateParams.class));
        verifyNoMoreInteractions(openAiClient, chatService, chatCompletionService);
    }

    @Test
    @DisplayName("sends the model identifier bound to the openai model property")
    void sendsTheModelIdentifierBoundToTheOpenaiModelProperty() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().model().asString()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("sends a second model identifier when the openai model property carries another value")
    void sendsASecondModelIdentifierWhenTheOpenaiModelPropertyCarriesAnotherValue() {
        service = seamedServiceCarrying(
                openaiGroup(OTHER_MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().model().asString()).isEqualTo(OTHER_MODEL);
    }

    @Test
    @DisplayName("reports the model configuration key without reaching the openai client when the model is unset")
    void reportsTheModelConfigurationKeyWithoutReachingTheOpenaiClientWhenTheModelIsUnset() {
        service = seamedServiceCarrying(
                openaiGroup(" ", MAX_COMPLETION_TOKENS, TEMPERATURE, N));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.model");

        assertThat(service.openAiClientAccessorCalls()).isZero();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("carries the prompt as a single user message")
    void carriesThePromptAsASingleUserMessage() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        List<ChatCompletionMessageParam> messages = capturedRequest().messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).isUser()).isTrue();
        assertThat(messages.get(0).asUser().content().isText()).isTrue();
        assertThat(messages.get(0).asUser().content().asText()).isEqualTo(EXPECTED_PROMPT);
    }

    @Test
    @DisplayName("sends a max completion tokens value of one hundred and fifty")
    void sendsAMaxCompletionTokensValueOfOneHundredAndFifty() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().maxCompletionTokens()).contains(150L);
    }

    // Content decides; a choice carrying none reports BLANK_TEXT whatever its finish reason —
    // DL-083, DL-202 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports blank text when the first choice carries no content, whatever its finish "
            + "reason")
    void reportsBlankTextWhenTheFirstChoiceCarriesNoContentWhateverItsFinishReason() {
        stubClientReturning(completionCarrying(
                choiceCarrying(Optional.empty(), ChatCompletion.Choice.FinishReason.LENGTH)));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessage("BLANK_TEXT");
    }

    // DL-083, DL-202 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports blank text when the first choice carries blank content, whatever its "
            + "finish reason")
    void reportsBlankTextWhenTheFirstChoiceCarriesBlankContentWhateverItsFinishReason() {
        stubClientReturning(completionCarrying(
                choiceCarrying(Optional.of("   "), ChatCompletion.Choice.FinishReason.LENGTH)));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessage("BLANK_TEXT");
    }

    // A truncated but usable reply is consumed and its finish reason is recorded — DL-083,
    // DL-202 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("accepts content cut short at the token cap and records the finish reason once")
    void acceptsContentCutShortAtTheTokenCapAndRecordsTheFinishReason() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(PADDED_GENERATED_TEXT), ChatCompletion.Choice.FinishReason.LENGTH)));

        assertThat(service.generateResponse(tweet())).isEqualTo(TRIMMED_GENERATED_TEXT);

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("INCOMPLETE:length"))
                .noneMatch(record -> record.contains(TRIMMED_GENERATED_TEXT));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("records the provider status, type, code and param when the request is rejected")
    void recordsTheProviderStatusTypeCodeAndParamWhenTheRequestIsRejected() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(rejectedRequest());

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.generateResponse(tweet()));

        assertThat(renderedRecords(records)).anyMatch(record -> record.contains("HTTP 400")
                && record.contains("unsupported_parameter")
                && record.contains("temperature"));
        assertThat(renderedRecords(records))
                .noneMatch(record -> record.contains("Unsupported parameter"));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("records absent for a provider type, code and param that fail the shape check")
    void recordsAbsentForProviderFieldsThatFailTheShapeCheck() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientFailure(BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .message("Unsupported parameter")
                        .type("Unsupported Parameter\nERROR forged")
                        .code("bad code\r\nWARN forged")
                        .param("a param with spaces")
                        .build())
                .build());

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.generateResponse(tweet()));

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("type absent")
                        && record.contains("code absent")
                        && record.contains("param absent"));
        assertThat(renderedRecords(records)).noneMatch(record -> record.contains("forged")
                || record.contains("\r") || record.contains("\n"));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("writes no post identifier at all, so no record boundary can be forged through one")
    void writesNoPostIdentifierAtAll() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        TweetDto forging = new TweetDto("47\r\nERROR forged administrative record", TWEET_CONTENT,
                LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA, null, USER_ID, AI_TOOLS_MENTIONED);
        stubClientFailure(new IllegalStateException("transport down"));

        assertThatIllegalStateException().isThrownBy(() -> service.generateResponse(forging));

        assertThat(renderedRecords(records)).isNotEmpty();
        assertThat(renderedRecords(records)).allSatisfy(record -> assertThat(record)
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain("47")
                .doesNotContain("forged")
                .doesNotContain("transport down"));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("neutralises control characters carried by the provider type, code and param of a "
            + "rejection")
    void neutralisesControlCharactersCarriedByTheProviderFieldsOfARejection() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(rejectedRequestCarryingControlCharacters());

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.generateResponse(tweet()));

        String logged = renderedRecords(records).stream()
                .filter(record -> record.contains("HTTP 400"))
                .findFirst()
                .orElseThrow();
        assertThat(logged).contains("type absent")
                .contains("code absent")
                .contains("param absent");
        assertThat(logged).doesNotContain("invalid")
                .doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0000");
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("withholds a provider param longer than the length the log guard carries")
    void withholdsAProviderParamLongerThanTheLogGuardCarries() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(rejectedRequestCarryingParam("y".repeat(300)));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.generateResponse(tweet()));

        String logged = renderedRecords(records).stream()
                .filter(record -> record.contains("HTTP 400"))
                .findFirst()
                .orElseThrow();
        assertThat(logged).contains("param absent")
                .doesNotContain("y".repeat(GUARDED_VALUE_LIMIT + 1));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("sends no temperature when scanner.openai.temperature is unset")
    void sendsNoTemperatureWhenTheKeyIsUnset() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().temperature()).isEmpty();
    }

    @Test
    @DisplayName("sends the reasoning effort bound to the openai properties")
    void sendsTheReasoningEffortBoundToTheOpenaiProperties() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        ChatCompletionCreateParams request = capturedRequest();
        assertThat(request.reasoningEffort()).isPresent();
        assertThat(request.reasoningEffort().orElseThrow().asString()).isEqualTo(REASONING_EFFORT);
    }

    @Test
    @DisplayName("sends no reasoning effort when the key is blank")
    void sendsNoReasoningEffortWhenTheKeyIsBlank() {
        service = seamedServiceCarrying(new ScannerProperties.Openai(API_KEY, MODEL,
                MAX_COMPLETION_TOKENS, TEMPERATURE, N, "   ", REQUEST_TIMEOUT_SECONDS, MAX_RETRIES));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("rejects a temperature outside the accepted range")
    void rejectsATemperatureOutsideTheAcceptedRange() {
        service = seamedServiceCarrying(openaiGroup(MODEL, MAX_COMPLETION_TOKENS, 2.5d, N));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.temperature");
    }

    @Test
    @DisplayName("rejects a max completion tokens value below one")
    void rejectsAMaxCompletionTokensValueBelowOne() {
        service = seamedServiceCarrying(openaiGroup(MODEL, 0L, TEMPERATURE, N));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.max-completion-tokens");
    }

    // Only the first choice is consumed, so only one may be requested — DL-267 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] n={0}")
    @ValueSource(longs = {-1L, 0L, 2L, 5L, 64L})
    @DisplayName("rejects any n other than one before the request is issued")
    void rejectsAnyNOtherThanOneBeforeTheRequestIsIssued(long configured) {
        service = seamedServiceCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, configured));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.n")
                .withMessageContaining("must be exactly 1");

        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("sends an n of one")
    void sendsAnNOfOne() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().n()).contains(1L);
    }

    @Test
    @DisplayName("sends the reasoning effort bound to the openai reasoning effort property")
    void sendsTheReasoningEffortBoundToTheOpenaiReasoningEffortProperty() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort())
                .contains(ReasoningEffort.of(REASONING_EFFORT));
    }

    @Test
    @DisplayName("sends a second reasoning effort when the property carries another accepted value")
    void sendsASecondReasoningEffortWhenThePropertyCarriesAnotherAcceptedValue() {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort("medium"));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).contains(ReasoningEffort.MEDIUM);
    }

    @Test
    @DisplayName("sends an accepted reasoning effort surrounded by padding")
    void sendsAnAcceptedReasoningEffortWhoseLetterCaseAndPaddingDiffer() {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort("  high  "));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).contains(ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("sends no reasoning effort when the property is blank")
    void sendsNoReasoningEffortWhenThePropertyIsBlank() {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort("   "));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("sends no reasoning effort when the property is unbound")
    void sendsNoReasoningEffortWhenThePropertyIsUnbound() {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort(null));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("reports the reasoning effort key and the accepted values without reaching the openai client")
    void reportsTheReasoningEffortKeyAndTheAcceptedValuesWithoutReachingTheOpenaiClient() {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort(UNACCEPTED_REASONING_EFFORT));

        Throwable failure = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage())
                .contains("scanner.openai.reasoning-effort")
                .contains("none", "low", "medium", "high", "xhigh", "max")
                .doesNotContain(UNACCEPTED_REASONING_EFFORT);

        assertThat(service.openAiClientAccessorCalls()).isZero();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("sends the second max completion tokens and temperature bound to the openai properties")
    void sendsTheSecondMaxCompletionTokensAndTemperatureBoundToTheOpenaiProperties() {
        service = seamedServiceCarrying(
                openaiGroup(MODEL, OTHER_MAX_COMPLETION_TOKENS, OTHER_TEMPERATURE, N));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        ChatCompletionCreateParams request = capturedRequest();
        assertThat(request.maxCompletionTokens()).contains(77L);
        assertThat(request.n()).contains(1L);
        assertThat(request.temperature()).isPresent();
        assertThat(request.temperature().orElseThrow()).isCloseTo(0.11d, within(TOLERANCE));
    }

    @Test
    @DisplayName("sends a temperature only when scanner.openai.temperature carries a value")
    void sendsATemperatureOnlyWhenTheKeyCarriesAValue() {
        service = seamedServiceCarrying(openaiGroup(MODEL, MAX_COMPLETION_TOKENS, 1.5d, N));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().temperature().orElseThrow()).isCloseTo(1.5d, within(TOLERANCE));
    }

    @Test
    @DisplayName("sends the shipped temperature alongside the shipped reasoning effort")
    void sendsTheShippedTemperatureAlongsideTheShippedReasoningEffort() {
        service = seamedServiceCarrying(new ScannerProperties.Openai(API_KEY, MODEL,
                MAX_COMPLETION_TOKENS, SHIPPED_TEMPERATURE, N, SHIPPED_REASONING_EFFORT,
                REQUEST_TIMEOUT_SECONDS, MAX_RETRIES));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        ChatCompletionCreateParams request = capturedRequest();
        assertThat(request.temperature().orElseThrow())
                .isCloseTo(SHIPPED_TEMPERATURE, within(TOLERANCE));
        assertThat(request.reasoningEffort())
                .contains(ReasoningEffort.of(SHIPPED_REASONING_EFFORT));
    }

    @ParameterizedTest(name = "a temperature carried with effort {0} is rejected")
    @MethodSource("effortsThatExcludeATemperature")
    @DisplayName("rejects a temperature carried alongside an effort other than none, naming both keys")
    void rejectsATemperatureCarriedAlongsideAnEffortOtherThanNoneNamingBothKeys(
            String reasoningEffort) {
        service = seamedServiceCarrying(new ScannerProperties.Openai(API_KEY, MODEL,
                MAX_COMPLETION_TOKENS, SHIPPED_TEMPERATURE, N, reasoningEffort,
                REQUEST_TIMEOUT_SECONDS, MAX_RETRIES));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.temperature")
                .withMessageContaining("scanner.openai.reasoning-effort");
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("sends a temperature when the reasoning effort key carries no value")
    void sendsATemperatureWhenTheReasoningEffortKeyCarriesNoValue() {
        service = seamedServiceCarrying(new ScannerProperties.Openai(API_KEY, MODEL,
                MAX_COMPLETION_TOKENS, SHIPPED_TEMPERATURE, N, "   ", REQUEST_TIMEOUT_SECONDS,
                MAX_RETRIES));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        ChatCompletionCreateParams request = capturedRequest();
        assertThat(request.temperature().orElseThrow())
                .isCloseTo(SHIPPED_TEMPERATURE, within(TOLERANCE));
        assertThat(request.reasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("rejects the minimal reasoning effort the sdk recognises but the model does not accept")
    void rejectsTheMinimalReasoningEffortTheSdkRecognisesButTheModelDoesNotAccept() {
        service = seamedServiceCarrying(
                openaiGroupWithReasoningEffort(ReasoningEffort.MINIMAL.asString()));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.reasoning-effort")
                .withMessageNotContaining(ReasoningEffort.MINIMAL.asString());
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("names no rejected value among the accepted reasoning efforts")
    void namesNoRejectedValueAmongTheAcceptedReasoningEfforts() {
        service = seamedServiceCarrying(
                openaiGroupWithReasoningEffort(ReasoningEffort.MINIMAL.asString()));

        Throwable failure = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage()).contains("none, low, medium, high, xhigh, max");
    }

    @Test
    @DisplayName("sends no max tokens value")
    @SuppressWarnings("deprecation")
    void sendsNoMaxTokensValue() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().maxTokens()).isEmpty();
    }

    @Test
    @DisplayName("sends no stop sequence")
    void sendsNoStopSequence() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().stop()).isEmpty();
    }

    @Test
    @DisplayName("sends the configured reasoning effort")
    void sendsTheConfiguredReasoningEffort() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort().orElseThrow().asString())
                .isEqualTo(REASONING_EFFORT);
    }

    @ParameterizedTest(name = "reasoning effort {0} reaches the request")
    @MethodSource("acceptedReasoningEfforts")
    @DisplayName("sends every reasoning effort the configured model accepts")
    void sendsEveryReasoningEffortTheConfiguredModelAccepts(String reasoningEffort) {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort(reasoningEffort));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort())
                .contains(ReasoningEffort.of(reasoningEffort));
    }

    @ParameterizedTest(name = "reasoning effort {0} is trimmed before it reaches the request")
    @MethodSource("paddedReasoningEfforts")
    @DisplayName("trims the configured reasoning effort")
    void trimsTheConfiguredReasoningEffort(String reasoningEffort) {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort(reasoningEffort));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).contains(ReasoningEffort.LOW);
    }

    @ParameterizedTest(name = "reasoning effort {0} omits the parameter")
    @MethodSource("blankReasoningEfforts")
    @DisplayName("omits the reasoning effort when the configured value is absent or blank")
    void omitsTheReasoningEffortWhenTheConfiguredValueIsAbsentOrBlank(String reasoningEffort) {
        service = seamedServiceCarrying(openaiGroupWithReasoningEffort(reasoningEffort));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().reasoningEffort()).isEmpty();
        assertThat(capturedRequest()._reasoningEffort().isMissing()).isTrue();
    }

    @ParameterizedTest(name = "reasoning effort {0} is rejected")
    @MethodSource("unacceptedReasoningEfforts")
    @DisplayName("rejects a reasoning effort the configured model does not accept, naming the key")
    void rejectsAReasoningEffortTheConfiguredModelDoesNotAcceptNamingTheKey(String reasoningEffort) {
        LlmService misconfigured = seamedServiceCarrying(
                openaiGroupWithReasoningEffort(reasoningEffort));

        assertThatIllegalStateException()
                .isThrownBy(() -> misconfigured.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.reasoning-effort")
                .withMessageContaining("none, low, medium, high, xhigh, max");
    }

    @Test
    @DisplayName("rejects an unaccepted reasoning effort before it reaches the openai client")
    void rejectsAnUnacceptedReasoningEffortBeforeItReachesTheOpenaiClient() {
        LlmService misconfigured = seamedServiceCarrying(
                openaiGroupWithReasoningEffort("Minimal"));

        catchThrowable(() -> misconfigured.generateResponse(tweet()));

        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("never renders the configured reasoning effort in the failure it reports")
    void neverRendersTheConfiguredReasoningEffortInTheFailureItReports() {
        LlmService misconfigured = seamedServiceCarrying(
                openaiGroupWithReasoningEffort("exhaustive"));

        Throwable thrown = catchThrowable(() -> misconfigured.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).doesNotContain("exhaustive");
    }

    @Test
    @DisplayName("composes the prompt from the post body and the context clause")
    void composesThePromptFromThePostBodyAndTheContextClause() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(promptOf(capturedRequest())).isEqualTo(EXPECTED_PROMPT);
    }

    @Test
    @DisplayName("quotes the post body and separates the three prompt segments with blank lines")
    void quotesThePostBodyAndSeparatesTheThreePromptSegmentsWithBlankLines() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).startsWith("Generate a response to the following tweet: '");
        assertThat(prompt).contains("'" + TWEET_CONTENT + "'");
        assertThat(prompt).contains("\n\nContext: ");
        assertThat(prompt).endsWith("\n\nResponse:");
        assertThat(prompt).doesNotEndWith("\n\nResponse:\n");
        assertThat(prompt.split("\n\n", -1)).hasSize(3);
    }

    @Test
    @DisplayName("composes the same prompt twice for the same post")
    void composesTheSamePromptTwiceForTheSamePost() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto sameTweet = tweet();

        service.generateResponse(sameTweet);
        service.generateResponse(sameTweet);

        List<ChatCompletionCreateParams> requests = capturedRequests(2);
        String firstPrompt = promptOf(requests.get(0));
        String secondPrompt = promptOf(requests.get(1));
        assertThat(secondPrompt).isEqualTo(firstPrompt);
        assertThat(secondPrompt.getBytes(UTF_8)).isEqualTo(firstPrompt.getBytes(UTF_8));
        assertThat(firstPrompt).isEqualTo(EXPECTED_PROMPT);
    }

    @Test
    @DisplayName("composes a context clause carrying the ai tool names and the doubt rating")
    void composesAContextClauseCarryingTheAiToolNamesAndTheDoubtRating() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).contains("GitHub Copilot");
        assertThat(prompt).contains("Cursor");
        assertThat(prompt).contains("GitHub Copilot, Cursor");
        assertThat(prompt).contains("7.5");
        assertThat(prompt).contains("Context: AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5");
    }

    @Test
    @DisplayName("composes a context clause naming no ai tool when the post carries an empty list")
    void composesAContextClauseNamingNoAiToolWhenThePostCarriesAnEmptyList() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withoutAiTools = tweetCarrying(TWEET_CONTENT, DOUBT_RATING, List.of());

        assertThatCode(() -> service.generateResponse(withoutAiTools)).doesNotThrowAnyException();

        assertThat(promptOf(capturedRequest()))
                .isEqualTo(expectedPrompt(TWEET_CONTENT, "AI tools mentioned: none; doubt rating: 7.5"));
    }

    @ParameterizedTest(name = "a doubt rating of {0} yields an unknown doubt rating in the context clause")
    @MethodSource("doubtRatingsThatAreNotNumbers")
    @DisplayName("composes a context clause carrying an unknown doubt rating when the rating is not a number")
    void composesAContextClauseCarryingAnUnknownDoubtRatingWhenTheRatingIsNotANumber(double doubtRating) {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withoutANumericRating = tweetCarrying(TWEET_CONTENT, doubtRating, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withoutANumericRating))
                .doesNotThrowAnyException();

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(
                TWEET_CONTENT, "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: unknown"));
    }

    @Test
    @DisplayName("composes a context clause naming no ai tool and an unknown doubt rating together")
    void composesAContextClauseNamingNoAiToolAndAnUnknownDoubtRatingTogether() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withNeither = tweetCarrying(TWEET_CONTENT, Double.NaN, List.of());

        assertThatCode(() -> service.generateResponse(withNeither)).doesNotThrowAnyException();

        assertThat(promptOf(capturedRequest()))
                .isEqualTo(expectedPrompt(TWEET_CONTENT, "AI tools mentioned: none; doubt rating: unknown"));
    }

    @Test
    @DisplayName("carries a doubt rating as a boxed Double on the post record")
    void carriesADoubtRatingAsABoxedDoubleOnThePostRecord() {
        RecordComponent doubtRating = recordComponent(TweetDto.class, "doubtRating");

        assertThat(doubtRating.getType()).isEqualTo(Double.class);
        assertThat(recordComponent(TweetDto.class, "likeCount").getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("reads a post whose list components are null as a post carrying empty lists")
    void readsAPostWhoseListComponentsAreNullAsAPostCarryingEmptyLists() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withoutLists = new TweetDto(TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT,
                DOUBT_RATING, null, null, USER_ID, null);

        assertThat(withoutLists.media()).isEmpty();
        assertThat(withoutLists.aiToolsMentioned()).isEmpty();
        assertThatCode(() -> service.generateResponse(withoutLists)).doesNotThrowAnyException();
        assertThat(promptOf(capturedRequest()))
                .isEqualTo(expectedPrompt(TWEET_CONTENT, "AI tools mentioned: none; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("carries an empty post body into the prompt between quotes")
    void carriesAnEmptyPostBodyIntoThePromptBetweenQuotes() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withoutABody = tweetCarrying("", DOUBT_RATING, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withoutABody)).doesNotThrowAnyException();

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(
                "", "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("carries a blank post body into the prompt unchanged")
    void carriesABlankPostBodyIntoThePromptUnchanged() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withABlankBody = tweetCarrying("   ", DOUBT_RATING, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withABlankBody)).doesNotThrowAnyException();

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(
                "   ", "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    // The post body is interpolated untruncated — DL-035 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("carries a five thousand character post body into the prompt untruncated")
    void carriesAFiveThousandCharacterPostBodyIntoThePromptUntruncated() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        String longBody = "a".repeat(5_000);
        TweetDto withALongBody = tweetCarrying(longBody, DOUBT_RATING, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withALongBody)).doesNotThrowAnyException();

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).contains(longBody);
        assertThat(prompt).doesNotContain("\u2026");
        assertThat(prompt).isEqualTo(expectedPrompt(longBody,
                "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    // The post body is interpolated unfolded — DL-035 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("carries a line break inside the post body into the prompt unfolded")
    void carriesALineBreakInsideThePostBodyIntoThePromptUnfolded() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withLineBreaks =
                tweetCarrying("first\r\nsecond", DOUBT_RATING, AI_TOOLS_MENTIONED);

        service.generateResponse(withLineBreaks);

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt("first\r\nsecond",
                "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("carries a single quote inside the post body into the prompt unescaped")
    void carriesASingleQuoteInsideThePostBodyIntoThePromptUnescaped() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        String bodyWithAQuote = "it's not ready";
        TweetDto withAQuote = tweetCarrying(bodyWithAQuote, DOUBT_RATING, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withAQuote)).doesNotThrowAnyException();

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).contains("'it's not ready'");
        assertThat(prompt).doesNotContain("\\'");
        assertThat(prompt).isEqualTo(expectedPrompt(
                bodyWithAQuote, "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("carries no more than ten ai tool names into the prompt")
    void carriesNoMoreThanTenAiToolNamesIntoThePrompt() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        List<String> fifteenNames = List.of("t01", "t02", "t03", "t04", "t05", "t06", "t07", "t08",
                "t09", "t10", "t11", "t12", "t13", "t14", "t15");
        TweetDto withManyNames = tweetCarrying(TWEET_CONTENT, DOUBT_RATING, fifteenNames);

        service.generateResponse(withManyNames);

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(TWEET_CONTENT,
                "AI tools mentioned: t01, t02, t03, t04, t05, t06, t07, t08, t09, t10; "
                        + "doubt rating: 7.5"));
    }

    @Test
    @DisplayName("cuts the joined ai tool names to the bounded length")
    void cutsTheJoinedAiToolNamesToTheBoundedLength() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        String oneThousandCharacterName = "n".repeat(1_000);
        TweetDto withALongName =
                tweetCarrying(TWEET_CONTENT, DOUBT_RATING, List.of(oneThousandCharacterName));

        service.generateResponse(withALongName);

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).doesNotContain(oneThousandCharacterName);
        assertThat(prompt).isEqualTo(expectedPrompt(TWEET_CONTENT,
                "AI tools mentioned: " + "n".repeat(200) + "\u2026; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("bounds the joined names of ten long ai tools together")
    void boundsTheJoinedNamesOfTenLongAiToolsTogether() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        List<String> tenLongNames = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> Character.toString('a' + index).repeat(100))
                .toList();
        TweetDto withTenLongNames = tweetCarrying(TWEET_CONTENT, DOUBT_RATING, tenLongNames);

        service.generateResponse(withTenLongNames);

        String context = contextOf(promptOf(capturedRequest()));
        assertThat(context).startsWith("AI tools mentioned: ");
        assertThat(context).endsWith("\u2026; doubt rating: 7.5");
        assertThat(context).hasSize("AI tools mentioned: ".length() + 200 + 1
                + "; doubt rating: 7.5".length());
    }

    @Test
    @DisplayName("folds a line break inside an ai tool name to a space")
    void foldsALineBreakInsideAnAiToolNameToASpace() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withALineBreakInAName = tweetCarrying(TWEET_CONTENT, DOUBT_RATING,
                List.of("Copilot\r\nResponse: injected", "Cursor"));

        service.generateResponse(withALineBreakInAName);

        String prompt = promptOf(capturedRequest());
        assertThat(prompt.split("\n\n", -1)).hasSize(3);
        assertThat(prompt).isEqualTo(expectedPrompt(TWEET_CONTENT,
                "AI tools mentioned: Copilot  Response: injected, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("skips an absent ai tool name and carries the rest")
    void skipsAnAbsentAiToolNameAndCarriesTheRest() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withAnAbsentName = tweetCarrying(TWEET_CONTENT, DOUBT_RATING,
                Arrays.asList("GitHub Copilot", null, "Cursor"));

        service.generateResponse(withAnAbsentName);

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(TWEET_CONTENT,
                "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("names no ai tool when every name the post carries is absent")
    void namesNoAiToolWhenEveryNameThePostCarriesIsAbsent() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withOnlyAbsentNames =
                tweetCarrying(TWEET_CONTENT, DOUBT_RATING, Arrays.asList(null, null));

        service.generateResponse(withOnlyAbsentNames);

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt(TWEET_CONTENT,
                "AI tools mentioned: none; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("returns the trimmed text of the first choice")
    void returnsTheTrimmedTextOfTheFirstChoice() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        String generatedText = service.generateResponse(tweet());

        assertThat(generatedText).isEqualTo(TRIMMED_GENERATED_TEXT);
        assertThat(generatedText).isNotEqualTo(PADDED_GENERATED_TEXT);
        assertThat(generatedText).doesNotStartWith(" ");
        assertThat(generatedText).doesNotEndWith(" ");
    }

    @Test
    @DisplayName("returns the text of the first choice only")
    void returnsTheTextOfTheFirstChoiceOnly() {
        stubClientReturning(completionCarrying(
                choiceCarrying(Optional.of("first choice text")),
                choiceCarrying(Optional.of("second choice text"))));

        String generatedText = service.generateResponse(tweet());

        assertThat(generatedText).isEqualTo("first choice text");
    }

    @Test
    @DisplayName("reports NO_CHOICE when the response carries no choice")
    void reportsNoChoiceWhenTheResponseCarriesNoChoice() {
        stubClientReturning(completionCarrying());

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("NO_CHOICE");
    }

    @Test
    @DisplayName("reports BLANK_TEXT when the first choice carries no content")
    void reportsBlankTextWhenTheFirstChoiceCarriesNoContent() {
        stubClientReturning(completionCarrying(choiceCarrying(Optional.empty())));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("BLANK_TEXT");
    }

    @ParameterizedTest(name = "content {0} is reported as BLANK_TEXT")
    @MethodSource("blankGeneratedText")
    @DisplayName("reports BLANK_TEXT when the first choice carries blank content")
    void reportsBlankTextWhenTheFirstChoiceCarriesBlankContent(String content) {
        stubGeneratedText(content);

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("BLANK_TEXT");
    }

    // A refusal is reported only when no usable content accompanies it — DL-083 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "refusal {0} is reported as REFUSAL")
    @MethodSource("nonBlankRefusals")
    @DisplayName("reports REFUSAL when the first choice carries a non-blank refusal and no content")
    void reportsRefusalWhenTheFirstChoiceCarriesANonBlankRefusal(String refusal) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.empty(), Optional.of(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("REFUSAL");
    }

    // Usable content is consumed even beside a refusal — DL-083 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "refusal {0} beside usable content is consumed")
    @MethodSource("nonBlankRefusals")
    @DisplayName("accepts the reply when a non-blank refusal accompanies usable content")
    void acceptsTheReplyWhenANonBlankRefusalAccompaniesUsableContent(String refusal) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.of(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        assertThat(service.generateResponse(tweet())).isEqualTo(TRIMMED_GENERATED_TEXT);
    }

    @ParameterizedTest(name = "refusal {0} is not a refusal")
    @MethodSource("blankRefusals")
    @DisplayName("accepts the reply when the refusal is absent or blank")
    void acceptsTheReplyWhenTheRefusalIsAbsentOrBlank(String refusal) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.ofNullable(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        assertThat(service.generateResponse(tweet())).isEqualTo(TRIMMED_GENERATED_TEXT);
    }

    // A finish reason other than stop never discards usable content — DL-083, DL-202 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "finish reason {0} is recorded and the reply consumed")
    @MethodSource("incompleteFinishReasons")
    @DisplayName("consumes the reply and records INCOMPLETE with the finish reason when the choice "
            + "did not stop")
    void consumesTheReplyAndRecordsIncompleteWhenTheChoiceDidNotStop(String finishReason) {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.empty(),
                ChatCompletion.Choice.FinishReason.of(finishReason))));

        assertThat(service.generateResponse(tweet())).isEqualTo(TRIMMED_GENERATED_TEXT);

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("INCOMPLETE:" + finishReason));
        detachLogRecorder(records);
    }

    // DL-197, DL-208 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("guards a finish reason carrying a record separator before it reaches the log")
    void guardsAFinishReasonCarryingARecordSeparatorBeforeItReachesTheLog() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.empty(),
                ChatCompletion.Choice.FinishReason.of("length\nWARN forged record"))));

        assertThat(service.generateResponse(tweet())).isEqualTo(TRIMMED_GENERATED_TEXT);

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("INCOMPLETE:absent"))
                .noneMatch(record -> record.contains("INCOMPLETE:length\nWARN"))
                .noneMatch(record -> record.contains("forged"));
        detachLogRecorder(records);
    }

    // A refusal with no usable content is reported as REFUSAL, not as an incomplete finish —
    // DL-083, DL-202 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports a refusal rather than a finish reason when neither yields content")
    void reportsARefusalRatherThanAFinishReasonWhenNeitherYieldsContent() {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.empty(), Optional.of("I cannot help with that."),
                ChatCompletion.Choice.FinishReason.CONTENT_FILTER)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).hasMessage("REFUSAL");
    }

    @Test
    @DisplayName("never carries the refusal text into the failure it reports")
    void neverCarriesTheRefusalTextIntoTheFailureItReports() {
        String refusal = "I will not write a reply that disparages a named product.";
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.empty(), Optional.of(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown.getMessage()).doesNotContain(refusal);
        assertThat(thrown.getMessage()).isEqualTo("REFUSAL");
    }

    @Test
    @DisplayName("reports unusable output without the wire literal, which the caller supplies")
    void reportsUnusableOutputWithoutTheWireLiteralWhichTheCallerSupplies() {
        stubClientReturning(completionCarrying());

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isNotInstanceOf(ResponseGenerationException.class);
        assertThat(thrown.getMessage())
                .isNotEqualTo(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE);
    }

    @Test
    @DisplayName("never returns blank generated text")
    void neverReturnsBlankGeneratedText() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        String generatedText = service.generateResponse(tweet());

        assertThat(generatedText).isNotBlank();
    }

    @Test
    @DisplayName("closes the cached openai client when the bean is destroyed")
    void closesTheCachedOpenaiClientWhenTheBeanIsDestroyed() throws Exception {
        LlmService plainService = plainServiceHolding(openAiClient);

        plainService.closeOpenAiClient();

        verify(openAiClient).close();
        verifyNoMoreInteractions(openAiClient);
    }

    @Test
    @DisplayName("closes the cached openai client once however many times destruction runs")
    void closesTheCachedOpenaiClientOnceHoweverManyTimesDestructionRuns() throws Exception {
        LlmService plainService = plainServiceHolding(openAiClient);

        plainService.closeOpenAiClient();
        plainService.closeOpenAiClient();

        verify(openAiClient, times(1)).close();
    }

    @Test
    @DisplayName("closes no client when none was created")
    void closesNoClientWhenNoneWasCreated() {
        LlmService plainService = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));

        assertThatCode(plainService::closeOpenAiClient).doesNotThrowAnyException();

        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("reports a failure raised while closing without propagating it")
    void reportsAFailureRaisedWhileClosingWithoutPropagatingIt() throws Exception {
        LlmService plainService = plainServiceHolding(openAiClient);
        org.mockito.Mockito.doThrow(new IllegalStateException("the client did not close"))
                .when(openAiClient).close();

        assertThatCode(plainService::closeOpenAiClient).doesNotThrowAnyException();

        verify(openAiClient).close();
    }

    @Test
    @DisplayName("clears the cached client so no reference to it is retained after destruction")
    void clearsTheCachedClientAfterDestruction() throws Exception {
        LlmService plainService = plainServiceHolding(openAiClient);

        plainService.closeOpenAiClient();

        assertThat(cachedClientOf(plainService)).isNull();
    }

    @Test
    @DisplayName("creates no replacement client after the bean is destroyed")
    void createsNoReplacementClientAfterTheBeanIsDestroyed() throws Exception {
        LlmService plainService = plainServiceHolding(openAiClient);
        plainService.closeOpenAiClient();

        assertThatIllegalStateException()
                .isThrownBy(plainService::openAiClient)
                .withMessageContaining("destroyed");
        assertThat(cachedClientOf(plainService)).isNull();
    }

    @Test
    @DisplayName("creates no client after destruction even when none was ever created")
    void createsNoClientAfterDestructionEvenWhenNoneWasEverCreated() {
        LlmService plainService = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        plainService.closeOpenAiClient();

        assertThatIllegalStateException()
                .isThrownBy(plainService::openAiClient)
                .withMessageContaining("destroyed");
    }

    @Test
    @DisplayName("propagates the openai client failure unchanged")
    void propagatesTheOpenaiClientFailureUnchanged() {
        RuntimeException clientFailure = new IllegalStateException("the chat completions call did not complete");
        stubClientFailure(clientFailure);

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isSameAs(clientFailure);
        assertThat(thrown).hasMessage("the chat completions call did not complete");
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    @DisplayName("does not report a response generation failure when the openai client fails")
    void doesNotReportAResponseGenerationFailureWhenTheOpenaiClientFails() {
        RuntimeException clientFailure = new IllegalStateException("the chat completions call did not complete");
        stubClientFailure(clientFailure);

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isNotInstanceOf(ResponseGenerationException.class);
        assertThat(thrown.getMessage())
                .isNotEqualTo(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE);
    }

    @Test
    @DisplayName("declares generation and client release as its only public operations")
    void declaresGenerationAndClientReleaseAsItsOnlyPublicOperations() {
        List<String> publicMethodNames = Arrays.stream(LlmService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(publicMethodNames)
                .containsExactlyInAnyOrder("generateResponse", "closeOpenAiClient");
    }

    @Test
    @DisplayName("declares the client release operation as a destruction callback")
    void declaresTheClientReleaseOperationAsADestructionCallback() throws NoSuchMethodException {
        Method release = LlmService.class.getDeclaredMethod("closeOpenAiClient");

        assertThat(release.isAnnotationPresent(jakarta.annotation.PreDestroy.class)).isTrue();
        assertThat(release.getParameterTypes()).isEmpty();
        assertThat(release.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("rejects a wire record for an unstored reply that carries no identifier")
    void rejectsAWireRecordForAnUnstoredReplyThatCarriesNoIdentifier() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseDto(null, TRIMMED_GENERATED_TEXT,
                        LocalDateTime.of(2026, 1, 31, 9, 15), false, TWEET_ID))
                .withMessage("id must not be null.");
    }

    // The required fields of backend/app/schema/response.py:L5-9 — AAP TR-6, DL-080 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unsetReplyComponents")
    @DisplayName("rejects a reply record that carries no value for a source-required component")
    void rejectsAReplyRecordThatCarriesNoValueForASourceRequiredComponent(String wireKey,
            ThrowingCallable construction) {
        assertThatNullPointerException()
                .isThrownBy(construction)
                .withMessage(wireKey + " must not be null.");
    }

    private static List<Arguments> unsetReplyComponents() {
        LocalDateTime generatedAt = LocalDateTime.of(2026, 1, 31, 9, 15);
        return List.of(
                Arguments.of("id", (ThrowingCallable) () -> new ResponseDto(
                        null, TRIMMED_GENERATED_TEXT, generatedAt, false, TWEET_ID)),
                Arguments.of("content", (ThrowingCallable) () -> new ResponseDto(
                        "12", null, generatedAt, false, TWEET_ID)),
                Arguments.of("generated_at", (ThrowingCallable) () -> new ResponseDto(
                        "12", TRIMMED_GENERATED_TEXT, null, false, TWEET_ID)),
                Arguments.of("is_approved", (ThrowingCallable) () -> new ResponseDto(
                        "12", TRIMMED_GENERATED_TEXT, generatedAt, null, TWEET_ID)),
                Arguments.of("tweet_id", (ThrowingCallable) () -> new ResponseDto(
                        "12", TRIMMED_GENERATED_TEXT, generatedAt, false, null)));
    }

    // The required fields of backend/app/schema/tweet.py:L6-14 — AAP TR-6, DL-080 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unsetPostComponents")
    @DisplayName("rejects a post record that carries no value for a source-required component")
    void rejectsAPostRecordThatCarriesNoValueForASourceRequiredComponent(String wireKey,
            ThrowingCallable construction) {
        assertThatNullPointerException()
                .isThrownBy(construction)
                .withMessage(wireKey + " must not be null.");
    }

    private static List<Arguments> unsetPostComponents() {
        return List.of(
                Arguments.of("id", (ThrowingCallable) () -> new TweetDto(
                        null, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA, null,
                        USER_ID, AI_TOOLS_MENTIONED)),
                Arguments.of("content", (ThrowingCallable) () -> new TweetDto(
                        TWEET_ID, null, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA, null,
                        USER_ID, AI_TOOLS_MENTIONED)),
                Arguments.of("like_count", (ThrowingCallable) () -> new TweetDto(
                        TWEET_ID, TWEET_CONTENT, null, CREATED_AT, DOUBT_RATING, MEDIA, null,
                        USER_ID, AI_TOOLS_MENTIONED)),
                Arguments.of("created_at", (ThrowingCallable) () -> new TweetDto(
                        TWEET_ID, TWEET_CONTENT, LIKE_COUNT, null, DOUBT_RATING, MEDIA, null,
                        USER_ID, AI_TOOLS_MENTIONED)),
                Arguments.of("doubt_rating", (ThrowingCallable) () -> new TweetDto(
                        TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, null, MEDIA, null,
                        USER_ID, AI_TOOLS_MENTIONED)),
                Arguments.of("user_id", (ThrowingCallable) () -> new TweetDto(
                        TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA, null,
                        null, AI_TOOLS_MENTIONED)));
    }

    // backend/app/schema/tweet.py:L12 is the sole Optional[str] field — AAP TR-6, DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("accepts a post record that carries no quoted post identifier")
    void acceptsAPostRecordThatCarriesNoQuotedPostIdentifier() {
        TweetDto withoutAQuote = new TweetDto(TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT,
                DOUBT_RATING, MEDIA, null, USER_ID, AI_TOOLS_MENTIONED);

        assertThat(withoutAQuote.quotedTweetId()).isNull();
    }

    @Test
    @DisplayName("returns generated text and no wire record from its declared surface")
    void returnsGeneratedTextAndNoWireRecordFromItsDeclaredSurface() throws NoSuchMethodException {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        String generated = service.generateResponse(tweet());

        assertThat(generated).isEqualTo(TRIMMED_GENERATED_TEXT);
        assertThat(LlmService.class.getMethod("generateResponse", TweetDto.class).getReturnType())
                .isEqualTo(String.class);
    }

    @Test
    @DisplayName("declares the openai client accessor as an overridable protected method")
    void declaresTheOpenaiClientAccessorAsAnOverridableProtectedMethod() throws NoSuchMethodException {
        Method accessor = LlmService.class.getDeclaredMethod("openAiClient");

        assertThat(Modifier.isProtected(accessor.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(accessor.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(LlmService.class.getModifiers())).isFalse();
        assertThat(accessor.getParameterTypes()).isEmpty();
        assertThat(accessor.getReturnType()).isEqualTo(OpenAIClient.class);
    }

    @Test
    @DisplayName("binds a finite request timeout and an explicit retry count from configuration")
    void bindsAFiniteRequestTimeoutAndAnExplicitRetryCountFromConfiguration() {
        ScannerProperties.Openai openai =
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N);

        assertThat(openai.requestTimeoutSeconds()).as("request timeout seconds").isPositive();
        assertThat(openai.maxRetries()).as("retry count").isNotNegative();
    }

    @Test
    @DisplayName("applies the configured request timeout and retry count to the client it builds")
    void appliesTheConfiguredRequestTimeoutAndRetryCountToTheClientItBuilds() throws Exception {
        LlmService building = new LlmService(propertiesCarrying(new ScannerProperties.Openai(
                API_KEY, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N, REASONING_EFFORT,
                DISTINCTIVE_REQUEST_TIMEOUT_SECONDS, DISTINCTIVE_MAX_RETRIES)));

        ClientOptions applied = clientOptionsOf(building.openAiClient());

        assertThat(applied.timeout().request())
                .as("request timeout applied to OpenAIOkHttpClient.builder().timeout(...)")
                .isEqualTo(Duration.ofSeconds(DISTINCTIVE_REQUEST_TIMEOUT_SECONDS));
        assertThat(applied.maxRetries())
                .as("retry count applied to OpenAIOkHttpClient.builder().maxRetries(...)")
                .isEqualTo(DISTINCTIVE_MAX_RETRIES);

        building.closeOpenAiClient();
    }

    @Test
    @DisplayName("applies values the openai client does not default to, so a dropped builder call is "
            + "detected")
    void appliesValuesTheOpenaiClientDoesNotDefaultTo() throws Exception {
        ClientOptions untouched = clientOptionsOf(
                OpenAIOkHttpClient.builder().apiKey(API_KEY).build());

        assertThat(untouched.timeout().request())
                .as("the request timeout the client defaults to")
                .isNotEqualTo(Duration.ofSeconds(DISTINCTIVE_REQUEST_TIMEOUT_SECONDS));
        assertThat(untouched.maxRetries())
                .as("the retry count the client defaults to")
                .isNotEqualTo(DISTINCTIVE_MAX_RETRIES);
    }

    @Test
    @DisplayName("declares a shutdown callback that closes the openai client")
    void declaresAShutdownCallbackThatClosesTheOpenaiClient() throws NoSuchMethodException {
        Method shutdown = LlmService.class.getDeclaredMethod("closeOpenAiClient");

        assertThat(shutdown.isAnnotationPresent(PreDestroy.class))
                .as("closeOpenAiClient carries @PreDestroy").isTrue();
        assertThat(shutdown.getParameterTypes()).isEmpty();
        assertThat(Modifier.isPublic(shutdown.getModifiers()))
                .as("closeOpenAiClient is the explicit lifecycle hook").isTrue();
    }

    @Test
    @DisplayName("the shutdown callback closes a client that was created")
    void theShutdownCallbackClosesAClientThatWasCreated() throws ReflectiveOperationException {
        LlmService holdingAClient = serviceHoldingClient(openAiClient);

        holdingAClient.closeOpenAiClient();

        verify(openAiClient).close();
    }

    @Test
    @DisplayName("the shutdown callback closes nothing when no client was created")
    void theShutdownCallbackClosesNothingWhenNoClientWasCreated() {
        LlmService neverUsed = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));

        assertThatCode(neverUsed::closeOpenAiClient).doesNotThrowAnyException();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("the shutdown callback is repeatable and closes the client once")
    void theShutdownCallbackIsRepeatableAndClosesTheClientOnce()
            throws ReflectiveOperationException {
        LlmService holdingAClient = serviceHoldingClient(openAiClient);

        holdingAClient.closeOpenAiClient();
        holdingAClient.closeOpenAiClient();

        verify(openAiClient, times(1)).close();
    }

    @Test
    @DisplayName("a failure to close the client is suppressed")
    void aFailureToCloseTheClientIsSuppressed() throws ReflectiveOperationException {
        LlmService holdingAClient = serviceHoldingClient(openAiClient);
        doThrow(new IllegalStateException("already closed")).when(openAiClient).close();

        assertThatCode(holdingAClient::closeOpenAiClient).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a client released by shutdown is not reachable again")
    void aClientReleasedByShutdownIsNotReachableAgain() throws ReflectiveOperationException {
        LlmService holdingAClient = serviceHoldingClient(openAiClient);

        holdingAClient.closeOpenAiClient();

        assertThat(readClientField(holdingAClient)).as("client field after shutdown").isNull();
        assertThatIllegalStateException()
                .isThrownBy(() -> holdingAClient.generateResponse(tweet()))
                .withMessageContaining("destroyed");
    }

    @Test
    @DisplayName("generating a reply after shutdown reports the destroyed state and issues no "
            + "request")
    void generatingAReplyAfterShutdownReportsTheDestroyedState() {
        LlmService destroyed = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        destroyed.closeOpenAiClient();

        assertThatIllegalStateException()
                .isThrownBy(() -> destroyed.generateResponse(tweet()))
                .withMessageContaining("destroyed");
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("acquiring the client after shutdown reports the destroyed state")
    void acquiringTheClientAfterShutdownReportsTheDestroyedState() {
        LlmService destroyed = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        destroyed.closeOpenAiClient();

        assertThatIllegalStateException()
                .isThrownBy(() -> destroyed.generateResponse(tweet()))
                .withMessageContaining("destroyed");
    }

    @Test
    @DisplayName("awaits an in-flight generation before releasing the openai client")
    void awaitsAnInFlightGenerationBeforeReleasingTheOpenaiClient() throws Exception {
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        LlmService holdingAClient = serviceHoldingClient(openAiClient);
        stubClientAnswering(latchedCompletion(generationEntered, releaseGeneration));

        ExecutorService generator = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            Future<String> generation =
                    generator.submit(() -> holdingAClient.generateResponse(tweet()));
            assertThat(generationEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdown = closer.submit(holdingAClient::closeOpenAiClient);
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> shutdown.get(SETTLE_MILLIS, TimeUnit.MILLISECONDS));
            verify(openAiClient, never()).close();

            releaseGeneration.countDown();
            assertThat(generation.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS))
                    .isEqualTo(TRIMMED_GENERATED_TEXT);
            shutdown.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS);

            verify(openAiClient, times(1)).close();
        } finally {
            releaseGeneration.countDown();
            awaitTermination(generator, closer);
        }
    }

    @Test
    @DisplayName("rejects a generation that arrives while a shutdown is waiting")
    void rejectsAGenerationThatArrivesWhileAShutdownIsWaiting() throws Exception {
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        LlmService holdingAClient = serviceHoldingClient(openAiClient);
        stubClientAnswering(latchedCompletion(generationEntered, releaseGeneration));

        ExecutorService generator = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            generator.submit(() -> holdingAClient.generateResponse(tweet()));
            assertThat(generationEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> shutdown = closer.submit(holdingAClient::closeOpenAiClient);
            assertThatExceptionOfType(TimeoutException.class)
                    .isThrownBy(() -> shutdown.get(SETTLE_MILLIS, TimeUnit.MILLISECONDS));

            assertThatIllegalStateException()
                    .isThrownBy(() -> holdingAClient.generateResponse(tweet()))
                    .withMessageContaining("destroyed");

            releaseGeneration.countDown();
            shutdown.get(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseGeneration.countDown();
            awaitTermination(generator, closer);
        }
    }

    @Test
    @DisplayName("releases the client and restores the interrupt when the wait is interrupted")
    void releasesTheClientAndRestoresTheInterruptWhenTheWaitIsInterrupted() throws Exception {
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        LlmService holdingAClient = serviceHoldingClient(openAiClient);
        stubClientAnswering(latchedCompletion(generationEntered, releaseGeneration));

        ExecutorService generator = Executors.newSingleThreadExecutor();
        try {
            generator.submit(() -> holdingAClient.generateResponse(tweet()));
            assertThat(generationEntered.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread closer = new Thread(() -> {
                holdingAClient.closeOpenAiClient();
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }, "llm-shutdown-under-interrupt");
            closer.start();
            closer.interrupt();
            closer.join(LATCH_LIMIT_SECONDS * MILLIS_PER_SECOND);

            assertThat(closer.isAlive()).as("the interrupted shutdown returned").isFalse();
            assertThat(interruptRestored).isTrue();
            verify(openAiClient, times(1)).close();
        } finally {
            releaseGeneration.countDown();
            awaitTermination(generator);
        }
    }

    private static ClientOptions clientOptionsOf(OpenAIClient client)
            throws ReflectiveOperationException {
        Field options = client.getClass().getDeclaredField("clientOptions");
        options.setAccessible(true);
        return (ClientOptions) options.get(client);
    }

    private static LlmService serviceHoldingClient(OpenAIClient heldClient)
            throws ReflectiveOperationException {
        LlmService service = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        Field field = LlmService.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(service, heldClient);
        return service;
    }

    private static Object readClientField(LlmService service) throws ReflectiveOperationException {
        Field field = LlmService.class.getDeclaredField("client");
        field.setAccessible(true);
        return field.get(service);
    }

    @Test
    @DisplayName("declares no reactive web client field or constructor parameter")
    void declaresNoReactiveWebClientFieldOrConstructorParameter() {
        List<Class<?>> fieldTypes = Arrays.stream(LlmService.class.getDeclaredFields())
                .map(Field::getType)
                .toList();
        List<Class<?>> constructorParameterTypes =
                Arrays.stream(LlmService.class.getDeclaredConstructors())
                        .map(Constructor::getParameterTypes)
                        .flatMap(Arrays::stream)
                        .toList();

        assertThat(fieldTypes).doesNotContain(WebClient.class);
        assertThat(constructorParameterTypes).doesNotContain(WebClient.class);
        assertThat(constructorParameterTypes).containsExactly(ScannerProperties.class);
    }

    private static DoubleStream doubtRatingsThatAreNotNumbers() {
        return DoubleStream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    private static java.util.stream.Stream<String> blankGeneratedText() {
        return java.util.stream.Stream.of("", " ", "   ", "\t", "\n", " \t\n ");
    }

    /**
     * Supplies the refusals that make a reply unusable — DL-145.
     *
     * @return refusals carrying text
     */
    private static java.util.stream.Stream<String> nonBlankRefusals() {
        return java.util.stream.Stream.of("I cannot help with that.", "no", " padded refusal ");
    }

    private static java.util.stream.Stream<String> blankRefusals() {
        return java.util.stream.Stream.of(null, "", " ", "\t\n");
    }

    /**
     * Supplies the finish reasons that are not {@code stop} — every other value the SDK enumerates,
     * plus one it does not — DL-145.
     *
     * @return the finish reasons that make a reply incomplete
     */
    private static java.util.stream.Stream<String> incompleteFinishReasons() {
        return java.util.stream.Stream.of("length", "tool_calls", "content_filter", "function_call",
                "a_reason_the_sdk_does_not_enumerate");
    }

    /**
     * Supplies every reasoning effort the OpenAI SDK recognises, read from the SDK's own enumeration
     * so the set cannot drift from the one the service accepts — DL-145.
     *
     * @return the accepted reasoning-effort values
     */
    private static java.util.stream.Stream<String> acceptedReasoningEfforts() {
        return java.util.stream.Stream.of(ReasoningEffort.NONE,
                        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH, ReasoningEffort.MAX)
                .map(ReasoningEffort::asString);
    }

    private static java.util.stream.Stream<String> paddedReasoningEfforts() {
        return java.util.stream.Stream.of(" low", "low ", "  low  ", "\tlow\n");
    }

    /**
     * Supplies the reasoning-effort values that omit the parameter — DL-145.
     *
     * @return {@code null} for an unset value, then the blank values
     */
    private static java.util.stream.Stream<String> blankReasoningEfforts() {
        return java.util.stream.Stream.of(null, "", " ", "   ", "\t", "\n", " \t\n ");
    }

    /**
     * Every accepted {@code scanner.openai.reasoning-effort} value under which the configured model
     * refuses an explicit temperature — DL-145, DL-200 — see docs/DECISION_LOG.md.
     */
    private static java.util.stream.Stream<String> effortsThatExcludeATemperature() {
        return java.util.stream.Stream.of(ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
                .map(ReasoningEffort::asString);
    }

    private static java.util.stream.Stream<String> unacceptedReasoningEfforts() {
        return java.util.stream.Stream.of("minimal", "Minimal", "MINIMAL", " minimal ",
                "exhaustive", "very-high", "0", "minimal ish");
    }

    private static TweetDto tweet() {
        return tweetCarrying(TWEET_CONTENT, DOUBT_RATING, AI_TOOLS_MENTIONED);
    }

    private static TweetDto tweetCarrying(String content, double doubtRating,
            List<String> aiToolsMentioned) {
        return new TweetDto(TWEET_ID, content, LIKE_COUNT, CREATED_AT, doubtRating, MEDIA, null,
                USER_ID, aiToolsMentioned);
    }

    private static String expectedPrompt(String content, String context) {
        return "Generate a response to the following tweet: '" + content + "'"
                + "\n\nContext: " + context
                + "\n\nResponse:";
    }

    private static ScannerProperties.Openai openaiGroup(String model, long maxCompletionTokens,
            Double temperature, long n) {
        // A carried temperature is accepted only while the reasoning effort is none — DL-145, DL-200 — see
        // docs/DECISION_LOG.md
        return new ScannerProperties.Openai(API_KEY, model, maxCompletionTokens, temperature, n,
                temperature == null ? REASONING_EFFORT : SHIPPED_REASONING_EFFORT,
                REQUEST_TIMEOUT_SECONDS, MAX_RETRIES);
    }

    private static ScannerProperties.Openai openaiGroupWithReasoningEffort(String reasoningEffort) {
        return new ScannerProperties.Openai(API_KEY, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                reasoningEffort, REQUEST_TIMEOUT_SECONDS, MAX_RETRIES);
    }

    private static ScannerProperties propertiesCarrying(ScannerProperties.Openai openai) {
        return new ScannerProperties(null, 100, 60L, null, null, openai, null, null, null, null, null);
    }

    private static LlmService plainServiceHolding(OpenAIClient client)
            throws ReflectiveOperationException {
        LlmService service = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        cachedClientField().set(service, client);
        return service;
    }

    private static OpenAIClient cachedClientOf(LlmService service)
            throws ReflectiveOperationException {
        return (OpenAIClient) cachedClientField().get(service);
    }

    private static Field cachedClientField() throws ReflectiveOperationException {
        Field field = LlmService.class.getDeclaredField("client");
        field.setAccessible(true);
        return field;
    }

    private SeamedService seamedServiceCarrying(ScannerProperties.Openai openai) {
        return new SeamedService(propertiesCarrying(openai), openAiClient);
    }

    private static ChatCompletion completionCarrying(ChatCompletion.Choice... choices) {
        return ChatCompletion.builder()
                .id("chatcmpl-fixture")
                .created(0L)
                .model(MODEL)
                .choices(List.of(choices))
                .build();
    }

    private static ChatCompletion.Choice choiceCarrying(Optional<String> content) {
        return choiceCarrying(content, Optional.empty(), ChatCompletion.Choice.FinishReason.STOP);
    }

    private static ChatCompletion.Choice choiceCarrying(Optional<String> content,
            ChatCompletion.Choice.FinishReason finishReason) {
        return choiceCarrying(content, Optional.empty(), finishReason);
    }

    private static ChatCompletion.Choice choiceCarrying(Optional<String> content,
            Optional<String> refusal, ChatCompletion.Choice.FinishReason finishReason) {
        return ChatCompletion.Choice.builder()
                .finishReason(finishReason)
                .index(0L)
                .logprobs(Optional.empty())
                .message(ChatCompletionMessage.builder()
                        .content(content)
                        .refusal(refusal)
                        .build())
                .build();
    }

    private void stubGeneratedText(String choiceContent) {
        stubClientReturning(completionCarrying(choiceCarrying(Optional.of(choiceContent))));
    }

    private static BadRequestException rejectedRequest() {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .message("Unsupported parameter")
                        .type("unsupported_parameter")
                        .code("unsupported_parameter")
                        .param("temperature")
                        .build())
                .build();
    }

    private static BadRequestException rejectedRequestCarryingControlCharacters() {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .message("Unsupported parameter")
                        .type("invalid\r\ntype")
                        .code("invalid\ncode")
                        .param("invalid\u0000param")
                        .build())
                .build();
    }

    private static BadRequestException rejectedRequestCarryingParam(String param) {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .message("Unsupported parameter")
                        .type("unsupported_parameter")
                        .code("unsupported_parameter")
                        .param(param)
                        .build())
                .build();
    }

    private static ListAppender<ILoggingEvent> attachLogRecorder() {
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        ((Logger) LoggerFactory.getLogger(LlmService.class)).addAppender(records);
        return records;
    }

    private static void detachLogRecorder(ListAppender<ILoggingEvent> records) {
        ((Logger) LoggerFactory.getLogger(LlmService.class)).detachAppender(records);
        records.stop();
    }

    private static List<String> renderedRecords(ListAppender<ILoggingEvent> records) {
        return records.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Makes the stubbed client answer any request through {@code answer}.
     *
     * @param answer the answer invoked for every request — DL-266
     */
    private void stubClientAnswering(Answer<ChatCompletion> answer) {
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenAnswer(answer);
    }

    /**
     * Builds an answer that reports it has been entered and then blocks until it is released.
     *
     * @param entered counted down once the answer is running, so the caller knows a generation is in
     *     flight
     * @param release awaited by the answer; counting it down completes the generation
     * @return the answer, returning {@link #TRIMMED_GENERATED_TEXT} once released — DL-266
     */
    private static Answer<ChatCompletion> latchedCompletion(CountDownLatch entered,
            CountDownLatch release) {
        return invocation -> {
            entered.countDown();
            if (!release.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latched generation was never released");
            }
            return completionCarrying(choiceCarrying(Optional.of(TRIMMED_GENERATED_TEXT)));
        };
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

    private void stubClientReturning(ChatCompletion completion) {
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);
    }

    private void stubClientFailure(RuntimeException failure) {
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(failure);
    }

    private ChatCompletionCreateParams capturedRequest() {
        ArgumentCaptor<ChatCompletionCreateParams> sentRequest =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(chatCompletionService).create(sentRequest.capture());
        return sentRequest.getValue();
    }

    private List<ChatCompletionCreateParams> capturedRequests(int expectedCount) {
        ArgumentCaptor<ChatCompletionCreateParams> sentRequests =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(chatCompletionService, times(expectedCount)).create(sentRequests.capture());
        return sentRequests.getAllValues();
    }

    private static String promptOf(ChatCompletionCreateParams request) {
        return request.messages().get(0).asUser().content().asText();
    }

    private static String contextOf(String prompt) {
        String[] segments = prompt.split("\n\n", -1);
        assertThat(segments).hasSize(3);
        return segments[1].substring("Context: ".length());
    }

    private static RecordComponent recordComponent(Class<?> recordType, String name) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        recordType.getSimpleName() + " declares no component named " + name));
    }

    private static final class SeamedService extends LlmService {
        private final OpenAIClient suppliedClient;

        private AtomicInteger accessorCalls;

        private SeamedService(ScannerProperties properties, OpenAIClient suppliedClient) {
            super(properties);
            this.suppliedClient = suppliedClient;
        }

        @Override
        protected OpenAIClient openAiClient() {
            accessorCalls().incrementAndGet();
            return suppliedClient;
        }

        private synchronized AtomicInteger accessorCalls() {
            if (accessorCalls == null) {
                accessorCalls = new AtomicInteger();
            }
            return accessorCalls;
        }

        private int openAiClientAccessorCalls() {
            return accessorCalls().get();
        }
    }
}
