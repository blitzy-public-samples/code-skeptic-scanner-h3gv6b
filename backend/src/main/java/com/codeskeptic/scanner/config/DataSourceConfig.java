package com.codeskeptic.scanner.config;

import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

// Ported from backend/app/db/database.py:L5-13 (faithful port) — see docs/DECISION_LOG.md DL-027,
// DL-028, DL-270
/**
 * The connection source for the persistence layer.
 *
 * <p>Replaces {@code create_engine(settings.DATABASE_URL)} at
 * {@code backend/app/db/database.py:L5-8} with one pooled {@link DataSource} bean, and
 * {@code get_db_session()} at {@code :L10-13} with Spring Boot's JPA auto-configuration plus
 * {@code @Transactional} on the service layer — DL-027.
 *
 * <p>The dialect is read by Hibernate from the JDBC connection metadata and the driver is resolved at
 * run time from the JDBC URL, so no vendor is named at compile time — DL-027, DL-028. Table creation
 * is {@code spring.jpa.hibernate.ddl-auto} — DL-026. Pool geometry and timing are the HikariCP
 * defaults; this class configures neither — DL-270.
 *
 * <p>No JDBC URL, username or password reaches a log record or an exception message raised here —
 * DL-052.
 *
 * <p>Holds one immutable field, mutates nothing after construction and is safe for concurrent use.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private static final String ABSENT = "absent";

    private static final String SUPPLIED = "supplied";

    private final ScannerProperties properties;

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
     * <p>The instance is returned with its pool not yet started: HikariCP opens the pool on the first
     * {@code getConnection()} call, and the container closes it when the context closes. With
     * {@code server.shutdown: graceful} in {@code application.yml} the web layer drains its in-flight
     * requests before that close runs — DL-294.
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
    @Bean
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
