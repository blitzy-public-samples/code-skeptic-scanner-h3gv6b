package com.codeskeptic.scanner.api;

import java.util.List;
import java.util.Map;
import java.util.Set;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.codeskeptic.scanner.config.RequestMediaTypeConfig;
import com.codeskeptic.scanner.dto.ErrorResponse;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.util.LogSafe;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates an exception raised while a request is being handled into the HTTP status code and the
 * response body the service returns.
 *
 * <p>Every response body is the single-key {@link ErrorResponse} envelope. The handlers preserve the
 * source route literals and the global {@code Not found} and {@code Internal server error} literals.
 * Framework request failures map to 400, 405, 406 or 415; response-write and unexpected failures map
 * to 500. Authentication failures are handled by the security chain, and servlet error dispatches
 * are rendered from the {@link ErrorAttributes} bean {@link #errorEnvelopeAttributes()} declares.
 *
 * <p>A failure answered by {@code HttpServletResponse.sendError(int)} reaches no handler declared
 * here; the container re-dispatches the request to the error page, where Spring Boot's own
 * {@code BasicErrorController} renders the attribute map that bean returns — at most the single key
 * {@value #ERROR_KEY}, carrying the same literals declared below — DL-183.
 *
 * <p>Every body produced here is an {@link ErrorResponse}: the single-key
 * {@code {"error": <string>}} envelope. The table names every handler this class declares and the
 * status and message each one puts on the wire.
 *
 * <table border="1">
 * <caption>Status and message emitted by each handler</caption>
 * <tr><th>Handler</th><th>Status</th><th>Message on the wire</th></tr>
 * <tr><td>{@link #handleNotFound(NotFoundException)}</td><td>404</td>
 *   <td>the exception's own message — one of the five per-route 404 literals</td></tr>
 * <tr><td>{@link #handleBadRequest(BadRequestException)}</td><td>400</td>
 *   <td>the exception's own message — one of the three per-route 400 literals</td></tr>
 * <tr><td>{@link #handleResponseGenerationFailure(ResponseGenerationException)}</td><td>500</td>
 *   <td>{@code Failed to generate response}</td></tr>
 * <tr><td>{@link #handleMethodArgumentNotValid(MethodArgumentNotValidException)}</td><td>400</td>
 *   <td>{@code Tweet ID is required}, {@code No value provided} or {@value #BAD_REQUEST}</td></tr>
 * <tr><td>{@link #handleNoHandlerFound()}</td><td>404</td><td>{@value #NOT_FOUND}</td></tr>
 * <tr><td>{@link #handleClientRequestFailure(Exception)}</td><td>400</td>
 *   <td>{@value #BAD_REQUEST}</td></tr>
 * <tr><td>{@link #handleMessageConversionFailure(HttpMessageConversionException)}</td><td>400</td>
 *   <td>{@value #BAD_REQUEST}</td></tr>
 * <tr><td>{@link #handleMethodNotSupported(HttpRequestMethodNotSupportedException)}</td><td>405</td>
 *   <td>{@value #METHOD_NOT_ALLOWED}, with an {@code Allow} header</td></tr>
 * <tr><td>{@link #handleNotAcceptable(HttpMediaTypeNotAcceptableException)}</td><td>406</td>
 *   <td>{@value #NOT_ACCEPTABLE}</td></tr>
 * <tr><td>{@link #handleUnsupportedMediaType(HttpMediaTypeNotSupportedException)}</td><td>415</td>
 *   <td>{@value #UNSUPPORTED_MEDIA_TYPE}</td></tr>
 * <tr><td>{@link #handleMultipartFailure(MultipartException)}</td><td>415</td>
 *   <td>{@value #UNSUPPORTED_MEDIA_TYPE}</td></tr>
 * <tr><td>{@link #handleIllegalArgument(IllegalArgumentException, HttpServletRequest)}</td>
 *   <td>415, or 500</td>
 *   <td>{@value #UNSUPPORTED_MEDIA_TYPE} for a request media type that names no concrete type,
 *       otherwise {@value #INTERNAL_SERVER_ERROR}</td></tr>
 * <tr><td>{@link #handleResponseWriteFailure(HttpMessageNotWritableException)}</td><td>500</td>
 *   <td>{@value #INTERNAL_SERVER_ERROR}</td></tr>
 * <tr><td>{@link #handleUnexpectedException(Exception)}</td><td>500</td>
 *   <td>{@value #INTERNAL_SERVER_ERROR}</td></tr>
 * </table>
 *
 * <p>This class also declares the body of the servlet {@code ERROR} dispatch. The error path
 * {@code server.error.path} names — {@code /error} by default — stays mapped to Spring Boot's own
 * {@code BasicErrorController}, and {@link #errorEnvelopeAttributes()} replaces the attribute source
 * it renders, so a JSON error dispatch produces the same single-key envelope as every row above —
 * DL-183. A dispatched 401 or 403 yields no attribute; 404, 405, 406 and 415 each yield their literal
 * from the table; any other 4xx yields {@value #BAD_REQUEST}; every other recorded status yields
 * {@value #INTERNAL_SERVER_ERROR}. {@link #errorDispatchStatusFor(int)} and
 * {@link #errorDispatchMessageFor(int)} publish the same status and literal selection to
 * {@code com.codeskeptic.scanner.config.ContainerErrorResponseConfig}, which answers a failure the
 * container reports before any filter runs — DL-237.
 *
 * <p>The three {@code com.codeskeptic.scanner.exception} types declare no {@code @ResponseStatus};
 * their status is assigned here. Their messages are copied through {@link Throwable#getMessage()}
 * unaltered; each of the eight per-route literals round-trips character-for-character.
 *
 * <p>A client failure Spring MVC raises — a malformed or unbindable body, a missing or unconvertible
 * request value, an unsupported method, an unsupported media type or an unsatisfiable {@code Accept}
 * header — is matched by one of the framework handlers and keeps the status the framework assigns
 * it; {@link #handleUnexpectedException(Exception)} receives what no earlier handler matches — DL-092,
 * DL-188.
 *
 * <p>Two request media-type failures the framework raises outside its own hierarchy are answered 415
 * here as well: a multipart request, which no route of this service consumes, and a
 * {@code Content-Type} naming a non-concrete media type such as {@code application/*} — DL-234,
 * DL-235.
 *
 * <p>{@link HttpMessageNotReadableException}, {@link HttpMessageNotWritableException} and their
 * {@link HttpMessageConversionException} supertype are each declared on a handler of their own: a
 * request the converter could not read answers 400, a response it could not write answers 500 —
 * DL-188.
 *
 * <p>Authentication and authorisation failures are answered by the security filter chain, which runs
 * ahead of the {@code DispatcherServlet}; no exception from them reaches this class. A request the
 * chain's firewall rejects is answered with {@code sendError} and reaches the {@code ERROR} dispatch
 * instead, where {@link #errorEnvelopeAttributes()} supplies the body.
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

    /** JSON key of the single-key envelope declared by {@code dto.ErrorResponse}. */
    private static final String ERROR_KEY = "error";

    /**
     * Messages keyed by the status the container recorded, for the four statuses of the
     * {@code ERROR} dispatch that carry a message of their own.
     */
    private static final Map<Integer, String> ERROR_DISPATCH_MESSAGES = Map.of(
            HttpStatus.NOT_FOUND.value(), NOT_FOUND,
            HttpStatus.METHOD_NOT_ALLOWED.value(), METHOD_NOT_ALLOWED,
            HttpStatus.NOT_ACCEPTABLE.value(), NOT_ACCEPTABLE,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), UNSUPPORTED_MEDIA_TYPE);

    // Ported from backend/app/main.py:L31-37 (faithful port) — see docs/DECISION_LOG.md DL-092
    /**
     * Publishes the error-attribute source that renders this class's envelope on the servlet
     * {@code ERROR} dispatch.
     *
     * <p>The handler methods below translate every exception that reaches the
     * {@code DispatcherServlet} during a {@code REQUEST} dispatch. A failure answered with
     * {@code HttpServletResponse.sendError(int)} — which Spring Security's request firewall issues
     * for a rejected path such as {@code //tweets} — unwinds that dispatch and asks the container to
     * re-dispatch to the error page. This bean supplies the attributes rendered on that second
     * dispatch, so both dispatches put the same single-key envelope on the wire and neither carries a
     * request path, a timestamp or an exception detail.
     *
     * <p>{@code DefaultErrorAttributes} is declared only while the context holds no
     * {@link ErrorAttributes} bean, so this bean replaces it while Spring Boot's own
     * {@code BasicErrorController} stays the handler mapped to the error path — DL-183.
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
     * <p>Carries the four literals of {@code backend/app/api/tweets.py:L32,L43},
     * {@code backend/app/api/responses.py:L31,L65} and {@code backend/app/api/settings.py:L22}; the
     * message is passed through unmapped, and the two response-scoped literals stay distinct.
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
     * <p>The wire outcome is recorded at {@code WARN}, naming the cause's class only. This advice writes
     * no {@code ERROR} record; the layer that raised the failure writes the single one —
     * {@code service.LlmService} for a provider failure, {@code service.ResponseService} for a
     * repository or transaction failure — DL-252. No stack trace, no cause message and no stored value
     * is written.
     *
     * @param ex the raised exception; its {@link Throwable#getMessage()} becomes the response body
     * @return HTTP 500 carrying {@code {"error": <ex.getMessage()>}}
     */
    @ExceptionHandler(ResponseGenerationException.class)
    public ResponseEntity<ErrorResponse> handleResponseGenerationFailure(ResponseGenerationException ex) {
        // The public outcome is recorded once here; the ERROR owner sits upstream — DL-252 — see
        // docs/DECISION_LOG.md
        log.warn("Responding HTTP 500 with the generation literal; cause {}",
                ex.getCause() == null ? LogSafe.type(ex) : LogSafe.type(ex.getCause()));

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
     * <p>An unmapped field name and a binding result carrying no field error both yield
     * {@value #BAD_REQUEST}, so this handler emits one of exactly three messages and no framework
     * text, no annotation text and no field name reaches the wire.
     *
     * @param ex the raised exception, whose binding result supplies the rejected field names
     * @return HTTP 400 carrying the single-key error envelope
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message =
                fieldErrors.isEmpty() ? BAD_REQUEST : validationMessageFor(fieldErrors.get(0));
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
     * <p>A request body repeating a member is one such unreadable body: with
     * {@code spring.jackson.parser.strict-duplicate-detection} set to {@code true}, the parser reports
     * {@code {"value":"first","value":"second"}} on {@code PUT /settings/{key}} and
     * {@code {"username":"admin","password":"a","password":"b"}} on {@code POST /auth/token} as
     * {@link HttpMessageNotReadableException}, and each answers 400 here — DL-188.
     *
     * <p>The eight per-route 400 literals are carried by {@link BadRequestException} and answered by
     * {@link #handleBadRequest(BadRequestException)} — DL-092.
     *
     * @param ex the raised exception; its message reaches neither the response body nor the log
     * @return HTTP 400 carrying {@code {"error": "Bad request"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MissingServletRequestPartException.class, TypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleClientRequestFailure(Exception ex) {
        log.debug("Rejecting a malformed request with HTTP 400: {}", LogSafe.type(ex));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(BAD_REQUEST));
    }

    /**
     * Reports a request body the converter could not bind onto the handler's parameter type with HTTP
     * 400 and the message {@value #BAD_REQUEST}.
     *
     * <p>This method receives the {@link HttpMessageConversionException} supertype alone;
     * {@link HttpMessageNotReadableException} and {@link HttpMessageNotWritableException} are each
     * matched by a handler declaring them directly. {@code AbstractJackson2HttpMessageConverter}
     * raises the supertype for every
     * {@code com.fasterxml.jackson.databind.exc.InvalidDefinitionException} the binding of a request
     * body produces, which includes a JSON object repeating a member that binds to a record
     * component: {@code {"value":"first","value":"second"}} on {@code PUT /settings/{key}} and
     * {@code {"username":"admin","password":"a","password":"b"}} on {@code POST /auth/token} each
     * reach this method — DL-188.
     *
     * <p>The status and the message are the ones {@link #handleClientRequestFailure(Exception)}
     * serves.
     *
     * <p>Only the exception's class name is written to the log, at {@code WARN}. Neither the class
     * name, the detail message nor any part of the request body reaches the response — DL-052,
     * DL-197.
     *
     * @param ex the raised exception; its message reaches neither the response body nor the log
     * @return HTTP 400 carrying {@code {"error": "Bad request"}}
     */
    // Net-new (no Python counterpart) — DL-188, DL-197 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMessageConversionException.class)
    public ResponseEntity<ErrorResponse> handleMessageConversionFailure(
            HttpMessageConversionException ex) {

        // The message of a binding failure quotes the request body — see docs/DECISION_LOG.md DL-197
        log.warn("Rejecting a request body the converter could not bind with HTTP 400: {}; detail {}",
                LogSafe.type(ex), LogSafe.correlation(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(BAD_REQUEST));
    }

    /**
     * Reports a response body the converter could not write with HTTP 500 and the message
     * {@value #INTERNAL_SERVER_ERROR}.
     *
     * <p>{@link HttpMessageNotWritableException} is raised after a handler has returned, while its
     * return value is being serialised. It is answered with the status and the message
     * {@link #handleUnexpectedException(Exception)} serves — DL-188.
     *
     * <p>Only the exception's class name is written to the log, at {@code ERROR}; its message may
     * carry response-field detail and stays out of the log, and neither its type nor its message
     * reaches the response body — DL-052.
     *
     * @param ex the raised exception, whose class name is recorded in the log
     * @return HTTP 500 carrying {@code {"error": "Internal server error"}}
     */
    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ErrorResponse> handleResponseWriteFailure(
            HttpMessageNotWritableException ex) {

        log.error("A response body could not be written; responding HTTP 500: {}",
                ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(INTERNAL_SERVER_ERROR));
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
     * @param ex the raised exception; its message reaches neither the response body nor the log
     * @return HTTP 415 carrying {@code {"error": "Unsupported media type"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {

        log.debug("Rejecting an unsupported media type with HTTP 415");
        return unsupportedMediaType();
    }

    /**
     * Reports a request the multipart resolver could not parse with HTTP 415 and the message
     * {@value #UNSUPPORTED_MEDIA_TYPE}.
     *
     * <p>No route in this service consumes a multipart body: the tree declares no
     * {@code @RequestPart} parameter, no {@code MultipartFile} parameter and no {@code multipart}
     * entry in any {@code consumes} attribute. The status is the one
     * {@link #handleUnsupportedMediaType(HttpMediaTypeNotSupportedException)} serves — DL-234.
     *
     * <p>{@code DispatcherServlet.checkMultipart} resolves a multipart request before handler
     * mapping, so this failure arrives with no handler method attached; a media type such as
     * {@code multipart/form-data} carrying no {@code boundary} parameter and
     * {@code multipart/mixed} both reach this method. A missing multipart part is a different
     * failure: {@link MissingServletRequestPartException} extends
     * {@link ServletRequestBindingException} and is answered 400 by
     * {@link #handleClientRequestFailure(Exception)} — DL-234.
     *
     * <p>Only the exception's class name is written to the log, at {@code WARN}. Neither the class
     * name, the detail message nor any part of the request reaches the response — DL-052.
     *
     * @param ex the raised exception, whose class name is recorded in the log
     * @return HTTP 415 carrying {@code {"error": "Unsupported media type"}}
     */
    // Net-new (no Python counterpart) — DL-092, DL-234 — see docs/DECISION_LOG.md
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartFailure(MultipartException ex) {
        log.warn("Rejecting a multipart request this service does not consume with HTTP 415: {}",
                LogSafe.type(ex));
        return unsupportedMediaType();
    }

    /**
     * Reports a request whose {@code Content-Type} names no concrete media type with HTTP 415 and the
     * message {@value #UNSUPPORTED_MEDIA_TYPE}, and any other
     * {@link IllegalArgumentException} exactly as {@link #handleUnexpectedException(Exception)} does.
     *
     * <p>{@code HttpHeaders.setContentType} answers a wildcard type and a wildcard subtype with
     * {@link IllegalArgumentException}, and {@code ServletServerHttpRequest.getHeaders} calls it while
     * a message converter reads the request body, so a {@code Content-Type} of {@code *&#47;*},
     * {@code application/*} or {@code text/*} raises that exception during argument resolution. A
     * {@code Content-Type} the media-type parser rejects outright raises
     * {@code InvalidMediaTypeException}, which the converter translates into
     * {@link HttpMediaTypeNotSupportedException} — DL-235.
     *
     * <p>The status is selected from the request, and not from the exception: 415 when the request
     * carries a {@code Content-Type} that parses to a non-concrete media type or that cannot be
     * parsed at all, and otherwise the unchanged 500 of
     * {@link #handleUnexpectedException(Exception)}, stack trace included — DL-235.
     *
     * @param ex the raised exception, recorded in the log
     * @param request the request being handled, read only for its {@code Content-Type} header
     * @return HTTP 415 carrying {@code {"error": "Unsupported media type"}} for a non-concrete
     *     request media type, otherwise HTTP 500 carrying
     *     {@code {"error": "Internal server error"}}
     */
    // Net-new (no Python counterpart) — DL-235 — see docs/DECISION_LOG.md
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
            HttpServletRequest request) {

        if (namesNoConcreteMediaType(request)) {
            log.warn("Rejecting a request whose Content-Type names no concrete media type with "
                    + "HTTP 415: {}", LogSafe.type(ex));
            return unsupportedMediaType();
        }
        return handleUnexpectedException(ex);
    }

    /**
     * Reports a request whose {@code Accept} header no handler can satisfy with HTTP 406 and the
     * message {@value #NOT_ACCEPTABLE}.
     *
     * <p>See docs/DECISION_LOG.md DL-092.
     *
     * @param ex the raised exception; its message reaches neither the response body nor the log
     * @return HTTP 406 carrying {@code {"error": "Not acceptable"}}
     */
    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.debug("Rejecting a request whose Accept header cannot be satisfied with HTTP 406");
        // The envelope is written as JSON irrespective of the unsatisfiable Accept header - DL-092 -
        // see docs/DECISION_LOG.md
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(NOT_ACCEPTABLE));
    }

    /**
     * Reports any exception no other handler matches with HTTP 500 and the message
     * {@value #INTERNAL_SERVER_ERROR}.
     *
     * <p>Reproduces {@code backend/app/main.py:L35-37}: every exception no earlier handler matches is
     * answered here. Neither the exception's type nor its message reaches the response body.
     *
     * <p>The {@code ERROR} record carries three pieces of metadata this application derives and
     * nothing the failure itself wrote: the bounded type chain of
     * {@code util.LogSafe.typeChain(Throwable)}, the frame of
     * {@code util.LogSafe.originFrame(Throwable)}, and a correlation token over those two — so two
     * occurrences of one defect share a token while no message, no cause text and no stack reaches the
     * record — DL-197 — see docs/DECISION_LOG.md.
     *
     * <p>The sanitized detail of {@code util.LogSafe.failureDetail(Throwable)} — every message in the
     * chain guarded and bounded, plus a bounded number of frames — is written at {@code DEBUG} only.
     * It is reachable where {@code logging.level.com.codeskeptic.scanner} is set to {@code DEBUG}, and
     * is absent at the declared default of {@code INFO} — DL-197.
     *
     * @param ex the raised exception, reported as bounded metadata
     * @return HTTP 500 carrying {@code {"error": "Internal server error"}}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        String types = LogSafe.typeChain(ex);
        String origin = LogSafe.originFrame(ex);
        log.error("Unhandled exception reached the error-handling advice; responding HTTP 500. "
                + "Failure {}, raised at {}, correlation {}",
                types, origin, LogSafe.correlation(types + '|' + origin));
        if (log.isDebugEnabled()) {
            log.debug("Sanitized detail of the unhandled failure: {}", LogSafe.failureDetail(ex));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(INTERNAL_SERVER_ERROR));
    }

    /**
     * Builds the response every 415 branch of this advice serves.
     *
     * @return HTTP 415 carrying {@code {"error": "Unsupported media type"}}; never {@code null}
     */
    private static ResponseEntity<ErrorResponse> unsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse(UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * Reports whether a request carries a {@code Content-Type} that names no single concrete media
     * type.
     *
     * <p>The question is decided by {@code config.RequestMediaTypeConfig}, the single declaration the
     * request filter reads as well. A request carrying no {@code Content-Type} at all is not one: an
     * absent header is answered by
     * {@link #handleUnsupportedMediaType(HttpMediaTypeNotSupportedException)}, which the framework
     * raises for it.
     *
     * @param request the request being handled, possibly {@code null}
     * @return {@code true} when the header holds a media type with a wildcard type or subtype, or a
     *     value the media-type parser rejects; {@code false} when the header is absent, blank, or
     *     names one concrete media type
     */
    private static boolean namesNoConcreteMediaType(HttpServletRequest request) {
        return request != null
                && RequestMediaTypeConfig.namesNoConcreteMediaType(request.getContentType());
    }

    /**
     * Resolves the wire message for a single rejected field.
     *
     * @param fieldError the rejected field reported by Bean Validation
     * @return {@link BadRequestException#TWEET_ID_IS_REQUIRED} or
     *         {@link BadRequestException#NO_VALUE_PROVIDED} for a mapped field name, otherwise
     *         {@value #BAD_REQUEST}; never {@code null} and never the field error's own message
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
        return BAD_REQUEST;
    }

    // The one declaration of the container-level error envelope — DL-183, DL-237 — see
    // docs/DECISION_LOG.md
    /**
     * Resolves the status this service reports for a status the container recorded.
     *
     * <p>Declared here beside {@link #errorDispatchMessageFor(int)} so the container-level error
     * surface has a single declaration: the {@link ErrorAttributes} bean below reads both, and so does
     * the error-report valve {@code config/ContainerErrorResponseConfig} installs for a rejection the
     * container answers before any filter or servlet runs — DL-237.
     *
     * @param status the status the container recorded
     * @return {@code status} itself when it is a 4xx status, otherwise
     *         {@link HttpStatus#INTERNAL_SERVER_ERROR}'s value
     */
    public static int errorDispatchStatusFor(int status) {
        return isClientErrorStatus(status) ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * Reports whether a status is a 4xx status.
     *
     * @param status the status to classify
     * @return {@code true} when {@code status} lies in the 4xx range
     */
    private static boolean isClientErrorStatus(int status) {
        return status >= HttpStatus.BAD_REQUEST.value()
                && status < HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    // The one declaration of the container-level error envelope — DL-183, DL-237 — see
    // docs/DECISION_LOG.md
    /**
     * Resolves the wire message for a status the container recorded on an {@code ERROR} dispatch.
     *
     * @param status the recorded status
     * @return the message to carry under {@value #ERROR_KEY}, or {@code null} when the dispatched
     *         status carries no body
     */
    public static String errorDispatchMessageFor(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return null;
        }
        String mapped = ERROR_DISPATCH_MESSAGES.get(status);
        if (mapped != null) {
            return mapped;
        }
        return isClientErrorStatus(status) ? BAD_REQUEST : INTERNAL_SERVER_ERROR;
    }

    /**
     * Renders the single-key error envelope for the servlet {@code ERROR} dispatch.
     *
     * <p>Every attribute the framework default contributes — {@code timestamp}, {@code status},
     * {@code error}, {@code path}, {@code exception}, {@code message}, {@code trace} and
     * {@code errors} — is replaced by the one key {@value GlobalExceptionHandler#ERROR_KEY} carrying
     * one of the six literals this class declares, selected by
     * {@link GlobalExceptionHandler#errorDispatchMessageFor(int)}. A dispatched 401 or 403 yields an
     * empty map, which carries no literal.
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
            String message = errorDispatchMessageFor(status);
            log.debug("Rendering the error envelope for a dispatched status of {}", status);
            return (message == null) ? Map.of() : Map.of(ERROR_KEY, message);
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
            if (recorded instanceof Integer value) {
                return value;
            }
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }
}
