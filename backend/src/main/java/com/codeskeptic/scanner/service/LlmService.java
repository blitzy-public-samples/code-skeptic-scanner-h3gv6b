package com.codeskeptic.scanner.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.util.LogSafe;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import jakarta.annotation.PreDestroy;

// Ported from backend/app/services/llm_service.py:L6-32 (faithful port, modern API) — see docs/DECISION_LOG.md
/**
 * Adapter for the OpenAI API.
 *
 * <p>{@link #generateResponse(TweetDto)} builds the prompt of
 * {@code backend/app/services/llm_service.py:L16}, issues one Chat Completions request and returns
 * the trimmed generated text. {@link #closeOpenAiClient()} releases the client when the bean is
 * destroyed. No OpenAI SDK type appears in either signature — DL-081.
 *
 * <p>The {@link OpenAIClient} is created on first use by {@link #openAiClient()}, in place of the
 * {@code Completion.api_key} assignment at {@code backend/app/services/llm_service.py:L9} — DL-085.
 * Constructing this bean reads no credential and opens no connection.
 *
 * <p>The call parameters transcribe the literals passed to {@code Completion.create(...)} at
 * {@code backend/app/services/llm_service.py:L22-25} and reach the request from
 * {@code scanner.openai.max-completion-tokens}, {@code scanner.openai.n} and
 * {@code scanner.openai.temperature}. The first two are always carried. The third is carried only
 * when the key is set, and the key carries no default, so no temperature reaches the request unless
 * a deployment sets one. {@code scanner.openai.reasoning-effort} is carried when it is not blank.
 * The {@code stop=None} argument at {@code :L24} is expressed by setting no stop parameter.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-011, DL-032,
 * DL-033, DL-034, DL-035, DL-052, DL-081, DL-083, DL-084, DL-085 and DL-145; construct-level
 * provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean and every member declared here is safe for concurrent use. The client
 * field is written only inside a {@code synchronized (this)} block and read through a
 * {@code volatile} field access, so at most one client exists.
 */
@Service
public class LlmService {

    // Logging baseline — see docs/DECISION_LOG.md DL-052
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    // Prompt segments transcribed from backend/app/services/llm_service.py:L16 (faithful port) —
    // see docs/DECISION_LOG.md DL-035

    /** Opens the prompt and the quoted post body. Source text precedes {@code {tweet.content}}. */
    private static final String PROMPT_PREFIX = "Generate a response to the following tweet: '";

    /** Closes the quoted post body and opens the context clause. */
    private static final String PROMPT_CONTEXT_SEPARATOR = "'\n\nContext: ";

    /** Closes the prompt. Carries no trailing whitespace, matching the source literal. */
    private static final String PROMPT_SUFFIX = "\n\nResponse:";

    // The Context: value replaces {tweet.context}, an attribute the source model never declared
    // (backend/app/schema/tweet.py:L5-14) — see docs/DECISION_LOG.md DL-035

    /** Opens the context clause, ahead of the AI tool names. */
    private static final String CONTEXT_TOOLS_PREFIX = "AI tools mentioned: ";

    /** Separates the AI tool names from the doubt rating. */
    private static final String CONTEXT_RATING_PREFIX = "; doubt rating: ";

    /** Joins two AI tool names. */
    private static final String AI_TOOL_DELIMITER = ", ";

    /** Stands in for the AI tool names when the post names none. */
    private static final String NO_AI_TOOLS = "none";

    /** Stands in for the doubt rating when the value is absent or is not a finite number. */
    private static final String UNKNOWN_DOUBT_RATING = "unknown";

    // The four fixed unusable-output codes — see docs/DECISION_LOG.md DL-145

    /** Code for a response carrying no choice at all. */
    private static final String NO_CHOICE = "NO_CHOICE";

    /** Code for a first choice carrying a non-blank refusal. */
    private static final String REFUSAL = "REFUSAL";

    /**
     * Code prefix for a first choice whose finish reason is not
     * {@code stop}; the finish reason follows it.
     */
    private static final String INCOMPLETE_PREFIX = "INCOMPLETE:";

    /** Code for a first choice whose text is absent, or blank once trimmed. */
    private static final String BLANK_TEXT = "BLANK_TEXT";

