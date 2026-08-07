package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.TweetDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Ported from backend/app/services/notion_service.py:L5-53 (faithful port) — see docs/DECISION_LOG.md
// Replaces backend/tests/test_services.py:L24-38 — see docs/DECISION_LOG.md
/**
 * Exercises the three operations {@link NotionService} exposes and the requests it sends.
 *
 * <p>Transport is a stubbed {@link RestClient}, with one mock per fluent step of the page-creation,
 * the database-query and the page-update chain. No test opens a connection, resolves a credential or
 * reaches Notion. Configuration is a real {@link ScannerProperties} carrying a real
 * {@code scanner.notion} group.
 *
 * <p>Request payloads are captured with an {@link ArgumentCaptor} over the body handed to
 * {@link RestClient.RequestBodySpec#body(Object)} and read as a {@link JsonNode} tree. Notion
 * responses are trees built in this class.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotionService")
class NotionServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATABASE_ID = "notion-database-1a2b3c4d";

    private static final String OTHER_DATABASE_ID = "notion-database-9z8y7x6w";

    private static final String API_KEY = "not-a-real-notion-credential";

    private static final String PAGES_PATH = "/v1/pages";

    private static final String PAGE_PATH = "/v1/pages/{pageId}";

    private static final String DATABASE_QUERY_PATH = "/v1/databases/{databaseId}/query";

    private static final String NOTION_API_BASE_URL = "https://api.notion.com";

    private static final String NOTION_VERSION_HEADER = "Notion-Version";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer";

    private static final String SCHEME_SEPARATOR = "://";

    private static final String CREATED_PAGE_ID = "8f14e45f-ea1a-4b2c-8d3e-000000000001";

    private static final String MATCHED_PAGE_ID = "8f14e45f-ea1a-4b2c-8d3e-000000000002";

    private static final String TWEET_ID = "1793355680000000001";

    private static final String TWEET_CONTENT = "Every AI coding tool review reads like an advert";

    private static final int LIKE_COUNT = 128;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 2, 14, 8, 45, 30);

    private static final String CREATED_AT_TEXT = "2026-02-14T08:45:30";

    private static final double DOUBT_RATING = 7.5d;

    private static final List<String> MEDIA = List.of("https://pbs.example/media/1.png");

    private static final String QUOTED_TWEET_ID = "1793355680000000002";

    private static final String USER_ID = "user-4242";

    private static final List<String> AI_TOOLS_MENTIONED = List.of("GitHub Copilot", "Cursor");

    /**
     * Value of {@link TweetDto#media()} carrying an element that itself holds the delimiter — DL-164
     * — see docs/DECISION_LOG.md.
     */
    private static final List<String> EDGE_CASE_MEDIA =
            List.of("https://pbs.example/a,b.png", "https://pbs.example/c.png");

    /**
     * Value of {@link TweetDto#aiToolsMentioned()} carrying a backslash, a trailing backslash,
     * surrounding whitespace, interior whitespace and a blank element — DL-164 — see
     * docs/DECISION_LOG.md.
     */
    private static final List<String> EDGE_CASE_AI_TOOLS = Arrays.asList("C:\\tools\\codeium",
            "Cursor\\", "  Copilot  ", "Cursor  Editor", "   ", null);

    private static final int LIMIT = 25;

    private static final int GUARDED_VALUE_LIMIT = 64;

    private static final String START_CURSOR = "MTc5MzM1NTY4MDAwMDAwMDAwMQ";

    /** {@code page_size} sent when the requested limit is not positive — DL-154. */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** Largest {@code page_size} Notion accepts, and the cap applied above it — DL-154. */
    private static final int MAXIMUM_PAGE_SIZE = 100;

    private static final String RESPONSE_TEXT = "Benchmarks and a repeatable harness would settle it.";

    private static final String PROPERTY_CONTENT = "Content";

    private static final String PROPERTY_AUTHOR = "Author";

    private static final String PROPERTY_TIMESTAMP = "Timestamp";

    private static final String PROPERTY_DOUBT_RATING = "Doubt Rating";

    private static final String PROPERTY_ENGAGEMENT = "Engagement";

    private static final String PROPERTY_TWEET_ID = "Tweet Id";

    private static final String PROPERTY_MEDIA = "Media";

    private static final String PROPERTY_QUOTED_TWEET_ID = "Quoted Tweet Id";

    private static final String PROPERTY_AI_TOOLS_MENTIONED = "AI Tools Mentioned";

    private static final String PROPERTY_RESPONSE = "Response";

    /** Select property named at {@code backend/app/services/notion_service.py:L18}. */
    private static final String PROPERTY_SENTIMENT = "Sentiment";

    private static final String KEY_TITLE = "title";

    private static final String KEY_RICH_TEXT = "rich_text";

    private static final String KEY_TEXT = "text";

    private static final String KEY_CONTENT = "content";

    private static final String KEY_DATE = "date";

    private static final String KEY_START = "start";

    private static final String KEY_NUMBER = "number";

    private static final String KEY_SELECT = "select";

    private static final String KEY_PARENT = "parent";

    private static final String KEY_DATABASE_ID = "database_id";

    private static final String KEY_PROPERTIES = "properties";

    private static final String KEY_ID = "id";

    private static final String KEY_RESULTS = "results";

    private static final String KEY_PAGE_SIZE = "page_size";

    private static final String KEY_START_CURSOR = "start_cursor";

    private static final String KEY_FILTER = "filter";

    private static final String KEY_PROPERTY = "property";

    private static final String KEY_EQUALS = "equals";

    private static final String REPOSITORY_SUFFIX = "Repository";

    private static final String REPOSITORY_PACKAGE = "com.codeskeptic.scanner.repository";

    private static final String ENTITY_PACKAGE = "com.codeskeptic.scanner.entity";

    private static final String SPRING_DATA_PACKAGE = "org.springframework.data";

    private static final String BEAN_VALIDATION_PACKAGE = "jakarta.validation";

    private static final String VALIDATOR_PACKAGE = "org.hibernate.validator";

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec postSpec;

    @Mock
    private RestClient.RequestBodyUriSpec patchSpec;

    @Mock
    private RestClient.RequestBodySpec pageCreationSpec;

    @Mock
    private RestClient.RequestBodySpec databaseQuerySpec;

    @Mock
    private RestClient.RequestBodySpec pageUpdateSpec;

    @Mock
    private RestClient.ResponseSpec pageCreationResponse;

    @Mock
    private RestClient.ResponseSpec databaseQueryResponse;

    @Mock
    private RestClient.ResponseSpec pageUpdateResponse;

    private NotionService service;

    @BeforeEach
    void createService() {
        service = serviceCarrying(DATABASE_ID);
    }

    @Test
    @DisplayName("writes the content property as a title carrying the post body")
    void writesTheContentPropertyAsATitleCarryingThePostBody() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode content = storedProperty(PROPERTY_CONTENT);
        assertThat(content.has(KEY_TITLE)).isTrue();
        assertThat(content.has(KEY_RICH_TEXT)).isFalse();
        assertThat(textOf(content, KEY_TITLE)).isEqualTo(TWEET_CONTENT);
    }

    // Content longer than the provider's per-item ceiling is carried as consecutive items and is not
    // refused, and concatenating them reproduces it exactly — DL-292 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0} character(s) -> {1} item(s)")
    @CsvSource({
        "1, 1",
        "1999, 1",
        "2000, 1",
        "2001, 2",
        "4000, 2",
        "4001, 3",
        "6000, 3",
        "12345, 7"
    })
    @DisplayName("carries a title longer than the provider's per-item ceiling as consecutive items "
            + "that reproduce it exactly")
    void carriesALongTitleAsConsecutiveItems(int characters, int expectedItems) {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        String body = bodyOfLength(characters);

        service.storeTweet(tweetCarryingContent(body));

        JsonNode items = storedProperty(PROPERTY_CONTENT).path(KEY_TITLE);
        assertThat(items.size()).as("title items for %s character(s)", characters)
                .isEqualTo(expectedItems);
        for (JsonNode item : items) {
            assertThat(item.path(KEY_TEXT).path(KEY_CONTENT).asText().length())
                    .as("length of one title item").isBetween(1, 2_000);
        }
        assertThat(concatenatedText(items)).as("concatenated title text").isEqualTo(body);
    }

    // The reverse mapping concatenates every item, so a value the write side split is reassembled —
    // DL-292 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reads a title split across items back as the whole value")
    void readsATitleSplitAcrossItemsBackAsTheWholeValue() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        String body = bodyOfLength(5_000);
        service.storeTweet(tweetCarryingContent(body));
        JsonNode written = storedProperties();
        assertThat(written.path(PROPERTY_CONTENT).path(KEY_TITLE).size())
                .as("items the write side produced").isEqualTo(3);

        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, written)));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).as("mirrored posts").hasSize(1);
        assertThat(mirrored.get(0).content()).as("reassembled content").isEqualTo(body);
    }

    private static String bodyOfLength(int characters) {
        StringBuilder text = new StringBuilder(characters);
        for (int index = 0; index < characters; index++) {
            text.append((char) ('a' + (index % 26)));
        }
        return text.toString();
    }

    private static String concatenatedText(JsonNode items) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : items) {
            text.append(item.path(KEY_TEXT).path(KEY_CONTENT).asText());
        }
        return text.toString();
    }

    private static TweetDto tweetCarryingContent(String content) {
        return new TweetDto(TWEET_ID, content, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA,
                QUOTED_TWEET_ID, USER_ID, AI_TOOLS_MENTIONED);
    }

    @Test
    @DisplayName("writes the author property as rich text carrying the user id")
    void writesTheAuthorPropertyAsRichTextCarryingTheUserId() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode author = storedProperty(PROPERTY_AUTHOR);
        assertThat(author.has(KEY_RICH_TEXT)).isTrue();
        assertThat(author.has(KEY_TITLE)).isFalse();
        assertThat(textOf(author, KEY_RICH_TEXT)).isEqualTo(USER_ID);
        assertThat(textOf(author, KEY_RICH_TEXT)).isNotEqualTo(TWEET_CONTENT);
    }

    @Test
    @DisplayName("writes the timestamp property as a date carrying the created at value")
    void writesTheTimestampPropertyAsADateCarryingTheCreatedAtValue() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode timestamp = storedProperty(PROPERTY_TIMESTAMP);
        assertThat(timestamp.has(KEY_DATE)).isTrue();
        assertThat(timestamp.path(KEY_DATE).path(KEY_START).asText()).isEqualTo(CREATED_AT_TEXT);
    }

    @Test
    @DisplayName("writes the doubt rating property as a number carrying the doubt rating")
    void writesTheDoubtRatingPropertyAsANumberCarryingTheDoubtRating() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode doubtRating = storedProperty(PROPERTY_DOUBT_RATING);
        assertThat(doubtRating.has(KEY_NUMBER)).isTrue();
        assertThat(doubtRating.has(KEY_SELECT)).isFalse();
        assertThat(doubtRating.path(KEY_NUMBER).isNumber()).isTrue();
        assertThat(doubtRating.path(KEY_NUMBER).doubleValue()).isEqualTo(DOUBT_RATING);
    }

    @Test
    @DisplayName("writes the engagement property as a number carrying the like count")
    void writesTheEngagementPropertyAsANumberCarryingTheLikeCount() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode engagement = storedProperty(PROPERTY_ENGAGEMENT);
        assertThat(engagement.has(KEY_NUMBER)).isTrue();
        assertThat(engagement.path(KEY_NUMBER).isNumber()).isTrue();
        assertThat(engagement.path(KEY_NUMBER).intValue()).isEqualTo(LIKE_COUNT);
    }

    @Test
    @DisplayName("writes the tweet id property as rich text carrying the post id on every stored page")
    void writesTheTweetIdPropertyAsRichTextCarryingThePostIdOnEveryStoredPage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());
        service.storeTweet(tweetCarryingId(TWEET_ID + "9"));

        List<JsonNode> storedPages = capturedBodies(pageCreationSpec, 2);
        assertThat(storedPages).hasSize(2);
        assertThat(propertyOf(storedPages.get(0), PROPERTY_TWEET_ID).has(KEY_RICH_TEXT)).isTrue();
        assertThat(textOf(propertyOf(storedPages.get(0), PROPERTY_TWEET_ID), KEY_RICH_TEXT))
                .isEqualTo(TWEET_ID);
        assertThat(textOf(propertyOf(storedPages.get(1), PROPERTY_TWEET_ID), KEY_RICH_TEXT))
                .isEqualTo(TWEET_ID + "9");
    }

    @Test
    @DisplayName("writes one property per mirrored post component on a stored page")
    void writesOnePropertyPerMirroredPostComponentOnAStoredPage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        assertThat(propertyNamesOf(storedProperties())).containsExactlyInAnyOrder(
                PROPERTY_CONTENT,
                PROPERTY_AUTHOR,
                PROPERTY_TIMESTAMP,
                PROPERTY_DOUBT_RATING,
                PROPERTY_ENGAGEMENT,
                PROPERTY_TWEET_ID);
    }

    // backend/app/schema/tweet.py:L12 is the sole Optional[str] field — DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("omits a property whose component the post does not carry")
    void omitsAPropertyWhoseComponentThePostDoesNotCarry() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        TweetDto quotingNothing = new TweetDto(TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT,
                DOUBT_RATING, List.of(), null, USER_ID, List.of());

        service.storeTweet(quotingNothing);

        assertThat(propertyNamesOf(storedProperties()))
                .doesNotContain(PROPERTY_QUOTED_TWEET_ID)
                .contains(PROPERTY_CONTENT, PROPERTY_TWEET_ID);
    }

    @Test
    @DisplayName("writes none of the three properties the mirror contract withdraws")
    void writesNoneOfTheThreePropertiesTheMirrorContractWithdraws() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweetCarryingLists(EDGE_CASE_MEDIA, EDGE_CASE_AI_TOOLS));

        assertThat(propertyNamesOf(storedProperties()))
                .doesNotContain(PROPERTY_MEDIA, PROPERTY_QUOTED_TWEET_ID,
                        PROPERTY_AI_TOOLS_MENTIONED);
        assertThat(storedProperties().has(PROPERTY_MEDIA)).isFalse();
        assertThat(storedProperties().has(PROPERTY_QUOTED_TWEET_ID)).isFalse();
        assertThat(storedProperties().has(PROPERTY_AI_TOOLS_MENTIONED)).isFalse();
    }

    @Test
    @DisplayName("reads the two withdrawn list components back as empty lists and the quoted post id "
            + "as absent")
    void readsTheWithdrawnComponentsBackAsEmptyAndAbsent() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweetCarryingLists(EDGE_CASE_MEDIA, EDGE_CASE_AI_TOOLS));
        JsonNode written = storedProperties();

        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, written)));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).hasSize(1);
        assertThat(mirrored.get(0).media()).isEmpty();
        assertThat(mirrored.get(0).aiToolsMentioned()).isEmpty();
        assertThat(mirrored.get(0).quotedTweetId()).isNull();
    }

    @Test
    @DisplayName("writes no sentiment property on a stored page")
    void writesNoSentimentPropertyOnAStoredPage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        assertThat(storedProperties().has(PROPERTY_SENTIMENT)).isFalse();
        assertThat(propertyNamesOf(storedProperties()))
                .isNotEmpty()
                .doesNotContain(PROPERTY_SENTIMENT);
    }

    @Test
    @DisplayName("writes no response property on a stored page")
    void writesNoResponsePropertyOnAStoredPage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        assertThat(storedProperties().has(PROPERTY_RESPONSE)).isFalse();
        assertThat(propertyNamesOf(storedProperties()))
                .isNotEmpty()
                .doesNotContain(PROPERTY_RESPONSE);
    }

    @Test
    @DisplayName("sends the parent and the properties as the only members of a creation request")
    void sendsTheParentAndThePropertiesAsTheOnlyMembersOfACreationRequest() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        assertThat(propertyNamesOf(capturedBody(pageCreationSpec)))
                .containsExactly(KEY_PARENT, KEY_PROPERTIES);
    }

    @Test
    @DisplayName("returns the identifier of the created page as a string")
    void returnsTheIdentifierOfTheCreatedPageAsAString() throws NoSuchMethodException {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        String created = service.storeTweet(tweet());

        assertThat(created).isEqualTo(CREATED_PAGE_ID);
        assertThat(NotionService.class.getDeclaredMethod("storeTweet", TweetDto.class).getReturnType())
                .isEqualTo(String.class);
    }

    @Test
    @DisplayName("sends the configured database id as the parent of the created page")
    void sendsTheConfiguredDatabaseIdAsTheParentOfTheCreatedPage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        JsonNode parent = capturedBody(pageCreationSpec).path(KEY_PARENT);
        assertThat(propertyNamesOf(parent)).containsExactly(KEY_DATABASE_ID);
        assertThat(parent.path(KEY_DATABASE_ID).asText()).isEqualTo(DATABASE_ID);
    }

    @Test
    @DisplayName("sends a second configured database id as the parent of the created page")
    void sendsASecondConfiguredDatabaseIdAsTheParentOfTheCreatedPage() {
        service = serviceCarrying(OTHER_DATABASE_ID);
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));

        service.storeTweet(tweet());

        assertThat(capturedBody(pageCreationSpec).path(KEY_PARENT).path(KEY_DATABASE_ID).asText())
                .isEqualTo(OTHER_DATABASE_ID);
    }

    @Test
    @DisplayName("sends the configured database id as the queried database")
    void sendsTheConfiguredDatabaseIdAsTheQueriedDatabase() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        assertThat(capturedUriVariable(postSpec)).isEqualTo(DATABASE_ID);
        assertThat(capturedUriTemplate(postSpec)).isEqualTo(DATABASE_QUERY_PATH);
    }

    @Test
    @DisplayName("sends a second configured database id as the queried database")
    void sendsASecondConfiguredDatabaseIdAsTheQueriedDatabase() {
        service = serviceCarrying(OTHER_DATABASE_ID);
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        assertThat(capturedUriVariable(postSpec)).isEqualTo(OTHER_DATABASE_ID);
    }

    @Test
    @DisplayName("sends the supplied limit as the page size")
    void sendsTheSuppliedLimitAsThePageSize() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue()).isEqualTo(LIMIT);
    }

    @ParameterizedTest(name = "a limit of {0} is sent as the default page size")
    @MethodSource("nonPositiveLimits")
    @DisplayName("sends the default page size when the limit is not positive")
    void sendsTheDefaultPageSizeWhenTheLimitIsNotPositive(int limit) {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(limit, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue())
                .isEqualTo(DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("sends a limit of exactly one hundred uncapped")
    void sendsALimitOfExactlyOneHundredUncapped() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(MAXIMUM_PAGE_SIZE, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue())
                .isEqualTo(MAXIMUM_PAGE_SIZE);
    }

    @ParameterizedTest(name = "a limit of {0} is capped at one hundred")
    @MethodSource("limitsAboveTheMaximum")
    @DisplayName("caps a limit above one hundred at the largest page size notion accepts")
    void capsALimitAboveOneHundredAtTheLargestPageSizeNotionAccepts(int limit) {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(limit, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue())
                .isEqualTo(MAXIMUM_PAGE_SIZE);
    }

    @Test
    @DisplayName("sends a lowest accepted page size of one when the limit is one")
    void sendsALowestAcceptedPageSizeOfOneWhenTheLimitIsOne() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(1, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("sends the supplied start cursor as the start cursor")
    void sendsTheSuppliedStartCursorAsTheStartCursor() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        JsonNode request = capturedBody(databaseQuerySpec);
        assertThat(request.has(KEY_START_CURSOR)).isTrue();
        assertThat(request.path(KEY_START_CURSOR).asText()).isEqualTo(START_CURSOR);
    }

    @Test
    @DisplayName("omits the start cursor and returns a list when no start cursor is supplied")
    void omitsTheStartCursorAndReturnsAListWhenNoStartCursorIsSupplied() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        List<TweetDto> mirrored = service.getTweets(LIMIT, null);

        assertThat(mirrored).isNotNull().isEmpty();
        JsonNode request = capturedBody(databaseQuerySpec);
        assertThat(request.has(KEY_START_CURSOR)).isFalse();
        assertThat(propertyNamesOf(request)).containsExactly(KEY_PAGE_SIZE);
    }

    @Test
    @DisplayName("returns an empty list when the query carries no result")
    void returnsAnEmptyListWhenTheQueryCarriesNoResult() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).isNotNull();
        assertThat(mirrored).isEmpty();
    }

    @Test
    @DisplayName("reads a stored page back through the property names it wrote")
    void readsAStoredPageBackThroughThePropertyNamesItWrote() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        JsonNode written = storedProperties();

        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, written)));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(propertyNamesOf(written)).contains(
                PROPERTY_CONTENT, PROPERTY_AUTHOR, PROPERTY_TIMESTAMP, PROPERTY_DOUBT_RATING,
                PROPERTY_ENGAGEMENT, PROPERTY_TWEET_ID);
        assertThat(mirrored).hasSize(1);
        TweetDto roundTripped = mirrored.get(0);
        assertThat(roundTripped.content()).isEqualTo(TWEET_CONTENT);
        assertThat(roundTripped.userId()).isEqualTo(USER_ID);
        assertThat(roundTripped.createdAt()).isEqualTo(CREATED_AT);
        assertThat(roundTripped.doubtRating()).isEqualTo(DOUBT_RATING);
        assertThat(roundTripped.likeCount()).isEqualTo(LIKE_COUNT);
        assertThat(roundTripped.id()).isEqualTo(TWEET_ID);
        assertThat(roundTripped.media()).isEmpty();
        assertThat(roundTripped.quotedTweetId()).isNull();
        assertThat(roundTripped.aiToolsMentioned()).isEmpty();
    }

    @Test
    @DisplayName("falls back to the notion page identifier when a page carries no mirrored tweet "
            + "identifier")
    void fallsBackToTheNotionPageIdentifierWhenNoMirroredTweetIdentifierIsCarried() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        JsonNode written = storedProperties();
        ObjectNode withoutTweetId = ((ObjectNode) written.deepCopy()).without(PROPERTY_TWEET_ID);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, withoutTweetId)));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).extracting(TweetDto::id).containsExactly(MATCHED_PAGE_ID);
    }

    @Test
    @DisplayName("substitutes the notion page identifier only for the page whose mirrored tweet "
            + "identifier is absent")
    void substitutesTheNotionPageIdentifierOnlyWhenTheTweetIdentifierIsAbsent() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        ObjectNode withoutTweetId =
                ((ObjectNode) storedProperties().deepCopy()).without(PROPERTY_TWEET_ID);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, withoutTweetId),
                pageCarrying(CREATED_PAGE_ID, storedProperties())));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).extracting(TweetDto::id)
                .containsExactly(MATCHED_PAGE_ID, TWEET_ID);
    }

    // A page carrying no value for a source-required component has no wire form — DL-080, DL-219 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {PROPERTY_CONTENT, PROPERTY_AUTHOR, PROPERTY_TIMESTAMP,
            PROPERTY_DOUBT_RATING, PROPERTY_ENGAGEMENT})
    @DisplayName("skips a mirrored page that omits a property the wire record declares required")
    void skipsAMirroredPageThatOmitsARequiredProperty(String omitted) {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        ObjectNode withoutARequiredProperty =
                ((ObjectNode) storedProperties().deepCopy()).without(omitted);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, withoutARequiredProperty)));

        assertThat(service.getTweets(LIMIT, START_CURSOR)).isEmpty();
    }

    @Test
    @DisplayName("reads only the complete pages of a response holding one incomplete page")
    void readsOnlyTheCompletePagesOfAResponseHoldingOneIncompletePage() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        ObjectNode withoutContent =
                ((ObjectNode) storedProperties().deepCopy()).without(PROPERTY_CONTENT);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, withoutContent),
                pageCarrying(CREATED_PAGE_ID, storedProperties())));

        assertThat(service.getTweets(LIMIT, START_CURSOR)).extracting(TweetDto::id)
                .containsExactly(TWEET_ID);
    }

    // backend/app/schema/tweet.py:L12 is the sole Optional[str] field — DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("carries a null quoted tweet id for a mirrored page that omits that property")
    void carriesANullQuotedTweetIdForAMirroredPageThatOmitsThatProperty() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        ObjectNode withoutQuotedTweetId =
                ((ObjectNode) storedProperties().deepCopy()).without(PROPERTY_QUOTED_TWEET_ID);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, withoutQuotedTweetId)));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).hasSize(1);
        assertThat(mirrored.get(0).quotedTweetId()).isNull();
        assertThat(mirrored.get(0).id()).isEqualTo(TWEET_ID);
    }

    // The provider explanation is reported by size, never by text — DL-269 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("records a rejection with the provider status, error code and request id, and the "
            + "explanation by length only")
    void recordsARejectionWithTheProviderFieldsAndNeverTheBody() {
        String explanation = "Media is not a property that exists.";
        String body = "{\"object\":\"error\",\"status\":400,\"code\":\"validation_error\","
                + "\"message\":\"" + explanation + "\"}";
        stubPost();
        when(postSpec.uri(PAGES_PATH)).thenThrow(rejection(HttpStatus.BAD_REQUEST, body, "req-9zk"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatThrownBy(() -> service.storeTweet(tweet()))
                    .isInstanceOf(HttpClientErrorException.class);

            String logged = onlyErrorRecord(recorded);
            assertThat(logged)
                    .contains("HTTP 400")
                    .contains("Notion code validation_error")
                    .contains("request id req-9zk")
                    .contains("explanation length " + explanation.length());
            assertThat(logged).doesNotContain(explanation);
            assertThat(logged).doesNotContain("\"object\"").doesNotContain("\"status\":400");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("carries no character of a control-character-bearing explanation into the record")
    void carriesNoCharacterOfAControlCharacterBearingExplanationIntoTheRecord() {
        String body = "{\"code\":\"validation_error\",\"message\":"
                + "\"denied\\r\\n2026-01-01 ERROR forged administrator record\\u0000tail\"}";
        stubPost();
        when(postSpec.uri(PAGES_PATH)).thenThrow(rejection(HttpStatus.BAD_REQUEST, body, "req-2"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatThrownBy(() -> service.storeTweet(tweet()))
                    .isInstanceOf(HttpClientErrorException.class);

            String logged = onlyErrorRecord(recorded);
            assertThat(logged).doesNotContain("forged").doesNotContain("denied");
            assertThat(logged).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0000");
            assertThat(logged).contains("explanation length 57");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("reports an absent explanation as a length below zero")
    void reportsAnAbsentExplanationAsALengthBelowZero() {
        stubPost();
        when(postSpec.uri(PAGES_PATH)).thenThrow(rejection(HttpStatus.BAD_REQUEST,
                "{\"code\":\"validation_error\"}", "req-3"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatThrownBy(() -> service.storeTweet(tweet()))
                    .isInstanceOf(HttpClientErrorException.class);

            assertThat(onlyErrorRecord(recorded)).contains("explanation length -1");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("records absent for an error code and a request id that fail their shape checks")
    void recordsAbsentForAnErrorCodeAndRequestIdThatFailTheirShapeChecks() {
        String body = "{\"code\":\"Validation Error\\ninjected\",\"message\":\"nope\"}";
        stubPost();
        when(postSpec.uri(PAGES_PATH))
                .thenThrow(rejection(HttpStatus.BAD_REQUEST, body, "bad id\nforged"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatThrownBy(() -> service.storeTweet(tweet()))
                    .isInstanceOf(HttpClientErrorException.class);

            String logged = onlyErrorRecord(recorded);
            assertThat(logged).contains("Notion code absent").contains("request id absent");
            assertThat(logged).doesNotContain("injected").doesNotContain("forged");
            assertThat(logged).doesNotContain("nope").contains("explanation length 4");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("carries no run of a five hundred character rejection message into the record")
    void carriesNoRunOfAFiveHundredCharacterRejectionMessageIntoTheRecord() {
        String longMessage = "x".repeat(500);
        String body = "{\"code\":\"validation_error\",\"message\":\"" + longMessage + "\"}";
        stubPost();
        when(postSpec.uri(PAGES_PATH)).thenThrow(rejection(HttpStatus.BAD_REQUEST, body, "req-1"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatThrownBy(() -> service.storeTweet(tweet()))
                    .isInstanceOf(HttpClientErrorException.class);

            assertThat(onlyErrorRecord(recorded))
                    .doesNotContain("xx")
                    .contains("explanation length 500");
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("records only the failure type when a rejection carries no HTTP response")
    void recordsOnlyTheFailureTypeWhenARejectionCarriesNoHttpResponse() {
        stubPost();
        when(postSpec.uri(PAGES_PATH)).thenThrow(new IllegalStateException("transport down"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            assertThatIllegalStateException().isThrownBy(() -> service.storeTweet(tweet()));

            String logged = onlyErrorRecord(recorded);
            assertThat(logged).contains("IllegalStateException").doesNotContain("transport down");
        } finally {
            detachAppender(recorded);
        }
    }

    private static HttpClientErrorException rejection(HttpStatus status, String body,
            String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("x-request-id", requestId);
        HttpClientErrorException answered = HttpClientErrorException
                .create(status, status.getReasonPhrase(), headers,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        answered.setBodyConvertFunction(type -> {
            try {
                return MAPPER.readTree(body);
            } catch (JsonProcessingException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        });
        return answered;
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(NotionService.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(NotionService.class)).detachAppender(appender);
    }

    private static String onlyErrorRecord(ListAppender<ILoggingEvent> appender) {
        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNull();
        return errors.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("reports a failure when a successful query answers with an empty body")
    void reportsAFailureWhenASuccessfulQueryAnswersWithAnEmptyBody() {
        stubPost();
        stubDatabaseQueryReturning(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getTweets(LIMIT, START_CURSOR))
                .withMessageContaining("empty body");
    }

    @Test
    @DisplayName("reports a failure when a successful query answers without a results array")
    void reportsAFailureWhenASuccessfulQueryAnswersWithoutAResultsArray() {
        stubPost();
        stubDatabaseQueryReturning(MAPPER.createObjectNode());

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getTweets(LIMIT, START_CURSOR))
                .withMessageContaining("results array");
    }

    @Test
    @DisplayName("reports a failure when a successful page creation answers with an empty body")
    void reportsAFailureWhenASuccessfulPageCreationAnswersWithAnEmptyBody() {
        stubPost();
        stubPageCreationReturning(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.storeTweet(tweet()))
                .withMessageContaining("empty body");
    }

    @Test
    @DisplayName("reports a failure when a successful page creation returns no page identifier")
    void reportsAFailureWhenASuccessfulPageCreationReturnsNoPageIdentifier() {
        stubPost();
        stubPageCreationReturning(MAPPER.createObjectNode());

        assertThatIllegalStateException()
                .isThrownBy(() -> service.storeTweet(tweet()))
                .withMessageContaining("page identifier");
    }

    @Test
    @DisplayName("reads every page of a multi page query result in the order notion returned them")
    void readsEveryPageOfAMultiPageQueryResultInTheOrderNotionReturnedThem() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        service.storeTweet(tweet());
        service.storeTweet(tweetCarryingId(TWEET_ID + "9"));
        List<JsonNode> written = capturedBodies(pageCreationSpec, 2);

        stubDatabaseQueryReturning(queryResultCarrying(
                pageCarrying(MATCHED_PAGE_ID, written.get(0).path(KEY_PROPERTIES)),
                pageCarrying(CREATED_PAGE_ID, written.get(1).path(KEY_PROPERTIES))));
        List<TweetDto> mirrored = service.getTweets(LIMIT, START_CURSOR);

        assertThat(mirrored).extracting(TweetDto::id).containsExactly(TWEET_ID, TWEET_ID + "9");
    }

    @Test
    @DisplayName("leaves notion untouched when the tweet id query matches no page")
    void leavesNotionUntouchedWhenTheTweetIdQueryMatchesNoPage() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        verify(restClient, never()).patch();
    }

    @Test
    @DisplayName("leaves notion untouched when every matched page carries no page identifier")
    void reportsAFailureWhenEveryMatchedPageCarriesNoPageIdentifier() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying("", noProperties())));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .withMessageContaining("no page identifier");
        verify(restClient, never()).patch();
    }

    @Test
    @DisplayName("reports a failure when the tweet id query answers with an empty body")
    void reportsAFailureWhenTheTweetIdQueryAnswersWithAnEmptyBody() {
        stubPost();
        stubDatabaseQueryReturning(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT));
        verify(restClient, never()).patch();
    }

    @Test
    @DisplayName("queries the tweet id property for the supplied post identifier")
    void queriesTheTweetIdPropertyForTheSuppliedPostIdentifier() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        JsonNode filter = capturedBody(databaseQuerySpec).path(KEY_FILTER);
        assertThat(filter.path(KEY_PROPERTY).asText()).isEqualTo(PROPERTY_TWEET_ID);
        assertThat(filter.path(KEY_RICH_TEXT).path(KEY_EQUALS).asText()).isEqualTo(TWEET_ID);
    }

    @Test
    @DisplayName("updates the page the tweet id query matched")
    void updatesThePageTheTweetIdQueryMatched() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        assertThat(capturedUriTemplate(patchSpec)).isEqualTo(PAGE_PATH);
        assertThat(capturedUriVariable(patchSpec)).isEqualTo(MATCHED_PAGE_ID);
    }

    @Test
    @DisplayName("executes the page update by taking the bodiless entity of the response")
    void executesThePageUpdateByTakingTheBodilessEntityOfTheResponse() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        InOrder execution = inOrder(restClient, patchSpec, pageUpdateSpec, pageUpdateResponse);
        execution.verify(restClient).patch();
        execution.verify(patchSpec).uri(eq(PAGE_PATH), any(Object.class));
        execution.verify(pageUpdateSpec).body(any(Object.class));
        execution.verify(pageUpdateSpec).retrieve();
        execution.verify(pageUpdateResponse).toBodilessEntity();
        execution.verifyNoMoreInteractions();
    }

    @ParameterizedTest(name = "[{index}] tweet id {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("issues no page update at all for a tweet identifier that carries nothing")
    void issuesNoPageUpdateAtAllForATweetIdentifierThatCarriesNothing(String blankTweetId) {
        service.updateTweetResponse(blankTweetId, RESPONSE_TEXT);

        verify(restClient, never()).patch();
        verify(restClient, never()).post();
        verifyNoInteractions(patchSpec, pageUpdateSpec, pageUpdateResponse);
    }

    @Test
    @DisplayName("issues no page update when the tweet id query matches no page")
    void issuesNoPageUpdateWhenTheTweetIdQueryMatchesNoPage() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        verify(restClient, never()).patch();
        verifyNoInteractions(patchSpec, pageUpdateSpec, pageUpdateResponse);
    }

    @Test
    @DisplayName("reports a failure and issues no page update when the matched page carries no "
            + "identifier")
    void reportsAFailureAndIssuesNoPageUpdateWhenTheMatchedPageCarriesNoIdentifier() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(MAPPER.createObjectNode()));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .withMessageContaining("page identifier");

        verify(restClient, never()).patch();
        verifyNoInteractions(patchSpec, pageUpdateSpec, pageUpdateResponse);
    }

    @Test
    @DisplayName("propagates a failure raised while the page update executes")
    void propagatesAFailureRaisedWhileThePageUpdateExecutes() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        RuntimeException transportFailure = new IllegalStateException("the PATCH did not complete");
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .isSameAs(transportFailure);

        verify(pageUpdateResponse).toBodilessEntity();
    }

    @Test
    @DisplayName("attempts a rate-limited mirror write again and reports the write once it lands")
    void attemptsARateLimitedMirrorWriteAgain() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        when(pageUpdateResponse.toBodilessEntity())
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn(new ResponseEntity<Void>(HttpStatus.OK));

        serviceRetrying(DATABASE_ID, 1).updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        verify(pageUpdateResponse, times(2)).toBodilessEntity();
    }

    @Test
    @DisplayName("attempts a mirror write the provider answered with a server error again")
    void attemptsAMirrorWriteAnsweredWithAServerErrorAgain() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        when(pageUpdateResponse.toBodilessEntity())
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(new ResponseEntity<Void>(HttpStatus.OK));

        serviceRetrying(DATABASE_ID, 1).updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        verify(pageUpdateResponse, times(2)).toBodilessEntity();
    }

    @Test
    @DisplayName("attempts a mirror write that failed in transport again")
    void attemptsAMirrorWriteThatFailedInTransportAgain() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        when(pageUpdateResponse.toBodilessEntity())
                .thenThrow(new ResourceAccessException("the connection was reset"))
                .thenReturn(new ResponseEntity<Void>(HttpStatus.OK));

        serviceRetrying(DATABASE_ID, 1).updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        verify(pageUpdateResponse, times(2)).toBodilessEntity();
    }

    // A failure that is neither a status answer nor a transport failure is answered the same way by a
    // later attempt, so it is propagated at once — DL-253 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("localFailures")
    @DisplayName("propagates a mirror-write failure that is neither a status answer nor a transport "
            + "failure without attempting it again")
    void propagatesALocalMirrorWriteFailureWithoutAnotherAttempt(String label,
            RuntimeException failure) {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(failure);

        NotionService service = serviceRetrying(DATABASE_ID, 3);

        assertThatThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .as("failure raised for %s", label)
                .isSameAs(failure);
        verify(pageUpdateResponse, times(1)).toBodilessEntity();
    }

    private static Stream<Arguments> localFailures() {
        return Stream.of(
                Arguments.of("a malformed answer the client could not read",
                        new RestClientException("the answer could not be read")),
                Arguments.of("a programming failure",
                        new IllegalStateException("the PATCH did not complete")),
                Arguments.of("a missing reference",
                        new NullPointerException("a required value was absent")),
                Arguments.of("a rejected argument",
                        new IllegalArgumentException("the identifier was not accepted")));
    }

    @ParameterizedTest(name = "a {0} answer is not attempted again")
    @ValueSource(ints = {400, 401, 403, 404, 409})
    @DisplayName("makes one attempt only when the provider answered with a status it will repeat")
    void makesOneAttemptOnlyForAStatusTheProviderWillRepeat(int status) {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        HttpClientErrorException rejected =
                new HttpClientErrorException(HttpStatus.valueOf(status));
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(rejected);

        NotionService retrying = serviceRetrying(DATABASE_ID, 3);

        assertThatThrownBy(() -> retrying.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .isSameAs(rejected);

        verify(pageUpdateResponse, times(1)).toBodilessEntity();
    }

    @Test
    @DisplayName("reports the failure once the retry budget is spent")
    void reportsTheFailureOnceTheRetryBudgetIsSpent() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        HttpClientErrorException rateLimited =
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(rateLimited);

        NotionService retrying = serviceRetrying(DATABASE_ID, 2);

        assertThatThrownBy(() -> retrying.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .isSameAs(rateLimited);

        verify(pageUpdateResponse, times(3)).toBodilessEntity();
    }

    @Test
    @DisplayName("makes one attempt only when the retry budget is zero")
    void makesOneAttemptOnlyWhenTheRetryBudgetIsZero() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        HttpClientErrorException rateLimited =
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(rateLimited);

        assertThatThrownBy(() -> service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .isSameAs(rateLimited);

        verify(pageUpdateResponse, times(1)).toBodilessEntity();
    }

    @Test
    @DisplayName("makes one attempt only when the notion group is unbound")
    void makesOneAttemptOnlyWhenTheNotionGroupIsUnbound() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();
        HttpClientErrorException rateLimited =
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        when(pageUpdateResponse.toBodilessEntity()).thenThrow(rateLimited);

        NotionService unbound = new NotionService(restClient,
                new ScannerProperties(null, 100, 60L, null,
                        new ScannerProperties.Notion(API_KEY, DATABASE_ID, "2022-06-28", 5L, 10L,
                                0, 0L),
                        null, null, null, null, null, null));

        assertThatThrownBy(() -> unbound.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .isSameAs(rateLimited);

        verify(pageUpdateResponse, times(1)).toBodilessEntity();
    }

    @Test
    @DisplayName("reads a negative retry budget and a negative backoff as zero")
    void readsANegativeRetryBudgetAndANegativeBackoffAsZero() {
        ScannerProperties.Notion group = new ScannerProperties.Notion(API_KEY, DATABASE_ID,
                "2022-06-28", 5L, 10L, -4, -250L);

        assertThat(group.mirrorMaxRetries()).isZero();
        assertThat(group.mirrorRetryBackoffMillis()).isZero();
    }

    @Test
    @DisplayName("keeps a configured retry budget and backoff as bound")
    void keepsAConfiguredRetryBudgetAndBackoffAsBound() {
        ScannerProperties.Notion group = new ScannerProperties.Notion(API_KEY, DATABASE_ID,
                "2022-06-28", 5L, 10L, 3, 750L);

        assertThat(group.mirrorMaxRetries()).isEqualTo(3);
        assertThat(group.mirrorRetryBackoffMillis()).isEqualTo(750L);
    }

    @Test
    @DisplayName("writes the response property as rich text carrying the supplied text")
    void writesTheResponsePropertyAsRichTextCarryingTheSuppliedText() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        JsonNode response = capturedBody(pageUpdateSpec).path(KEY_PROPERTIES).path(PROPERTY_RESPONSE);
        assertThat(response.has(KEY_RICH_TEXT)).isTrue();
        assertThat(textOf(response, KEY_RICH_TEXT)).isEqualTo(RESPONSE_TEXT);
    }

    @Test
    @DisplayName("writes only the response property on an update")
    void writesOnlyTheResponsePropertyOnAnUpdate() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        JsonNode request = capturedBody(pageUpdateSpec);
        assertThat(propertyNamesOf(request)).containsExactly(KEY_PROPERTIES);
        assertThat(propertyNamesOf(request.path(KEY_PROPERTIES))).containsExactly(PROPERTY_RESPONSE);
        assertThat(propertyNamesOf(request.path(KEY_PROPERTIES))).doesNotContain(
                PROPERTY_CONTENT,
                PROPERTY_AUTHOR,
                PROPERTY_TIMESTAMP,
                PROPERTY_DOUBT_RATING,
                PROPERTY_ENGAGEMENT,
                PROPERTY_TWEET_ID,
                PROPERTY_SENTIMENT);
    }

    @Test
    @DisplayName("declares an update operation that takes two strings and returns nothing")
    void declaresAnUpdateOperationThatTakesTwoStringsAndReturnsNothing()
            throws NoSuchMethodException {
        Method update = NotionService.class.getDeclaredMethod(
                "updateTweetResponse", String.class, String.class);

        assertThat(Modifier.isPublic(update.getModifiers())).isTrue();
        assertThat(update.getParameterTypes()).containsExactly(String.class, String.class);
        assertThat(update.getReturnType()).isEqualTo(void.class);
    }

    @ParameterizedTest(name = "a database id of {0} is rejected by getTweets")
    @MethodSource("blankDatabaseIds")
    @DisplayName("rejects a query when the configured database id is absent or blank")
    void rejectsAQueryWhenTheConfiguredDatabaseIdIsAbsentOrBlank(String databaseId) {
        NotionService unconfigured = serviceCarrying(databaseId);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.getTweets(LIMIT, START_CURSOR))
                .withMessageContaining("scanner.notion.database-id");
        verifyNoInteractions(restClient);
    }

    @ParameterizedTest(name = "a database id of {0} is rejected by storeTweet")
    @MethodSource("blankDatabaseIds")
    @DisplayName("rejects a mirror when the configured database id is absent or blank")
    void rejectsAMirrorWhenTheConfiguredDatabaseIdIsAbsentOrBlank(String databaseId) {
        NotionService unconfigured = serviceCarrying(databaseId);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.storeTweet(tweet()))
                .withMessageContaining("scanner.notion.database-id");
        verifyNoInteractions(restClient);
    }

    @ParameterizedTest(name = "a database id of {0} is rejected by updateTweetResponse")
    @MethodSource("blankDatabaseIds")
    @DisplayName("rejects an update when the configured database id is absent or blank")
    void rejectsAnUpdateWhenTheConfiguredDatabaseIdIsAbsentOrBlank(String databaseId) {
        NotionService unconfigured = serviceCarrying(databaseId);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.updateTweetResponse(TWEET_ID, RESPONSE_TEXT))
                .withMessageContaining("scanner.notion.database-id");
        verifyNoInteractions(restClient);
    }

    @ParameterizedTest(name = "a tweet id of {0} is a no-op even with no database id configured")
    @MethodSource("blankTweetIds")
    @DisplayName("leaves notion untouched for a blank tweet id before it reads the database id")
    void leavesNotionUntouchedForABlankTweetIdBeforeItReadsTheDatabaseId(String tweetId) {
        NotionService unconfigured = serviceCarrying(null);

        unconfigured.updateTweetResponse(tweetId, RESPONSE_TEXT);

        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("strips surrounding whitespace from the configured database id")
    void stripsSurroundingWhitespaceFromTheConfiguredDatabaseId() {
        service = serviceCarrying("  " + DATABASE_ID + "  ");
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        assertThat(capturedUriVariable(postSpec)).isEqualTo(DATABASE_ID);
    }

    @Test
    @DisplayName("declares store tweet get tweets and update tweet response as its public operations")
    void declaresStoreTweetGetTweetsAndUpdateTweetResponseAsItsPublicOperations() {
        assertThat(publicDeclaredMethods()).extracting(Method::getName)
                .containsExactlyInAnyOrder("storeTweet", "getTweets", "updateTweetResponse");
    }

    @Test
    @DisplayName("declares a query operation that takes a limit and a cursor and returns a list of posts")
    void declaresAQueryOperationThatTakesALimitAndACursorAndReturnsAListOfPosts()
            throws NoSuchMethodException {
        Method getTweets = NotionService.class.getDeclaredMethod("getTweets", int.class, String.class);

        assertThat(Modifier.isPublic(getTweets.getModifiers())).isTrue();
        assertThat(getTweets.getParameterTypes()).containsExactly(int.class, String.class);
        assertThat(getTweets.getReturnType()).isEqualTo(List.class);
        assertThat(rawTypesOf(getTweets.getGenericReturnType()).toList())
                .containsExactly(List.class, TweetDto.class);
    }

    @Test
    @DisplayName("declares no map and no json node on any public signature")
    void declaresNoMapAndNoJsonNodeOnAnyPublicSignature() {
        List<Class<?>> publicSignatureTypes = publicSignatureTypes();

        assertThat(publicSignatureTypes).isNotEmpty();
        assertThat(publicSignatureTypes).noneMatch(Map.class::isAssignableFrom);
        assertThat(publicSignatureTypes).noneMatch(JsonNode.class::isAssignableFrom);
        assertThat(publicSignatureTypes).allMatch(type -> type.equals(String.class)
                || type.equals(List.class)
                || type.equals(TweetDto.class)
                || type.equals(int.class)
                || type.equals(void.class)
                || type.equals(RestClient.class)
                || type.equals(ScannerProperties.class));
    }

    @Test
    @DisplayName("sets no base url no version header and no authorization header on any request")
    void setsNoBaseUrlNoVersionHeaderAndNoAuthorizationHeaderOnAnyRequest() {
        stubPost();
        stubPageCreationReturning(createdPage(CREATED_PAGE_ID));
        stubDatabaseQueryReturning(queryResultCarrying(pageCarrying(MATCHED_PAGE_ID, noProperties())));
        stubPageUpdate();

        service.storeTweet(tweet());
        service.getTweets(LIMIT, START_CURSOR);
        service.updateTweetResponse(TWEET_ID, RESPONSE_TEXT);

        assertThat(invokedMethodNames())
                .isNotEmpty()
                .doesNotContain("header", "headers", "mutate", "baseUrl", "defaultHeader",
                        "defaultHeaders", "defaultUriVariables");
        verify(postSpec, never()).uri(any(URI.class));
        verify(patchSpec, never()).uri(any(URI.class));

        List<String> arguments = invocationArgumentTexts();
        assertThat(arguments).isNotEmpty();
        assertThat(arguments).noneMatch(argument -> argument.contains(NOTION_VERSION_HEADER));
        assertThat(arguments).noneMatch(argument -> argument.contains(AUTHORIZATION_HEADER));
        assertThat(arguments).noneMatch(argument -> argument.contains(BEARER_PREFIX));
        assertThat(arguments).noneMatch(argument -> argument.contains(NOTION_API_BASE_URL));
        assertThat(arguments).noneMatch(argument -> argument.contains(API_KEY));

        List<String> templates = requestedUriTemplates();
        assertThat(templates).containsExactlyInAnyOrder(
                PAGES_PATH, DATABASE_QUERY_PATH, DATABASE_QUERY_PATH, PAGE_PATH);
        assertThat(templates).allSatisfy(template -> {
            assertThat(template).startsWith("/");
            assertThat(template).doesNotContain(SCHEME_SEPARATOR);
            assertThat(template).doesNotContain(NOTION_API_BASE_URL);
        });
    }

    @Test
    @DisplayName("declares no repository field or constructor parameter")
    void declaresNoRepositoryFieldOrConstructorParameter() {
        List<Class<?>> declaredTypes = declaredDependencyTypes();

        assertThat(declaredTypes).isNotEmpty();
        assertThat(declaredTypes).noneMatch(
                type -> type.getSimpleName().endsWith(REPOSITORY_SUFFIX));
        assertThat(declaredTypes).noneMatch(
                type -> type.getName().startsWith(REPOSITORY_PACKAGE));
        assertThat(declaredTypes).noneMatch(type -> type.getName().startsWith(ENTITY_PACKAGE));
        assertThat(declaredTypes).noneMatch(
                type -> type.getName().startsWith(SPRING_DATA_PACKAGE));
    }

    @Test
    @DisplayName("declares no reactive web client field or constructor parameter")
    void declaresNoReactiveWebClientFieldOrConstructorParameter() {
        assertThat(declaredFieldTypes()).doesNotContain(WebClient.class);
        assertThat(declaredConstructorParameterTypes()).doesNotContain(WebClient.class);
        assertThat(declaredConstructorParameterTypes())
                .containsExactly(RestClient.class, ScannerProperties.class);
    }

    @Test
    @DisplayName("declares no validation constraint on its public surface or on the post it accepts")
    void declaresNoValidationConstraintOnItsPublicSurfaceOrOnThePostItAccepts() {
        List<Annotation> surfaceAnnotations = publicSurfaceAnnotations();
        List<Annotation> postAnnotations = Arrays.stream(TweetDto.class.getDeclaredFields())
                .flatMap(field -> Arrays.stream(field.getAnnotations()))
                .toList();

        assertThat(surfaceAnnotations).isNotEmpty();
        assertThat(postAnnotations).isNotEmpty();
        assertThat(surfaceAnnotations).noneMatch(NotionServiceTest::isValidationConstraint);
        assertThat(postAnnotations).noneMatch(NotionServiceTest::isValidationConstraint);
    }

    /**
     * Supplies the {@code limit} values that are replaced by the default page size — DL-154.
     *
     * @return zero and the negative limits
     */
    private static Stream<Integer> nonPositiveLimits() {
        return Stream.of(0, -1, -10, Integer.MIN_VALUE);
    }

    /**
     * Supplies the {@code limit} values that are capped at the largest page size Notion accepts —
     * DL-154.
     *
     * @return the limits above one hundred
     */
    private static Stream<Integer> limitsAboveTheMaximum() {
        return Stream.of(MAXIMUM_PAGE_SIZE + 1, 250, 1000, Integer.MAX_VALUE);
    }

    private static Stream<String> blankDatabaseIds() {
        return Stream.of(null, "", " ", "   ", "\t", "\n");
    }

    /**
     * Supplies the post identifiers {@code updateTweetResponse} treats as a no-op — DL-157.
     *
     * @return {@code null} for an absent identifier, then the blank identifiers
     */
    private static Stream<String> blankTweetIds() {
        return Stream.of(null, "", " ", "\t\n");
    }

    private NotionService serviceCarrying(String databaseId) {
        return new NotionService(restClient, propertiesCarrying(databaseId));
    }

    /**
     * Builds the unit under test over a {@code scanner.notion} group whose mirror retry budget is the
     * supplied one and whose backoff is zero, so no test waits — DL-253.
     *
     * @param databaseId value bound to {@code scanner.notion.database-id}
     * @param retries    value bound to {@code scanner.notion.mirror-max-retries}
     * @return the unit under test, holding {@link #restClient}
     */
    private NotionService serviceRetrying(String databaseId, int retries) {
        return new NotionService(restClient, propertiesCarrying(databaseId, retries, 0L));
    }

    private static ScannerProperties propertiesCarrying(String databaseId) {
        return propertiesCarrying(databaseId, 0, 0L);
    }

    /**
     * Builds a configuration root carrying a {@code scanner.notion} group with an explicit mirror
     * retry budget — DL-253.
     *
     * @param databaseId     value bound to {@code scanner.notion.database-id}
     * @param retries        value bound to {@code scanner.notion.mirror-max-retries}
     * @param backoffMillis  value bound to {@code scanner.notion.mirror-retry-backoff-millis}
     * @return the configuration root
     */
    private static ScannerProperties propertiesCarrying(String databaseId, int retries,
            long backoffMillis) {
        return new ScannerProperties(null, 100, 60L, null,
                new ScannerProperties.Notion(API_KEY, databaseId, "2022-06-28", 5L, 10L, retries,
                        backoffMillis),
                null, null, null, null, null, null);
    }

    private static TweetDto tweet() {
        return tweetCarryingId(TWEET_ID);
    }

    private static TweetDto tweetCarryingId(String id) {
        return new TweetDto(id, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA,
                QUOTED_TWEET_ID, USER_ID, AI_TOOLS_MENTIONED);
    }

    private static TweetDto tweetCarryingLists(List<String> media, List<String> aiToolsMentioned) {
        return new TweetDto(TWEET_ID, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, DOUBT_RATING, media,
                QUOTED_TWEET_ID, USER_ID, aiToolsMentioned);
    }

    private void stubPost() {
        when(restClient.post()).thenReturn(postSpec);
    }

    private void stubPageCreationReturning(JsonNode created) {
        when(postSpec.uri(PAGES_PATH)).thenReturn(pageCreationSpec);
        when(pageCreationSpec.body(any(Object.class))).thenReturn(pageCreationSpec);
        when(pageCreationSpec.retrieve()).thenReturn(pageCreationResponse);
        when(pageCreationResponse.body(JsonNode.class)).thenReturn(created);
    }

    private void stubDatabaseQueryReturning(JsonNode result) {
        when(postSpec.uri(eq(DATABASE_QUERY_PATH), any(Object.class))).thenReturn(databaseQuerySpec);
        when(databaseQuerySpec.body(any(Object.class))).thenReturn(databaseQuerySpec);
        when(databaseQuerySpec.retrieve()).thenReturn(databaseQueryResponse);
        when(databaseQueryResponse.body(JsonNode.class)).thenReturn(result);
    }

    private void stubPageUpdate() {
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(eq(PAGE_PATH), any(Object.class))).thenReturn(pageUpdateSpec);
        when(pageUpdateSpec.body(any(Object.class))).thenReturn(pageUpdateSpec);
        when(pageUpdateSpec.retrieve()).thenReturn(pageUpdateResponse);
    }

    private static ObjectNode createdPage(String pageId) {
        return MAPPER.createObjectNode().put(KEY_ID, pageId);
    }

    private static ObjectNode queryResultCarrying(JsonNode... pages) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode results = result.putArray(KEY_RESULTS);
        for (JsonNode page : pages) {
            results.add(page);
        }
        return result;
    }

    private static ObjectNode richText(String value) {
        ObjectNode property = MAPPER.createObjectNode();
        ObjectNode item = property.putArray(KEY_RICH_TEXT).addObject();
        item.putObject(KEY_TEXT).put(KEY_CONTENT, value);
        return property;
    }

    private static ObjectNode pageCarrying(String pageId, JsonNode properties) {
        ObjectNode page = MAPPER.createObjectNode();
        page.put(KEY_ID, pageId);
        page.set(KEY_PROPERTIES, properties);
        return page;
    }

    private static ObjectNode noProperties() {
        return MAPPER.createObjectNode();
    }

    private static JsonNode capturedBody(RestClient.RequestBodySpec spec) {
        ArgumentCaptor<Object> sentBody = ArgumentCaptor.forClass(Object.class);
        verify(spec).body(sentBody.capture());
        return MAPPER.valueToTree(sentBody.getValue());
    }

    private static List<JsonNode> capturedBodies(RestClient.RequestBodySpec spec, int expectedCount) {
        ArgumentCaptor<Object> sentBodies = ArgumentCaptor.forClass(Object.class);
        verify(spec, times(expectedCount)).body(sentBodies.capture());
        return sentBodies.getAllValues().stream()
                .<JsonNode>map(MAPPER::valueToTree)
                .toList();
    }

    private JsonNode storedProperties() {
        return capturedBody(pageCreationSpec).path(KEY_PROPERTIES);
    }

    private JsonNode storedProperty(String propertyName) {
        return storedProperties().path(propertyName);
    }

    private static JsonNode propertyOf(JsonNode request, String propertyName) {
        return request.path(KEY_PROPERTIES).path(propertyName);
    }

    private static String capturedUriTemplate(RestClient.RequestBodyUriSpec spec) {
        return uriInvocationOf(spec).getArgument(0, String.class);
    }

    private static Object capturedUriVariable(RestClient.RequestBodyUriSpec spec) {
        Invocation invocation = uriInvocationOf(spec);
        assertThat(invocation.getArguments()).hasSize(2);
        return invocation.getArgument(1, Object.class);
    }

    private static Invocation uriInvocationOf(RestClient.RequestBodyUriSpec spec) {
        List<Invocation> uriInvocations = uriInvocationsOf(spec);
        assertThat(uriInvocations).hasSize(1);
        return uriInvocations.get(0);
    }

    private static List<Invocation> uriInvocationsOf(RestClient.RequestBodyUriSpec spec) {
        return mockingDetails(spec).getInvocations().stream()
                .filter(invocation -> "uri".equals(invocation.getMethod().getName()))
                .toList();
    }

    private List<String> requestedUriTemplates() {
        return Stream.of(postSpec, patchSpec)
                .flatMap(spec -> uriInvocationsOf(spec).stream())
                .map(invocation -> invocation.getArgument(0, String.class))
                .toList();
    }

    private List<String> invokedMethodNames() {
        return transportMocks().stream()
                .flatMap(mock -> mockingDetails(mock).getInvocations().stream())
                .map(invocation -> invocation.getMethod().getName())
                .toList();
    }

    private List<String> invocationArgumentTexts() {
        return transportMocks().stream()
                .flatMap(mock -> mockingDetails(mock).getInvocations().stream())
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .map(String::valueOf)
                .toList();
    }

    private List<Object> transportMocks() {
        return List.of(restClient, postSpec, patchSpec, pageCreationSpec, databaseQuerySpec,
                pageUpdateSpec, pageCreationResponse, databaseQueryResponse, pageUpdateResponse);
    }

    private static String textOf(JsonNode property, String containerKey) {
        return property.path(containerKey).path(0).path(KEY_TEXT).path(KEY_CONTENT).asText();
    }

    private static List<String> propertyNamesOf(JsonNode node) {
        return node.propertyStream().map(Map.Entry::getKey).toList();
    }

    private static List<Method> publicDeclaredMethods() {
        return Arrays.stream(NotionService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
    }

    private static List<Constructor<?>> publicDeclaredConstructors() {
        return Arrays.stream(NotionService.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList();
    }

    private static List<Class<?>> publicSignatureTypes() {
        List<Type> declared = new ArrayList<>();
        for (Method method : publicDeclaredMethods()) {
            declared.add(method.getGenericReturnType());
            declared.addAll(Arrays.asList(method.getGenericParameterTypes()));
        }
        for (Constructor<?> constructor : publicDeclaredConstructors()) {
            declared.addAll(Arrays.asList(constructor.getGenericParameterTypes()));
        }
        return declared.stream()
                .flatMap(NotionServiceTest::rawTypesOf)
                .distinct()
                .toList();
    }

    private static Stream<Class<?>> rawTypesOf(Type type) {
        if (type instanceof Class<?> raw) {
            return Stream.of(raw);
        }
        if (type instanceof ParameterizedType parameterized) {
            return Stream.concat(
                    rawTypesOf(parameterized.getRawType()),
                    Arrays.stream(parameterized.getActualTypeArguments())
                            .flatMap(NotionServiceTest::rawTypesOf));
        }
        if (type instanceof GenericArrayType array) {
            return rawTypesOf(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return Stream.concat(
                            Arrays.stream(wildcard.getUpperBounds()),
                            Arrays.stream(wildcard.getLowerBounds()))
                    .flatMap(NotionServiceTest::rawTypesOf);
        }
        if (type instanceof TypeVariable<?> variable) {
            return Arrays.stream(variable.getBounds()).flatMap(NotionServiceTest::rawTypesOf);
        }
        return Stream.empty();
    }

    private static List<Class<?>> declaredFieldTypes() {
        return Arrays.stream(NotionService.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getType)
                .toList();
    }

    private static List<Class<?>> declaredConstructorParameterTypes() {
        return publicDeclaredConstructors().stream()
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .toList();
    }

    private static List<Class<?>> declaredDependencyTypes() {
        return Stream.concat(
                        declaredFieldTypes().stream(),
                        declaredConstructorParameterTypes().stream())
                .toList();
    }

    private static List<Annotation> publicSurfaceAnnotations() {
        List<Annotation> annotations =
                new ArrayList<>(Arrays.asList(NotionService.class.getAnnotations()));
        for (Method method : publicDeclaredMethods()) {
            annotations.addAll(Arrays.asList(method.getAnnotations()));
            for (Parameter parameter : method.getParameters()) {
                annotations.addAll(Arrays.asList(parameter.getAnnotations()));
            }
        }
        for (Constructor<?> constructor : publicDeclaredConstructors()) {
            annotations.addAll(Arrays.asList(constructor.getAnnotations()));
            for (Parameter parameter : constructor.getParameters()) {
                annotations.addAll(Arrays.asList(parameter.getAnnotations()));
            }
        }
        return List.copyOf(annotations);
    }

    private static boolean isValidationConstraint(Annotation annotation) {
        String annotationType = annotation.annotationType().getName();
        return annotationType.startsWith(BEAN_VALIDATION_PACKAGE)
                || annotationType.startsWith(VALIDATOR_PACKAGE);
    }
}
