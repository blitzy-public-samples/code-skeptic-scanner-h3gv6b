package com.codeskeptic.scanner.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.codeskeptic.scanner.dto.ErrorResponse;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;

import jakarta.servlet.RequestDispatcher;

/**
 * Translates an exception raised while a request is being handled into the HTTP status code and the
 * response body the service returns.
 *
 * <p>Replaces the two {@code @app.errorhandler} functions at {@code backend/app/main.py:L31-37}. The
 * wire surface is exactly theirs: the eight per-route literals carried through unaltered, plus the
 * two global literals {@value #NOT_FOUND} and {@value #INTERNAL_SERVER_ERROR}. Every body is an
 * {@link ErrorResponse}, the single-key {@code {"error": <string>}} envelope — DL-210.
 *
 * <table border="1">
 * <caption>Status and message emitted by each handler</caption>
 * <tr><th>Handler</th><th>Status</th><th>Message on the wire</th></tr>
 * <tr><td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>the exception's own message — one of five source branches carrying four distinct
 *   literals</td></tr>
 * <tr><td>{@link #handleBadRequest(BadRequestException)}</td><td>400</td>
 *   <td>the exception's own message — one of three source branches carrying three distinct
 *   literals</td></tr>
 * <tr><td>{@link #handleResponseGenerationFailure(ResponseGenerationException)}</td><td>500</td>
 *   <td>{@code Failed to generate response}</td></tr>
 * <tr><td>{@link #handleMethodArgumentNotValid(MethodArgumentNotValidException)}</td><td>400</td>
 *   <td>{@code Tweet ID is required} or {@code No value provided}; no body for any other field</td></tr>
 * <tr><td>{@link #handleNoHandlerFound()}</td><td>404</td><td>{@value #NOT_FOUND}</td></tr>
 * <tr><td>{@link #handleUnexpectedException(Exception)}</td>
 *   <td>the framework's own status, or 500</td>
 *   <td>no body for a framework request failure; {@value #INTERNAL_SERVER_ERROR} otherwise</td></tr>
 * </table>
 *
 * <p>A framework request failure keeps the status the framework assigns it and carries <em>no</em>
 * body, which is how the retired application answered each of them: only 404 and 500 carried the JSON
 * envelope. {@link #handleUnexpectedException(Exception)} is the single handler that decides this — DL-092.
 *
 * <p>The three {@code com.codeskeptic.scanner.exception} types declare no {@code @ResponseStatus};
 * their status is assigned here and their messages are copied through {@link Throwable#getMessage()}
 * character-for-character — DL-212. Authentication and authorisation failures are answered by the
 * security filter chain ahead of the {@code DispatcherServlet} and reach no handler here.
 *
 * <p>All state declared here is immutable; the single advice instance is safe to share.
 */
