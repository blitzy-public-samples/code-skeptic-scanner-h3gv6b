package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeskeptic.scanner.dto.ErrorResponse;
import com.codeskeptic.scanner.dto.LoginRequest;
import com.codeskeptic.scanner.dto.UpdateSettingRequest;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.RequestDispatcher;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;

// Ported from backend/app/main.py:L31-37 (faithful port) — see docs/DECISION_LOG.md
class GlobalExceptionHandlerTest {

    private static final String NOT_FOUND = "Not found";

    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private static final String TWEET_NOT_FOUND = "Tweet not found";

    private static final String RESPONSE_NOT_FOUND = "Response not found";

    private static final String RESPONSE_NOT_FOUND_OR_UPDATE_FAILED = "Response not found or update failed";

    private static final String SETTING_NOT_FOUND = "Setting not found";

    private static final String TWEET_ID_IS_REQUIRED = "Tweet ID is required";

    private static final String UPDATE_DATA_IS_REQUIRED = "Update data is required";

    private static final String NO_VALUE_PROVIDED = "No value provided";

    private static final String FAILED_TO_GENERATE_RESPONSE = "Failed to generate response";

    private static final String ERROR_KEY = "error";

    private static final String CAUSE_MESSAGE = "boom";

    /**
     * A value distinctive enough that finding it anywhere in a log record proves a credential reached
     * the log — see docs/DECISION_LOG.md DL-197.
     */
    private static final String SUBMITTED_CREDENTIAL = "Zq7-distinctive-credential-value-4711";

    private static final List<String> KEYS_ABSENT_FROM_EVERY_BODY = List.of(
            "type", "title", "status", "detail", "instance", "timestamp", "path", "message", "errors");

    private static final Set<Integer> STATUS_CODES_THIS_ADVICE_EMITS =
            Set.of(400, 404, 405, 406, 415, 500);

    /** The only two literals the advice puts on the wire outside a route's own message — DL-092. */
    private static final Set<String> GLOBAL_WIRE_LITERALS =
            Set.of("Not found", "Internal server error");


    private static final int UNAUTHORIZED = 401;

    private static final int FORBIDDEN = 403;

    private static final MethodParameter VALIDATION_TARGET_PARAMETER = validationTargetParameter();

    private GlobalExceptionHandler handler;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("the exception types declare the eight per-route message literals unchanged")
    void exceptionTypesDeclareThePerRouteMessageLiterals() {
        assertThat(NotFoundException.TWEET_NOT_FOUND).isEqualTo(TWEET_NOT_FOUND);
        assertThat(NotFoundException.RESPONSE_NOT_FOUND).isEqualTo(RESPONSE_NOT_FOUND);
        assertThat(NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED)
                .isEqualTo(RESPONSE_NOT_FOUND_OR_UPDATE_FAILED);
        assertThat(NotFoundException.SETTING_NOT_FOUND).isEqualTo(SETTING_NOT_FOUND);
        assertThat(BadRequestException.TWEET_ID_IS_REQUIRED).isEqualTo(TWEET_ID_IS_REQUIRED);
        assertThat(BadRequestException.UPDATE_DATA_IS_REQUIRED).isEqualTo(UPDATE_DATA_IS_REQUIRED);
        assertThat(BadRequestException.NO_VALUE_PROVIDED).isEqualTo(NO_VALUE_PROVIDED);
        assertThat(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE)
                .isEqualTo(FAILED_TO_GENERATE_RESPONSE);
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("notFoundCases")
    @DisplayName("returns 404 carrying the message of the reported NotFoundException")
    void returns404WithTheNotFoundExceptionMessage(NotFoundException reported, String expectedMessage)
            throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(reported);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertErrorEnvelope(response, expectedMessage);
    }

    private static Stream<Arguments> notFoundCases() {
        return Stream.of(
                Arguments.of(NotFoundException.tweetNotFound(), TWEET_NOT_FOUND),
                Arguments.of(NotFoundException.responseNotFound(), RESPONSE_NOT_FOUND),
                Arguments.of(
                        NotFoundException.responseNotFoundOrUpdateFailed(),
                        RESPONSE_NOT_FOUND_OR_UPDATE_FAILED),
                Arguments.of(NotFoundException.settingNotFound(), SETTING_NOT_FOUND));
    }

    @Test
    @DisplayName("keeps the two response-scoped 404 messages distinct at the same status")
    void keepsTheTwoResponseScoped404MessagesDistinct() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> readBranch =
                handler.handleNotFound(NotFoundException.responseNotFound());
        ResponseEntity<ErrorResponse> updateBranch =
                handler.handleNotFound(NotFoundException.responseNotFoundOrUpdateFailed());

        assertThat(readBranch.getStatusCode().value()).isEqualTo(404);
        assertThat(updateBranch.getStatusCode().value()).isEqualTo(404);
        assertErrorEnvelope(readBranch, RESPONSE_NOT_FOUND);
        assertErrorEnvelope(updateBranch, RESPONSE_NOT_FOUND_OR_UPDATE_FAILED);

