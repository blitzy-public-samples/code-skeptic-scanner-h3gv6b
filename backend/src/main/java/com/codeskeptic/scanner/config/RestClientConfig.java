package com.codeskeptic.scanner.config;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Transport for the Notion integration.
 *
 * <p>Replaces the {@code notion_client.Client} instance constructed at
 * {@code backend/app/services/notion_service.py:L8}, whose single {@code auth} argument carried
 * {@code NOTION_API_KEY}. The bean published here applies that credential, the Notion API host and
 * the version header the API requires on every request as bean-level defaults.
 *
 * <p>This class supplies transport only. The two operations the retired service performed —
 * {@code pages.create} at {@code backend/app/services/notion_service.py:L23-26} and
 * {@code databases.query} at {@code backend/app/services/notion_service.py:L34-38} — belong to
 * {@code service/NotionService}, as does {@code scanner.notion.database-id}, read at
 * {@code backend/app/services/notion_service.py:L24} and
 * {@code backend/app/services/notion_service.py:L35}.
 *
 * <p>The framework defaults carried by the injected builder apply unchanged — DL-013.
 *
 * <p>The published {@link RestClient} is fully configured before it is returned and is never mutated
 * afterwards, so it is safe to share across concurrent requests.
 *
 * @see ScannerProperties.Notion
 */
