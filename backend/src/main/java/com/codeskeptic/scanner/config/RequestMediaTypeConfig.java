package com.codeskeptic.scanner.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codeskeptic.scanner.util.LogSafe;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

// Net-new (no Python counterpart; the retired tree ran no filter of its own) — DL-221 — see
// docs/DECISION_LOG.md
/**
 * Withholds a request {@code Content-Type} header that names no concrete media type from request
 * processing.
 *
 * <p>{@code HttpHeaders.setContentType} rejects a media type carrying a wildcard type or a wildcard
 * subtype with {@link IllegalArgumentException}, and {@code ServletServerHttpRequest.getHeaders}
 * calls it while copying the request's headers. Three components of the request path call that
 * method:
 *
 * <ul>
 *   <li>{@code org.springframework.web.cors.DefaultCorsProcessor.handleInternal}, reached from the
 *       {@code CorsFilter} that {@code security/SecurityConfig} installs, for every request carrying
 *       an {@code Origin} header</li>
 *   <li>{@code AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters}, reached
 *       while a handler's {@code @RequestBody} parameter is resolved</li>
 *   <li>{@code AbstractMessageConverterMethodProcessor}, reached while a return value is written</li>
 * </ul>
 *
 * <p>The first of those runs inside the security filter chain, ahead of the
 * {@code DispatcherServlet}, so the exception it raises reaches no {@code @ExceptionHandler}: it
 * leaves the servlet, the container dispatches the request to the error page, and the response
 * carries neither the status the request earned nor a bounded log record. The filter published here
 * runs ahead of the security chain and presents such a request to every downstream component as a
 * request carrying no {@code Content-Type} at all, which
 * {@code api/GlobalExceptionHandler#handleUnsupportedMediaType} already answers
 * {@code 415 {"error": "Unsupported media type"}} — DL-221.
 *
 * <p>The header is withheld rather than the request rejected, so authorization still decides a
 * protected route before any media type does: a request carrying no accepted token is answered by
 * the chain's entry point with 401 and an empty body exactly as before — DL-115.
 *
 * <p>A media type that parses and names one concrete type — {@code application/json},
 * {@code text/plain}, {@code multipart/mixed} — is passed through untouched.
 *
 * <p>This class holds no mutable state and its filter is safe to share across concurrent requests.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-221;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@Configuration
public class RequestMediaTypeConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(RequestMediaTypeConfig.class);

    /** Registration name of the filter this class publishes. */
    private static final String FILTER_NAME = "nonConcreteContentTypeFilter";

    /** Every path the container serves. */
    private static final String ALL_PATHS = "/*";

    /**
     * Registers the filter ahead of every other filter the container runs.
     *
     * <p>Spring Boot registers {@code springSecurityFilterChain} at
     * {@code SecurityProperties.DEFAULT_FILTER_ORDER}, which is {@code Ordered.HIGHEST_PRECEDENCE}
     * plus 100. This registration therefore precedes the security chain, and with it the
     * {@code CorsFilter} inside that chain — DL-221.
     *
     * <p>The registration names {@link DispatcherType#REQUEST} alone, the dispatch on which a client
     * supplies the header.
     *
     * @return the filter registration; never {@code null}
     */
    @Bean
    public FilterRegistrationBean<NonConcreteContentTypeFilter>
            nonConcreteContentTypeFilterRegistration() {

        FilterRegistrationBean<NonConcreteContentTypeFilter> registration =
                new FilterRegistrationBean<>(new NonConcreteContentTypeFilter());
        registration.setName(FILTER_NAME);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns(ALL_PATHS);

        log.info("Registered the request media-type filter at order {} for {}",
                Ordered.HIGHEST_PRECEDENCE, ALL_PATHS);

        return registration;
    }

    /**
     * Reports whether a raw {@code Content-Type} header value names no single concrete media type.
     *
     * <p>This is the single declaration of that question. The filter below reads it to decide whether
     * to withhold the header, and {@code api.GlobalExceptionHandler} reads it to decide the status of
     * an {@link IllegalArgumentException} — DL-220, DL-221.
     *
     * @param declared the raw header value, possibly {@code null}
     * @return {@code true} when the value holds a media type with a wildcard type or subtype, or a
     *     value {@link MediaType#parseMediaType(String)} rejects; {@code false} when the value is
     *     {@code null}, blank, or names one concrete media type
     */
    public static boolean namesNoConcreteMediaType(String declared) {
        if (declared == null || declared.isBlank()) {
            return false;
        }
        try {
            return !MediaType.parseMediaType(declared).isConcrete();
        } catch (InvalidMediaTypeException ex) {
            return true;
        }
    }

    /**
     * Presents a request whose {@code Content-Type} names no concrete media type as a request
     * carrying no {@code Content-Type}.
     */
    static final class NonConcreteContentTypeFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {

            String declared = request.getContentType();
            if (!namesNoConcreteMediaType(declared)) {
                filterChain.doFilter(request, response);
                return;
            }

            // The header value is client-supplied, so it reaches the log only through the
            // log-injection guard — DL-149 — see docs/DECISION_LOG.md
            log.debug("Withholding a Content-Type naming no concrete media type from request "
                    + "processing: {}", LogSafe.logSafe(declared));
            filterChain.doFilter(new ContentTypeWithheldRequest(request), response);
        }
    }

    /**
     * A request whose {@code Content-Type} header is absent from every accessor that exposes it.
     *
     * <p>{@code getContentType()}, {@code getHeader(String)}, {@code getHeaders(String)} and
     * {@code getHeaderNames()} are the four accessors {@code ServletServerHttpRequest} and Spring
     * MVC's argument resolvers read the header through. Every other accessor, the request body
     * included, is inherited unchanged.
     */
    static final class ContentTypeWithheldRequest extends HttpServletRequestWrapper {

        /**
         * Wraps one request.
         *
         * @param request the request whose {@code Content-Type} is withheld, must not be {@code null}
         */
        ContentTypeWithheldRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public String getHeader(String name) {
            return isContentType(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isContentType(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> declared = super.getHeaderNames();
            while (declared != null && declared.hasMoreElements()) {
                String name = declared.nextElement();
                if (!isContentType(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }

        /**
         * Reports whether a header name is {@code Content-Type}, in any letter case.
         *
         * @param name the header name, possibly {@code null}
         * @return {@code true} when the name is the {@code Content-Type} header
         */
        private static boolean isContentType(String name) {
            return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name);
        }
    }
}
