package com.codeskeptic.scanner.api;

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.ErrorResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Net-new (no Python counterpart; the retired tree served no /error route) — DL-183 — see
// docs/DECISION_LOG.md
/**
 * Renders the service's error envelope for the servlet {@code ERROR} dispatch.
 *
 * <p>{@link GlobalExceptionHandler} translates every exception that reaches the
 * {@code DispatcherServlet} during a {@code REQUEST} dispatch. It does not reach a failure answered by
 * {@code HttpServletResponse.sendError(int)}, which unwinds the current dispatch and asks the
 * container to re-dispatch the request to the error page. This class is the target of that
 * re-dispatch, so the two classes together are the whole of the service's error surface and every
 * error body on the wire is one of the six literals they share.
 *
 * <p>Implementing {@link ErrorController} withdraws Spring Boot's {@code BasicErrorController}, which
 * {@code ErrorMvcAutoConfiguration} declares
 * {@code @ConditionalOnMissingBean(value = ErrorController.class, search = SearchStrategy.CURRENT)}.
 * That auto-configuration's container-level error-page registration is a separate, unconditional
 * bean, so the container still dispatches to the path below. The mapped path is read from
 * {@code server.error.path}, falling back to {@code error.path} and then to {@code /error}, the
 * expression {@code BasicErrorController} itself declares.
 *
 * <p>The mapping names no HTTP method; an {@code ERROR} dispatch preserves the method of the
 * request that failed.
 *
 * <table border="1">
 * <caption>Status and message this controller puts on the wire</caption>
 * <tr><th>Condition</th><th>Status</th><th>Message on the wire</th></tr>
 * <tr>
 *   <td>Dispatch type is not {@code ERROR} — an authenticated client requested the path directly</td>
 *   <td>404</td><td>{@code Not found}</td>
 * </tr>
 * <tr><td>{@code ERROR} dispatch carrying status 401 or 403</td><td>unchanged</td>
 *   <td><em>no body</em></td></tr>
 * <tr><td>{@code ERROR} dispatch carrying status 404</td><td>404</td><td>{@code Not found}</td></tr>
 * <tr><td>{@code ERROR} dispatch carrying status 405</td><td>405</td>
 *   <td>{@code Method not allowed}</td></tr>
 * <tr><td>{@code ERROR} dispatch carrying status 406</td><td>406</td>
 *   <td>{@code Not acceptable}</td></tr>
 * <tr><td>{@code ERROR} dispatch carrying status 415</td><td>415</td>
 *   <td>{@code Unsupported media type}</td></tr>
 * <tr><td>{@code ERROR} dispatch carrying any other 4xx status</td><td>unchanged</td>
 *   <td>{@code Bad request}</td></tr>
 * <tr><td>{@code ERROR} dispatch carrying any status that is not 4xx</td><td>500</td>
 *   <td>{@code Internal server error}</td></tr>
 * </table>
 *
 * <p>Every row of that table describes what a caller carrying an accepted bearer token receives.
 * {@code security.SecurityConfig} authorizes every path other than {@code POST /auth/token} with
 * {@code anyRequest().authenticated()}, and an unmapped path is no exception: a request that carries
 * no accepted token is answered by the chain's entry point with 401, an empty body and a
 * {@code WWW-Authenticate: Bearer} challenge, and neither the {@code DispatcherServlet} nor this class
 * is reached. Those 404 rows are reachable only with a token — DL-021, DL-115. The retired
 * tree's {@code @app.errorhandler(404)} at {@code backend/app/main.py:L33} was registered outside the
 * {@code @jwt_required} guards and answered an unauthenticated caller as well.
 *
 * <p>Every message above is already emitted by {@link GlobalExceptionHandler}; this class introduces
 * none of its own. The body is always the single-key {@link ErrorResponse} envelope, written as
 * {@code application/json} irrespective of the request's {@code Accept} header. Neither the request
 * path, the request method, the status code, a timestamp nor any exception detail is copied into a
 * response body.
 *
 * <p>Spring Security's {@code HeaderWriterFilter} keeps the default
 * {@code shouldNotFilterErrorDispatch()} and is therefore skipped on an {@code ERROR} dispatch. This
 * class applies the policy {@code security/SecurityConfig} publishes as a {@code HeaderWriter} bean.
 * Each writer in that policy either skips a name the response
 * already carries or replaces its value through {@code setHeader}, so restating the policy over a
 * response the {@code REQUEST} dispatch already wrote leaves each header with exactly one value —
 * DL-183, DL-194.
 *
 * <p>An unauthenticated client does not reach this class: {@code security/SecurityConfig}
 * authenticates every request other than {@code POST /auth/token}, so its entry point answers a
 * direct, unauthenticated request to this path with 401 and no body. The 404 in the table above is
 * what an authenticated client reads.
 *
 * <p>This is a singleton bean holding its one collaborator in a final field and no mutable state, so
 * it is safe to share across concurrent requests.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-183 and DL-194;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@RestController
public class ErrorDispatchController implements ErrorController {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(ErrorDispatchController.class);

    /** Wire literal of {@code backend/app/main.py:L33}. */
    private static final String NOT_FOUND = "Not found";

    /** Wire literal of {@code backend/app/main.py:L37}. */
    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    /** Message served for a 4xx status outside the mapped set — see docs/DECISION_LOG.md DL-092. */
    private static final String BAD_REQUEST = "Bad request";

    /** Message served with HTTP 405 — see docs/DECISION_LOG.md DL-092. */
    private static final String METHOD_NOT_ALLOWED = "Method not allowed";

    /** Message served with HTTP 406 — see docs/DECISION_LOG.md DL-092. */
    private static final String NOT_ACCEPTABLE = "Not acceptable";

    /** Message served with HTTP 415 — see docs/DECISION_LOG.md DL-092. */
    private static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";

    /**
     * Messages keyed by the status the container recorded, for the four statuses that carry a message
     * of their own.
     *
     * <p>404 is present so an {@code ERROR} dispatch reports the same literal as
     * {@link GlobalExceptionHandler#handleNoHandlerFound()}.
     */
    private static final Map<Integer, String> MESSAGE_BY_STATUS = Map.of(
            HttpStatus.NOT_FOUND.value(), NOT_FOUND,
            HttpStatus.METHOD_NOT_ALLOWED.value(), METHOD_NOT_ALLOWED,
            HttpStatus.NOT_ACCEPTABLE.value(), NOT_ACCEPTABLE,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), UNSUPPORTED_MEDIA_TYPE);

    /**
     * The application's transport-security header policy, published by
     * {@code security/SecurityConfig} — DL-194.
     */
    private final HeaderWriter transportSecurityHeaderWriter;

    /**
     * Creates the controller with the shared transport-security header policy.
     *
     * @param transportSecurityHeaderWriter the policy {@code security/SecurityConfig} publishes, must
     *     not be {@code null}
     * @throws NullPointerException when {@code transportSecurityHeaderWriter} is {@code null}
     */
    public ErrorDispatchController(HeaderWriter transportSecurityHeaderWriter) {
        this.transportSecurityHeaderWriter = Objects.requireNonNull(transportSecurityHeaderWriter,
                "transportSecurityHeaderWriter must not be null");
    }

    /**
     * Answers the request the container dispatched to the error page.
     *
     * <p>A request whose dispatch type is not {@link DispatcherType#ERROR} was made by a client
     * addressing the path directly. The retired Python tree registered four blueprints at
     * {@code backend/app/main.py:L26-29} and none of them served this path, so such a request is
     * reported exactly as any other unmapped path is: 404 carrying {@value #NOT_FOUND}. An
     * unauthenticated caller does not reach this method at all — the chain's entry point answers such
     * a request with 401 and no body — so the 404 above is what an authenticated caller reads.
     *
     * <p>The shared header policy is applied to the response before the body is selected. Each of its
     * writers sets a header only when the response does not already carry it, so a header the
     * {@code REQUEST} dispatch already wrote keeps exactly one value — DL-194.
     *
     * @param request the dispatched request, read for its dispatch type and for the status the
     *     container recorded under {@link RequestDispatcher#ERROR_STATUS_CODE}
     * @param response the response being written, which receives the shared header policy
     * @return the status and single-key body named in this class's table
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request,
            HttpServletResponse response) {

        // The shared policy of security/SecurityConfig, applied where HeaderWriterFilter is skipped —
        // DL-194 — see docs/DECISION_LOG.md
        transportSecurityHeaderWriter.writeHeaders(request, response);

        if (request.getDispatcherType() != DispatcherType.ERROR) {
            log.debug("A client addressed the error path directly; responding HTTP 404");
            return respond(HttpStatus.NOT_FOUND.value(), NOT_FOUND);
        }

        int status = recordedStatus(request);
        log.debug("Rendering the error envelope for a dispatched status of {}", status);

        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            // The security chain answers an unauthenticated request with a bare status and no body;
            // a dispatched 401 or 403 keeps that shape.
            return respond(status, null);
        }
        String mapped = MESSAGE_BY_STATUS.get(status);
        if (mapped != null) {
            return respond(status, mapped);
        }
        if (isClientError(status)) {
            return respond(status, BAD_REQUEST);
        }
        return respond(HttpStatus.INTERNAL_SERVER_ERROR.value(), INTERNAL_SERVER_ERROR);
    }

    /**
     * Reads the status the container recorded for the failure being dispatched.
     *
     * @param request the dispatched request
     * @return the recorded status, or {@link HttpStatus#INTERNAL_SERVER_ERROR}'s value when the
     *     attribute is absent or does not hold an integer
     */
    private static int recordedStatus(HttpServletRequest request) {
        Object recorded = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (recorded instanceof Integer value) {
            return value;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * Reports whether a status lies in the 4xx range.
     *
     * @param status the status to test
     * @return {@code true} when {@code status} is at least 400 and below 500
     */
    private static boolean isClientError(int status) {
        return status >= HttpStatus.BAD_REQUEST.value()
                && status < HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * Builds the response for one status and message.
     *
     * @param status status to report
     * @param message message to carry under the JSON key {@code error}, or {@code null} for a
     *     response with no body
     * @return the response, carrying a {@code application/json} body when {@code message} is not
     *     {@code null} and no body otherwise. The transport-security headers are written onto the
     *     response itself by {@link #handleError(HttpServletRequest, HttpServletResponse)} — DL-194
     */
    private static ResponseEntity<ErrorResponse> respond(int status, String message) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (message == null) {
            return builder.build();
        }
        // The envelope is written as JSON irrespective of the request's Accept header, so the
        // response always carries a body — DL-183 — see docs/DECISION_LOG.md
        return builder.contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(message));
    }
}
