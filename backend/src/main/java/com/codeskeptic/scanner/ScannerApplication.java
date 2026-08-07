package com.codeskeptic.scanner;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Entry point and composition root of the Code Skeptic Scanner backend service.
 *
 * <p>Replaces the {@code create_app()} factory at {@code backend/app/main.py:L15-39} and the
 * module-level {@code Flask} object at {@code :L13}. {@link SpringBootApplication} places the
 * component-scan root at this class's own package, so the {@code @RestController} classes in
 * {@code api} register their routes through that scan in place of the four
 * {@code app.register_blueprint(...)} calls at {@code :L26-29}, and
 * {@link ConfigurationPropertiesScan} registers
 * {@link com.codeskeptic.scanner.config.ScannerProperties} in place of {@code get_settings()} and
 * {@code app.config.from_object(settings)} at {@code :L16-18} — DL-209.
 *
 * <p>The remaining concerns the Flask module handled inline are declared elsewhere in the tree:
 * {@code config.CorsConfig} for {@code CORS(app)} ({@code :L20}), {@code security.SecurityConfig} for
 * {@code JWTManager(app)} ({@code :L22}), {@code config.DataSourceConfig} for
 * {@code app.db = get_db_connection()} ({@code :L24}), {@code api.GlobalExceptionHandler} for the
 * {@code @app.errorhandler} functions ({@code :L31-37}), {@code @EnableScheduling} on
 * {@code config.AsyncSchedulingConfig} with {@code task.ResponseGenerationScheduler} as the periodic
 * job ({@code backend/app/tasks/response_generation.py:L35-50}), and {@code task.TweetStreamClient}
 * for ingestion ({@code backend/app/tasks/tweet_monitoring.py:L36-55}).
 *
 * <p>The listen port is {@code server.port} in {@code src/main/resources/application.yml}, resolving
 * {@code ${PORT:5000}}; the servlet stack is selected by {@code spring.main.web-application-type} in
 * that same file.
 */
// Ported from backend/app/main.py:L15-39 (faithful port) — DL-209 — see docs/DECISION_LOG.md
@SpringBootApplication
@ConfigurationPropertiesScan
public class ScannerApplication {

    /**
     * Boots the Spring application context and starts the embedded servlet container.
     *
     * <p>Replaces {@code app.run(debug=True)} at {@code backend/app/main.py:L53}.
     *
     * @param args the process command-line arguments, forwarded verbatim to
     *     {@link SpringApplication#run(Class, String...)}, which exposes them to the context as a
     *     command-line property source
     */
    public static void main(String[] args) {
        SpringApplication.run(ScannerApplication.class, args);
    }

    /**
     * Publishes the UTC time source every reader of the current instant resolves.
     *
     * <p>{@code service.AnalyticsService} is the consumer: it derives the
     * {@code GET /analytics/trends} observation window from this clock. Scheduling reads its own clock
     * from the framework and does not resolve this bean.
     *
     * <p>The published instance is immutable and safe for concurrent use.
     *
     * @return the single {@link Clock} bean in the application context, resolvable by type and by the
     *     name {@code utcClock}; never {@code null}
     */
    // Net-new (no Python counterpart: backend/app/api/analytics.py:L13-14 read no clock and the
    // AnalyticsService it imported did not exist) — DL-278 — see docs/DECISION_LOG.md
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
