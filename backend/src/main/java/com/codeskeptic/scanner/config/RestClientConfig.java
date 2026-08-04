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
// DL-013, DL-052, DL-058.
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

    /** Pinned value of {@link #NOTION_VERSION_HEADER} — see {@code docs/DECISION_LOG.md}. */
    private static final String NOTION_API_VERSION = "2022-06-28";

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
     * header, the {@code Authorization} header holding {@code scanner.notion.api-key} as a bearer
     * credential, and JSON {@code Content-Type} and {@code Accept}. Payload construction, path
     * selection, pagination and error translation belong to the consumer.
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

        if (apiKey.isEmpty()) {
            log.warn("scanner.notion.api-key is not configured; Notion requests will be rejected as "
                    + "unauthorized until NOTION_API_KEY is supplied");
        }

        log.info("Notion RestClient configured with base URL {} and {}: {}",
                NOTION_API_BASE_URL, NOTION_VERSION_HEADER, NOTION_API_VERSION);

        return builder
                .requestFactory(boundedRequestFactory())
                .baseUrl(NOTION_API_BASE_URL)
                .defaultHeader(NOTION_VERSION_HEADER, NOTION_API_VERSION)
                .defaultHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Reads {@code scanner.notion.api-key} and normalises it into a header-safe token.
     *
     * <p>A {@code null} {@code scanner.notion} group and a {@code null} key both yield an empty
     * token. Surrounding whitespace is stripped.
     *
     * @return the configured key with surrounding whitespace removed, or an empty string when no key
     *     is configured; never {@code null}
     */
    private String resolveApiKey() {
        ScannerProperties.Notion notion = properties.notion();
        String apiKey = (notion == null) ? null : notion.apiKey();
        return (apiKey == null) ? "" : apiKey.strip();
    }

    /**
     * Builds the request factory the Notion client uses, with finite connect and read timeouts taken
     * from {@code scanner.notion.connect-timeout-seconds} and {@code scanner.notion.read-timeout-seconds}.
     *
     * <p>The transport implementation remains the one the framework detects on the classpath; only the
     * two timeout bounds are supplied by this application - see docs/DECISION_LOG.md DL-150,
     * DL-128.
     *
     * @return a request factory carrying application-owned finite timeouts
     */
    private ClientHttpRequestFactory boundedRequestFactory() {
        ClientHttpRequestFactorySettings bounded = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(properties.notion().connectTimeoutSeconds()))
                .withReadTimeout(Duration.ofSeconds(properties.notion().readTimeoutSeconds()));
        return ClientHttpRequestFactoryBuilder.detect().build(bounded);
    }
}