// Ported from backend/app/main.py:L31-37 (faithful port) — see docs/DECISION_LOG.md
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NOT_FOUND = "Not found";

    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private static final String ERROR_KEY = "error";

    private static final int TYPE_CHAIN_LIMIT = 5;

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String CHAIN_CONTINUES = "...";

    private static final String ABSENT = "absent";

    /**
     * Field names that select {@link BadRequestException#TWEET_ID_IS_REQUIRED}. Both the record
     * component name and the wire name are declared, so the message does not depend on which of the
     * two Bean Validation reports.
     */
    private static final Set<String> TWEET_ID_FIELD_NAMES = Set.of("tweetId", "tweet_id");

    private static final Set<String> VALUE_FIELD_NAMES = Set.of("value");

    // Envelope intent ported from backend/app/main.py:L31-37 (faithful port); the servlet ERROR-dispatch
    // mechanism that carries it is net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-183
    /**
     * Publishes the error-attribute source that renders this class's envelope on the servlet
     * {@code ERROR} dispatch.
     *
     * <p>The handler methods below translate exceptions raised during a {@code REQUEST} dispatch. A
     * failure answered with {@code HttpServletResponse.sendError(int)} — which Spring Security's
     * request firewall issues for a rejected path such as {@code //tweets} — unwinds that dispatch and
     * is re-dispatched to the error page. This bean supplies the attributes rendered on that second
     * dispatch, so a dispatched 404 or 500 puts the same envelope on the wire as the handlers below
     * and no dispatch carries a request path, a timestamp or an exception detail — DL-183.
     *
     * <p>{@code DefaultErrorAttributes} is declared only while the context holds no
     * {@link ErrorAttributes} bean, so this bean replaces it while Spring Boot's own
     * {@code BasicErrorController} stays the handler mapped to the error path.
     *
     * @return the error-attribute source, replacing the framework default
     */
    @Bean
    ErrorAttributes errorEnvelopeAttributes() {
        return new ErrorEnvelopeAttributes();
    }

    /**
     * Reports an absent entity with HTTP 404 and the exception's own message.
     *
     * <p>Carries the five source branches at {@code backend/app/api/tweets.py:L32,L43},
     * {@code backend/app/api/responses.py:L31,L65} and {@code backend/app/api/settings.py:L22}, which
     * declare four distinct literals between them: the two tweet branches share one. The message is
     * passed through unmapped and the two response-scoped literals stay distinct — DL-212.
     *
     * @param ex the raised exception, whose message becomes the response body
     * @return HTTP 404 carrying the single-key error envelope
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            NotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Reports a rejected request with HTTP 400 and the exception's own message.
     *
     * <p>Carries the three literals of {@code backend/app/api/responses.py:L41,L57} and
     * {@code backend/app/api/settings.py:L18}; the message is passed through unmapped.
     *
     * @param ex the raised exception, whose message becomes the response body
     * @return HTTP 400 carrying the single-key error envelope
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Reports a failed response generation with HTTP 500 and the exception's own message.
     *
     * <p>Reproduces {@code backend/app/api/responses.py:L48-49}, whose body is
     * {@code {"error": "Failed to generate response"}}. The public outcome is recorded once at
     * {@code WARN} naming the cause's type only; the {@code ERROR} owner sits upstream in the failing
     * layer — see docs/DECISION_LOG.md DL-084 and DL-197.
     *
     * @param ex the raised exception, whose message becomes the response body
     * @return HTTP 500 carrying the single-key error envelope
     */
    @ExceptionHandler(ResponseGenerationException.class)
    public ResponseEntity<ErrorResponse> handleResponseGenerationFailure(
            ResponseGenerationException ex) {

        Throwable reported = (ex.getCause() == null) ? ex : ex.getCause();
        log.warn("Responding HTTP 500 with the generation literal; cause {}",
                reported.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Reports a request body that failed Bean Validation with HTTP 400.
     *
     * <p>Reached when a controller binds its body with {@code @Valid}. The tree declares two
     * {@code @NotNull} constraints, corresponding to the inline guards at
     * {@code backend/app/api/responses.py:L40-41} and {@code backend/app/api/settings.py:L17-18}. The
     * name of the first rejected field selects the message:
     *
     * <ul>
     *   <li>{@code tweetId} or {@code tweet_id} selects
     *       {@link BadRequestException#TWEET_ID_IS_REQUIRED}, the literal of
     *       {@code backend/app/api/responses.py:L41}</li>
     *   <li>{@code value} selects {@link BadRequestException#NO_VALUE_PROVIDED}, the literal of
     *       {@code backend/app/api/settings.py:L18}</li>
     * </ul>
     *
     * <p>An unmapped field name and a binding result carrying no field error both yield HTTP 400 with
     * no body, so this handler writes one of exactly two messages and no framework text, no annotation
     * text and no field name reaches the wire.
     *
     * @param ex the raised exception, whose binding result supplies the rejected field names
     * @return HTTP 400 carrying the single-key error envelope, or HTTP 400 with no body
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {

        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message = fieldErrors.isEmpty() ? null : validationMessageFor(fieldErrors.get(0));
        if (message == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message));
    }

    /**
     * Reports a request whose path matches no handler with HTTP 404 and the message
     * {@value #NOT_FOUND}.
     *
     * <p>Reproduces {@code backend/app/main.py:L31-33}. Both types Spring MVC raises for an unmatched
     * path are declared: {@link NoHandlerFoundException}, raised by the {@code DispatcherServlet} when
     * {@code spring.mvc.throw-exception-if-no-handler-found} is enabled, and
     * {@link NoResourceFoundException}, raised by the resource handler when
     * {@code spring.web.resources.add-mappings} is enabled.
     *
     * @return HTTP 404 carrying {@code {"error": "Not found"}}
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandlerFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(NOT_FOUND));
    }

    /**
     * Reports every exception no other handler matches.
     *
     * <p>Reproduces {@code backend/app/main.py:L35-37} for a genuine internal failure: HTTP 500 with
     * the body {@code {"error": "Internal server error"}}. Neither the exception's type nor its
     * message reaches the response body, and the {@code ERROR} record names the bounded chain of
     * failure types {@link #typeChain(Throwable)} renders and nothing the failure itself wrote: no
     * message, no cause text and no stack trace reaches the record, and no throwable is attached to it
     * — DL-197 — see docs/DECISION_LOG.md.
     *
     * <p>A framework request failure is answered differently, and this is the single place that
     * decides it. A failure implementing Spring's {@code org.springframework.web.ErrorResponse} — an unsupported method, an
     * unsupported or unacceptable media type, a missing or unconvertible request value — and a body the
     * converter could not read both keep the status the framework assigns them and carry no body at
     * all. That is the behaviour of the retired application, in which only the 404 and 500 handlers
     * produced the JSON envelope and every other status was answered by Werkzeug's own page, so no
     * status beyond 404 and 500 gains a literal of its own. Such a failure is recorded at
     * {@code DEBUG}, not at {@code ERROR}: it is a caller mistake, not a defect.
     *
     * @param ex the raised exception, reported as bounded metadata
     * @return the framework's own status with no body for a framework request failure, otherwise
     *         HTTP 500 carrying {@code {"error": "Internal server error"}}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex) {

        HttpStatusCode frameworkStatus = frameworkStatusOf(ex);
        if (frameworkStatus != null) {
            log.debug("Rejecting a request the framework could not handle with HTTP {}: {}",
                    frameworkStatus.value(), ex.getClass().getSimpleName());
            return ResponseEntity.status(frameworkStatus)
                    .headers(frameworkHeadersOf(ex))
                    .build();
        }

        log.error("Unhandled exception reached the error-handling advice; responding HTTP 500. "
                + "Failure {}", typeChain(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(INTERNAL_SERVER_ERROR));
    }

    /**
     * Reports the status a framework request failure carries, and {@code null} for every other
     * failure.
     *
     * <p>A failure implementing Spring's {@code org.springframework.web.ErrorResponse} publishes the
     * status the framework assigns it. {@link HttpMessageConversionException} does not implement that
     * interface and is named explicitly: a request body the converter could not turn into the target
     * type — whether it is malformed JSON, which raises the
     * {@link HttpMessageNotReadableException} subtype, or a body repeating a component of the target
     * record, which raises the supertype directly — is a 400. Its sibling
     * {@link HttpMessageNotWritableException} is excluded. A response this service could not write
     * is a defect of this service and belongs on the 500 path.
     *
     * @param ex the raised exception, never {@code null}
     * @return the status to answer with, or {@code null} when the failure is not a framework request
     *         failure
     */
    private static HttpStatusCode frameworkStatusOf(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse published) {
            HttpStatusCode status = published.getStatusCode();
            return status.is5xxServerError() ? null : status;
        }
        if (ex instanceof HttpMessageConversionException
                && !(ex instanceof HttpMessageNotWritableException)) {
            return HttpStatus.BAD_REQUEST;
        }
        return null;
    }

    /**
     * Reports the response headers a framework request failure requires.
     *
     * <p>A failure implementing Spring's {@code org.springframework.web.ErrorResponse} carries the
     * headers its own status mandates — {@code Allow} on a 405 and {@code Accept} on a 415 or a 406 —
     * and those headers are copied onto the response so the status stays well formed. No other header
     * is contributed, and no header carries a wire literal.
     *
     * @param ex the raised exception, never {@code null}
     * @return the headers to write, empty when the failure declares none
     */
    private static HttpHeaders frameworkHeadersOf(Exception ex) {
        return (ex instanceof org.springframework.web.ErrorResponse published)
                ? published.getHeaders()
                : HttpHeaders.EMPTY;
    }

    /**
     * Resolves the wire message for a single rejected field.
     *
     * @param fieldError the rejected field reported by Bean Validation
     * @return {@link BadRequestException#TWEET_ID_IS_REQUIRED} or
     *         {@link BadRequestException#NO_VALUE_PROVIDED} for a mapped field name, otherwise
     *         {@code null}; never the field error's own message
     */
    private static String validationMessageFor(FieldError fieldError) {
        String field = fieldError.getField();
        if (field != null) {
            if (TWEET_ID_FIELD_NAMES.contains(field)) {
                return BadRequestException.TWEET_ID_IS_REQUIRED;
            }
            if (VALUE_FIELD_NAMES.contains(field)) {
                return BadRequestException.NO_VALUE_PROVIDED;
            }
        }
        return null;
    }

    // Bounded failure metadata for a log record — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders the chain of failure types a throwable and its causes declare.
     *
     * <p>At most {@value #TYPE_CHAIN_LIMIT} links are rendered, joined by {@value #CAUSE_SEPARATOR},
     * and a longer chain closes with {@value #CHAIN_CONTINUES}. A cycle in the cause chain terminates
     * the rendering. No message, no cause text and no stack frame is rendered, so nothing the failure
     * itself wrote reaches the record — DL-197.
     *
     * @param failure the failure to render, possibly {@code null}
     * @return the rendered chain, or {@value #ABSENT} when {@code failure} is {@code null}
     */
    private static String typeChain(Throwable failure) {
        if (failure == null) {
            return ABSENT;
        }
        StringBuilder chain = new StringBuilder();
        Throwable link = failure;
        int rendered = 0;
        while (link != null && rendered < TYPE_CHAIN_LIMIT) {
            if (rendered > 0) {
                chain.append(CAUSE_SEPARATOR);
            }
            chain.append(link.getClass().getSimpleName());
            rendered++;
            Throwable cause = link.getCause();
            link = (cause == link) ? null : cause;
        }
        if (link != null) {
            chain.append(CAUSE_SEPARATOR).append(CHAIN_CONTINUES);
        }
        return chain.toString();
    }

    /**
     * Renders the single-key error envelope for the servlet {@code ERROR} dispatch.
     *
     * <p>Every attribute the framework default contributes — {@code timestamp}, {@code status},
     * {@code error}, {@code path}, {@code exception}, {@code message}, {@code trace} and
     * {@code errors} — is replaced by the one key {@value GlobalExceptionHandler#ERROR_KEY} carrying
     * {@value GlobalExceptionHandler#NOT_FOUND} for a dispatched 404 and
     * {@value GlobalExceptionHandler#INTERNAL_SERVER_ERROR} for a dispatched 500. Every other status —
     * a dispatched 401, 403, 405, 406 or 415 included — yields an empty map, which carries no literal,
     * so the dispatch adds no wire literal to the two the retired handlers declared.
     *
     * <p>The superclass is retained so the framework still records the dispatched exception as a
     * request attribute; only the rendered attribute map is replaced.
     */
    private static final class ErrorEnvelopeAttributes extends DefaultErrorAttributes {

        /**
         * Returns the attribute map rendered for the dispatched failure.
         *
         * @param webRequest the dispatched request, read only for the recorded status
         * @param options    the framework's inclusion options; this implementation ignores them and
         *                   contributes no optional attribute
         * @return an immutable map holding at most the single key
         *         {@value GlobalExceptionHandler#ERROR_KEY}
         */
        @Override
        public Map<String, Object> getErrorAttributes(WebRequest webRequest,
                ErrorAttributeOptions options) {
            int status = recordedStatus(webRequest);
            log.debug("Rendering the error envelope for a dispatched status of {}", status);
            if (status == HttpStatus.NOT_FOUND.value()) {
                return Map.of(ERROR_KEY, NOT_FOUND);
            }
            if (status == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                return Map.of(ERROR_KEY, INTERNAL_SERVER_ERROR);
            }
            return Map.of();
        }

        /**
         * Reads the status the container recorded for the failure being dispatched.
         *
         * @param webRequest the dispatched request
         * @return the recorded status, or {@link HttpStatus#INTERNAL_SERVER_ERROR}'s value when the
         *         attribute is absent or does not hold an integer
         */
        static int recordedStatus(WebRequest webRequest) {
            Object recorded = webRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE,
                    RequestAttributes.SCOPE_REQUEST);
            return (recorded instanceof Integer status)
                    ? status
                    : HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }
}
