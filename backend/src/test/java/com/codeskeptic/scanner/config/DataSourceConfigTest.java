package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

// Net-new (no Python counterpart: backend/app/db/database.py:L5-8 returned a fresh unpooled engine
// per call and configured nothing) — DL-027, DL-270, DL-271 — see docs/DECISION_LOG.md
/**
 * Exercises the pool {@link DataSourceConfig} publishes: the connection identity it takes from
 * {@code scanner.database-url}, the geometry it takes from the allowlist
 * {@code scanner.datasource.pool.*}, and its refusal of the {@code spring.datasource.*} surface.
 *
 * <p>The context holds {@link DataSourceConfig} and the two bound property records only, so no
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

    /** Property assignment naming the configured database URL. */
    private static final String URL_PROPERTY = "scanner.database-url=" + CONFIGURED_URL;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class, DataSourceConfig.class);

    // DL-027 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("builds the pool from the translated scanner.database-url")
    void buildsThePoolFromTheTranslatedDatabaseUrl() {
        contextRunner.withPropertyValues(URL_PROPERTY)
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getJdbcUrl()).as("JDBC URL of the published pool")
                            .isEqualTo(TRANSLATED_URL);
                    assertThat(pool.getUsername()).as("username of the published pool")
                            .isEqualTo("scanner");
                    assertThat(pool.getPassword()).as("password of the published pool")
                            .isEqualTo("s3cret");
                });
    }

    // The declared pool defaults, not HikariCP's — DL-271 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("applies the declared pool defaults, keeping fewer connections idle than HikariCP "
            + "would")
    void appliesTheDeclaredPoolDefaults() {
        contextRunner.withPropertyValues(URL_PROPERTY)
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getMaximumPoolSize()).as("declared maximum pool size")
                            .isEqualTo(10);
                    assertThat(pool.getMinimumIdle()).as("declared minimum idle count").isEqualTo(2);
                    assertThat(pool.getMinimumIdle())
                            .as("fewer idle connections than HikariCP's own default of ten")
                            .isLessThan(pool.getMaximumPoolSize());
                    assertThat(pool.getConnectionTimeout()).as("declared connection timeout")
                            .isEqualTo(30_000L);
                    assertThat(pool.getValidationTimeout()).as("declared validation timeout")
                            .isEqualTo(5_000L);
                    assertThat(pool.getIdleTimeout()).as("declared idle timeout")
                            .isEqualTo(600_000L);
                    assertThat(pool.getMaxLifetime()).as("declared maximum lifetime")
                            .isEqualTo(1_800_000L);
                    assertThat(pool.getLeakDetectionThreshold())
                            .as("leak detection off by default").isZero();
                    assertThat(pool.getPoolName()).as("declared pool name")
                            .isEqualTo(DataSourcePoolProperties.DEFAULT_POOL_NAME);
                });
    }

    // Every allowlisted key reaches the published pool — DL-270 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("applies every pool setting a deployment configures under the allowlisted prefix")
    void appliesTheConfiguredPoolGeometry() {
        contextRunner.withPropertyValues(
                        URL_PROPERTY,
                        "scanner.datasource.pool.maximum-size=7",
                        "scanner.datasource.pool.minimum-idle=3",
                        "scanner.datasource.pool.connection-timeout-millis=15000",
                        "scanner.datasource.pool.validation-timeout-millis=2500",
                        "scanner.datasource.pool.idle-timeout-millis=60000",
                        "scanner.datasource.pool.max-lifetime-millis=120000",
                        "scanner.datasource.pool.leak-detection-threshold-millis=20000",
                        "scanner.datasource.pool.name=QA-PROBE-POOL")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getMaximumPoolSize()).isEqualTo(7);
                    assertThat(pool.getMinimumIdle()).isEqualTo(3);
                    assertThat(pool.getConnectionTimeout()).isEqualTo(15_000L);
                    assertThat(pool.getValidationTimeout()).isEqualTo(2_500L);
                    assertThat(pool.getIdleTimeout()).isEqualTo(60_000L);
                    assertThat(pool.getMaxLifetime()).isEqualTo(120_000L);
                    assertThat(pool.getLeakDetectionThreshold()).isEqualTo(20_000L);
                    assertThat(pool.getPoolName()).isEqualTo("QA-PROBE-POOL");

                    assertThat(pool.getJdbcUrl())
                            .as("JDBC URL of a pool whose geometry was configured")
                            .isEqualTo(TRANSLATED_URL);
                });
    }

    // A connection-identity key cannot bypass the translator — DL-270 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "spring.datasource.url=jdbc:postgresql://unused.example/other",
        "spring.datasource.jdbc-url=jdbc:postgresql://unused.example/other",
        "spring.datasource.username=unused",
        "spring.datasource.password=unused",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.jndi-name=java:comp/env/jdbc/other",
        "spring.datasource.hikari.jdbc-url=jdbc:postgresql://unused.example/other",
        "spring.datasource.hikari.username=unused",
        "spring.datasource.hikari.password=unused",
        "spring.datasource.hikari.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.data-source-class-name=org.postgresql.ds.PGSimpleDataSource",
        "spring.datasource.hikari.data-source-jndi=java:comp/env/jdbc/other",
        "spring.datasource.hikari.data-source-properties.currentSchema=other",
        "spring.datasource.hikari.connection-init-sql=SET ROLE other",
        "spring.datasource.hikari.catalog=other",
        "spring.datasource.hikari.schema=other",
    })
    @DisplayName("refuses to start when a connection-identity property is configured")
    void refusesToStartWhenAConnectionIdentityPropertyIsConfigured(String forbidden) {
        String key = forbidden.substring(0, forbidden.indexOf('='));

        contextRunner.withPropertyValues(URL_PROPERTY, forbidden)
                .run(context -> {
                    assertThat(context).as("context carrying " + key).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining(key)
                            .hasMessageContaining("scanner.database-url");
                });
    }

    @Test
    @DisplayName("names every configured connection-identity property in one failure")
    void namesEveryConfiguredConnectionIdentityPropertyInOneFailure() {
        contextRunner.withPropertyValues(
                        URL_PROPERTY,
                        "spring.datasource.url=jdbc:postgresql://unused.example/other",
                        "spring.datasource.username=unused",
                        "spring.datasource.password=unused")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("spring.datasource.url")
                            .hasMessageContaining("spring.datasource.username")
                            .hasMessageContaining("spring.datasource.password");
                });
    }

    // A geometry key under the unread prefix takes no effect — DL-270 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("ignores a pool setting configured under the unread spring.datasource prefix")
    void ignoresAPoolSettingConfiguredUnderTheUnreadPrefix() {
        contextRunner.withPropertyValues(
                        URL_PROPERTY,
                        "spring.datasource.hikari.maximum-pool-size=97",
                        "spring.datasource.hikari.pool-name=IGNORED-POOL")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getMaximumPoolSize()).as("the unread key took no effect")
                            .isEqualTo(10);
                    assertThat(pool.getPoolName()).as("the unread key took no effect")
                            .isEqualTo(DataSourcePoolProperties.DEFAULT_POOL_NAME);
                });
    }

    // Every bound refuses a value outside its range — DL-271 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "scanner.datasource.pool.maximum-size=0",
        "scanner.datasource.pool.maximum-size=101",
        "scanner.datasource.pool.minimum-idle=-1",
        "scanner.datasource.pool.minimum-idle=11",
        "scanner.datasource.pool.connection-timeout-millis=249",
        "scanner.datasource.pool.validation-timeout-millis=0",
        "scanner.datasource.pool.idle-timeout-millis=9999",
        "scanner.datasource.pool.idle-timeout-millis=-1",
        "scanner.datasource.pool.max-lifetime-millis=29999",
        "scanner.datasource.pool.leak-detection-threshold-millis=1999",
    })
    @DisplayName("refuses to start when a pool bound lies outside its accepted range")
    void refusesToStartWhenAPoolBoundLiesOutsideItsAcceptedRange(String outOfRange) {
        String key = outOfRange.substring(0, outOfRange.indexOf('='));

        contextRunner.withPropertyValues(URL_PROPERTY, outOfRange)
                .run(context -> {
                    assertThat(context).as("context carrying " + outOfRange).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(key);
                });
    }

    @Test
    @DisplayName("accepts a minimum idle count equal to the maximum size")
    void acceptsAMinimumIdleCountEqualToTheMaximumSize() {
        contextRunner.withPropertyValues(
                        URL_PROPERTY,
                        "scanner.datasource.pool.maximum-size=5",
                        "scanner.datasource.pool.minimum-idle=5")
                .run(context -> assertThat(context.getBean(HikariDataSource.class).getMinimumIdle())
                        .isEqualTo(5));
    }

    @Test
    @DisplayName("accepts zero for the idle timeout, the maximum lifetime and leak detection")
    void acceptsZeroForTheOptionalBounds() {
        contextRunner.withPropertyValues(
                        URL_PROPERTY,
                        "scanner.datasource.pool.idle-timeout-millis=0",
                        "scanner.datasource.pool.max-lifetime-millis=0",
                        "scanner.datasource.pool.leak-detection-threshold-millis=0")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);

                    assertThat(pool.getIdleTimeout()).isZero();
                    assertThat(pool.getMaxLifetime()).isZero();
                    assertThat(pool.getLeakDetectionThreshold()).isZero();
                });
    }

    @Test
    @DisplayName("names the pool with the declared default when the configured name is blank")
    void namesThePoolWithTheDeclaredDefaultWhenTheConfiguredNameIsBlank() {
        contextRunner.withPropertyValues(URL_PROPERTY, "scanner.datasource.pool.name=   ")
                .run(context -> assertThat(context.getBean(HikariDataSource.class).getPoolName())
                        .isEqualTo(DataSourcePoolProperties.DEFAULT_POOL_NAME));
    }

    @Test
    @DisplayName("strips surrounding whitespace from the configured pool name")
    void stripsSurroundingWhitespaceFromTheConfiguredPoolName() {
        contextRunner.withPropertyValues(URL_PROPERTY, "scanner.datasource.pool.name=  QA-POOL  ")
                .run(context -> assertThat(context.getBean(HikariDataSource.class).getPoolName())
                        .isEqualTo("QA-POOL"));
    }

    // A value that carries no supported scheme refuses to start — DL-027 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses to publish a pool for a database URL naming an unsupported scheme")
    void refusesToPublishAPoolForAnUnsupportedScheme() {
        contextRunner.withPropertyValues("scanner.database-url=oracle://host/service")
                .run(context -> assertThat(context)
                        .as("context built from an unsupported database URL").hasFailed());
    }

    /** Registers the two bound configuration records {@link DataSourceConfig} takes. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ScannerProperties.class, DataSourcePoolProperties.class})
    static class BoundProperties {
    }
}
