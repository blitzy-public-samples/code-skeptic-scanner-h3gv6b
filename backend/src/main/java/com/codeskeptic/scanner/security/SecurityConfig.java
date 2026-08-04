package com.codeskeptic.scanner.security;

import java.util.Collections;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.config.ScannerProperties;

// Ported from backend/app/main.py:L22 (JWTManager) + backend/app/core/security.py:L14-18 (passlib
// bcrypt) + the eleven bare @jwt_required guards in backend/app/api/*.py (faithful port) — see
// docs/DECISION_LOG.md DL-019, DL-020, DL-021, DL-051
/**
 * Security composition root of the backend service: the one servlet filter chain and the three
 * collaborators that authenticate a request.
 *
 * <p>The single {@link SecurityFilterChain} declared here replaces {@code jwt = JWTManager(app)} at
 * {@code backend/app/main.py:L22} together with the eleven bare {@code @jwt_required} decorators at
 * {@code backend/app/api/tweets.py:L10,L24,L37}, {@code backend/app/api/responses.py:L9,L23,L34,L52},
 * {@code backend/app/api/settings.py:L8,L14} and {@code backend/app/api/analytics.py:L8,L18}. That
 * form, applied without parentheses, registers the decorator factory in {@code flask-jwt-extended}
 * 4.x and leaves the route unguarded. The chain declared here authenticates all eleven — DL-021.
 *
 * <p>Four beans are published, and no other:
 *
 * <ul>
 *   <li>{@link #securityFilterChain(HttpSecurity)} — the chain, carrying the authorization rules,
 *       the unauthenticated-request entry point and {@link JwtAuthenticationFilter}.
 *   <li>{@link #passwordEncoder()} — successor of the passlib bcrypt helpers at
 *       {@code backend/app/core/security.py:L14-18}, which no call site in the retired tree invoked.
 *   <li>{@link #userDetailsService()} — the {@code scanner.auth} credential store — DL-020.
 *   <li>{@link #authenticationManager(AuthenticationConfiguration)} — consumed by
 *       {@code com.codeskeptic.scanner.api.AuthController} to authenticate a
 *       {@code com.codeskeptic.scanner.dto.LoginRequest} — DL-019.
 * </ul>
 *
 * <p>The chain carries exactly two authorization rules. {@code POST /auth/token} is permitted with no
 * authentication and is the only such route in the service — DL-019. Every other request requires an
 * authenticated principal, which covers the eleven pre-existing routes: {@code GET /tweets},
 * {@code GET /tweets/{tweetId}}, {@code POST /tweets/{tweetId}/analyze}, {@code GET /responses},
 * {@code GET /responses/{responseId}}, {@code POST /responses},
 * {@code PUT /responses/{responseId}}, {@code GET /settings}, {@code PUT /settings/{key}},
 * {@code GET /analytics/trends} and {@code GET /analytics/summary}. Every path is unprefixed, as the
 * four Flask blueprints registered at {@code backend/app/main.py:L26-29} were.
 *
 * <p>Cross-origin policy is not declared in this class. The single {@code CorsConfigurationSource}
 * bean of {@code com.codeskeptic.scanner.config.CorsConfig}, which reproduces the argument-free
 * {@code CORS(app)} at {@code backend/app/main.py:L20}, is injected here and handed to the chain's
 * CORS configurer, ahead of the authorization rules — DL-051. The ordering matches
 * {@code backend/app/main.py:L20,L22}.
 *
 * <p>The chain is stateless: no HTTP session is created, CSRF protection is off, and HTTP Basic and
 * form login are both off. A request that reaches the authorization stage carrying no authentication
 * is answered by {@link HttpStatusEntryPoint} with status {@code 401} and an empty body.
 * {@code com.codeskeptic.scanner.api.GlobalExceptionHandler} owns the {@code {"error": <string>}}
 * envelopes of {@code backend/app/main.py:L31-37} and is reached only by exceptions raised inside the
 * {@code DispatcherServlet}.
 *
 * <p>The credential store holds exactly one principal, built from {@code scanner.auth.username} and
 * {@code scanner.auth.password-hash} and holding no authority. No table backs it, and the schema this
 * service creates stays the four tables of {@code backend/app/db/models.py} — DL-020. Neither the
 * principal name nor the password hash is written to the log, and construction fails with
 * {@link IllegalStateException} when {@code scanner.auth.password-hash} is absent or blank.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-019, DL-020, DL-021
 * and DL-051; construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton configuration class. All three fields are {@code final} and hold singleton
 * collaborators, and every bean published here is fully built before it is returned and never mutated
 * afterwards, so every member declared here is safe for concurrent use.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Path of the one route this chain permits with no authentication — DL-019. */
    private static final String TOKEN_ENDPOINT = "/auth/token";

    /**
     * Principal name applied when {@code scanner.auth.username} is absent or blank; the same default
     * {@code src/main/resources/application.yml} declares for {@code AUTH_USERNAME} — DL-020.
     */
    private static final String DEFAULT_USERNAME = "admin";

    /**
     * Message of the {@link IllegalStateException} raised when {@code scanner.auth.password-hash}
     * carries no value — DL-020.
     */
    private static final String MISSING_PASSWORD_HASH_MESSAGE =
            "scanner.auth.password-hash is not configured; supply a bcrypt hash of the application "
                    + "principal's password through the AUTH_PASSWORD_HASH environment variable. It "
                    + "has no default value.";

    /** Supplies the {@code scanner.auth} group that {@link #userDetailsService()} reads. */
    private final ScannerProperties properties;

    /** Handed to the {@link JwtAuthenticationFilter} this class adds to the chain. */
    private final JwtService jwtService;

    /**
     * The permissive policy published by {@code com.codeskeptic.scanner.config.CorsConfig} — DL-051.
     *
     * <p>The parameter name of the constructor argument that populates this field matches that bean's
     * name, {@code corsConfigurationSource}. Spring MVC publishes
     * {@code mvcHandlerMappingIntrospector}, a second bean implementing this same interface, in a
     * running servlet context.
     */
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Retains the three collaborators the beans below consume.
     *
     * @param properties the bound configuration root; its {@code scanner.auth} group supplies the
     *     single principal's name and password hash
     * @param jwtService verifies the bearer token presented on a request, for the
     *     {@link JwtAuthenticationFilter} added to the chain
     * @param corsConfigurationSource the application's CORS policy, published by
     *     {@code com.codeskeptic.scanner.config.CorsConfig}
     * @throws NullPointerException if any argument is {@code null}
     */
    public SecurityConfig(ScannerProperties properties, JwtService jwtService,
            CorsConfigurationSource corsConfigurationSource) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.corsConfigurationSource = Objects.requireNonNull(corsConfigurationSource,
                "corsConfigurationSource must not be null");
    }

    // Ported from backend/app/main.py:L20,L22 and the eleven bare @jwt_required guards in
    // backend/app/api/*.py (faithful port) — see docs/DECISION_LOG.md DL-019, DL-021, DL-051
    /**
     * Builds the application's only security filter chain.
     *
     * <p>The chain applies, in this order: the CORS policy injected from
     * {@code com.codeskeptic.scanner.config.CorsConfig}; CSRF protection off; stateless session
     * management; HTTP Basic and form login off; two authorization rules —
     * {@code POST /auth/token} permitted and every other request authenticated; a
     * {@link HttpStatusEntryPoint} answering {@code 401} with an empty body; and
     * {@link JwtAuthenticationFilter} positioned ahead of
     * {@link UsernamePasswordAuthenticationFilter}.
     *
     * <p>{@link JwtAuthenticationFilter} is constructed here and is not a bean, so it is registered
     * in this chain alone and never in the servlet container's own filter list.
     *
     * @param http the builder Spring Security supplies for this chain
     * @return the built chain; never {@code null}
     * @throws Exception if the builder cannot apply any element of the configuration above
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, TOKEN_ENDPOINT).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);

        log.info("Security filter chain built: POST {} is permitted unauthenticated; every other "
                + "request requires an authenticated principal, answered with {} when it carries "
                + "none", TOKEN_ENDPOINT, HttpStatus.UNAUTHORIZED.value());

        return http.build();
    }

    // Ported from backend/app/core/security.py:L14-18 (faithful port) — see docs/DECISION_LOG.md
    /**
     * Publishes the encoder that verifies a presented password against the stored hash.
     *
     * <p>Successor of {@code verify_password} at {@code backend/app/core/security.py:L14-15} and
     * {@code get_password_hash} at {@code :L17-18}, both built on {@code passlib.hash.bcrypt}
     * imported at {@code :L3} and neither invoked by any call site in the retired tree. This bean is
     * invoked on every credential check the {@link AuthenticationManager} performs.
     *
     * <p>{@link BCryptPasswordEncoder} reads and writes the modular-crypt bcrypt format the passlib
     * context produced, so {@code scanner.auth.password-hash} carries a raw {@code $2a$}, {@code $2b$}
     * or {@code $2y$} hash and no encoder-identifier prefix.
     *
     * @return the bcrypt password encoder; never {@code null}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-020
    /**
     * Publishes the credential store: one principal, read from the {@code scanner.auth} group.
     *
     * <p>{@code scanner.auth.username} supplies the principal name and falls back to {@code admin}
     * when it is absent or blank. {@code scanner.auth.password-hash} supplies a bcrypt hash of that
     * principal's password and has no fallback of any kind.
     *
     * <p>The principal holds an empty authority collection. The service declares no role, no scope
     * and no authority anywhere, and the chain's {@code anyRequest().authenticated()} rule reads
     * none — DL-020.
     *
     * <p>Publishing this bean displaces Spring Boot's {@code UserDetailsServiceAutoConfiguration},
     * whose {@code user} principal carries a generated password written to the log at startup.
     *
     * <p>The only {@code User} entity the project documents,
     * {@code documentation/Technical Specifications.md:L322-331}, models a monitored X account —
     * {@code id}, {@code handle}, {@code followerCount}, {@code lastTweetDate} — and no application
     * principal. The schema this service creates is the four tables of
     * {@code backend/app/db/models.py}: {@code tweets}, {@code responses}, {@code ai_tools} and
     * {@code settings} — DL-020.
     *
     * @return an {@link InMemoryUserDetailsManager} holding exactly one principal; never {@code null}
     * @throws IllegalStateException if {@code scanner.auth.password-hash} is absent or blank
     */
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails principal = User.withUsername(configuredUsername())
                .password(configuredPasswordHash())
                .authorities(Collections.emptyList())
                .build();

        log.info("Credential store initialised from scanner.auth with a single principal holding "
                + "{} authorities", principal.getAuthorities().size());

        return new InMemoryUserDetailsManager(principal);
    }

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-019, DL-020
    /**
     * Publishes the authentication manager that
     * {@code com.codeskeptic.scanner.api.AuthController} consumes.
     *
     * <p>The manager Spring Security assembles from {@link #userDetailsService()} and
     * {@link #passwordEncoder()} performs the credential check behind {@code POST /auth/token} —
     * DL-019. A presented password that does not match the stored hash raises
     * {@link org.springframework.security.authentication.BadCredentialsException}; an unknown
     * principal name raises the same type, the manager's default for a principal it cannot resolve.
     *
     * @param configuration the authentication configuration Spring Security publishes for the
     *     application context
     * @return the application's authentication manager; never {@code null}
     * @throws Exception if the configuration cannot assemble a manager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Reads the principal name from the {@code scanner.auth} group.
     *
     * <p>An unbound group, an absent value and a blank value are read alike, as an unsupplied name.
     *
     * @return the value of {@code scanner.auth.username}, trimmed, or {@code admin} when that value
     *     carries nothing
     */
    private String configuredUsername() {
        ScannerProperties.Auth auth = properties.auth();
        String username = (auth == null) ? null : auth.username();
        if (username == null || username.isBlank()) {
            log.info("scanner.auth.username carries no value; the single principal is named by the "
                    + "built-in default");
            return DEFAULT_USERNAME;
        }
        return username.trim();
    }

    /**
     * Reads the principal's bcrypt password hash from the {@code scanner.auth} group.
     *
     * <p>An unbound group, an absent value and a blank value are read alike, as an unsupplied hash,
     * and each fails context refresh — DL-020.
     *
     * @return the value of {@code scanner.auth.password-hash}, trimmed
     * @throws IllegalStateException if that value carries nothing
     */
    private String configuredPasswordHash() {
        ScannerProperties.Auth auth = properties.auth();
        String passwordHash = (auth == null) ? null : auth.passwordHash();
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalStateException(MISSING_PASSWORD_HASH_MESSAGE);
        }
        return passwordHash.trim();
    }
}
