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
 * <p>Ported from {@code backend/app/tasks/tweet_monitoring.py:L45-51} (faithful port) - see
 * {@code docs/DECISION_LOG.md} DL-012, DL-045, DL-046, DL-052, DL-058.
 *
 * <p>This class supplies transport only. The app-only bearer token exchange, the stream rule
 * synchronisation, the reconnection backoff, the {@code x-rate-limit-reset} handling and the
 * newline-delimited JSON parsing all live in
 * {@code com.codeskeptic.scanner.task.TweetStreamClient}.
 *
 * <p>The bean carries no {@code Authorization} header. The consumer sets that header per request
 * from the app-only bearer token it holds at runtime (DL-046).
 *
 * <p>No response timeout, no read timeout and no reduced codec buffer limit are configured. The
 * framework defaults apply: the response is unbounded and the connect phase is bounded.
 */
@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /**
     * X API host root, not a {@code /2}-rooted prefix. Consumers supply the path. The endpoints
     * reached through this bean are:
     *
     * <ul>
     *   <li>{@code /oauth2/token} - the OAuth 2 client-credentials exchange (DL-046)
     *   <li>{@code /2/tweets/search/stream} - the filtered stream, delivered as chunked
     *       newline-delimited JSON (DL-045)
     *   <li>{@code /2/tweets/search/stream/rules} - stream rule registration and reconciliation
     * </ul>
     */
    private static final String X_API_BASE_URL = "https://api.twitter.com";

    /** Product token sent on every request. Matches the Maven artifactId. */
    private static final String USER_AGENT = "code-skeptic-scanner-backend";

    /**
     * The X API transport, replacing the {@code tweepy.Stream} object the Python task constructed.
     *
     * <p>Only the constant headers below are applied. Reconnection, authentication and payload
     * handling belong to the consumer.
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
