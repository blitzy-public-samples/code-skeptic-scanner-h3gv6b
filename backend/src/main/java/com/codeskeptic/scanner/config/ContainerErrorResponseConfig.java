package com.codeskeptic.scanner.config;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import org.apache.catalina.Host;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.codeskeptic.scanner.api.GlobalExceptionHandler;
import com.codeskeptic.scanner.util.LogSafe;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

// Net-new (no Python counterpart; the retired tree ran no servlet container) — DL-237 — see
// docs/DECISION_LOG.md
/**
 * Answers a request the servlet container rejects before any filter or servlet runs with this
 * service's error envelope.
 *
 * <p>A request whose headers exceed {@code server.max-http-request-header-size} (DL-238), whose target
 * holds an encoded character the connector refuses, or whose framing the protocol layer rejects never
 * reaches the filter chain: the connector records a status and the container's error-report valve
 * writes the body. The valve declared here replaces the {@code ErrorReportValve} Spring Boot installs,
 * which writes an HTML document and none of this service's headers — DL-237.
 *
 * <p>The status and the literal are the ones {@code api/GlobalExceptionHandler} declares, so the three
 * error surfaces — that advice for a {@code REQUEST} dispatch, its nested error-path controller for an
 * {@code ERROR} dispatch, and this valve for a container-level rejection — put the same literals on
 * the wire. The header policy is the {@code HeaderWriter} bean {@code security/SecurityConfig}
 * publishes, the same policy {@code HeaderWriterFilter} applies inside the chain — DL-241. The CORS
 * headers mirror the permissive policy {@code config/CorsConfig} declares, read from that same bean —
 * DL-051.
 *
 * <p>A status that carries no body inside the chain carries none here either: 401 and 403 keep the
 * bare shape the chain's entry point produces — DL-115.
 *
 * <p>This class holds no mutable state and its valve is safe to share across concurrent requests.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-237, DL-238 and
 * DL-241; construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@Configuration
public class ContainerErrorResponseConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(ContainerErrorResponseConfig.class);

    /** The application's transport-security header policy — DL-241. */
    private final HeaderWriter transportSecurityHeaderWriter;

    /** The application's permissive CORS policy — DL-051. */
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Whether {@code server.forward-headers-strategy} makes the forwarded scheme the request's own,
     * which is what decides {@code Strict-Transport-Security} inside the chain.
     */
    private final boolean forwardedSchemeHonoured;

    /**
     * Creates the configuration with the policies the valve applies.
     *
     * @param transportSecurityHeaderWriter the policy {@code security/SecurityConfig} publishes, must
     *     not be {@code null}
     * @param corsConfigurationSource the policy {@code config/CorsConfig} publishes, must not be
     *     {@code null}
     * @param serverProperties the container's own properties, read for
     *     {@code server.forward-headers-strategy}, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public ContainerErrorResponseConfig(HeaderWriter transportSecurityHeaderWriter,
            CorsConfigurationSource corsConfigurationSource, ServerProperties serverProperties) {

        this.transportSecurityHeaderWriter = Objects.requireNonNull(transportSecurityHeaderWriter,
                "transportSecurityHeaderWriter must not be null");
        this.corsConfigurationSource = Objects.requireNonNull(corsConfigurationSource,
                "corsConfigurationSource must not be null");
        Objects.requireNonNull(serverProperties, "serverProperties must not be null");
        this.forwardedSchemeHonoured =
                honoursForwardedScheme(serverProperties.getForwardHeadersStrategy());
    }

    /**
     * Reports whether a configured strategy makes a forwarded scheme the request's own scheme.
     *
     * @param strategy the configured strategy, possibly {@code null} when none is set
     * @return {@code true} for {@code FRAMEWORK} and {@code NATIVE}, {@code false} otherwise
     */
    static boolean honoursForwardedScheme(ForwardHeadersStrategy strategy) {
        return strategy == ForwardHeadersStrategy.FRAMEWORK
                || strategy == ForwardHeadersStrategy.NATIVE;
    }

    /**
     * Replaces the container's error-report valve with {@link ErrorEnvelopeReportValve}.
     *
     * <p>Two container behaviours fix the shape of this method. {@code Pipeline.addValve} appends and
     * an error-report valve reports only after the valves beneath it have run, so the valve added last
     * is the one that reports and this customizer is ordered after Spring Boot's own, which installs
     * the HTML valve. {@code StandardHost} installs a further valve of its configured error-report
     * class at start unless one is already present, so that class name is cleared once the replacement
     * is in place — DL-237.
     *
     * @return the customizer; never {@code null}
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> errorEnvelopeValveCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            if (!(context.getParent() instanceof Host host)) {
                log.warn("The servlet context has no host parent; the container error envelope is "
                        + "not installed");
                return;
            }

            Pipeline pipeline = host.getPipeline();
            int replaced = 0;
            for (Valve valve : pipeline.getValves()) {
                if (valve instanceof ErrorReportValve) {
                    pipeline.removeValve(valve);
                    replaced++;
                }
            }
            if (host instanceof StandardHost standardHost) {
                // Empty is the value StandardHost.startInternal reads as "install none" — DL-237
                standardHost.setErrorReportValveClass("");
            }
            pipeline.addValve(new ErrorEnvelopeReportValve(transportSecurityHeaderWriter,
                    corsConfigurationSource, forwardedSchemeHonoured));

            log.info("Container error responses now carry the service error envelope; {} default "
                    + "error-report valve(s) replaced", replaced);
        });
    }

    /**
     * Writes this service's error envelope for a failure the container reports itself.
     *
     * <p>{@code ErrorReportValve.invoke} decides whether a report is due — it skips a committed
     * response, an in-flight asynchronous request and a response no error has been recorded on — and
     * this class overrides only the body it writes.
     */
    static final class ErrorEnvelopeReportValve extends ErrorReportValve {

        /** Lowest status a report is written for. */
        private static final int FIRST_ERROR_STATUS = 400;

        /** JSON key of the single-key envelope declared by {@code dto.ErrorResponse}. */
        private static final String ERROR_KEY = "error";

        /** Forwarded-scheme value that marks a request as having arrived over TLS. */
        private static final String HTTPS = "https";

        /** Target the policies are looked up against, which every policy of this service covers. */
        private static final String POLICY_LOOKUP_TARGET = "/";

        /** Context path of this application, which the container serves at the root. */
        private static final String ROOT_CONTEXT_PATH = "";

        private final HeaderWriter transportSecurityHeaderWriter;

        private final CorsConfigurationSource corsConfigurationSource;

        private final boolean forwardedSchemeHonoured;

        /**
         * Creates the valve with the policies it applies to every response it writes.
         *
         * @param transportSecurityHeaderWriter the transport-security policy, must not be
         *     {@code null}
         * @param corsConfigurationSource the CORS policy, must not be {@code null}
         * @param forwardedSchemeHonoured whether a forwarded {@code https} scheme is the request's own
         *     scheme, as {@code server.forward-headers-strategy} declares
         */
        ErrorEnvelopeReportValve(HeaderWriter transportSecurityHeaderWriter,
                CorsConfigurationSource corsConfigurationSource, boolean forwardedSchemeHonoured) {

            this.transportSecurityHeaderWriter = Objects.requireNonNull(transportSecurityHeaderWriter,
                    "transportSecurityHeaderWriter must not be null");
            this.corsConfigurationSource = Objects.requireNonNull(corsConfigurationSource,
                    "corsConfigurationSource must not be null");
            this.forwardedSchemeHonoured = forwardedSchemeHonoured;
        }

        /**
         * Writes the envelope for one rejected request.
         *
         * @param request the rejected request, read for its scheme and its {@code Origin} header
         * @param response the response being written
         * @param throwable the failure the container recorded, which reaches neither the body nor the
         *     log
         */
        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int recorded = response.getStatus();
            if (recorded < FIRST_ERROR_STATUS || response.getContentWritten() > 0) {
                return;
            }

            int status = GlobalExceptionHandler.errorDispatchStatusFor(recorded);
            String message = GlobalExceptionHandler.errorDispatchMessageFor(recorded);
            log.debug("Rendering the error envelope for a container-level rejection recorded as {}",
                    recorded);

            writeEnvelope(request, response, status, message);
        }

        /**
         * Applies this service's headers to a response the container rejected and writes the body.
         *
         * @param request the rejected request
         * @param response the response being written
         * @param status the status to report
         * @param message the literal to carry under {@value #ERROR_KEY}, or {@code null} for a
         *     response with no body
         */
        private void writeEnvelope(Request request, Response response, int status, String message) {
            try {
                response.setStatus(status);
                applyHeaders(request, response);

                if (message == null) {
                    response.setContentLength(0);
                    response.finishResponse();
                    return;
                }

                // The content type carries no charset parameter, matching every other error
                // response of this service — DL-237
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);

                Writer reporter = response.getReporter();
                if (reporter != null) {
                    reporter.write(envelope(message));
                    response.finishResponse();
                }
            } catch (IOException | IllegalStateException ex) {
                // The connection is gone or the response is already committed; there is nothing left
                // to write.
                log.debug("The error envelope could not be written for a container-level rejection: "
                        + "{}", LogSafe.type(ex));
            }
        }

        /**
         * Writes this service's transport-security headers and its permissive CORS headers.
         *
         * <p>Both policies are applied against {@link RejectedRequestView}, which reports a
         * resolvable target and the request's effective scheme.
         *
         * @param request the rejected request
         * @param response the response being written
         */
        void applyHeaders(HttpServletRequest request, HttpServletResponse response) {
            HttpServletRequest view = new RejectedRequestView(request, forwardedSchemeHonoured);

            transportSecurityHeaderWriter.writeHeaders(view, response);

            addVaryIfAbsent(response, HttpHeaders.ORIGIN);
            addVaryIfAbsent(response, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            addVaryIfAbsent(response, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

            String origin = view.getHeader(HttpHeaders.ORIGIN);
            if (origin == null) {
                return;
            }
            String allowedOrigin = allowedOriginFor(view, origin);
            if (allowedOrigin != null) {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
            }
        }

        /**
         * Adds one {@code Vary} value the response does not already carry.
         *
         * <p>A response the filter chain reached before the failure was recorded already carries the
         * three values {@code DefaultCorsProcessor} writes; each value stays single — DL-237.
         *
         * @param response the response being written
         * @param value the {@code Vary} value to add
         */
        private static void addVaryIfAbsent(HttpServletResponse response, String value) {
            if (!response.getHeaders(HttpHeaders.VARY).contains(value)) {
                response.addHeader(HttpHeaders.VARY, value);
            }
        }

        /**
         * Resolves the value the application's CORS policy allows for one request origin.
         *
         * @param view the rejected request, presented with a resolvable target
         * @param origin the request's {@code Origin} header value
         * @return the value to write as {@code Access-Control-Allow-Origin}, or {@code null} when the
         *     policy allows no origin for this request or cannot be resolved at all
         */
        private String allowedOriginFor(HttpServletRequest view, String origin) {
            try {
                CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(view);
                return (configuration == null) ? null : configuration.checkOrigin(origin);
            } catch (RuntimeException ex) {
                log.debug("The CORS policy could not be resolved for a container-level rejection: {}",
                        LogSafe.type(ex));
                return null;
            }
        }

        /**
         * The view of a rejected request the header and CORS policies are applied against.
         *
         * <p>Two properties of such a request are restated by this view. Its target is what the
         * connector rejected and need not parse as a path a policy can be looked up under, so the view
         * reports {@value #POLICY_LOOKUP_TARGET}, which the single policy {@code config/CorsConfig}
         * registers for every path covers. No filter has run, so the forwarded scheme has not been
         * applied; when {@code server.forward-headers-strategy} declares that scheme authoritative,
         * the view reports the request as secure for a forwarded {@code https}, which is what
         * {@code HstsHeaderWriter} reads — DL-237, DL-241.
         */
        static final class RejectedRequestView extends HttpServletRequestWrapper {

            private final boolean forwardedSchemeHonoured;

            /**
             * Wraps one rejected request.
             *
             * @param request the rejected request, must not be {@code null}
             * @param forwardedSchemeHonoured whether a forwarded {@code https} scheme is this
             *     request's own scheme
             */
            RejectedRequestView(HttpServletRequest request, boolean forwardedSchemeHonoured) {
                super(request);
                this.forwardedSchemeHonoured = forwardedSchemeHonoured;
            }

            @Override
            public String getRequestURI() {
                return POLICY_LOOKUP_TARGET;
            }

            @Override
            public String getServletPath() {
                return POLICY_LOOKUP_TARGET;
            }

            @Override
            public String getPathInfo() {
                return null;
            }

            @Override
            public String getContextPath() {
                return ROOT_CONTEXT_PATH;
            }

            @Override
            public boolean isSecure() {
                return super.isSecure() || forwardedHttps();
            }

            @Override
            public String getScheme() {
                return forwardedHttps() ? HTTPS : super.getScheme();
            }

            /**
             * Reports whether the request carries a forwarded scheme of {@code https} that this
             * application's configuration declares authoritative.
             *
             * @return {@code true} when the scheme is honoured and the first forwarded value is
             *     {@code https}, in any letter case
             */
            private boolean forwardedHttps() {
                if (!forwardedSchemeHonoured) {
                    return false;
                }
                String forwarded = super.getHeader("X-Forwarded-Proto");
                if (forwarded == null || forwarded.isBlank()) {
                    return false;
                }
                int firstDelimiter = forwarded.indexOf(',');
                String first = (firstDelimiter < 0) ? forwarded
                        : forwarded.substring(0, firstDelimiter);
                return HTTPS.equalsIgnoreCase(first.trim());
            }
        }

        /**
         * Renders the single-key envelope.
         *
         * @param message the literal to carry, which is one of the literals
         *     {@code api/GlobalExceptionHandler} declares and therefore holds no character JSON
         *     escapes
         * @return the JSON document to write
         */
        private static String envelope(String message) {
            return "{\"" + ERROR_KEY + "\":\"" + message + "\"}";
        }
    }
}
