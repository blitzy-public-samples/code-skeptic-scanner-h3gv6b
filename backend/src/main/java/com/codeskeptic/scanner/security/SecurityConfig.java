package com.codeskeptic.scanner.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.util.ConfiguredValues;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

// Ported from backend/app/main.py:L22 (JWTManager) + backend/app/core/security.py:L14-18 (passlib
// bcrypt) + the eleven bare @jwt_required guards in backend/app/api/*.py (faithful port) — see
// docs/DECISION_LOG.md DL-019, DL-020, DL-021, DL-051, DL-112, DL-114, DL-115
/**
 * Security composition root of the backend service: the one servlet filter chain and the four
 * collaborators that authenticate a request.
 *
 * <p>The single {@link SecurityFilterChain} declared here replaces {@code jwt = JWTManager(app)} at
 * {@code backend/app/main.py:L22} together with the eleven bare {@code @jwt_required} decorators at
 * {@code backend/app/api/tweets.py:L10,L24,L37}, {@code backend/app/api/responses.py:L9,L23,L34,L52},
 * {@code backend/app/api/settings.py:L8,L14} and {@code backend/app/api/analytics.py:L8,L18}, each
 * applied without parentheses. The chain declared here authenticates all eleven — DL-021.
 *
 * <p>Five beans are published, and no other:
 *
 * <ul>
 *   <li>{@link #securityFilterChain(HttpSecurity)} — the chain, carrying the authorization rules,
 *       the unauthenticated-request entry point and {@link JwtAuthenticationFilter}.
 *   <li>{@link #securityContextRepository()} — the request-scoped context store the chain reads and
 *       {@link JwtAuthenticationFilter} writes — DL-112.
 *   <li>{@link #passwordEncoder()} — successor of the passlib bcrypt helpers at
 *       {@code backend/app/core/security.py:L14-18}.
 *   <li>{@link #userDetailsService()} — the {@code scanner.auth} credential store — DL-020.
 *   <li>{@link #authenticationManager(AuthenticationConfiguration)} — consumed by
 *       {@code com.codeskeptic.scanner.api.AuthController} to authenticate a
 *       {@code com.codeskeptic.scanner.dto.LoginRequest} — DL-019.
 * </ul>
 *
 * <p>The chain carries two authorization rules. {@code POST /auth/token} is permitted with no
 * authentication — DL-019. Every other request requires an authenticated principal, which covers the
 * eleven pre-existing routes: {@code GET /tweets},
 * {@code GET /tweets/{tweetId}}, {@code POST /tweets/{tweetId}/analyze}, {@code GET /responses},
 * {@code GET /responses/{responseId}}, {@code POST /responses},
 * {@code PUT /responses/{responseId}}, {@code GET /settings}, {@code PUT /settings/{key}},
 * {@code GET /analytics/trends} and {@code GET /analytics/summary}. Every path is unprefixed, as the
 * four Flask blueprints registered at {@code backend/app/main.py:L26-29} were.
 *
 * <p>Cross-origin policy is not declared in this class. The single {@code CorsConfigurationSource}
 * bean of {@code com.codeskeptic.scanner.config.CorsConfig}, which reproduces the argument-free
 * {@code CORS(app)} at {@code backend/app/main.py:L20}, is injected here and handed to the chain's
 * CORS configurer, ahead of the authorization rules — DL-051.
 *
 * <p>The chain is stateless: no HTTP session is created, CSRF protection is off, and HTTP Basic,
 * form login and logout are all off — DL-114. A request that reaches the authorization stage carrying
 * no authentication is answered with status {@code 401}, an empty body and a
 * {@code WWW-Authenticate: Bearer} challenge — DL-115.
 * {@code com.codeskeptic.scanner.api.GlobalExceptionHandler} owns the {@code {"error": <string>}}
 * envelopes of {@code backend/app/main.py:L31-37} and is reached only by exceptions raised inside the
 * {@code DispatcherServlet}.
 *
 * <p>Two request-body bounds are enforced inside the chain, both before any converter reads a body.
 * {@code POST /auth/token} accepts at most 4096 encoded bytes and answers a larger body with the
 * route's own empty 401 — DL-118. Every other request that carries a body accepts at most 65536
 * encoded bytes and answers a larger body with 400 and {@code {"error":"Bad request"}}, the body the
 * {@code ErrorAttributes} bean of {@code com.codeskeptic.scanner.api.GlobalExceptionHandler} renders
 * for a dispatched 400 — DL-183. The second bound runs after authorization: an unauthenticated
 * request is answered with the bare 401 first.
 *
 * <p>Response headers are the Spring Security defaults, with {@code Strict-Transport-Security}
 * declared explicitly at the values the framework's own writer carries — a one-year lifetime,
 * subdomains included, no preload. That header is written on a request the container reports as
 * secure, and {@code server.forward-headers-strategy} in {@code application.yml} is what makes a
 * request whose TLS was terminated upstream report itself that way.
 *
 * <p>The credential store holds exactly one principal, built from {@code scanner.auth.username} and
 * {@code scanner.auth.password-hash} and holding no authority. No table backs it, and the schema this
 * service creates stays the four tables of {@code backend/app/db/models.py} — DL-020. Neither the
 * principal name nor the password hash is written to the log, and construction fails with
 * {@link IllegalStateException} when {@code scanner.auth.password-hash} is absent, blank, still an
 * unresolved {@code ${AUTH_PASSWORD_HASH}} placeholder, or not a bcrypt hash of the shape and cost
 * this service accepts — DL-116, DL-189.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-019, DL-020,
 * DL-021, DL-051, DL-112, DL-114, DL-115, DL-116 and DL-118; construct-level provenance is
 * recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton configuration class and is thread-safe. All four fields are {@code final} and
 * every bean published here is fully built before it is returned and never mutated afterwards.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Path of the one route this chain permits with no authentication — DL-019. */
    private static final String TOKEN_ENDPOINT = "/auth/token";

    // The authorization rule and the body-size filter decide from this one matcher — DL-118 — see
    // docs/DECISION_LOG.md
    /**
     * Matcher of {@code POST} {@value #TOKEN_ENDPOINT}. It is the single instance both the
     * {@code permitAll} rule of {@link #securityFilterChain(HttpSecurity)} and
     * {@code LoginRequestBodyLimitFilter.shouldNotFilter} consult; the two decide the same question
     * from the same input. {@link PathPatternRequestMatcher} reads the parsed request path,
     * percent-decoded and normalized: {@code POST /auth/%74oken} matches it exactly as
     * {@code POST /auth/token} does, as does Spring MVC when it dispatches to
     * {@code api/AuthController}.
     */
    private static final RequestMatcher TOKEN_ENDPOINT_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, TOKEN_ENDPOINT);

    /**
     * Principal name applied when {@code scanner.auth.username} is absent or blank; the same default
     * {@code src/main/resources/application.yml} declares for {@code AUTH_USERNAME} — DL-020.
     */
    private static final String DEFAULT_USERNAME = "admin";

    /** Challenge returned with every {@code 401} this chain produces — DL-115. */
    private static final String BEARER_CHALLENGE = "Bearer";

    /** Maximum encoded size of the JSON body accepted by {@code POST /auth/token} — DL-118. */
    private static final int MAXIMUM_LOGIN_REQUEST_BYTES = 4_096;

    // Applies to every authenticated route the bound DL-118 places on POST /auth/token — see
    // docs/DECISION_LOG.md DL-118
    /**
     * Maximum encoded size of the request body accepted on an authenticated route that carries one:
     * sixteen times {@value #MAXIMUM_LOGIN_REQUEST_BYTES}, the bound the token route carries.
     *
     * <p>The bound is enforced before any converter reads the body: a larger body is neither
     * deserialized nor allocated in full. The retired schemas and the retired {@code String} columns
     * declared no length — DL-118.
     */
    private static final int MAXIMUM_REQUEST_BODY_BYTES = 65_536;

    /**
     * Request methods that carry no body, and on which {@link RequestBodyLimitFilter} therefore does
     * nothing.
     */
    private static final Set<String> BODYLESS_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name(),
                    HttpMethod.TRACE.name());

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md
    /**
     * Lifetime declared by {@code Strict-Transport-Security}, in seconds: one year, which is the value
     * Spring Security's own writer carries. The header is written on a request the container reports as
     * secure; {@code server.forward-headers-strategy} in {@code application.yml} is what makes a
     * TLS-terminated request report itself that way.
     */
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    /**
     * Shape a value of {@code scanner.auth.password-hash} must match: the modular-crypt bcrypt form,
     * a two-digit cost, and a 53-character radix-64 salt-and-digest tail — DL-116.
     */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");


    /** Smallest bcrypt cost {@link #userDetailsService()} accepts — DL-116. */
    private static final int MINIMUM_BCRYPT_COST = 10;

    /** Largest bcrypt cost {@link #userDetailsService()} accepts — DL-116. */
    private static final int MAXIMUM_BCRYPT_COST = 14;

    /**
     * Message of the {@link IllegalStateException} raised when {@code scanner.auth.password-hash}
     * carries no value — DL-020.
     */
    private static final String MISSING_PASSWORD_HASH_MESSAGE =
            "scanner.auth.password-hash is not configured; supply a bcrypt hash of the application "
                    + "principal's password through the AUTH_PASSWORD_HASH environment variable. It "
                    + "has no default value.";

    /**
     * Message raised for a password hash outside the accepted bcrypt shape — DL-116.
     */
    private static final String MALFORMED_PASSWORD_HASH_MESSAGE =
            "scanner.auth.password-hash does not carry a bcrypt hash of the expected form: "
                    + "$2a$, $2b$ or $2y$, a two-digit cost between " + MINIMUM_BCRYPT_COST
                    + " and " + MAXIMUM_BCRYPT_COST + ", then a 53-character salt and digest. "
                    + "Regenerate it with BCryptPasswordEncoder and set AUTH_PASSWORD_HASH to the "
                    + "result. The configured value is not reproduced here.";

    /** Supplies the {@code scanner.auth} group that {@link #userDetailsService()} reads. */
    private final ScannerProperties properties;

    /** Handed to the {@link JwtAuthenticationFilter} this class adds to the chain. */
    private final JwtService jwtService;

    /**
     * The permissive policy published by {@code com.codeskeptic.scanner.config.CorsConfig} — DL-051.
     *
     * <p>The parameter name of the constructor argument that populates this field matches that bean's
     * name, {@code corsConfigurationSource}.
     */
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * The one context store shared by the chain and by {@link JwtAuthenticationFilter} — DL-112.
     */
    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

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
    // backend/app/api/*.py (faithful port) — see docs/DECISION_LOG.md DL-019, DL-021, DL-051,
    // DL-112, DL-114, DL-115
    /**
     * Builds the application's only security filter chain.
     *
     * <p>The chain applies, in this order: the CORS policy injected from
     * {@code com.codeskeptic.scanner.config.CorsConfig}; CSRF protection off; stateless session
     * management; the request-attribute context repository; HTTP Basic, form login and logout all
     * off; two authorization rules — {@code POST /auth/token} permitted and every other request
     * authenticated; the bearer-aware {@code 401} entry point;
     * {@link JwtAuthenticationFilter} positioned ahead of
     * {@link UsernamePasswordAuthenticationFilter}; and the two request-body bounds, the login bound
     * ahead of {@link UsernamePasswordAuthenticationFilter} and the general bound behind
     * {@link AuthorizationFilter}.
     *
     * <p>Disabling logout removes the {@code /logout} route Spring Security otherwise installs and
     * permits for every method — DL-114. With logout off, {@code POST /auth/token} is the only route
     * this chain serves without authentication.
     *
     * <p>{@link JwtAuthenticationFilter} is constructed here and is not a bean; it is registered in
     * this chain alone and never in the servlet container's own filter list. It receives the same
     * {@link SecurityContextRepository} instance the chain is configured with — DL-112.
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
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(TOKEN_ENDPOINT_MATCHER).permitAll()
                        .anyRequest().authenticated())
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(false)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(bearerAuthenticationEntryPoint()))
                .addFilterBefore(
                        new LoginRequestBodyLimitFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, securityContextRepository,
                                userDetailsService()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RequestBodyLimitFilter(), AuthorizationFilter.class);

        log.info("Security filter chain built: POST {} is permitted unauthenticated; logout is "
                + "disabled; every other request requires an authenticated principal, answered with "
                + "{} and a {} challenge when it carries none",
                TOKEN_ENDPOINT, HttpStatus.UNAUTHORIZED.value(), BEARER_CHALLENGE);

        return http.build();
    }

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-112
    /**
     * Publishes the context store the chain reads and {@link JwtAuthenticationFilter} writes.
     *
     * <p>The store is request-scoped: a context survives a dispatch boundary within one request and
     * nothing is written to an HTTP session. The chain remains stateless — DL-112.
     *
     * @return the shared repository instance; never {@code null}
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return securityContextRepository;
    }

    // Ported from backend/app/core/security.py:L14-18 (faithful port) — see docs/DECISION_LOG.md
    /**
     * Publishes the encoder that verifies a presented password against the stored hash.
     *
     * <p>Successor of {@code verify_password} at {@code backend/app/core/security.py:L14-15} and
     * {@code get_password_hash} at {@code :L17-18}, both built on {@code passlib.hash.bcrypt}
     * imported at {@code :L3}. This bean is invoked on every credential check the
     * {@link AuthenticationManager} performs.
     *
     * <p>{@link BCryptPasswordEncoder} reads and writes the modular-crypt bcrypt format the passlib
     * context produced. {@code scanner.auth.password-hash} carries a raw {@code $2a$}, {@code $2b$} or
     * {@code $2y$} hash and no encoder-identifier prefix.
     *
     * @return the bcrypt password encoder; never {@code null}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-020, DL-116
    /**
     * Publishes the credential store: one principal, read from the {@code scanner.auth} group.
     *
     * <p>{@code scanner.auth.username} supplies the principal name and falls back to {@code admin}
     * when it is absent or blank. {@code scanner.auth.password-hash} supplies a bcrypt hash of that
     * principal's password, has no fallback of any kind, and is validated here against the shape
     * {@link BCryptPasswordEncoder} produces and a cost between {@value #MINIMUM_BCRYPT_COST} and
     * {@value #MAXIMUM_BCRYPT_COST} — DL-116. A value failing either check fails context refresh and
     * is never reproduced in the failure message or the log.
     *
     * <p>The principal holds an empty authority collection, which is what the chain's
     * {@code anyRequest().authenticated()} rule reads — DL-020.
     *
     * <p>This bean replaces Spring Boot's {@code UserDetailsServiceAutoConfiguration}. The schema
     * remains the four tables of {@code backend/app/db/models.py} — DL-020.
     *
     * @return an {@link InMemoryUserDetailsManager} holding exactly one principal; never {@code null}
     * @throws IllegalStateException if {@code scanner.auth.password-hash} is absent, blank, or not a
     *     bcrypt hash of an accepted shape and cost
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
     * DL-019. A presented password that does not match the stored hash and an unknown principal name
     * both raise {@link org.springframework.security.authentication.BadCredentialsException}.
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

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-115
    /**
     * Builds the entry point that answers a request reaching the authorization stage with no
     * authentication.
     *
     * <p>The response carries status {@code 401}, a {@code WWW-Authenticate: Bearer} challenge and an
     * empty body. It renders no container error page or JSON error envelope — DL-115.
     *
     * @return the entry point handed to the chain's exception-handling configurer; never {@code null}
     */
    private static AuthenticationEntryPoint bearerAuthenticationEntryPoint() {
        return (request, response, authenticationException) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        };
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
     * Reads and validates the principal's bcrypt password hash from the {@code scanner.auth} group.
     *
     * <p>An unbound group, an absent value, a blank value and an unresolved
     * {@code ${AUTH_PASSWORD_HASH}} placeholder are all read as an unsupplied hash and raise
     * {@link #MISSING_PASSWORD_HASH_MESSAGE} — DL-189. A value not matching {@link #BCRYPT_HASH}, or
     * carrying a cost outside {@value #MINIMUM_BCRYPT_COST}..{@value #MAXIMUM_BCRYPT_COST}, is
     * rejected as malformed. Each outcome fails context refresh, and none reproduces the configured
     * value — DL-116.
     *
     * @return the value of {@code scanner.auth.password-hash}, trimmed and validated
     * @throws IllegalStateException if that value carries nothing or is not an accepted bcrypt hash
     */
    private String configuredPasswordHash() {
        ScannerProperties.Auth auth = properties.auth();
        String passwordHash = (auth == null) ? null : auth.passwordHash();
        if (ConfiguredValues.isUnset(passwordHash)) {
            throw new IllegalStateException(MISSING_PASSWORD_HASH_MESSAGE);
        }
        String trimmed = passwordHash.trim();

        Matcher matcher = BCRYPT_HASH.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalStateException(MALFORMED_PASSWORD_HASH_MESSAGE);
        }
        int cost = Integer.parseInt(matcher.group(1));
        if (cost < MINIMUM_BCRYPT_COST || cost > MAXIMUM_BCRYPT_COST) {
            throw new IllegalStateException(MALFORMED_PASSWORD_HASH_MESSAGE);
        }

        log.info("scanner.auth.password-hash accepted: a bcrypt hash at cost {}", cost);
        return trimmed;
    }

    // Applies to scanner.auth.password-hash the guard security/JwtService already applies to
    // scanner.jwt.secret — DL-185, DL-186, DL-189 — see docs/DECISION_LOG.md

    /**
     * Bounds the encoded login body before Jackson allocates or deserializes it — DL-118.
     *
     * <p>The filter applies to exactly the requests {@link #TOKEN_ENDPOINT_MATCHER} matches, which is
     * the same instance the chain's {@code permitAll} rule consults. Matching therefore runs against
     * the parsed request path — percent-decoded and normalized — so an encoded spelling such as
     * {@code POST /auth/%74oken} is bounded here exactly as {@code POST /auth/token} is.
     *
     * <p>It reads at most {@value #MAXIMUM_LOGIN_REQUEST_BYTES} plus one bytes, rejects a larger body
     * with the route's empty 401 response, and replays an accepted body to Spring MVC.
     */
    private static final class LoginRequestBodyLimitFilter extends OncePerRequestFilter {

        // The same matcher instance the permitAll rule consults — DL-118 — see
        // docs/DECISION_LOG.md
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !TOKEN_ENDPOINT_MATCHER.matches(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (request.getContentLengthLong() > MAXIMUM_LOGIN_REQUEST_BYTES) {
                rejectOversizedLoginRequest(response);
                return;
            }

            byte[] body = request.getInputStream().readNBytes(MAXIMUM_LOGIN_REQUEST_BYTES + 1);
            if (body.length > MAXIMUM_LOGIN_REQUEST_BYTES) {
                rejectOversizedLoginRequest(response);
                return;
            }

            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        }

        private static void rejectOversizedLoginRequest(HttpServletResponse response) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentLength(0);
        }
    }

    // Applies to every authenticated route that carries a body the bound DL-118 places on
    // POST /auth/token — see docs/DECISION_LOG.md DL-118, DL-183
    /**
     * Bounds the encoded request body on an authenticated route before any converter reads it.
     *
     * <p>The filter runs after {@link AuthorizationFilter}, so a request that carries no
     * authenticated principal is still answered with the chain's bare 401 rather than with this
     * bound. It does nothing on a request whose method carries no body, and nothing on
     * {@code POST /auth/token}, which {@link LoginRequestBodyLimitFilter} bounds at the smaller
     * {@value #MAXIMUM_LOGIN_REQUEST_BYTES} bytes.
     *
     * <p>A declared {@code Content-Length} above {@value #MAXIMUM_REQUEST_BODY_BYTES} is rejected
     * without reading the body at all. A body whose length is not declared is read to at most
     * {@value #MAXIMUM_REQUEST_BODY_BYTES} plus one bytes, and rejected if that many arrive. An
     * accepted body is replayed to Spring MVC through {@link CachedBodyRequest}.
     *
     * <p>Rejection calls {@link HttpServletResponse#sendError(int)} with 400, which the container
     * re-dispatches to the framework's own error controller; the {@code ErrorAttributes} bean of
     * {@code api.GlobalExceptionHandler} renders {@code {"error":"Bad request"}} there — the same
     * status and the same body that advice returns for a request the converters cannot read. No wire
     * literal is written here — DL-183.
     */
    private static final class RequestBodyLimitFilter extends OncePerRequestFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return BODYLESS_METHODS.contains(request.getMethod())
                    || TOKEN_ENDPOINT_MATCHER.matches(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (request.getContentLengthLong() > MAXIMUM_REQUEST_BODY_BYTES) {
                rejectOversizedRequest(request, response);
                return;
            }

            byte[] body = request.getInputStream().readNBytes(MAXIMUM_REQUEST_BODY_BYTES + 1);
            if (body.length > MAXIMUM_REQUEST_BODY_BYTES) {
                rejectOversizedRequest(request, response);
                return;
            }

            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        }

        private static void rejectOversizedRequest(HttpServletRequest request,
                HttpServletResponse response) throws IOException {

            log.warn("Rejected a {} request whose body exceeded {} bytes",
                    request.getMethod(), MAXIMUM_REQUEST_BODY_BYTES);
            response.sendError(HttpStatus.BAD_REQUEST.value());
        }
    }

    /** Request wrapper that replays a bounded body consumed by a size filter. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        // Net-new (no Python counterpart) — DL-198 — see docs/DECISION_LOG.md
        /**
         * {@inheritDoc}
         *
         * <p>Falls back to UTF-8 when the request declares no charset, declares one this JVM does not
         * provide, or declares one that is not a legal charset name. {@link Charset#forName(String)}
         * throws {@link java.nio.charset.UnsupportedCharsetException} and
         * {@link java.nio.charset.IllegalCharsetNameException}, both unchecked, so without this guard
         * a caller-supplied {@code Content-Type} charset could raise from inside the filter chain.
         */
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), readerCharset()));
        }

        /**
         * Resolves the charset {@link #getReader()} decodes the cached body with.
         *
         * @return the declared charset when it is usable, otherwise {@link StandardCharsets#UTF_8};
         *     never {@code null}
         */
        private Charset readerCharset() {
            String encoding = getCharacterEncoding();
            if (encoding == null || encoding.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (UnsupportedCharsetException | IllegalCharsetNameException unusable) {
                log.warn("Request declared a charset this service cannot decode with; reading the "
                        + "cached body as UTF-8 instead");
                return StandardCharsets.UTF_8;
            }
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** Blocking servlet input stream over an immutable in-memory request body. */
    private static final class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private CachedBodyServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
        }

        @Override
        public int available() {
            return input.available();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            Objects.requireNonNull(readListener, "readListener must not be null");
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException ex) {
                readListener.onError(ex);
            }
        }
    }
}
