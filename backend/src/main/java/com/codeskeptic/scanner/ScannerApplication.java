package com.codeskeptic.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point and composition root of the Code Skeptic Scanner backend service.
 *
 * <p>Replaces the {@code create_app()} factory at {@code backend/app/main.py:L15-39} together with
 * the module-level {@code Flask} object at {@code backend/app/main.py:L13} that the factory
 * configured and returned. This application has exactly one {@code ApplicationContext}.
 *
 * <p>{@link SpringBootApplication} places the component-scan root at this class's own package,
 * {@code com.codeskeptic.scanner}, which puts every sibling package within the scanned set:
 * {@code api}, {@code config}, {@code dto}, {@code entity}, {@code exception}, {@code repository},
 * {@code security}, {@code service}, {@code service.mapper}, {@code task} and {@code util}. The
 * {@code @RestController} classes in {@code api} register their routes through that scan; the four
 * {@code app.register_blueprint(...)} calls at {@code backend/app/main.py:L26-29} have no
 * counterpart here. {@link ConfigurationPropertiesScan} registers
 * {@link com.codeskeptic.scanner.config.ScannerProperties} from its {@code @ConfigurationProperties}
 * annotation alone, replacing {@code get_settings()} and {@code app.config.from_object(settings)} at
 * {@code backend/app/main.py:L16-18}; beans reach those values by constructor injection.
 *
 * <p>Every remaining concern that the Flask module handled inline is declared elsewhere in the tree:
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
 *   <li>Scheduling - {@code @EnableScheduling} is declared on {@code config.AsyncSchedulingConfig}
 *       and on no other class in this application; the periodic job is
 *       {@code task.ResponseGenerationScheduler}.
 *   <li>Stream ingestion - {@code task.TweetStreamClient}, which starts after context refresh and
 *       stops on context close.
 * </ul>
 *
 * <p>This class declares no bean, reads no configuration key, injects no collaborator, holds no
 * state and starts no thread. It declares no {@code ApplicationRunner}, no
 * {@code CommandLineRunner} and no {@code @PostConstruct} method;
 * {@code initialize_background_tasks()} at {@code backend/app/main.py:L43-48} has no counterpart
 * here. The listen port is {@code server.port} in {@code src/main/resources/application.yml}, which
 * resolves {@code ${PORT:5000}}, and the servlet stack is selected by
 * {@code spring.main.web-application-type} in that same file; neither is set programmatically.
 *
 * <p>See {@code docs/DECISION_LOG.md} and {@code docs/TRACEABILITY_MATRIX.md}.
 */
// Ported from backend/app/main.py:L15-39 (faithful port) — see docs/DECISION_LOG.md
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
