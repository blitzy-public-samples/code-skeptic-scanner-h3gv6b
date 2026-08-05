package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;

// Ported from backend/app/api/tweets.py:L9-55 (faithful port); replaces
// backend/tests/test_api.py:L11-33 \u2014 see docs/DECISION_LOG.md DL-037, DL-038, DL-048
/**
 * Exercises the three routes {@link TweetController} serves, behind the application's servlet
 * security chain.
 *
 * <p>The chain is the real one: {@code security.SecurityConfig}, {@code config.CorsConfig} and
 * {@code security.JwtService} are imported rather than disabled, so every route is reached with a
 * minted bearer token and every route is also exercised without one.
 *
 * <p>The two collaborators are Mockito beans. No test resolves a Google credential, opens a
 * connection or reaches an external system.
 */
@WebMvcTest(TweetController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("TweetController")
class TweetControllerTest {

    /** Principal named by {@code scanner.auth.username} under the {@code test} profile. */
    private static final String PRINCIPAL = "admin";

    /** Identifier the routes address in most tests. */
    private static final String TWEET_ID = "7";

    /** Wire literal of {@code backend/app/api/tweets.py:L32,L43}. */
    private static final String TWEET_NOT_FOUND = "{\"error\":\"Tweet not found\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private TwitterService twitterService;

    @MockitoBean
    private SentimentAnalysisService sentimentAnalysisService;

    // -------------------------------------------------------------------------
    // GET /tweets \u2014 pagination parity with request.args.get(..., type=int)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reads page 1 of 10 when the request carries no pagination parameter")
    void readsPage1Of10WhenTheRequestCarriesNoPaginationParameter() throws Exception {
        when(twitterService.getPaginatedTweets(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/tweets").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10));

        verify(twitterService).getPaginatedTweets(1, 10);
    }

    @Test
    @DisplayName("reads the page and size the request carries")
    void readsThePageAndSizeTheRequestCarries() throws Exception {
        when(twitterService.getPaginatedTweets(3, 25)).thenReturn(emptyPage(3, 25));

        mockMvc.perform(get("/tweets").param("page", "3").param("per_page", "25")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(3, 25);
    }

    @ParameterizedTest(name = "[{index}] page={0}")
    @ValueSource(strings = {"abc", "", " ", "3.5", "0x10", "1e3", "99999999999999999999", "--3"})
    @DisplayName("answers 200 with the declared default when page does not convert to an integer")
    void answers200WithTheDeclaredDefaultWhenPageDoesNotConvertToAnInteger(String page)
            throws Exception {

        when(twitterService.getPaginatedTweets(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/tweets").param("page", page)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(1, 10);
    }

    @ParameterizedTest(name = "[{index}] per_page={0}")
    @ValueSource(strings = {"abc", "", " ", "2.5", "many"})
    @DisplayName("answers 200 with the declared default when per_page does not convert to an integer")
    void answers200WithTheDeclaredDefaultWhenPerPageDoesNotConvertToAnInteger(String perPage)
            throws Exception {

        when(twitterService.getPaginatedTweets(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/tweets").param("per_page", perPage)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(1, 10);
    }

    @Test
    @DisplayName("passes a negative page on unclamped, as the source did")
    void passesANegativePageOnUnclamped() throws Exception {
        when(twitterService.getPaginatedTweets(-5, 10)).thenReturn(emptyPage(-5, 10));

        mockMvc.perform(get("/tweets").param("page", "-5")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(-5, 10);
    }

    @Test
    @DisplayName("accepts a padded and signed pagination value as int(...) accepted it")
    void acceptsAPaddedAndSignedPaginationValue() throws Exception {
        when(twitterService.getPaginatedTweets(4, 20)).thenReturn(emptyPage(4, 20));

        mockMvc.perform(get("/tweets").param("page", " +4 ").param("per_page", " 20 ")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(4, 20);
    }

    @Test
    @DisplayName("ignores a camelCase pagination spelling and applies the declared default")
    void ignoresACamelCasePaginationSpelling() throws Exception {
        when(twitterService.getPaginatedTweets(1, 10)).thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/tweets").param("perPage", "25")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(1, 10);
    }

    // -------------------------------------------------------------------------
    // GET /tweets/{tweetId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renders the addressed row unwrapped")
    void rendersTheAddressedRowUnwrapped() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenReturn(tweet(null));

        mockMvc.perform(get("/tweets/" + TWEET_ID).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TWEET_ID))
                .andExpect(jsonPath("$.like_count").value(120));
    }

    @ParameterizedTest(name = "[{index}] tweetId={0}")
    @ValueSource(strings = {"7", "not-a-number", "9999999999999999999999"})
    @DisplayName("answers 404 with the Tweet not found envelope for an identifier that addresses no row")
    void answers404WithTheTweetNotFoundEnvelopeForAnIdentifierThatAddressesNoRow(String tweetId)
            throws Exception {

        when(twitterService.getTweet(tweetId)).thenThrow(NotFoundException.tweetNotFound());

        mockMvc.perform(get("/tweets/" + tweetId).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT));
    }

    // -------------------------------------------------------------------------
    // POST /tweets/{tweetId}/analyze
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scores a row carrying no doubt rating and records the analysis")
    void scoresARowCarryingNoDoubtRatingAndRecordsTheAnalysis() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenReturn(tweet(null));
        when(sentimentAnalysisService.analyzeSentiment(anyString())).thenReturn(-0.4d);

        mockMvc.perform(post("/tweets/" + TWEET_ID + "/analyze")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.analysis_result").value(-0.4d));

        verify(sentimentAnalysisService).analyzeSentiment("AI coding tools still cannot get this right");
        verify(twitterService).updateTweetAnalysis(TWEET_ID, -0.4d);
    }

    @Test
    @DisplayName("reports an identifier addressing no row before scoring anything")
    void reportsAnIdentifierAddressingNoRowBeforeScoringAnything() throws Exception {
        when(twitterService.getTweet("404")).thenThrow(NotFoundException.tweetNotFound());

        mockMvc.perform(post("/tweets/404/analyze").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT));

        verifyNoInteractions(sentimentAnalysisService);
    }

    @Test
    @DisplayName("sends the identifier as received on the wire, not the identifier of the row read")
    void sendsTheIdentifierAsReceivedOnTheWire() throws Exception {
        when(twitterService.getTweet("007")).thenReturn(tweet(7.0d));

        mockMvc.perform(post("/tweets/007/analyze").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweet_id").value("007"));

        ArgumentCaptor<String> addressed = ArgumentCaptor.forClass(String.class);
        verify(twitterService).getTweet(addressed.capture());
        assertThat(addressed.getValue()).isEqualTo("007");
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answers every route with a bare 401 when the request carries no token")
    void answersEveryRouteWithABare401WhenTheRequestCarriesNoToken() throws Exception {
        mockMvc.perform(get("/tweets"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        mockMvc.perform(get("/tweets/" + TWEET_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post("/tweets/" + TWEET_ID + "/analyze"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(twitterService, sentimentAnalysisService);
    }

    @Test
    @DisplayName("answers a route with a bare 401 when the bearer token is not one this service minted")
    void answersARouteWithABare401WhenTheBearerTokenIsNotOneThisServiceMinted() throws Exception {
        mockMvc.perform(get("/tweets").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(twitterService);
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
     * Builds the wire form of one {@code tweets} row.
     *
     * @param doubtRating the value of the {@code doubt_rating} column, or {@code null} for a row that
     *                    carries none
     * @return the row
     */
    private static TweetDto tweet(Double doubtRating) {
        return new TweetDto(TWEET_ID, "AI coding tools still cannot get this right", 120,
                LocalDateTime.of(2026, 8, 1, 12, 0), doubtRating, List.of(), null, "42",
                List.of("GPT-4"));
    }

    /**
     * Builds an empty page carrying the supplied counters.
     *
     * @param page    the 1-based page number the envelope reports
     * @param perPage the page size the envelope reports
     * @return the envelope
     */
    private static PaginatedTweetsDto emptyPage(int page, int perPage) {
        return new PaginatedTweetsDto(List.of(), new PaginationDto(page, perPage, 0L, 0));
    }
}
