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
import java.util.List;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
 * Security composition root: the one servlet filter chain and the four collaborators that
 * authenticate a request.
 *
 * <p>Two authorization rules: {@code POST /auth/token} is permitted with no authentication (DL-019)
 * and every other request requires an authenticated principal, which covers all eleven unprefixed
 * routes the four Flask blueprints registered at {@code backend/app/main.py:L26-29} and which the
 * eleven bare {@code @jwt_required} decorators did not enforce — DL-021.
 *
 * <p>Stateless: no HTTP session, and CSRF, HTTP Basic, form login and logout all off — DL-114. An
 * unauthenticated request is answered with {@code 401}, an empty body and a
 * {@code WWW-Authenticate: Bearer} challenge — DL-115. {@code POST /auth/token} accepts at most
 * {@value #MAXIMUM_LOGIN_REQUEST_BYTES} encoded bytes and answers a larger body with that route's own
 * empty 401, enforced before any converter reads a body — DL-118; the eleven pre-existing routes carry
 * no body-size bound of this chain's making.
 *
 * <p>Cross-origin policy is injected, not declared here — DL-051. Response headers are the Spring
 * Security defaults apart from {@code Strict-Transport-Security}, declared inline below — DL-277.
 *
 * <p>The credential store holds one principal from {@code scanner.auth}, holding no authority and
 * backed by no table, so the schema stays the four tables of {@code backend/app/db/models.py} —
 * DL-020. Neither the principal name nor the hash is logged, and construction fails with
 * {@link IllegalStateException} for a hash that is absent, blank, an unresolved
 * {@code ${AUTH_PASSWORD_HASH}} placeholder, or outside the accepted bcrypt shape and cost — DL-116,
 * DL-189.
 *
 * <p>Singleton configuration class, thread-safe: all four fields are {@code final} and every bean is
 * fully built before it is returned and never mutated afterwards.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Binding leaves an unresolved placeholder in place as literal text — DL-186. */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String TOKEN_ENDPOINT = "/auth/token";

    // The authorization rule and the body-size filter decide from this one matcher — DL-118 — see
    // docs/DECISION_LOG.md
    /**
     * The single instance both the {@code permitAll} rule and
     * {@code LoginRequestBodyLimitFilter.shouldNotFilter} consult, so the two decide the same question
     * from the same input. The path is read percent-decoded and normalized, so
     * {@code POST /auth/%74oken} matches — DL-118.
     */
    private static final RequestMatcher TOKEN_ENDPOINT_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, TOKEN_ENDPOINT);

    /**
     * Principal name applied when {@code scanner.auth.username} is absent or blank; the same default
     * {@code src/main/resources/application.yml} declares for {@code AUTH_USERNAME} — DL-020.
     */
    private static final String DEFAULT_USERNAME = "admin";

    private static final String BEARER_CHALLENGE = "Bearer";

    private static final int MAXIMUM_LOGIN_REQUEST_BYTES = 4_096;

    // Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-277
    /**
     * Lifetime declared by {@code Strict-Transport-Security}, in seconds: one year, which is the value
     * Spring Security's own writer carries — DL-277. The header is written on a request the container
     * reports as secure, which {@code server.forward-headers-strategy} decides — DL-293.
     */
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    /**
     * Shape a value of {@code scanner.auth.password-hash} must match: the modular-crypt bcrypt form,
     * a two-digit cost, and a 53-character radix-64 salt-and-digest tail — DL-116.
     */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

    private static final int MINIMUM_BCRYPT_COST = 10;

    private static final int MAXIMUM_BCRYPT_COST = 14;

    private static final String MISSING_PASSWORD_HASH_MESSAGE =
            "scanner.auth.password-hash is not configured; supply a bcrypt hash of the application "
                    + "principal's password through the AUTH_PASSWORD_HASH environment variable. It "
                    + "has no default value.";

    private static final String MALFORMED_PASSWORD_HASH_MESSAGE =
            "scanner.auth.password-hash does not carry a bcrypt hash of the expected form: "
                    + "$2a$, $2b$ or $2y$, a two-digit cost between " + MINIMUM_BCRYPT_COST
                    + " and " + MAXIMUM_BCRYPT_COST + ", then a 53-character salt and digest. "
                    + "Regenerate it with BCryptPasswordEncoder and set AUTH_PASSWORD_HASH to the "
                    + "result. The configured value is not reproduced here.";

    private final ScannerProperties properties;

    private final JwtService jwtService;

    /** Injected by bean name, which the constructor parameter name matches — DL-051. */
    private final CorsConfigurationSource corsConfigurationSource;

    /** The one store shared by the chain and by {@link JwtAuthenticationFilter} — DL-112. */
    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

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
     * {@link UsernamePasswordAuthenticationFilter}; and the login body bound, also ahead of
     * {@link UsernamePasswordAuthenticationFilter}.
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
        if (isUnset(passwordHash)) {
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
     * the same instance the chain's {@code permitAll} rule consults. Matching runs against the parsed
     * request path — percent-decoded and normalized — so an encoded spelling such as
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

        // Net-new (no Python counterpart) — DL-118 — see docs/DECISION_LOG.md
        /**
         * {@inheritDoc}
         *
         * <p>Decodes the cached body with the charset the request declared, and with UTF-8 when the
         * request declares no charset, declares one this JVM does not provide, or declares one that is
         * not a legal charset name — DL-118.
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

    /**
     * Reports whether a bound configuration value carries no usable configuration.
     *
     * <p>Binding leaves an unresolved {@code ${...}} placeholder in place as literal text when the
     * environment variable behind it is absent, so such a value is neither {@code null} nor blank and
     * is treated as unset here — DL-186.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is {@code null}, blank, or an unresolved placeholder
     */
    private static boolean isUnset(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

}
