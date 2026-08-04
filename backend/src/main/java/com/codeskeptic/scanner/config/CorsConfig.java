package com.codeskeptic.scanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin resource sharing (CORS) configuration for the backend service.
 *
 * <p>Ported from {@code CORS(app)} at {@code backend/app/main.py:L20} (faithful
 * port). Flask-CORS was invoked there with no arguments at all: no
 * {@code origins}, no {@code methods}, no {@code allow_headers}, no
 * {@code supports_credentials} and no resource pattern, which applies a
 * permissive policy across every registered route. This class reproduces that
 * policy for every path the service serves.
 *
 * <p>The single {@link CorsConfigurationSource} bean published here is the only
 * CORS mechanism this application declares. Its bean name,
 * {@code corsConfigurationSource}, is the name Spring Security's
 * {@code CorsConfigurer} looks up, and {@code SecurityConfig} wires it into the
 * {@code SecurityFilterChain} — mirroring the source ordering in which
 * {@code CORS(app)} at {@code backend/app/main.py:L20} precedes
 * {@code JWTManager(app)} at {@code backend/app/main.py:L22}. Spring MVC also
 * registers {@code mvcHandlerMappingIntrospector}, a second bean of that same
 * interface type in a running context.
 *
 * <p>Decisions covering this class are recorded in {@code docs/DECISION_LOG.md}
 * DL-051 and DL-058; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 */
@Configuration
public class CorsConfig {

    /**
     * Path pattern the ported policy is registered against. Matches every
     * request path, covering all eleven endpoints the service exposes.
     */
    private static final String ALL_PATHS = "/**";

    /**
     * Publishes the application's CORS policy.
     *
     * <p>Mirrors the Flask-CORS defaults in force at
     * {@code backend/app/main.py:L20}: every origin, every HTTP method and every
     * request header is permitted. {@code allowCredentials} is left unset,
     * mirroring the {@code supports_credentials=False} default; {@code maxAge}
     * and {@code exposedHeaders} are left unset, mirroring the
     * {@code max_age=None} and {@code expose_headers=None} defaults.
     *
     * <p>The returned source is fully populated before it is published and is
     * never mutated afterwards. It is safe to share across concurrent requests.
     *
     * @return the {@link CorsConfigurationSource} consumed by the security
     *         filter chain; never {@code null}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(CorsConfiguration.ALL);
        configuration.addAllowedMethod(CorsConfiguration.ALL);
        configuration.addAllowedHeader(CorsConfiguration.ALL);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ALL_PATHS, configuration);
        return source;
    }
}
