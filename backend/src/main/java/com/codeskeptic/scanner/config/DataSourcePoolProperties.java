package com.codeskeptic.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Net-new (no Python counterpart: backend/app/db/database.py:L5-8 built a fresh unpooled engine per
// call and configured no pool at all) — DL-270, DL-271 — see docs/DECISION_LOG.md
/**
 * Bound configuration group for the connection pool {@link DataSourceConfig} publishes.
 *
 * <p>This record is the complete set of pool settings a deployment may supply. It carries pool
 * geometry and pool timing only: no JDBC URL, no username, no password, no driver or data-source
 * class name, no catalog or schema, no connection-initialisation statement and no free-form
 * driver-property map. A setting that is not a component of this record is not configurable, and the
 * connection identity stays the one {@link DatabaseUrlTranslator} derived from
 * {@code scanner.database-url} — DL-270.
 *
 * <p>Values bind from {@code src/main/resources/application.yml} under the
 * {@code scanner.datasource.pool} prefix. Spring's relaxed binding maps each kebab-case key onto the
 * matching camelCase component and accepts the {@code SCREAMING_SNAKE} environment-variable form of
 * the same key — DL-271.
 *
 * <p>Every component is range-checked by the compact constructor below, which refuses a value
 * outside its accepted range and substitutes none. Each accepted range is the one HikariCP enforces,
 * with a ceiling of {@value #MAXIMUM_SIZE_CEILING} on the pool size — see
 * docs/DECISION_LOG.md DL-271. A failure message names the key and the offending number; no
 * connection identity appears in it — DL-052.
 *
 * <p>This record is immutable, holds no reference to mutable state and is safe for concurrent use.
 * It carries no credential, so its {@code toString()} is the compiler-generated one.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-270 and DL-271;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @param maximumSize value of {@code scanner.datasource.pool.maximum-size}, the largest number of
 *     connections this replica opens; default {@code 10}, accepted range {@code 1} to
 *     {@value #MAXIMUM_SIZE_CEILING} inclusive
 * @param minimumIdle value of {@code scanner.datasource.pool.minimum-idle}, the number of
 *     connections kept open while idle; default {@code 2}, accepted range {@code 0} to
 *     {@code maximumSize} inclusive
 * @param connectionTimeoutMillis value of
 *     {@code scanner.datasource.pool.connection-timeout-millis}, how long a caller waits for a
 *     connection; default {@code 30000}, accepted range {@value #MINIMUM_TIMEOUT_MILLIS} or greater
 * @param validationTimeoutMillis value of
 *     {@code scanner.datasource.pool.validation-timeout-millis}, how long a connection test may
 *     take; default {@code 5000}, accepted range {@value #MINIMUM_TIMEOUT_MILLIS} or greater
 * @param idleTimeoutMillis value of {@code scanner.datasource.pool.idle-timeout-millis}, how long a
 *     connection above {@code minimumIdle} is kept before it is retired; default {@code 600000},
 *     accepted values {@code 0} — never retire — or {@value #MINIMUM_IDLE_TIMEOUT_MILLIS} or
 *     greater
 * @param maxLifetimeMillis value of {@code scanner.datasource.pool.max-lifetime-millis}, the age at
 *     which a connection is retired; default {@code 1800000}, accepted values {@code 0} — no
 *     maximum — or {@value #MINIMUM_MAX_LIFETIME_MILLIS} or greater
 * @param leakDetectionThresholdMillis value of
 *     {@code scanner.datasource.pool.leak-detection-threshold-millis}, how long a connection may be
 *     held out of the pool before it is reported; default {@code 0} — off — accepted values
 *     {@code 0} or {@value #MINIMUM_LEAK_DETECTION_MILLIS} or greater
 * @param name value of {@code scanner.datasource.pool.name}, the pool name carried by HikariCP's own
 *     log records, its threads and its metrics; default {@value #DEFAULT_POOL_NAME}, and never blank
 */
@ConfigurationProperties(prefix = "scanner.datasource.pool")
public record DataSourcePoolProperties(

        @DefaultValue("10") int maximumSize,

        @DefaultValue("2") int minimumIdle,

        @DefaultValue("30000") long connectionTimeoutMillis,

        @DefaultValue("5000") long validationTimeoutMillis,

        @DefaultValue("600000") long idleTimeoutMillis,

        @DefaultValue("1800000") long maxLifetimeMillis,

        @DefaultValue("0") long leakDetectionThresholdMillis,

        @DefaultValue(DEFAULT_POOL_NAME) String name) {

    /** Pool name applied when {@code scanner.datasource.pool.name} is not configured. */
    public static final String DEFAULT_POOL_NAME = "code-skeptic-scanner-pool";

    /** Largest accepted value of {@code scanner.datasource.pool.maximum-size} — DL-271. */
    public static final int MAXIMUM_SIZE_CEILING = 100;

    /**
     * Smallest accepted connection and validation timeout, in milliseconds. HikariCP rejects a
     * shorter value.
     */
    public static final long MINIMUM_TIMEOUT_MILLIS = 250L;

    /**
     * Smallest accepted non-zero value of {@code scanner.datasource.pool.idle-timeout-millis}.
     * HikariCP rejects a shorter non-zero value.
     */
    public static final long MINIMUM_IDLE_TIMEOUT_MILLIS = 10_000L;

    /**
     * Smallest accepted non-zero value of {@code scanner.datasource.pool.max-lifetime-millis}.
     * HikariCP rejects a shorter non-zero value.
     */
    public static final long MINIMUM_MAX_LIFETIME_MILLIS = 30_000L;

    /**
     * Smallest accepted non-zero value of
     * {@code scanner.datasource.pool.leak-detection-threshold-millis}. HikariCP rejects a shorter
     * non-zero value.
     */
    public static final long MINIMUM_LEAK_DETECTION_MILLIS = 2_000L;

    /**
     * Range-checks every bound value and normalises the pool name.
     *
     * <p>A blank or absent name becomes {@value #DEFAULT_POOL_NAME}; every other name is stripped of
     * surrounding whitespace. Every numeric bound is checked against the range its component
     * documents, and a value outside that range is refused, so binding fails at startup — see
     * docs/DECISION_LOG.md DL-271.
     *
     * @throws IllegalStateException when a bound value lies outside its accepted range
     */
    public DataSourcePoolProperties {
        name = (name == null || name.isBlank()) ? DEFAULT_POOL_NAME : name.strip();

        requireWithin("scanner.datasource.pool.maximum-size", maximumSize, 1,
                MAXIMUM_SIZE_CEILING);
        requireWithin("scanner.datasource.pool.minimum-idle", minimumIdle, 0, maximumSize);
        requireAtLeast("scanner.datasource.pool.connection-timeout-millis", connectionTimeoutMillis,
                MINIMUM_TIMEOUT_MILLIS);
        requireAtLeast("scanner.datasource.pool.validation-timeout-millis", validationTimeoutMillis,
                MINIMUM_TIMEOUT_MILLIS);
        requireZeroOrAtLeast("scanner.datasource.pool.idle-timeout-millis", idleTimeoutMillis,
                MINIMUM_IDLE_TIMEOUT_MILLIS);
        requireZeroOrAtLeast("scanner.datasource.pool.max-lifetime-millis", maxLifetimeMillis,
                MINIMUM_MAX_LIFETIME_MILLIS);
        requireZeroOrAtLeast("scanner.datasource.pool.leak-detection-threshold-millis",
                leakDetectionThresholdMillis, MINIMUM_LEAK_DETECTION_MILLIS);
    }

    /**
     * Refuses an integer outside an inclusive range.
     *
     * @param key     the configuration key the value binds from
     * @param value   the bound value
     * @param lowest  the smallest accepted value
     * @param highest the largest accepted value
     * @throws IllegalStateException when {@code value} lies outside the range
     */
    private static void requireWithin(String key, int value, int lowest, int highest) {
        if (value < lowest || value > highest) {
            throw new IllegalStateException(key + " must lie between " + lowest + " and " + highest
                    + " inclusive; it is " + value + ".");
        }
    }

    /**
     * Refuses a duration below a floor.
     *
     * @param key    the configuration key the value binds from
     * @param value  the bound value in milliseconds
     * @param lowest the smallest accepted value in milliseconds
     * @throws IllegalStateException when {@code value} is below {@code lowest}
     */
    private static void requireAtLeast(String key, long value, long lowest) {
        if (value < lowest) {
            throw new IllegalStateException(key + " must be at least " + lowest
                    + " milliseconds; it is " + value + ".");
        }
    }

    /**
     * Refuses a duration that is neither zero nor at or above a floor.
     *
     * @param key    the configuration key the value binds from
     * @param value  the bound value in milliseconds
     * @param lowest the smallest accepted non-zero value in milliseconds
     * @throws IllegalStateException when {@code value} is negative, or is below {@code lowest}
     *     without being zero
     */
    private static void requireZeroOrAtLeast(String key, long value, long lowest) {
        if (value != 0L && value < lowest) {
            throw new IllegalStateException(key + " must be 0 or at least " + lowest
                    + " milliseconds; it is " + value + ".");
        }
    }
}
