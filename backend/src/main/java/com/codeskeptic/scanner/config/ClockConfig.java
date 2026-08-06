package com.codeskeptic.scanner.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Net-new (no Python counterpart: backend/app/api/analytics.py:L13-14 read no clock and the
// AnalyticsService it imported did not exist) — DL-278 — see docs/DECISION_LOG.md
/**
 * Publishes the application's time source.
 *
 * <p>The bean is {@link Clock#systemUTC()}, so every instant it reports is read in UTC and no reader
 * depends on the JVM's default time zone. {@code task.TweetStreamListener.readCreatedAt} and
 * {@code service.NotionService.parseOffsetDateTime} both normalise a delivered timestamp to UTC
 * before it is stored in {@code tweets.created_at} — DL-192 — so a query bound to that column is
 * bound against UTC values, and this clock is the same basis — DL-278.
 *
 * <p>{@code service.AnalyticsService} is the consumer: it derives the {@code GET /analytics/trends}
 * observation window from this clock. Scheduling reads its own clock from the
 * {@code org.springframework.scheduling.TriggerContext} the framework supplies and does not resolve
 * this bean — DL-228.
 *
 * <p>The published instance is immutable and safe for concurrent use.
 */
@Configuration
public class ClockConfig {

    /**
     * Publishes the UTC clock every reader of the current instant resolves.
     *
     * @return the single {@link Clock} bean in the application context, resolvable by type and by the
     *     name {@code utcClock}; never {@code null}
     */
    // Net-new (no source construct) — DL-278 — see docs/DECISION_LOG.md
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
