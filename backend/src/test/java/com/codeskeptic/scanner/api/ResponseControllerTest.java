package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.CreateResponseRequest;
import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.UpdateResponseRequest;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.ResponseService;

import jakarta.validation.Constraint;

// Ported from backend/tests/test_api.py (faithful port) — see docs/DECISION_LOG.md
/**
 * Exercises the four routes {@link ResponseController} serves, behind the application's servlet
 * security chain.
 *
 * <p>The routes and their statuses are those of {@code backend/app/api/responses.py}:
 * {@code GET /responses} at {@code :L8-20}, {@code GET /responses/<response_id>} at {@code :L22-31},
 * {@code POST /responses} at {@code :L33-49} and {@code PUT /responses/<response_id>} at
 * {@code :L51-65}. The retired suite reached this resource at {@code backend/tests/test_api.py:L25-33}
 * with {@code fastapi.testclient.TestClient} against a Flask application and carried no
 * authentication header.
 *
 * <p>The chain in this slice is the application's own: {@code security.SecurityConfig},
 * {@code config.CorsConfig} and {@code security.JwtService} are imported. Each route is reached with
 * a bearer token the real {@link JwtService} mints, and each route is also reached without one.
 * {@link GlobalExceptionHandler} is a {@code @RestControllerAdvice} and is part of every web slice.
 * Every error envelope asserted below is the envelope the application serves.
 *
 * <p>{@code service.ResponseService} is the single mocked collaborator. No test here reaches OpenAI,
 * Notion, X or a database.
 *
 * <p>The six wire literals asserted character-for-character are {@code Response not found}
 * ({@code backend/app/api/responses.py:L31}), {@code Tweet ID is required} ({@code :L41}),
 * {@code Failed to generate response} ({@code :L49}), {@code Update data is required}
 * ({@code :L57}), {@code Response not found or update failed} ({@code :L65}) and
 * {@code Internal server error} ({@code backend/app/main.py:L37}).
 *
 * <p>Decisions covered by the assertions here are recorded in {@code docs/DECISION_LOG.md} DL-021,
 * DL-022, DL-023, DL-038, DL-048, DL-050, DL-059, DL-076, DL-082, DL-092, DL-123, DL-217,
 * DL-225, DL-231 and DL-286; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 */
