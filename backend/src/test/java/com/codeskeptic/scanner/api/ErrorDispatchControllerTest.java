package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.codeskeptic.scanner.dto.ErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

// Net-new (no Python counterpart) — DL-183 — see docs/DECISION_LOG.md
/**
 * Exercises the error-page controller that answers the servlet {@code ERROR} dispatch.
 *
 * <p>The outer class drives the controller directly, which is where the whole status-to-message map is
 * covered. The nested slice drives it through the real {@code DispatcherServlet} so the mapped path,
 * the JSON rendering and the withdrawal of Spring Boot's own error controller are observed rather than
 * inferred.
 */
@DisplayName("ErrorDispatchController")
class ErrorDispatchControllerTest {

    private static final String ERROR_PATH = "/error";

    private static final String NOT_FOUND = "Not found";

    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private static final String BAD_REQUEST = "Bad request";

    private static final String METHOD_NOT_ALLOWED = "Method not allowed";

    private static final String NOT_ACCEPTABLE = "Not acceptable";

    private static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported media type";

    private static final String ERROR_KEY = "error";

    /** Keys Spring Boot's own error body carries and this envelope must not. */
    private static final List<String> KEYS_ABSENT_FROM_EVERY_BODY = List.of(
            "timestamp", "status", "path", "type", "title", "detail", "instance", "message", "errors",
            "trace", "exception");

    /** Header names Spring Security writes on a {@code REQUEST} dispatch. */
    private static final List<String> RESTATED_SECURITY_HEADERS = List.of(
            "X-Content-Type-Options", "X-Frame-Options", "X-XSS-Protection",
            HttpHeaders.CACHE_CONTROL, HttpHeaders.PRAGMA, HttpHeaders.EXPIRES);

    private ErrorDispatchController controller;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        controller = new ErrorDispatchController();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("implements the marker interface that withdraws Spring Boot's error controller")
    void implementsTheMarkerInterfaceThatWithdrawsSpringBootsErrorController() {
        assertThat(controller).isInstanceOf(ErrorController.class);
    }

