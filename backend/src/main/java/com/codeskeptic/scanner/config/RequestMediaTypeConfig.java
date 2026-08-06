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

// Net-new (no Python counterpart; the retired tree ran no filter of its own) — DL-236 — see
// docs/DECISION_LOG.md
/**
 * Withholds a request {@code Content-Type} header that names no concrete media type from request
 * processing.
 *
 * <p>Two values are withheld: a media type carrying a wildcard type or a wildcard subtype, and a value
 * {@link MediaType#parseMediaType(String)} rejects. {@code HttpHeaders.setContentType} answers each
 * with {@link IllegalArgumentException}, and {@code ServletServerHttpRequest.getHeaders} calls it from
 * the CORS processor inside the security filter chain, from the {@code @RequestBody} argument resolver
 * and from the return-value writer — DL-236.
 *
 * <p>The filter published here runs ahead of the security chain and presents such a request to every
 * downstream component as a request carrying no {@code Content-Type} at all, which
 * {@code api/GlobalExceptionHandler#handleUnsupportedMediaType} answers
 * {@code 415 {"error": "Unsupported media type"}}. This filter withholds the header and rejects no
 * request; authorization decides a protected route first, and a request carrying no accepted token is
 * answered by the chain's entry point with 401 and an empty body — DL-115.
 *
 * <p>A media type that parses and names one concrete type — {@code application/json},
 * {@code text/plain}, {@code multipart/mixed} — is passed through untouched.
 *
 * <p>This class holds no mutable state and its filter is safe to share across concurrent requests.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-236;
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
     * <p>The order is {@link Ordered#HIGHEST_PRECEDENCE}, ahead of
     * {@code springSecurityFilterChain}, which Spring Boot registers at
     * {@code SecurityProperties.DEFAULT_FILTER_ORDER} — {@code Ordered.HIGHEST_PRECEDENCE} plus 100 —
     * and ahead of the {@code CorsFilter} inside that chain — DL-236.
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
     * an {@link IllegalArgumentException} — DL-235, DL-236.
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

            // The client-supplied header value reaches the log through the log-injection guard only
            // — DL-149 — see docs/DECISION_LOG.md
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
