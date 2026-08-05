package com.codeskeptic.scanner.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.ResponseService;

// Ported from backend/app/api/responses.py:L8-65 (faithful port); replaces
// backend/tests/test_api.py:L11-33 \u2014 see docs/DECISION_LOG.md DL-038, DL-048
/**
 * Exercises the four routes {@link ResponseController} serves, behind the application's servlet
 * security chain.
 *
 * <p>The chain is the real one: {@code security.SecurityConfig}, {@code config.CorsConfig} and
 * {@code security.JwtService} are imported rather than disabled, so every route is reached with a
 * minted bearer token and every route is also exercised without one.
 *
 * <p>The one collaborator is a Mockito bean, so no test reaches OpenAI, Notion or a database.
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

    /** Message the advice serves for a body the converter rejected — DL-092. */
    private static final String BAD_REQUEST = "{\"error\":\"Bad request\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ResponseService responseService;

    // -------------------------------------------------------------------------
    // GET /responses \u2014 pagination parity with request.args.get(..., type=int)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reads page 1 of 10 when the request carries no pagination parameter")
    void readsPage1Of10WhenTheRequestCarriesNoPaginationParameter() throws Exception {
        when(responseService.getPaginatedResponses(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10))
                .andExpect(jsonPath("$.responses").isArray());

        verify(responseService).getPaginatedResponses(1, 10);
    }

    // A page beyond the queryable offset is answered, not rejected — DL-217, DL-219 — see
    // docs/DECISION_LOG.md
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

    @Test
    @DisplayName("reads the page and size the request carries")
    void readsThePageAndSizeTheRequestCarries() throws Exception {
        when(responseService.getPaginatedResponses(2, 5)).thenReturn(emptyPage(2, 5));

        mockMvc.perform(get("/responses").param("page", "2").param("per_page", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).getPaginatedResponses(2, 5);
    }

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
    @DisplayName("passes a negative page on unclamped, as the source did")
    void passesANegativePageOnUnclamped() throws Exception {
        when(responseService.getPaginatedResponses(-2, 10)).thenReturn(emptyPage(-2, 10));

        mockMvc.perform(get("/responses").param("page", "-2")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(responseService).getPaginatedResponses(-2, 10);
    }

    // -------------------------------------------------------------------------
    // GET /responses/{responseId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renders the addressed row unwrapped")
    void rendersTheAddressedRowUnwrapped() throws Exception {
        when(responseService.getResponseById(RESPONSE_ID)).thenReturn(response(false));

        mockMvc.perform(get("/responses/" + RESPONSE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.tweet_id").value("7"))
                .andExpect(jsonPath("$.is_approved").value(false));
    }

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
                .andExpect(content().json(RESPONSE_NOT_FOUND, JsonCompareMode.STRICT));
    }

    // -------------------------------------------------------------------------
    // POST /responses
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answers 201 carrying the generated row")
    void answers201CarryingTheGeneratedRow() throws Exception {
        when(responseService.generateResponse("7")).thenReturn(response(false));

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"7\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RESPONSE_ID))
                .andExpect(jsonPath("$.is_approved").value(false));
    }

    @ParameterizedTest(name = "[{index}] body={0}")
    @ValueSource(strings = {"{}", "{\"tweet_id\":null}"})
    @DisplayName("answers 400 with the Tweet ID is required envelope when the body names no tweet")
    void answers400WithTheTweetIdIsRequiredEnvelopeWhenTheBodyNamesNoTweet(String body)
            throws Exception {

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(TWEET_ID_REQUIRED, JsonCompareMode.STRICT));

        verifyNoInteractions(responseService);
    }

    @Test
    @DisplayName("answers 500 with the Failed to generate response envelope when generation fails")
    void answers500WithTheFailedToGenerateResponseEnvelopeWhenGenerationFails() throws Exception {
        when(responseService.generateResponse("7")).thenThrow(new ResponseGenerationException());

        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"7\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(GENERATION_FAILED, JsonCompareMode.STRICT));
    }

    // -------------------------------------------------------------------------
    // PUT /responses/{responseId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answers 200 carrying the stored row")
    void answers200CarryingTheStoredRow() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenReturn(response(true));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\",\"is_approved\":true}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_approved").value(true));
    }

    @Test
    @DisplayName("answers 404 with the update failure envelope when the row is absent")
    void answers404WithTheUpdateFailureEnvelopeWhenTheRowIsAbsent() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(NotFoundException.responseNotFoundOrUpdateFailed());

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A revised reply\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(UPDATE_FAILED, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("answers 200 when the body carries the approval flag alone")
    void answers200WhenTheBodyCarriesTheApprovalFlagAlone() throws Exception {
        when(responseService.updateResponse(eq(RESPONSE_ID), any())).thenReturn(response(false));

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_approved\":false}")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_approved").value(false));
    }

    // A carried key carries a usable value — DL-082, DL-092 — see docs/DECISION_LOG.md
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"is_approved\":\"true\"}",
            "{\"is_approved\":\"false\"}",
            "{\"is_approved\":1}",
            "{\"is_approved\":null}",
            "{\"content\":null}",
            "{\"content\":123}",
            "{\"content\":true}",
            "{\"content\":[\"a\"]}",
            "{\"content\":{\"x\":1}}",
            "{\"content\":\"valid\",\"is_approved\":\"true\"}"
    })
    @DisplayName("answers 400 with the generic envelope when a carried key holds an unusable value")
    void answers400WithTheGenericEnvelopeWhenACarriedKeyHoldsAnUnusableValue(String body)
            throws Exception {

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(BAD_REQUEST, JsonCompareMode.STRICT));

        verifyNoInteractions(responseService);
    }

    // backend/app/api/responses.py:L56-57 — the body-level guard, unchanged by DL-082
    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"isApproved\":true}", "{\"id\":\"9\"}" })
    @DisplayName("answers 400 with the update-data envelope when the body carries no writable key")
    void answers400WithTheUpdateDataEnvelopeWhenTheBodyCarriesNoWritableKey(String body)
            throws Exception {

        when(responseService.updateResponse(eq(RESPONSE_ID), any()))
                .thenThrow(BadRequestException.updateDataRequired());

        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(UPDATE_DATA_REQUIRED, JsonCompareMode.STRICT));
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answers every route with a bare 401 when the request carries no token")
    void answersEveryRouteWithABare401WhenTheRequestCarriesNoToken() throws Exception {
        mockMvc.perform(get("/responses"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        mockMvc.perform(get("/responses/" + RESPONSE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post("/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tweet_id\":\"7\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(put("/responses/" + RESPONSE_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(responseService);
    }

    @Test
    @DisplayName("answers a route with a bare 401 when the bearer token is not one this service minted")
    void answersARouteWithABare401WhenTheBearerTokenIsNotOneThisServiceMinted() throws Exception {
        mockMvc.perform(get("/responses").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

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
     * Builds the wire form of one {@code responses} row.
     *
     * @param approved the value of the {@code is_approved} column
     * @return the row
     */
    private static ResponseDto response(boolean approved) {
        return new ResponseDto(RESPONSE_ID, "A generated reply",
                LocalDateTime.of(2026, 1, 31, 9, 15), approved, "7");
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
}
