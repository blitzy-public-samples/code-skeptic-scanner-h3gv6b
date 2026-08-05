package com.codeskeptic.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point and composition root of the Code Skeptic Scanner backend service.
 *
 * <p>Replaces the {@code create_app()} factory at {@code backend/app/main.py:L15-39} and the
 * module-level {@code Flask} object at {@code backend/app/main.py:L13}.
 *
 * <p>{@link SpringBootApplication} places the component-scan root at this class's own package,
 * {@code com.codeskeptic.scanner}, so the scanned set includes {@code api}, {@code config},
 * {@code dto}, {@code entity}, {@code exception}, {@code repository}, {@code security},
 * {@code service}, {@code service.mapper}, {@code task} and {@code util}. The
 * {@code @RestController} classes in {@code api} register their routes through that scan, in place of
 * the four {@code app.register_blueprint(...)} calls at {@code backend/app/main.py:L26-29}.
 * {@link ConfigurationPropertiesScan} registers
 * {@link com.codeskeptic.scanner.config.ScannerProperties}, in place of {@code get_settings()} and
 * {@code app.config.from_object(settings)} at {@code backend/app/main.py:L16-18}.
 *
 * <p>The remaining concerns the Flask module handled inline are declared elsewhere in the tree:
 *
 * <ul>
 *   <li>Cross-origin policy - {@code config.CorsConfig}, replacing {@code CORS(app)} at
 *       {@code backend/app/main.py:L20}.
 *   <li>Authentication - {@code security.SecurityConfig}, replacing {@code JWTManager(app)} at
 *       {@code backend/app/main.py:L22}.
 *   <li>The data source - {@code config.DataSourceConfig}, replacing
 *       {@code app.db = get_db_connection()} at {@code backend/app/main.py:L24}.
 *   <li>The 404 and 500 error bodies - {@code api.GlobalExceptionHandler}, replacing the
 *       {@code @app.errorhandler} functions at {@code backend/app/main.py:L31-37}.
 *   <li>Scheduling - {@code @EnableScheduling} on {@code config.AsyncSchedulingConfig}; the periodic
 *       job is {@code task.ResponseGenerationScheduler}, replacing
 *       {@code backend/app/tasks/response_generation.py:L35-50}.
 *   <li>Stream ingestion - {@code task.TweetStreamClient}, replacing
 *       {@code backend/app/tasks/tweet_monitoring.py:L36-55}.
 * </ul>
 *
 * <p>The listen port is {@code server.port} in {@code src/main/resources/application.yml}, which
 * resolves {@code ${PORT:5000}}; the servlet stack is selected by
 * {@code spring.main.web-application-type} in that same file.
 *
 * <p>See {@code docs/DECISION_LOG.md} and {@code docs/TRACEABILITY_MATRIX.md}.
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
}