        assertThat(readBranch.getBody().error()).isNotEqualTo(updateBranch.getBody().error());
        assertThat(readBranch.getBody().error()).isEqualTo("Response not found");
        assertThat(updateBranch.getBody().error()).isEqualTo("Response not found or update failed");
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("badRequestCases")
    @DisplayName("returns 400 carrying the message of the reported BadRequestException")
    void returns400WithTheBadRequestExceptionMessage(BadRequestException reported, String expectedMessage)
            throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(reported);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, expectedMessage);
    }

    private static Stream<Arguments> badRequestCases() {
        return Stream.of(
                Arguments.of(BadRequestException.tweetIdRequired(), TWEET_ID_IS_REQUIRED),
                Arguments.of(BadRequestException.updateDataRequired(), UPDATE_DATA_IS_REQUIRED),
                Arguments.of(BadRequestException.noValueProvided(), NO_VALUE_PROVIDED));
    }

    @Test
    @DisplayName("returns 500 carrying Failed to generate response and not Internal server error")
    void returns500WithTheResponseGenerationMessage() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleResponseGenerationFailure(new ResponseGenerationException());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, FAILED_TO_GENERATE_RESPONSE);
        assertThat(response.getBody().error()).isEqualTo("Failed to generate response");
        assertThat(response.getBody().error()).isNotEqualTo(INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("returns 500 with Failed to generate response and omits the cause message")
    void returns500WithTheResponseGenerationMessageAndOmitsTheCause() throws JsonProcessingException {
        ResponseGenerationException reported =
                new ResponseGenerationException(new IllegalStateException(CAUSE_MESSAGE));

        ResponseEntity<ErrorResponse> response = handler.handleResponseGenerationFailure(reported);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, FAILED_TO_GENERATE_RESPONSE);
        assertThat(response.getBody().error()).doesNotContain(CAUSE_MESSAGE);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    // The public outcome is recorded once; the ERROR owner sits upstream — DL-084, DL-197 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("records the generation outcome once at WARN, naming the cause class only")
    void recordsTheGenerationOutcomeOnceAtWarnNamingTheCauseClassOnly() {
        ResponseGenerationException reported =
                new ResponseGenerationException(new IllegalStateException(CAUSE_MESSAGE));

        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleResponseGenerationFailure(reported);

            List<ILoggingEvent> warnings = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            ILoggingEvent warning = warnings.get(0);

            assertThat(warning.getFormattedMessage())
                    .contains("Responding HTTP 500 with the generation literal")
                    .contains("IllegalStateException");
            assertThat(warning.getFormattedMessage()).doesNotContain(CAUSE_MESSAGE);
            assertThat(warning.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // The public outcome is recorded once; the ERROR owner sits upstream — DL-084, DL-197 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("records no ERROR for a generation failure the failing layer already reported")
    void recordsNoErrorForAGenerationFailureTheFailingLayerAlreadyReported() {
        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleResponseGenerationFailure(new ResponseGenerationException());

            assertThat(recorded.list).noneMatch(event -> event.getLevel() == Level.ERROR);
            assertThat(recorded.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .hasSize(1)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("Responding HTTP 500 with the generation literal")
                            .contains("ResponseGenerationException"));
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
        "tweetId, Tweet ID is required",
        "tweet_id, Tweet ID is required",
        "value, No value provided"
    })
    @DisplayName("returns 400 with the literal the rejected field name maps to")
    void returns400WithTheLiteralTheRejectedFieldMapsTo(String rejectedField, String expectedMessage)
            throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureOn(rejectedField));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, expectedMessage);
    }

    // Only the two literals of backend/app/api/responses.py:L41 and settings.py:L18 reach the wire —
    // DL-092 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"somethingElse", "content"})
    @DisplayName("returns 400 with no body when the rejected field name is outside the map")
    void returns400WithNoBodyForAFieldOutsideTheMap(String rejectedField) {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureOn(rejectedField));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).as("body of an unmapped validation failure").isNull();
    }

    @Test
    @DisplayName("returns 400 with no body when the binding result carries no field error")
    void returns400WithNoBodyWhenNoFieldErrorIsPresent() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).as("body of a fieldless validation failure").isNull();
    }

    @Test
    @DisplayName("emits one of exactly two messages, or none, for a body that failed validation")
    void emitsOneOfExactlyTwoMessagesForABodyThatFailedValidation() {
        List<ResponseEntity<ErrorResponse>> responses = List.of(
                handler.handleMethodArgumentNotValid(validationFailureOn("tweetId")),
                handler.handleMethodArgumentNotValid(validationFailureOn("tweet_id")),
                handler.handleMethodArgumentNotValid(validationFailureOn("value")),
                handler.handleMethodArgumentNotValid(validationFailureOn("somethingElse")),
                handler.handleMethodArgumentNotValid(validationFailureOn("content")),
                handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors()));

        assertThat(responses)
                .allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(400))
                .allSatisfy(response -> assertThat(response.getBody() == null
                        ? null : response.getBody().error())
                        .isIn(TWEET_ID_IS_REQUIRED, NO_VALUE_PROVIDED, null));
    }

    @Test
    @DisplayName("returns 404 with the Not found envelope when no handler matches the path")
    void returns404WithNotFoundEnvelopeForNoHandlerFound() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleNoHandlerFound();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertErrorEnvelope(response, NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("Not found");
    }

    @Test
    @DisplayName("routes NoHandlerFoundException and NoResourceFoundException to one method and one body")
    void routesBothUnmatchedPathTypesToOneMethodAndOneBody() throws JsonProcessingException {
        NoHandlerFoundException noHandlerFound =
                new NoHandlerFoundException("GET", "/does-not-exist", new HttpHeaders());
        NoResourceFoundException noResourceFound =
                new NoResourceFoundException(HttpMethod.GET, "/does-not-exist");

        assertThat(handledExceptionTypes())
                .contains(NoHandlerFoundException.class, NoResourceFoundException.class);
        assertThat(handlerMethodFor(noHandlerFound.getClass()))
                .isEqualTo(handlerMethodFor(noResourceFound.getClass()));

        ResponseEntity<ErrorResponse> first = handler.handleNoHandlerFound();
        ResponseEntity<ErrorResponse> second = handler.handleNoHandlerFound();

        assertThat(first.getStatusCode().value()).isEqualTo(404);
        assertThat(second.getStatusCode().value()).isEqualTo(404);
        assertThat(first.getBody()).isEqualTo(second.getBody());
        assertErrorEnvelope(first, NOT_FOUND);
        assertErrorEnvelope(second, NOT_FOUND);
        assertThat(envelopeOf(first)).isEqualTo(envelopeOf(second));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unexpectedExceptionCases")
    @DisplayName("returns 500 with the Internal server error envelope and omits the exception message")
    void returns500WithInternalServerErrorEnvelope(Exception reported) throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(reported);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("Internal server error");
        assertThat(response.getBody().error()).doesNotContain(CAUSE_MESSAGE);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    private static Stream<Arguments> unexpectedExceptionCases() {
        return Stream.of(
                Arguments.of(new RuntimeException(CAUSE_MESSAGE)),
                Arguments.of(new Exception(CAUSE_MESSAGE)));
    }

    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("frameworkRequestFailures")
    @DisplayName("answers a framework request failure with the framework's own status and no body")
    void answersAFrameworkRequestFailureWithItsOwnStatusAndNoBody(Exception reported,
            int expectedStatus, String description) {

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(reported);

        assertThat(response.getStatusCode().value()).as(description).isEqualTo(expectedStatus);
        assertThat(response.getBody()).as("body of %s", description).isNull();
    }

    private static Stream<Arguments> frameworkRequestFailures() {
        return Stream.of(
                Arguments.of(malformedBody(), 400, "a syntactically malformed request body"),
                Arguments.of(new HttpMessageConversionException(CAUSE_MESSAGE), 400,
                        "a body the converter could not turn into the target record"),
                Arguments.of(missingParameter(), 400, "a missing query parameter"),
                Arguments.of(new MissingServletRequestPartException("part"), 400,
                        "a missing multipart part"),
                Arguments.of(methodNotSupported(), 405, "a request method no route supports"),
                Arguments.of(unsupportedMediaType(), 415,
                        "a request body whose media type no handler consumes"),
                Arguments.of(new HttpMediaTypeNotAcceptableException("none"), 406,
                        "an Accept header no handler can satisfy"));
    }

    // Every other failure is a defect and reads as 500 — DL-092 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("nonFrameworkFailures")
    @DisplayName("answers a failure that is not a framework request failure with 500 and the "
            + "Internal server error envelope")
    void answersANonFrameworkFailureWith500(Exception reported, String description)
            throws JsonProcessingException {

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(reported);

        assertThat(response.getStatusCode().value()).as(description).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    private static Stream<Arguments> nonFrameworkFailures() {
        return Stream.of(
                Arguments.of(new HttpMessageNotWritableException(CAUSE_MESSAGE),
                        "a response the converter could not write"),
                Arguments.of(new TypeMismatchException(CAUSE_MESSAGE, Integer.class),
                        "a value the target type cannot hold"),
                Arguments.of(new MultipartException(CAUSE_MESSAGE), "a multipart parse failure"),
                Arguments.of(new IllegalArgumentException(CAUSE_MESSAGE), "an illegal argument"),
                Arguments.of(new InvalidMediaTypeException("not a media type", "no slash"),
                        "a Content-Type that names no media type"),
                Arguments.of(new IllegalStateException(CAUSE_MESSAGE), "a defect in our own code"));
    }

    // The single decision point that separates the two paths — DL-092 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("routes every framework request failure and every defect to the one catch-all "
            + "handler")
    void routesEveryFailureToTheOneCatchAllHandler() {
        ExceptionHandlerMethodResolver resolver = resolverForTheAdvice();
        Method catchAll = handlerMethodFor(Exception.class);

        for (Exception failure : List.of(malformedBody(), missingParameter(),
                new MissingServletRequestPartException("part"), methodNotSupported(),
                unsupportedMediaType(), new HttpMediaTypeNotAcceptableException("none"),
                new HttpMessageConversionException(CAUSE_MESSAGE),
                new HttpMessageNotWritableException(CAUSE_MESSAGE),
                new MultipartException(CAUSE_MESSAGE), new IllegalArgumentException(CAUSE_MESSAGE),
                new IllegalStateException(CAUSE_MESSAGE))) {

            assertThat(resolver.resolveMethod(failure))
                    .as("handler resolved for %s", failure.getClass().getSimpleName())
                    .isEqualTo(catchAll);
        }
    }

    // Bodies the converter rejects reach the wire as a bodiless 400 or a 500 — DL-092 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesRepeatingARecordComponent")
    @DisplayName("answers a body repeating a record component without echoing any submitted value")
    void answersABodyRepeatingARecordComponentWithoutEchoingIt(Class<?> targetType, String body) {
        HttpMessageConversionException raised = conversionFailureReadingBody(defaultConverter(),
                targetType, body);

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(raised);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).as("body of a rejected request body").isNull();
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Unexpected token at [Source: (String)\"{\"a\":\"\r\nWARN forged log record\"}\"]",
        "cannot deserialize\nINFO admin logged in",
        "bad body \u0000 with a null byte",
        "JSON parse error: password=hunter2",
    })
    @DisplayName("writes no part of a conversion failure's message to the log")
    void writesNoPartOfAConversionFailuresMessageToTheLog(String attackerControlledMessage) {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        List<ILoggingEvent> events = new ArrayList<>();
        try {
            handler.handleUnexpectedException(
                    new HttpMessageNotReadableException(attackerControlledMessage,
                            new MockHttpInputMessage(new byte[0])));
            events.addAll(appender.list);
        } finally {
            detachAdviceAppender(appender);
        }

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage())
                .doesNotContain(attackerControlledMessage)
                .doesNotContain("forged log record")
                .doesNotContain("admin logged in")
                .doesNotContain("hunter2")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain("\u0000");
        assertThat(event.getArgumentArray())
                .noneSatisfy(argument -> assertThat(String.valueOf(argument))
                        .contains(attackerControlledMessage));
        assertThat(event.getThrowableProxy()).isNull();
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("names the exception class in the conversion-failure log record")
    void namesTheExceptionClassInTheConversionFailureLogRecord() {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        String formatted;
        try {
            handler.handleUnexpectedException(
                    new HttpMessageNotReadableException("body the caller sent",
                            new MockHttpInputMessage(new byte[0])));
            formatted = appender.list.get(0).getFormattedMessage();
        } finally {
            detachAdviceAppender(appender);
        }

        assertThat(formatted)
                .contains("HttpMessageNotReadableException")
                .doesNotContain("body the caller sent");
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesCarryingEachRecordComponentOnce")
    @DisplayName("reads a body carrying each record component once, so the repeated-component cases "
            + "isolate the repetition")
    void readsABodyCarryingEachRecordComponentOnce(Class<?> targetType, String body) {
        assertThat(readBody(defaultConverter(), targetType, body)).isNotNull();
    }

    private static Stream<Arguments> bodiesRepeatingARecordComponent() {
        return Stream.of(
                Arguments.of(UpdateSettingRequest.class, "{\"value\":\"first\",\"value\":\"second\"}"),
                Arguments.of(UpdateSettingRequest.class, "{\"value\":\"same\",\"value\":\"same\"}"),
                Arguments.of(UpdateSettingRequest.class, "{\"value\":null,\"value\":\"x\"}"),
                Arguments.of(UpdateSettingRequest.class,
                        "{\"value\":\"a\",\"value\":\"b\",\"value\":\"c\"}"),
                Arguments.of(LoginRequest.class,
                        "{\"username\":\"admin\",\"password\":\"a\",\"password\":\"b\"}"));
    }

    private static Stream<Arguments> bodiesCarryingEachRecordComponentOnce() {
        return Stream.of(
                Arguments.of(UpdateSettingRequest.class, "{\"value\":\"first\"}"),
                Arguments.of(UpdateSettingRequest.class, "{\"value\":null}"),
                Arguments.of(LoginRequest.class,
                        "{\"username\":\"admin\",\"password\":\"a\"}"));
    }

    // The framework converter binds a repeated component to its last value — DL-188 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0} binds username {1} and password {2}")
    @CsvSource(delimiter = '|', value = {
        "{\"username\":\"admin\",\"username\":\"root\",\"password\":\"a\"} | root  | a",
        "{\"username\":\"root\",\"username\":\"admin\",\"password\":\"a\"} | admin | a",
        "{\"password\":\"a\",\"password\":\"b\"}                            |       | b"
    })
    @DisplayName("binds a body repeating a record component before completion with the last value "
            + "instead of raising a conversion failure")
    void bindsABodyRepeatingARecordComponentBeforeCompletion(String body, String expectedUsername,
            String expectedPassword) throws Exception {

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        MockHttpInputMessage message =
                new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
        message.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Object bound = converter.read(LoginRequest.class, null, message);

        assertThat(bound).isInstanceOf(LoginRequest.class);
        assertThat(((LoginRequest) bound).username()).isEqualTo(expectedUsername);
        assertThat(((LoginRequest) bound).password()).isEqualTo(expectedPassword);
    }

    /**
     * Reads {@code body} onto {@code targetType} through the framework's own Jackson converter and
     * returns the conversion failure it raises.
     *
     * @param targetType the record the body is bound onto
     * @param body       the request body, as received
     * @return the raised exception
     */
    private static HttpMessageConversionException conversionFailureReadingBody(
            Class<?> targetType, String body) {

        return conversionFailureReadingBody(defaultConverter(), targetType, body);
    }

    private static HttpMessageConversionException conversionFailureReadingBody(
            MappingJackson2HttpMessageConverter converter, Class<?> targetType, String body) {

        return (HttpMessageConversionException) assertThatThrownBy(
                () -> converter.read(targetType, null, inputMessageOf(body)))
                        .isInstanceOf(HttpMessageConversionException.class)
                        .actual();
    }

    /**
     * Reads {@code body} onto {@code targetType} through {@code converter} and returns the bound value.
     *
     * @param converter  the converter reading the body
     * @param targetType the record the body is bound onto
     * @param body       the request body, as received
     * @return the bound value
     */
    private static Object readBody(MappingJackson2HttpMessageConverter converter, Class<?> targetType,
            String body) {

        try {
            return converter.read(targetType, null, inputMessageOf(body));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("The body could not be read", failure);
        }
    }

    /**
     * Builds a JSON request message carrying {@code body}.
     *
     * @param body the request body, as received
     * @return the message
     */
    private static MockHttpInputMessage inputMessageOf(String body) {
        MockHttpInputMessage message =
                new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
        message.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return message;
    }

    /** The converter the framework installs, with the duplicate handling it ships with. */
    private static MappingJackson2HttpMessageConverter defaultConverter() {
        return new MappingJackson2HttpMessageConverter();
    }

    private static ExceptionHandlerMethodResolver resolverForTheAdvice() {
        return new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
    }

    // -----------------------------------------------------------------------
    // What the advice writes to the log — DL-197
    // -----------------------------------------------------------------------

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("keeps a request body out of the log when the converter cannot bind it")
    void keepsARequestBodyOutOfTheLogWhenTheConverterCannotBindIt() {
        String body = "{\"username\":\"admin\",\"password\":\"s3cr3t-pa55phrase\","
                + "\"password\":\"s3cr3t-pa55phrase\"}";
        HttpMessageConversionException raised =
                conversionFailureReadingBody(LoginRequest.class, body);

        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(raised);

            assertThat(recorded.list).hasSize(1);
            ILoggingEvent record = recorded.list.get(0);
            String logged = record.getFormattedMessage();

            assertThat(logged).contains(raised.getClass().getSimpleName());
            assertThat(logged)
                    .doesNotContain("password")
                    .doesNotContain("s3cr3t-pa55phrase")
                    .doesNotContain("admin")
                    .doesNotContain(raised.getMessage());
            assertThat(record.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("keeps the message of a malformed body out of the log on the readable path too")
    void keepsTheMessageOfAMalformedBodyOutOfTheLogOnTheReadablePathToo() {
        HttpMessageConversionException raised = conversionFailureReadingBody(LoginRequest.class,
                "{\"username\":{\"nested\":\"HUNTER2SECRET\"},\"password\":\"p\"}");
        assertThat(raised).isInstanceOf(HttpMessageNotReadableException.class);

        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(raised);

            List<ILoggingEvent> records = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .toList();
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getFormattedMessage())
                    .isEqualTo("Rejecting a request the framework could not handle with HTTP 400: "
                            + "HttpMessageNotReadableException");
            assertThat(records.get(0).getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records the catch-all failure as bounded metadata and attaches no throwable")
    void recordsTheCatchAllFailureAsBoundedMetadataAndAttachesNoThrowable() {
        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(new IllegalStateException("a defect in our own code"));

            List<ILoggingEvent> errors = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).hasSize(1);
            ILoggingEvent error = errors.get(0);
            assertThat(error.getThrowableProxy()).as("throwable attached to the record").isNull();
            assertThat(error.getFormattedMessage())
                    .isEqualTo("Unhandled exception reached the error-handling advice; "
                            + "responding HTTP 500. Failure IllegalStateException")
                    .doesNotContain("a defect in our own code");
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // Log-capture proof that hostile failure text never reaches the ERROR record — DL-197 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "jdbc:postgresql://db.internal:5432/codeskeptic?user=scanner&password=s3cr3t",
        "Connection refused: db.internal/10.0.0.7:5432",
        "duplicate key value violates unique constraint \"tweets_pkey\": Detail: Key (id)=(4711)",
        "\r\n2026-01-01 00:00:00 ERROR forged record boundary",
        "value 'HUNTER2SECRET' at [Source: (String)\"{\"password\":\"HUNTER2SECRET\"}\"]",
    })
    @DisplayName("keeps hostile failure text out of the record the catch-all leaves")
    void keepsHostileFailureTextOutOfTheRecordTheCatchAllLeaves(String hostileMessage) {
        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(
                    new IllegalStateException(hostileMessage, new RuntimeException(hostileMessage)));

            List<ILoggingEvent> errors = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getFormattedMessage())
                    .as("the ERROR record")
                    .contains("Failure IllegalStateException <- RuntimeException")
                    .doesNotContain(hostileMessage)
                    .doesNotContain("password")
                    .doesNotContain("s3cr3t")
                    .doesNotContain("HUNTER2SECRET")
                    .doesNotContain("db.internal")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
            assertThat(errors.get(0).getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    private static ListAppender<ILoggingEvent> attachAdviceAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        adviceLogger.addAppender(appender);
        // DEBUG records are part of the contract these tests assert, so the level is raised for the
        // duration of the test and restored by detachAdviceAppender.
        adviceLogger.setLevel(Level.DEBUG);
        return appender;
    }

    private static void detachAdviceAppender(ListAppender<ILoggingEvent> appender) {
        Logger adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        adviceLogger.detachAppender(appender);
        adviceLogger.setLevel(null);
    }

    // The framework statuses reach the wire with no literal of their own — DL-092 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 405 with no body for an unsupported request method")
    void returns405WithNoBodyForAnUnsupportedRequestMethod() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(methodNotSupported());

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("returns 405 with no body when no method is reported as supported")
    void returns405WithNoBodyWhenNoMethodIsReportedAsSupported() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new HttpRequestMethodNotSupportedException("PATCH"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("returns 415 with no body for a request body whose media type no handler consumes")
    void returns415WithNoBodyForAnUnsupportedMediaType() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpectedException(unsupportedMediaType());

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("returns 406 with no body for a request whose Accept header cannot be satisfied")
    void returns406WithNoBodyForAnUnacceptableAcceptHeader() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new HttpMediaTypeNotAcceptableException("none"));

        assertThat(response.getStatusCode().value()).isEqualTo(406);
        assertThat(response.getBody()).isNull();
    }

    // A multipart parse failure and an unparseable Content-Type are defects of no route this service
    // declares, so both read as 500 — DL-092 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 500 with the Internal server error envelope for a multipart parse failure")
    void returns500ForAMultipartParseFailure() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new MultipartException("Failed to parse multipart servlet request"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("returns 400 with no body for a missing multipart part and 500 for a parse failure")
    void separatesAMissingMultipartPartFromAParseFailure() throws JsonProcessingException {
        assertThat(MultipartException.class
                .isAssignableFrom(MissingServletRequestPartException.class)).isFalse();

        ResponseEntity<ErrorResponse> missingPart = handler.handleUnexpectedException(
                new MissingServletRequestPartException("file"));
        assertThat(missingPart.getStatusCode().value()).isEqualTo(400);
        assertThat(missingPart.getBody()).isNull();

        ResponseEntity<ErrorResponse> parseFailure =
                handler.handleUnexpectedException(new MultipartException(CAUSE_MESSAGE));
        assertThat(parseFailure.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(parseFailure, INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("returns 500 when the request Content-Type cannot be parsed as a media type at all")
    void returns500WhenTheRequestContentTypeCannotBeParsed() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new InvalidMediaTypeException("not a media type", "does not contain '/'"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records only the exception class for a multipart failure, never its message or a "
            + "stack trace")
    void recordsOnlyTheExceptionClassForAMultipartFailure() {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(new MultipartException(SUBMITTED_CREDENTIAL));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent record = appender.list.get(0);
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(record.getFormattedMessage()).contains("MultipartException");
            assertThat(record.getFormattedMessage()).doesNotContain(SUBMITTED_CREDENTIAL);
            assertThat(record.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(appender);
        }
    }

    // A caller mistake is recorded at DEBUG and a defect at ERROR — DL-197 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("records a framework request failure at DEBUG and a genuine failure at ERROR")
    void separatesTheFrameworkRecordFromTheGenuineFailureRecord() {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(unsupportedMediaType());

            assertThat(appender.list).hasSize(1);
            ILoggingEvent frameworkRecord = appender.list.get(0);
            assertThat(frameworkRecord.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(frameworkRecord.getFormattedMessage())
                    .isEqualTo("Rejecting a request the framework could not handle with HTTP 415: "
                            + "HttpMediaTypeNotSupportedException");
            assertThat(frameworkRecord.getThrowableProxy()).isNull();

            handler.handleUnexpectedException(new IllegalArgumentException(SUBMITTED_CREDENTIAL));

            ILoggingEvent genuineFailureRecord = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .findFirst()
                    .orElseThrow();
            assertThat(genuineFailureRecord.getFormattedMessage())
                    .contains("Failure IllegalArgumentException")
                    .doesNotContain(SUBMITTED_CREDENTIAL);
            assertThat(genuineFailureRecord.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(appender);
        }
    }

    @Test
    @DisplayName("keeps the per-route literals distinct from the two global literals")
    void keepsThePerRouteLiteralsDistinctFromTheGlobalLiterals() {
        assertThat(List.of(TWEET_ID_IS_REQUIRED, UPDATE_DATA_IS_REQUIRED, NO_VALUE_PROVIDED))
                .doesNotContain(NOT_FOUND, INTERNAL_SERVER_ERROR);
        assertThat(handler.handleBadRequest(BadRequestException.tweetIdRequired()).getBody().error())
                .isEqualTo(TWEET_ID_IS_REQUIRED);
        assertThat(handler.handleNotFound(NotFoundException.tweetNotFound()).getBody().error())
                .isEqualTo(TWEET_NOT_FOUND);
    }

    /**
     * Builds the failure Spring MVC raises for a syntactically malformed request body.
     *
     * @return the failure
     */
    private static HttpMessageNotReadableException malformedBody() {
        return new HttpMessageNotReadableException(CAUSE_MESSAGE,
                new MockHttpInputMessage(new byte[0]));
    }

    /**
     * Builds the failure Spring MVC raises for a missing query parameter.
     *
     * @return the failure
     */
    private static MissingServletRequestParameterException missingParameter() {
        return new MissingServletRequestParameterException("page", "int");
    }

    /**
     * Builds the failure Spring MVC raises for a request method the matched path does not support.
     *
     * @return the failure, reporting {@code GET} as the only supported method
     */
    private static HttpRequestMethodNotSupportedException methodNotSupported() {
        return new HttpRequestMethodNotSupportedException("DELETE", List.of(HttpMethod.GET.name()));
    }

    /**
     * Builds the failure Spring MVC raises for a request body whose media type no handler consumes.
     *
     * @return the failure
     */
    private static HttpMediaTypeNotSupportedException unsupportedMediaType() {
        return new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                List.of(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("answers every handled exception with the single-key error object, or with no body "
            + "at all, and never with another key")
    void answersEveryHandledExceptionWithTheSingleKeyErrorObject() throws JsonProcessingException {
        for (ResponseEntity<ErrorResponse> response : allHandlerInvocations()) {
            if (response.getBody() != null) {
                assertSingleKeyErrorBody(response);
            }
        }
    }

    @Test
    @DisplayName("answers every handled exception with an ErrorResponse body or none, and never a "
            + "ProblemDetail")
    void answersEveryHandledExceptionWithAnErrorResponseAndNeverAProblemDetail() {
        for (ResponseEntity<ErrorResponse> response : allHandlerInvocations()) {
            Object body = response.getBody();

            if (body != null) {
                assertThat(body)
                        .isInstanceOf(ErrorResponse.class)
                        .isNotInstanceOf(ProblemDetail.class);
            }
        }
    }

    // Only the two global literals and the eight route literals reach the wire — DL-092 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("puts no literal beyond the route messages and the two global literals on the wire")
    void putsNoLiteralBeyondTheRouteMessagesAndTheTwoGlobalLiteralsOnTheWire() {
        Set<String> emitted = new LinkedHashSet<>();
        for (ResponseEntity<ErrorResponse> response : allHandlerInvocations()) {
            if (response.getBody() != null) {
                emitted.add(response.getBody().error());
            }
        }

        assertThat(emitted).containsExactlyInAnyOrder(
                TWEET_NOT_FOUND, RESPONSE_NOT_FOUND, RESPONSE_NOT_FOUND_OR_UPDATE_FAILED,
                SETTING_NOT_FOUND, TWEET_ID_IS_REQUIRED, UPDATE_DATA_IS_REQUIRED, NO_VALUE_PROVIDED,
                FAILED_TO_GENERATE_RESPONSE, NOT_FOUND, INTERNAL_SERVER_ERROR);
        assertThat(emitted)
                .doesNotContain("Bad request", "Method not allowed", "Unsupported media type",
                        "Not acceptable");
    }

    @Test
    @DisplayName("does not extend ResponseEntityExceptionHandler")
    void doesNotExtendResponseEntityExceptionHandler() {
        assertThat(ResponseEntityExceptionHandler.class.isAssignableFrom(GlobalExceptionHandler.class))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // The servlet container's error path — DL-183 — see docs/DECISION_LOG.md
    // -----------------------------------------------------------------------

    // The closed handler set the plan sanctions: the three domain failures, the validation failure,
    // the two unmatched-path types and the catch-all — DL-092 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("declares exactly the three domain failures, the validation failure, both "
            + "unmatched-path types and the catch-all, and nothing else")
    void declaresExactlyTheSanctionedHandlerSet() {
        assertThat(handledExceptionTypes()).containsExactlyInAnyOrder(
                NotFoundException.class,
                BadRequestException.class,
                ResponseGenerationException.class,
                MethodArgumentNotValidException.class,
                NoHandlerFoundException.class,
                NoResourceFoundException.class,
                Exception.class);
        assertThat(exceptionHandlerMethods()).hasSize(6);
    }

    @Test
    @DisplayName("declares no dedicated handler for an authentication failure or an access-denied "
            + "failure")
    void declaresNoDedicatedHandlerForAnAuthenticationOrAccessDeniedFailure() {
        Set<Class<?>> handled = handledExceptionTypes();

        assertThat(handled).doesNotContain(AuthenticationException.class, AccessDeniedException.class);
        for (Class<?> handledType : handled) {
            assertThat(AuthenticationException.class.isAssignableFrom(handledType))
                    .as("%s is a dedicated authentication type", handledType.getName())
                    .isFalse();
            assertThat(AccessDeniedException.class.isAssignableFrom(handledType))
                    .as("%s is a dedicated access-denied type", handledType.getName())
                    .isFalse();
        }
    }

    // Net-new (no Python counterpart) — DL-092 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("resolves an authentication failure and an access-denied failure to the catch-all "
            + "handler, which answers 500 with the Internal server error envelope")
    void resolvesAnAuthenticationAndAccessDeniedFailureToTheCatchAllHandler()
            throws JsonProcessingException {

        ExceptionHandlerMethodResolver resolver = resolverForTheAdvice();
        Method catchAll = handlerMethodFor(Exception.class);

        AuthenticationServiceException authenticationFailure =
                new AuthenticationServiceException(CAUSE_MESSAGE);
        AccessDeniedException accessDenied = new AccessDeniedException(CAUSE_MESSAGE);

        assertThat(resolver.resolveMethod(authenticationFailure)).isEqualTo(catchAll);
        assertThat(resolver.resolveMethod(accessDenied)).isEqualTo(catchAll);

        for (Exception failure : List.of(authenticationFailure, accessDenied)) {
            ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(failure);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
            assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
        }
    }

    @Test
    @DisplayName("carries no ResponseStatus annotation of 401 or 403 on the class or any method")
    void carriesNoResponseStatusOf401Or403() {
        List<Method> methodsCarryingResponseStatus = new ArrayList<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
            if (responseStatus != null) {
                methodsCarryingResponseStatus.add(method);
                assertThat(responseStatus.value()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
                assertThat(responseStatus.code()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
            }
        }

        assertThat(methodsCarryingResponseStatus).isEmpty();
        assertThat(GlobalExceptionHandler.class.getAnnotation(ResponseStatus.class)).isNull();
    }

    @Test
    @DisplayName("emits only client and server statuses this advice declares and never 401 or 403")
    void emitsOnlyTheStatusesThisAdviceDeclares() {
        List<ResponseEntity<ErrorResponse>> invocations = allHandlerInvocations();

        assertThat(invocations).isNotEmpty();
        for (ResponseEntity<ErrorResponse> response : invocations) {
            int status = response.getStatusCode().value();

            assertThat(status).isIn(STATUS_CODES_THIS_ADVICE_EMITS);
            assertThat(status).isNotEqualTo(UNAUTHORIZED);
            assertThat(status).isNotEqualTo(FORBIDDEN);
        }
    }

    @Test
    @DisplayName("is annotated with RestControllerAdvice")
    void isAnnotatedWithRestControllerAdvice() {
        assertThat(GlobalExceptionHandler.class.isAnnotationPresent(RestControllerAdvice.class)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "404,Not found",
        "500,Internal server error"
    })
    @DisplayName("renders the single-key envelope for a dispatched 404 and 500")
    void rendersTheSingleKeyEnvelopeForADispatchedStatus(int dispatchedStatus, String expected) {
        Map<String, Object> attributes = errorAttributesFor(dispatchedStatus);

        assertThat(attributes).containsExactly(entry("error", expected));
    }

    @ParameterizedTest
    @ValueSource(ints = {UNAUTHORIZED, FORBIDDEN, 400, 405, 406, 409, 415, 429, 502, 503})
    @DisplayName("renders no attribute for a dispatched status other than 404 and 500")
    void rendersNoAttributeForADispatchedStatusOtherThan404And500(int dispatchedStatus) {
        assertThat(errorAttributesFor(dispatchedStatus)).isEmpty();
    }

    @Test
    @DisplayName("renders the Internal server error envelope when no status was recorded")
    void rendersTheInternalServerErrorEnvelopeWhenNoStatusWasRecorded() {
        Map<String, Object> attributes = errorAttributes()
                .getErrorAttributes(new ServletWebRequest(new MockHttpServletRequest()),
                        ErrorAttributeOptions.defaults());

        assertThat(attributes).containsExactly(entry("error", INTERNAL_SERVER_ERROR));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 405, 415, 500})
    @DisplayName("omits the timestamp, status, path, exception, message, trace and errors attributes")
    void omitsEveryFrameworkContributedAttribute(int dispatchedStatus) {
        Map<String, Object> attributes = errorAttributesFor(dispatchedStatus);

        assertThat(attributes).doesNotContainKeys(
                "timestamp", "status", "path", "exception", "message", "trace", "errors");
    }

    @Test
    @DisplayName("names an attribute source that records the dispatched exception as the framework does")
    void namesAnAttributeSourceThatRecordsTheDispatchedException() {
        assertThat(errorAttributes()).isInstanceOf(DefaultErrorAttributes.class);
    }

    // The error path stays mapped to the framework controller; this advice publishes only the status
    // and literal it renders — DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("declares no request-mapped or ErrorController type of its own")
    void declaresNoRequestMappedOrErrorControllerTypeOfItsOwn() {
        assertThat(GlobalExceptionHandler.class.getDeclaredClasses()).allSatisfy(nested -> {
            assertThat(ErrorController.class.isAssignableFrom(nested)).isFalse();
            assertThat(nested.getAnnotation(RequestMapping.class)).isNull();
            assertThat(nested.getAnnotation(Controller.class)).isNull();
            assertThat(nested.getAnnotation(RestController.class)).isNull();
        });
        assertThat(GlobalExceptionHandler.class.getAnnotation(RequestMapping.class)).isNull();
        assertThat(GlobalExceptionHandler.class.getAnnotation(Controller.class)).isNull();
    }

    @ParameterizedTest(name = "a dispatch recording {0} carries {1}")
    @CsvSource({
        "404,Not found",
        "500,Internal server error"
    })
    @DisplayName("publishes the single-key literal for a dispatched 404 and 500")
    void publishesTheSingleKeyLiteralForADispatchedStatus(int dispatchedStatus,
            String expectedMessage) {

        assertThat(errorAttributesFor(dispatchedStatus))
                .containsExactly(entry("error", expectedMessage));
    }

    @ParameterizedTest
    @ValueSource(ints = {UNAUTHORIZED, FORBIDDEN, 400, 405, 406, 415, 502, 503, 504})
    @DisplayName("publishes a dispatched status other than 404 and 500 with no literal at all")
    void publishesADispatchedOtherStatusWithNoLiteral(int dispatchedStatus) {
        assertThat(errorAttributesFor(dispatchedStatus)).isEmpty();
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"text/html", "text/html,application/xhtml+xml,*/*;q=0.8", "text/plain",
        "*/*", "application/xml"})
    @DisplayName("renders the same attribute map whatever the Accept header names")
    void rendersTheSameAttributeMapWhateverTheAcceptHeaderNames(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value());
        request.addHeader(HttpHeaders.ACCEPT, accept);

        Map<String, Object> attributes = errorAttributes().getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());

        assertThat(attributes).containsExactly(entry("error", NOT_FOUND));
    }

    @Test
    @DisplayName("maps a dispatch recording no status to the internal server error status")
    void mapsADispatchRecordingNoStatusToTheInternalServerErrorStatus() {
        Map<String, Object> attributes = errorAttributes().getErrorAttributes(
                new ServletWebRequest(new MockHttpServletRequest()),
                ErrorAttributeOptions.defaults());

        assertThat(attributes).containsExactly(entry("error", INTERNAL_SERVER_ERROR));
    }

    // The error dispatch adds no literal beyond the two the retired handlers declared — DL-092 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("publishes only the two global literals across every dispatchable status")
    void publishesOnlyTheTwoGlobalLiteralsAcrossEveryDispatchableStatus() {
        Set<String> rendered = new LinkedHashSet<>();
        for (int status = 400; status < 600; status++) {
            Object literal = errorAttributesFor(status).get(ERROR_KEY);
            if (literal != null) {
                rendered.add(String.valueOf(literal));
            }
        }

        assertThat(rendered).containsExactlyInAnyOrderElementsOf(GLOBAL_WIRE_LITERALS);
        assertThat(rendered).containsExactlyInAnyOrder(NOT_FOUND, INTERNAL_SERVER_ERROR);
    }

    private ErrorAttributes errorAttributes() {
        return handler.errorEnvelopeAttributes();
    }

    private Map<String, Object> errorAttributesFor(int dispatchedStatus) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, dispatchedStatus);
        return errorAttributes().getErrorAttributes(new ServletWebRequest(request),
                ErrorAttributeOptions.defaults());
    }

    private List<ResponseEntity<ErrorResponse>> allHandlerInvocations() {
        List<ResponseEntity<ErrorResponse>> invocations = new ArrayList<>();
        invocations.add(handler.handleNotFound(NotFoundException.tweetNotFound()));
        invocations.add(handler.handleNotFound(NotFoundException.responseNotFound()));
        invocations.add(handler.handleNotFound(NotFoundException.responseNotFoundOrUpdateFailed()));
        invocations.add(handler.handleNotFound(NotFoundException.settingNotFound()));
        invocations.add(handler.handleBadRequest(BadRequestException.tweetIdRequired()));
        invocations.add(handler.handleBadRequest(BadRequestException.updateDataRequired()));
        invocations.add(handler.handleBadRequest(BadRequestException.noValueProvided()));
        invocations.add(handler.handleResponseGenerationFailure(new ResponseGenerationException()));
        invocations.add(handler.handleMethodArgumentNotValid(validationFailureOn("tweetId")));
        invocations.add(handler.handleMethodArgumentNotValid(validationFailureOn("tweet_id")));
        invocations.add(handler.handleMethodArgumentNotValid(validationFailureOn("value")));
        invocations.add(handler.handleMethodArgumentNotValid(validationFailureOn("somethingElse")));
        invocations.add(handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors()));
        invocations.add(handler.handleNoHandlerFound());
        invocations.add(handler.handleUnexpectedException(malformedBody()));
        invocations.add(handler.handleUnexpectedException(missingParameter()));
        invocations.add(handler.handleUnexpectedException(
                new HttpMessageConversionException(CAUSE_MESSAGE)));
        invocations.add(handler.handleUnexpectedException(conversionFailureReadingBody(
                defaultConverter(), UpdateSettingRequest.class,
                "{\"value\":\"first\",\"value\":\"second\"}")));
        invocations.add(handler.handleUnexpectedException(
                new HttpMessageNotWritableException(CAUSE_MESSAGE)));
        invocations.add(handler.handleUnexpectedException(methodNotSupported()));
        invocations.add(handler.handleUnexpectedException(unsupportedMediaType()));
        invocations.add(handler.handleUnexpectedException(new MultipartException(CAUSE_MESSAGE)));
        invocations.add(handler.handleUnexpectedException(
                new IllegalArgumentException(CAUSE_MESSAGE)));
        invocations.add(handler.handleUnexpectedException(
                new HttpMediaTypeNotAcceptableException("none")));
        invocations.add(handler.handleUnexpectedException(new RuntimeException(CAUSE_MESSAGE)));
        invocations.add(handler.handleUnexpectedException(new Exception(CAUSE_MESSAGE)));
        return invocations;
    }

    private void assertErrorEnvelope(ResponseEntity<ErrorResponse> response, String expectedMessage)
            throws JsonProcessingException {
        assertSingleKeyErrorBody(response);

        assertThat(response.getBody().error()).isEqualTo(expectedMessage);

        JsonNode envelope = envelopeOf(response);
        assertThat(envelope.get(ERROR_KEY).isTextual()).isTrue();
        assertThat(envelope.get(ERROR_KEY).asText()).isEqualTo(expectedMessage);
    }

    private void assertSingleKeyErrorBody(ResponseEntity<ErrorResponse> response)
            throws JsonProcessingException {
        Object body = response.getBody();
        assertThat(body).isInstanceOf(ErrorResponse.class);
        assertThat(body).isNotInstanceOf(ProblemDetail.class);

        JsonNode envelope = envelopeOf(response);
        assertThat(envelope.isObject()).isTrue();
        assertThat(envelope.size()).isEqualTo(1);
        assertThat(envelope.has(ERROR_KEY)).isTrue();

        List<String> keys = new ArrayList<>();
        envelope.properties().forEach(property -> keys.add(property.getKey()));
        assertThat(keys).containsExactly(ERROR_KEY);

        for (String absentKey : KEYS_ABSENT_FROM_EVERY_BODY) {
            assertThat(envelope.has(absentKey)).isFalse();
        }
    }

    private JsonNode envelopeOf(ResponseEntity<ErrorResponse> response) throws JsonProcessingException {
        return objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
    }

    private static Method handlerMethodFor(Class<?> exceptionType) {
        List<Method> matches = new ArrayList<>();
        for (Method method : exceptionHandlerMethods()) {
            if (handledExceptionTypesOf(method).contains(exceptionType)) {
                matches.add(method);
            }
        }

        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static List<Method> exceptionHandlerMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ExceptionHandler.class)) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static Set<Class<?>> handledExceptionTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Method method : exceptionHandlerMethods()) {
            types.addAll(handledExceptionTypesOf(method));
        }
        return types;
    }

    private static Set<Class<?>> handledExceptionTypesOf(Method method) {
        Set<Class<?>> types = new LinkedHashSet<>();
        ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
        if (annotation != null && annotation.value().length > 0) {
            types.addAll(Arrays.asList(annotation.value()));
            return types;
        }

        for (Class<?> parameterType : method.getParameterTypes()) {
            if (Throwable.class.isAssignableFrom(parameterType)) {
                types.add(parameterType);
            }
        }
        return types;
    }

    private static MethodArgumentNotValidException validationFailureOn(String rejectedField) {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new ValidationTarget(), "validationTarget");
        bindingResult.rejectValue(rejectedField, "NotNull");
        return new MethodArgumentNotValidException(VALIDATION_TARGET_PARAMETER, bindingResult);
    }

    private static MethodArgumentNotValidException validationFailureWithoutFieldErrors() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new ValidationTarget(), "validationTarget");
        return new MethodArgumentNotValidException(VALIDATION_TARGET_PARAMETER, bindingResult);
    }

    private static MethodParameter validationTargetParameter() {
        try {
            Method holder =
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("validationTargetHolder", Object.class);
            return new MethodParameter(holder, 0);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("validationTargetHolder(Object) is not declared", ex);
        }
    }

    private void validationTargetHolder(Object target) {
        // Reflection target of validationTargetParameter(); this method is never invoked.
    }

    private static final class ValidationTarget {

        public String getTweetId() {
            return null;
        }

        public String getTweet_id() {
            return null;
        }

        public String getValue() {
            return null;
        }

        public String getSomethingElse() {
            return null;
        }

        public String getContent() {
            return null;
        }
    }
}
