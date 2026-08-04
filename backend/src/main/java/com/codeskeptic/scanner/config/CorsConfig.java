package com.codeskeptic.scanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cross-origin resource sharing (CORS) configuration for the backend service.
 *
 * <p>Reproduces the permissive policy of {@code CORS(app)} at {@code backend/app/main.py:L20}, which
 * was invoked with no arguments at all: no {@code origins}, no {@code methods}, no
 * {@code allow_headers}, no {@code supports_credentials} and no resource pattern.
 *
 * <p>The {@link CorsConfigurationSource} bean published here is the only CORS mechanism this
 * application declares. Its bean name is {@code corsConfigurationSource}, the name Spring Security's
 * {@code CorsConfigurer} looks up. Note that Spring MVC also registers
 * {@code mvcHandlerMappingIntrospector}, a second bean of that same interface type in a running
 * context.
 */
// Ported from backend/app/main.py:L20 (faithful port) — see docs/DECISION_LOG.md DL-051
@Configuration
public class CorsConfig {

    private static final String ALL_PATHS = "/**";

    /**
     * Publishes the application's CORS policy for every request path.
     *
     * <p>Every origin, every HTTP method and every request header is permitted.
     * {@code allowCredentials}, {@code maxAge} and {@code exposedHeaders} are left unset, mirroring
     * the Flask-CORS {@code supports_credentials=False}, {@code max_age=None} and
     * {@code expose_headers=None} defaults in force at {@code backend/app/main.py:L20}.
     *
     * <p>The returned source is fully populated before it is published and is never mutated
     * afterwards, so it is safe to share across concurrent requests.
     *
     * @return the {@link CorsConfigurationSource} for all paths; never {@code null}
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
