package com.codeskeptic.scanner.api;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.codeskeptic.scanner.dto.ErrorResponse;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;

/**
 * Translates an exception raised while a request is being handled into the HTTP status code and the
 * response body the service returns.
 *
 * <p>This class is the service's single exception-to-status translation point. The retired Python tree
 * registered two handlers on the application object — {@code @app.errorhandler(404)} at
 * {@code backend/app/main.py:L31-33} and {@code @app.errorhandler(500)} at {@code :L35-37} — and
 * carried the other eight status/body pairs inline inside the eleven route functions.
 *
 * <p>A failure answered by {@code HttpServletResponse.sendError(int)} unwinds the current dispatch
 * before any exception can reach this advice, and the container re-dispatches the request to the error
 * page. {@link ErrorDispatchController} answers that dispatch with the same literals declared below,
 * so the two classes together are the whole of the service's error surface — DL-183.
 *
 * <p>Every body produced here is an {@link ErrorResponse}: the single-key
 * {@code {"error": <string>}} envelope. The complete set of status and message pairs this class puts
 * on the wire is the following.
 *
 * <table border="1">
 * <caption>Status and message emitted by each handler</caption>
 * <tr>
 *   <th>Handler</th><th>Status</th><th>Message on the wire</th><th>Source</th>
 * </tr>
 * <tr>
 *   <td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>{@code Tweet not found}</td><td>{@code backend/app/api/tweets.py:L32}, {@code :L43}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>{@code Response not found}</td><td>{@code backend/app/api/responses.py:L31}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>{@code Response not found or update failed}</td>
 *   <td>{@code backend/app/api/responses.py:L65}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>{@code Setting not found}</td><td>{@code backend/app/api/settings.py:L22}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleBadRequest(BadRequestException)}</td><td>400</td>
 *   <td>{@code Tweet ID is required}</td><td>{@code backend/app/api/responses.py:L41}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleBadRequest(BadRequestException)}</td><td>400</td>
 *   <td>{@code Update data is required}</td><td>{@code backend/app/api/responses.py:L57}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleBadRequest(BadRequestException)}</td><td>400</td>
 *   <td>{@code No value provided}</td><td>{@code backend/app/api/settings.py:L18}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleResponseGenerationFailure(ResponseGenerationException)}</td><td>500</td>
 *   <td>{@code Failed to generate response}</td><td>{@code backend/app/api/responses.py:L49}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleMethodArgumentNotValid(MethodArgumentNotValidException)}</td><td>400</td>
 *   <td>{@code Tweet ID is required}</td><td>{@code backend/app/api/responses.py:L41}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleMethodArgumentNotValid(MethodArgumentNotValidException)}</td><td>400</td>
 *   <td>{@code No value provided}</td><td>{@code backend/app/api/settings.py:L18}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleNoHandlerFound()}</td><td>404</td>
 *   <td>{@code Not found}</td><td>{@code backend/app/main.py:L33}</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleClientRequestFailure(Exception)}</td><td>400</td>
 *   <td>{@code Bad request}</td><td>net-new — see docs/DECISION_LOG.md DL-092</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleMethodNotSupported(HttpRequestMethodNotSupportedException)}</td><td>405</td>
 *   <td>{@code Method not allowed}</td><td>net-new — see docs/DECISION_LOG.md DL-092</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleNotAcceptable(HttpMediaTypeNotAcceptableException)}</td><td>406</td>
 *   <td>{@code Not acceptable}</td><td>net-new — see docs/DECISION_LOG.md DL-092</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleUnsupportedMediaType(HttpMediaTypeNotSupportedException)}</td><td>415</td>
 *   <td>{@code Unsupported media type}</td><td>net-new — see docs/DECISION_LOG.md DL-092</td>
 * </tr>
 * <tr>
 *   <td>{@link #handleUnexpectedException(Exception)}</td><td>500</td>
 *   <td>{@code Internal server error}</td><td>{@code backend/app/main.py:L37}</td>
 * </tr>
 * </table>
 *
 * <p>The three {@code com.codeskeptic.scanner.exception} types declare no {@code @ResponseStatus};
 * their status is assigned here. Their messages are copied through {@link Throwable#getMessage()}
 * unaltered, so each of the eight per-route literals round-trips character-for-character.
 *
 * <p>Spring selects a handler by exception type, most specific match first. A client failure Spring
 * MVC raises — a malformed body, a missing or unconvertible request value, an unsupported method, an
 * unsupported media type or an unsatisfiable {@code Accept} header — is matched by one of the four
 * framework handlers and keeps the status the framework assigns it;
 * {@link #handleUnexpectedException(Exception)} receives what no earlier handler matches — DL-092.
 *
 * <p>Authentication and authorisation failures are answered by the security filter chain, which runs
 * ahead of the {@code DispatcherServlet}; no exception from them reaches this class. A request the
 * chain's firewall rejects is answered with {@code sendError} and therefore reaches
 * {@link ErrorDispatchController} instead.
 *
 * <p>All state declared here is immutable. The single advice instance is safe to share across
 * concurrent requests.
 */
