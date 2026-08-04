package com.codeskeptic.scanner.config;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * {@code service/NotionService}, as does {@code scanner.notion.database-id}, which those two call
 * sites read at {@code backend/app/services/notion_service.py:L24} and
 * {@code backend/app/services/notion_service.py:L35}. That identifier is a per-request payload
 * value; it is not a transport default and is not read here.
 *
 * <p>Notion is a secondary mirror; the relational store remains the system of record. No retry,
 * rate-limiting, circuit-breaker, caching or request-logging interceptor is registered and no timeout
 * is narrowed, so the framework defaults carried by the injected builder apply unchanged.
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
     * <p>Four bean-level defaults are applied and nothing else: the base URL, the mandatory
     * {@code Notion-Version} header, the {@code Authorization} header holding
     * {@code scanner.notion.api-key} as a bearer credential, and JSON {@code Content-Type} and
     * {@code Accept}. Payload construction, path selection, pagination and error translation belong
     * to the consumer.
     *
     * <p>{@code scanner.notion.api-key} resolves to an empty value when {@code NOTION_API_KEY} is
     * absent, per {@code src/main/resources/application.yml}. The bean is still published in that
     * state and a warning naming the key is logged; neither the application context nor startup
     * fails. No key material is logged at any level.
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
     * token, so the {@code Authorization} header value can never render the literal {@code "null"}.
     * Surrounding whitespace is stripped. The {@code scanner.notion} group is non-{@code null}
     * whenever it is bound from configuration; the guard covers a directly constructed
     * {@link ScannerProperties}.
     *
     * @return the configured key with surrounding whitespace removed, or an empty string when no key
     *     is configured; never {@code null}
     */
    private String resolveApiKey() {
        ScannerProperties.Notion notion = properties.notion();
        String apiKey = (notion == null) ? null : notion.apiKey();
        return (apiKey == null) ? "" : apiKey.strip();
    }
}
