package com.codeskeptic.scanner.service;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
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
 * {@code scanner.openai.temperature}, whose defaults are the source's own {@code 150}, {@code 1} and
 * {@code 0.7}. The first two are always carried; the third is carried whenever the key holds a value,
 * and a deployment that sets it blank omits the parameter. {@code scanner.openai.reasoning-effort} is
 * carried when it is not blank. The {@code stop=None} argument at {@code :L24} is expressed by setting
 * no stop parameter.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-011, DL-032,
 * DL-033, DL-034, DL-035, DL-052, DL-081, DL-083, DL-084, DL-085, DL-145, DL-200 and DL-202;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
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

    /** Configuration key of the temperature, named by the failure message it can raise. */
    private static final String TEMPERATURE_KEY = "scanner.openai.temperature";

    // Model-specific reasoning-effort allowlist — DL-145 — see docs/DECISION_LOG.md
    /**
     * The reasoning-effort values the configured GPT-5.x model accepts. It is narrower than the
     * SDK-global {@link ReasoningEffort.Value} set, which the client carries for every model it can
     * address: {@code minimal} is refused by the configured model with HTTP 400
     * {@code unsupported_value}, and it is rejected here and never sent — DL-145, DL-245.
     */
    private static final Set<ReasoningEffort.Value> ACCEPTED_REASONING_EFFORT_VALUES =
            Collections.unmodifiableSet(EnumSet.of(
                    ReasoningEffort.Value.NONE,
                    ReasoningEffort.Value.LOW,
                    ReasoningEffort.Value.MEDIUM,
                    ReasoningEffort.Value.HIGH,
                    ReasoningEffort.Value.XHIGH,
                    ReasoningEffort.Value.MAX));

    /** {@link #ACCEPTED_REASONING_EFFORT_VALUES} rendered for a failure message, in enum order. */
    private static final String ACCEPTED_REASONING_EFFORTS =
            ACCEPTED_REASONING_EFFORT_VALUES.stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));


    /** Appended to the joined AI tool names when they are cut to {@value #PROMPT_CONTEXT_LIMIT}. */
    private static final String BODY_TRUNCATION_MARK = "…";

    // Bounds applied to the Context: value — DL-265 — see docs/DECISION_LOG.md
    /** Most AI tool names carried into the prompt. Names past this many are not carried. */
    private static final int PROMPT_AI_TOOL_LIMIT = 10;

    /** Most characters the joined AI tool names occupy in the prompt. */
    private static final int PROMPT_CONTEXT_LIMIT = 200;

    /**
     * Longest {@link #closeOpenAiClient()} awaits an in-flight completion before releasing the
     * client, in seconds — DL-266.
     */
    private static final long SHUTDOWN_AWAIT_SECONDS = 30L;

    /** Highest accepted value of {@code scanner.openai.temperature}. */
    private static final double MAXIMUM_TEMPERATURE = 2.0d;

    /** Reported in place of an absent OpenAI error component. */
    private static final String ABSENT = "absent";

    /**
     * Accepted shape of a provider-controlled rejection member once the log guard has rendered it —
     * DL-084, DL-119. A machine-readable member carries no intra-value spacing, so a rendering that
     * does is free text and is refused, and no part of it is carried.
     */
    private static final Pattern GUARDED_MEMBER_SHAPE = Pattern.compile("\\S{1,64}");

    /** Reported when work arrives after this bean has been destroyed — DL-266. */
    private static final String DESTROYED_MESSAGE =
            "LlmService has been destroyed; no OpenAI API client is created.";

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
     * Set by {@link #closeOpenAiClient()} when the bean is destroyed. Read without the monitor by
     * {@link #generateResponse(TweetDto)} so a request arriving during a shutdown is rejected before it
     * queues on {@link #activeUseLock}, and written inside a {@code synchronized (this)} block so no
     * client can be created after destruction — DL-266.
     */
    private volatile boolean destroyed;

    /**
     * Held for reading while one completion is in flight and for writing while the client is released,
     * so a context close cannot abort live work — DL-266.
     */
    private final ReentrantReadWriteLock activeUseLock = new ReentrantReadWriteLock();

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

        // A request arriving during a shutdown is rejected before it queues on the lock — DL-266 —
        // see docs/DECISION_LOG.md
        if (destroyed) {
            throw new IllegalStateException(DESTROYED_MESSAGE);
        }

        String prompt = buildPrompt(tweet);
        String model = requireConfigured(openai().model(), "scanner.openai.model");

        // Every caller-supplied and configuration-derived value rendered through the log guard —
        // DL-149 — see docs/DECISION_LOG.md
        log.debug("Requesting a generated reply for tweet {} from model {} with a {} character prompt",
                LogSafe.logSafe(tweet.id()), LogSafe.logSafe(model), prompt.length());

        ChatCompletionCreateParams params = buildParams(model, prompt);

        // The completion is in flight for as long as this lock is held, so a context close awaits it
        // — DL-266 — see docs/DECISION_LOG.md
        Lock activeUse = activeUseLock.readLock();
        activeUse.lock();
        ChatCompletion completion;
        try {
            if (destroyed) {
                throw new IllegalStateException(DESTROYED_MESSAGE);
            }
            completion = openAiClient().chat().completions().create(params);
        } catch (OpenAIServiceException rejected) {
            // Every provider-controlled member passes the log guard before it is recorded — DL-149
            // — see docs/DECISION_LOG.md
            log.error("Model {} rejected the generation request for tweet {} with HTTP {}: "
                    + "type {}, code {}, param {}",
                    LogSafe.logSafe(model), LogSafe.logSafe(tweet.id()), rejected.statusCode(),
                    guarded(rejected.type()),
                    guarded(rejected.code()),
                    guarded(rejected.param()));
            throw rejected;
        } catch (RuntimeException failure) {
            log.error("Requesting a generated reply for tweet {} from model {} failed with {}",
                    LogSafe.logSafe(tweet.id()), LogSafe.logSafe(model), LogSafe.type(failure));
            throw failure;
        } finally {
            activeUse.unlock();
        }

        String generatedText = firstChoiceContent(completion);

        log.info("Model {} returned {} character(s) of generated text for tweet {}",
                LogSafe.logSafe(model), generatedText.length(), LogSafe.logSafe(tweet.id()));

        return generatedText;
    }

    /**
     * Builds the Chat Completions request.
     *
     * <p>{@code scanner.openai.max-completion-tokens} and {@code scanner.openai.n} are always
     * carried and are validated as at least one. {@code scanner.openai.reasoning-effort} is carried
     * when it names one of the values the API accepts, and is omitted when the key is blank.
     * {@code scanner.openai.temperature} is carried whenever the key holds a value, and is validated
     * to lie between {@code 0} and {@value #MAXIMUM_TEMPERATURE} inclusive; setting the key blank
     * keeps the parameter off the request, which is what a model that refuses an explicit temperature
     * requires — DL-200. No stop parameter is set, expressing the {@code stop=None} argument at
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
                .n(requireExactlyOne(openai.n(), "scanner.openai.n"));          // :L23 — DL-267
        // :L24 stop=None — no stop parameter is set.

        Optional<ReasoningEffort> effort = resolveReasoningEffort();
        effort.ifPresent(builder::reasoningEffort);

        Double temperature = openai.temperature();
        if (temperature != null) {
            if (!Double.isFinite(temperature) || temperature < 0.0d
                    || temperature > MAXIMUM_TEMPERATURE) {
                throw new IllegalStateException(TEMPERATURE_KEY + " must lie between 0 and "
                        + MAXIMUM_TEMPERATURE + " inclusive.");
            }
            requireTemperatureIsAccepted(effort);
            builder.temperature(temperature);                                   // :L25
        }

        return builder.build();
    }

    // Net-new bound on scanner.openai.n — DL-267 — see docs/DECISION_LOG.md
    /**
     * Validates the configured number of choices.
     *
     * <p>{@link #firstChoiceContent(ChatCompletion)} consumes the first choice and no other, matching
     * the {@code choices[0]} index at {@code backend/app/services/llm_service.py:L29}. Any value other
     * than one is rejected before the request is issued — see docs/DECISION_LOG.md DL-267.
     *
     * @param value the configured value
     * @param key   the configuration key the value binds from; named in the failure message
     * @return {@code value}, guaranteed to be exactly one
     * @throws IllegalStateException when {@code value} is not one
     */
    private static long requireExactlyOne(long value, String key) {
        if (value != 1L) {
            throw new IllegalStateException(key + " must be exactly 1, because only the first choice "
                    + "is consumed; it is " + value + ".");
        }
        return value;
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
                    throw new IllegalStateException(DESTROYED_MESSAGE);
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
     * <p>Runs on the container's destruction callback. The bean is marked destroyed first, so a
     * request arriving during the shutdown is rejected before it queues; the method then waits up to
     * {@value #SHUTDOWN_AWAIT_SECONDS} seconds for a completion already in flight, and releases the
     * client once it has returned. A completion still in flight at that bound, and an interrupt while
     * waiting, are each reported at {@code WARN} and the client is released anyway — DL-266.
     *
     * <p>The client field is read and written inside the same monitor {@link #openAiClient()} uses, so
     * a client created before destruction is closed exactly once and {@link #openAiClient()} creates no
     * replacement afterwards — see docs/DECISION_LOG.md DL-085.
     *
     * <p>A failure raised while closing is reported at {@code WARN} by the failure's type and does not
     * propagate. The field is cleared whether or not closing succeeded.
     */
    // Net-new (no Python counterpart: the source assigned a module-level credential at
    // backend/app/services/llm_service.py:L9 and held no client) — DL-085 — see
    // docs/DECISION_LOG.md
    @PreDestroy
    public void closeOpenAiClient() {
        // Closing is marked before the wait, so a request arriving during it is rejected and is never
        // started — DL-266 — see docs/DECISION_LOG.md
        markDestroyed();

        Lock exclusive = activeUseLock.writeLock();
        boolean drained = false;
        try {
            drained = exclusive.tryLock(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
            if (!drained) {
                log.warn("A generation was still in flight after {}s; releasing the OpenAI API client "
                        + "anyway", SHUTDOWN_AWAIT_SECONDS);
            }
            releaseClient();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Awaiting an in-flight generation was interrupted; releasing the OpenAI API "
                    + "client");
            releaseClient();
        } finally {
            if (drained) {
                exclusive.unlock();
            }
        }
    }

    /**
     * Sets the destroyed flag.
     *
     * <p>The flag is written inside the same monitor {@link #openAiClient()} reads it in, so a
     * concurrent {@link #openAiClient()} either creates the client before the flag is set — in which
     * case {@link #releaseClient()}, which enters the same monitor afterwards, closes it — or observes
     * the flag and creates nothing — DL-266.
     */
    private void markDestroyed() {
        synchronized (this) {
            this.destroyed = true;
        }
    }

    /**
     * Closes the OpenAI client if one was created and clears the field.
     *
     * <p>The field is read and written inside the same monitor {@link #openAiClient()} uses, so a
     * client created before destruction is closed exactly once. A failure raised while closing is
     * reported at {@code WARN} by the failure's type and does not propagate — DL-266.
     */
    private void releaseClient() {
        OpenAIClient local;
        synchronized (this) {
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
     * <p>The template around the interpolated values is the source's, character for character, and the
     * post body is interpolated into it verbatim: no character is folded, escaped or cut, and a
     * {@code null} body interpolates as the empty string — see docs/DECISION_LOG.md DL-035. The
     * {@code Context:} value comes from {@link #buildContext(TweetDto)}, which is bounded — DL-265.
     *
     * @param tweet the post to reply to; must not be {@code null}
     * @return the prompt carried as the single user message, never {@code null}
     */
    // Ported from backend/app/services/llm_service.py:L16 (faithful port: the template and the
    // verbatim interpolation of the post body) — see docs/DECISION_LOG.md DL-035
    private String buildPrompt(TweetDto tweet) {
        String content = tweet.content();
        return PROMPT_PREFIX
                + (content == null ? "" : content)
                + PROMPT_CONTEXT_SEPARATOR
                + buildContext(tweet)
                + PROMPT_SUFFIX;
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
        // The names originate in stream rule tags, so they are bounded and folded exactly as the post
        // body is — DL-265 — see docs/DECISION_LOG.md
        String toolNames = (aiToolsMentioned == null || aiToolsMentioned.isEmpty())
                ? NO_AI_TOOLS
                : boundedToolNames(aiToolsMentioned);

        Double doubtRating = tweet.doubtRating();
        String rating = (doubtRating != null && Double.isFinite(doubtRating))
                ? Double.toString(doubtRating)
                : UNKNOWN_DOUBT_RATING;

        return CONTEXT_TOOLS_PREFIX + toolNames + CONTEXT_RATING_PREFIX + rating;
    }

    // Net-new bound on the Context: value — DL-265 — see docs/DECISION_LOG.md
    /**
     * Joins the AI tool names carried into the prompt, bounded and folded.
     *
     * <p>At most {@value #PROMPT_AI_TOOL_LIMIT} names are joined with {@value #AI_TOOL_DELIMITER} in
     * list order. Every line break within a name is folded to a space so no name can introduce a line
     * of its own into the prompt structure. The joined value is cut to
     * {@value #PROMPT_CONTEXT_LIMIT} characters and marked with {@value #BODY_TRUNCATION_MARK} when it
     * is longer; this segment carries at most that many characters whatever the row holds.
     *
     * <p>{@link TweetDto} drops a {@code null} element from its list components, so every name here is
     * present.
     *
     * @param aiToolsMentioned the names the row carries, never {@code null} and never empty
     * @return the joined names, never {@code null} and never empty
     */
    private static String boundedToolNames(List<String> aiToolsMentioned) {
        StringBuilder joined = new StringBuilder();
        int carried = 0;
        for (String name : aiToolsMentioned) {
            if (carried >= PROMPT_AI_TOOL_LIMIT) {
                break;
            }
            if (carried > 0) {
                joined.append(AI_TOOL_DELIMITER);
            }
            joined.append(name.replace('\r', ' ').replace('\n', ' '));
            carried++;
        }

        if (joined.length() <= PROMPT_CONTEXT_LIMIT) {
            return joined.toString();
        }
        return joined.substring(0, PROMPT_CONTEXT_LIMIT) + BODY_TRUNCATION_MARK;
    }

    /**
     * Extracts and trims the text of the first choice the response carries.
     *
     * <p>Choices past the first are ignored, matching the {@code choices[0]} index at
     * {@code backend/app/services/llm_service.py:L29}.
     *
     * <p>Content decides. Non-blank content of the first choice is returned whatever finish reason
     * accompanies it, including a reply the model cut short at the token cap, matching the source's
     * {@code choices[0].text.strip()}. A finish reason other than
     * {@code stop} accompanying accepted content is recorded once at {@code WARN} under
     * {@value #INCOMPLETE_PREFIX} followed by that reason, which is an enumerated provider token
     * rendered through {@link LogSafe#logSafe(String)} — DL-197, DL-202.
     *
     * <p>Three outcomes carry no usable content. Each is reported at {@code WARN} under a fixed
     * unusable-output code and raised as an {@link IllegalStateException} whose message is that code
     * — see docs/DECISION_LOG.md DL-083 and DL-202:
     *
     * <ul>
     *   <li>{@value #NO_CHOICE} — the response carries no choice.</li>
     *   <li>{@value #REFUSAL} — the first choice carries no usable content and carries a non-blank
     *       refusal. The refusal text is model output and is never logged.</li>
     *   <li>{@value #BLANK_TEXT} — the first choice carries no content, or content that is blank once
     *       trimmed, and carries no refusal.</li>
     * </ul>
     *
     * @param completion the Chat Completions response; must not be {@code null}
     * @return the trimmed generated text, never {@code null} and never blank
     * @throws IllegalStateException if the response carries no usable reply; the message is the fixed
     *                               unusable-output code
     */
    // Ported from backend/app/services/llm_service.py:L29 (faithful port); the refusal and
    // finish-reason states are net-new, the source's completions response carried neither field —
    // see docs/DECISION_LOG.md DL-032, DL-083 and DL-202
    private String firstChoiceContent(ChatCompletion completion) {
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices == null || choices.isEmpty()) {
            throw unusableOutput(NO_CHOICE, "the response carried no choice");
        }
        ChatCompletion.Choice choice = choices.get(0);

        String content = choice.message().content().orElse(null);
        String trimmed = (content == null) ? "" : content.trim();

        if (!trimmed.isEmpty()) {
            reportIncompleteFinish(choice.finishReason());
            return trimmed;
        }

        String refusal = choice.message().refusal().orElse(null);
        if (refusal != null && !refusal.isBlank()) {
            throw unusableOutput(REFUSAL,
                    "the first choice carried a refusal and no usable content");
        }
        if (content == null) {
            throw unusableOutput(BLANK_TEXT, "the first choice carried no content");
        }
        throw unusableOutput(BLANK_TEXT, "the first choice carried blank content");
    }

    /**
     * Records a finish reason other than {@code stop} that accompanied accepted content.
     *
     * <p>Nothing is recorded for {@code stop} and nothing is recorded for an absent reason. The reply
     * text is never logged — DL-197, DL-202.
     *
     * @param finishReason the reason the first choice reported, possibly {@code null}
     */
    private void reportIncompleteFinish(ChatCompletion.Choice.FinishReason finishReason) {
        if (finishReason == null || ChatCompletion.Choice.FinishReason.STOP.equals(finishReason)) {
            return;
        }
        log.warn("The Chat Completions reply is accepted with an incomplete finish [{}{}]",
                INCOMPLETE_PREFIX, LogSafe.logSafe(finishReason.asString()));
    }

    /**
     * Renders one provider-controlled member of a rejection for a log record.
     *
     * <p>The rendering is guarded twice. {@link LogSafe#logSafe(String)} first replaces every
     * character outside printable ASCII — which includes the carriage return and line feed a forged
     * record boundary needs — and bounds the value at 64 characters. The bounded rendering is then
     * held to {@link #GUARDED_MEMBER_SHAPE}: a member the provider publishes for a machine to read
     * carries no intra-value spacing, so a rendering that does is free text and is reported as
     * {@value #ABSENT} and is not carried into a record. An empty {@link Optional} is likewise
     * rendered as {@value #ABSENT}.
     *
     * @param providerValue the member the provider supplied, possibly empty
     * @return the guarded rendering; never {@code null}
     */
    // Log-injection guard plus shape check applied to every provider-supplied value — DL-084,
    // DL-119, DL-149 — see docs/DECISION_LOG.md
    private static String guarded(Optional<String> providerValue) {
        String value = providerValue.orElse(null);
        if (value == null) {
            return ABSENT;
        }
        String rendered = LogSafe.logSafe(value);
        return GUARDED_MEMBER_SHAPE.matcher(rendered).matches() ? rendered : ABSENT;
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

    // Net-new guard: the two request members cannot be sent together — DL-267 — see
    // docs/DECISION_LOG.md
    /**
     * Refuses a configuration that carries {@code temperature} alongside a reasoning effort the model
     * does not accept it with.
     *
     * @param effort the resolved reasoning effort, never {@code null}
     * @throws IllegalStateException when an effort other than {@code none} is carried, naming both
     *     configuration keys and echoing neither value
     */
    private static void requireTemperatureIsAccepted(Optional<ReasoningEffort> effort) {
        ReasoningEffort carried = effort.orElse(null);
        if (carried != null && carried.value() != ReasoningEffort.Value.NONE) {
            throw new IllegalStateException(TEMPERATURE_KEY + " cannot be sent while "
                    + REASONING_EFFORT_KEY + " names an effort other than none; set "
                    + REASONING_EFFORT_KEY + " to none or leave " + TEMPERATURE_KEY + " blank.");
        }
    }

    /**
     * Reads {@code scanner.openai.reasoning-effort} and validates it against the values the SDK
     * recognises.
     *
     * <p>A blank or absent value omits the parameter. A value outside
     * {@link #ACCEPTED_REASONING_EFFORT_VALUES} is rejected before the request is built, naming the
     * key and the accepted set but never echoing the configured value; the SDK itself neither
     * rejects an unrecognised value, which it carries as {@code _UNKNOWN}, nor rejects
     * {@code minimal}, which it recognises but the configured model does not accept.
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
        if (!ACCEPTED_REASONING_EFFORT_VALUES.contains(effort.value())) {
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
