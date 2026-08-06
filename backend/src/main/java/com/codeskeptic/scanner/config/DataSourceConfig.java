package com.codeskeptic.scanner.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import com.zaxxer.hikari.HikariDataSource;

// Ported from backend/app/db/database.py:L5-13 (faithful port) — see docs/DECISION_LOG.md DL-027,
// DL-028. The allowlisted pool surface below is net-new — see docs/DECISION_LOG.md DL-270, DL-271.
/**
 * The connection source for the persistence layer.
 *
 * <p>Replaces {@code get_db_connection()} at {@code backend/app/db/database.py:L5-8}, which returned
 * {@code create_engine(settings.DATABASE_URL)}. The same value arrives here as
 * {@code scanner.database-url} on the injected {@link ScannerProperties} record and is published as
 * the application's single pooled {@link DataSource} bean — DL-027.
 *
 * <p>{@code get_db_session()} at {@code backend/app/db/database.py:L10-13} is replaced by Spring
 * Boot's JPA auto-configuration, which builds the {@code EntityManagerFactory} and the transaction
 * manager on top of the bean declared below, and by {@code @Transactional} on the service layer.
 *
 * <p>{@link ScannerProperties} is reached by constructor injection into a {@code final} field, in
 * place of the {@code get_settings()} call at {@code backend/app/db/database.py:L6}.
 * {@link DatabaseUrlTranslator} holds the {@code DATABASE_URL} grammar; the dialect is read by
 * Hibernate from the JDBC connection metadata of the bean below and the driver is resolved at run
 * time from the JDBC URL — DL-027, DL-028. Table creation is {@code spring.jpa.hibernate.ddl-auto} in
 * {@code application.yml} — DL-026.
 *
 * <p>The pool surface is {@link DataSourcePoolProperties}, bound from
 * {@code scanner.datasource.pool.*}. It is an allowlist: it carries pool geometry and pool timing
 * only, so no deployment value can supply a JDBC URL, a credential, a driver or data-source class
 * name, a catalog or schema, a connection-initialisation statement or a free-form driver-property
 * map — DL-270. The whole {@code spring.datasource.*} surface is unread: a key from
 * {@link #FORBIDDEN_IDENTITY_KEYS} refuses to start, and any other {@code spring.datasource.*} key
 * is reported at {@code WARN} and ignored, so nothing a deployment sets there takes silent effect —
 * DL-270.
 *
 * <p>No JDBC URL, username or password reaches a log record or an exception message raised by this
 * class — DL-052.
 *
 * <p>This class holds immutable fields only, mutates nothing after construction and is safe for
 * concurrent use.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** Stands in for a credential the configured value did not carry, in the log records below. */
    private static final String ABSENT = "absent";

    /** Stands in for a credential the configured value carried, in the log records below. */
    private static final String SUPPLIED = "supplied";

    /** Prefix of the framework pool surface this class deliberately does not read — DL-270. */
    private static final String SPRING_DATASOURCE_PREFIX = "spring.datasource.";

    /** Prefix of the allowlisted pool surface this class does read — DL-270. */
    private static final String POOL_PREFIX = "scanner.datasource.pool";

    /**
     * Keys that would redirect the pool away from the connection identity
     * {@link DatabaseUrlTranslator} derived, or inject into the connections it opens. Any one of them
     * refuses to start — DL-270.
     */
    private static final List<String> FORBIDDEN_IDENTITY_KEYS = List.of(
            "spring.datasource.url",
            "spring.datasource.jdbc-url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.datasource.driver-class-name",
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.driver-class-name",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-jndi",
            "spring.datasource.hikari.data-source-properties",
            "spring.datasource.hikari.connection-init-sql",
            "spring.datasource.hikari.catalog",
            "spring.datasource.hikari.schema");

    private final ScannerProperties properties;

    private final DataSourcePoolProperties pool;

    private final Environment environment;

    /**
     * Captures the bound configuration this class reads and the environment it screens.
     *
     * @param properties  the bound {@code scanner} configuration root, never {@code null}
     * @param pool        the bound {@code scanner.datasource.pool} group, never {@code null}
     * @param environment the environment screened for the unread {@code spring.datasource.*} surface,
     *     never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DataSourceConfig(ScannerProperties properties, DataSourcePoolProperties pool,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "ScannerProperties must not be null.");
        this.pool = Objects.requireNonNull(pool, "DataSourcePoolProperties must not be null.");
        this.environment = Objects.requireNonNull(environment, "Environment must not be null.");
    }

    /**
     * Publishes the pooled connection source built from {@code scanner.database-url}.
     *
     * <p>The environment is screened first. A key from {@link #FORBIDDEN_IDENTITY_KEYS} refuses to
     * start; any other {@code spring.datasource.*} key is reported at {@code WARN} and ignored. Both
     * records name keys only, never values — DL-052, DL-270.
     *
     * <p>{@link DatabaseUrlTranslator#translate(String)} then turns the configured value into a JDBC
     * URL and, for a value that carried a user-info component, a separate username and password. The
     * URL is always applied; the username and the password are applied only when the translation
     * produced them, so a value that already begins with {@code jdbc:} leaves both at the HikariCP
     * default.
     *
     * <p>Every component of {@link DataSourcePoolProperties} is then applied. Each one was
     * range-checked as it bound, so HikariCP replaces none of them — DL-271.
     *
     * <p>The instance is returned with its pool not yet started: HikariCP opens the pool on the first
     * {@code getConnection()} call, and the container closes it when the context closes. With
     * {@code server.shutdown: graceful} in {@code application.yml} the web layer drains its in-flight
     * requests before that close runs — DL-271.
     *
     * <p>This is the only {@link DataSource} bean in the application context;
     * {@code DataSourceAutoConfiguration} backs off and every consumer resolves this instance.
     *
     * @return the single {@link DataSource} bean in the application context, resolvable by type and
     *     by the name {@code dataSource}; never {@code null}
     * @throws IllegalStateException if a forbidden {@code spring.datasource.*} key is configured, or
     *     if {@code scanner.database-url} is absent, blank, unparseable or names an unsupported
     *     scheme, as raised and reported by {@link DatabaseUrlTranslator#translate(String)}
     */
    // The pool surface is the allowlist scanner.datasource.pool.* — DL-270, DL-271 — see
    // docs/DECISION_LOG.md
    @Bean
    public HikariDataSource dataSource() {
        screenUnreadPoolSurface();

        DatabaseUrlTranslator.TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate(this.properties.databaseUrl());

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(translated.jdbcUrl());
        if (translated.username() != null) {
            dataSource.setUsername(translated.username());
        }
        if (translated.password() != null) {
            dataSource.setPassword(translated.password());
        }

        applyPoolGeometry(dataSource);

        // Logging baseline — DL-052 — see docs/DECISION_LOG.md
        log.info("Pooled DataSource published for the persistence layer; username {}, password {}.",
                translated.username() == null ? ABSENT : SUPPLIED,
                translated.password() == null ? ABSENT : SUPPLIED);

        return dataSource;
    }

    /**
     * Applies the allowlisted pool settings to the pool being published.
     *
     * @param dataSource the pool to configure, never {@code null}
     */
    // Net-new bounded pool geometry — DL-271 — see docs/DECISION_LOG.md
    private void applyPoolGeometry(HikariDataSource dataSource) {
        dataSource.setPoolName(this.pool.name());
        dataSource.setMaximumPoolSize(this.pool.maximumSize());
        dataSource.setMinimumIdle(this.pool.minimumIdle());
        dataSource.setConnectionTimeout(this.pool.connectionTimeoutMillis());
        dataSource.setValidationTimeout(this.pool.validationTimeoutMillis());
        dataSource.setIdleTimeout(this.pool.idleTimeoutMillis());
        dataSource.setMaxLifetime(this.pool.maxLifetimeMillis());
        dataSource.setLeakDetectionThreshold(this.pool.leakDetectionThresholdMillis());

        log.info("Connection pool {} bounded to at most {} connection(s), {} kept idle, "
                        + "{}ms to obtain one, idle retirement after {}ms and a maximum lifetime "
                        + "of {}ms.",
                this.pool.name(), this.pool.maximumSize(), this.pool.minimumIdle(),
                this.pool.connectionTimeoutMillis(), this.pool.idleTimeoutMillis(),
                this.pool.maxLifetimeMillis());
    }

    /**
     * Screens the environment for the {@code spring.datasource.*} surface this class does not read.
     *
     * <p>A key from {@link #FORBIDDEN_IDENTITY_KEYS} is refused. Every other
     * {@code spring.datasource.*} key found in an enumerable property source is reported once at
     * {@code WARN}, naming the keys and pointing at {@value #POOL_PREFIX} — see docs/DECISION_LOG.md
     * DL-270.
     *
     * @throws IllegalStateException when a forbidden key is configured
     */
    // Net-new allowlist enforcement — DL-270 — see docs/DECISION_LOG.md
    private void screenUnreadPoolSurface() {
        Set<String> present = unreadPoolKeys();

        Set<String> forbidden = new LinkedHashSet<>();
        for (String key : FORBIDDEN_IDENTITY_KEYS) {
            // containsProperty resolves relaxed forms too, so SPRING_DATASOURCE_HIKARI_JDBC_URL is
            // found as readily as the canonical key.
            if (this.environment.containsProperty(key)) {
                forbidden.add(key);
            }
        }
        for (String key : present) {
            if (isForbidden(key)) {
                forbidden.add(key);
            }
        }
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException("The connection identity of the pool comes from "
                    + "scanner.database-url alone; remove " + String.join(", ", forbidden)
                    + ". Pool settings belong to " + POOL_PREFIX + ".*");
        }

        if (!present.isEmpty()) {
            log.warn("Ignoring {} configured under spring.datasource, which this application does "
                    + "not read; configure the pool through {}.*", present, POOL_PREFIX);
        }
    }

    /**
     * Reports whether one property name is a connection-identity key or a member of one.
     *
     * <p>A name that extends a forbidden key by a dotted segment is forbidden alongside the key
     * itself: a map-valued key such as {@code spring.datasource.hikari.data-source-properties} appears
     * in a property source only as its members — see docs/DECISION_LOG.md DL-270.
     *
     * @param name the property name found in the environment
     * @return {@code true} when the name is forbidden
     */
    private static boolean isForbidden(String name) {
        for (String key : FORBIDDEN_IDENTITY_KEYS) {
            if (name.equals(key) || name.startsWith(key + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the {@code spring.datasource.*} keys present in the enumerable property sources.
     *
     * <p>A non-enumerable source cannot be listed. The forbidden keys are looked up by name, so they
     * are found in such a source as well.
     *
     * @return the keys found, in the order the sources declare them; never {@code null}
     */
    private Set<String> unreadPoolKeys() {
        Set<String> found = new LinkedHashSet<>();
        if (!(this.environment instanceof ConfigurableEnvironment configurable)) {
            return found;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(SPRING_DATASOURCE_PREFIX)) {
                        found.add(name);
                    }
                }
            }
        }
        return found;
    }
}