    /** Configuration key of the reasoning effort, named by the failure message it can raise. */
    private static final String REASONING_EFFORT_KEY = "scanner.openai.reasoning-effort";

    /** Reasoning-effort values rendered from {@link ReasoningEffort.Value}, excluding {@code _UNKNOWN}. */
    private static final String ACCEPTED_REASONING_EFFORTS =
            Arrays.stream(ReasoningEffort.Value.values())
                    .filter(value -> value != ReasoningEffort.Value._UNKNOWN)
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));


    /**
     * Longest run of post body carried into the prompt. A longer body is cut to this length and
     * marked with {@value #BODY_TRUNCATION_MARK}.
     */
    private static final int PROMPT_BODY_LIMIT = 1000;

    /** Appended to a post body cut to {@value #PROMPT_BODY_LIMIT}. */
    private static final String BODY_TRUNCATION_MARK = "…";

    /** Highest accepted value of {@code scanner.openai.temperature}. */
    private static final double MAXIMUM_TEMPERATURE = 2.0d;

    /** Reported in place of an absent OpenAI error component. */
    private static final String ABSENT = "absent";

    /**
     * Bound configuration root, supplying the OpenAI credential, the model identifier and the three
     * call parameters, in place of the {@code get_settings()} call at
     * {@code backend/app/services/llm_service.py:L8}.
     */
    private final ScannerProperties properties;

    /**
     * OpenAI client, created on first use by {@link #openAiClient()} and reused thereafter. Written
     * only inside a {@code synchronized (this)} block and read through a {@code volatile} field
     * access. {@link #closeOpenAiClient()} clears it.
     */
    private volatile OpenAIClient client;

    /**
     * Set by {@link #closeOpenAiClient()} when the bean is destroyed. Written and read only inside a
     * {@code synchronized (this)} block, so no client can be created after destruction.
     */
    private boolean destroyed;

    /**
     * Creates the service.
     *
     * <p>No credential is read and no connection is opened here; the OpenAI client is created later
     * by {@link #openAiClient()}.
     *
     * @param properties the bound configuration root; must not be {@code null}
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    // Replaces the constructor at backend/app/services/llm_service.py:L7-9 (faithful port) — see
    // docs/DECISION_LOG.md DL-052
    public LlmService(ScannerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    // The adapter returns generated text; ResponseService owns persistence and the wire record —
    // DL-081 — see docs/DECISION_LOG.md
    /**
     * Generates a reply to the supplied post and returns the generated text.
     *
     * <p>The prompt is assembled by {@link #buildPrompt(TweetDto)}. One Chat Completions request
     * carries it as a single user message, together with the model identifier from
     * {@code scanner.openai.model} and the three call parameters from
     * {@code scanner.openai.max-completion-tokens}, {@code scanner.openai.temperature} and
     * {@code scanner.openai.n}. No stop parameter is set. The reasoning effort of
     * {@code scanner.openai.reasoning-effort} is added by {@link #resolveReasoningEffort()}, which
     * omits the parameter when the configured value is blank — see docs/DECISION_LOG.md DL-145. The
     * first choice's message content is trimmed, matching the {@code .strip()} at
     * {@code backend/app/services/llm_service.py:L29}.
     *
     * <p>The return value is the generated text; storing it happens outside this class — see
     * docs/DECISION_LOG.md DL-081.
     *
     * <p>Only a complete, non-blank reply is returned. {@link #firstChoiceContent(ChatCompletion)}
     * reports every other outcome under a fixed unusable-output code as an
     * {@link IllegalStateException}, which the caller renders as the wire literal of
     * {@code backend/app/api/responses.py:L49} — see docs/DECISION_LOG.md DL-083 and DL-145.
     *
     * <p>A failure raised by the OpenAI client is recorded here under the sanitized adapter policy
     * and propagates unchanged — see docs/DECISION_LOG.md DL-084.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the trimmed generated text, never {@code null} and never blank
     * @throws NullPointerException  if {@code tweet} is {@code null}
     * @throws IllegalStateException if {@code scanner.openai.api-key} or
     *                               {@code scanner.openai.model} is unset or blank, if
     *                               {@code scanner.openai.reasoning-effort} names a value the SDK
     *                               does not recognise, if this bean has been destroyed, or if the
     *                               model returned no usable reply — in the last case
     *                               {@link Throwable#getMessage()} is the fixed unusable-output code
     */
    // Ported from backend/app/services/llm_service.py:L14-32 (faithful port). Chat Completions
    // replaces Completion.create(engine="text-davinci-002", ...) at :L19-26 — see
    // docs/DECISION_LOG.md DL-011, DL-032, DL-033, DL-034, DL-081 and DL-083
    public String generateResponse(TweetDto tweet) {
        Objects.requireNonNull(tweet, "tweet must not be null.");

        String prompt = buildPrompt(tweet);
        String model = requireConfigured(openai().model(), "scanner.openai.model");

        log.debug("Requesting a generated reply for tweet {} from model {} with a {} character prompt",
                tweet.id(), model, prompt.length());

        ChatCompletionCreateParams params = buildParams(model, prompt);

        ChatCompletion completion;
        try {
            completion = openAiClient().chat().completions().create(params);
        } catch (OpenAIServiceException rejected) {
            log.error("Model {} rejected the generation request for tweet {} with HTTP {}: "
                    + "type {}, code {}, param {}",
                    model, tweet.id(), rejected.statusCode(),
                    rejected.type().orElse(ABSENT),
                    rejected.code().orElse(ABSENT),
                    rejected.param().orElse(ABSENT));
            throw rejected;
        } catch (RuntimeException failure) {
            log.error("Requesting a generated reply for tweet {} from model {} failed with {}",
                    tweet.id(), model, LogSafe.type(failure));
            throw failure;
        }

        String generatedText = firstChoiceContent(completion);

        log.info("Model {} returned {} character(s) of generated text for tweet {}",
                model, generatedText.length(), LogSafe.logSafe(tweet.id()));

        return generatedText;
    }

    /**
     * Builds the Chat Completions request.
     *
     * <p>{@code scanner.openai.max-completion-tokens} and {@code scanner.openai.n} are always
     * carried and are validated as at least one. {@code scanner.openai.reasoning-effort} is carried
     * when it names one of the values the API accepts, and is omitted when the key is blank.
     * {@code scanner.openai.temperature} is carried only when the key is set, and is validated to lie
     * between {@code 0} and {@value #MAXIMUM_TEMPERATURE} inclusive; a reasoning model accepts only
     * its own default temperature, so leaving the key unset is what keeps the parameter off the
     * request. No stop parameter is set, expressing the {@code stop=None} argument at
     * {@code backend/app/services/llm_service.py:L24}.
     *
     * @param model  the model identifier, never blank
     * @param prompt the single user message, never blank
     * @return the request parameters, never {@code null}
     * @throws IllegalStateException when a configured value lies outside its accepted range
     */
    // Ported from backend/app/services/llm_service.py:L19-26 (faithful port) — see
    // docs/DECISION_LOG.md DL-032, DL-033, DL-034 and DL-145
    private ChatCompletionCreateParams buildParams(String model, String prompt) {
        ScannerProperties.Openai openai = openai();

        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)                                                   // :L20 — DL-033
                .addUserMessage(prompt)                                         // :L21
                .maxCompletionTokens(requireAtLeastOne(openai.maxCompletionTokens(),
                        "scanner.openai.max-completion-tokens"))                // :L22 — DL-034
                .n(requireAtLeastOne(openai.n(), "scanner.openai.n"));          // :L23
        // :L24 stop=None — no stop parameter is set.

        resolveReasoningEffort().ifPresent(builder::reasoningEffort);

        Double temperature = openai.temperature();
        if (temperature != null) {
            if (!Double.isFinite(temperature) || temperature < 0.0d
                    || temperature > MAXIMUM_TEMPERATURE) {
                throw new IllegalStateException("scanner.openai.temperature must lie between 0 and "
                        + MAXIMUM_TEMPERATURE + " inclusive.");
            }
            builder.temperature(temperature);                                   // :L25
        }

        return builder.build();
    }

    /**
     * Validates one configured count.
     *
     * @param value the configured value
     * @param key   the configuration key the value binds from; named in the failure message
     * @return {@code value}, guaranteed to be at least one
     * @throws IllegalStateException when {@code value} is below one
     */
    private static long requireAtLeastOne(long value, String key) {
        if (value < 1L) {
            throw new IllegalStateException(key + " must be at least 1.");
        }
        return value;
    }

    /**
     * Validates one configured retry limit.
     *
     * @param value the configured value
     * @param key   the configuration key the value binds from; named in the failure message
     * @return {@code value}, guaranteed not to be negative
     * @throws IllegalStateException when {@code value} is negative
     */
    private static int requireNotNegative(int value, String key) {
        if (value < 0) {
            throw new IllegalStateException(key + " must not be negative.");
        }
        return value;
    }

    /**
     * Returns the OpenAI client, creating it on first use from {@code scanner.openai.api-key} and
     * reusing it thereafter.
     *
     * <p>Access uses double-checked locking over the {@code volatile} field, so at most one client is
     * created. The credential is read on this path and not at construction, so the failure surfaces on
     * the first generation attempt — DL-085.
     *
     * @return the OpenAI client, never {@code null}
     * @throws IllegalStateException if {@code scanner.openai.api-key} is unset or blank, or if this
     *     bean has been destroyed
     */
    // Replaces the module-level `Completion.api_key = ...` assignment at
    // backend/app/services/llm_service.py:L9 (net-new lifecycle) — see docs/DECISION_LOG.md DL-011
    // and DL-085
    protected OpenAIClient openAiClient() {
        OpenAIClient local = this.client;
        if (local == null) {
            synchronized (this) {
                if (this.destroyed) {
                    throw new IllegalStateException(
                            "LlmService has been destroyed; no OpenAI API client is created.");
                }
                local = this.client;
                if (local == null) {
                    String apiKey = requireConfigured(openai().apiKey(), "scanner.openai.api-key");
                    // Explicit request budget and retry limit - see docs/DECISION_LOG.md DL-146
                    Duration requestTimeout = Duration.ofSeconds(requireAtLeastOne(
                            openai().requestTimeoutSeconds(),
                            "scanner.openai.request-timeout-seconds"));
                    int maxRetries = requireNotNegative(openai().maxRetries(),
                            "scanner.openai.max-retries");
                    log.info("Creating the OpenAI API client with a {}s request timeout and {} retries",
                            requestTimeout.toSeconds(), maxRetries);
                    local = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(requestTimeout)
                            .maxRetries(maxRetries)
                            .build();
                    this.client = local;
                }
            }
        }
        return local;
    }

    /**
     * Closes the OpenAI client and clears it when this bean is destroyed.
     *
     * <p>Runs on the container's destruction callback. The client field is read and written inside the
     * same monitor {@link #openAiClient()} uses, so a client created before destruction is closed
     * exactly once and {@link #openAiClient()} creates no replacement afterwards — see
     * docs/DECISION_LOG.md DL-085.
     *
     * <p>A failure raised while closing is reported at {@code WARN} by the failure's type and does not
     * propagate. The field is cleared whether or not closing succeeded.
     */
    // Net-new (no Python counterpart: the source assigned a module-level credential at
    // backend/app/services/llm_service.py:L9 and held no client) — DL-085 — see
    // docs/DECISION_LOG.md
    @PreDestroy
    public void closeOpenAiClient() {
        OpenAIClient local;
        synchronized (this) {
            this.destroyed = true;
            local = this.client;
            this.client = null;
        }
        if (local == null) {
            return;
        }
        log.info("Closing the OpenAI API client");
        try {
            local.close();
        } catch (RuntimeException e) {
            log.warn("Closing the OpenAI API client did not complete: {}",
                    LogSafe.type(e));
        }
    }

    /**
     * Assembles the prompt, reproducing the source f-string segment for segment.
     *
     * <p>The post body is interpolated exactly as supplied, unescaped and untruncated, matching the
     * source. The {@code Context:} value comes from {@link #buildContext(TweetDto)}.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the prompt carried as the single user message, never {@code null}
     */
    // Ported from backend/app/services/llm_service.py:L16 (faithful port) — see
    // docs/DECISION_LOG.md DL-035
    private String buildPrompt(TweetDto tweet) {
        return PROMPT_PREFIX
                + boundedBody(tweet.content())
                + PROMPT_CONTEXT_SEPARATOR
                + buildContext(tweet)
                + PROMPT_SUFFIX;
    }

    /**
     * Bounds the post body carried into the prompt.
     *
     * <p>The body is attacker-authored text. A body longer than {@value #PROMPT_BODY_LIMIT}
     * characters is cut to that length and marked with {@value #BODY_TRUNCATION_MARK}, and every
     * line break within it is folded to a space so the body cannot introduce a line of its own into
     * the prompt structure. An absent body reads as the empty string.
     *
     * @param content the post body, possibly {@code null}
     * @return the text to interpolate, never {@code null}
     */
    private static String boundedBody(String content) {
        if (content == null) {
            return "";
        }
        String folded = content.replace('\r', ' ').replace('\n', ' ');
        if (folded.length() <= PROMPT_BODY_LIMIT) {
            return folded;
        }
        return folded.substring(0, PROMPT_BODY_LIMIT) + BODY_TRUNCATION_MARK;
    }

    /**
     * Builds the {@code Context:} value from the AI tool names and the doubt rating the post
     * carries.
     *
     * <p>The value is {@code "AI tools mentioned: <names>; doubt rating: <rating>"}. The names are
     * joined with {@value #AI_TOOL_DELIMITER} in list order, or rendered as {@value #NO_AI_TOOLS} when
     * the post names none. The rating is rendered by {@link Double#toString(double)}, which is
     * independent of the default locale, or as {@value #UNKNOWN_DOUBT_RATING} when the value is absent
     * or is not finite — DL-080.
     *
     * <p>Only {@link TweetDto#aiToolsMentioned()} and {@link TweetDto#doubtRating()} are read, so the
     * same post always produces the same value.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the {@code Context:} value, never {@code null} and never empty
     */
    // Replaces {tweet.context} at backend/app/services/llm_service.py:L16, an attribute the source
    // model never declared (backend/app/schema/tweet.py:L5-14) — see docs/DECISION_LOG.md DL-035
    private String buildContext(TweetDto tweet) {
        List<String> aiToolsMentioned = tweet.aiToolsMentioned();
        String toolNames = (aiToolsMentioned == null || aiToolsMentioned.isEmpty())
                ? NO_AI_TOOLS
                : String.join(AI_TOOL_DELIMITER, aiToolsMentioned);

        Double doubtRating = tweet.doubtRating();
        String rating = (doubtRating != null && Double.isFinite(doubtRating))
                ? Double.toString(doubtRating)
                : UNKNOWN_DOUBT_RATING;

        return CONTEXT_TOOLS_PREFIX + toolNames + CONTEXT_RATING_PREFIX + rating;
    }

    /**
     * Extracts and trims the text of the first choice the response carries.
     *
     * <p>Choices past the first are ignored, matching the {@code choices[0]} index at
     * {@code backend/app/services/llm_service.py:L29}.
     *
     * <p>Only a complete, non-blank reply is accepted. Four outcomes are reported at {@code WARN}
     * under a fixed unusable-output code and raised as an {@link IllegalStateException} whose message
     * is that code — see docs/DECISION_LOG.md DL-083 and DL-145:
     *
     * <ul>
     *   <li>{@value #NO_CHOICE} — the response carries no choice.</li>
     *   <li>{@value #REFUSAL} — the first choice carries a non-blank refusal. The refusal text is
     *       model output and is never logged.</li>
     *   <li>{@value #INCOMPLETE_PREFIX} followed by the finish reason — the first choice finished for
     *       a reason other than {@code stop}. The finish reason is an enumerated provider token,
     *       rendered through {@link LogSafe#logSafe(String)}.</li>
     *   <li>{@value #BLANK_TEXT} — the first choice carries no content, or content that is blank once
     *       trimmed.</li>
     * </ul>
     *
     * <p>The checks run in that order, so the earliest applicable code is the one reported.
     *
     * @param completion the Chat Completions response; must not be {@code null}
     * @return the trimmed generated text, never {@code null} and never blank
     * @throws IllegalStateException if the response carries no usable reply; the message is the fixed
     *                               unusable-output code
     */
    // Ported from backend/app/services/llm_service.py:L29 (faithful port); the refusal and
    // finish-reason states are net-new, the source's completions response carried neither field —
    // see docs/DECISION_LOG.md DL-032, DL-083 and DL-145
    private String firstChoiceContent(ChatCompletion completion) {
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices == null || choices.isEmpty()) {
            throw unusableOutput(NO_CHOICE, "the response carried no choice");
        }
        ChatCompletion.Choice choice = choices.get(0);

        String refusal = choice.message().refusal().orElse(null);
        if (refusal != null && !refusal.isBlank()) {
            throw unusableOutput(REFUSAL, "the first choice carried a refusal");
        }

        ChatCompletion.Choice.FinishReason finishReason = choice.finishReason();
        if (!ChatCompletion.Choice.FinishReason.STOP.equals(finishReason)) {
            throw unusableOutput(INCOMPLETE_PREFIX + LogSafe.logSafe(finishReason.asString()),
                    "the first choice did not finish");
        }

        String content = choice.message().content().orElse(null);
        if (content == null) {
            throw unusableOutput(BLANK_TEXT, "the first choice carried no content");
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw unusableOutput(BLANK_TEXT, "the first choice carried blank content");
        }
        return trimmed;
    }

    /**
     * Records an unusable Chat Completions reply and builds the failure that reports it.
     *
     * <p>The returned exception's message is the code itself, so the caller can report the state
     * without reproducing any model output. The refusal text and the reply text are never logged.
     *
     * @param code   the unusable-output code
     * @param detail fixed text naming the observed state, for the log record only
     * @return the failure to raise
     */
    private IllegalStateException unusableOutput(String code, String detail) {
        log.warn("The Chat Completions reply is unusable [{}]: {}; no reply was generated",
                code, detail);
        return new IllegalStateException(code);
    }

    /**
     * Reads {@code scanner.openai.reasoning-effort} and validates it against the values the SDK
     * recognises.
     *
     * <p>A blank or absent value omits the parameter. A value the SDK does not recognise is rejected
     * before the request is built, naming the key and the accepted set; the SDK itself carries an
     * unrecognised value as {@code _UNKNOWN} rather than rejecting it.
     *
     * @return the effort to carry on the request, or {@link Optional#empty()} to omit the parameter
     * @throws IllegalStateException when the configured value is not one of the accepted values
     */
    private Optional<ReasoningEffort> resolveReasoningEffort() {
        String configured = openai().reasoningEffort();
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        ReasoningEffort effort = ReasoningEffort.of(configured.trim());
        if (effort.value() == ReasoningEffort.Value._UNKNOWN) {
            throw new IllegalStateException(REASONING_EFFORT_KEY
                    + " is not one of the accepted values " + ACCEPTED_REASONING_EFFORTS
                    + "; leave it blank to omit the parameter.");
        }
        return Optional.of(effort);
    }

    /**
     * Returns the {@code scanner.openai} group.
     *
     * @return the bound OpenAI configuration group, never {@code null}
     * @throws IllegalStateException if the group is absent
     */
    // Ported from backend/app/core/config.py:L8 and backend/app/services/llm_service.py:L20-25
    // (faithful port) — see docs/DECISION_LOG.md DL-033
    private ScannerProperties.Openai openai() {
        ScannerProperties.Openai openai = properties.openai();
        if (openai == null) {
            throw new IllegalStateException(
                    "The scanner.openai configuration group is not bound; declare it in application.yml.");
        }
        return openai;
    }

    /**
     * Returns a configured value, rejecting one that is absent or blank.
     *
     * <p>The message names the configuration key only; no configured value reaches the message, the
     * log or any caller — DL-052.
     *
     * @param value the configured value, possibly {@code null}
     * @param key   the configuration key the value binds from; used in the failure message
     * @return {@code value}, guaranteed neither {@code null} nor blank
     * @throws IllegalStateException if {@code value} is {@code null} or blank
     */
    // Replaces the unguarded read of settings.openai_api_key at
    // backend/app/services/llm_service.py:L9, which named a key the source never declared
    // (backend/app/core/config.py:L8 declared OPENAI_API_KEY) — see docs/DECISION_LOG.md DL-033
    private String requireConfigured(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not configured; set it to generate responses.");
        }
        return value;
    }

    // Net-new (no Python counterpart; the retired call set no reasoning parameter) — DL-145 — see
    // docs/DECISION_LOG.md
}