// Ported from backend/app/services/notion_service.py:L8 (faithful port) — see docs/DECISION_LOG.md
// DL-013, DL-052, DL-198.
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /**
     * Notion API host root, not a {@code /v1}-rooted prefix. Consumers supply the path:
     * {@code /v1/pages} for the page creation at
     * {@code backend/app/services/notion_service.py:L23-26} and
     * {@code /v1/databases/{database_id}/query} for the database query at
     * {@code backend/app/services/notion_service.py:L34-38}.
     */
    private static final String NOTION_API_BASE_URL = "https://api.notion.com";

    /** Name of the header the Notion API requires on every request. */
    private static final String NOTION_VERSION_HEADER = "Notion-Version";

    /**
     * Value applied to {@link #NOTION_VERSION_HEADER} when {@code scanner.notion.api-version} resolves
     * to a blank or header-unsafe value. Matches the {@code @DefaultValue} declared on
     * {@link ScannerProperties.Notion#apiVersion()} — DL-151 — see {@code docs/DECISION_LOG.md}.
     */
    private static final String DEFAULT_NOTION_API_VERSION = "2022-06-28";

    /**
     * Connect timeout applied when the {@code scanner.notion} group is absent. It is the value
     * {@code ScannerProperties.Notion} declares as its own default — see docs/DECISION_LOG.md DL-150.
     */
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L;

    /**
     * Read timeout applied when the {@code scanner.notion} group is absent. It is the value
     * {@code ScannerProperties.Notion} declares as its own default — see docs/DECISION_LOG.md DL-150.
     */
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 10L;

    /** Scheme prefix of the {@code Authorization} header value. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Bound configuration root; supplies {@code scanner.notion.api-key}. */
    private final ScannerProperties properties;

    /**
     * Injects the bound configuration root, replacing the {@code get_settings()} call issued at
     * {@code backend/app/services/notion_service.py:L7} and declared at
     * {@code backend/app/core/config.py:L17-18}.
     *
     * @param properties the bound configuration root; must not be {@code null}
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public RestClientConfig(ScannerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "ScannerProperties must not be null");
    }

    /**
     * Publishes the Notion API transport.
     *
     * <p>Four bean-level defaults are applied: the base URL, the mandatory {@code Notion-Version}
     * header carrying {@code scanner.notion.api-version}, the {@code Authorization} header holding
     * {@code scanner.notion.api-key} as a bearer credential, and JSON {@code Content-Type} and
     * {@code Accept}. Payload construction, path selection, pagination and error translation belong to
     * the consumer.
     *
     * <p>{@code scanner.notion.api-key} resolves to an empty value when {@code NOTION_API_KEY} is
     * absent, per {@code src/main/resources/application.yml}. The bean is published in that state and
     * a warning naming the key is logged. No key material is logged at any level — DL-052.
     *
     * @param builder the auto-configured, prototype-scoped builder, which supplies the default
     *     request factory and message converters
     * @return the single {@link RestClient} bean in the application context, resolvable by type;
     *     never {@code null}
     */
    @Bean
    public RestClient notionRestClient(RestClient.Builder builder) {
        String apiKey = resolveApiKey();
        String apiVersion = resolveApiVersion();

        if (apiKey.isEmpty()) {
            log.warn("scanner.notion.api-key is not configured; Notion requests will be rejected as "
                    + "unauthorized until NOTION_API_KEY is supplied");
        }

        log.info("Notion RestClient configured with base URL {} and {}: {}",
                NOTION_API_BASE_URL, NOTION_VERSION_HEADER, apiVersion);

        return builder
                .requestFactory(boundedRequestFactory())
                .baseUrl(NOTION_API_BASE_URL)
                .defaultHeader(NOTION_VERSION_HEADER, apiVersion)
                .defaultHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // The configured API version is the value actually sent — DL-198 — see docs/DECISION_LOG.md
    /**
     * Reads {@code scanner.notion.api-key} and normalises it into a header-safe token.
     *
     * <p>A {@code null} {@code scanner.notion} group and a {@code null} key both yield an empty
     * token, which is the same state an absent {@code NOTION_API_KEY} produces.
     *
     * @return the configured key, stripped and verified header-safe, or an empty string when no
     *     usable key is configured; never {@code null}
     */
    private String resolveApiKey() {
        ScannerProperties.Notion notion = notionGroup();
        String apiKey = (notion == null) ? null : notion.apiKey();
        return headerSafe(apiKey, "scanner.notion.api-key");
    }

    /**
     * Reads {@code scanner.notion.api-version} and normalises it into a header-safe token.
     *
     * <p>Falls back to {@link #DEFAULT_NOTION_API_VERSION} when the configured value is absent, blank
     * or header-unsafe, so the header is never sent blank — DL-151.
     *
     * @return the version to send on every request; never {@code null} and never blank
     */
    private String resolveApiVersion() {
        ScannerProperties.Notion notion = properties.notion();
        String configured = (notion == null) ? null : notion.apiVersion();
        String safe = headerSafe(configured, "scanner.notion.api-version");
        if (safe.isEmpty()) {
            return DEFAULT_NOTION_API_VERSION;
        }
        return safe;
    }

    // Net-new (no Python counterpart; notion_client built the headers itself) — DL-195 — see
    // docs/DECISION_LOG.md
    /**
     * Strips a configured value and rejects it when it still carries a character that is not legal in
     * an HTTP header field value.
     *
     * <p>{@link String#strip()} removes a leading or trailing carriage return or line feed but leaves
     * an embedded one in place. Any value containing a character below {@code U+0020}, or
     * {@code U+007F}, is discarded and not sent; the property name is logged at {@code WARN} and no
     * part of the value is logged, at any level — DL-052, DL-195.
     *
     * @param value the configured value, possibly {@code null}
     * @param propertyName the property the value came from, named in the warning
     * @return the stripped value, or an empty string when it is {@code null}, blank or unsafe; never
     *     {@code null}
     */
    private String headerSafe(String value, String propertyName) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        for (int index = 0; index < stripped.length(); index++) {
            char character = stripped.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                log.warn("{} contains a character that is not legal in an HTTP header value and has "
                        + "been discarded; supply a value with no control characters", propertyName);
                return "";
            }
        }
        return stripped;
    }

    // The Notion-Version header value is scanner.notion.api-version — DL-151 — see
    // docs/DECISION_LOG.md


    /**
     * Reads the {@code scanner.notion} group.
     *
     * @return the bound group, or {@code null} when the group is not bound
     */
    private ScannerProperties.Notion notionGroup() {
        return properties.notion();
    }

    /**
     * Builds the request factory the Notion client uses, with finite connect and read timeouts taken
     * from {@code scanner.notion.connect-timeout-seconds} and {@code scanner.notion.read-timeout-seconds}.
     *
     * <p>The transport implementation remains the one the framework detects on the classpath; only the
     * two timeout bounds are supplied by this application - see docs/DECISION_LOG.md DL-150,
     * DL-128.
     *
     * <p>A {@code null} {@code scanner.notion} group yields {@value #DEFAULT_CONNECT_TIMEOUT_SECONDS}
     * and {@value #DEFAULT_READ_TIMEOUT_SECONDS} seconds, the values the properties declare as their
     * own defaults, matching how {@link #resolveApiKey()} and {@link #resolveApiVersion()} tolerate the
     * same absent group.
     *
     * @return a request factory carrying application-owned finite timeouts
     */
    private ClientHttpRequestFactory boundedRequestFactory() {
        ScannerProperties.Notion notion = notionGroup();
        long connectTimeoutSeconds = (notion == null)
                ? DEFAULT_CONNECT_TIMEOUT_SECONDS : notion.connectTimeoutSeconds();
        long readTimeoutSeconds = (notion == null)
                ? DEFAULT_READ_TIMEOUT_SECONDS : notion.readTimeoutSeconds();
        ClientHttpRequestFactorySettings bounded = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(requireAtLeastOne(connectTimeoutSeconds,
                        "scanner.notion.connect-timeout-seconds")))
                .withReadTimeout(Duration.ofSeconds(requireAtLeastOne(readTimeoutSeconds,
                        "scanner.notion.read-timeout-seconds")));
        return ClientHttpRequestFactoryBuilder.detect().build(bounded);
    }

    /**
     * Validates one configured timeout.
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
}
