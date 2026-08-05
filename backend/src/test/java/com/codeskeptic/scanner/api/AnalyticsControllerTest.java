package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
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

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SummaryDto;
import com.codeskeptic.scanner.dto.TrendsDto;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.AnalyticsService;

// Ported from backend/app/api/analytics.py:L7-25 (faithful port); replaces
// backend/tests/test_api.py:L47-59 \u2014 see docs/DECISION_LOG.md DL-041, DL-042
/**
 * Exercises the two routes {@link AnalyticsController} serves, behind the application's servlet
 * security chain.
 *
 * <p>Both service methods take no argument, as {@code get_trends()} at
 * {@code backend/app/api/analytics.py:L14} and {@code get_summary()} at {@code :L24} took none. The
 * tests therefore assert that neither route reads a query parameter: a request carrying
 * {@code start_date} and {@code end_date}, the parameters {@code backend/tests/test_api.py:L55-59}
 * probed for, is answered exactly as a request carrying none \u2014 DL-042.
 *
 * <p>The chain is the real one: {@code security.SecurityConfig}, {@code config.CorsConfig} and
 * {@code security.JwtService} are imported rather than disabled.
 */
@WebMvcTest(AnalyticsController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("AnalyticsController")
class AnalyticsControllerTest {

    /** Principal named by {@code scanner.auth.username} under the {@code test} profile. */
    private static final String PRINCIPAL = "admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AnalyticsService analyticsService;

    // -------------------------------------------------------------------------
    // GET /analytics/trends
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renders the trends envelope with the snake_case keys of every point")
    void rendersTheTrendsEnvelopeWithTheSnakeCaseKeysOfEveryPoint() throws Exception {
        when(analyticsService.getTrends()).thenReturn(trends());

        mockMvc.perform(get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.trends[0].date").value("2026-08-01"))
                .andExpect(jsonPath("$.trends[0].tweet_count").value(4))
                .andExpect(jsonPath("$.trends[0].average_doubt_rating").value(6.5d))
                .andExpect(jsonPath("$.trends[0].total_likes").value(410));
    }

    @Test
    @DisplayName("renders an empty trends array when the window holds no row")
    void rendersAnEmptyTrendsArrayWhenTheWindowHoldsNoRow() throws Exception {
        when(analyticsService.getTrends()).thenReturn(new TrendsDto(List.of()));

        mockMvc.perform(get("/analytics/trends").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends").isArray())
                .andExpect(jsonPath("$.trends").isEmpty());
    }

    @Test
    @DisplayName("reads no query parameter on the trends route")
    void readsNoQueryParameterOnTheTrendsRoute() throws Exception {
        when(analyticsService.getTrends()).thenReturn(trends());

        MvcResult withParameters = mockMvc.perform(get("/analytics/trends")
                        .param("start_date", "2026-01-01").param("end_date", "2026-12-31")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withoutParameters = mockMvc.perform(get("/analytics/trends")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(withParameters.getResponse().getContentAsString())
                .isEqualTo(withoutParameters.getResponse().getContentAsString());
        verify(analyticsService, times(2)).getTrends();
    }

    // -------------------------------------------------------------------------
    // GET /analytics/summary
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renders every summary metric under its snake_case key")
    void rendersEverySummaryMetricUnderItsSnakeCaseKey() throws Exception {
        when(analyticsService.getSummary()).thenReturn(summary());

        mockMvc.perform(get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_tweets").value(12))
                .andExpect(jsonPath("$.total_responses").value(7))
                .andExpect(jsonPath("$.approved_responses").value(3))
                .andExpect(jsonPath("$.pending_responses").value(4))
                .andExpect(jsonPath("$.average_doubt_rating").value(6.25d))
                .andExpect(jsonPath("$.average_like_count").value(102.5d))
                .andExpect(jsonPath("$.tracked_ai_tools").value(5));
    }

    @Test
    @DisplayName("renders the two metric names the retired suite asserted by name")
    void rendersTheTwoMetricNamesTheRetiredSuiteAssertedByName() throws Exception {
        when(analyticsService.getSummary()).thenReturn(summary());

        String body = mockMvc.perform(get("/analytics/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("total_tweets").contains("total_responses");
    }

    @Test
    @DisplayName("renders a summary whose metrics are all absent")
    void rendersASummaryWhoseMetricsAreAllAbsent() throws Exception {
        when(analyticsService.getSummary())
                .thenReturn(new SummaryDto(0L, 0L, 0L, 0L, null, null, 0L));

        mockMvc.perform(get("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_tweets").value(0))
                .andExpect(jsonPath("$.average_doubt_rating").doesNotExist())
                .andExpect(jsonPath("$.average_like_count").doesNotExist());
    }

    @Test
    @DisplayName("reads no query parameter on the summary route")
    void readsNoQueryParameterOnTheSummaryRoute() throws Exception {
        when(analyticsService.getSummary()).thenReturn(summary());

        MvcResult withParameters = mockMvc.perform(get("/analytics/summary")
                        .param("start_date", "2026-01-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult withoutParameters = mockMvc.perform(get("/analytics/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(withParameters.getResponse().getContentAsString())
                .isEqualTo(withoutParameters.getResponse().getContentAsString());
    }

    // -------------------------------------------------------------------------
    // Route surface and authentication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("declares exactly two routes, both taking no parameter")
    void declaresExactlyTwoRoutesBothTakingNoParameter() {
        List<Method> routes = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .toList();

        assertThat(routes).hasSize(2);
        assertThat(routes).allSatisfy(route -> assertThat(route.getParameterCount()).isZero());
    }

    @Test
    @DisplayName("answers both routes with a bare 401 when the request carries no token")
    void answersBothRoutesWithABare401WhenTheRequestCarriesNoToken() throws Exception {
        mockMvc.perform(get("/analytics/trends"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        mockMvc.perform(get("/analytics/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("answers a route with a bare 401 when the bearer token is not one this service minted")
    void answersARouteWithABare401WhenTheBearerTokenIsNotOneThisServiceMinted() throws Exception {
        mockMvc.perform(get("/analytics/summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("answers a write method on a read route with 405 and the method literal")
    void answersAWriteMethodOnAReadRouteWith405AndTheMethodLiteral() throws Exception {
        mockMvc.perform(post("/analytics/summary").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Method not allowed"));

        verifyNoInteractions(analyticsService);
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
     * Builds a trends envelope carrying two day buckets.
     *
     * @return the envelope
     */
    private static TrendsDto trends() {
        return new TrendsDto(List.of(
                new TrendsDto.TrendPoint(LocalDate.of(2026, 8, 1), 4L, 6.5d, 410L),
                new TrendsDto.TrendPoint(LocalDate.of(2026, 8, 2), 1L, 2.0d, 12L)));
    }

    /**
     * Builds a summary carrying one value per metric.
     *
     * @return the summary
     */
    private static SummaryDto summary() {
        return new SummaryDto(12L, 7L, 3L, 4L, 6.25d, 102.5d, 5L);
    }
}
