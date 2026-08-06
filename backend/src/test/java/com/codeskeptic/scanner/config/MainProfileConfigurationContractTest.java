package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

// Net-new (no Python counterpart) — DL-026, DL-027, DL-029, DL-030, DL-031 — see
// docs/DECISION_LOG.md
/**
 * Verifies the production {@code application.yml} contract through Spring's YAML loader,
 * placeholder resolver and configuration binder.
 */
@DisplayName("Main-profile configuration contract")
class MainProfileConfigurationContractTest {

    /** Values required to resolve every no-default placeholder in the production document. */
    private static final Map<String, Object> REQUIRED_ENVIRONMENT = Map.of(
            "DATABASE_URL", "postgresql://scanner:secret@db.internal:5432/codeskeptic",
            "SECRET_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "AUTH_PASSWORD_HASH", "$2a$10$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuu");

    @Test
    @DisplayName("binds the production server, servlet, schema and scanner defaults")
    void bindsTheProductionServerServletSchemaAndScannerDefaults() throws IOException {
        LoadedConfiguration loaded = load(REQUIRED_ENVIRONMENT);
        ServerProperties server = loaded.bind("server", ServerProperties.class);
        ScannerProperties scanner = loaded.bind("scanner", ScannerProperties.class);

        assertThat(server.getPort()).isEqualTo(5000);
        assertThat(server.getForwardHeadersStrategy())
                .isEqualTo(ForwardHeadersStrategy.FRAMEWORK);
        assertThat(server.getMaxHttpRequestHeaderSize()).isEqualTo(DataSize.ofKilobytes(8));
        assertThat(loaded.property("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(loaded.property("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(loaded.booleanProperty("spring.jpa.open-in-view")).isFalse();
        assertThat(loaded.booleanProperty("spring.jpa.properties.hibernate.auto_quote_keyword"))
                .isTrue();
        assertThat(loaded.booleanProperty("spring.jackson.parser.strict-duplicate-detection"))
                .isTrue();
        assertThat(scanner.popularityThreshold()).isEqualTo(100);
        assertThat(scanner.responseGenerationDelaySeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("binds environment overrides through the production placeholders")
    void bindsEnvironmentOverridesThroughTheProductionPlaceholders() throws IOException {
        Map<String, Object> environment = withRequired(
                "PORT", "6100",
                "FORWARD_HEADERS_STRATEGY", "native",
                "MAX_HTTP_REQUEST_HEADER_SIZE", "12KB",
                "TWEET_POPULARITY_THRESHOLD", "250",
                "RESPONSE_GENERATION_DELAY", "75");

        LoadedConfiguration loaded = load(environment);
        ServerProperties server = loaded.bind("server", ServerProperties.class);
        ScannerProperties scanner = loaded.bind("scanner", ScannerProperties.class);

        assertThat(server.getPort()).isEqualTo(6100);
        assertThat(server.getForwardHeadersStrategy()).isEqualTo(ForwardHeadersStrategy.NATIVE);
        assertThat(server.getMaxHttpRequestHeaderSize()).isEqualTo(DataSize.ofKilobytes(12));
        assertThat(scanner.popularityThreshold()).isEqualTo(250);
        assertThat(scanner.responseGenerationDelaySeconds()).isEqualTo(75L);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "scanner.database-url|${DATABASE_URL}",
        "scanner.jwt.secret|${SECRET_KEY}",
        "scanner.auth.password-hash|${AUTH_PASSWORD_HASH}"
    })
    @DisplayName("declares each startup-required value with no default")
    void declaresEachStartupRequiredValueWithNoDefault(String property, String placeholder)
            throws IOException {

        assertThat(rawProperty(property)).isEqualTo(placeholder);
        assertThat(placeholder).doesNotContain(":");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "scanner.jwt.secret|${SECRET_KEY}",
        "scanner.jwt.algorithm|${ALGORITHM:HS256}",
        "scanner.notion.database-id|${NOTION_DATABASE_ID:}",
        "scanner.twitter.api-secret-key|${TWITTER_API_SECRET_KEY:${TWITTER_API_SECRET:}}",
        "scanner.twitter.access-token|${TWITTER_ACCESS_TOKEN:}",
        "scanner.twitter.access-token-secret|${TWITTER_ACCESS_TOKEN_SECRET:}",
        "scanner.twitter.consumer-key|${TWITTER_CONSUMER_KEY:${TWITTER_API_KEY:}}",
        "scanner.twitter.consumer-secret|${TWITTER_CONSUMER_SECRET:${TWITTER_API_SECRET:}}"
    })
    @DisplayName("declares every source-drift environment key")
    void declaresEverySourceDriftEnvironmentKey(String property, String placeholder)
            throws IOException {

        assertThat(rawProperty(property)).isEqualTo(placeholder);
    }

    @Test
    @DisplayName("binds all eight source-drift values from their explicit environment keys")
    void bindsAllEightSourceDriftValuesFromTheirExplicitEnvironmentKeys() throws IOException {
        ScannerProperties scanner = load(withRequired(
                "ALGORITHM", "hs256",
                "NOTION_DATABASE_ID", "notion-database",
                "TWITTER_API_SECRET", "canonical-secret",
                "TWITTER_API_SECRET_KEY", "explicit-secret-key",
                "TWITTER_ACCESS_TOKEN", "access-token",
                "TWITTER_ACCESS_TOKEN_SECRET", "access-token-secret",
                "TWITTER_API_KEY", "canonical-key",
                "TWITTER_CONSUMER_KEY", "explicit-consumer-key",
                "TWITTER_CONSUMER_SECRET", "explicit-consumer-secret"))
                .bind("scanner", ScannerProperties.class);

        assertThat(scanner.jwt().secret()).isEqualTo(REQUIRED_ENVIRONMENT.get("SECRET_KEY"));
        assertThat(scanner.jwt().algorithm()).isEqualTo("hs256");
        assertThat(scanner.notion().databaseId()).isEqualTo("notion-database");
        assertThat(scanner.twitter().apiSecretKey()).isEqualTo("explicit-secret-key");
        assertThat(scanner.twitter().accessToken()).isEqualTo("access-token");
        assertThat(scanner.twitter().accessTokenSecret()).isEqualTo("access-token-secret");
        assertThat(scanner.twitter().consumerKey()).isEqualTo("explicit-consumer-key");
        assertThat(scanner.twitter().consumerSecret()).isEqualTo("explicit-consumer-secret");
    }

    @Test
    @DisplayName("falls back from the three Twitter aliases to the canonical credential pair")
    void fallsBackFromTheThreeTwitterAliasesToTheCanonicalCredentialPair() throws IOException {
        ScannerProperties.Twitter twitter = load(withRequired(
                "TWITTER_API_KEY", "canonical-key",
                "TWITTER_API_SECRET", "canonical-secret"))
                .bind("scanner", ScannerProperties.class)
                .twitter();

        assertThat(twitter.apiSecretKey()).isEqualTo("canonical-secret");
        assertThat(twitter.consumerKey()).isEqualTo("canonical-key");
        assertThat(twitter.consumerSecret()).isEqualTo("canonical-secret");
    }

    @Test
    @DisplayName("prefers explicit Twitter alias values over the canonical fallback pair")
    void prefersExplicitTwitterAliasValuesOverTheCanonicalFallbackPair() throws IOException {
        ScannerProperties.Twitter twitter = load(withRequired(
                "TWITTER_API_KEY", "canonical-key",
                "TWITTER_API_SECRET", "canonical-secret",
                "TWITTER_API_SECRET_KEY", "alias-secret-key",
                "TWITTER_CONSUMER_KEY", "alias-consumer-key",
                "TWITTER_CONSUMER_SECRET", "alias-consumer-secret"))
                .bind("scanner", ScannerProperties.class)
                .twitter();

        assertThat(twitter.apiSecretKey()).isEqualTo("alias-secret-key");
        assertThat(twitter.consumerKey()).isEqualTo("alias-consumer-key");
        assertThat(twitter.consumerSecret()).isEqualTo("alias-consumer-secret");
    }

    /**
     * Loads the production YAML beneath a deterministic environment property source.
     *
     * @param environment environment-style values
     * @return the loaded placeholder resolver and binder
     * @throws IOException when the production resource cannot be read
     */
    private static LoadedConfiguration load(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("contract-environment", environment));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        yaml.forEach(sources::addLast);

        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return new LoadedConfiguration(binder, new PropertySourcesPropertyResolver(sources));
    }

    /**
     * Reads one unexpanded value directly from the production YAML property source.
     *
     * @param name property name
     * @return raw placeholder expression
     * @throws IOException when the production resource cannot be read
     */
    private static String rawProperty(String name) throws IOException {
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            Object value = source.getProperty(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        throw new IllegalStateException("application.yml does not declare " + name + ".");
    }

    /**
     * Extends the required environment with key-value pairs.
     *
     * @param entries alternating key and value strings
     * @return environment map
     */
    private static Map<String, Object> withRequired(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Environment entries must be key-value pairs.");
        }
        Map<String, Object> environment = new LinkedHashMap<>(REQUIRED_ENVIRONMENT);
        for (int index = 0; index < entries.length; index += 2) {
            environment.put(entries[index], entries[index + 1]);
        }
        return environment;
    }

    /**
     * Loaded production configuration accessors.
     *
     * @param binder binder over environment and YAML sources
     * @param resolver placeholder-resolving property reader
     */
    private record LoadedConfiguration(Binder binder, PropertySourcesPropertyResolver resolver) {

        private <T> T bind(String prefix, Class<T> type) {
            return binder.bind(prefix, Bindable.of(type))
                    .orElseThrow(() -> new IllegalStateException(
                            "application.yml did not bind " + prefix + "."));
        }

        private String property(String name) {
            return resolver.getRequiredProperty(name);
        }

        private boolean booleanProperty(String name) {
            return resolver.getRequiredProperty(name, Boolean.class);
        }
    }
}