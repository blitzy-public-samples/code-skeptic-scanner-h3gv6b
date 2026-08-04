package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.codeskeptic.scanner.dto.ErrorResponse;
import com.codeskeptic.scanner.dto.LoginRequest;
import com.codeskeptic.scanner.dto.UpdateSettingRequest;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;

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
    @DisplayName("returns 400 and no mapped literal when the rejected field name is outside the map")
    void returns400AndNoMappedLiteralForAFieldOutsideTheMap(String rejectedField)
            throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureOn(rejectedField));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelopeWithoutMessage(response);
        assertThat(response.getBody().error()).isNotEqualTo(TWEET_ID_IS_REQUIRED);
        assertThat(response.getBody().error()).isNotEqualTo(NO_VALUE_PROVIDED);
        assertThat(envelopeOf(response).toString()).doesNotContain(rejectedField);
    }

    @Test
    @DisplayName("returns 400 with no message when the binding result carries no field error")
    void returns400WithNoMessageWhenNoFieldErrorIsPresent() throws JsonProcessingException {
        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentNotValid(validationFailureWithoutFieldErrors());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelopeWithoutMessage(response);
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

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("bodiesRepeatingARecordComponent")
    @DisplayName("answers a body repeating a record component with 400 and never 500")
    void answersABodyRepeatingARecordComponentWith400AndNever500(
            Class<?> targetType, String body) throws JsonProcessingException {

        HttpMessageConversionException raised = conversionFailureReadingBody(targetType, body);

        assertThat(raised).isExactlyInstanceOf(HttpMessageConversionException.class);
        assertThat(raised).isNotInstanceOf(HttpMessageNotReadableException.class);
        assertThat(raised.getCause()).isInstanceOf(InvalidDefinitionException.class);

        Method resolved = resolverForTheAdvice().resolveMethod(raised);
        assertThat(resolved)
                .isNotNull()
                .isEqualTo(handlerMethodFor(HttpMessageConversionException.class));

        ResponseEntity<ErrorResponse> response = handler.handleMessageConversionFailure(raised);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertErrorEnvelope(response, BAD_REQUEST);
        assertThat(envelopeOf(response).toString()).doesNotContain("password");
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
     * Reads {@code body} onto {@code targetType} through the framework's own Jackson converter and
     * returns the conversion failure it raises.
     *
     * @param targetType the record the body is bound onto
     * @param body       the request body, as received
     * @return the raised exception
     */
    private static HttpMessageConversionException conversionFailureReadingBody(
            Class<?> targetType, String body) {

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        MockHttpInputMessage message =
                new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
        message.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        return (HttpMessageConversionException) assertThatThrownBy(
                () -> converter.read(targetType, null, message))
                        .isInstanceOf(HttpMessageConversionException.class)
                        .actual();
    }

    private static ExceptionHandlerMethodResolver resolverForTheAdvice() {
        return new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
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
                Exception.class);
    }

    @Test
    @DisplayName("declares no handler for an authentication failure or an access-denied failure")
    void declaresNoHandlerForAnAuthenticationOrAccessDeniedFailure() {
        Set<Class<?>> handled = handledExceptionTypes();

        assertThat(handled).doesNotContain(AuthenticationException.class, AccessDeniedException.class);
        for (Class<?> handledType : handled) {
            assertThat(AuthenticationException.class.isAssignableFrom(handledType)).isFalse();
            assertThat(AccessDeniedException.class.isAssignableFrom(handledType)).isFalse();
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
                UpdateSettingRequest.class, "{\"value\":\"first\",\"value\":\"second\"}")));
        invocations.add(handler.handleResponseWriteFailure(
                new HttpMessageNotWritableException(CAUSE_MESSAGE)));
        invocations.add(handler.handleMethodNotSupported(methodNotSupported()));
        invocations.add(handler.handleUnsupportedMediaType(unsupportedMediaType()));
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

    private void assertErrorEnvelopeWithoutMessage(ResponseEntity<ErrorResponse> response)
            throws JsonProcessingException {
        assertSingleKeyErrorBody(response);

        assertThat(response.getBody().error()).isNull();
        assertThat(envelopeOf(response).get(ERROR_KEY).isNull()).isTrue();
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
