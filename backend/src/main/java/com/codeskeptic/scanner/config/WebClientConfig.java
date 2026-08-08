package com.codeskeptic.scanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Transport for the X (Twitter) integration.
 *
 * <p>Transport only: no {@code Authorization} header is carried here. The single consumer,
 * {@code task.TweetStreamClient}, sets that header per request from the app-only bearer token it
 * holds at runtime — DL-046.
 *
 * <p>No response timeout, read timeout or codec buffer limit is configured, so the long-lived chunked
 * stream body is neither cut short nor buffered whole. The two short request/response calls sharing
 * this client are bounded per request by {@code task.TweetStreamClient} — DL-230.
 */
// Replaces the tweepy.Stream construction at backend/app/tasks/tweet_monitoring.py:L45-51. The
// transport itself is net-new: the source targeted the retired v1.1 statuses/filter API over the
// tweepy SDK, and this bean targets X API v2 over WebClient — see docs/DECISION_LOG.md DL-012,
// DL-045, DL-046, DL-052.
@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /**
     * X API host root, not a {@code /2}-rooted prefix. Consumers supply the path: {@code /oauth2/token}
     * for the OAuth 2 client-credentials exchange, {@code /2/tweets/search/stream} for the filtered
     * stream, and {@code /2/tweets/search/stream/rules} for stream rule registration.
     */
    private static final String X_API_BASE_URL = "https://api.x.com";

    private static final String USER_AGENT = "code-skeptic-scanner-backend";

    /**
     * Publishes the X API transport.
     *
     * <p>Only the two constant headers below are applied. Rule reconciliation belongs to
     * {@code task.TweetStreamClient} — DL-045 — as do authentication — DL-046 — reconnection —
     * DL-207 — and payload handling — DL-220, DL-222 — see docs/DECISION_LOG.md.
     *
     * @param builder the auto-configured, prototype-scoped builder, which supplies the default
     *     connector and codecs
     * @return the single {@link WebClient} bean in the application context, resolvable by type
     */
    @Bean
    public WebClient twitterWebClient(WebClient.Builder builder) {
        log.info("X API WebClient configured with base URL {}", X_API_BASE_URL);

        return builder
                .baseUrl(X_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }
}
