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
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
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

import com.fasterxml.jackson.core.JsonParser;
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
     * the log — see docs/DECISION_LOG.md DL-201.
     */
    private static final String SUBMITTED_CREDENTIAL = "Zq7-distinctive-credential-value-4711";

    private static final List<String> KEYS_ABSENT_FROM_EVERY_BODY = List.of(
            "type", "title", "status", "detail", "instance", "timestamp", "path", "message", "errors");

    private static final Set<Integer> STATUS_CODES_THIS_ADVICE_EMITS =
            Set.of(400, 404, 405, 406, 415, 500);

    private static final String BAD_REQUEST = "Bad request";

    private static final String METHOD_NOT_ALLOWED = "Method not allowed";

    private static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";

    private static final String NOT_ACCEPTABLE = "Not acceptable";

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

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"somethingElse", "content"})
    @DisplayName("returns 400 and the Bad request literal when the rejected field name is outside the map")
    void returns400AndTheBadRequestLiteralForAFieldOutsideTheMap(String rejectedField)
            throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureOn(rejectedField));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(response.getBody().error()).isNotEqualTo(TWEET_ID_IS_REQUIRED);
        assertThat(response.getBody().error()).isNotEqualTo(NO_VALUE_PROVIDED);
        assertThat(envelopeOf(response).toString()).doesNotContain(rejectedField);
    }

    @Test
    @DisplayName("returns 400 and the Bad request literal when the binding result carries no field error")
    void returns400AndTheBadRequestLiteralWhenNoFieldErrorIsPresent() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
    }

    @Test
    @DisplayName("emits one of exactly three messages for a body that failed validation")
    void emitsOneOfExactlyThreeMessagesForABodyThatFailedValidation() {
        List<ResponseEntity<ErrorResponse>> responses = List.of(
                handler.handleMethodArgumentNotValid(validationFailureOn("tweetId")),
                handler.handleMethodArgumentNotValid(validationFailureOn("tweet_id")),
                handler.handleMethodArgumentNotValid(validationFailureOn("value")),
                handler.handleMethodArgumentNotValid(validationFailureOn("somethingElse")),
                handler.handleMethodArgumentNotValid(validationFailureOn("content")),
                handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors()));

        assertThat(responses)
                .allSatisfy(response -> assertThat(response.getBody()).isNotNull())
                .extracting(response -> response.getBody().error())
                .allSatisfy(message -> assertThat(message)
                        .isIn(TWEET_ID_IS_REQUIRED, NO_VALUE_PROVIDED, BAD_REQUEST));
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
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("clientRequestFailures")
    @DisplayName("returns 400 and never 500 for a request the framework rejected")
    void returns400AndNever500ForAClientRequestFailure(Exception reported, String description)
            throws JsonProcessingException {

        ResponseEntity<ErrorResponse> response = handler.handleClientRequestFailure(reported);

        assertThat(response.getStatusCode().value()).as(description).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    private static Stream<Arguments> clientRequestFailures() {
        return Stream.of(
                Arguments.of(malformedBody(), "a syntactically malformed request body"),
                Arguments.of(missingParameter(), "a missing query parameter"),
                Arguments.of(new MissingServletRequestPartException("part"), "a missing multipart part"),
                Arguments.of(new TypeMismatchException(CAUSE_MESSAGE, Integer.class),
                        "a request value the target type cannot hold"));
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 400 with the Bad request envelope for a message-conversion failure")
    void returns400WithBadRequestForAMessageConversionFailure() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleMessageConversionFailure(
                new HttpMessageConversionException(CAUSE_MESSAGE));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    // Converter-failure log sanitisation — DL-199 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records only the exception class for a message-conversion failure, never its message")
    void recordsOnlyTheExceptionClassForAMessageConversionFailure() throws JsonProcessingException {
        String attackerControlled = "SENTINEL-9f3a\r\nWARN forged log line: secret=hunter2";
        Logger adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> recorded = new ListAppender<>();
        recorded.start();
        adviceLogger.addAppender(recorded);
        try {
            ResponseEntity<ErrorResponse> response = handler.handleMessageConversionFailure(
                    new HttpMessageConversionException(attackerControlled));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertErrorEnvelope(response, BAD_REQUEST);
        } finally {
            adviceLogger.detachAppender(recorded);
            recorded.stop();
        }

        assertThat(recorded.list).hasSize(1);
        ILoggingEvent event = recorded.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains(HttpMessageConversionException.class.getSimpleName())
                .doesNotContain("SENTINEL-9f3a")
                .doesNotContain("hunter2")
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesRepeatingARecordComponent")
    @DisplayName("answers a body repeating a record component after completion with 400 and never "
            + "500")
    void answersABodyRepeatingARecordComponentAfterCompletionWith400(
            Class<?> targetType, String body) throws JsonProcessingException {

        HttpMessageConversionException raised = conversionFailureReadingBody(defaultConverter(),
                targetType, body);

        // The advice routes a conversion failure that is not a read failure to its own handler.
        assertThat(raised).isNotInstanceOf(HttpMessageNotReadableException.class);

        Method resolved = resolverForTheAdvice().resolveMethod(raised);
        assertThat(resolved)
                .isNotNull()
                .isEqualTo(handlerMethodFor(HttpMessageConversionException.class))
                .isNotEqualTo(handlerMethodFor(Exception.class));

        ResponseEntity<ErrorResponse> response = handler.handleMessageConversionFailure(raised);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(envelopeOf(response).toString()).doesNotContain("password");
    }

    // Net-new (no Python counterpart) — DL-196 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Unexpected token at [Source: (String)\"{\"a\":\"\r\nWARN forged log record\"}\"]",
        "cannot deserialize\nINFO admin logged in",
        "bad body \u0000 with a null byte",
        "JSON parse error: password=hunter2",
    })
    @DisplayName("writes no part of a conversion failure's message to the log")
    void writesNoPartOfAConversionFailuresMessageToTheLog(String attackerControlledMessage) {
        List<ILoggingEvent> events = new ArrayList<>();
        Logger advice = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        advice.addAppender(appender);
        try {
            handler.handleMessageConversionFailure(
                    new HttpMessageConversionException(attackerControlledMessage));
            events.addAll(appender.list);
        } finally {
            advice.detachAppender(appender);
            appender.stop();
        }

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
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

    // Net-new (no Python counterpart) — DL-196 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("names the exception class in the conversion-failure log record")
    void namesTheExceptionClassInTheConversionFailureLogRecord() {
        Logger advice = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        advice.addAppender(appender);
        String formatted;
        try {
            handler.handleMessageConversionFailure(
                    new HttpMessageNotReadableException("body the caller sent",
                            new MockHttpInputMessage(new byte[0])));
            formatted = appender.list.get(0).getFormattedMessage();
        } finally {
            advice.detachAppender(appender);
            appender.stop();
        }

        assertThat(formatted)
                .contains("HttpMessageNotReadableException")
                .doesNotContain("body the caller sent");
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesRepeatingARecordComponent")
    @DisplayName("answers a body repeating a record component with 400 under strict duplicate "
            + "detection, where the failure is a read failure")
    void answersABodyRepeatingARecordComponentWith400UnderStrictDuplicateDetection(
            Class<?> targetType, String body) throws JsonProcessingException {

        HttpMessageConversionException raised = conversionFailureReadingBody(
                strictDuplicateDetectionConverter(), targetType, body);

        assertThat(raised).isInstanceOf(HttpMessageNotReadableException.class);

        Method resolved = resolverForTheAdvice().resolveMethod(raised);
        assertThat(resolved)
                .isNotNull()
                .isEqualTo(handlerMethodFor(HttpMessageNotReadableException.class))
                .isNotEqualTo(handlerMethodFor(Exception.class));

        ResponseEntity<ErrorResponse> response =
                handler.handleClientRequestFailure(raised);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(envelopeOf(response).toString()).doesNotContain("password");
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesCarryingEachRecordComponentOnce")
    @DisplayName("reads a body carrying each record component once, so the repeated-component cases "
            + "isolate the repetition")
    void readsABodyCarryingEachRecordComponentOnce(Class<?> targetType, String body) {
        assertThat(readBody(defaultConverter(), targetType, body)).isNotNull();
        assertThat(readBody(strictDuplicateDetectionConverter(), targetType, body)).isNotNull();
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

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
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

    // Net-new (no Python counterpart) — DL-193 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("writes no part of a converter message or of a submitted value to the log")
    void writesNoPartOfAConverterMessageToTheLog() {
        String submitted = "s3cr3t-submitted-value";
        HttpMessageConversionException raised = conversionFailureReadingBody(
                UpdateSettingRequest.class,
                "{\"value\":\"" + submitted + "\",\"value\":\"" + submitted + "\"}");

        Logger advice = (Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> recorded = new ListAppender<>();
        recorded.start();
        advice.addAppender(recorded);
        try {
            handler.handleMessageConversionFailure(raised);
        } finally {
            advice.detachAppender(recorded);
            recorded.stop();
        }

        assertThat(recorded.list).hasSize(1);
        ILoggingEvent event = recorded.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .startsWith("Rejecting a request body the converter could not bind with HTTP 400: "
                        + raised.getClass().getSimpleName())
                .doesNotContain(raised.getMessage())
                .doesNotContain(submitted)
                .doesNotContain("value")
                .doesNotContain("fallback")
                .doesNotContain(raised.getMessage());
        assertThat(event.getThrowableProxy()).isNull();
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 500 with the Internal server error envelope for a response-write failure")
    void returns500WithInternalServerErrorForAResponseWriteFailure() throws JsonProcessingException {
        HttpMessageNotWritableException raised = new HttpMessageNotWritableException(CAUSE_MESSAGE);

        Method resolved = resolverForTheAdvice().resolveMethod(raised);
        assertThat(resolved)
                .isNotNull()
                .isEqualTo(handlerMethodFor(HttpMessageNotWritableException.class));

        ResponseEntity<ErrorResponse> response = handler.handleResponseWriteFailure(raised);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
        assertThat(envelopeOf(response).toString()).doesNotContain(CAUSE_MESSAGE);
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("routes each type of the conversion hierarchy to its own handler by direction")
    void routesEachTypeOfTheConversionHierarchyToItsOwnHandler() {
        ExceptionHandlerMethodResolver resolver = resolverForTheAdvice();

        assertThat(resolver.resolveMethod(malformedBody()))
                .isEqualTo(handlerMethodFor(HttpMessageNotReadableException.class));
        assertThat(resolver.resolveMethod(new HttpMessageConversionException(CAUSE_MESSAGE)))
                .isEqualTo(handlerMethodFor(HttpMessageConversionException.class));
        assertThat(resolver.resolveMethod(new HttpMessageNotWritableException(CAUSE_MESSAGE)))
                .isEqualTo(handlerMethodFor(HttpMessageNotWritableException.class));

        Method readable = handlerMethodFor(HttpMessageNotReadableException.class);
        Method supertype = handlerMethodFor(HttpMessageConversionException.class);
        Method writable = handlerMethodFor(HttpMessageNotWritableException.class);
        assertThat(Set.of(readable, supertype, writable)).hasSize(3);
        assertThat(writable).isNotEqualTo(handlerMethodFor(Exception.class));
    }

    /**
     * Reads {@code body} onto {@code targetType} through {@code converter} and returns the conversion
     * failure it raises.
     *
     * @param converter  the converter reading the body
     * @param targetType the record the body is bound onto
     * @param body       the request body, as received
     * @return the raised exception
     */
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

    /** The same converter with {@code STRICT_DUPLICATE_DETECTION} enabled on its parser. */
    private static MappingJackson2HttpMessageConverter strictDuplicateDetectionConverter() {
        ObjectMapper strict = new ObjectMapper();
        strict.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return new MappingJackson2HttpMessageConverter(strict);
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
            handler.handleMessageConversionFailure(raised);

            List<ILoggingEvent> warnings = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            ILoggingEvent warning = warnings.get(0);
            String logged = warning.getFormattedMessage();

            assertThat(logged)
                    .contains("Rejecting a request body the converter could not bind with HTTP 400")
                    .contains("HttpMessageConversionException")
                    .contains("detail sha256:");
            assertThat(logged)
                    .doesNotContain("password")
                    .doesNotContain("s3cr3t-pa55phrase")
                    .doesNotContain("admin")
                    .doesNotContain(raised.getMessage());
            assertThat(warning.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("derives a stable correlation token that identifies the failure shape rather than "
            + "the request")
    void derivesAStableCorrelationTokenThatIdentifiesTheFailureShape() {
        String settingBody = "{\"value\":\"first\",\"value\":\"second\"}";
        String loginBody = "{\"username\":\"a\",\"password\":\"b\",\"password\":\"c\"}";

        String repeated = correlationTokenFor(
                conversionFailureReadingBody(UpdateSettingRequest.class, settingBody));
        String repeatedAgain = correlationTokenFor(
                conversionFailureReadingBody(UpdateSettingRequest.class, settingBody));
        String otherRoute = correlationTokenFor(
                conversionFailureReadingBody(LoginRequest.class, loginBody));

        // Two requests that provoke the same library message share a token: the token names the
        // failure shape, not the caller or the body.
        assertThat(repeated).startsWith("sha256:").isEqualTo(repeatedAgain).isEqualTo(otherRoute);

        // A different library message yields a different token.
        assertThat(correlationTokenFor(new HttpMessageConversionException("a different failure")))
                .startsWith("sha256:")
                .isNotEqualTo(repeated);
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
            handler.handleClientRequestFailure(raised);

            List<ILoggingEvent> records = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .toList();
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getFormattedMessage())
                    .isEqualTo("Rejecting a malformed request with HTTP 400: "
                            + "HttpMessageNotReadableException");
            assertThat(records.get(0).getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    // Net-new (no Python counterpart) — DL-197 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("attaches the throwable only for this service's own unexpected failure")
    void attachesTheThrowableOnlyForThisServicesOwnUnexpectedFailure() {
        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            handler.handleUnexpectedException(new IllegalStateException("a defect in our own code"));

            List<ILoggingEvent> errors = recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getThrowableProxy()).isNotNull();
            assertThat(errors.get(0).getFormattedMessage())
                    .isEqualTo("Unhandled exception reached the error-handling advice; "
                            + "responding HTTP 500");
        } finally {
            detachAdviceAppender(recorded);
        }
    }

    private static String correlationTokenFor(HttpMessageConversionException raised) {
        ListAppender<ILoggingEvent> recorded = attachAdviceAppender();
        try {
            new GlobalExceptionHandler().handleMessageConversionFailure(raised);
            return recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(event -> event.getArgumentArray()[1])
                    .map(String::valueOf)
                    .findFirst()
                    .orElseThrow();
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

    @Test
    @DisplayName("returns 405 with the allowed methods for an unsupported request method")
    void returns405WithTheAllowedMethodsForAnUnsupportedRequestMethod() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(methodNotSupported());

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertErrorEnvelope(response, METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().get(HttpHeaders.ALLOW))
                .containsExactly(HttpMethod.GET.name());
    }

    @Test
    @DisplayName("returns 405 without an allow header when no method is reported as supported")
    void returns405WithoutAnAllowHeaderWhenNoMethodIsReportedAsSupported()
            throws JsonProcessingException {

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PATCH"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertErrorEnvelope(response, METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().containsKey(HttpHeaders.ALLOW)).isFalse();
    }

    @Test
    @DisplayName("returns 415 for a request body whose media type no handler consumes")
    void returns415ForARequestBodyWhoseMediaTypeNoHandlerConsumes() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnsupportedMediaType(unsupportedMediaType());

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertErrorEnvelope(response, UNSUPPORTED_MEDIA_TYPE);
    }

    // Net-new (no Python counterpart) — DL-219 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 415 for a multipart request no route of this service consumes")
    void returns415ForAMultipartRequestNoRouteConsumes() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleMultipartFailure(
                new MultipartException("Failed to parse multipart servlet request"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertErrorEnvelope(response, UNSUPPORTED_MEDIA_TYPE);
    }

    // Net-new (no Python counterpart) — DL-219 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records only the exception class for a multipart failure, never its message or a "
            + "stack trace")
    void recordsOnlyTheExceptionClassForAMultipartFailure() {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        try {
            handler.handleMultipartFailure(new MultipartException(SUBMITTED_CREDENTIAL));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent record = appender.list.get(0);
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(record.getFormattedMessage()).contains("MultipartException");
            assertThat(record.getFormattedMessage()).doesNotContain(SUBMITTED_CREDENTIAL);
            assertThat(record.getThrowableProxy()).isNull();
        } finally {
            detachAdviceAppender(appender);
        }
    }

    // Net-new (no Python counterpart) — DL-219 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("keeps a missing multipart part on the 400 path and a multipart parse failure on "
            + "the 415 path")
    void keepsAMissingMultipartPartOnThe400PathAndAParseFailureOnThe415Path() {
        assertThat(MultipartException.class
                .isAssignableFrom(MissingServletRequestPartException.class)).isFalse();

        ExceptionHandlerMethodResolver resolver = resolverForTheAdvice();

        assertThat(resolver.resolveMethod(new MissingServletRequestPartException("file")))
                .isEqualTo(handlerMethodFor(MissingServletRequestPartException.class));
        assertThat(resolver.resolveMethod(new MultipartException(CAUSE_MESSAGE)))
                .isEqualTo(handlerMethodFor(MultipartException.class));
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] Content-Type {0}")
    @ValueSource(strings = {"*/*", "application/*", "text/*", "multipart/*"})
    @DisplayName("returns 415 when the request Content-Type names no concrete media type")
    void returns415WhenTheRequestContentTypeNamesNoConcreteMediaType(String declaredContentType)
            throws JsonProcessingException {

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException("Content-Type cannot contain wildcard type '*'"),
                requestCarryingContentType(declaredContentType));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertErrorEnvelope(response, UNSUPPORTED_MEDIA_TYPE);
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 415 when the request Content-Type cannot be parsed as a media type at all")
    void returns415WhenTheRequestContentTypeCannotBeParsed() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new InvalidMediaTypeException("not a media type", "does not contain '/'"),
                requestCarryingContentType("not a media type"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertErrorEnvelope(response, UNSUPPORTED_MEDIA_TYPE);
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] Content-Type {0}")
    @ValueSource(strings = {"application/json", "text/plain", "application/json;charset=UTF-8"})
    @DisplayName("returns 500 for an illegal-argument failure on a request naming a concrete media "
            + "type")
    void returns500ForAnIllegalArgumentFailureOnAConcreteMediaType(String declaredContentType)
            throws JsonProcessingException {

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException(CAUSE_MESSAGE),
                requestCarryingContentType(declaredContentType));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("returns 500 for an illegal-argument failure on a request carrying no Content-Type")
    void returns500ForAnIllegalArgumentFailureWithoutAContentType() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException(CAUSE_MESSAGE), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a non-concrete media type at WARN without a stack trace and keeps the "
            + "stack trace for a genuine illegal-argument failure")
    void separatesTheMediaTypeRecordFromTheGenuineFailureRecord() {
        ListAppender<ILoggingEvent> appender = attachAdviceAppender();
        try {
            handler.handleIllegalArgument(
                    new IllegalArgumentException("Content-Type cannot contain wildcard subtype '*'"),
                    requestCarryingContentType("application/*"));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent mediaTypeRecord = appender.list.get(0);
            assertThat(mediaTypeRecord.getLevel()).isEqualTo(Level.WARN);
            assertThat(mediaTypeRecord.getFormattedMessage())
                    .contains("IllegalArgumentException")
                    .doesNotContain("wildcard subtype");
            assertThat(mediaTypeRecord.getThrowableProxy()).isNull();

            handler.handleIllegalArgument(new IllegalArgumentException(SUBMITTED_CREDENTIAL),
                    requestCarryingContentType("application/json"));

            assertThat(appender.list).hasSize(2);
            ILoggingEvent genuineFailureRecord = appender.list.get(1);
            assertThat(genuineFailureRecord.getLevel()).isEqualTo(Level.ERROR);
            assertThat(genuineFailureRecord.getFormattedMessage()).doesNotContain(SUBMITTED_CREDENTIAL);
            assertThat(genuineFailureRecord.getThrowableProxy()).isNotNull();
        } finally {
            detachAdviceAppender(appender);
        }
    }

    // Net-new (no Python counterpart) — DL-220 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("resolves an illegal-argument failure to its own handler rather than the catch-all")
    void resolvesAnIllegalArgumentFailureToItsOwnHandler() {
        ExceptionHandlerMethodResolver resolver = resolverForTheAdvice();

        assertThat(resolver.resolveMethod(new IllegalArgumentException(CAUSE_MESSAGE)))
                .isEqualTo(handlerMethodFor(IllegalArgumentException.class))
                .isNotEqualTo(handlerMethodFor(Exception.class));
        assertThat(resolver.resolveMethod(new IllegalStateException(CAUSE_MESSAGE)))
                .isEqualTo(handlerMethodFor(Exception.class));
    }

    /**
     * Builds a request declaring one {@code Content-Type} header value.
     *
     * @param declaredContentType the raw header value, which need not be a valid media type
     * @return the request; never {@code null}
     */
    private static MockHttpServletRequest requestCarryingContentType(String declaredContentType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(declaredContentType);
        return request;
    }

    @Test
    @DisplayName("returns 406 for a request whose accept header cannot be satisfied")
    void returns406ForARequestWhoseAcceptHeaderCannotBeSatisfied() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleNotAcceptable(new HttpMediaTypeNotAcceptableException("none"));

        assertThat(response.getStatusCode().value()).isEqualTo(406);
        assertErrorEnvelope(response, NOT_ACCEPTABLE);
    }

    @Test
    @DisplayName("keeps the per-route 400 and 404 literals distinct from the framework 400 literal")
    void keepsThePerRouteLiteralsDistinctFromTheFrameworkLiteral() {
        assertThat(BAD_REQUEST)
                .isNotEqualTo(TWEET_ID_IS_REQUIRED)
                .isNotEqualTo(UPDATE_DATA_IS_REQUIRED)
                .isNotEqualTo(NO_VALUE_PROVIDED)
                .isNotEqualTo(NOT_FOUND)
                .isNotEqualTo(INTERNAL_SERVER_ERROR);
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
    @DisplayName("answers every handled exception with the single-key error object and no other key")
    void answersEveryHandledExceptionWithTheSingleKeyErrorObject() throws JsonProcessingException {
        for (ResponseEntity<ErrorResponse> response : allHandlerInvocations()) {
            assertSingleKeyErrorBody(response);
        }
    }

    @Test
    @DisplayName("answers every handled exception with an ErrorResponse body and never a ProblemDetail")
    void answersEveryHandledExceptionWithAnErrorResponseAndNeverAProblemDetail() {
        for (ResponseEntity<ErrorResponse> response : allHandlerInvocations()) {
            Object body = response.getBody();

            assertThat(body).isInstanceOf(ErrorResponse.class);
            assertThat(body).isNotInstanceOf(ProblemDetail.class);
        }
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

    @ParameterizedTest(name = "an error dispatch recording {0} returns {0} carrying {1}")
    @CsvSource({
        "400,Bad request",
        "404,Not found",
        "405,Method not allowed",
        "406,Not acceptable",
        "415,Unsupported media type",
        "500,Internal server error"
    })

    /**
     * Asserts that a response body is the single-key envelope carrying exactly the given message.
     *
     * @param response the response under test
     * @param expectedMessage the value the {@code error} key must hold
     * @throws JsonProcessingException if the body cannot be serialised
     */
    private void assertThatBodyCarriesOnly(
            ResponseEntity<ErrorResponse> response, String expectedMessage)
            throws JsonProcessingException {

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(expectedMessage);

        JsonNode body = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
        assertThat(body.properties()).hasSize(1);
        assertThat(body.get(ERROR_KEY).asText()).isEqualTo(expectedMessage);
        for (String absentKey : KEYS_ABSENT_FROM_EVERY_BODY) {
            assertThat(body.has(absentKey)).isFalse();
        }
    }

    @Test
    @DisplayName("handles every domain failure and every client failure Spring MVC raises")
    void handlesEveryDomainFailureAndEveryClientFailureSpringMvcRaises() {
        Set<Class<?>> handled = handledExceptionTypes();

        assertThat(handled).contains(
                NotFoundException.class,
                BadRequestException.class,
                ResponseGenerationException.class,
                MethodArgumentNotValidException.class,
                NoHandlerFoundException.class,
                NoResourceFoundException.class,
                HttpMessageNotReadableException.class,
                HttpMessageConversionException.class,
                HttpMessageNotWritableException.class,
                ServletRequestBindingException.class,
                MissingServletRequestPartException.class,
                TypeMismatchException.class,
                HttpRequestMethodNotSupportedException.class,
                HttpMediaTypeNotSupportedException.class,
                HttpMediaTypeNotAcceptableException.class,
                MultipartException.class,
                IllegalArgumentException.class,
                Exception.class);
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
        "405,Method not allowed",
        "406,Not acceptable",
        "415,Unsupported media type",
        "400,Bad request",
        "409,Bad request",
        "429,Bad request",
        "500,Internal server error",
        "502,Internal server error",
        "503,Internal server error"
    })
    @DisplayName("renders the single-key envelope for a dispatched status")
    void rendersTheSingleKeyEnvelopeForADispatchedStatus(int dispatchedStatus, String expected) {
        Map<String, Object> attributes = errorAttributesFor(dispatchedStatus);

        assertThat(attributes).containsExactly(entry("error", expected));
    }

    @ParameterizedTest
    @ValueSource(ints = {UNAUTHORIZED, FORBIDDEN})
    @DisplayName("renders no attribute for a dispatched 401 or 403")
    void rendersNoAttributeForADispatchedUnauthorizedOrForbidden(int dispatchedStatus) {
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
    @DisplayName("carries every attribute value from the closed literal set this advice declares")
    void carriesEveryAttributeValueFromTheClosedLiteralSet() {
        Set<String> rendered = new LinkedHashSet<>();
        for (int status = 400; status < 600; status++) {
            Object message = errorAttributesFor(status).get("error");
            if (message != null) {
                rendered.add(String.valueOf(message));
            }
        }

        assertThat(rendered).containsExactlyInAnyOrder(NOT_FOUND, INTERNAL_SERVER_ERROR,
                "Bad request", "Method not allowed", "Not acceptable", "Unsupported media type");
    }

    @Test
    @DisplayName("names an attribute source the framework error controller resolves")
    void namesAnAttributeSourceTheFrameworkErrorControllerResolves() {
        assertThat(errorAttributes()).isInstanceOf(DefaultErrorAttributes.class);
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
        invocations.add(handler.handleClientRequestFailure(malformedBody()));
        invocations.add(handler.handleClientRequestFailure(missingParameter()));
        invocations.add(handler.handleMessageConversionFailure(
                new HttpMessageConversionException(CAUSE_MESSAGE)));
        invocations.add(handler.handleMessageConversionFailure(conversionFailureReadingBody(
                defaultConverter(), UpdateSettingRequest.class,
                "{\"value\":\"first\",\"value\":\"second\"}")));
        invocations.add(handler.handleClientRequestFailure(conversionFailureReadingBody(
                strictDuplicateDetectionConverter(), UpdateSettingRequest.class,
                "{\"value\":\"first\",\"value\":\"second\"}")));
        invocations.add(handler.handleResponseWriteFailure(
                new HttpMessageNotWritableException(CAUSE_MESSAGE)));
        invocations.add(handler.handleMethodNotSupported(methodNotSupported()));
        invocations.add(handler.handleUnsupportedMediaType(unsupportedMediaType()));
        invocations.add(handler.handleMultipartFailure(new MultipartException(CAUSE_MESSAGE)));
        invocations.add(handler.handleIllegalArgument(new IllegalArgumentException(CAUSE_MESSAGE),
                requestCarryingContentType("*/*")));
        invocations.add(handler.handleIllegalArgument(new IllegalArgumentException(CAUSE_MESSAGE),
                requestCarryingContentType("application/json")));
        invocations.add(handler.handleNotAcceptable(new HttpMediaTypeNotAcceptableException("none")));
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

    /**
     * Attaches a capturing appender to the advice's own logger.
     *
     * @return the attached appender, already started
     */
    private static ListAppender<ILoggingEvent> captureAdviceLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adviceLogger().addAppender(appender);
        return appender;
    }

    /**
     * Detaches a capturing appender from the advice's own logger.
     *
     * @param appender the appender to detach
     */
    private static void releaseAdviceLog(ListAppender<ILoggingEvent> appender) {
        adviceLogger().detachAppender(appender);
        appender.stop();
    }

    /**
     * Returns the advice's own logger.
     *
     * @return the logger {@link GlobalExceptionHandler} writes to
     */
    private static ch.qos.logback.classic.Logger adviceLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