// Ported from backend/app/main.py:L31-37 (faithful port) — see docs/DECISION_LOG.md
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Records the exceptions reported by {@link #handleUnexpectedException(Exception)}. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Wire literal of {@code backend/app/main.py:L33}. */
    private static final String NOT_FOUND = "Not found";

    /** Wire literal of {@code backend/app/main.py:L37}. */
    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    /**
     * Message served with HTTP 400 for a request the framework rejected before a handler read it. Net-new
     * (no source literal; {@code backend/app/main.py:L31-37} registered handlers for 404 and 500 only) —
     * see docs/DECISION_LOG.md DL-092.
     */
    private static final String BAD_REQUEST = "Bad request";

    /** Message served with HTTP 405 — see docs/DECISION_LOG.md DL-092. */
    private static final String METHOD_NOT_ALLOWED = "Method not allowed";

    /** Message served with HTTP 415 — see docs/DECISION_LOG.md DL-092. */
    private static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";

    /** Message served with HTTP 406 — see docs/DECISION_LOG.md DL-092. */
    private static final String NOT_ACCEPTABLE = "Not acceptable";

    /**
     * Rejected-field names that select {@link BadRequestException#TWEET_ID_IS_REQUIRED}.
     *
     * <p>Holds the record component name of {@code com.codeskeptic.scanner.dto.CreateResponseRequest}
     * and the JSON key it binds from.
     */
    private static final Set<String> TWEET_ID_FIELD_NAMES = Set.of("tweetId", "tweet_id");

    /**
     * Rejected-field names that select {@link BadRequestException#NO_VALUE_PROVIDED}.
     *
     * <p>Holds the record component name of {@code com.codeskeptic.scanner.dto.UpdateSettingRequest},
     * which is also the JSON key it binds from.
     */
    private static final Set<String> VALUE_FIELD_NAMES = Set.of("value");

    /**
     * Reports an absent entity with HTTP 404 and the exception's own message.
     *
     * <p>Carries the four literals of {@code backend/app/api/tweets.py:L32,L43},
     * {@code backend/app/api/responses.py:L31,L65} and {@code backend/app/api/settings.py:L22}; the
     * message is passed through unmapped, so the two response-scoped literals stay distinct.
     *
     * @param ex the raised exception; its {@link Throwable#getMessage()} becomes the response body
     * @return HTTP 404 carrying {@code {"error": <ex.getMessage()>}}
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Reports a rejected request with HTTP 400 and the exception's own message.
     *
     * <p>Carries the three literals of {@code backend/app/api/responses.py:L41,L57} and
     * {@code backend/app/api/settings.py:L18}.
     *
     * @param ex the raised exception; its {@link Throwable#getMessage()} becomes the response body
     * @return HTTP 400 carrying {@code {"error": <ex.getMessage()>}}
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.getMessage()));
    }

    /**
     * Reports an incomplete response generation with HTTP 500 and the exception's own message.
     *
     * <p>Carries the literal of {@code backend/app/api/responses.py:L49}, which is
     * {@link ResponseGenerationException#FAILED_TO_GENERATE_RESPONSE}.
     * {@link #handleUnexpectedException(Exception)} also produces HTTP 500, with the separate message
     * {@value #INTERNAL_SERVER_ERROR}.
     *
     * @param ex the raised exception; its {@link Throwable#getMessage()} becomes the response body
     * @return HTTP 500 carrying {@code {"error": <ex.getMessage()>}}
     */
    @ExceptionHandler(ResponseGenerationException.class)
    public ResponseEntity<ErrorResponse> handleResponseGenerationFailure(ResponseGenerationException ex) {
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
     * <p>Both messages are read from the constants declared on {@link BadRequestException}, the same
     * constants {@link #handleBadRequest(BadRequestException)} passes through.
     *
     * <p>An unmapped field name yields that field error's own
     * {@link FieldError#getDefaultMessage()}, and a binding result carrying no field error yields
     * {@code {"error": null}}.
     *
     * @param ex the raised exception, whose binding result supplies the rejected field names
     * @return HTTP 400 carrying the single-key error envelope
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message = fieldErrors.isEmpty() ? null : validationMessageFor(fieldErrors.get(0));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(message));
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
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(NOT_FOUND));
    }

    /**
     * Reports a request the framework rejected before a handler could read it with HTTP 400 and the
     * message {@value #BAD_REQUEST}.
     *
     * <p>Covers a syntactically malformed or unreadable request body
     * ({@link HttpMessageNotReadableException}), a missing or unbindable request value
     * ({@link ServletRequestBindingException}, which is the supertype of the missing-parameter and
     * missing-header failures), a missing multipart part
     * ({@link MissingServletRequestPartException}) and a request value whose text the target type
     * cannot hold ({@link TypeMismatchException}, which is the supertype of the path-variable and
     * query-parameter conversion failures).
     *
     * <p>The eight per-route 400 literals are carried by {@link BadRequestException} and answered by
     * {@link #handleBadRequest(BadRequestException)} — DL-092.
     *
     * @param ex the raised exception; neither its type nor its message reaches the response body
     * @return HTTP 400 carrying {@code {"error": "Bad request"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MissingServletRequestPartException.class, TypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleClientRequestFailure(Exception ex) {
        log.debug("Rejecting a malformed request with HTTP 400: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(BAD_REQUEST));
    }

    /**
     * Reports a request whose method the matched path does not support with HTTP 405 and the message
     * {@value #METHOD_NOT_ALLOWED}.
     *
     * <p>The response carries the {@code Allow} header listing the methods the path does support —
     * see docs/DECISION_LOG.md DL-092.
     *
     * @param ex the raised exception, supplying the supported methods
     * @return HTTP 405 carrying {@code {"error": "Method not allowed"}} and an {@code Allow} header
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {

        log.debug("Rejecting an unsupported method with HTTP 405");
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new HttpMethod[0]));
        }
        return response.body(new ErrorResponse(METHOD_NOT_ALLOWED));
    }

    /**
     * Reports a request body whose media type no handler consumes with HTTP 415 and the message
     * {@value #UNSUPPORTED_MEDIA_TYPE}.
     *
     * <p>See docs/DECISION_LOG.md DL-092.
     *
     * @param ex the raised exception; neither its type nor its message reaches the response body
     * @return HTTP 415 carrying {@code {"error": "Unsupported media type"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {

        log.debug("Rejecting an unsupported media type with HTTP 415");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse(UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * Reports a request whose {@code Accept} header no handler can satisfy with HTTP 406 and the
     * message {@value #NOT_ACCEPTABLE}.
     *
     * <p>See docs/DECISION_LOG.md DL-092.
     *
     * @param ex the raised exception; neither its type nor its message reaches the response body
     * @return HTTP 406 carrying {@code {"error": "Not acceptable"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.debug("Rejecting a request whose Accept header cannot be satisfied with HTTP 406");
        // The envelope is written as JSON irrespective of the unsatisfiable Accept header, so the
        // response always carries a body - DL-092 - see docs/DECISION_LOG.md
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(NOT_ACCEPTABLE));
    }

    /**
     * Reports any exception no other handler matches with HTTP 500 and the message
     * {@value #INTERNAL_SERVER_ERROR}.
     *
     * <p>Reproduces {@code backend/app/main.py:L35-37}. A client failure the framework raises is
     * matched by an earlier handler — DL-092. The exception is written to the log at {@code ERROR}
     * with its stack trace; neither its type nor its message reaches the response body.
     *
     * @param ex the raised exception, recorded in the log
     * @return HTTP 500 carrying {@code {"error": "Internal server error"}}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception reached the error-handling advice; responding HTTP 500", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(INTERNAL_SERVER_ERROR));
    }

    /**
     * Resolves the wire message for a single rejected field.
     *
     * @param fieldError the rejected field reported by Bean Validation
     * @return {@link BadRequestException#TWEET_ID_IS_REQUIRED} or
     *         {@link BadRequestException#NO_VALUE_PROVIDED} for a mapped field name, otherwise the
     *         field error's own default message, which may be {@code null}
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
        return fieldError.getDefaultMessage();
    }
}
