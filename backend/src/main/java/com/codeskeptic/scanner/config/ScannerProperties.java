package com.codeskeptic.scanner.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Ported from backend/app/core/config.py:L4-15 (faithful port) — see docs/DECISION_LOG.md.
// Seven keys were declared by the source Settings class at :L5-11; the other eight were read by the
// source without ever being declared — see docs/DECISION_LOG.md DL-031.
/**
 * Bound configuration root for the backend service, replacing the Pydantic
 * {@code Settings(BaseSettings)} class declared at {@code backend/app/core/config.py:L4-15}.
 *
 * <p>Values bind from {@code src/main/resources/application.yml} under the {@code scanner} prefix.
 * Spring's relaxed binding maps each kebab-case key onto the matching camelCase component of this
 * record and accepts the {@code SCREAMING_SNAKE} environment-variable form of the same key, which
 * replaces the {@code env_file = ".env"} convention at {@code backend/app/core/config.py:L13-15}.
 * This type carries no stereotype annotation and is reached by constructor injection, replacing the
 * {@code get_settings()} factory at {@code backend/app/core/config.py:L17-18}.
 *
 * <p>No Google Cloud credential key is declared, matching {@code LanguageServiceClient()} at
 * {@code backend/app/services/sentiment_analysis.py:L8}, which takes no argument. No
 * {@code spring}-prefixed or {@code server}-prefixed key is declared; those live in
 * {@code application.yml}.
 *
 * <p>This record and every nested group are immutable, hold no reference to mutable state and are
 * safe for concurrent use. {@link #toString()} and the {@code toString()} of every nested group
 * render every credential, principal name and external resource identifier as
 * {@code ***REDACTED***} — DL-052 — using the same marker whether the underlying value is
 * {@code null}, empty or populated. {@link Analytics} and {@link Ingestion} keep the
 * compiler-generated form.
 *
 * <p>See {@code docs/DECISION_LOG.md} DL-015, DL-016, DL-017, DL-020, DL-027, DL-031, DL-033,
 * DL-034, DL-042, DL-044, DL-046 and DL-052; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @param databaseUrl value of {@code scanner.database-url}, carried verbatim under the
 *     {@code scanner} prefix and consumed by {@link DatabaseUrlTranslator}
 * @param popularityThreshold value of {@code scanner.popularity-threshold}, default {@code 100}
 * @param responseGenerationDelaySeconds value of
 *     {@code scanner.response-generation-delay-seconds}, default {@code 60}
 * @param twitter the {@code scanner.twitter} group, never {@code null} when bound
 * @param notion the {@code scanner.notion} group, never {@code null} when bound
 * @param openai the {@code scanner.openai} group, never {@code null} when bound
 * @param jwt the {@code scanner.jwt} group, never {@code null} when bound
 * @param auth the {@code scanner.auth} group, never {@code null} when bound
 * @param analytics the {@code scanner.analytics} group, never {@code null} when bound
 * @param ingestion the {@code scanner.ingestion} group, never {@code null} when bound
 */
