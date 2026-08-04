package com.codeskeptic.scanner.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

// Ported from backend/app/services/llm_service.py:L6-32 (faithful port, modern API) — see docs/DECISION_LOG.md
/**
 * Adapter for the OpenAI API and the single home of reply generation.
 *
 * <p>One operation is exposed. {@link #generateResponse(TweetDto)} builds the prompt the source
 * assembled at {@code backend/app/services/llm_service.py:L16}, issues one Chat Completions request
 * and returns the generated text as a {@link ResponseDto}. No OpenAI SDK type appears in that
 * signature.
 *
 * <p>The underlying {@link OpenAIClient} is created on first use by {@link #openAiClient()};
 * constructing this bean reads no credential and opens no connection, so the application context
 * loads with {@code scanner.openai.api-key} unset. The source instead assigned
 * {@code Completion.api_key} as a constructor side effect at
 * {@code backend/app/services/llm_service.py:L9}; no static or global SDK state is written here.
 *
 * <p>The three call parameters transcribe the literals passed to {@code Completion.create(...)} at
 * {@code backend/app/services/llm_service.py:L22-25} and reach the request from
 * {@code scanner.openai.max-completion-tokens}, {@code scanner.openai.temperature} and
 * {@code scanner.openai.n}, whose defaults are {@code 150}, {@code 0.7} and {@code 1}. The
 * {@code stop=None} argument at {@code :L24} is expressed by setting no stop parameter at all.
 *
 * <p>This class generates text and returns it. It reaches no repository, holds no HTTP client for X
 * and declares no operation that publishes a reply; {@code is_approved} on the returned record is
 * {@code false} and is a flag for a human to read ({@code backend/app/db/models.py:L26}).
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-011, DL-032,
 * DL-033, DL-034, DL-035 and DL-052; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean and every member declared here is safe for concurrent use. The client
 * field is written only inside a {@code synchronized (this)} block and read through a
 * {@code volatile} field access, so at most one client is created however many request threads and
 * scheduler threads call {@link #generateResponse(TweetDto)} at once.
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

    /** Stands in for the doubt rating when the value is not a finite number. */
    private static final String UNKNOWN_DOUBT_RATING = "unknown";

    /** Returned as the generated text when the response carries no usable content. */
    private static final String NO_GENERATED_TEXT = "";

    /**
     * Bound configuration root, supplying the OpenAI credential, the model identifier and the three
     * call parameters. Replaces the {@code get_settings()} call at
     * {@code backend/app/services/llm_service.py:L8}, which built a new settings object per
     * instantiation ({@code backend/app/core/config.py:L17-18}).
     */
    private final ScannerProperties properties;

    /**
     * OpenAI client, created on first use by {@link #openAiClient()} and reused thereafter. Written
     * only inside a {@code synchronized (this)} block and read through a {@code volatile} field
     * access.
     */
    private volatile OpenAIClient client;

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

    /**
     * Generates a reply to the supplied post and returns it as an unsaved {@link ResponseDto}.
     *
     * <p>The prompt is assembled by {@link #buildPrompt(TweetDto)}. One Chat Completions request
     * carries it as a single user message, together with the model identifier from
     * {@code scanner.openai.model} and the three call parameters from
     * {@code scanner.openai.max-completion-tokens}, {@code scanner.openai.temperature} and
     * {@code scanner.openai.n}. No stop parameter is set. The first choice's message content is
     * trimmed, matching the {@code .strip()} at
     * {@code backend/app/services/llm_service.py:L29}; a response carrying no choice, or a choice
     * whose content is absent, yields empty text; no exception is raised.
     *
     * <p>The returned record carries {@code generatedAt} as the current local time and
     * {@code isApproved} as {@code false}, both of which the source omitted at
     * {@code backend/app/services/llm_service.py:L32}. {@code id} is {@code null}: it is the
     * database-generated key of the {@code responses} row and does not exist until the row is
     * saved, which happens outside this class.
     *
     * <p>A failure is logged at {@code ERROR} and rethrown unchanged. No message is minted here for
     * a client response and no substitute text is returned.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the generated reply, with {@code id} {@code null} and {@code isApproved}
     *         {@code false}, never {@code null}
     * @throws NullPointerException  if {@code tweet} is {@code null}
     * @throws IllegalStateException if {@code scanner.openai.api-key} or
     *                               {@code scanner.openai.model} is unset or blank
     */
    // Ported from backend/app/services/llm_service.py:L14-32 (faithful port). Chat Completions
    // replaces Completion.create(engine="text-davinci-002", ...) at :L19-26 — see
    // docs/DECISION_LOG.md DL-011, DL-032, DL-033 and DL-034
    public ResponseDto generateResponse(TweetDto tweet) {
        Objects.requireNonNull(tweet, "tweet must not be null.");

        String prompt = buildPrompt(tweet);
        String model = requireConfigured(openai().model(), "scanner.openai.model");

        log.info("Requesting a generated reply for tweet {} from model {} with a {} character prompt",
                tweet.id(), model, prompt.length());

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(model)                                            // :L20 — DL-033
                    .addUserMessage(prompt)                                  // :L21
                    .maxCompletionTokens(openai().maxCompletionTokens())     // :L22 — DL-034
                    .n(openai().n())                                         // :L23
                    // :L24 stop=None — no stop parameter is set.
                    .temperature(openai().temperature())                     // :L25
                    .build();

            ChatCompletion completion = openAiClient().chat().completions().create(params);
            String generatedText = firstChoiceContent(completion);

            log.info("Model {} returned {} character(s) of generated text for tweet {}",
                    model, generatedText.length(), tweet.id());

            // backend/app/services/llm_service.py:L32 set content and tweet_id only; generatedAt
            // and isApproved are set here and id is the unassigned database key.
            return new ResponseDto(null, generatedText, LocalDateTime.now(), false, tweet.id());
        } catch (RuntimeException e) {
            log.error("Response generation failed for tweet {} using model {}", tweet.id(), model, e);
            throw e;
        }
    }

    /**
     * Returns the OpenAI client, creating it on first use from {@code scanner.openai.api-key} and
     * reusing it thereafter.
     *
     * <p>Access uses double-checked locking over the {@code volatile} field, so at most one client
     * is created however many threads call this method concurrently. The credential is read on this
     * path. No credential is read at construction, so the application context loads with the key
     * unset and the failure surfaces on the first generation attempt.
     *
     * <p>Declared neither {@code private} nor {@code final}, so a subclass can supply the client.
     *
     * @return the OpenAI client, never {@code null}
     * @throws IllegalStateException if {@code scanner.openai.api-key} is unset or blank
     */
    // Replaces the module-level `Completion.api_key = ...` assignment at
    // backend/app/services/llm_service.py:L9 (net-new lifecycle) — see docs/DECISION_LOG.md DL-011
    protected OpenAIClient openAiClient() {
        OpenAIClient local = this.client;
        if (local == null) {
            synchronized (this) {
                local = this.client;
                if (local == null) {
                    String apiKey = requireConfigured(openai().apiKey(), "scanner.openai.api-key");
                    log.info("Creating the OpenAI API client");
                    local = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
                    this.client = local;
                }
            }
        }
        return local;
    }

    /**
     * Assembles the prompt, reproducing the source f-string segment for segment.
     *
     * <p>The post body is interpolated exactly as supplied — unescaped, untruncated and with a
     * quote character inside it left alone, matching the source. The {@code Context:} value comes
     * from {@link #buildContext(TweetDto)}.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the prompt carried as the single user message, never {@code null}
     */
    // Ported from backend/app/services/llm_service.py:L16 (faithful port) — see
    // docs/DECISION_LOG.md DL-035
    private String buildPrompt(TweetDto tweet) {
        return PROMPT_PREFIX
                + tweet.content()
                + PROMPT_CONTEXT_SEPARATOR
                + buildContext(tweet)
                + PROMPT_SUFFIX;
    }

    /**
     * Builds the {@code Context:} value from the AI tool names and the doubt rating the post
     * carries.
     *
     * <p>The value is {@code "AI tools mentioned: <names>; doubt rating: <rating>"}. The names are
     * joined with {@value #AI_TOOL_DELIMITER} in list order, or rendered as
     * {@value #NO_AI_TOOLS} when the post names none. The rating is rendered by
     * {@link Double#toString(double)}, which is independent of the default locale, or as
     * {@value #UNKNOWN_DOUBT_RATING} when the value is not finite.
     *
     * <p>The same post always produces the same value: no timestamp, no random value and no
     * unordered iteration contributes to it, and only {@link TweetDto#aiToolsMentioned()} and
     * {@link TweetDto#doubtRating()} are read.
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

        double doubtRating = tweet.doubtRating();
        String rating = Double.isFinite(doubtRating)
                ? Double.toString(doubtRating)
                : UNKNOWN_DOUBT_RATING;

        return CONTEXT_TOOLS_PREFIX + toolNames + CONTEXT_RATING_PREFIX + rating;
    }

    /**
     * Extracts and trims the text of the first choice the response carries.
     *
     * <p>A response carrying no choice is reported at {@code WARN} and yields empty text, as does a
     * choice whose message content is absent. Choices past the first are ignored, matching the
     * {@code choices[0]} index at {@code backend/app/services/llm_service.py:L29}.
     *
     * @param completion the Chat Completions response; must not be {@code null}
     * @return the trimmed generated text, never {@code null}
     */
    // Ported from backend/app/services/llm_service.py:L29 (faithful port) — see
    // docs/DECISION_LOG.md DL-032
    private String firstChoiceContent(ChatCompletion completion) {
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices == null || choices.isEmpty()) {
            log.warn("The Chat Completions response carried no choice; the generated text is empty");
            return NO_GENERATED_TEXT;
        }
        return choices.get(0).message().content().orElse(NO_GENERATED_TEXT).trim();
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
     * <p>The message names the configuration key only. No configured value reaches the message, the
     * log or any caller.
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
}
