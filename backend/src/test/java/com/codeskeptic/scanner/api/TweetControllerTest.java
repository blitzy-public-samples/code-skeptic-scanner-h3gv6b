package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.isA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.TwitterService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Replaces backend/tests/test_api.py, whose source antecedent drove a Flask application through
// fastapi.testclient at :L2 and could not be executed — see docs/DECISION_LOG.md DL-239
/**
 * Exercises the three routes {@link TweetController} serves, behind the application's servlet
 * security chain.
 *
 * <table border="1">
 * <caption>Route surface under test</caption>
 * <tr><th>Method and path</th><th>Source</th></tr>
 * <tr><td>{@code GET /tweets}</td><td>{@code backend/app/api/tweets.py:L9-21}</td></tr>
 * <tr><td>{@code GET /tweets/{tweetId}}</td><td>{@code backend/app/api/tweets.py:L23-32}</td></tr>
 * <tr><td>{@code POST /tweets/{tweetId}/analyze}</td>
 *     <td>{@code backend/app/api/tweets.py:L36-55}</td></tr>
 * </table>
 *
 * <p>The retired counterpart is {@code backend/tests/test_api.py:L1-59}, which addressed
 * {@code POST /tweets/}, {@code DELETE /tweets/1} and {@code GET /tweets/1/responses} — three paths
 * this service does not serve — through {@code fastapi.testclient.TestClient} against a Flask
 * application, and carried no {@code Authorization} header on any request.
 *
 * <p>The slice registers the real {@link SecurityConfig} filter chain, the real {@link CorsConfig}
 * policy, the real {@link JwtService} and the bound {@link ScannerProperties}, under the
 * {@code test} profile of {@code src/test/resources/application-test.yml}.
 * {@link GlobalExceptionHandler} is a {@code @RestControllerAdvice} and is part of every web slice.
 * Each error envelope asserted below is the one that advice produces.
 *
 * <p>{@link TwitterService} is the only replaced bean; the analyze route reaches it alone — DL-263.
 * No test here resolves a Google credential, opens a database connection, reaches an external system
 * or reads the network.
 *
 * <p>Every authenticated request carries a bearer credential minted by the real {@link JwtService}
 * for the principal {@code scanner.auth.username} names, and every route is also exercised with no
 * credential at all.
 *
 * <p>Decisions covered by the assertions here are recorded in {@code docs/DECISION_LOG.md} DL-021,
 * DL-022, DL-023, DL-024, DL-037, DL-038, DL-048, DL-050, DL-059 and DL-217; construct-level
 * provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@WebMvcTest(TweetController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("TweetController")
class TweetControllerTest {
    private static final String PRINCIPAL = "admin";

    /** Route of {@code backend/app/api/tweets.py:L9}. */
    private static final String LIST_ROUTE = "/tweets";

    /** Route of {@code backend/app/api/tweets.py:L9} carrying the frontend's {@code /api} prefix. */
    private static final String PREFIXED_LIST_ROUTE = "/api/tweets";

    private static final String TWEET_ID = "7";

    /** Wire literal of {@code backend/app/api/tweets.py:L32,L43}. */
    private static final String TWEET_NOT_FOUND = "{\"error\":\"Tweet not found\"}";

    /** The sanctioned envelope of an unmatched path — backend/app/main.py:L31-33, DL-183. */
    private static final String NOT_FOUND_BODY = "{\"error\":\"Not found\"}";

    /** Wire literal of {@code backend/app/main.py:L37}. */
    private static final String INTERNAL_SERVER_ERROR = "{\"error\":\"Internal server error\"}";

    private static final String ERROR_KEY = "error";

    /** Page number {@code backend/app/api/tweets.py:L12} declares as the default. */
    private static final int DEFAULT_PAGE = 1;

    /** Page size {@code backend/app/api/tweets.py:L13} declares as the default. */
    private static final int DEFAULT_PER_PAGE = 10;

    private static final String ROW_CONTENT = "AI coding tools still cannot get this right";

    private static final int ROW_LIKE_COUNT = 120;

    private static final LocalDateTime ROW_CREATED_AT = LocalDateTime.of(2026, 8, 1, 12, 30, 45);

    private static final String ROW_CREATED_AT_ON_THE_WIRE = "2026-08-01T12:30:45";

    private static final double ROW_DOUBT_RATING = 6.5d;

    private static final List<String> ROW_MEDIA =
            List.of("https://media.example/1.png", "https://media.example/2.png");

    private static final String ROW_USER_ID = "42";

    private static final List<String> ROW_AI_TOOLS_MENTIONED = List.of("GPT-4", "AI code assistant");

    private static final double SENTIMENT_SCORE = -0.25d;

    /** The two keys of {@code backend/app/api/tweets.py:L18-21}. */
    private static final String[] ENVELOPE_KEYS = { "tweets", "pagination" };

    /** The nine keys of {@code backend/app/schema/tweet.py:L6-14} — DL-022. */
    private static final String[] ROW_KEYS = { "id", "content", "like_count", "created_at",
            "doubt_rating", "media", "quoted_tweet_id", "user_id", "ai_tools_mentioned" };

    /** The four keys {@code dto.PaginationDto} declares — DL-038. */
    private static final String[] PAGINATION_KEYS = { "page", "per_page", "total", "total_pages" };

    /** The two keys of {@code backend/app/api/tweets.py:L52-55} — DL-037. */
    private static final String[] ANALYSIS_KEYS = { "tweet_id", "analysis_result" };

    /** The member names {@code frontend/src/schema/tweet.ts:L6-12} declares — DL-022, DL-059. */
    private static final String[] FRONTEND_ROW_KEYS =
            { "likeCount", "createdAt", "doubtRating", "quotedTweetId", "userId", "aiToolsMentioned" };

    /** Member names asserted absent from the {@code pagination} object — DL-038. */
    private static final String[] PAGE_KEYS = { "number", "size", "numberOfElements", "first", "last",
            "empty", "sort", "pageable" };

    /** Member names asserted absent from {@code analysis_result} — DL-037. */
    private static final String[] COMPOSITE_ANALYSIS_KEYS = { "score", "doubt_rating", "sentiment" };

    private static final String OVERLONG_ID = "9".repeat(2048);

    private static final String UNUSUAL_ID = "~id_2026.08.05-x";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TwitterService twitterService;

    // =====================================================================
    // GET /tweets — backend/app/api/tweets.py:L9-21
    // =====================================================================

    @Test
    @DisplayName("GET /tweets answers 200 with the tweets and pagination pair and nothing else")
    void listAnswers200WithTheTweetsAndPaginationPairAndNothingElse() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets").isArray())
                .andExpect(jsonPath("$.tweets", hasSize(1)))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ENVELOPE_KEYS);
    }

    // A row carrying no doubt rating is answered as part of its page rather than making the whole page
    // unanswerable — DL-080 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets answers 200 with a null doubt_rating for a row that carries no rating, "
            + "and answers the rated rows of the same page alongside it")
    void listAnswers200WithANullDoubtRatingForARowThatCarriesNoRating() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(unanalysedRow(), row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 2L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets", hasSize(2)))
                .andExpect(jsonPath("$.tweets[0].doubt_rating").value(nullValue()))
                .andExpect(jsonPath("$.tweets[0].content").value(ROW_CONTENT))
                .andExpect(jsonPath("$.tweets[0].like_count").value(ROW_LIKE_COUNT))
                .andExpect(jsonPath("$.tweets[1].doubt_rating").value(ROW_DOUBT_RATING))
                .andExpect(jsonPath("$.pagination.total").value(2))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ENVELOPE_KEYS);
    }

    // The same row on the detail route — DL-080 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets/{tweetId} answers 200 with a null doubt_rating for a row that carries "
            + "no rating")
    void detailAnswers200WithANullDoubtRatingForARowThatCarriesNoRating() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenReturn(unanalysedRow());

        mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TWEET_ID))
                .andExpect(jsonPath("$.doubt_rating").value(nullValue()))
                .andExpect(jsonPath("$.content").value(ROW_CONTENT))
                .andExpect(jsonPath("$.user_id").value(ROW_USER_ID));
    }

    // Wire spelling per_page — backend/app/api/tweets.py:L13 — DL-059 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets reads the page and size the per_page spelling carries")
    void listReadsThePageAndSizeThePerPageSpellingCarries() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt())).thenReturn(emptyPage(3, 25));

        mockMvc.perform(get(LIST_ROUTE).param("page", "3").param("per_page", "25")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> capturedPage = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> capturedPerPage = ArgumentCaptor.forClass(Integer.class);
        verify(twitterService).getPaginatedTweets(capturedPage.capture(), capturedPerPage.capture());
        assertThat(capturedPage.getValue()).isEqualTo(3);
        assertThat(capturedPerPage.getValue()).isEqualTo(25);
    }

    // The camelCase spelling of frontend/src/services/api.ts:L25 is not bound — DL-059 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets ignores the camelCase perPage spelling and applies the declared default")
    void listIgnoresTheCamelCasePerPageSpelling() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(emptyPage(3, DEFAULT_PER_PAGE));

        mockMvc.perform(get(LIST_ROUTE).param("page", "3").param("perPage", "25")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> capturedPage = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> capturedPerPage = ArgumentCaptor.forClass(Integer.class);
        verify(twitterService).getPaginatedTweets(capturedPage.capture(), capturedPerPage.capture());
        assertThat(capturedPage.getValue()).isEqualTo(3);
        assertThat(capturedPerPage.getValue()).isEqualTo(DEFAULT_PER_PAGE);
    }

    // Declared defaults 1 and 10 — backend/app/api/tweets.py:L12-13
    @Test
    @DisplayName("GET /tweets reads page 1 of 10 when the request carries no pagination parameter")
    void listReadsPage1Of10WhenTheRequestCarriesNoPaginationParameter() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(emptyPage(DEFAULT_PAGE, DEFAULT_PER_PAGE));

        mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> capturedPage = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> capturedPerPage = ArgumentCaptor.forClass(Integer.class);
        verify(twitterService).getPaginatedTweets(capturedPage.capture(), capturedPerPage.capture());
        assertThat(capturedPage.getValue()).isEqualTo(DEFAULT_PAGE);
        assertThat(capturedPerPage.getValue()).isEqualTo(DEFAULT_PER_PAGE);
    }

    // Pagination envelope keys and the 1-based wire page — DL-038 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets reports the four pagination keys with a 1-based page")
    void listReportsTheFourPaginationKeysWithA1BasedPage() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), 1, 10, 25L, 3));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.per_page").value(10))
                .andExpect(jsonPath("$.pagination.total").value(25))
                .andExpect(jsonPath("$.pagination.total_pages").value(3))
                .andExpectAll(absent("$.pagination", PAGE_KEYS))
                .andReturn();

        assertThat(nestedMap(bodyAsMap(result), "pagination")).containsOnlyKeys(PAGINATION_KEYS);
    }

    // Nine snake_case member names — DL-022 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets serialises each row with nine snake_case keys and no camelCase key")
    void listSerialisesEachRowWithNineSnakeCaseKeys() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpectAll(rowMatchers("$.tweets[0]"))
                .andExpectAll(absent("$.tweets[0]", FRONTEND_ROW_KEYS))
                .andReturn();

        assertThat(rowAt(bodyAsMap(result))).containsOnlyKeys(ROW_KEYS);
    }

    // The tweets.id column is Column(Integer, primary_key=True) at backend/app/db/models.py:L10 —
    // DL-023 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets serialises the row identifier as a JSON string")
    void listSerialisesTheRowIdentifierAsAJsonString() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets[0].id").value(isA(String.class)))
                .andExpect(jsonPath("$.tweets[0].id").value(TWEET_ID))
                .andReturn();

        assertThat(rowAt(bodyAsMap(result)).get("id")).isInstanceOf(String.class).isEqualTo(TWEET_ID);
    }

    // Two single Column(String) values at backend/app/db/models.py:L15,L18 — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets serialises the media and ai_tools_mentioned columns as JSON arrays")
    void listSerialisesTheDelimitedColumnsAsJsonArrays() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets[0].media").isArray())
                .andExpect(jsonPath("$.tweets[0].media", hasSize(ROW_MEDIA.size())))
                .andExpect(jsonPath("$.tweets[0].media[0]").value(ROW_MEDIA.get(0)))
                .andExpect(jsonPath("$.tweets[0].media[1]").value(ROW_MEDIA.get(1)))
                .andExpect(jsonPath("$.tweets[0].ai_tools_mentioned").isArray())
                .andExpect(jsonPath("$.tweets[0].ai_tools_mentioned",
                        hasSize(ROW_AI_TOOLS_MENTIONED.size())))
                .andExpect(jsonPath("$.tweets[0].ai_tools_mentioned[0]")
                        .value(ROW_AI_TOOLS_MENTIONED.get(0)))
                .andExpect(jsonPath("$.tweets[0].ai_tools_mentioned[1]")
                        .value(ROW_AI_TOOLS_MENTIONED.get(1)))
                .andReturn();

        Map<String, Object> row = rowAt(bodyAsMap(result));
        assertThat(row.get("media")).isEqualTo(ROW_MEDIA);
        assertThat(row.get("ai_tools_mentioned")).isEqualTo(ROW_AI_TOOLS_MENTIONED);
    }

    // quoted_tweet_id is the sole Optional[str] field of backend/app/schema/tweet.py:L12
    @Test
    @DisplayName("GET /tweets carries a quoted_tweet_id member holding null when the row quotes none")
    void listCarriesAQuotedTweetIdMemberHoldingNull() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(row()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> row = rowAt(bodyAsMap(result));
        assertThat(row).containsKey("quoted_tweet_id");
        assertThat(row.get("quoted_tweet_id")).isNull();
        assertThat(result.getResponse().getContentAsString()).contains("\"quoted_tweet_id\":null");
    }

    // A row stored but not yet analysed renders its rating as JSON null — DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets answers 200 and carries a doubt_rating member holding null for a row "
            + "that has not been analysed")
    void listAnswers200AndCarriesANullDoubtRatingForAnUnanalysedRow() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(page(List.of(unanalysedRow()), DEFAULT_PAGE, DEFAULT_PER_PAGE, 1L, 1));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets[0].id").value(TWEET_ID))
                .andExpect(jsonPath("$.tweets[0].doubt_rating").doesNotExist())
                .andReturn();

        Map<String, Object> row = rowAt(bodyAsMap(result));
        assertThat(row).containsKey("doubt_rating");
        assertThat(row.get("doubt_rating")).isNull();
        assertThat(result.getResponse().getContentAsString()).contains("\"doubt_rating\":null");
    }

    // A row stored but not yet analysed renders its rating as JSON null — DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets/{tweetId} answers 200 and carries a doubt_rating member holding null "
            + "for a row that has not been analysed")
    void detailAnswers200AndCarriesANullDoubtRatingForAnUnanalysedRow() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenReturn(unanalysedRow());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TWEET_ID))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"doubt_rating\":null");
    }

    @Test
    @DisplayName("GET /tweets answers 200 with an empty array and the pagination keys for an empty page")
    void listAnswers200WithAnEmptyArrayForAnEmptyPage() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenReturn(emptyPage(DEFAULT_PAGE, DEFAULT_PER_PAGE));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets").isArray())
                .andExpect(jsonPath("$.tweets", hasSize(0)))
                .andExpect(jsonPath("$.pagination.page").value(DEFAULT_PAGE))
                .andExpect(jsonPath("$.pagination.per_page").value(DEFAULT_PER_PAGE))
                .andExpect(jsonPath("$.pagination.total").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0))
                .andReturn();

        Map<String, Object> body = bodyAsMap(result);
        assertThat(body).containsOnlyKeys(ENVELOPE_KEYS);
        assertThat(nestedMap(body, "pagination")).containsOnlyKeys(PAGINATION_KEYS);
    }

    // Query-parameter conversion parity with request.args.get(..., type=int) at
    // backend/app/api/tweets.py:L12-13 — DL-217 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] page={0} per_page={1}")
    @CsvSource({
            "2147483647,10",
            "2147483646,10",
            "99999999,99999999"
    })
    @DisplayName("GET /tweets answers 200 and passes an out-of-range page through unchanged")
    void listAnswers200AndPassesAnOutOfRangePageThroughUnchanged(int page, int perPage)
            throws Exception {
        when(twitterService.getPaginatedTweets(page, perPage)).thenReturn(emptyPage(page, perPage));

        mockMvc.perform(get(LIST_ROUTE)
                        .param("page", String.valueOf(page))
                        .param("per_page", String.valueOf(perPage))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweets").isArray())
                .andExpect(jsonPath("$.pagination.page").value(page));

        verify(twitterService).getPaginatedTweets(page, perPage);
    }

    @ParameterizedTest(name = "[{index}] page={0}")
    @ValueSource(strings = {"abc", "", " ", "3.5", "0x10", "1e3", "99999999999999999999", "--3"})
    @DisplayName("GET /tweets answers 200 with the declared default when page holds no whole number")
    void listAnswers200WithTheDeclaredDefaultWhenPageHoldsNoWholeNumber(String page)
            throws Exception {
        when(twitterService.getPaginatedTweets(DEFAULT_PAGE, DEFAULT_PER_PAGE))
                .thenReturn(emptyPage(DEFAULT_PAGE, DEFAULT_PER_PAGE));

        mockMvc.perform(get(LIST_ROUTE).param("page", page)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(DEFAULT_PAGE, DEFAULT_PER_PAGE);
    }

    @ParameterizedTest(name = "[{index}] per_page={0}")
    @ValueSource(strings = {"abc", "", " ", "2.5", "many"})
    @DisplayName("GET /tweets answers 200 with the declared default when per_page holds no whole number")
    void listAnswers200WithTheDeclaredDefaultWhenPerPageHoldsNoWholeNumber(String perPage)
            throws Exception {
        when(twitterService.getPaginatedTweets(DEFAULT_PAGE, DEFAULT_PER_PAGE))
                .thenReturn(emptyPage(DEFAULT_PAGE, DEFAULT_PER_PAGE));

        mockMvc.perform(get(LIST_ROUTE).param("per_page", perPage)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(DEFAULT_PAGE, DEFAULT_PER_PAGE);
    }

    // A parsed whole number reaches the service unchanged — DL-217 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets passes a negative page on unclamped")
    void listPassesANegativePageOnUnclamped() throws Exception {
        when(twitterService.getPaginatedTweets(-5, DEFAULT_PER_PAGE))
                .thenReturn(emptyPage(-5, DEFAULT_PER_PAGE));

        mockMvc.perform(get(LIST_ROUTE).param("page", "-5")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(-5, DEFAULT_PER_PAGE);
    }

    @Test
    @DisplayName("GET /tweets accepts a padded and signed pagination value")
    void listAcceptsAPaddedAndSignedPaginationValue() throws Exception {
        when(twitterService.getPaginatedTweets(4, 20)).thenReturn(emptyPage(4, 20));

        mockMvc.perform(get(LIST_ROUTE).param("page", " +4 ").param("per_page", " 20 ")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(twitterService).getPaginatedTweets(4, 20);
    }

    @Test
    @DisplayName("GET /tweets answers 500 with the internal server error envelope when the read fails")
    void listAnswers500WithTheInternalServerErrorEnvelope() throws Exception {
        when(twitterService.getPaginatedTweets(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("the page could not be read"));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);
    }

    // The eleven routes are served unprefixed; frontend/src/services/api.ts:L25 calls /api/tweets —
    // DL-059, DL-183 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /api/tweets does not reach the listing handler")
    void prefixedListRouteDoesNotReachTheListingHandler() throws Exception {
        MvcResult result = mockMvc.perform(get(PREFIXED_LIST_ROUTE)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(NOT_FOUND_BODY, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);
        assertThat(bodyAsMap(result)).containsEntry(ERROR_KEY, "Not found");
        verifyNoInteractions(twitterService);
    }

    // =====================================================================
    // GET /tweets/{tweetId} — backend/app/api/tweets.py:L23-32
    // =====================================================================

    @Test
    @DisplayName("GET /tweets/{tweetId} renders the addressed row unwrapped with nine snake_case keys")
    void readRendersTheAddressedRowUnwrapped() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenReturn(row());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpectAll(rowMatchers("$"))
                .andExpectAll(absent("$", FRONTEND_ROW_KEYS))
                .andExpect(jsonPath("$.id").value(isA(String.class)))
                .andExpect(jsonPath("$.media").isArray())
                .andExpect(jsonPath("$.media", hasSize(ROW_MEDIA.size())))
                .andExpect(jsonPath("$.ai_tools_mentioned").isArray())
                .andExpect(jsonPath("$.ai_tools_mentioned", hasSize(ROW_AI_TOOLS_MENTIONED.size())))
                .andReturn();

        Map<String, Object> body = bodyAsMap(result);
        assertThat(body).containsOnlyKeys(ROW_KEYS);
        assertThat(body.get("id")).isInstanceOf(String.class);
        assertThat(body.get("media")).isEqualTo(ROW_MEDIA);
        assertThat(body.get("ai_tools_mentioned")).isEqualTo(ROW_AI_TOOLS_MENTIONED);
    }

    @Test
    @DisplayName("GET /tweets/{tweetId} answers 404 with a single-key Tweet not found envelope")
    void readAnswers404WithASingleKeyTweetNotFoundEnvelope() throws Exception {
        when(twitterService.getTweet(TWEET_ID)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);
        assertThat(bodyAsMap(result).get(ERROR_KEY)).isEqualTo("Tweet not found");
    }

    // Flask's default path converter delivered a string at backend/app/api/tweets.py:L23 — DL-048 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] tweetId={0}")
    @ValueSource(strings = {"not-a-number", "abc-123", "9999999999999999999999", "~id_2026.08.05-x"})
    @DisplayName("GET /tweets/{tweetId} answers 404 when the identifier holds no decimal number")
    void readAnswers404WhenTheIdentifierHoldsNoDecimalNumber(String tweetId) throws Exception {
        when(twitterService.getTweet(tweetId)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + tweetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        int observed = result.getResponse().getStatus();
        assertThat(observed).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(observed).isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(observed).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

        ArgumentCaptor<String> addressed = ArgumentCaptor.forClass(String.class);
        verify(twitterService).getTweet(addressed.capture());
        assertThat(addressed.getValue()).isInstanceOf(String.class).isEqualTo(tweetId);
    }

    // No length, pattern or range constraint exists at backend/app/schema/tweet.py:L5-14 — DL-050 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("GET /tweets/{tweetId} answers 404 for a path segment of 2048 characters")
    void readAnswers404ForAPathSegmentOf2048Characters() throws Exception {
        when(twitterService.getTweet(OVERLONG_ID)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + OVERLONG_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(twitterService).getTweet(OVERLONG_ID);
    }

    @Test
    @DisplayName("GET /tweets/{tweetId} answers 200 for a path segment holding unusual characters")
    void readAnswers200ForAPathSegmentHoldingUnusualCharacters() throws Exception {
        when(twitterService.getTweet(UNUSUAL_ID)).thenReturn(row());

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + UNUSUAL_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(bodyAsMap(result)).containsOnlyKeys(ROW_KEYS);
        verify(twitterService).getTweet(UNUSUAL_ID);
    }

    @Test
    @DisplayName("GET /tweets/{tweetId} answers 500 with the internal server error envelope on failure")
    void readAnswers500WithTheInternalServerErrorEnvelope() throws Exception {
        when(twitterService.getTweet(TWEET_ID))
                .thenThrow(new RuntimeException("the row could not be read"));

        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);
    }

    // =====================================================================
    // POST /tweets/{tweetId}/analyze — backend/app/api/tweets.py:L36-55
    // =====================================================================

    // The route delegates the read, the scoring and the write to one service operation — DL-263 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze delegates to one service operation and renders it")
    void analyzeDelegatesToOneServiceOperationAndRendersIt() throws Exception {
        when(twitterService.analyzeTweet(TWEET_ID)).thenReturn(SENTIMENT_SCORE);

        MvcResult result = mockMvc.perform(post(analyzeRoute(TWEET_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweet_id").value(TWEET_ID))
                .andExpect(jsonPath("$.analysis_result").value(SENTIMENT_SCORE))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ANALYSIS_KEYS);

        verify(twitterService, times(1)).analyzeTweet(TWEET_ID);
        // The route reads no row and writes none of its own — DL-263
        verify(twitterService, never()).getTweet(any());
        verify(twitterService, never()).updateTweetAnalysis(any(), anyDouble());

        verifyNoMoreInteractions(twitterService);
    }

    // analysis_result is the score as a floating-point number — DL-037 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze renders analysis_result as a bare number")
    void analyzeRendersAnalysisResultAsABareNumber() throws Exception {
        when(twitterService.analyzeTweet(TWEET_ID)).thenReturn(SENTIMENT_SCORE);

        MvcResult result = mockMvc.perform(post(analyzeRoute(TWEET_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis_result").value(SENTIMENT_SCORE))
                .andExpectAll(absent("$.analysis_result", COMPOSITE_ANALYSIS_KEYS))
                .andExpect(jsonPath("$.doubt_rating").doesNotExist())
                .andReturn();

        Map<String, Object> body = bodyAsMap(result);
        assertThat(body.get("analysis_result")).isInstanceOf(Number.class);
        assertThat(body.get("analysis_result")).isNotInstanceOf(Map.class);
        assertThat(((Number) body.get("analysis_result")).doubleValue()).isEqualTo(SENTIMENT_SCORE);
    }

    // tweet_id is the path value as received — backend/app/api/tweets.py:L53 — DL-037 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] tweetId={0}")
    @ValueSource(strings = {"7", "007", "0000000000000000000042"})
    @DisplayName("POST /tweets/{tweetId}/analyze renders the identifier as received on the wire")
    void analyzeRendersTheIdentifierAsReceivedOnTheWire(String tweetId) throws Exception {
        when(twitterService.analyzeTweet(tweetId)).thenReturn(SENTIMENT_SCORE);

        MvcResult result = mockMvc.perform(post(analyzeRoute(tweetId))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tweet_id").value(isA(String.class)))
                .andExpect(jsonPath("$.tweet_id").value(tweetId))
                .andReturn();

        Map<String, Object> body = bodyAsMap(result);
        assertThat(body).containsOnlyKeys(ANALYSIS_KEYS);
        assertThat(body.get("tweet_id")).isInstanceOf(String.class).isEqualTo(tweetId);

        ArgumentCaptor<String> addressed = ArgumentCaptor.forClass(String.class);
        verify(twitterService).analyzeTweet(addressed.capture());
        assertThat(addressed.getValue()).isEqualTo(tweetId);
    }

    // The branch of backend/app/api/tweets.py:L42-43, carrying the literal of :L32
    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze answers 404 and scores nothing for an absent row")
    void analyzeAnswers404AndScoresNothingForAnAbsentRow() throws Exception {
        when(twitterService.analyzeTweet(TWEET_ID)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(post(analyzeRoute(TWEET_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);

        verify(twitterService, never()).updateTweetAnalysis(any(), anyDouble());
        verify(twitterService, never()).getTweet(any());

        verify(twitterService).analyzeTweet(TWEET_ID);
        verifyNoMoreInteractions(twitterService);
    }

    // Flask's default path converter delivered a string at backend/app/api/tweets.py:L36 — DL-048 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] tweetId={0}")
    @ValueSource(strings = {"not-a-number", "abc-123", "~id_2026.08.05-x"})
    @DisplayName("POST /tweets/{tweetId}/analyze answers 404 when the identifier holds no decimal number")
    void analyzeAnswers404WhenTheIdentifierHoldsNoDecimalNumber(String tweetId) throws Exception {
        when(twitterService.analyzeTweet(tweetId)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(post(analyzeRoute(tweetId))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        int observed = result.getResponse().getStatus();
        assertThat(observed).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(observed).isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(observed).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

        ArgumentCaptor<String> addressed = ArgumentCaptor.forClass(String.class);
        verify(twitterService).analyzeTweet(addressed.capture());
        assertThat(addressed.getValue()).isInstanceOf(String.class).isEqualTo(tweetId);
        verify(twitterService, never()).updateTweetAnalysis(any(), anyDouble());
    }

    // No length, pattern or range constraint exists at backend/app/schema/tweet.py:L5-14 — DL-050 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze answers 404 for a path segment of 2048 characters")
    void analyzeAnswers404ForAPathSegmentOf2048Characters() throws Exception {
        when(twitterService.analyzeTweet(OVERLONG_ID)).thenThrow(NotFoundException.tweetNotFound());

        MvcResult result = mockMvc.perform(post(analyzeRoute(OVERLONG_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(TWEET_NOT_FOUND, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(twitterService).analyzeTweet(OVERLONG_ID);
    }

    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze answers 500 with the internal server error envelope")
    void analyzeAnswers500WithTheInternalServerErrorEnvelope() throws Exception {
        when(twitterService.analyzeTweet(TWEET_ID))
                .thenThrow(new RuntimeException("the document could not be scored"));

        MvcResult result = mockMvc.perform(post(analyzeRoute(TWEET_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(INTERNAL_SERVER_ERROR, JsonCompareMode.STRICT))
                .andReturn();

        assertThat(bodyAsMap(result)).containsOnlyKeys(ERROR_KEY);
        verify(twitterService, never()).updateTweetAnalysis(any(), anyDouble());
    }

    // =====================================================================
    // Authentication — the bare @jwt_required sites at
    // backend/app/api/tweets.py:L10,L24,L37 enforced nothing — DL-021
    // =====================================================================

    @Test
    @DisplayName("GET /tweets answers a bare 401 with an empty body when the request carries no token")
    void listAnswersABare401WhenTheRequestCarriesNoToken() throws Exception {
        MvcResult result = mockMvc.perform(get(LIST_ROUTE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertBare401(result);
        verifyNoInteractions(twitterService);
    }

    @Test
    @DisplayName("GET /tweets/{tweetId} answers a bare 401 with an empty body when no token is carried")
    void readAnswersABare401WhenTheRequestCarriesNoToken() throws Exception {
        MvcResult result = mockMvc.perform(get(LIST_ROUTE + "/" + TWEET_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertBare401(result);
        verifyNoInteractions(twitterService);
    }

    @Test
    @DisplayName("POST /tweets/{tweetId}/analyze answers a bare 401 with an empty body when no token is carried")
    void analyzeAnswersABare401WhenTheRequestCarriesNoToken() throws Exception {
        MvcResult result = mockMvc.perform(post(analyzeRoute(TWEET_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertBare401(result);
        verifyNoInteractions(twitterService);
    }

    @Test
    @DisplayName("GET /tweets answers a bare 401 when the bearer token is not one this service minted")
    void listAnswersABare401WhenTheBearerTokenIsNotOneThisServiceMinted() throws Exception {
        MvcResult result = mockMvc.perform(get(LIST_ROUTE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""))
                .andReturn();

        assertBare401(result);
        verifyNoInteractions(twitterService);
    }

    private String bearer() {
        return "Bearer " + jwtService.generateToken(PRINCIPAL);
    }

    /**
     * Builds the analyze route addressing one identifier.
     *
     * @param tweetId the path value to address
     * @return the request path of {@code backend/app/api/tweets.py:L36}
     */
    private static String analyzeRoute(String tweetId) {
        return LIST_ROUTE + "/" + tweetId + "/analyze";
    }

    /**
     * Builds the wire form of one fully populated {@code tweets} row.
     *
     * <p>{@code quotedTweetId} is {@code null}, the sole component
     * {@code backend/app/schema/tweet.py:L12} declares {@code Optional[str]}.
     *
     * @return the row, holding a value for each of the other eight components
     */
    private static TweetDto row() {
        return new TweetDto(TWEET_ID, ROW_CONTENT, ROW_LIKE_COUNT, ROW_CREATED_AT, ROW_DOUBT_RATING,
                ROW_MEDIA, null, ROW_USER_ID, ROW_AI_TOOLS_MENTIONED);
    }

    /**
     * Builds the wire form of a {@code tweets} row that has been stored but not yet analysed, so its
     * nullable {@code doubt_rating} column holds nothing — see docs/DECISION_LOG.md DL-080.
     *
     * @return the row, whose {@code doubt_rating} and {@code quoted_tweet_id} are both {@code null}
     */
    private static TweetDto unanalysedRow() {
        return new TweetDto(TWEET_ID, ROW_CONTENT, ROW_LIKE_COUNT, ROW_CREATED_AT, null,
                ROW_MEDIA, null, ROW_USER_ID, ROW_AI_TOOLS_MENTIONED);
    }

    /**
     * Builds an envelope carrying the supplied rows and counters.
     *
     * @param rows       the rows the {@code tweets} member carries
     * @param page       the 1-based page number the envelope reports
     * @param perPage    the page size the envelope reports
     * @param total      the matching row count the envelope reports
     * @param totalPages the page count the envelope reports
     * @return the envelope
     */
    private static PaginatedTweetsDto page(List<TweetDto> rows, int page, int perPage, long total,
            int totalPages) {
        return new PaginatedTweetsDto(rows, new PaginationDto(page, perPage, total, totalPages));
    }

    private static PaginatedTweetsDto emptyPage(int page, int perPage) {
        return page(List.of(), page, perPage, 0L, 0);
    }

    private Map<String, Object> bodyAsMap(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> body, String key) {
        assertThat(body.get(key)).isInstanceOf(Map.class);
        return (Map<String, Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowAt(Map<String, Object> body) {
        assertThat(body.get("tweets")).isInstanceOf(List.class);
        List<Object> rows = (List<Object>) body.get("tweets");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).isInstanceOf(Map.class);
        return (Map<String, Object>) rows.get(0);
    }

    private static ResultMatcher[] rowMatchers(String path) {
        return new ResultMatcher[] {
                jsonPath(path + ".id").value(TWEET_ID),
                jsonPath(path + ".content").value(ROW_CONTENT),
                jsonPath(path + ".like_count").value(ROW_LIKE_COUNT),
                jsonPath(path + ".created_at").value(ROW_CREATED_AT_ON_THE_WIRE),
                jsonPath(path + ".doubt_rating").value(ROW_DOUBT_RATING),
                jsonPath(path + ".media[0]").value(ROW_MEDIA.get(0)),
                jsonPath(path + ".media[1]").value(ROW_MEDIA.get(1)),
                jsonPath(path + ".user_id").value(ROW_USER_ID),
                jsonPath(path + ".ai_tools_mentioned[0]").value(ROW_AI_TOOLS_MENTIONED.get(0)),
                jsonPath(path + ".ai_tools_mentioned[1]").value(ROW_AI_TOOLS_MENTIONED.get(1))
        };
    }

    private static ResultMatcher[] absent(String path, String[] keys) {
        ResultMatcher[] matchers = new ResultMatcher[keys.length];
        for (int index = 0; index < keys.length; index++) {
            matchers[index] = jsonPath(path + "." + keys[index]).doesNotExist();
        }
        return matchers;
    }

    private static void assertBare401(MvcResult result) throws Exception {
        int observed = result.getResponse().getStatus();
        assertThat(observed).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(observed).isNotEqualTo(HttpStatus.FORBIDDEN.value());

        String body = result.getResponse().getContentAsString();
        assertThat(body).isEmpty();
        assertThat(body).doesNotContain(ERROR_KEY);
    }
}
