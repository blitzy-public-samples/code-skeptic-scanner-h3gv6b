package com.codeskeptic.scanner.config;

import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

// Ported from backend/app/db/database.py:L5-13 (faithful port) — see docs/DECISION_LOG.md DL-027,
// DL-028, DL-058.
/**
 * The connection source for the persistence layer.
 *
 * <p>Replaces {@code get_db_connection()} at {@code backend/app/db/database.py:L5-8}, which read
 * {@code settings.DATABASE_URL} through {@code get_settings()} and returned
 * {@code create_engine(settings.DATABASE_URL)}. The same value arrives here as
 * {@code scanner.database-url} on the injected {@link ScannerProperties} record and is published as
 * the application's single pooled {@link DataSource} bean — DL-027.
 *
 * <p>{@code get_db_session()} at {@code backend/app/db/database.py:L10-13} has no counterpart in
 * this class. Spring Boot's JPA auto-configuration builds the {@code EntityManagerFactory} and the
 * transaction manager on top of the bean declared below, and {@code @Transactional} on the service
 * layer opens and closes the unit of work that the {@code sessionmaker} call produced.
 *
 * <p>{@link ScannerProperties} is reached by constructor injection into a {@code final} field, which
 * replaces the {@code get_settings()} call at {@code backend/app/db/database.py:L6}. No static
 * accessor, {@code @Value} binding or field injection is used, and no configuration key is read
 * anywhere else in this class.
 *
 * <p>What this class does not do:
 * <ul>
 *   <li>It parses nothing: {@link DatabaseUrlTranslator} is the only home of the
 *       {@code DATABASE_URL} grammar — DL-027.</li>
 *   <li>It sets no Hibernate {@code Dialect}, no {@code spring.jpa.database-platform}, no
 *       {@code hibernate.dialect} and no other vendor-specific Hibernate property. Hibernate reads
 *       the dialect from the JDBC connection metadata of the bean below — DL-027.</li>
 *   <li>It names no vendor, host, port, database or driver class, and it never calls
 *       {@code setDriverClassName}. The JDBC driver is resolved at run time from the JDBC URL
 *       against the drivers on the runtime classpath — DL-028.</li>
 *   <li>It sets no pool property other than the URL and the credentials; every HikariCP default
 *       applies unchanged.</li>
 *   <li>It declares no {@code EntityManagerFactory}, no
 *       {@code LocalContainerEntityManagerFactoryBean}, no {@code PlatformTransactionManager}, no
 *       {@code JdbcTemplate}, no {@code TransactionTemplate} and no {@code SessionFactory} bean, no
 *       second {@link DataSource}, and no routing or replica {@link DataSource}.</li>
 *   <li>It creates, migrates, populates and seeds nothing. Table creation is
 *       {@code spring.jpa.hibernate.ddl-auto} in {@code application.yml}; no Flyway, no Liquibase,
 *       no {@code SchemaExport}, no {@code DataSourceInitializer}, no
 *       {@code ResourceDatabasePopulator}, no {@code schema.sql} and no {@code data.sql} takes
 *       part.</li>
 *   <li>It sets no {@code server}-prefixed and no {@code spring}-prefixed property from code.</li>
 * </ul>
 *
 * <p>No JDBC URL, username or password reaches a log record or an exception message raised by this
 * class.
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
     * is always applied. The username and the password are applied only when the translation
     * produced them; the pass-through case — a value that already began with {@code jdbc:}, which is
     * how the H2 test profile and any pre-formed JDBC URL arrive — leaves both at the HikariCP
     * default and starts normally.
     *
     * <p>The instance is returned with its pool not yet started: HikariCP opens the pool on the first
     * {@code getConnection()} call, and the container closes it when the context closes.
     *
     * <p>This is the only bean this class declares and the only {@link DataSource} in the
     * application context. Spring Boot's {@code DataSourceAutoConfiguration} backs off, and every
     * consumer — the {@code EntityManagerFactory}, the transaction manager and every Spring Data JPA
     * repository — resolves this instance.
     *
     * @return the single {@link DataSource} bean in the application context, resolvable by type and
     *     by the name {@code dataSource}; never {@code null}
     * @throws IllegalStateException if {@code scanner.database-url} is absent, blank, unparseable or
     *     names an unsupported scheme, as raised and reported by
     *     {@link DatabaseUrlTranslator#translate(String)}
     */
    @Bean
    public DataSource dataSource() {
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

        // This record names no value: neither the JDBC URL nor either credential.
        log.info("Pooled DataSource published for the persistence layer; username {}, password {}.",
                translated.username() == null ? ABSENT : SUPPLIED,
                translated.password() == null ? ABSENT : SUPPLIED);

        return dataSource;
    }
}
