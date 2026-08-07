package com.codeskeptic.scanner.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
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


    /**
     * JDBC URL prefix of the vendor {@code com.mysql:mysql-connector-j} serves. It is the one vendor
     * whose accepted schemes cover a server product the driver cannot serve — DL-304.
     */
    private static final String MYSQL_JDBC_PREFIX = "jdbc:mysql:";

    /**
     * Server product {@code com.mysql:mysql-connector-j} cannot serve, matched case-insensitively
     * against both the product name and the product version a connection reports — DL-304.
     */
    private static final String UNSERVABLE_SERVER_PRODUCT = "mariadb";

    /** Greatest number of characters of a reported server product a diagnostic carries — DL-052. */
    private static final int SERVER_PRODUCT_LIMIT = 96;

    /** Greatest number of characters of a reported server product a diagnostic carries — DL-052. */
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
     * <p>A URL naming the MySQL vendor is then checked against the server behind it — DL-187. That
     * check is the one path that opens a connection here; for every other vendor the instance is
     * returned with its pool not yet started, HikariCP opening it on the first
     * {@code getConnection()} call. The container closes the pool when the context closes, and with
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
     *     {@link DatabaseUrlTranslator#translate(String)}; and when the value resolves onto the
     *     MySQL vendor while the server behind it reports MariaDB — DL-304
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


        // The server product is checked before the persistence layer starts — DL-304 — see
        // docs/DECISION_LOG.md
        try {
            verifyServerProduct(dataSource, translated.jdbcUrl());
        } catch (IllegalStateException refused) {
            dataSource.close();
            throw refused;
        }

        // Logging baseline — DL-052 — see docs/DECISION_LOG.md
        log.info("Pooled DataSource published for the persistence layer; username {}, password {}.",
                translated.username() == null ? ABSENT : SUPPLIED,
                translated.password() == null ? ABSENT : SUPPLIED);

        return dataSource;
    }

    /**
     * Refuses a server product the resolved JDBC driver cannot serve, before the persistence layer
     * reads the connection metadata Hibernate needs.
     *
     * <p>The check applies to one vendor. {@code com.mysql:mysql-connector-j} serves both the
     * {@code mysql} and the {@code mariadb} scheme the agreed scheme table maps onto it, but it reads
     * its reserved-word list from {@code INFORMATION_SCHEMA.KEYWORDS} with a predicate a MariaDB
     * server does not offer, so dialect resolution fails after the pool is open with an error naming
     * neither {@code DATABASE_URL} nor the server. This method turns that into a refusal that names
     * both — DL-304. Every other vendor returns immediately and no connection is opened.
     *
     * <p>A connection that cannot be opened at all is not this method's subject: the failure is
     * reported at {@code WARN} naming the key and the failure type only, and the caller proceeds, so
     * an unreachable database keeps the startup path it already had and is explained by
     * {@link DatabaseStartupFailureAnalyzer} — DL-305.
     *
     * @param dataSource the pool to read one connection's metadata from, never {@code null}
     * @param jdbcUrl    the translated JDBC URL whose vendor selects the check, never {@code null}
     * @throws IllegalStateException when the server reports a product the resolved driver cannot serve
     */
    // Net-new server-product guard — DL-304 — see docs/DECISION_LOG.md
    void verifyServerProduct(DataSource dataSource, String jdbcUrl) {
        if (!namesMysqlVendor(jdbcUrl)) {
            return;
        }

        String product;
        String version;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            product = metaData.getDatabaseProductName();
            version = metaData.getDatabaseProductVersion();
        } catch (SQLException | RuntimeException unreachable) {
            log.warn("The server product behind scanner.database-url could not be read before the "
                            + "persistence layer started, so it is unchecked: {}.",
                    unreachable.getClass().getSimpleName());
            return;
        }

        String reportedProduct = logSafe(product, SERVER_PRODUCT_LIMIT);
        String reportedVersion = logSafe(version, SERVER_PRODUCT_LIMIT);
        if (reportsUnservableServerProduct(product, version)) {
            throw new IllegalStateException("DATABASE_URL resolved onto the MySQL vendor, which "
                    + "com.mysql:mysql-connector-j serves, but the server behind it reports '"
                    + reportedProduct + "' version '" + reportedVersion + "'. That server product is "
                    + "not one this service supports: the driver reads its reserved-word list from "
                    + "INFORMATION_SCHEMA.KEYWORDS with a predicate the server does not offer, so the "
                    + "persistence layer cannot determine a dialect from it. Supply a MySQL server, "
                    + "or a DATABASE_URL naming the postgresql or h2 scheme.");
        }

        log.info("Server product behind the pool reported as '{}' version '{}'.",
                reportedProduct, reportedVersion);
    }

    /**
     * Reports whether a JDBC URL selects the vendor {@code com.mysql:mysql-connector-j} serves.
     *
     * @param jdbcUrl the translated JDBC URL, possibly {@code null}
     * @return {@code true} when the URL opens with the MySQL vendor prefix, in any letter case
     */
    private static boolean namesMysqlVendor(String jdbcUrl) {
        return jdbcUrl != null
                && jdbcUrl.toLowerCase(Locale.ROOT).startsWith(MYSQL_JDBC_PREFIX);
    }

    /**
     * Reports whether the metadata a connection published names a server product the resolved driver
     * cannot serve.
     *
     * <p>Both members are examined because a MariaDB server reached through Connector/J publishes
     * {@code MySQL} as its product name for compatibility and names itself in the product version
     * instead — DL-304.
     *
     * @param productName    value of {@code DatabaseMetaData.getDatabaseProductName()}, possibly
     *                       {@code null}
     * @param productVersion value of {@code DatabaseMetaData.getDatabaseProductVersion()}, possibly
     *                       {@code null}
     * @return {@code true} when either member names the unservable product
     */
    static boolean reportsUnservableServerProduct(String productName, String productVersion) {
        return names(productName) || names(productVersion);
    }

    /**
     * Reports whether one metadata member names the unservable server product.
     *
     * @param reported the reported member, possibly {@code null}
     * @return {@code true} when the member carries the product token in any letter case
     */
    private static boolean names(String reported) {
        return reported != null
                && reported.toLowerCase(Locale.ROOT).contains(UNSERVABLE_SERVER_PRODUCT);
    }


    // Net-new startup diagnostic — DL-305 — see docs/DECISION_LOG.md
    /**
     * Reports a startup failure of the JDBC and dialect chain as a diagnostic naming
     * {@code scanner.database-url}.
     *
     * <p>The persistence layer determines its dialect from the connection metadata rather than from a
     * configured platform — DL-027 — so a driver that cannot be resolved, a server that cannot be
     * reached and a server whose metadata cannot be read all end in one Hibernate failure that names
     * neither the configuration key that produced the connection nor the underlying error. This
     * analyzer replaces that report with the key, the failure that caused it and the remediation.
     *
     * <p>It applies to exactly two shapes and nothing else: a failure whose cause chain carries a
     * {@link SQLException}, and a failure whose chain carries the dialect-determination message. A
     * refusal raised by {@link DatabaseUrlTranslator} or by
     * {@link DataSourceConfig#verifyServerProduct(DataSource, String)} already names its key and its
     * remedy, carries neither shape, and is therefore left to the framework's own reporting.
     *
     * <p>No JDBC URL reaches the analysis: the driver's own message may embed the URL it was given, so
     * every {@code jdbc:}-prefixed token in it is replaced before the message is carried, and the
     * result passes through {@link #logSafe(String, int)} — DL-052, DL-052.
     *
     * <p>Spring Boot instantiates this class through {@code META-INF/spring.factories} and calls it on
     * the failure path only. It holds no state.
     */
    public static class DatabaseStartupFailureAnalyzer implements FailureAnalyzer {

        /** Dialect-determination failure this analyzer recognises, matched case-insensitively. */
        private static final String DIALECT_MARKER = "unable to determine dialect";

        /** Stands in for a {@code jdbc:} token the underlying message carried — DL-052. */
        private static final String REDACTED_URL = "***REDACTED***";

        /** Greatest number of characters of an underlying message the analysis carries — DL-052. */
        private static final int MESSAGE_LIMIT = 240;

        /** Greatest number of causes walked, so a self-referencing chain cannot loop. */
        private static final int MAXIMUM_CHAIN_DEPTH = 64;

        /** Remediation the analysis offers, which names the key rather than any configured value. */
        private static final String ACTION = """
                Check the value of DATABASE_URL, which binds scanner.database-url:
                  - it must name one of the schemes postgresql, postgres, mysql, mariadb or h2, or \
                already be a jdbc: URL whose driver this artifact ships;
                  - the host, port and database it names must be reachable from this process, and the \
                server must be running;
                  - credentials belong in its user-info component, as scheme://user:password@host/db; \
                a value already in jdbc: form carries none;
                  - the server product must be PostgreSQL, MySQL or H2, since the dialect is read from \
                the server's own metadata rather than configured.""";

        /**
         * Produces the analysis for a failure of the JDBC and dialect chain.
         *
         * @param failure the startup failure, never {@code null}
         * @return the analysis, or {@code null} when the failure is not one this analyzer recognises
         */
        @Override
        public FailureAnalysis analyze(Throwable failure) {
            Throwable cause = databaseCause(failure);
            if (cause == null) {
                return null;
            }
            return new FailureAnalysis(description(cause), ACTION, failure);
        }

        /**
         * Finds the cause that makes a failure one of the two recognised shapes.
         *
         * <p>The deepest {@link SQLException} is preferred, because it is the driver's own report and
         * the most specific. A chain carrying none is recognised by the dialect-determination message,
         * and there too the deepest carrier is returned: a wrapping bean-creation failure repeats its
         * cause's text inside its own message, so the outermost carrier would describe the wrapper
         * rather than the failure.
         *
         * @param failure the startup failure, possibly {@code null}
         * @return the cause to describe, or {@code null} when neither shape is present
         */
        private static Throwable databaseCause(Throwable failure) {
            Throwable sqlFailure = null;
            Throwable dialectFailure = null;

            Throwable current = failure;
            for (int depth = 0; current != null && depth < MAXIMUM_CHAIN_DEPTH; depth++) {
                if (current instanceof SQLException) {
                    sqlFailure = current;
                }
                if (namesDialectFailure(current.getMessage())) {
                    dialectFailure = current;
                }
                current = (current.getCause() == current) ? null : current.getCause();
            }
            return sqlFailure != null ? sqlFailure : dialectFailure;
        }

        /**
         * Reports whether a message is the dialect-determination failure.
         *
         * @param message the message to examine, possibly {@code null}
         * @return {@code true} when the message names that failure, in any letter case
         */
        private static boolean namesDialectFailure(String message) {
            return message != null && message.toLowerCase(Locale.ROOT).contains(DIALECT_MARKER);
        }

        /**
         * Renders the description, naming the key and the underlying failure.
         *
         * @param cause the cause to describe, never {@code null}
         * @return the description, carrying no JDBC URL
         */
        private static String description(Throwable cause) {
            StringBuilder described = new StringBuilder()
                    .append("The persistence layer could not obtain a usable connection from the "
                            + "database URL configured as scanner.database-url, which binds the "
                            + "DATABASE_URL environment variable. The dialect is read from the "
                            + "server's own connection metadata rather than configured, so a driver "
                            + "that cannot be resolved, a server that cannot be reached and metadata "
                            + "that cannot be read all end here.")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("Underlying failure: ")
                    .append(cause.getClass().getSimpleName());

            if (cause instanceof SQLException sqlFailure && sqlFailure.getSQLState() != null) {
                described.append(" (SQLState ")
                        .append(logSafe(sqlFailure.getSQLState(), SERVER_PRODUCT_LIMIT))
                        .append(')');
            }
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                described.append(": ").append(logSafe(
                        withoutJdbcUrls(withoutParentheticalAdvice(message)), MESSAGE_LIMIT));
            }
            return described.append('.').toString();
        }

        /**
         * Cuts a message at its first parenthesis.
         *
         * <p>Both the persistence layer and the drivers put remediation inside a parenthesis, and the
         * remediation they suggest — configuring a dialect — is the one this service deliberately does
         * not use (DL-027). Remediation is the {@link #ACTION} field's subject, so the parenthesised
         * segment is dropped rather than carried into a description that would then contradict it.
         *
         * @param message the underlying message, never {@code null}
         * @return the message up to its first parenthesis, stripped, or the whole message when it
         *     carries none
         */
        private static String withoutParentheticalAdvice(String message) {
            int parenthesis = message.indexOf(" (");
            return (parenthesis < 0) ? message.strip() : message.substring(0, parenthesis).strip();
        }

        /**
         * Replaces every {@code jdbc:}-prefixed token of a message, so a driver message that embeds
         * the URL it was given does not carry it into the analysis — DL-052.
         *
         * @param message the underlying message, never {@code null}
         * @return the message with every such token replaced by {@value #REDACTED_URL}
         */
        private static String withoutJdbcUrls(String message) {
            StringBuilder redacted = new StringBuilder(message.length());
            for (String token : message.split(" ", -1)) {
                if (!redacted.isEmpty()) {
                    redacted.append(' ');
                }
                redacted.append(token.toLowerCase(Locale.ROOT).startsWith("jdbc:")
                        ? REDACTED_URL
                        : token);
            }
            return redacted.toString();
        }
    }

    /**
     * Renders a value for a diagnostic: bounded in length and free of every character that could forge
     * a record boundary.
     *
     * <p>A {@code null} value renders as {@value #ABSENT}. Every character outside printable ASCII is
     * replaced, and a value longer than {@code limit} is cut at it.
     *
     * @param value the value to render, possibly {@code null}
     * @param limit greatest number of characters the rendering carries
     * @return the rendered value, never {@code null}
     */
    // The log-safety policy of DL-052 applied where this class reports a value — see
    // docs/DECISION_LOG.md
    private static String logSafe(String value, int limit) {
        if (value == null) {
            return ABSENT;
        }
        StringBuilder rendered = new StringBuilder(Math.min(value.length(), limit));
        for (int index = 0; index < value.length() && rendered.length() < limit; index++) {
            char character = value.charAt(index);
            rendered.append((character >= 0x20 && character <= 0x7E) ? character : '?');
        }
        return rendered.toString();
    }

}
