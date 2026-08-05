package com.codeskeptic.scanner.config;

import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

// Ported from backend/app/db/database.py:L5-13 (faithful port) — see docs/DECISION_LOG.md DL-027,
// DL-028.
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
 * <p>Pool configuration is Spring Boot's own {@code spring.datasource.hikari.*} surface, bound onto
 * the published instance — DL-229. {@code spring.datasource.url}, {@code .username},
 * {@code .password} and {@code .driver-class-name} remain unset and unread: the connection target
 * comes from {@code scanner.database-url} alone — DL-027.
 *
 * <p>No JDBC URL, username or password reaches a log record or an exception message raised by this
 * class — DL-052.
 *
 * <p>This class holds one immutable field, mutates nothing after construction and is safe for
 * concurrent use.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** Stands in for a credential the configured value did not carry, in the one log record below. */
    private static final String ABSENT = "absent";

    /** Stands in for a credential the configured value carried, in the one log record below. */
    private static final String SUPPLIED = "supplied";

    /**
     * Configuration prefix bound onto the published pool. It is Spring Boot's own Hikari prefix, so a
     * deployment configures this pool with the keys it would use for a pool the framework built —
     * DL-229.
     */
    private static final String HIKARI_PROPERTY_PREFIX = "spring.datasource.hikari";

    private final ScannerProperties properties;

    /**
     * Captures the bound configuration root this class reads {@code scanner.database-url} from.
     *
     * @param properties the bound {@code scanner} configuration root, never {@code null}
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public DataSourceConfig(ScannerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "ScannerProperties must not be null.");
    }

    /**
     * Publishes the pooled connection source built from {@code scanner.database-url}.
     *
     * <p>{@link DatabaseUrlTranslator#translate(String)} turns the configured value into a JDBC URL
     * and, for a value that carried a user-info component, a separate username and password. The URL
     * is always applied; the username and the password are applied only when the translation produced
     * them, so a value that already begins with {@code jdbc:} leaves both at the HikariCP default.
     *
     * <p>Every {@code spring.datasource.hikari.*} property is bound onto the returned instance after
     * this method has applied the translated values, so pool geometry, timeouts, leak detection,
     * metric registration and the pool name are configurable exactly as they are for a pool Spring
     * Boot builds itself — see docs/DECISION_LOG.md DL-229. A deployment that sets no such property
     * gets the HikariCP defaults.
     *
     * <p>The instance is returned with its pool not yet started: HikariCP opens the pool on the first
     * {@code getConnection()} call, and the container closes it when the context closes.
     *
     * <p>This is the only {@link DataSource} bean in the application context;
     * {@code DataSourceAutoConfiguration} backs off and every consumer resolves this instance.
     *
     * @return the single {@link DataSource} bean in the application context, resolvable by type and
     *     by the name {@code dataSource}; never {@code null}
     * @throws IllegalStateException if {@code scanner.database-url} is absent, blank, unparseable or
     *     names an unsupported scheme, as raised and reported by
     *     {@link DatabaseUrlTranslator#translate(String)}
     */
    // The Spring Boot Hikari property surface is bound onto this pool — DL-229 — see
    // docs/DECISION_LOG.md
    @Bean
    @ConfigurationProperties(prefix = HIKARI_PROPERTY_PREFIX)
    public HikariDataSource dataSource() {
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

        // Logging baseline — DL-052 — see docs/DECISION_LOG.md
        log.info("Pooled DataSource published for the persistence layer; username {}, password {}.",
                translated.username() == null ? ABSENT : SUPPLIED,
                translated.password() == null ? ABSENT : SUPPLIED);

        return dataSource;
    }
}
