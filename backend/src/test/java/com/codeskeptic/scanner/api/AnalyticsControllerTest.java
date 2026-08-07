package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.AnalyticsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Replaces backend/tests/test_api.py:L47-59, whose source antecedent probed
// /analytics/trends?start_date=&end_date=, a query the route does not declare — see
// docs/DECISION_LOG.md DL-042
@WebMvcTest(AnalyticsController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
class AnalyticsControllerTest {
    private static final String PRINCIPAL = "admin";
    private static final String INTERNAL_ERROR_BODY =
            "{\"error\":\"Internal server error\"}";
    private static final String NOT_FOUND_BODY = "{\"error\":\"Not found\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    void serializesSummaryContract() throws Exception {
        when(analyticsService.getSummary()).thenReturn(summary());

        MvcResult result = mockMvc.perform(get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total_tweets").value(12))
                .andExpect(jsonPath("$.total_responses").value(7))
                .andExpect(jsonPath("$.approved_responses").value(3))
                .andExpect(jsonPath("$.pending_responses").value(4))
                .andExpect(jsonPath("$.average_doubt_rating").value(6.25d))
                .andExpect(jsonPath("$.average_like_count").value(102.5d))
                .andExpect(jsonPath("$.tracked_ai_tools").value(5))
                .andExpect(jsonPath("$.totalTweets").doesNotExist())
                .andExpect(jsonPath("$.totalResponses").doesNotExist())
                .andExpect(jsonPath("$.approvedResponses").doesNotExist())
                .andExpect(jsonPath("$.pendingResponses").doesNotExist())
                .andExpect(jsonPath("$.averageDoubtRating").doesNotExist())
                .andExpect(jsonPath("$.averageLikeCount").doesNotExist())
                .andExpect(jsonPath("$.trackedAiTools").doesNotExist())
                .andReturn();

        Map<String, Object> body = readObject(result);

        assertThat(body).containsOnlyKeys(
                "total_tweets",
                "total_responses",
                "approved_responses",
                "pending_responses",
                "average_doubt_rating",
                "average_like_count",
                "tracked_ai_tools");
        assertThat(body.get("total_tweets")).isInstanceOf(Integer.class).isEqualTo(12);
        assertThat(body.get("total_responses")).isInstanceOf(Integer.class).isEqualTo(7);
        assertThat(body.get("approved_responses")).isInstanceOf(Integer.class).isEqualTo(3);
        assertThat(body.get("pending_responses")).isInstanceOf(Integer.class).isEqualTo(4);
        assertThat(body.get("tracked_ai_tools")).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(body.get("average_doubt_rating"))
                .isInstanceOf(Double.class)
                .isEqualTo(6.25d);
        assertThat(body.get("average_like_count"))
                .isInstanceOf(Double.class)
                .isEqualTo(102.5d);
        verify(analyticsService, times(1)).getSummary();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void serializesNullSummaryAveragesAsPresentKeys() throws Exception {
        when(analyticsService.getSummary())
                .thenReturn(new SummaryDto(0L, 0L, 0L, 0L, null, null, 0L));

        MvcResult result = mockMvc.perform(get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average_doubt_rating").value(nullValue()))
                .andExpect(jsonPath("$.average_like_count").value(nullValue()))
                .andReturn();

        Map<String, Object> body = readObject(result);

        assertThat(body).containsOnlyKeys(
                "total_tweets",
                "total_responses",
                "approved_responses",
                "pending_responses",
                "average_doubt_rating",
                "average_like_count",
                "tracked_ai_tools");
        assertThat(body).containsKeys("average_doubt_rating", "average_like_count");
        assertThat(body.get("average_doubt_rating")).isNull();
        assertThat(body.get("average_like_count")).isNull();
        verify(analyticsService, times(1)).getSummary();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void returnsBareUnauthorizedForSummary() throws Exception {
        MvcResult result = mockMvc.perform(get("/analytics/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBareUnauthorized(result);
        verifyNoInteractions(analyticsService);
    }

    @Test
    void returnsInternalErrorEnvelopeForSummary() throws Exception {
        when(analyticsService.getSummary()).thenThrow(new RuntimeException("summary failure"));

        MvcResult result = mockMvc.perform(get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(INTERNAL_ERROR_BODY))
                .andReturn();

        assertThat(readObject(result))
                .containsExactly(Map.entry("error", "Internal server error"));
        verify(analyticsService, times(1)).getSummary();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void serializesTrendsContract() throws Exception {
        when(analyticsService.getTrends()).thenReturn(trends());

        MvcResult result = mockMvc.perform(get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.trends.length()").value(2))
                .andExpect(jsonPath("$.trends[0].date").value("2026-08-01"))
                .andExpect(jsonPath("$.trends[0].tweet_count").value(4))
                .andExpect(jsonPath("$.trends[0].average_doubt_rating").value(6.5d))
                .andExpect(jsonPath("$.trends[0].total_likes").value(410))
                .andExpect(jsonPath("$.trends[0].tweetCount").doesNotExist())
                .andExpect(jsonPath("$.trends[0].averageDoubtRating").doesNotExist())
                .andExpect(jsonPath("$.trends[0].totalLikes").doesNotExist())
                .andReturn();

        Map<String, Object> body = readObject(result);

        assertThat(body).containsOnlyKeys("trends");
        assertThat(body.get("trends")).isInstanceOf(List.class);

        List<?> points = (List<?>) body.get("trends");
        assertThat(points).hasSize(2);
        assertThat(points).allSatisfy(point -> {
            assertThat(point).isInstanceOf(Map.class);
            Map<?, ?> pointObject = (Map<?, ?>) point;
            assertThat(pointObject).hasSize(4);
            assertThat(pointObject.keySet().stream().map(Object::toString).toList())
                    .containsExactlyInAnyOrder(
                            "date", "tweet_count", "average_doubt_rating", "total_likes");
        });
        assertThat(((Map<?, ?>) points.get(0)).get("date"))
                .isInstanceOf(String.class)
                .isEqualTo("2026-08-01");
        verify(analyticsService, times(1)).getTrends();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void serializesEmptyTrendsArray() throws Exception {
        when(analyticsService.getTrends()).thenReturn(new TrendsDto(List.of()));

        MvcResult result = mockMvc.perform(get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"trends\":[]}"))
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.trends").isEmpty())
                .andReturn();

        Map<String, Object> body = readObject(result);

        assertThat(body).containsOnlyKeys("trends");
        assertThat(body.get("trends")).isEqualTo(List.of());
        verify(analyticsService, times(1)).getTrends();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void returnsBareUnauthorizedForTrends() throws Exception {
        MvcResult result = mockMvc.perform(get("/analytics/trends"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBareUnauthorized(result);
        verifyNoInteractions(analyticsService);
    }

    @Test
    void returnsInternalErrorEnvelopeForTrends() throws Exception {
        when(analyticsService.getTrends()).thenThrow(new RuntimeException("trends failure"));

        MvcResult result = mockMvc.perform(get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(INTERNAL_ERROR_BODY))
                .andReturn();

        assertThat(readObject(result))
                .containsExactly(Map.entry("error", "Internal server error"));
        verify(analyticsService, times(1)).getTrends();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void ignoresDateRangeQueryParametersOnSummary() throws Exception {
        when(analyticsService.getSummary()).thenReturn(summary());

        MvcResult withoutParameters = mockMvc.perform(
                        get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withParameters = mockMvc.perform(get("/analytics/summary")
                        .param("start_date", "2023-01-01")
                        .param("end_date", "2023-12-31")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(withParameters.getResponse().getContentAsString())
                .isEqualTo(withoutParameters.getResponse().getContentAsString());
        verify(analyticsService, times(2)).getSummary();
        verifyNoMoreInteractions(analyticsService);
    }

    @Test
    void ignoresQueryParametersOnTrends() throws Exception {
        when(analyticsService.getTrends()).thenReturn(trends());

        MvcResult withoutParameters = mockMvc.perform(
                        get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withDateRange = mockMvc.perform(get("/analytics/trends")
                        .param("start_date", "2023-01-01")
                        .param("end_date", "2023-12-31")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withDays = mockMvc.perform(get("/analytics/trends")
                        .param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withGranularity = mockMvc.perform(get("/analytics/trends")
                        .param("granularity", "week")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withInvalidDate = mockMvc.perform(get("/analytics/trends")
                        .param("start_date", "not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String expectedBody = withoutParameters.getResponse().getContentAsString();
        assertThat(withDateRange.getResponse().getContentAsString()).isEqualTo(expectedBody);
        assertThat(withDays.getResponse().getContentAsString()).isEqualTo(expectedBody);
        assertThat(withGranularity.getResponse().getContentAsString()).isEqualTo(expectedBody);
        assertThat(withInvalidDate.getResponse().getContentAsString()).isEqualTo(expectedBody);
        verify(analyticsService, times(5)).getTrends();
        verifyNoMoreInteractions(analyticsService);
    }

    // An unmapped path answers the envelope of backend/app/main.py:L31-33 — DL-059, DL-183 —
    // see docs/DECISION_LOG.md
    @Test
    void rejectsUndeclaredAnalyticsRoutes() throws Exception {
        for (String path : List.of(
                "/analytics/",
                "/api/analytics/summary",
                "/analytics/summary/2023",
                "/analytics/trends/2023")) {
            MvcResult result = mockMvc.perform(
                            get(path).header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(NOT_FOUND_BODY))
                    .andReturn();

            assertThat(readObject(result))
                    .as("error envelope served at %s", path)
                    .containsExactly(Map.entry("error", "Not found"));
        }

        verifyNoInteractions(analyticsService);
    }

    // Every mapped route requires a token the chain can verify — DL-021, DL-115 — see
    // docs/DECISION_LOG.md
    @Test
    void rejectsACredentialTheChainCannotVerify() throws Exception {
        String minted = jwtService.generateToken(PRINCIPAL);

        for (String credential : List.of(
                "Bearer not-a-token",
                "Bearer " + minted + "tampered",
                minted,
                "Basic YWRtaW46YWRtaW4=")) {
            for (String path : List.of("/analytics/summary", "/analytics/trends")) {
                MvcResult result = mockMvc.perform(
                                get(path).header(HttpHeaders.AUTHORIZATION, credential))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().string(""))
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                        .andReturn();

                assertBareUnauthorized(result);
            }
        }

        verifyNoInteractions(analyticsService);
    }

    @Test
    void declaresTwoInputFreeControllerMappings() {
        List<Method> routes = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                .filter(AnalyticsControllerTest::isRequestMapping)
                .toList();

        assertThat(routes)
                .hasSize(2)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("getSummary", "getTrends");
        assertThat(routes).allSatisfy(route -> {
            assertThat(route.getParameterCount()).isZero();
            assertThat(route.getParameters()).allSatisfy(
                    AnalyticsControllerTest::assertNoRequestAnnotations);
            assertNoMappingConditions(route);
        });
    }

    @Test
    void declaresInputFreeServiceMethods() throws NoSuchMethodException {
        Method getSummary = AnalyticsService.class.getDeclaredMethod("getSummary");
        Method getTrends = AnalyticsService.class.getDeclaredMethod("getTrends");

        assertThat(getSummary.getParameterTypes()).isEmpty();
        assertThat(getTrends.getParameterTypes()).isEmpty();
    }

    private static boolean isRequestMapping(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }

    private static void assertNoRequestAnnotations(Parameter parameter) {
        List<Class<? extends Annotation>> requestAnnotations = List.of(
                RequestParam.class,
                PathVariable.class,
                RequestBody.class,
                ModelAttribute.class,
                RequestHeader.class);

        requestAnnotations.forEach(annotation ->
                assertThat(parameter.isAnnotationPresent(annotation)).isFalse());
    }

    private static void assertNoMappingConditions(Method method) {
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        if (getMapping != null) {
            assertThat(getMapping.params()).isEmpty();
            assertThat(getMapping.headers()).isEmpty();
        }

        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
        if (requestMapping != null) {
            assertThat(requestMapping.params()).isEmpty();
            assertThat(requestMapping.headers()).isEmpty();
        }
    }

    /**
     * Builds the {@code Authorization} header value of an authenticated request.
     *
     * <p>The token is minted by the same {@code security/JwtService} the imported
     * {@code security/SecurityConfig} chain verifies, so every request carrying it crosses
     * {@code security/JwtAuthenticationFilter} — DL-021, DL-115.
     *
     * @return the {@code Bearer} credential of the principal {@value #PRINCIPAL}
     */
    private String bearer() {
        return "Bearer " + jwtService.generateToken(PRINCIPAL);
    }

    private void assertBareUnauthorized(MvcResult result) throws Exception {
        int responseStatus = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString();

        assertThat(responseStatus).isEqualTo(401);
        assertThat(responseStatus).isNotEqualTo(403);
        assertThat(responseBody).isEmpty();
        assertThat(responseBody).doesNotContain("error");
    }

    private Map<String, Object> readObject(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    private static TrendsDto trends() {
        return new TrendsDto(List.of(
                new TrendsDto.TrendPoint(LocalDate.of(2026, 8, 1), 4L, 6.5d, 410L),
                new TrendsDto.TrendPoint(LocalDate.of(2026, 8, 2), 1L, 2.0d, 12L)));
    }

    private static SummaryDto summary() {
        return new SummaryDto(12L, 7L, 3L, 4L, 6.25d, 102.5d, 5L);
    }
}
