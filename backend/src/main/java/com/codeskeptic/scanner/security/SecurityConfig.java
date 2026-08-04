package com.codeskeptic.scanner.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codeskeptic.scanner.config.ScannerProperties;

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
 * {@code backend/app/api/settings.py:L8,L14} and {@code backend/app/api/analytics.py:L8,L18}. That
 * form, applied without parentheses, registers the decorator factory in {@code flask-jwt-extended}
 * 4.x and leaves the route unguarded. The chain declared here authenticates all eleven — DL-021.
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
 * <p>This is a singleton configuration class. All four fields are {@code final} and hold singleton
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

    /** Challenge returned with every {@code 401} this chain produces — DL-115. */
    private static final String BEARER_CHALLENGE = "Bearer";

    /** Maximum encoded size of the JSON body accepted by {@code POST /auth/token} — DL-118. */
    private static final int MAXIMUM_LOGIN_REQUEST_BYTES = 4_096;

    /**
     * Shape a value of {@code scanner.auth.password-hash} must match: the modular-crypt bcrypt form,
     * a two-digit cost, and a 53-character radix-64 salt-and-digest tail — DL-116.
     */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    /**
     * Shape of a Spring property placeholder that resolved to nothing. Configuration binding leaves
     * such a placeholder in place as literal text when the environment variable behind it is absent,
     * so the bound value is neither {@code null} nor blank — DL-189.
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

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
     * Message of the {@link IllegalStateException} raised when {@code scanner.auth.password-hash}
     * carries a value that is not a bcrypt hash of an accepted shape — DL-116. The rejected value is
     * never named.
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
     * authenticated; the bearer-aware {@code 401} entry point; and
     * {@link JwtAuthenticationFilter} positioned ahead of
     * {@link UsernamePasswordAuthenticationFilter}.
     *
     * <p>Disabling logout removes the {@code /logout} route Spring Security otherwise installs and
     * permits for every method — DL-114. With logout off, {@code POST /auth/token} is the only route
     * this chain serves without authentication.
     *
     * <p>{@link JwtAuthenticationFilter} is constructed here and is not a bean, so it is registered
     * in this chain alone and never in the servlet container's own filter list. It receives the same
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
                        .requestMatchers(HttpMethod.POST, TOKEN_ENDPOINT).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(bearerAuthenticationEntryPoint()))
                .addFilterBefore(
                        new LoginRequestBodyLimitFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, securityContextRepository),
                        UsernamePasswordAuthenticationFilter.class);

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
     * <p>The principal holds an empty authority collection. The service declares no role, no scope
     * and no authority anywhere, and the chain's {@code anyRequest().authenticated()} rule reads
     * none — DL-020.
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
     * <p>Four values are read alike, as an unsupplied hash, and all four raise
     * {@link #MISSING_PASSWORD_HASH_MESSAGE}: an unbound group, an absent value, a blank value and an
     * unresolved {@code ${AUTH_PASSWORD_HASH}} placeholder. The fourth arrives when the environment
     * variable behind the placeholder is absent, because configuration binding leaves the placeholder
     * in place as literal text rather than failing — DL-189.
     *
     * <p>A value present but not matching {@link #BCRYPT_HASH}, or carrying a cost outside
     * {@value #MINIMUM_BCRYPT_COST}..{@value #MAXIMUM_BCRYPT_COST}, is rejected as malformed. Each
     * outcome fails context refresh, and none reproduces the configured value — DL-116.
     *
     * @return the value of {@code scanner.auth.password-hash}, trimmed and validated
     * @throws IllegalStateException if that value carries nothing or is not an accepted bcrypt hash
     */
    private String configuredPasswordHash() {
        ScannerProperties.Auth auth = properties.auth();
        String passwordHash = (auth == null) ? null : auth.passwordHash();
        if (passwordHash == null
                || passwordHash.isBlank()
                || isUnresolvedPlaceholder(passwordHash)) {
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
     * Reports whether a configured value still holds the property placeholder that should have
     * supplied it.
     *
     * <p>{@code scanner.auth.password-hash} is declared as {@code ${AUTH_PASSWORD_HASH}} with no
     * default. The {@code @ConfigurationProperties} binder resolves placeholders through a resolver
     * that leaves an unresolvable one in place rather than failing, so an unset
     * {@code AUTH_PASSWORD_HASH} binds the literal twenty-two-character text
     * {@code ${AUTH_PASSWORD_HASH}} — a value that is neither {@code null} nor blank. Recognising that
     * shape, ignoring surrounding whitespace, is what keeps
     * {@link #MISSING_PASSWORD_HASH_MESSAGE} reachable — DL-189.
     *
     * @param value the bound value, never {@code null} when this is called
     * @return {@code true} when the value, ignoring surrounding whitespace, opens with a dollar sign
     *     followed by an opening brace and closes with a closing brace
     */
    private static boolean isUnresolvedPlaceholder(String value) {
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

    /**
     * Bounds the encoded login body before Jackson allocates or deserializes it — DL-118.
     *
     * <p>The filter applies only to {@code POST /auth/token}. It reads at most
     * {@value #MAXIMUM_LOGIN_REQUEST_BYTES} plus one bytes, rejects a larger body with the route's
     * empty 401 response, and replays an accepted body to Spring MVC.
     */
    private static final class LoginRequestBodyLimitFilter extends OncePerRequestFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            if (!HttpMethod.POST.name().equals(request.getMethod())) {
                return true;
            }
            String requestPath = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (!contextPath.isEmpty() && requestPath.startsWith(contextPath)) {
                requestPath = requestPath.substring(contextPath.length());
            }
            return !TOKEN_ENDPOINT.equals(requestPath);
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

    /** Request wrapper that replays the bounded login body consumed by the size filter. */
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

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = (encoding == null)
                    ? StandardCharsets.UTF_8
                    : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
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
