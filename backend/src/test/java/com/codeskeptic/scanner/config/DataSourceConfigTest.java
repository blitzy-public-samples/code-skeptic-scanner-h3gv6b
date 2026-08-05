package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

// Net-new (no Python counterpart: backend/app/db/database.py:L5-8 returned a fresh unpooled engine
// per call and configured nothing) — DL-027, DL-229 — see docs/DECISION_LOG.md
/**
 * Exercises the pool {@link DataSourceConfig} publishes: the values it takes from
 * {@code scanner.database-url} and the values a deployment supplies through
 * {@code spring.datasource.hikari.*}.
 *
 * <p>The context holds {@link DataSourceConfig} and the bound {@link ScannerProperties} only, so no
 * persistence unit is built. HikariCP opens no connection until one is requested, so no test here
 * reaches a database.
 */
@DisplayName("DataSourceConfig")
class DataSourceConfigTest {

    /** SQLAlchemy-style value the translator turns into a JDBC URL plus a credential pair. */
    private static final String CONFIGURED_URL =
            "postgresql://scanner:s3cret@db.internal:5432/codeskeptic";

    /** JDBC form of {@link #CONFIGURED_URL}. */
    private static final String TRANSLATED_URL = "jdbc:postgresql://db.internal:5432/codeskeptic";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class, DataSourceConfig.class);

    // DL-027 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("builds the pool from the translated scanner.database-url and leaves the HikariCP "
            + "defaults in place")
    void buildsThePoolFromTheTranslatedDatabaseUrl() {
        contextRunner.withPropertyValues("scanner.database-url=" + CONFIGURED_URL)
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getJdbcUrl()).as("JDBC URL of the published pool")
                            .isEqualTo(TRANSLATED_URL);
                    assertThat(pool.getUsername()).as("username of the published pool")
                            .isEqualTo("scanner");
                    assertThat(pool.getPassword()).as("password of the published pool")
                            .isEqualTo("s3cret");
                    assertThat(pool.getMaximumPoolSize()).as("default maximum pool size")
                            .isEqualTo(10);
                    assertThat(pool.getPoolName()).as("pool name this class configures").isNull();
                });
    }

    // Every spring.datasource.hikari.* property reaches the published pool — DL-229 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("applies the maximum pool size, the minimum idle count, the pool name and the leak "
            + "detection threshold a deployment configures")
    void appliesTheConfiguredPoolGeometry() {
        contextRunner.withPropertyValues(
                        "scanner.database-url=" + CONFIGURED_URL,
                        "spring.datasource.hikari.maximum-pool-size=7",
                        "spring.datasource.hikari.minimum-idle=2",
                        "spring.datasource.hikari.pool-name=QA-PROBE-POOL",
                        "spring.datasource.hikari.leak-detection-threshold=20000",
                        "spring.datasource.hikari.connection-timeout=15000",
                        "spring.datasource.hikari.register-mbeans=true")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getMaximumPoolSize()).as("configured maximum pool size")
                            .isEqualTo(7);
                    assertThat(pool.getMinimumIdle()).as("configured minimum idle count").isEqualTo(2);
                    assertThat(pool.getPoolName()).as("configured pool name")
                            .isEqualTo("QA-PROBE-POOL");
                    assertThat(pool.getLeakDetectionThreshold())
                            .as("configured leak detection threshold").isEqualTo(20_000L);
                    assertThat(pool.getConnectionTimeout()).as("configured connection timeout")
                            .isEqualTo(15_000L);
                    assertThat(pool.isRegisterMbeans()).as("configured MBean registration").isTrue();

                    assertThat(pool.getJdbcUrl())
                            .as("JDBC URL of a pool whose geometry was configured")
                            .isEqualTo(TRANSLATED_URL);
                });
    }

    // spring.datasource.url and its siblings stay unread — DL-027 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("ignores spring.datasource.url, username and password")
    void ignoresTheSpringDataSourceConnectionProperties() {
        contextRunner.withPropertyValues(
                        "scanner.database-url=" + CONFIGURED_URL,
                        "spring.datasource.url=jdbc:postgresql://unused.example/other",
                        "spring.datasource.username=unused",
                        "spring.datasource.password=unused")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getJdbcUrl()).as("JDBC URL of the published pool")
                            .isEqualTo(TRANSLATED_URL);
                    assertThat(pool.getUsername()).as("username of the published pool")
                            .isEqualTo("scanner");
                });
    }

    // A value that carries no supported scheme refuses to start — DL-027 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses to publish a pool for a database URL naming an unsupported scheme")
    void refusesToPublishAPoolForAnUnsupportedScheme() {
        contextRunner.withPropertyValues("scanner.database-url=oracle://host/service")
                .run(context -> assertThat(context)
                        .as("context built from an unsupported database URL").hasFailed());
    }

    /** Registers the bound configuration root {@link DataSourceConfig} takes. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ScannerProperties.class)
    static class BoundProperties {
    }
}