@WebMvcTest(ResponseController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("ResponseController")
class ResponseControllerTest {

    /** Principal named by {@code scanner.auth.username} under the {@code test} profile. */
    private static final String PRINCIPAL = "admin";

    /** Identifier the single-row routes address. */
    private static final String RESPONSE_ID = "1";

    /** Path segment carrying no number, accepted by Flask's default converter at {@code :L22}. */
    private static final String UNPARSEABLE_ID = "not-a-number";

    /** Identifier of the {@code tweets} row {@code POST /responses} names. */
    private static final String TWEET_ID = "7";

    /** Wire value of {@code responses.content} in every fixture row. */
    private static final String CONTENT = "A generated reply";

    /** Wire value of {@code responses.generated_at} in every fixture row. */
    private static final String GENERATED_AT = "2026-01-31T09:15:30";

    /** Wire literal of {@code backend/app/api/responses.py:L31}. */
    private static final String RESPONSE_NOT_FOUND = "{\"error\":\"Response not found\"}";

    /** Wire literal of {@code backend/app/api/responses.py:L65}. */
    private static final String UPDATE_FAILED =
            "{\"error\":\"Response not found or update failed\"}";

    /** Wire literal of {@code backend/app/api/responses.py:L41}. */
    private static final String TWEET_ID_REQUIRED = "{\"error\":\"Tweet ID is required\"}";

    /** Wire literal of {@code backend/app/api/responses.py:L49}. */
    private static final String GENERATION_FAILED =
            "{\"error\":\"Failed to generate response\"}";

    /** Wire literal of {@code backend/app/api/responses.py:L57}. */
    private static final String UPDATE_DATA_REQUIRED = "{\"error\":\"Update data is required\"}";

    /** Wire literal of {@code backend/app/main.py:L37}. */
    private static final String INTERNAL_SERVER_ERROR = "{\"error\":\"Internal server error\"}";

    /** Message the advice serves for a body the converter rejected — DL-092. */
    private static final String BAD_REQUEST = "{\"error\":\"Bad request\"}";

    /** The single member name of every error envelope — {@code backend/app/main.py:L31-37}. */
    private static final String ERROR_KEY = "error";

    /** The sanctioned envelope of an unmatched path — backend/app/main.py:L31-33, DL-183. */
    private static final String NOT_FOUND_BODY = "{\"error\":\"Not found\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ResponseService responseService;

    // -------------------------------------------------------------------------
    // GET /responses — pagination parity with request.args.get(..., type=int)
    // -------------------------------------------------------------------------

    // backend/app/api/responses.py:L11-12 — the declared defaults are 1 and 10
    @Test
    @DisplayName("reads page 1 of 10 when the request carries no pagination parameter")
    void readsPage1Of10WhenTheRequestCarriesNoPaginationParameter() throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt())).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10))
                .andExpect(jsonPath("$.responses").isArray());

        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> perPage = ArgumentCaptor.forClass(Integer.class);
        verify(responseService).getPaginatedResponses(page.capture(), perPage.capture());
        assertThat(page.getValue()).isEqualTo(1);
        assertThat(perPage.getValue()).isEqualTo(10);
    }

    // backend/app/api/responses.py:L11-12 — a carried value reaches the service unchanged
    @ParameterizedTest(name = "[{index}] page={0} per_page={1}")
    @CsvSource({ "4,50", "2,5" })
    @DisplayName("reads the page and size the request carries")
    void readsThePageAndSizeTheRequestCarries(int page, int perPage) throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt()))
                .thenReturn(emptyPage(page, perPage));

        mockMvc.perform(get("/responses")
                        .param("page", String.valueOf(page))
                        .param("per_page", String.valueOf(perPage))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(page))
                .andExpect(jsonPath("$.pagination.per_page").value(perPage));

        ArgumentCaptor<Integer> readPage = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> readPerPage = ArgumentCaptor.forClass(Integer.class);
        verify(responseService).getPaginatedResponses(readPage.capture(), readPerPage.capture());
        assertThat(readPage.getValue()).isEqualTo(page);
        assertThat(readPerPage.getValue()).isEqualTo(perPage);
    }

    // The wire name is per_page — DL-059 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] name={0}")
    @ValueSource(strings = { "perPage", "PerPage", "per-page", "perpage" })
    @DisplayName("reads a size of 10 when the request spells the size parameter otherwise")
    void readsASizeOf10WhenTheRequestSpellsTheSizeParameterOtherwise(String parameterName)
            throws Exception {

        when(responseService.getPaginatedResponses(anyInt(), anyInt())).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/responses")
                        .param(parameterName, "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.per_page").value(10));

        ArgumentCaptor<Integer> perPage = ArgumentCaptor.forClass(Integer.class);
        verify(responseService).getPaginatedResponses(anyInt(), perPage.capture());
        assertThat(perPage.getValue()).isEqualTo(10);
    }

    // backend/app/api/responses.py:L17-20 — the envelope carries two members
    @Test
    @DisplayName("renders the list body with exactly the responses and pagination members")
    void rendersTheListBodyWithExactlyTheResponsesAndPaginationMembers() throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt())).thenReturn(onePage());

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.responses").isArray())
                .andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.pagination").exists())
                .andExpect(jsonPath("$.tweets").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    // Pagination member names — DL-038 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("renders the pagination member with exactly page, per_page, total and total_pages")
    void rendersThePaginationMemberWithExactlyFourSnakeCaseKeys() throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt())).thenReturn(onePage());

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.*", hasSize(4)))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10))
                .andExpect(jsonPath("$.pagination.total").value(1))
                .andExpect(jsonPath("$.pagination.total_pages").value(1))
                .andExpect(jsonPath("$.pagination.number").doesNotExist())
                .andExpect(jsonPath("$.pagination.size").doesNotExist())
                .andExpect(jsonPath("$.pagination.numberOfElements").doesNotExist())
                .andExpect(jsonPath("$.pagination.totalElements").doesNotExist())
                .andExpect(jsonPath("$.pagination.totalPages").doesNotExist())
                .andExpect(jsonPath("$.pagination.perPage").doesNotExist())
                .andExpect(jsonPath("$.pagination.first").doesNotExist())
                .andExpect(jsonPath("$.pagination.last").doesNotExist())
                .andExpect(jsonPath("$.pagination.empty").doesNotExist())
                .andExpect(jsonPath("$.pagination.sort").doesNotExist())
                .andExpect(jsonPath("$.pagination.pageable").doesNotExist());
    }

    // Wire member names and identifier types — DL-022, DL-023 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("renders each listed row with exactly the five snake_case keys")
    void rendersEachListedRowWithExactlyTheFiveSnakeCaseKeys() throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt())).thenReturn(onePage());

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].*", hasSize(5)))
                .andExpect(jsonPath("$.responses[0].id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.responses[0].content").value(CONTENT))
                .andExpect(jsonPath("$.responses[0].generated_at").value(GENERATED_AT))
                .andExpect(jsonPath("$.responses[0].is_approved").value(false))
                .andExpect(jsonPath("$.responses[0].tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.responses[0].id", instanceOf(String.class)))
                .andExpect(jsonPath("$.responses[0].tweet_id", instanceOf(String.class)))
                .andExpect(jsonPath("$.responses[0].generated_at", instanceOf(String.class)))
                .andExpect(jsonPath("$.responses[0].is_approved", instanceOf(Boolean.class)))
                .andExpect(jsonPath("$.responses[0].generatedAt").doesNotExist())
                .andExpect(jsonPath("$.responses[0].isApproved").doesNotExist())
                .andExpect(jsonPath("$.responses[0].approved").doesNotExist())
                .andExpect(jsonPath("$.responses[0].tweetId").doesNotExist())
                .andExpect(jsonPath("$.responses[0].tweet").doesNotExist());
    }

    // A page beyond the queryable offset is answered — DL-217, DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] page={0}")
    @ValueSource(ints = {2147483647, 2147483646, 214748366})
    @DisplayName("answers 200 and passes an out-of-range page through to the service unchanged")
    void answers200AndPassesAnOutOfRangePageThroughToTheServiceUnchanged(int page) throws Exception {
        when(responseService.getPaginatedResponses(page, 10)).thenReturn(emptyPage(page, 10));

        mockMvc.perform(get("/responses").param("page", String.valueOf(page))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses").isArray())
                .andExpect(jsonPath("$.pagination.page").value(page));

        verify(responseService).getPaginatedResponses(page, 10);
    }

    // request.args.get(..., type=int) answers with the default — DL-217 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] page={0}")
    @ValueSource(strings = {"abc", "", " ", "3.5", "0x10", "99999999999999999999", "--3"})
    @DisplayName("answers 200 with the declared default when page does not convert to an integer")
    void answers200WithTheDeclaredDefaultWhenPageDoesNotConvertToAnInteger(String page)
            throws Exception {

        when(responseService.getPaginatedResponses(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/responses").param("page", page)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).getPaginatedResponses(1, 10);
    }

    @ParameterizedTest(name = "[{index}] per_page={0}")
    @ValueSource(strings = {"abc", "", " ", "2.5"})
    @DisplayName("answers 200 with the declared default when per_page does not convert to an integer")
    void answers200WithTheDeclaredDefaultWhenPerPageDoesNotConvertToAnInteger(String perPage)
            throws Exception {

        when(responseService.getPaginatedResponses(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/responses").param("per_page", perPage)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).getPaginatedResponses(1, 10);
    }

    @Test
    @DisplayName("passes a negative page on to the service unclamped")
    void passesANegativePageOnToTheServiceUnclamped() throws Exception {
        when(responseService.getPaginatedResponses(-2, 10)).thenReturn(emptyPage(-2, 10));

        mockMvc.perform(get("/responses").param("page", "-2")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).getPaginatedResponses(-2, 10);
    }

    // backend/app/main.py:L35-37 — the generic 500 envelope
    @Test
    @DisplayName("answers 500 with the internal server error envelope when the read fails")
    void answers500WithTheInternalServerErrorEnvelopeWhenTheReadFails() throws Exception {
        when(responseService.getPaginatedResponses(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("the responses table could not be read"));

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error").value("Internal server error"))
                .andExpect(jsonPath("$.responses").doesNotExist())
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // GET /responses/{responseId} — backend/app/api/responses.py:L22-31
    // -------------------------------------------------------------------------

    // backend/app/api/responses.py:L29 — the row is rendered unwrapped
    @Test
    @DisplayName("renders the addressed row unwrapped with exactly the five snake_case keys")
    void rendersTheAddressedRowUnwrappedWithExactlyTheFiveSnakeCaseKeys() throws Exception {
        when(responseService.getResponseById(RESPONSE_ID)).thenReturn(response(false));

        mockMvc.perform(get("/responses/" + RESPONSE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.content").value(CONTENT))
                .andExpect(jsonPath("$.generated_at").value(GENERATED_AT))
                .andExpect(jsonPath("$.is_approved").value(false))
                .andExpect(jsonPath("$.tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.id", instanceOf(String.class)))
                .andExpect(jsonPath("$.tweet_id", instanceOf(String.class)))
                .andExpect(jsonPath("$.generatedAt").doesNotExist())
                .andExpect(jsonPath("$.isApproved").doesNotExist())
                .andExpect(jsonPath("$.approved").doesNotExist())
                .andExpect(jsonPath("$.tweetId").doesNotExist())
                .andExpect(jsonPath("$.responses").doesNotExist())
                .andExpect(jsonPath("$.pagination").doesNotExist());

        verify(responseService).getResponseById(RESPONSE_ID);
        verifyNoMoreInteractions(responseService);
    }

    // backend/app/api/responses.py:L31 — the 404 literal of this route
    @ParameterizedTest(name = "[{index}] responseId={0}")
    @ValueSource(strings = {"1", "not-a-number", "9999999999999999999999"})
    @DisplayName("answers 404 with the Response not found envelope when the row is absent")
    void answers404WithTheResponseNotFoundEnvelopeWhenTheRowIsAbsent(String responseId)
            throws Exception {

        when(responseService.getResponseById(responseId))
                .thenThrow(NotFoundException.responseNotFound());

        mockMvc.perform(get("/responses/" + responseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(RESPONSE_NOT_FOUND, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error").value(NotFoundException.RESPONSE_NOT_FOUND));
    }

    // A path segment carrying no number is a row that is absent — DL-048 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("answers 404, not 400 and not 500, when the read path segment carries no number")
    void answers404WhenTheReadPathSegmentCarriesNoNumber() throws Exception {
        when(responseService.getResponseById(UNPARSEABLE_ID))
                .thenThrow(NotFoundException.responseNotFound());

        mockMvc.perform(get("/responses/" + UNPARSEABLE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST))
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR))
                .andExpect(content().json(RESPONSE_NOT_FOUND, JsonCompareMode.STRICT));

        ArgumentCaptor<String> readIdentifier = ArgumentCaptor.forClass(String.class);
        verify(responseService).getResponseById(readIdentifier.capture());
        assertThat(readIdentifier.getValue())
                .isInstanceOf(String.class)
                .isEqualTo(UNPARSEABLE_ID);
    }

    // backend/app/main.py:L35-37 — the generic 500 envelope
    @Test
    @DisplayName("answers 500 with the internal server error envelope when the single read fails")
    void answers500WithTheInternalServerErrorEnvelopeWhenTheSingleReadFails() throws Exception {
        when(responseService.getResponseById(RESPONSE_ID))
                .thenThrow(new RuntimeException("the responses row could not be read"));

        mockMvc.perform(get("/responses/" + RESPONSE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)));
    }

    // -------------------------------------------------------------------------
    // POST /responses — backend/app/api/responses.py:L33-49, statuses 400, 201, 500
    // -------------------------------------------------------------------------

    // backend/app/api/responses.py:L47 — the success status of this route is 201
    @Test
    @DisplayName("answers 201, and not 200, carrying the generated row")
    void answers201AndNot200CarryingTheGeneratedRow() throws Exception {
        when(responseService.generateResponse(TWEET_ID)).thenReturn(response(false));

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + TWEET_ID + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isCreated())
                .andExpect(statusIsNot(HttpStatus.OK))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.content").value(CONTENT))
                .andExpect(jsonPath("$.generated_at").value(GENERATED_AT))
                .andExpect(jsonPath("$.is_approved").value(false))
                .andExpect(jsonPath("$.tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.id", instanceOf(String.class)))
                .andExpect(jsonPath("$.tweet_id", instanceOf(String.class)))
                .andExpect(jsonPath("$.isApproved").doesNotExist())
                .andExpect(jsonPath("$.approved").doesNotExist());

        ArgumentCaptor<String> generatedFor = ArgumentCaptor.forClass(String.class);
        verify(responseService).generateResponse(generatedFor.capture());
        assertThat(generatedFor.getValue()).isEqualTo(TWEET_ID);
        // No publish operation is reached — IR7 — see docs/DECISION_LOG.md
        verifyNoMoreInteractions(responseService);
    }

    // backend/app/db/models.py:L26 — the is_approved column of a freshly generated row
    @Test
    @DisplayName("renders is_approved as the JSON boolean false on a freshly generated row")
    void rendersIsApprovedAsTheJsonBooleanFalseOnAFreshlyGeneratedRow() throws Exception {
        when(responseService.generateResponse(TWEET_ID)).thenReturn(response(false));

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + TWEET_ID + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_approved", instanceOf(Boolean.class)))
                .andExpect(jsonPath("$.is_approved").value(false));

        verify(responseService).generateResponse(TWEET_ID);
        // No publish operation is reached — IR7 — see docs/DECISION_LOG.md
        verifyNoMoreInteractions(responseService);
    }

    // backend/app/api/responses.py:L40-41 — every value the guard `if not tweet_id` read as false —
    // DL-286 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] body={0}")
    @ValueSource(strings = {
        "{}",
        "{\"tweet_id\":null}",
        "{\"tweet_id\":\"\"}",
        "{\"tweet_id\":0}",
        "{\"tweet_id\":-0}",
        "{\"tweet_id\":0.0}",
        "{\"tweet_id\":-0.0}",
        "{\"tweet_id\":0.00}",
        "{\"tweet_id\":0e0}",
        "{\"tweet_id\":false}",
        "{\"tweet_id\":[]}",
        "{\"tweet_id\":{}}"
    })
    @DisplayName("answers 400 with the Tweet ID is required envelope when the body names no tweet")
    void answers400WithTheTweetIdIsRequiredEnvelopeWhenTheBodyNamesNoTweet(String body)
            throws Exception {

        // The service reports the same literal for the normalised absent identifier as the constraint
        // reports for the omitted member — backend/app/api/responses.py:L40-41 — DL-286
        when(responseService.generateResponse(null)).thenThrow(BadRequestException.tweetIdRequired());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(TWEET_ID_REQUIRED, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error").value(BadRequestException.TWEET_ID_IS_REQUIRED))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.tweet_id").doesNotExist())
                .andExpect(jsonPath("$.tweetId").doesNotExist())
                .andExpect(bodyDoesNotContain("must not be null"))
                .andExpect(bodyDoesNotContain("tweetId"))
                .andExpect(bodyDoesNotContain("NotNull"));

        verify(responseService, never()).generateResponse(anyString());
    }

    // backend/app/api/responses.py:L40 — a value the guard read as false never reaches the identifier
    // parser or the generator — DL-286 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] body={0}")
    @ValueSource(strings = {
        "{\"tweet_id\":\"\"}",
        "{\"tweet_id\":0}",
        "{\"tweet_id\":0.0}",
        "{\"tweet_id\":false}",
        "{\"tweet_id\":[]}",
        "{\"tweet_id\":{}}"
    })
    @DisplayName("carries no identifier to the generator for a value the source guard rejected")
    void carriesNoIdentifierToTheGeneratorForAValueTheSourceGuardRejected(String body)
            throws Exception {

        when(responseService.generateResponse(null)).thenThrow(BadRequestException.tweetIdRequired());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(TWEET_ID_REQUIRED, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error").value(BadRequestException.TWEET_ID_IS_REQUIRED));

        ArgumentCaptor<String> generatedFor = ArgumentCaptor.forClass(String.class);
        verify(responseService).generateResponse(generatedFor.capture());
        assertThat(generatedFor.getValue()).as("identifier handed to the generator").isNull();
    }

    // backend/app/api/responses.py:L40,L49 — a value the guard read as true reaches generation, so one
    // that names no row answers the 500 literal and never the guard's 400 — DL-286 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] body={0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "{\"tweet_id\":7}                  | 7",
        "{\"tweet_id\":\"7\"}              | 7",
        "{\"tweet_id\":0.5}                | 0.5",
        "{\"tweet_id\":true}               | true",
        "{\"tweet_id\":\" \"}              | ' '",
        "{\"tweet_id\":[1]}                | [1]",
        "{\"tweet_id\":{\"a\":1}}           | {\"a\":1}"
    })
    @DisplayName("carries a value the source guard accepted to the generator unchanged")
    void carriesAValueTheSourceGuardAcceptedToTheGeneratorUnchanged(String body, String expected)
            throws Exception {

        when(responseService.generateResponse(expected)).thenThrow(new ResponseGenerationException());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST))
                .andExpect(content().json(GENERATION_FAILED, JsonCompareMode.STRICT))
                .andExpect(bodyDoesNotContain(BadRequestException.TWEET_ID_IS_REQUIRED));

        verify(responseService).generateResponse(expected);
    }

    // backend/app/api/responses.py:L49 — the 500 literal of this route
    @Test
    @DisplayName("answers 500 with the Failed to generate response envelope when generation fails")
    void answers500WithTheFailedToGenerateResponseEnvelopeWhenGenerationFails() throws Exception {
        when(responseService.generateResponse(TWEET_ID)).thenThrow(new ResponseGenerationException());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + TWEET_ID + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(GENERATION_FAILED, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error")
                        .value(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE))
                .andExpect(bodyDoesNotContain("Internal server error"));
    }

    // This route declares no 404 branch — DL-076 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] tweet_id={0}")
    @ValueSource(strings = {"does-not-exist", "not-a-number", "999999999999999999999"})
    @DisplayName("answers 500, and not 404, when no row can be generated for the named tweet")
    void answers500AndNot404WhenNoRowCanBeGeneratedForTheNamedTweet(String tweetId)
            throws Exception {

        when(responseService.generateResponse(tweetId)).thenThrow(new ResponseGenerationException());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + tweetId + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(statusIsNot(HttpStatus.NOT_FOUND))
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST))
                .andExpect(content().json(GENERATION_FAILED, JsonCompareMode.STRICT))
                .andExpect(bodyDoesNotContain("Response not found"));

        verify(responseService).generateResponse(tweetId);
    }

    // -------------------------------------------------------------------------
    // PUT /responses/{responseId} — backend/app/api/responses.py:L51-65, statuses 400, 200, 404
    // -------------------------------------------------------------------------

    // backend/app/api/responses.py:L63 — the success status of this route is 200
    @Test
    @DisplayName("answers 200 carrying the stored row and forwards both written values")
    void answers200CarryingTheStoredRowAndForwardsBothWrittenValues() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(true));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\",\"is_approved\":true}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.CREATED))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.content").value(CONTENT))
                .andExpect(jsonPath("$.generated_at").value(GENERATED_AT))
                .andExpect(jsonPath("$.is_approved").value(true))
                .andExpect(jsonPath("$.tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.isApproved").doesNotExist())
                .andExpect(jsonPath("$.approved").doesNotExist());

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.writesContent()).isTrue();
        assertThat(written.contentValue()).isEqualTo("A revised reply");
        assertThat(written.writesApproval()).isTrue();
        assertThat(written.approvalValue()).isEqualTo(Boolean.TRUE);
        assertThat(written.carriesNoUpdatableMember()).isFalse();
    }

    // The two writable members are content and is_approved — backend/app/db/models.py:L24,L26
    @Test
    @DisplayName("forwards content and is_approved alone when the body carries further members")
    void forwardsContentAndIsApprovedAloneWhenTheBodyCarriesFurtherMembers() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(true));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"is_approved\":true,\"id\":\"99\","
                                + "\"tweet_id\":\"7\",\"generated_at\":\"2026-01-01T00:00:00\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST));

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.contentValue()).isEqualTo("x");
        assertThat(written.approvalValue()).isEqualTo(Boolean.TRUE);
        assertThat(Arrays.stream(UpdateResponseRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly("content", "isApproved");
    }

    // backend/app/api/responses.py:L54 — a member the body omits is not written
    @Test
    @DisplayName("forwards no approval value when the body carries content alone")
    void forwardsNoApprovalValueWhenTheBodyCarriesContentAlone() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(false));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"only text\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.contentValue()).isEqualTo("only text");
        assertThat(written.writesContent()).isTrue();
        assertThat(written.approvalValue()).isNull();
        assertThat(written.approvalValue()).isNotEqualTo(Boolean.FALSE);
        assertThat(written.writesApproval()).isFalse();
    }

    // backend/app/api/responses.py:L54 — a carried false is a written false
    @Test
    @DisplayName("forwards a false approval value when the body carries the approval flag alone")
    void forwardsAFalseApprovalValueWhenTheBodyCarriesTheApprovalFlagAlone() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(false));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_approved\":false}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_approved").value(false));

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.approvalValue()).isEqualTo(Boolean.FALSE);
        assertThat(written.approvalValue()).isNotNull();
        assertThat(written.writesApproval()).isTrue();
        assertThat(written.contentValue()).isNull();
        assertThat(written.writesContent()).isFalse();
    }

    // A carried member the addressed column can hold, an explicit JSON null included, is forwarded as
    // the value it carries — DL-082, DL-244 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] body={0}")
    @MethodSource("usableUpdateBodies")
    @DisplayName("forwards every usable carried member without answering 400")
    void forwardsEveryUsableCarriedMemberWithoutAnswering400(String body,
            boolean writesContent, String contentValue,
            boolean writesApproval, Boolean approvalValue) throws Exception {

        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(true));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST));

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.writesContent()).isEqualTo(writesContent);
        assertThat(written.contentValue()).isEqualTo(contentValue);
        assertThat(written.writesApproval()).isEqualTo(writesApproval);
        assertThat(written.approvalValue()).isEqualTo(approvalValue);
        assertThat(written.carriesNoUpdatableMember()).isFalse();
    }

    /**
     * The request bodies whose carried members the two addressed columns can hold.
     *
     * @return one argument set per body: the body, whether {@code content} is written and the value
     *     it writes, then whether {@code is_approved} is written and the value it writes
     */
    private static Stream<Arguments> usableUpdateBodies() {
        return Stream.of(
                Arguments.of("{\"is_approved\":true}", false, null, true, Boolean.TRUE),
                Arguments.of("{\"is_approved\":false}", false, null, true, Boolean.FALSE),
                Arguments.of("{\"is_approved\":null}", false, null, true, null),
                Arguments.of("{\"content\":null}", true, null, false, null),
                Arguments.of("{\"content\":\"\"}", true, "", false, null),
                Arguments.of("{\"content\":\"valid\",\"is_approved\":false}", true,
                        "valid", true, Boolean.FALSE),
                Arguments.of("{\"content\":null,\"is_approved\":null}", true, null, true, null));
    }

    // A carried member of any JSON type is bound and passed to the service, which is validation
    // parity with the free-form request.json of backend/app/api/responses.py:L54 — DL-050, DL-231 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] body={0}")
    @ValueSource(strings = {
        "{\"is_approved\":\"true\"}",
        "{\"is_approved\":\"false\"}",
        "{\"is_approved\":1}",
        "{\"is_approved\":0}",
        "{\"is_approved\":[true]}",
        "{\"is_approved\":{\"value\":true}}",
        "{\"content\":123}",
        "{\"content\":true}",
        "{\"content\":[\"a\"]}",
        "{\"content\":{\"x\":1}}",
        "{\"content\":\"valid\",\"is_approved\":\"true\"}",
        "{\"content\":123,\"is_approved\":true}"
    })
    @DisplayName("answers 200 and reaches the service for a carried member of any JSON type")
    void answers200ForACarriedMemberOfAnyJsonType(String body) throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(false));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).updateResponse(eq(RESPONSE_ID), any());
    }

    // No submitted value is echoed on the wire — DL-231 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("echoes no submitted member name back for a carried value of a surprising type")
    void echoesNoSubmittedMemberNameBackForASurprisingCarriedValue() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(NotFoundException.responseNotFoundOrUpdateFailed());

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":123}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(bodyDoesNotContain("is_approved", "JSON string",
                        "IllegalArgumentException"));
    }

    // backend/app/api/responses.py:L56-57 — the body-level guard
    @ParameterizedTest(name = "[{index}] body={0}")
    // A body carrying a recognised member is a write whatever JSON type it holds — that is validation
    // parity with the free-form request.json of backend/app/api/responses.py:L54 — so only a body
    // carrying neither recognised member reaches this literal — DL-082, DL-050.
    @ValueSource(strings = { "{}", "{\"isApproved\":true}", "{\"id\":\"9\"}" })
    @DisplayName("answers 400 with the Update data is required envelope when the body carries no writable member")
    void answers400WithTheUpdateDataIsRequiredEnvelopeWhenTheBodyCarriesNoWritableMember(String body)
            throws Exception {

        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(BadRequestException.updateDataRequired());

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(UPDATE_DATA_REQUIRED, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error").value(BadRequestException.UPDATE_DATA_IS_REQUIRED));

        UpdateResponseRequest written = capturedUpdate();
        assertThat(written.carriesNoUpdatableMember()).isTrue();
    }

    // backend/app/api/responses.py:L65 — the 404 literal of this route
    @Test
    @DisplayName("answers 404 with the Response not found or update failed envelope when the row is absent")
    void answers404WithTheResponseNotFoundOrUpdateFailedEnvelopeWhenTheRowIsAbsent()
            throws Exception {

        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(NotFoundException.responseNotFoundOrUpdateFailed());

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(UPDATE_FAILED, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.error")
                        .value(NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED));

        // The two 404 literals of this resource are different strings —
        // backend/app/api/responses.py:L31 and :L65
        assertThat(NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED)
                .isNotEqualTo(NotFoundException.RESPONSE_NOT_FOUND)
                .isEqualTo("Response not found or update failed");
        assertThat(UPDATE_FAILED).isNotEqualTo(RESPONSE_NOT_FOUND);
    }

    // A path segment carrying no number is a row that is absent — DL-048 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("answers 404, not 400 and not 500, when the written path segment carries no number")
    void answers404WhenTheWrittenPathSegmentCarriesNoNumber() throws Exception {
        when(responseService.updateResponse(eq(UNPARSEABLE_ID), any()))
                .thenThrow(NotFoundException.responseNotFoundOrUpdateFailed());

        mockMvc.perform(put("/responses/" + UNPARSEABLE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST))
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR))
                .andExpect(content().json(UPDATE_FAILED, JsonCompareMode.STRICT));

        ArgumentCaptor<String> writtenIdentifier = ArgumentCaptor.forClass(String.class);
        verify(responseService).updateResponse(writtenIdentifier.capture(), any());
        assertThat(writtenIdentifier.getValue())
                .isInstanceOf(String.class)
                .isEqualTo(UNPARSEABLE_ID);
    }

    // backend/app/main.py:L35-37 — the generic 500 envelope
    @Test
    @DisplayName("answers 500 with the internal server error envelope when the write fails")
    void answers500WithTheInternalServerErrorEnvelopeWhenTheWriteFails() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(new RuntimeException("the responses row could not be written"));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", hasSize(1)));
    }

    // -------------------------------------------------------------------------
    // Validation parity — backend/app/schema/response.py:L4-9 declares types and optionality only
    // -------------------------------------------------------------------------

    // No @Size and no @NotBlank on the written content — DL-050 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] content length={0}")
    @ValueSource(ints = {0, 1, 2, 512, 5000})
    @DisplayName("accepts a written content value of any length")
    void acceptsAWrittenContentValueOfAnyLength(int length) throws Exception {
        String written = longText(length);
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(false));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + written + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST));

        UpdateResponseRequest captured = capturedUpdate();
        assertThat(captured.contentValue()).hasSize(length).isEqualTo(written);
        assertThat(captured.writesContent()).isTrue();
    }

    // No @Size on the named tweet — DL-050 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] tweet_id length={0}")
    @ValueSource(ints = {1, 2, 64, 512})
    @DisplayName("accepts a named tweet id of any length")
    void acceptsANamedTweetIdOfAnyLength(int length) throws Exception {
        String named = longText(length);
        when(responseService.generateResponse(named)).thenReturn(response(false));

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + named + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isCreated())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST));

        verify(responseService).generateResponse(named);
    }

    // The one constraint on this resource is @NotNull on CreateResponseRequest.tweet_id — DL-050
    @Test
    @DisplayName("declares one constraint on the create body and none on the update body")
    void declaresOneConstraintOnTheCreateBodyAndNoneOnTheUpdateBody() {
        assertThat(constraintAnnotationNames(CreateResponseRequest.class))
                .containsExactly("NotNull");
        assertThat(constraintAnnotationNames(UpdateResponseRequest.class)).isEmpty();
        assertThat(constraintAnnotationNames(ResponseDto.class)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Authentication — the bare @jwt_required of backend/app/api/responses.py:L9,L23,L34,L52
    // is enforced here — DL-021 — see docs/DECISION_LOG.md
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answers every route with a bare 401, and not a 403, when the request carries no token")
    void answersEveryRouteWithABare401WhenTheRequestCarriesNoToken() throws Exception {
        mockMvc.perform(get("/responses"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN))
                .andExpect(content().string(""))
                .andExpect(bodyDoesNotContain(ERROR_KEY))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        mockMvc.perform(get("/responses/" + RESPONSE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN))
                .andExpect(content().string(""))
                .andExpect(bodyDoesNotContain(ERROR_KEY));

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + TWEET_ID + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN))
                .andExpect(content().string(""))
                .andExpect(bodyDoesNotContain(ERROR_KEY));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN))
                .andExpect(content().string(""))
                .andExpect(bodyDoesNotContain(ERROR_KEY));

        verifyNoInteractions(responseService);
    }

    @Test
    @DisplayName("answers a route with a bare 401 when the bearer token is not one this service minted")
    void answersARouteWithABare401WhenTheBearerTokenIsNotOneThisServiceMinted() throws Exception {
        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN))
                .andExpect(content().string(""));

        verifyNoInteractions(responseService);
    }

    // -------------------------------------------------------------------------
    // Path surface — the four routes stay unprefixed — DL-059 — see docs/DECISION_LOG.md
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/api/responses", "/v1/responses", "/Responses"})
    @DisplayName("generates no row at a prefixed or differently cased path")
    void generatesNoRowAtAPrefixedOrDifferentlyCasedPath(String path) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"" + TWEET_ID + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(NOT_FOUND_BODY, JsonCompareMode.STRICT));

        verifyNoInteractions(responseService);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/api/responses", "/v1/responses", "/Responses"})
    @DisplayName("lists no row at a prefixed or differently cased path")
    void listsNoRowAtAPrefixedOrDifferentlyCasedPath(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(NOT_FOUND_BODY, JsonCompareMode.STRICT));

        verifyNoInteractions(responseService);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * Mints a bearer credential for the configured principal.
     *
     * @return the value of an {@code Authorization} header the chain accepts
     */
    private String bearer() {
        return "Bearer " + jwtService.generateToken(PRINCIPAL);
    }

    /**
     * Reads the update body the controller forwarded for {@link #RESPONSE_ID}.
     *
     * @return the forwarded body, never {@code null}
     */
    private UpdateResponseRequest capturedUpdate() {
        ArgumentCaptor<UpdateResponseRequest> forwarded =
                ArgumentCaptor.forClass(UpdateResponseRequest.class);
        verify(responseService).updateResponse(eq(RESPONSE_ID), forwarded.capture());
        return forwarded.getValue();
    }

    /**
     * Builds the wire form of one {@code responses} row.
     *
     * @param approved the value of the {@code is_approved} column
     * @return the row
     */
    private static ResponseDto response(boolean approved) {
        return new ResponseDto(RESPONSE_ID, CONTENT,
                LocalDateTime.of(2026, 1, 31, 9, 15, 30), approved, TWEET_ID);
    }

    /**
     * Builds a page carrying one unapproved row and the counters that describe it.
     *
     * @return the envelope
     */
    private static PaginatedResponsesDto onePage() {
        return new PaginatedResponsesDto(List.of(response(false)),
                new PaginationDto(1, 10, 1L, 1));
    }

    /**
     * Builds an empty page carrying the supplied counters.
     *
     * @param page    the 1-based page number the envelope reports
     * @param perPage the page size the envelope reports
     * @return the envelope
     */
    private static PaginatedResponsesDto emptyPage(int page, int perPage) {
        return new PaginatedResponsesDto(List.of(), new PaginationDto(page, perPage, 0L, 0));
    }

    /**
     * Builds a JSON-safe run of characters of the requested length.
     *
     * @param length the number of characters, zero or greater
     * @return the run, empty when {@code length} is zero
     */
    private static String longText(int length) {
        return "a".repeat(length);
    }

    /**
     * Asserts that the answered status is not the one supplied.
     *
     * @param unexpected the status the route must not answer with
     * @return the matcher
     */
    private static ResultMatcher statusIsNot(HttpStatus unexpected) {
        return result -> assertThat(result.getResponse().getStatus())
                .isNotEqualTo(unexpected.value());
    }

    /**
     * Asserts that the answered body carries no occurrence of the supplied text.
     *
     * @param text the text the body must not carry
     * @return the matcher
     */
    private static ResultMatcher bodyDoesNotContain(String... text) {
        return result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain(text);
    }

    /**
     * Reads the simple names of the Bean Validation constraints a record declares, across its
     * fields, its accessors and its canonical constructor parameters.
     *
     * @param type the record type to read
     * @return the constraint names, deduplicated and sorted, empty when the record declares none
     */
    private static List<String> constraintAnnotationNames(Class<?> type) {
        Stream<Annotation> onFields = Arrays.stream(type.getDeclaredFields())
                .flatMap(field -> Arrays.stream(field.getAnnotations()));
        Stream<Annotation> onAccessors = Arrays.stream(type.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations()));
        Stream<Annotation> onParameters = Arrays.stream(type.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterAnnotations()))
                .flatMap(Arrays::stream);

        return Stream.of(onFields, onAccessors, onParameters)
                .flatMap(annotations -> annotations)
                .map(Annotation::annotationType)
                .filter(annotationType -> annotationType.isAnnotationPresent(Constraint.class))
                .map(Class::getSimpleName)
                .distinct()
                .sorted()
                .toList();
    }
}
