package com.codeskeptic.scanner.api;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
 *   <td>{@link #handleUnexpectedException(Exception)}</td><td>500</td>
 *   <td>{@code Internal server error}</td><td>{@code backend/app/main.py:L37}</td>
 * </tr>
 * </table>
 *
 * <p>The three {@code com.codeskeptic.scanner.exception} types declare no {@code @ResponseStatus};
 * their status is assigned here. Their messages are copied through {@link Throwable#getMessage()}
 * unaltered — never normalised, reworded or recased. Each of the eight per-route literals round-trips
 * character-for-character.
 *
 * <p>Spring selects a handler by exception type, most specific match first.
 * {@link #handleUnexpectedException(Exception)} receives only what no earlier handler matches. That
 * includes {@code HttpMessageNotReadableException}, raised by a syntactically malformed request body.
 *
 * <p>Authentication and authorisation failures are answered by the security filter chain, which runs
 * ahead of the {@code DispatcherServlet}; no exception from them reaches this class.
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
     * {@code backend/app/api/responses.py:L31,L65} and {@code backend/app/api/settings.py:L22}. The
     * two response-scoped literals stay distinct: the message is passed through, never mapped.
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
     * {@link ResponseGenerationException#FAILED_TO_GENERATE_RESPONSE}. This status is also produced by
     * {@link #handleUnexpectedException(Exception)}, which emits {@value #INTERNAL_SERVER_ERROR}. The
     * two 500 bodies are separate messages.
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
     * <p>Reached when a controller binds its body with {@code @Valid}. The tree declares exactly two
     * {@code @NotNull} constraints, corresponding to the inline guards at
     * {@code backend/app/api/responses.py:L40-41} and {@code backend/app/api/settings.py:L17-18}. The
     * name of the first rejected field selects the message from a closed two-entry map:
     *
     * <ul>
     *   <li>{@code tweetId} or {@code tweet_id} selects
     *       {@link BadRequestException#TWEET_ID_IS_REQUIRED}, the literal of
     *       {@code backend/app/api/responses.py:L41}</li>
     *   <li>{@code value} selects {@link BadRequestException#NO_VALUE_PROVIDED}, the literal of
     *       {@code backend/app/api/settings.py:L18}</li>
     * </ul>
     *
     * <p>Both messages are read from the constants declared on {@link BadRequestException} — the same
     * constants {@link #handleBadRequest(BadRequestException)} passes through.
     *
     * <p>An unmapped field name yields that field error's own
     * {@link FieldError#getDefaultMessage()}. A binding result carrying no field error at all yields
     * {@code {"error": null}}; no message is synthesised in either case.
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
     * Reports any exception no other handler matches with HTTP 500 and the message
     * {@value #INTERNAL_SERVER_ERROR}.
     *
     * <p>Reproduces {@code backend/app/main.py:L35-37}. The exception is written to the log at
     * {@code ERROR} with its stack trace; neither its type nor its message reaches the response body.
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
