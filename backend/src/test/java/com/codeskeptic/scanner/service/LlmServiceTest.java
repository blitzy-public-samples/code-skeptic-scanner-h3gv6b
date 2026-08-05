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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    /** Tolerance applied to every floating-point comparison in this class. */
    private static final double TOLERANCE = 1e-9d;

    // -------------------------------------------------------------------------
    // scanner.openai values carried by the properties under test
    // -------------------------------------------------------------------------

    /** Value bound to {@code scanner.openai.api-key}. */
    private static final String API_KEY = "not-a-real-openai-credential";

    /** Value bound to {@code scanner.openai.model}. */
    private static final String MODEL = "gpt-5.6-terra";

    /** Value bound to {@code scanner.openai.max-completion-tokens}. */
    private static final long MAX_COMPLETION_TOKENS = 150L;

    /**
     * Value bound to {@code scanner.openai.temperature} by the default fixture: the key is unset, so
     * no temperature reaches the request.
     */
    private static final Double TEMPERATURE = null;

    /** Value bound to {@code scanner.openai.n}. */
    private static final long N = 1L;

    /** The {@code scanner.openai.reasoning-effort} value the fixtures bind. */
    private static final String REASONING_EFFORT = "low";

    /** A {@code scanner.openai.reasoning-effort} value the OpenAI client does not recognise. */
    private static final String UNACCEPTED_REASONING_EFFORT = "exhaustive";

    /** The {@code scanner.openai.request-timeout-seconds} value the fixtures bind. */
    private static final long REQUEST_TIMEOUT_SECONDS = 30L;

    /** The {@code scanner.openai.max-retries} value the fixtures bind. */
    private static final int MAX_RETRIES = 2;

    /** Request timeout, in seconds, that the OpenAI client applies none of on its own. */
    private static final long DISTINCTIVE_REQUEST_TIMEOUT_SECONDS = 17L;

    /** Retry count that differs from the one the OpenAI client defaults to. */
    private static final int DISTINCTIVE_MAX_RETRIES = 5;

    /** Second value bound to {@code scanner.openai.model}. */
    private static final String OTHER_MODEL = "a-different-chat-model-identifier";

    /** Second value bound to {@code scanner.openai.max-completion-tokens}. */
    private static final long OTHER_MAX_COMPLETION_TOKENS = 77L;

    /** Second value bound to {@code scanner.openai.temperature}, supplied explicitly. */
    private static final Double OTHER_TEMPERATURE = 0.11d;

    /** Second value bound to {@code scanner.openai.n}. */
    private static final long OTHER_N = 2L;

    // -------------------------------------------------------------------------
    // Post supplied to generateResponse(TweetDto)
    // -------------------------------------------------------------------------

    /** Identifier of the post the tests reply to. */
    private static final String TWEET_ID = "4711";

    /** Body of the post the tests reply to. */
    private static final String TWEET_CONTENT = "AI coding tools still cannot get this right";

    /** Number of likes recorded on the post the tests reply to. */
    private static final int LIKE_COUNT = 250;

    /** Creation time of the post the tests reply to. */
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 31, 9, 15);

    /** Doubt rating of the post the tests reply to. */
    private static final double DOUBT_RATING = 7.5d;

    /** Media references attached to the post the tests reply to. */
    private static final List<String> MEDIA = List.of("https://pbs.example/media/1.png");

    /** Author of the post the tests reply to. */
    private static final String USER_ID = "user-99";

    /** AI tool names carried by the post the tests reply to. */
    private static final List<String> AI_TOOLS_MENTIONED = List.of("GitHub Copilot", "Cursor");

    // -------------------------------------------------------------------------
    // Prompt and generated text
    // -------------------------------------------------------------------------

    /** Prompt the request is asserted to carry for the post above, spelled out in full. */
    private static final String EXPECTED_PROMPT =
            "Generate a response to the following tweet: 'AI coding tools still cannot get this right'"
                    + "\n\n"
                    + "Context: AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"
                    + "\n\n"
                    + "Response:";

    /** Text the stubbed response carries, padded on both sides. */
    private static final String PADDED_GENERATED_TEXT = "  Even seasoned reviewers disagree.  ";

    /** Text {@link #PADDED_GENERATED_TEXT} yields once trimmed. */
    private static final String TRIMMED_GENERATED_TEXT = "Even seasoned reviewers disagree.";

    /** Stubbed OpenAI client, reached only through the protected accessor. */
    @Mock
    private OpenAIClient openAiClient;

    /** Chat service the stubbed client returns. */
    @Mock
    private ChatService chatService;

    /** Chat Completions service the stubbed chat service returns. */
    @Mock
    private ChatCompletionService chatCompletionService;

    /** Unit under test, holding {@link #openAiClient} behind the accessor. */
    private SeamedService service;

    @BeforeEach
    void createService() {
        service = seamedServiceCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N));
    }

    // -------------------------------------------------------------------------
    // OpenAI client acquisition
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Request routing and the model identifier
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Call parameters
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sends a max completion tokens value of one hundred and fifty")
    void sendsAMaxCompletionTokensValueOfOneHundredAndFifty() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        assertThat(capturedRequest().maxCompletionTokens()).contains(150L);
    }

    @Test
    @DisplayName("records the finish reason when the first choice carries no content")
    void recordsTheFinishReasonWhenTheFirstChoiceCarriesNoContent() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientReturning(completionCarrying(
                choiceCarrying(Optional.empty(), ChatCompletion.Choice.FinishReason.LENGTH)));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessage("INCOMPLETE:length");

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("INCOMPLETE:length"));
        detachLogRecorder(records);
    }

    @Test
    @DisplayName("records the finish reason when the first choice carries blank content")
    void recordsTheFinishReasonWhenTheFirstChoiceCarriesBlankContent() {
        ListAppender<ILoggingEvent> records = attachLogRecorder();
        stubClientReturning(completionCarrying(
                choiceCarrying(Optional.of("   "), ChatCompletion.Choice.FinishReason.LENGTH)));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessage("INCOMPLETE:length");

        assertThat(renderedRecords(records))
                .anyMatch(record -> record.contains("INCOMPLETE:length"));
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

    @Test
    @DisplayName("rejects an n below one")
    void rejectsAnNBelowOne() {
        service = seamedServiceCarrying(openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, 0L));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.n");
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
                .contains("none", "minimal", "low", "medium", "high", "xhigh", "max")
                .doesNotContain(UNACCEPTED_REASONING_EFFORT);

        assertThat(service.openAiClientAccessorCalls()).isZero();
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("sends the second max completion tokens, temperature and n bound to the openai properties")
    void sendsTheSecondMaxCompletionTokensTemperatureAndNBoundToTheOpenaiProperties() {
        service = seamedServiceCarrying(
                openaiGroup(MODEL, OTHER_MAX_COMPLETION_TOKENS, OTHER_TEMPERATURE, OTHER_N));
        stubGeneratedText(PADDED_GENERATED_TEXT);

        service.generateResponse(tweet());

        ChatCompletionCreateParams request = capturedRequest();
        assertThat(request.maxCompletionTokens()).contains(77L);
        assertThat(request.n()).contains(2L);
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

    // -------------------------------------------------------------------------
    // Reasoning effort — DL-145
    // -------------------------------------------------------------------------

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
    @DisplayName("sends every reasoning effort the sdk recognises")
    void sendsEveryReasoningEffortTheSdkRecognises(String reasoningEffort) {
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
    @DisplayName("rejects a reasoning effort the sdk does not recognise, naming the key")
    void rejectsAReasoningEffortTheSdkDoesNotRecogniseNamingTheKey(String reasoningEffort) {
        LlmService misconfigured = seamedServiceCarrying(
                openaiGroupWithReasoningEffort(reasoningEffort));

        assertThatIllegalStateException()
                .isThrownBy(() -> misconfigured.generateResponse(tweet()))
                .withMessageContaining("scanner.openai.reasoning-effort")
                .withMessageContaining("none, minimal, low, medium, high, xhigh, max");
    }

    @Test
    @DisplayName("rejects an unrecognised reasoning effort before it reaches the openai client")
    void rejectsAnUnrecognisedReasoningEffortBeforeItReachesTheOpenaiClient() {
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

    // -------------------------------------------------------------------------
    // Prompt composition
    // -------------------------------------------------------------------------

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

    @Test
    @DisplayName("cuts a five thousand character post body to the bounded length")
    void cutsAFiveThousandCharacterPostBodyToTheBoundedLength() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        String longBody = "a".repeat(5_000);
        TweetDto withALongBody = tweetCarrying(longBody, DOUBT_RATING, AI_TOOLS_MENTIONED);

        assertThatCode(() -> service.generateResponse(withALongBody)).doesNotThrowAnyException();

        String prompt = promptOf(capturedRequest());
        assertThat(prompt).doesNotContain(longBody);
        assertThat(prompt).isEqualTo(expectedPrompt("a".repeat(1_000) + "\u2026",
                "AI tools mentioned: GitHub Copilot, Cursor; doubt rating: 7.5"));
    }

    @Test
    @DisplayName("folds a line break inside the post body to a space")
    void foldsALineBreakInsideThePostBodyToASpace() {
        stubGeneratedText(PADDED_GENERATED_TEXT);
        TweetDto withLineBreaks =
                tweetCarrying("first\r\nsecond", DOUBT_RATING, AI_TOOLS_MENTIONED);

        service.generateResponse(withLineBreaks);

        assertThat(promptOf(capturedRequest())).isEqualTo(expectedPrompt("first  second",
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

    // -------------------------------------------------------------------------
    // Returned generated text
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("returns the trimmed text of the first choice")
    void returnsTheTrimmedTextOfTheFirstChoice() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        String generatedText = service.generateResponse(tweet()).content();

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

        String generatedText = service.generateResponse(tweet()).content();

        assertThat(generatedText).isEqualTo("first choice text");
    }

    // -------------------------------------------------------------------------
    // Unusable model output is a generation failure, reported under one of four fixed codes —
    // DL-083 and DL-145
    // -------------------------------------------------------------------------

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

    @ParameterizedTest(name = "refusal {0} is reported as REFUSAL")
    @MethodSource("nonBlankRefusals")
    @DisplayName("reports REFUSAL when the first choice carries a non-blank refusal")
    void reportsRefusalWhenTheFirstChoiceCarriesANonBlankRefusal(String refusal) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.of(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("REFUSAL");
    }

    @ParameterizedTest(name = "refusal {0} is not a refusal")
    @MethodSource("blankRefusals")
    @DisplayName("accepts the reply when the refusal is absent or blank")
    void acceptsTheReplyWhenTheRefusalIsAbsentOrBlank(String refusal) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.ofNullable(refusal),
                ChatCompletion.Choice.FinishReason.STOP)));

        assertThat(service.generateResponse(tweet()).content()).isEqualTo(TRIMMED_GENERATED_TEXT);
    }

    @ParameterizedTest(name = "finish reason {0} is reported as INCOMPLETE")
    @MethodSource("incompleteFinishReasons")
    @DisplayName("reports INCOMPLETE with the finish reason when the choice did not stop")
    void reportsIncompleteWithTheFinishReasonWhenTheChoiceDidNotStop(String finishReason) {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.empty(),
                ChatCompletion.Choice.FinishReason.of(finishReason))));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("INCOMPLETE:" + finishReason);
    }

    @Test
    @DisplayName("guards a finish reason carrying a record separator before it reaches the code")
    void guardsAFinishReasonCarryingARecordSeparatorBeforeItReachesTheCode() {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.of(TRIMMED_GENERATED_TEXT), Optional.empty(),
                ChatCompletion.Choice.FinishReason.of("length\nWARN forged record"))));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessage("INCOMPLETE:length?WARN forged record");
        assertThat(thrown.getMessage()).doesNotContain("\n");
    }

    @Test
    @DisplayName("reports a refusal before it reports an incomplete finish reason")
    void reportsARefusalBeforeItReportsAnIncompleteFinishReason() {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.empty(), Optional.of("I cannot help with that."),
                ChatCompletion.Choice.FinishReason.CONTENT_FILTER)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).hasMessage("REFUSAL");
    }

    @Test
    @DisplayName("reports an incomplete finish reason before it reports blank text")
    void reportsAnIncompleteFinishReasonBeforeItReportsBlankText() {
        stubClientReturning(completionCarrying(choiceCarrying(
                Optional.empty(), Optional.empty(), ChatCompletion.Choice.FinishReason.LENGTH)));

        Throwable thrown = catchThrowable(() -> service.generateResponse(tweet()));

        assertThat(thrown).hasMessage("INCOMPLETE:length");
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

        String generatedText = service.generateResponse(tweet()).content();

        assertThat(generatedText).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Client lifecycle — DL-085
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Failure propagation
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Declared surface
    // -------------------------------------------------------------------------

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
    @DisplayName("accepts a wire record for an unstored reply that carries no identifier")
    void acceptsAWireRecordForAnUnstoredReplyThatCarriesNoIdentifier() {
        ResponseDto unstored = new ResponseDto(null, TRIMMED_GENERATED_TEXT,
                LocalDateTime.of(2026, 1, 31, 9, 15), false, TWEET_ID);

        assertThat(unstored.id()).isNull();
        assertThat(unstored.content()).isEqualTo(TRIMMED_GENERATED_TEXT);
        assertThat(unstored.tweetId()).isEqualTo(TWEET_ID);
    }

    @Test
    @DisplayName("returns no wire record and reaches no dto type from its declared surface")
    void returnsAPopulatedWireRecordFromItsDeclaredSurface() {
        stubGeneratedText(PADDED_GENERATED_TEXT);

        ResponseDto generated = service.generateResponse(tweet());

        assertThat(generated.id()).isNull();
        assertThat(generated.content()).isEqualTo(TRIMMED_GENERATED_TEXT);
        assertThat(generated.tweetId()).isEqualTo(TWEET_ID);
        assertThat(generated.isApproved()).isFalse();
        assertThat(generated.generatedAt()).isNotNull();
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

    // -------------------------------------------------------------------------
    // Call budget and client lifecycle — DL-085, DL-146 — see docs/DECISION_LOG.md
    // -------------------------------------------------------------------------

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

    /**
     * Reads the transport options an {@link OpenAIClient} was built with.
     *
     * @param client the client under inspection
     * @return the options the builder applied
     * @throws ReflectiveOperationException when the options cannot be read
     */
    private static ClientOptions clientOptionsOf(OpenAIClient client)
            throws ReflectiveOperationException {

        Field options = client.getClass().getDeclaredField("clientOptions");
        options.setAccessible(true);
        return (ClientOptions) options.get(client);
    }

    /**
     * Builds a service already holding {@code heldClient} in the field the shutdown callback
     * releases, standing in for a service whose accessor has created one.
     *
     * @param heldClient the client the service holds
     * @return the service holding that client
     * @throws ReflectiveOperationException when the field cannot be written
     */
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

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * Supplies the doubt ratings a {@code double} can carry that are not numbers.
     *
     * @return the not-a-number and the two infinite doubt ratings
     */
    private static DoubleStream doubtRatingsThatAreNotNumbers() {
        return DoubleStream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    /**
     * Supplies the model outputs that carry no usable text.
     *
     * @return the empty, whitespace-only, tab and newline outputs
     */
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

    /**
     * Supplies the refusal values that leave a reply usable — absent, empty and whitespace-only.
     *
     * @return {@code null} for an absent refusal, then the blank refusals
     */
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
        return java.util.stream.Stream.of(ReasoningEffort.NONE, ReasoningEffort.MINIMAL,
                        ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH, ReasoningEffort.MAX)
                .map(ReasoningEffort::asString);
    }

    /**
     * Supplies accepted reasoning efforts carrying surrounding whitespace.
     *
     * @return {@code low} surrounded by whitespace
     */
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
     * Supplies reasoning-effort values the OpenAI SDK does not recognise, including one that differs
     * from an accepted value by case only.
     *
     * @return the rejected reasoning-effort values
     */
    private static java.util.stream.Stream<String> unacceptedReasoningEfforts() {
        return java.util.stream.Stream.of("Minimal", "MINIMAL", "exhaustive", "very-high", "0",
                "minimal ish");
    }

    /**
     * Builds the post every test replies to unless it supplies its own.
     *
     * @return a post carrying {@link #TWEET_CONTENT}, {@link #DOUBT_RATING} and
     *     {@link #AI_TOOLS_MENTIONED}
     */
    private static TweetDto tweet() {
        return tweetCarrying(TWEET_CONTENT, DOUBT_RATING, AI_TOOLS_MENTIONED);
    }

    /**
     * Builds a post carrying the three values the prompt is composed from, holding every other
     * component at its fixture value.
     *
     * @param content the post body
     * @param doubtRating the doubt rating
     * @param aiToolsMentioned the AI tool names
     * @return the post
     */
    private static TweetDto tweetCarrying(String content, double doubtRating,
            List<String> aiToolsMentioned) {

        return new TweetDto(TWEET_ID, content, LIKE_COUNT, CREATED_AT, doubtRating, MEDIA, null,
                USER_ID, aiToolsMentioned);
    }

    /**
     * Assembles the prompt a post with the given body and context clause yields.
     *
     * @param content the post body interpolated between single quotes
     * @param context the value following {@code Context: }
     * @return the composed prompt
     */
    private static String expectedPrompt(String content, String context) {
        return "Generate a response to the following tweet: '" + content + "'"
                + "\n\nContext: " + context
                + "\n\nResponse:";
    }

    /**
     * Builds a {@code scanner.openai} group carrying {@link #API_KEY} and the supplied values.
     *
     * @param model value of {@code scanner.openai.model}
     * @param maxCompletionTokens value of {@code scanner.openai.max-completion-tokens}
     * @param temperature value of {@code scanner.openai.temperature}
     * @param n value of {@code scanner.openai.n}
     * @return the group
     */
    private static ScannerProperties.Openai openaiGroup(String model, long maxCompletionTokens,
            Double temperature, long n) {

        return new ScannerProperties.Openai(API_KEY, model, maxCompletionTokens, temperature, n,
                REASONING_EFFORT, REQUEST_TIMEOUT_SECONDS, MAX_RETRIES);
    }

    /**
     * Builds a {@code scanner.openai} group carrying the supplied reasoning effort and the values
     * every other test uses.
     *
     * @param reasoningEffort value of {@code scanner.openai.reasoning-effort}, possibly {@code null}
     * @return the group
     */
    private static ScannerProperties.Openai openaiGroupWithReasoningEffort(String reasoningEffort) {
        return new ScannerProperties.Openai(API_KEY, MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N,
                reasoningEffort, REQUEST_TIMEOUT_SECONDS, MAX_RETRIES);
    }

    /**
     * Builds a configuration root carrying the supplied {@code scanner.openai} group. Every group
     * {@link LlmService} does not read is left unbound.
     *
     * @param openai the {@code scanner.openai} group
     * @return the configuration root
     */
    private static ScannerProperties propertiesCarrying(ScannerProperties.Openai openai) {
        return new ScannerProperties(null, 100, 60L, null, null, openai, null, null, null, null);
    }

    /**
     * Builds an unseamed {@link LlmService} whose cached client is the supplied one.
     *
     * <p>The cached field is written directly, which is the only way to place a stubbed client where
     * {@link LlmService#closeOpenAiClient()} reads it: {@link SeamedService} overrides the accessor
     * and therefore never populates that field.
     *
     * @param client the client to cache
     * @return the service holding {@code client}
     * @throws ReflectiveOperationException if the cached field cannot be written
     */
    private static LlmService plainServiceHolding(OpenAIClient client)
            throws ReflectiveOperationException {

        LlmService service = new LlmService(propertiesCarrying(
                openaiGroup(MODEL, MAX_COMPLETION_TOKENS, TEMPERATURE, N)));
        cachedClientField().set(service, client);
        return service;
    }

    /**
     * Reads the client an unseamed {@link LlmService} has cached.
     *
     * @param service the service to read
     * @return the cached client, or {@code null} when none is cached
     * @throws ReflectiveOperationException if the cached field cannot be read
     */
    private static OpenAIClient cachedClientOf(LlmService service)
            throws ReflectiveOperationException {

        return (OpenAIClient) cachedClientField().get(service);
    }

    /**
     * Returns the accessible field holding the cached OpenAI client.
     *
     * @return the cached-client field
     * @throws ReflectiveOperationException if the field is not declared
     */
    private static Field cachedClientField() throws ReflectiveOperationException {
        Field field = LlmService.class.getDeclaredField("client");
        field.setAccessible(true);
        return field;
    }

    /**
     * Builds the unit under test over a configuration root carrying the supplied group, with
     * {@link #openAiClient} behind the accessor.
     *
     * @param openai the {@code scanner.openai} group
     * @return the unit under test
     */
    private SeamedService seamedServiceCarrying(ScannerProperties.Openai openai) {
        return new SeamedService(propertiesCarrying(openai), openAiClient);
    }

    /**
     * Builds a Chat Completions response carrying the supplied choices in order.
     *
     * @param choices the choices the response carries; none yields a response with no choice
     * @return the response
     */
    private static ChatCompletion completionCarrying(ChatCompletion.Choice... choices) {
        return ChatCompletion.builder()
                .id("chatcmpl-fixture")
                .created(0L)
                .model(MODEL)
                .choices(List.of(choices))
                .build();
    }

    /**
     * Builds a complete choice: the supplied content, no refusal and a {@code stop} finish reason.
     *
     * @param content the message content, or {@link Optional#empty()} for a choice carrying none
     * @return the choice
     */
    private static ChatCompletion.Choice choiceCarrying(Optional<String> content) {
        return choiceCarrying(content, Optional.empty(), ChatCompletion.Choice.FinishReason.STOP);
    }

    /**
     * Builds one choice carrying the supplied content and finish reason, and no refusal.
     *
     * @param content      the message content the choice carries
     * @param finishReason the reason the model stopped
     * @return the choice
     */
    private static ChatCompletion.Choice choiceCarrying(Optional<String> content,
            ChatCompletion.Choice.FinishReason finishReason) {
        return choiceCarrying(content, Optional.empty(), finishReason);
    }

    /**
     * Builds one choice carrying the supplied content, refusal and finish reason.
     *
     * @param content      the message content, or {@link Optional#empty()} for a choice carrying none
     * @param refusal      the refusal, or {@link Optional#empty()} for a choice carrying none
     * @param finishReason the reason the model stopped
     * @return the choice
     */
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

    /**
     * Makes the stubbed client answer any request with a response whose single choice carries
     * {@code choiceContent}.
     *
     * @param choiceContent the message content the stubbed response carries
     */
    private void stubGeneratedText(String choiceContent) {
        stubClientReturning(completionCarrying(choiceCarrying(Optional.of(choiceContent))));
    }

    /**
     * Makes the stubbed client answer any request with {@code completion}.
     *
     * @param completion the response the stubbed client returns
     */
    /**
     * Builds a provider rejection carrying a status, a type, a code and a parameter name.
     *
     * @return the rejection the client raises
     */
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

    /**
     * Attaches a recorder to this class's logger.
     *
     * @return the attached recorder
     */
    private static ListAppender<ILoggingEvent> attachLogRecorder() {
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        ((Logger) LoggerFactory.getLogger(LlmService.class)).addAppender(records);
        return records;
    }

    /**
     * Detaches a recorder from this class's logger.
     *
     * @param records the recorder to detach
     */
    private static void detachLogRecorder(ListAppender<ILoggingEvent> records) {
        ((Logger) LoggerFactory.getLogger(LlmService.class)).detachAppender(records);
        records.stop();
    }

    /**
     * Renders every captured record with its arguments substituted.
     *
     * @param records the recorder to read
     * @return the rendered messages
     */
    private static List<String> renderedRecords(ListAppender<ILoggingEvent> records) {
        return records.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void stubClientReturning(ChatCompletion completion) {
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);
    }

    /**
     * Makes the stubbed client throw {@code failure} for any request.
     *
     * @param failure the throwable the stubbed client raises
     */
    private void stubClientFailure(RuntimeException failure) {
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(failure);
    }

    /**
     * Captures the single request the stubbed Chat Completions service received.
     *
     * @return the captured request
     */
    private ChatCompletionCreateParams capturedRequest() {
        ArgumentCaptor<ChatCompletionCreateParams> sentRequest =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(chatCompletionService).create(sentRequest.capture());
        return sentRequest.getValue();
    }

    /**
     * Captures every request the stubbed Chat Completions service received, in order.
     *
     * @param expectedCount the number of requests the service is verified to have received
     * @return the captured requests, oldest first
     */
    private List<ChatCompletionCreateParams> capturedRequests(int expectedCount) {
        ArgumentCaptor<ChatCompletionCreateParams> sentRequests =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(chatCompletionService, times(expectedCount)).create(sentRequests.capture());
        return sentRequests.getAllValues();
    }

    /**
     * Reads the text of the first user message a request carries.
     *
     * @param request the captured request
     * @return the prompt the request carries
     */
    private static String promptOf(ChatCompletionCreateParams request) {
        return request.messages().get(0).asUser().content().asText();
    }

    /**
     * Looks up a record component by name.
     *
     * @param recordType the record class to inspect
     * @param name the component name
     * @return the named component
     * @throws AssertionError if {@code recordType} declares no component with that name
     */
    private static RecordComponent recordComponent(Class<?> recordType, String name) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        recordType.getSimpleName() + " declares no component named " + name));
    }

    /**
     * {@link LlmService} with the protected OpenAI client accessor overridden to return a supplied
     * client and to count how often the accessor is reached.
     */
    private static final class SeamedService extends LlmService {

        private final OpenAIClient suppliedClient;

        /**
         * Accessor invocation count, created on first use by {@link #accessorCalls()}. Calls made
         * while the superclass constructor is still running are counted and retained.
         */
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

        /**
         * Returns how often {@link #openAiClient()} has been reached on this instance.
         *
         * @return the accessor invocation count
         */
        private int openAiClientAccessorCalls() {
            return accessorCalls().get();
        }
    }
}