@ConfigurationProperties(prefix = "scanner")
public record ScannerProperties(

        // scanner.database-url — declared at backend/app/core/config.py:L9, read at
        // backend/app/db/database.py:L7 — DL-027
        String databaseUrl,

        // scanner.popularity-threshold — declared with the default 100 at
        // backend/app/core/config.py:L10, read at backend/app/services/twitter_service.py:L43
        @DefaultValue("100") int popularityThreshold,

        // scanner.response-generation-delay-seconds — declared with the default 60 at
        // backend/app/core/config.py:L11, read at backend/app/tasks/response_generation.py:L50
        @DefaultValue("60") long responseGenerationDelaySeconds,

        @DefaultValue Twitter twitter,

        @DefaultValue Notion notion,

        @DefaultValue Openai openai,

        @DefaultValue Jwt jwt,

        @DefaultValue Auth auth,

        @DefaultValue Analytics analytics,

        @DefaultValue Ingestion ingestion,

        @DefaultValue Background background) {

    // Credential redaction in toString() — DL-052 — see docs/DECISION_LOG.md
    /** Rendered in place of every credential, principal name and resource identifier. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * Renders this record with {@code databaseUrl} redacted and each nested group rendering itself —
     * DL-052.
     *
     * @return the record's components, with every credential, principal name and resource
     *     identifier redacted
     */
    @Override
    public String toString() {
        return "ScannerProperties[databaseUrl=" + REDACTED
                + ", popularityThreshold=" + popularityThreshold
                + ", responseGenerationDelaySeconds=" + responseGenerationDelaySeconds
                + ", twitter=" + twitter
                + ", notion=" + notion
                + ", openai=" + openai
                + ", jwt=" + jwt
                + ", auth=" + auth
                + ", analytics=" + analytics
                + ", ingestion=" + ingestion
                + "]";
    }

    // Ported from backend/app/core/config.py:L5-6,
    // backend/app/services/twitter_service.py:L12-13 and
    // backend/app/tasks/tweet_monitoring.py:L46-49 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.twitter} group: the X (Twitter) credential set. Per-component provenance is
     * recorded inline below.
     *
     * <p>{@code application.yml} resolves {@code api-secret-key}, {@code consumer-key} and
     * {@code consumer-secret} from the canonical pair through nested placeholder defaults — DL-031.
     * {@code access-token} and {@code access-token-secret} are the retained OAuth 1.0a user-context
     * components, declared with empty defaults in {@code application.yml} — DL-046.
     *
     * <p>{@code task/TweetStreamClient} reads {@code consumerKey} and {@code consumerSecret} for the
     * app-only client-credentials exchange — DL-046. The remaining five components are declared, and
     * every key the retired tree referenced resolves; the v2 read path signs nothing with them and no
     * production code reads them — DL-031.
     *
     * <p>Seven components of this group are credentials and every one of those is redacted by
     * {@link #toString()}. The eighth is not: {@code requestTimeoutSeconds} bounds the two short
     * request/response calls {@code task/TweetStreamClient} makes on the X API — the app-only token
     * exchange and the stream-rules calls — DL-230. The bound on the wait between two delivered
     * stream records is {@code scanner.ingestion.stream-idle-timeout-seconds} on {@link Ingestion},
     * which is the one property that binds {@code TWITTER_STREAM_IDLE_TIMEOUT_SECONDS} — DL-256.
     *
     * @param apiKey value of {@code scanner.twitter.api-key}
     * @param apiSecret value of {@code scanner.twitter.api-secret}
     * @param apiSecretKey value of {@code scanner.twitter.api-secret-key}
     * @param consumerKey value of {@code scanner.twitter.consumer-key}
     * @param consumerSecret value of {@code scanner.twitter.consumer-secret}
     * @param accessToken value of {@code scanner.twitter.access-token}
     * @param accessTokenSecret value of {@code scanner.twitter.access-token-secret}
     * @param requestTimeoutSeconds value of {@code scanner.twitter.request-timeout-seconds},
     *     default {@code 10}; bounds the token exchange and the stream-rules calls only
     */
    public record Twitter(

            // scanner.twitter.api-key — declared at backend/app/core/config.py:L5, read at
            // backend/app/services/twitter_service.py:L12
            String apiKey,

            // scanner.twitter.api-secret — declared at backend/app/core/config.py:L6; no Python
            // read site
            String apiSecret,

            // scanner.twitter.api-secret-key — read at
            // backend/app/services/twitter_service.py:L12, never declared — DL-031
            String apiSecretKey,

            // scanner.twitter.consumer-key — read at
            // backend/app/tasks/tweet_monitoring.py:L46, never declared — DL-031
            String consumerKey,

            // scanner.twitter.consumer-secret — read at
            // backend/app/tasks/tweet_monitoring.py:L47, never declared — DL-031
            String consumerSecret,

            // scanner.twitter.access-token — read at
            // backend/app/services/twitter_service.py:L13 and
            // backend/app/tasks/tweet_monitoring.py:L48, never declared — DL-031
            String accessToken,

            // scanner.twitter.access-token-secret — read at
            // backend/app/services/twitter_service.py:L13 and
            // backend/app/tasks/tweet_monitoring.py:L49, never declared — DL-031
            String accessTokenSecret,

            // scanner.twitter.request-timeout-seconds — net-new: the source set no timeout.
            // Bounds the app-only token exchange and the stream-rules calls only; the filtered
            // stream itself is not bounded. A value below one second is read as one second —
            // DL-230
            @DefaultValue("10") long requestTimeoutSeconds) {

        /**
         * Renders this group with all seven credentials redacted and the one bound in the clear —
         * DL-052.
         *
         * @return the group's components, every credential value redacted
         */
        @Override
        public String toString() {
            return "Twitter[apiKey=" + REDACTED
                    + ", apiSecret=" + REDACTED
                    + ", apiSecretKey=" + REDACTED
                    + ", consumerKey=" + REDACTED
                    + ", consumerSecret=" + REDACTED
                    + ", accessToken=" + REDACTED
                    + ", accessTokenSecret=" + REDACTED
                    + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                    + "]";
        }
    }

    // Ported from backend/app/core/config.py:L7 and
    // backend/app/services/notion_service.py:L8,L24,L35 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.notion} group: the Notion API credential, the target database identifier,
     * the API version header value and the two transport timeouts. Per-component provenance is
     * recorded inline below.
     *
     * <p>The credential and the database identifier are redacted by {@link #toString()}.
     *
     * <p>{@code config/RestClientConfig} reads {@code apiVersion} for the {@code Notion-Version}
     * header, and {@code connectTimeoutSeconds} and {@code readTimeoutSeconds} for the request
     * factory, as it publishes the transport — DL-150, DL-151.
     *
     * @param apiKey value of {@code scanner.notion.api-key}
     * @param databaseId value of {@code scanner.notion.database-id}
     * @param apiVersion value of {@code scanner.notion.api-version}, default {@code 2022-06-28}
     * @param connectTimeoutSeconds value of {@code scanner.notion.connect-timeout-seconds}, default
     *     {@code 5}
     * @param readTimeoutSeconds value of {@code scanner.notion.read-timeout-seconds}, default
     *     {@code 10}
     * @param mirrorMaxRetries value of {@code scanner.notion.mirror-max-retries}, default {@code 2}:
     *     retries a mirror write makes after its first attempt; a negative value is read as {@code 0}
     * @param mirrorRetryBackoffMillis value of {@code scanner.notion.mirror-retry-backoff-millis},
     *     default {@code 500}: the wait before the first retry, doubled before each later one; a value
     *     below {@code 0} is read as {@code 0}
     */
    public record Notion(

            // scanner.notion.api-key — declared at backend/app/core/config.py:L7, read at
            // backend/app/services/notion_service.py:L8
            String apiKey,

            // scanner.notion.database-id — read at backend/app/services/notion_service.py:L24 and
            // backend/app/services/notion_service.py:L35, never declared
            String databaseId,

            // scanner.notion.api-version — net-new: notion_client carried its own pinned version
            // (backend/app/services/notion_service.py:L8 set none) — DL-151
            @DefaultValue("2022-06-28") String apiVersion,

            // scanner.notion.connect-timeout-seconds — net-new: the source set no timeout — DL-150
            @DefaultValue("5") long connectTimeoutSeconds,

            // scanner.notion.read-timeout-seconds — net-new: the source set no timeout — DL-150
            @DefaultValue("10") long readTimeoutSeconds,

            // scanner.notion.mirror-max-retries — net-new: the source retried nothing — DL-253
            @DefaultValue("2") int mirrorMaxRetries,

            // scanner.notion.mirror-retry-backoff-millis — net-new — DL-253
            @DefaultValue("500") long mirrorRetryBackoffMillis) {

        /**
         * Normalises the two retry components into usable values — DL-253.
         *
         * <p>A negative retry count is read as {@code 0} and a negative backoff as {@code 0}: such a
         * value disables the retry and does not fail startup.
         */
        public Notion {
            mirrorMaxRetries = Math.max(mirrorMaxRetries, 0);
            mirrorRetryBackoffMillis = Math.max(mirrorRetryBackoffMillis, 0L);
        }

        /**
         * Renders this group with the credential and the database identifier redacted — DL-052.
         *
         * @return the group's components, the credential and the database identifier redacted
         */
        @Override
        public String toString() {
            return "Notion[apiKey=" + REDACTED
                    + ", databaseId=" + REDACTED
                    + ", apiVersion=" + apiVersion
                    + ", connectTimeoutSeconds=" + connectTimeoutSeconds
                    + ", readTimeoutSeconds=" + readTimeoutSeconds
                    + ", mirrorMaxRetries=" + mirrorMaxRetries
                    + ", mirrorRetryBackoffMillis=" + mirrorRetryBackoffMillis
                    + "]";
        }
    }

    // Ported from backend/app/core/config.py:L8 and
    // backend/app/services/llm_service.py:L9,L20-26 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.openai} group: the OpenAI credential, the four call parameters and the three
     * transport and reasoning settings. Per-component provenance is recorded inline below.
     *
     * <p>The three numeric call parameters carry the literals passed to
     * {@code Completion.create(...)} at {@code backend/app/services/llm_service.py:L22-25} as their
     * defaults: {@code max_tokens=150} (DL-034, DL-202), {@code n=1} and {@code temperature=0.7}
     * (DL-200). {@code temperature} stays nullable, so a deployment that supplies a blank value omits
     * the parameter from the request.
     *
     * <p>{@code service/LlmService} reads every component of this group.
     * {@code maxCompletionTokens}, {@code n}, {@code temperature}, {@code requestTimeoutSeconds} and
     * {@code maxRetries} are range-checked there, and {@code reasoningEffort} is carried on the
     * request when it is not blank — DL-145, DL-146, DL-200, DL-201.
     *
     * <p>{@code service/LlmService} validates {@code reasoningEffort},
     * {@code requestTimeoutSeconds} and {@code maxRetries} on the path that creates the client and
     * builds the request — DL-145, DL-146, DL-201.
     *
     * @param apiKey value of {@code scanner.openai.api-key}, redacted by {@link #toString()}
     * @param model value of {@code scanner.openai.model}
     * @param maxCompletionTokens value of {@code scanner.openai.max-completion-tokens}, default
     *     {@code 150}
     * @param temperature value of {@code scanner.openai.temperature}, default {@code 0.7}, or
     *     {@code null} when the key is set to a blank value
     * @param n value of {@code scanner.openai.n}, default {@code 1}
     * @param reasoningEffort value of {@code scanner.openai.reasoning-effort}, default
     *     {@code none}; sent as the request's reasoning effort, and blank omits the parameter from
     *     the request
     * @param requestTimeoutSeconds value of {@code scanner.openai.request-timeout-seconds}, default
     *     {@code 30}; accepted range 1 second or greater, checked by
     *     {@code service/LlmService} on first use — see docs/DECISION_LOG.md DL-146, DL-201
     * @param maxRetries value of {@code scanner.openai.max-retries}, default {@code 2}; accepted range
     *     0 or greater, checked by {@code service/LlmService} on first use — see
     *     docs/DECISION_LOG.md DL-146, DL-201
     */
    public record Openai(

            // scanner.openai.api-key — declared at backend/app/core/config.py:L8, read as
            // settings.openai_api_key at backend/app/services/llm_service.py:L9
            String apiKey,

            // scanner.openai.model — replaces engine="text-davinci-002" at
            // backend/app/services/llm_service.py:L20 — DL-033
            String model,

            // scanner.openai.max-completion-tokens — max_tokens=150 at
            // backend/app/services/llm_service.py:L22 — DL-034, DL-202
            @DefaultValue("150") long maxCompletionTokens,

            // scanner.openai.temperature — temperature=0.7 at
            // backend/app/services/llm_service.py:L25; nullable so a blank value omits the
            // parameter — DL-200
            @DefaultValue("0.7") Double temperature,

            // scanner.openai.n — n=1 at backend/app/services/llm_service.py:L23
            @DefaultValue("1") long n,

            // scanner.openai.reasoning-effort — net-new: the source's completions call at
            // backend/app/services/llm_service.py:L19-26 had no reasoning parameter. Read by
            // service/LlmService.reasoningEffort() — DL-145
            @DefaultValue("none") String reasoningEffort,

            // scanner.openai.request-timeout-seconds — net-new: the source set no timeout.
            // Accepted range: 1 second or greater — DL-146, DL-201
            @DefaultValue("30") long requestTimeoutSeconds,

            // scanner.openai.max-retries — net-new: the source set no retry policy.
            // Accepted range: 0 or greater; 0 disables retrying — DL-146, DL-201
            @DefaultValue("2") int maxRetries) {

        /**
         * Renders this group with {@code apiKey} redacted — DL-052.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Openai[apiKey=" + REDACTED
                    + ", model=" + model
                    + ", maxCompletionTokens=" + maxCompletionTokens
                    + ", temperature=" + temperature
                    + ", n=" + n
                    + ", reasoningEffort=" + reasoningEffort
                    + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                    + ", maxRetries=" + maxRetries
                    + "]";
        }
    }

    // Ported from backend/app/core/security.py:L6-12 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.jwt} group: the signing secret, the algorithm name and the token lifetime.
     * Per-component provenance is recorded inline below.
     *
     * <p>{@code application.yml} declares {@code secret} as a placeholder with no default — DL-016 —
     * and {@code algorithm} with the default {@code HS256} — DL-015.
     *
     * <p>Binding accepts any value each component's type admits; {@code security/JwtService}
     * validates all three as it is constructed, so an algorithm other than {@code HS256} — DL-015,
     * DL-108, DL-184 — a secret shorter than 32 bytes — DL-186 — or a lifetime outside 1 … 60
     * minutes — DL-142 — fails startup.
     *
     * @param secret value of {@code scanner.jwt.secret}, redacted by {@link #toString()}
     * @param algorithm value of {@code scanner.jwt.algorithm}, {@code HS256} in any letter case
     * @param expirationMinutes value of {@code scanner.jwt.expiration-minutes}, default {@code 60},
     *     accepted range 1 … 60
     */
    public record Jwt(

            // scanner.jwt.secret — read at backend/app/core/security.py:L11, never declared —
            // DL-016
            String secret,

            // scanner.jwt.algorithm — read at backend/app/core/security.py:L11, never declared —
            // DL-015
            String algorithm,

            // scanner.jwt.expiration-minutes — replaces the expires_delta argument at
            // backend/app/core/security.py:L6,L9-10 — DL-017
            @DefaultValue("60") long expirationMinutes) {

        /**
         * Renders this group with {@code secret} redacted — DL-052.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Jwt[secret=" + REDACTED
                    + ", algorithm=" + algorithm
                    + ", expirationMinutes=" + expirationMinutes
                    + "]";
        }
    }

    // Net-new (no Python counterpart) — DL-020 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.auth} group: the single application principal.
     *
     * <p>{@code password-hash} holds a bcrypt hash, never a plaintext password. The hashing
     * counterpart in the retired tree was {@code backend/app/core/security.py:L14-18}.
     *
     * <p>Both components are redacted by {@link #toString()}.
     *
     * @param username value of {@code scanner.auth.username}
     * @param passwordHash value of {@code scanner.auth.password-hash}
     */
    public record Auth(

            // scanner.auth.username — no Python counterpart — DL-020
            String username,

            // scanner.auth.password-hash — bcrypt hash; the hashing counterpart is
            // backend/app/core/security.py:L14-18 — DL-020
            String passwordHash) {

        /**
         * Renders this group with the principal name and the password hash redacted — DL-052.
         *
         * @return the group's components, both values redacted
         */
        @Override
        public String toString() {
            return "Auth[username=" + REDACTED
                    + ", passwordHash=" + REDACTED
                    + "]";
        }
    }

    // Net-new (no Python counterpart) — DL-042 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.analytics} group: the observation window of the trend series, whose producing
     * method {@code get_trends()} at {@code backend/app/api/analytics.py:L14} takes no argument.
     *
     * <p>This group carries no credential. Its {@code toString()} is the compiler-generated one.
     *
     * <p>{@code trendWindowDays} is a finite positive number of days: the canonical constructor
     * refuses a value below {@value #MINIMUM_TREND_WINDOW_DAYS} and a value above
     * {@value #MAXIMUM_TREND_WINDOW_DAYS}, and startup fails when the configured value is refused —
     * DL-247 — see docs/DECISION_LOG.md.
     *
     * @param trendWindowDays value of {@code scanner.analytics.trend-window-days}, default
     *     {@code 30}. It counts UTC calendar dates, not a rolling duration: a value of {@code n}
     *     observes the current UTC date and the {@code n - 1} UTC dates before it — DL-278
     */
    public record Analytics(

            // scanner.analytics.trend-window-days — no Python counterpart; get_trends() is
            // argument-less at backend/app/api/analytics.py:L14 — DL-042, DL-247
            @DefaultValue("30") int trendWindowDays) {

        /** Smallest accepted {@code scanner.analytics.trend-window-days} — DL-247. */
        public static final int MINIMUM_TREND_WINDOW_DAYS = 1;

        /**
         * Largest accepted {@code scanner.analytics.trend-window-days}, one hundred years — DL-247.
         */
        public static final int MAXIMUM_TREND_WINDOW_DAYS = 36_500;

        /**
         * Refuses an observation window that is not a finite positive number of days — DL-247.
         *
         * @throws IllegalStateException when {@code trendWindowDays} is below
         *     {@value #MINIMUM_TREND_WINDOW_DAYS} or above {@value #MAXIMUM_TREND_WINDOW_DAYS}
         */
        public Analytics {
            if (trendWindowDays < MINIMUM_TREND_WINDOW_DAYS
                    || trendWindowDays > MAXIMUM_TREND_WINDOW_DAYS) {
                throw new IllegalStateException(
                        "scanner.analytics.trend-window-days must be between "
                                + MINIMUM_TREND_WINDOW_DAYS + " and " + MAXIMUM_TREND_WINDOW_DAYS
                                + " days; it is " + trendWindowDays + ".");
            }
        }
    }

    // Net-new (no Python counterpart) — DL-044 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.ingestion} group: the base terms of the stream rule set and the two bounds
     * the ingestion cycle applies. The retired task passed an empty list to
     * {@code stream.filter(track=keywords)} at {@code backend/app/tasks/tweet_monitoring.py:L53-55}.
     *
     * <p>The terms {@code application.yml} configures are the base of the rule set only.
     *
     * <p>{@code maxStreamRules} is the number of rules the account tier accepts; the composed term
     * collection is truncated to it — DL-254. {@code streamIdleTimeoutSeconds} is the span without
     * any byte from the filtered-stream connection after which the connection is treated as dead —
     * DL-256.
     *
     * <p>This group carries no credential. Its {@code toString()} is the compiler-generated one.
     *
     * @param streamBaseKeywords value of {@code scanner.ingestion.stream-base-keywords}; an
     *     unmodifiable copy of the configured sequence, empty when the key is absent, never
     *     {@code null}
     * @param maxStreamRules value of {@code scanner.ingestion.max-stream-rules}, default {@code 25};
     *     read as {@code 1} when the bound value is below {@code 1}
     * @param streamIdleTimeoutSeconds value of
     *     {@code scanner.ingestion.stream-idle-timeout-seconds}, default {@code 60}; read as
     *     {@code 1} when the bound value is below {@code 1}
     */
    public record Ingestion(

            // scanner.ingestion.stream-base-keywords — no Python counterpart; the keyword list was
            // empty at backend/app/tasks/tweet_monitoring.py:L53-55 — DL-044
            @DefaultValue List<String> streamBaseKeywords,

            // scanner.ingestion.max-stream-rules — no Python counterpart — DL-254
            @DefaultValue("25") int maxStreamRules,

            // scanner.ingestion.stream-idle-timeout-seconds — no Python counterpart — DL-256
            @DefaultValue("60") long streamIdleTimeoutSeconds) {

        /** Smallest accepted value of {@code scanner.ingestion.max-stream-rules} — DL-254. */
        private static final int MINIMUM_MAX_STREAM_RULES = 1;

        /**
         * Smallest accepted value of {@code scanner.ingestion.stream-idle-timeout-seconds} — DL-256.
         */
        private static final long MINIMUM_STREAM_IDLE_TIMEOUT_SECONDS = 1L;

        /**
         * Normalises the bound sequence into an unmodifiable copy and both bounds into usable values
         * — DL-044, DL-254, DL-256.
         *
         * <p>A {@code null} sequence becomes an empty list. A bound sequence is copied element by
         * element, the copy tolerates {@code null} elements, and a later change to the source
         * sequence leaves this record unchanged.
         *
         * <p>A rule bound below {@value #MINIMUM_MAX_STREAM_RULES} is read as
         * {@value #MINIMUM_MAX_STREAM_RULES}, and an idle bound below
         * {@value #MINIMUM_STREAM_IDLE_TIMEOUT_SECONDS} seconds as
         * {@value #MINIMUM_STREAM_IDLE_TIMEOUT_SECONDS} seconds, so a misconfiguration cannot
         * suppress the rule set or reconnect the stream continuously.
         */
        public Ingestion {
            streamBaseKeywords = (streamBaseKeywords == null)
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(streamBaseKeywords));
            maxStreamRules = Math.max(maxStreamRules, MINIMUM_MAX_STREAM_RULES);
            streamIdleTimeoutSeconds =
                    Math.max(streamIdleTimeoutSeconds, MINIMUM_STREAM_IDLE_TIMEOUT_SECONDS);
        }
    }

    // Net-new (no Python counterpart: backend/app/main.py:L43-48 started both background paths in
    // every process) — DL-250 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.background} group: which background paths this process runs.
     *
     * <p>{@code enabled} governs both background paths together, and each path also carries its own
     * switch. A path runs only when {@code enabled} and that path's own switch are both {@code true};
     * a process for which either is {@code false} starts that path in no way — see
     * docs/DECISION_LOG.md DL-250.
     *
     * <p>All three default to {@code true}, so a single-process deployment runs both paths without
     * configuring anything. {@code streamEnabled} binds the {@code TWITTER_STREAM_ENABLED} environment
     * variable.
     *
     * <p>This group carries no credential. Its {@code toString()} is the compiler-generated one.
     *
     * @param enabled                   value of {@code scanner.background.enabled}, default
     *     {@code true}: whether this process runs any background path at all
     * @param streamEnabled             value of {@code scanner.background.stream-enabled}, default
     *     {@code true}: whether this process runs the X filtered stream
     * @param responseGenerationEnabled value of
     *     {@code scanner.background.response-generation-enabled}, default {@code true}: whether this
     *     process runs the scheduled response-generation pass
     */
    public record Background(

            // scanner.background.enabled — no Python counterpart — DL-250
            @DefaultValue("true") boolean enabled,

            // scanner.background.stream-enabled — binds TWITTER_STREAM_ENABLED — DL-250
            @DefaultValue("true") boolean streamEnabled,

            // scanner.background.response-generation-enabled — no Python counterpart — DL-250
            @DefaultValue("true") boolean responseGenerationEnabled,

            // scanner.background.lease-ttl-seconds — no Python counterpart — DL-281
            @DefaultValue("120") long leaseTtlSeconds,

            // scanner.background.lease-renew-seconds — no Python counterpart — DL-281
            @DefaultValue("30") long leaseRenewSeconds,

            // scanner.background.max-candidates-per-pass — no Python counterpart — DL-282
            @DefaultValue("200") int maxCandidatesPerPass,

            // scanner.background.provider-calls-per-window — no Python counterpart — DL-283
            @DefaultValue("500") int providerCallsPerWindow,

            // scanner.background.provider-window-seconds — no Python counterpart — DL-283
            @DefaultValue("3600") long providerWindowSeconds,

            // scanner.background.provider-failure-threshold — no Python counterpart — DL-283
            @DefaultValue("5") int providerFailureThreshold,

            // scanner.background.provider-circuit-open-seconds — no Python counterpart — DL-283
            @DefaultValue("300") long providerCircuitOpenSeconds) {

        /** Declared default of {@code scanner.background.lease-ttl-seconds} — DL-281. */
        private static final long DEFAULT_LEASE_TTL_SECONDS = 120L;

        /** Declared default of {@code scanner.background.lease-renew-seconds} — DL-281. */
        private static final long DEFAULT_LEASE_RENEW_SECONDS = 30L;

        /** Declared default of {@code scanner.background.max-candidates-per-pass} — DL-282. */
        private static final int DEFAULT_MAX_CANDIDATES_PER_PASS = 200;

        /** Declared default of {@code scanner.background.provider-calls-per-window} — DL-283. */
        private static final int DEFAULT_PROVIDER_CALLS_PER_WINDOW = 500;

        /** Declared default of {@code scanner.background.provider-window-seconds} — DL-283. */
        private static final long DEFAULT_PROVIDER_WINDOW_SECONDS = 3_600L;

        /** Declared default of {@code scanner.background.provider-failure-threshold} — DL-283. */
        private static final int DEFAULT_PROVIDER_FAILURE_THRESHOLD = 5;

        /** Declared default of {@code scanner.background.provider-circuit-open-seconds} — DL-283. */
        private static final long DEFAULT_PROVIDER_CIRCUIT_OPEN_SECONDS = 300L;

        /** Smallest accepted value of {@code scanner.background.lease-ttl-seconds} — DL-281. */
        private static final long MINIMUM_LEASE_TTL_SECONDS = 10L;

        /** Largest accepted value of {@code scanner.background.lease-ttl-seconds} — DL-281. */
        private static final long MAXIMUM_LEASE_TTL_SECONDS = 3_600L;

        /** Smallest accepted value of {@code scanner.background.lease-renew-seconds} — DL-281. */
        private static final long MINIMUM_LEASE_RENEW_SECONDS = 1L;

        /** Divisor fixing the longest renewal interval as a fraction of the lease term — DL-281. */
        private static final long LEASE_RENEW_DIVISOR = 2L;

        /**
         * Smallest accepted value of {@code scanner.background.max-candidates-per-pass} — DL-282.
         */
        private static final int MINIMUM_MAX_CANDIDATES_PER_PASS = 1;

        /**
         * Smallest accepted value of {@code scanner.background.provider-calls-per-window} — DL-283.
         */
        private static final int MINIMUM_PROVIDER_CALLS_PER_WINDOW = 1;

        /** Smallest accepted value of {@code scanner.background.provider-window-seconds} — DL-283. */
        private static final long MINIMUM_PROVIDER_WINDOW_SECONDS = 1L;

        /**
         * Smallest accepted value of {@code scanner.background.provider-failure-threshold} — DL-283.
         */
        private static final int MINIMUM_PROVIDER_FAILURE_THRESHOLD = 1;

        /**
         * Smallest accepted value of {@code scanner.background.provider-circuit-open-seconds} —
         * DL-283.
         */
        private static final long MINIMUM_PROVIDER_CIRCUIT_OPEN_SECONDS = 1L;

        /**
         * Normalises every bound into a usable value — DL-281, DL-282, DL-283.
         *
         * <p>The lease term is held within {@value #MINIMUM_LEASE_TTL_SECONDS} and
         * {@value #MAXIMUM_LEASE_TTL_SECONDS} seconds. The renewal interval is held at
         * {@value #MINIMUM_LEASE_RENEW_SECONDS} second or more and at no more than the resulting term
         * divided by {@value #LEASE_RENEW_DIVISOR}, so a renewal always precedes an expiry and a
         * misconfiguration cannot let the term lapse between renewals. The per-pass candidate
         * ceiling, the provider call allowance, the window, the consecutive-failure threshold and the
         * span the circuit stays open each carry their own floor, so no bound can be configured away.
         */
        public Background {
            leaseTtlSeconds = Math.min(MAXIMUM_LEASE_TTL_SECONDS,
                    Math.max(leaseTtlSeconds, MINIMUM_LEASE_TTL_SECONDS));
            leaseRenewSeconds = Math.min(leaseTtlSeconds / LEASE_RENEW_DIVISOR,
                    Math.max(leaseRenewSeconds, MINIMUM_LEASE_RENEW_SECONDS));
            maxCandidatesPerPass =
                    Math.max(maxCandidatesPerPass, MINIMUM_MAX_CANDIDATES_PER_PASS);
            providerCallsPerWindow =
                    Math.max(providerCallsPerWindow, MINIMUM_PROVIDER_CALLS_PER_WINDOW);
            providerWindowSeconds =
                    Math.max(providerWindowSeconds, MINIMUM_PROVIDER_WINDOW_SECONDS);
            providerFailureThreshold =
                    Math.max(providerFailureThreshold, MINIMUM_PROVIDER_FAILURE_THRESHOLD);
            providerCircuitOpenSeconds =
                    Math.max(providerCircuitOpenSeconds, MINIMUM_PROVIDER_CIRCUIT_OPEN_SECONDS);
        }

        /**
         * Builds a group carrying the three switches and the declared default of every bound.
         *
         * <p>The bound values this factory writes are the {@code @DefaultValue} literals of the
         * components above, so a caller that holds no bound group — a hand-constructed
         * {@code ScannerProperties} or a null-guarded read — sees the same bounds a deployment sees
         * when it declares none. {@code config/MainProfileConfigurationContractTest} asserts the
         * agreement between this factory and the binder.
         *
         * @param enabled whether background work runs in this process at all
         * @param streamEnabled whether the X filtered stream runs in this process
         * @param responseGenerationEnabled whether the generation pass runs in this process
         * @return the group, never {@code null}
         */
        public static Background of(boolean enabled,
                boolean streamEnabled,
                boolean responseGenerationEnabled) {
            return new Background(enabled, streamEnabled, responseGenerationEnabled,
                    DEFAULT_LEASE_TTL_SECONDS, DEFAULT_LEASE_RENEW_SECONDS,
                    DEFAULT_MAX_CANDIDATES_PER_PASS, DEFAULT_PROVIDER_CALLS_PER_WINDOW,
                    DEFAULT_PROVIDER_WINDOW_SECONDS, DEFAULT_PROVIDER_FAILURE_THRESHOLD,
                    DEFAULT_PROVIDER_CIRCUIT_OPEN_SECONDS);
        }

        /**
         * Reports whether this process runs the X filtered stream.
         *
         * @return {@code true} when {@link #enabled()} and {@link #streamEnabled()} both hold
         */
        public boolean runsStream() {
            return enabled && streamEnabled;
        }

        /**
         * Reports whether this process runs the scheduled response-generation pass.
         *
         * @return {@code true} when {@link #enabled()} and {@link #responseGenerationEnabled()} both
         *         hold
         */
        public boolean runsResponseGeneration() {
            return enabled && responseGenerationEnabled;
        }
    }
}
