package com.codeskeptic.scanner.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Ported from backend/app/core/config.py:L4-15 (faithful port) — see docs/DECISION_LOG.md
/**
 * Bound configuration root for the backend service, replacing the Pydantic
 * {@code Settings(BaseSettings)} class declared at {@code backend/app/core/config.py:L4-15}.
 *
 * <p>Fifteen keys carried over from the retired Python tree are declared here. Seven were declared
 * by {@code Settings} itself at {@code backend/app/core/config.py:L5-11}. The remaining eight were
 * read by the Python code without ever being declared: {@code SECRET_KEY} and {@code ALGORITHM}
 * ({@code backend/app/core/security.py:L11}), {@code NOTION_DATABASE_ID}
 * ({@code backend/app/services/notion_service.py:L24} and {@code :L35}),
 * {@code TWITTER_API_SECRET_KEY} ({@code backend/app/services/twitter_service.py:L12}),
 * {@code TWITTER_ACCESS_TOKEN} and {@code TWITTER_ACCESS_TOKEN_SECRET}
 * ({@code backend/app/services/twitter_service.py:L13}), {@code TWITTER_CONSUMER_KEY} and
 * {@code TWITTER_CONSUMER_SECRET} ({@code backend/app/tasks/tweet_monitoring.py:L46-47}). Nine
 * further keys are declared under the decision-log identifiers named below.
 *
 * <p>Values bind from {@code src/main/resources/application.yml} under the {@code scanner} prefix.
 * Spring's relaxed binding maps each kebab-case key onto the matching camelCase component of this
 * record and accepts the {@code SCREAMING_SNAKE} environment-variable form of the same key, which
 * replaces the {@code env_file = ".env"} convention at {@code backend/app/core/config.py:L13-15}.
 *
 * <p>Registration comes from the {@code ConfigurationPropertiesScan} declared on
 * {@code com.codeskeptic.scanner.ScannerApplication}; this type carries no stereotype annotation.
 * The single bound instance is reached by constructor injection and replaces the
 * {@code get_settings()} factory at {@code backend/app/core/config.py:L17-18}, which returned a new
 * {@code Settings} object on every call.
 *
 * <p>Two property families are absent from this record. No Google Cloud credential key of any kind
 * is declared: {@code LanguageServiceClient()} at
 * {@code backend/app/services/sentiment_analysis.py:L8} takes no argument and authenticates with
 * Application Default Credentials. No {@code spring}-prefixed or {@code server}-prefixed key is
 * declared: those are declared by {@code application.yml} alone.
 *
 * <p>Usage, in a collaborator that needs the popularity gate's configured default and the OpenAI
 * model identifier:
 *
 * <pre>{@code
 * public class ExampleService {
 *
 *     private final ScannerProperties properties;
 *
 *     public ExampleService(ScannerProperties properties) {
 *         this.properties = properties;
 *     }
 *
 *     public boolean meetsThreshold(int likeCount) {
 *         return likeCount >= properties.popularityThreshold();
 *     }
 *
 *     public String model() {
 *         return properties.openai().model();
 *     }
 * }
 * }</pre>
 *
 * <p>This record and every nested group are immutable, hold no reference to mutable state and are
 * safe for concurrent use. {@link #toString()} and the {@code toString()} of every nested group
 * that carries a credential render that credential as {@code ***REDACTED***}; only
 * {@link Analytics} and {@link Ingestion} keep the compiler-generated form, neither carrying a
 * credential.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-015, DL-016,
 * DL-017, DL-020, DL-027, DL-031, DL-033, DL-034, DL-042, DL-044 and DL-058; construct-level
 * provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
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

        @DefaultValue Ingestion ingestion) {

    /** Rendered in place of any non-empty credential value. */
    private static final String REDACTED = "***REDACTED***";

    /** Rendered in place of a {@code null} value. */
    private static final String NULL_TEXT = "null";

    /** Rendered in place of an empty value. */
    private static final String EMPTY_TEXT = "\"\"";

    /**
     * Renders a credential-bearing value for {@code toString()}.
     *
     * @param value the configured value, which may be {@code null}
     * @return {@code null} for a {@code null} value, {@code ""} for an empty value, and
     *     {@code ***REDACTED***} for any other value
     */
    private static String redact(String value) {
        if (value == null) {
            return NULL_TEXT;
        }
        if (value.isEmpty()) {
            return EMPTY_TEXT;
        }
        return REDACTED;
    }

    /**
     * Renders this record with {@code databaseUrl} redacted and each nested group rendering itself.
     *
     * <p>A SQLAlchemy-style {@code DATABASE_URL} carries its credentials in its user-info component.
     * The nested groups that carry credentials redact them.
     *
     * @return the record's components, with every credential redacted
     */
    @Override
    public String toString() {
        return "ScannerProperties[databaseUrl=" + redact(databaseUrl)
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
     * The {@code scanner.twitter} group: the X (Twitter) credential set.
     *
     * <p>Two components carry keys {@code Settings} declared at
     * {@code backend/app/core/config.py:L5-6}. Five carry keys the Python code read without
     * declaring. {@code application.yml} resolves {@code api-secret-key}, {@code consumer-key} and
     * {@code consumer-secret} from the canonical pair through nested placeholder defaults — DL-031.
     *
     * <p>Every component of this group is a credential and every one is redacted by
     * {@link #toString()}.
     *
     * @param apiKey value of {@code scanner.twitter.api-key}
     * @param apiSecret value of {@code scanner.twitter.api-secret}
     * @param apiSecretKey value of {@code scanner.twitter.api-secret-key}
     * @param consumerKey value of {@code scanner.twitter.consumer-key}
     * @param consumerSecret value of {@code scanner.twitter.consumer-secret}
     * @param accessToken value of {@code scanner.twitter.access-token}
     * @param accessTokenSecret value of {@code scanner.twitter.access-token-secret}
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
            String accessTokenSecret) {

        /**
         * Renders this group with all seven credentials redacted.
         *
         * @return the group's components, every value redacted
         */
        @Override
        public String toString() {
            return "Twitter[apiKey=" + redact(apiKey)
                    + ", apiSecret=" + redact(apiSecret)
                    + ", apiSecretKey=" + redact(apiSecretKey)
                    + ", consumerKey=" + redact(consumerKey)
                    + ", consumerSecret=" + redact(consumerSecret)
                    + ", accessToken=" + redact(accessToken)
                    + ", accessTokenSecret=" + redact(accessTokenSecret)
                    + "]";
        }
    }

    // Ported from backend/app/core/config.py:L7 and
    // backend/app/services/notion_service.py:L8,L24,L35 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.notion} group: the Notion API credential and the target database
     * identifier.
     *
     * <p>{@code api-key} carries the key {@code Settings} declared at
     * {@code backend/app/core/config.py:L7}. {@code database-id} carries a key the Python code read
     * at two sites without ever declaring.
     *
     * @param apiKey value of {@code scanner.notion.api-key}, redacted by {@link #toString()}
     * @param databaseId value of {@code scanner.notion.database-id}
     */
    public record Notion(

            // scanner.notion.api-key — declared at backend/app/core/config.py:L7, read at
            // backend/app/services/notion_service.py:L8
            String apiKey,

            // scanner.notion.database-id — read at backend/app/services/notion_service.py:L24 and
            // backend/app/services/notion_service.py:L35, never declared
            String databaseId) {

        /**
         * Renders this group with {@code apiKey} redacted.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Notion[apiKey=" + redact(apiKey)
                    + ", databaseId=" + databaseId
                    + "]";
        }
    }

    // Ported from backend/app/core/config.py:L8 and
    // backend/app/services/llm_service.py:L9,L20-26 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.openai} group: the OpenAI credential and the four call parameters.
     *
     * <p>{@code api-key} carries the key {@code Settings} declared at
     * {@code backend/app/core/config.py:L8}, which the Python constructor read under the lowercase
     * name {@code settings.openai_api_key} at {@code backend/app/services/llm_service.py:L9}.
     *
     * <p>The three numeric components transcribe the literals passed to
     * {@code Completion.create(...)} at {@code backend/app/services/llm_service.py:L22-25}:
     * {@code max_tokens=150}, {@code n=1} and {@code temperature=0.7}. The {@code model} component
     * replaces the {@code engine="text-davinci-002"} literal at
     * {@code backend/app/services/llm_service.py:L20} — DL-033.
     *
     * @param apiKey value of {@code scanner.openai.api-key}, redacted by {@link #toString()}
     * @param model value of {@code scanner.openai.model}
     * @param maxCompletionTokens value of {@code scanner.openai.max-completion-tokens}, default
     *     {@code 150}
     * @param temperature value of {@code scanner.openai.temperature}, default {@code 0.7}
     * @param n value of {@code scanner.openai.n}, default {@code 1}
     */
    public record Openai(

            // scanner.openai.api-key — declared at backend/app/core/config.py:L8, read as
            // settings.openai_api_key at backend/app/services/llm_service.py:L9
            String apiKey,

            // scanner.openai.model — replaces engine="text-davinci-002" at
            // backend/app/services/llm_service.py:L20 — DL-033
            String model,

            // scanner.openai.max-completion-tokens — max_tokens=150 at
            // backend/app/services/llm_service.py:L22 — DL-034
            @DefaultValue("150") long maxCompletionTokens,

            // scanner.openai.temperature — temperature=0.7 at
            // backend/app/services/llm_service.py:L25
            @DefaultValue("0.7") double temperature,

            // scanner.openai.n — n=1 at backend/app/services/llm_service.py:L23
            @DefaultValue("1") long n) {

        /**
         * Renders this group with {@code apiKey} redacted.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Openai[apiKey=" + redact(apiKey)
                    + ", model=" + model
                    + ", maxCompletionTokens=" + maxCompletionTokens
                    + ", temperature=" + temperature
                    + ", n=" + n
                    + "]";
        }
    }

    // Ported from backend/app/core/security.py:L6-12 (faithful port) — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.jwt} group: the signing secret, the algorithm name and the token lifetime.
     *
     * <p>{@code secret} and {@code algorithm} carry the two keys
     * {@code encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)} read at
     * {@code backend/app/core/security.py:L11} without either ever being declared.
     * {@code application.yml} declares {@code secret} as a placeholder with no default — DL-016 —
     * and {@code algorithm} with the default {@code HS256} — DL-015.
     *
     * <p>{@code expiration-minutes} replaces the {@code expires_delta} argument of
     * {@code create_access_token(data, expires_delta)} at {@code backend/app/core/security.py:L6},
     * which {@code backend/app/core/security.py:L9-10} added to {@code datetime.utcnow()} — DL-017.
     *
     * @param secret value of {@code scanner.jwt.secret}, redacted by {@link #toString()}
     * @param algorithm value of {@code scanner.jwt.algorithm}
     * @param expirationMinutes value of {@code scanner.jwt.expiration-minutes}, default {@code 60}
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
         * Renders this group with {@code secret} redacted.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Jwt[secret=" + redact(secret)
                    + ", algorithm=" + algorithm
                    + ", expirationMinutes=" + expirationMinutes
                    + "]";
        }
    }

    // Net-new (no Python counterpart) — DL-020 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.auth} group: the single application principal.
     *
     * <p>No Python counterpart declared either key. The bcrypt helpers at
     * {@code backend/app/core/security.py:L14-18} were the only credential-verification code in the
     * retired tree and had no call site and no credential store — DL-020.
     *
     * <p>{@code password-hash} holds a bcrypt hash, never a plaintext password.
     *
     * @param username value of {@code scanner.auth.username}
     * @param passwordHash value of {@code scanner.auth.password-hash}, redacted by
     *     {@link #toString()}
     */
    public record Auth(

            // scanner.auth.username — no Python counterpart — DL-020
            String username,

            // scanner.auth.password-hash — bcrypt hash; the hashing counterpart is
            // backend/app/core/security.py:L14-18 — DL-020
            String passwordHash) {

        /**
         * Renders this group with {@code passwordHash} redacted.
         *
         * @return the group's components, with the credential redacted
         */
        @Override
        public String toString() {
            return "Auth[username=" + username
                    + ", passwordHash=" + redact(passwordHash)
                    + "]";
        }
    }

    // Net-new (no Python counterpart) — DL-042 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.analytics} group: the observation window of the trend series.
     *
     * <p>No Python counterpart declared this key. {@code get_trends()} at
     * {@code backend/app/api/analytics.py:L14} takes no argument, and this group is where the window
     * it spans is configured — DL-042.
     *
     * <p>This group carries no credential and keeps the compiler-generated {@code toString()}.
     *
     * @param trendWindowDays value of {@code scanner.analytics.trend-window-days}, default
     *     {@code 30}
     */
    public record Analytics(

            // scanner.analytics.trend-window-days — no Python counterpart; get_trends() is
            // argument-less at backend/app/api/analytics.py:L14 — DL-042
            @DefaultValue("30") int trendWindowDays) {
    }

    // Net-new (no Python counterpart) — DL-044 — see docs/DECISION_LOG.md
    /**
     * The {@code scanner.ingestion} group: the base terms of the stream rule set.
     *
     * <p>No Python counterpart declared this key. {@code stream_tweets} is documented in
     * {@code documentation/Code Structure.md} as taking a {@code List[str] keywords} parameter, and
     * the Python task passed an empty list to {@code stream.filter(track=keywords)} at
     * {@code backend/app/tasks/tweet_monitoring.py:L53-55} — DL-044.
     *
     * <p>The four terms {@code application.yml} configures are the base of the rule set only. The
     * union with the {@code ai_tools.name} rows and the {@code stream_keywords} setting-row override
     * belong to {@code com.codeskeptic.scanner.task.TweetStreamClient}.
     *
     * <p>This group carries no credential and keeps the compiler-generated {@code toString()}.
     *
     * @param streamBaseKeywords value of {@code scanner.ingestion.stream-base-keywords}; an
     *     unmodifiable copy of the configured sequence, empty when the key is absent, never
     *     {@code null}
     */
    public record Ingestion(

            // scanner.ingestion.stream-base-keywords — no Python counterpart; the keyword list was
            // empty at backend/app/tasks/tweet_monitoring.py:L53-55 — DL-044
            @DefaultValue List<String> streamBaseKeywords) {

        /**
         * Normalises the bound sequence into an unmodifiable copy.
         *
         * <p>A {@code null} sequence becomes an empty list. A bound sequence is copied element by
         * element, the copy tolerates {@code null} elements, and a later change to the source
         * sequence leaves this record unchanged.
         */
        public Ingestion {
            streamBaseKeywords = (streamBaseKeywords == null)
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(streamBaseKeywords));
        }
    }
}