    @Test
    @DisplayName("returns 404 with the Not found envelope when the path is requested directly")
    void returns404WithNotFoundEnvelopeWhenThePathIsRequestedDirectly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ERROR_PATH);
        request.setDispatcherType(DispatcherType.REQUEST);

        ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertSingleKeyEnvelope(response, NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
    @DisplayName("returns 404 for a direct request whatever the method")
    void returns404ForADirectRequestWhateverTheMethod(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, ERROR_PATH);
        request.setDispatcherType(DispatcherType.REQUEST);

        ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertSingleKeyEnvelope(response, NOT_FOUND);
    }

    @ParameterizedTest
    @CsvSource({
            "400, Bad request",
            "404, Not found",
            "405, Method not allowed",
            "406, Not acceptable",
            "415, Unsupported media type",
            "402, Bad request",
            "409, Bad request",
            "410, Bad request",
            "413, Bad request",
            "414, Bad request",
            "422, Bad request",
            "429, Bad request",
            "431, Bad request",
            "499, Bad request",
    })
    @DisplayName("keeps the dispatched 4xx status and carries the literal it maps to")
    void keepsTheDispatched4xxStatusAndCarriesTheMappedLiteral(int dispatched, String expected)
            throws Exception {

        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(dispatched));

        assertThat(response.getStatusCode().value()).isEqualTo(dispatched);
        assertSingleKeyEnvelope(response, expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501, 502, 503, 504, 505, 507, 599})
    @DisplayName("reports 500 with the Internal server error envelope for a dispatched 5xx status")
    void reports500WithInternalServerErrorEnvelopeForADispatched5xxStatus(int dispatched)
            throws Exception {

        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(dispatched));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertSingleKeyEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("keeps a dispatched 401 or 403 bare, matching the security chain")
    void keepsADispatched401Or403Bare(int dispatched) {
        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(dispatched));

        assertThat(response.getStatusCode().value()).isEqualTo(dispatched);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getContentType()).isNull();
        assertThat(response.getHeaders().keySet()).containsAll(RESTATED_SECURITY_HEADERS);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204, 302, 304, 399})
    @DisplayName("reports 500 when the dispatched status is not an error status")
    void reports500WhenTheDispatchedStatusIsNotAnErrorStatus(int dispatched) throws Exception {
        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(dispatched));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertSingleKeyEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("reports 500 when the dispatch carries no recorded status")
    void reports500WhenTheDispatchCarriesNoRecordedStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ERROR_PATH);
        request.setDispatcherType(DispatcherType.ERROR);

        ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertSingleKeyEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("reports 500 when the recorded status is not an integer")
    void reports500WhenTheRecordedStatusIsNotAnInteger() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ERROR_PATH);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, "400");

        ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertSingleKeyEnvelope(response, INTERNAL_SERVER_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/settings/AAA%25BBB",
            "/settings/%25",
            "/settings/x%27%3B%20DROP%20TABLE%20settings%3B--",
            "/settings/..%252F..%252Fetc",
            "/settings/<img src=x onerror=alert(1)>",
    })
    @DisplayName("never copies the failed request path into the body")
    void neverCopiesTheFailedRequestPathIntoTheBody(String failedPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", ERROR_PATH);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, failedPath);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "rejected " + failedPath);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(failedPath));

        ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertSingleKeyEnvelope(response, BAD_REQUEST);
        assertThat(objectMapper.writeValueAsString(response.getBody())).doesNotContain(failedPath);
    }

    @Test
    @DisplayName("carries no message text of its own beyond the six shared literals")
    void carriesNoMessageTextOfItsOwnBeyondTheSixSharedLiterals() throws Exception {
        List<String> emitted = new java.util.ArrayList<>();
        for (int status : new int[] {400, 401, 403, 404, 405, 406, 415, 418, 500, 503}) {
            ErrorResponse body = controller.handleError(errorDispatch(status)).getBody();
            if (body != null) {
                emitted.add(body.error());
            }
        }
        emitted.add(controller.handleError(directRequest()).getBody().error());

        assertThat(emitted).isNotEmpty().allSatisfy(message -> assertThat(message)
                .isIn(NOT_FOUND, INTERNAL_SERVER_ERROR, BAD_REQUEST, METHOD_NOT_ALLOWED,
                        NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    @DisplayName("pins the body to application/json so an Accept header cannot select another type")
    void pinsTheBodyToApplicationJson() {
        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(400));

        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
    }

    @Test
    @DisplayName("restates the security header set on every response it builds")
    void restatesTheSecurityHeaderSetOnEveryResponseItBuilds() {
        ResponseEntity<ErrorResponse> response = controller.handleError(errorDispatch(400));

        assertThat(response.getHeaders().keySet()).containsAll(RESTATED_SECURITY_HEADERS);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("X-XSS-Protection")).isEqualTo("0");
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst(HttpHeaders.EXPIRES)).isEqualTo("0");
    }

    private MockHttpServletRequest errorDispatch(int recordedStatus) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ERROR_PATH);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, recordedStatus);
        return request;
    }

    private MockHttpServletRequest directRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ERROR_PATH);
        request.setDispatcherType(DispatcherType.REQUEST);
        return request;
    }

    private void assertSingleKeyEnvelope(ResponseEntity<ErrorResponse> response, String expected)
            throws Exception {

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(expected);
        JsonNode body = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
        assertThat(body.properties()).hasSize(1);
        assertThat(body.get(ERROR_KEY).asText()).isEqualTo(expected);
        KEYS_ABSENT_FROM_EVERY_BODY.forEach(absent -> assertThat(body.has(absent)).isFalse());
    }

    /**
     * Drives the controller through the real {@code DispatcherServlet}.
     *
     * <p>The security filters are switched off because this slice asserts the error rendering and not
     * the filter chain; the chain's own behaviour on this path is covered by {@code AuthControllerTest}.
     */
    @Nested
    @WebMvcTest(ErrorDispatchController.class)
    @AutoConfigureMockMvc(addFilters = false)
    @ActiveProfiles("test")
    @DisplayName("through the DispatcherServlet")
    class ThroughTheDispatcherServlet {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("withdraws Spring Boot's BasicErrorController from the context")
        void withdrawsSpringBootsBasicErrorControllerFromTheContext() {
            assertThat(applicationContext.getBeansOfType(BasicErrorController.class)).isEmpty();
            assertThat(applicationContext.getBeansOfType(ErrorController.class).values())
                    .singleElement()
                    .isInstanceOf(ErrorDispatchController.class);
        }

        @Test
        @DisplayName("answers a direct request with 404 and the Not found envelope")
        void answersADirectRequestWith404AndTheNotFoundEnvelope() throws Exception {
            mockMvc.perform(get(ERROR_PATH))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value(NOT_FOUND))
                    .andExpect(jsonPath("$.timestamp").doesNotExist())
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.path").doesNotExist())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test
        @DisplayName("writes JSON for a direct request asking for text/html")
        void writesJsonForADirectRequestAskingForTextHtml() throws Exception {
            MvcResult result = mockMvc.perform(get(ERROR_PATH).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value(NOT_FOUND))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Whitelabel", "<html", "<body");
        }

        @ParameterizedTest
        @CsvSource({
                "400, 400, Bad request",
                "404, 404, Not found",
                "405, 405, Method not allowed",
                "406, 406, Not acceptable",
                "415, 415, Unsupported media type",
                "500, 500, Internal server error",
                "503, 500, Internal server error",
        })
        @DisplayName("renders the envelope for a dispatched status")
        void rendersTheEnvelopeForADispatchedStatus(int dispatched, int expectedStatus,
                String expectedMessage) throws Exception {

            mockMvc.perform(put(ERROR_PATH)
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, dispatched)
                            .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/settings/AAA%25BBB")
                            .with(request -> {
                                request.setDispatcherType(DispatcherType.ERROR);
                                return request;
                            }))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value(expectedMessage))
                    .andExpect(jsonPath("$.timestamp").doesNotExist())
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.path").doesNotExist());
        }

        @Test
        @DisplayName("answers a dispatched 401 with no body at all")
        void answersADispatched401WithNoBodyAtAll() throws Exception {
            MvcResult result = mockMvc.perform(get(ERROR_PATH)
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401)
                            .with(request -> {
                                request.setDispatcherType(DispatcherType.ERROR);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
    }
}
