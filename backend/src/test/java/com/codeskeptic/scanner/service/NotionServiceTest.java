package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.TweetDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    /** Renders captured request payloads and builds stubbed Notion responses. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // scanner.notion values carried by the properties under test
    // -------------------------------------------------------------------------

    /** Value bound to {@code scanner.notion.database-id}. */
    private static final String DATABASE_ID = "notion-database-1a2b3c4d";

    /** Second value bound to {@code scanner.notion.database-id}. */
    private static final String OTHER_DATABASE_ID = "notion-database-9z8y7x6w";

    /** Value bound to {@code scanner.notion.api-key}. */
    private static final String API_KEY = "not-a-real-notion-credential";

    // -------------------------------------------------------------------------
    // Request paths, each relative to the base URL carried by the injected RestClient
    // -------------------------------------------------------------------------

    /** Path the page-creation request is sent to. */
    private static final String PAGES_PATH = "/v1/pages";

    /** Path the page-update request is sent to, carrying the page identifier. */
    private static final String PAGE_PATH = "/v1/pages/{pageId}";

    /** Path the database-query request is sent to, carrying the database identifier. */
    private static final String DATABASE_QUERY_PATH = "/v1/databases/{databaseId}/query";

    // -------------------------------------------------------------------------
    // Transport defaults carried by the injected RestClient bean
    // -------------------------------------------------------------------------

    /** Base URL the injected {@link RestClient} bean carries. */
    private static final String NOTION_API_BASE_URL = "https://api.notion.com";

    /** Name of the version header the injected {@link RestClient} bean carries. */
    private static final String NOTION_VERSION_HEADER = "Notion-Version";

    /** Name of the credential header the injected {@link RestClient} bean carries. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Scheme prefix of the credential header value the injected {@link RestClient} bean carries. */
    private static final String BEARER_PREFIX = "Bearer";

    /** Scheme separator of an absolute URL. */
    private static final String SCHEME_SEPARATOR = "://";

    // -------------------------------------------------------------------------
    // Notion page identifiers carried by the stubbed responses
    // -------------------------------------------------------------------------

    /** Identifier the stubbed page-creation response returns. */
    private static final String CREATED_PAGE_ID = "8f14e45f-ea1a-4b2c-8d3e-000000000001";

    /** Identifier the stubbed database-query response returns for the matched page. */
    private static final String MATCHED_PAGE_ID = "8f14e45f-ea1a-4b2c-8d3e-000000000002";

    // -------------------------------------------------------------------------
    // Post handed to storeTweet(TweetDto)
    // -------------------------------------------------------------------------

    /** {@link TweetDto#id()} of the post the tests mirror. */
    private static final String TWEET_ID = "1793355680000000001";

    /** {@link TweetDto#content()} of the post the tests mirror. */
    private static final String TWEET_CONTENT = "Every AI coding tool review reads like an advert";

    /** {@link TweetDto#likeCount()} of the post the tests mirror. */
    private static final int LIKE_COUNT = 128;

    /** {@link TweetDto#createdAt()} of the post the tests mirror. */
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 2, 14, 8, 45, 30);

    /** ISO-8601 rendering of {@link #CREATED_AT}. */
    private static final String CREATED_AT_TEXT = "2026-02-14T08:45:30";

    /** {@link TweetDto#doubtRating()} of the post the tests mirror. */
    private static final double DOUBT_RATING = 7.5d;

    /** {@link TweetDto#media()} of the post the tests mirror. */
    private static final List<String> MEDIA = List.of("https://pbs.example/media/1.png");

    /** {@link TweetDto#quotedTweetId()} of the post the tests mirror. */
    private static final String QUOTED_TWEET_ID = "1793355680000000002";

    /** {@link TweetDto#userId()} of the post the tests mirror. */
    private static final String USER_ID = "user-4242";

    /** {@link TweetDto#aiToolsMentioned()} of the post the tests mirror. */
    private static final List<String> AI_TOOLS_MENTIONED = List.of("GitHub Copilot", "Cursor");

    // -------------------------------------------------------------------------
    // Arguments handed to getTweets(int, String) and updateTweetResponse(String, String)
    // -------------------------------------------------------------------------

    /** Value handed to the {@code limit} parameter of {@code getTweets}. */
    private static final int LIMIT = 25;

    /** Value handed to the {@code startCursor} parameter of {@code getTweets}. */
    private static final String START_CURSOR = "MTc5MzM1NTY4MDAwMDAwMDAwMQ";

    /** Value handed to the {@code responseText} parameter of {@code updateTweetResponse}. */
    private static final String RESPONSE_TEXT = "Benchmarks and a repeatable harness would settle it.";

    // -------------------------------------------------------------------------
    // Notion property names
    // -------------------------------------------------------------------------

    /** Title property carrying the post body. */
    private static final String PROPERTY_CONTENT = "Content";

    /** Rich-text property carrying the author identifier. */
    private static final String PROPERTY_AUTHOR = "Author";

    /** Date property carrying the post creation time. */
    private static final String PROPERTY_TIMESTAMP = "Timestamp";

    /** Number property carrying the doubt rating. */
    private static final String PROPERTY_DOUBT_RATING = "Doubt Rating";

    /** Number property carrying the like count. */
    private static final String PROPERTY_ENGAGEMENT = "Engagement";

    /** Rich-text property carrying the post identifier. */
    private static final String PROPERTY_TWEET_ID = "Tweet Id";

    /** Rich-text property carrying the generated reply. */
    private static final String PROPERTY_RESPONSE = "Response";

    /** Select property named at {@code backend/app/services/notion_service.py:L18}. */
    private static final String PROPERTY_SENTIMENT = "Sentiment";

    // -------------------------------------------------------------------------
    // Notion JSON member names
    // -------------------------------------------------------------------------

    /** Container of a title property's text items. */
    private static final String KEY_TITLE = "title";

    /** Container of a rich-text property's text items, and of a rich-text filter condition. */
    private static final String KEY_RICH_TEXT = "rich_text";

    /** Text object of a single title or rich-text item. */
    private static final String KEY_TEXT = "text";

    /** Literal text carried by a text object. */
    private static final String KEY_CONTENT = "content";

    /** Container of a date property's endpoints. */
    private static final String KEY_DATE = "date";

    /** Start endpoint of a date property. */
    private static final String KEY_START = "start";

    /** Value of a number property. */
    private static final String KEY_NUMBER = "number";

    /** Select object of a select property. */
    private static final String KEY_SELECT = "select";

    /** Parent reference of a page-creation request. */
    private static final String KEY_PARENT = "parent";

    /** Target database of a page-creation request. */
    private static final String KEY_DATABASE_ID = "database_id";

    /** Property map of a page-creation, page-update or page-read payload. */
    private static final String KEY_PROPERTIES = "properties";

    /** Identifier of a Notion page. */
    private static final String KEY_ID = "id";

    /** Page array of a database-query response. */
    private static final String KEY_RESULTS = "results";

    /** Page-size argument of a database-query request. */
    private static final String KEY_PAGE_SIZE = "page_size";

    /** Pagination cursor of a database-query request. */
    private static final String KEY_START_CURSOR = "start_cursor";

    /** Filter of a database-query request. */
    private static final String KEY_FILTER = "filter";

    /** Filtered property name of a database-query filter. */
    private static final String KEY_PROPERTY = "property";

    /** Equality condition of a database-query filter. */
    private static final String KEY_EQUALS = "equals";

    // -------------------------------------------------------------------------
    // Package prefixes and suffixes the declared-surface tests reject
    // -------------------------------------------------------------------------

    /** Simple-name suffix of every Spring Data repository interface in the module. */
    private static final String REPOSITORY_SUFFIX = "Repository";

    /** Package holding the module's repository interfaces. */
    private static final String REPOSITORY_PACKAGE = "com.codeskeptic.scanner.repository";

    /** Package holding the module's JPA entities. */
    private static final String ENTITY_PACKAGE = "com.codeskeptic.scanner.entity";

    /** Root package of Spring Data. */
    private static final String SPRING_DATA_PACKAGE = "org.springframework.data";

    /** Root package of Bean Validation. */
    private static final String BEAN_VALIDATION_PACKAGE = "jakarta.validation";

    /** Root package of the Bean Validation reference implementation. */
    private static final String VALIDATOR_PACKAGE = "org.hibernate.validator";

    // -------------------------------------------------------------------------
    // Stubbed transport, one mock per fluent step
    // -------------------------------------------------------------------------

    /** Notion API transport handed to the unit under test. */
    @Mock
    private RestClient restClient;

    /** Spec {@code restClient.post()} returns. */
    @Mock
    private RestClient.RequestBodyUriSpec postSpec;

    /** Spec {@code restClient.patch()} returns. */
    @Mock
    private RestClient.RequestBodyUriSpec patchSpec;

    /** Spec the page-creation path returns. */
    @Mock
    private RestClient.RequestBodySpec pageCreationSpec;

    /** Spec the database-query path returns. */
    @Mock
    private RestClient.RequestBodySpec databaseQuerySpec;

    /** Spec the page-update path returns. */
    @Mock
    private RestClient.RequestBodySpec pageUpdateSpec;

    /** Response spec of the page-creation chain. */
    @Mock
    private RestClient.ResponseSpec pageCreationResponse;

    /** Response spec of the database-query chain. */
    @Mock
    private RestClient.ResponseSpec databaseQueryResponse;

    /** Response spec of the page-update chain. */
    @Mock
    private RestClient.ResponseSpec pageUpdateResponse;

    /** Unit under test, holding {@link #restClient} and a {@code scanner.notion} group. */
    private NotionService service;

    @BeforeEach
    void createService() {
        service = serviceCarrying(DATABASE_ID);
    }

    // -------------------------------------------------------------------------
    // Page creation: the property map
    // -------------------------------------------------------------------------

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
    @DisplayName("writes exactly the six named properties on a stored page")
    void writesExactlyTheSixNamedPropertiesOnAStoredPage() {
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

    // -------------------------------------------------------------------------
    // The configured scanner.notion.database-id
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Database query: pagination arguments and mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sends the supplied limit as the page size")
    void sendsTheSuppliedLimitAsThePageSize() {
        stubPost();
        stubDatabaseQueryReturning(queryResultCarrying());

        service.getTweets(LIMIT, START_CURSOR);

        assertThat(capturedBody(databaseQuerySpec).path(KEY_PAGE_SIZE).intValue()).isEqualTo(LIMIT);
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

    // -------------------------------------------------------------------------
    // Response update
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Declared surface and transport containment
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Fixtures: the unit under test and its configuration
    // -------------------------------------------------------------------------

    /**
     * Builds the unit under test over a {@code scanner.notion} group carrying the supplied database
     * identifier and {@link #API_KEY}.
     *
     * @param databaseId value bound to {@code scanner.notion.database-id}
     * @return the unit under test, holding {@link #restClient}
     */
    private NotionService serviceCarrying(String databaseId) {
        return new NotionService(restClient, propertiesCarrying(databaseId));
    }

    /**
     * Builds a configuration root carrying a {@code scanner.notion} group. Every group
     * {@link NotionService} does not read is left unbound.
     *
     * @param databaseId value bound to {@code scanner.notion.database-id}
     * @return the configuration root
     */
    private static ScannerProperties propertiesCarrying(String databaseId) {
        return new ScannerProperties(null, 100, 60L, null,
                new ScannerProperties.Notion(API_KEY, databaseId), null, null, null, null, null);
    }

    /**
     * Builds the post every test mirrors unless it supplies its own.
     *
     * @return a post carrying {@link #TWEET_ID} and the remaining fixture values
     */
    private static TweetDto tweet() {
        return tweetCarryingId(TWEET_ID);
    }

    /**
     * Builds a post carrying the supplied identifier and the remaining fixture values.
     *
     * @param id value of {@link TweetDto#id()}
     * @return the post
     */
    private static TweetDto tweetCarryingId(String id) {
        return new TweetDto(id, TWEET_CONTENT, LIKE_COUNT, CREATED_AT, DOUBT_RATING, MEDIA,
                QUOTED_TWEET_ID, USER_ID, AI_TOOLS_MENTIONED);
    }

    // -------------------------------------------------------------------------
    // Fixtures: the stubbed fluent chains
    // -------------------------------------------------------------------------

    /** Makes the stubbed transport answer {@code post()} with {@link #postSpec}. */
    private void stubPost() {
        when(restClient.post()).thenReturn(postSpec);
    }

    /**
     * Stubs the page-creation chain and makes it answer with the supplied response.
     *
     * @param created the response body the chain returns
     */
    private void stubPageCreationReturning(JsonNode created) {
        when(postSpec.uri(PAGES_PATH)).thenReturn(pageCreationSpec);
        when(pageCreationSpec.body(any(Object.class))).thenReturn(pageCreationSpec);
        when(pageCreationSpec.retrieve()).thenReturn(pageCreationResponse);
        when(pageCreationResponse.body(JsonNode.class)).thenReturn(created);
    }

    /**
     * Stubs the database-query chain and makes it answer with the supplied response.
     *
     * @param result the response body the chain returns
     */
    private void stubDatabaseQueryReturning(JsonNode result) {
        when(postSpec.uri(eq(DATABASE_QUERY_PATH), any(Object.class))).thenReturn(databaseQuerySpec);
        when(databaseQuerySpec.body(any(Object.class))).thenReturn(databaseQuerySpec);
        when(databaseQuerySpec.retrieve()).thenReturn(databaseQueryResponse);
        when(databaseQueryResponse.body(JsonNode.class)).thenReturn(result);
    }

    /** Stubs the page-update chain. */
    private void stubPageUpdate() {
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(eq(PAGE_PATH), any(Object.class))).thenReturn(pageUpdateSpec);
        when(pageUpdateSpec.body(any(Object.class))).thenReturn(pageUpdateSpec);
        when(pageUpdateSpec.retrieve()).thenReturn(pageUpdateResponse);
    }

    // -------------------------------------------------------------------------
    // Fixtures: stubbed Notion responses
    // -------------------------------------------------------------------------

    /**
     * Builds a page-creation response carrying the supplied page identifier.
     *
     * @param pageId the created page's identifier
     * @return the response body
     */
    private static ObjectNode createdPage(String pageId) {
        return MAPPER.createObjectNode().put(KEY_ID, pageId);
    }

    /**
     * Builds a database-query response carrying the supplied pages in order.
     *
     * @param pages the pages the response carries; none yields a response with an empty result array
     * @return the response body
     */
    private static ObjectNode queryResultCarrying(JsonNode... pages) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode results = result.putArray(KEY_RESULTS);
        for (JsonNode page : pages) {
            results.add(page);
        }
        return result;
    }

    /**
     * Builds one page of a database-query response.
     *
     * @param pageId the page's identifier
     * @param properties the page's property map
     * @return the page
     */
    private static ObjectNode pageCarrying(String pageId, JsonNode properties) {
        ObjectNode page = MAPPER.createObjectNode();
        page.put(KEY_ID, pageId);
        page.set(KEY_PROPERTIES, properties);
        return page;
    }

    /**
     * Builds an empty property map.
     *
     * @return a property map carrying no property
     */
    private static ObjectNode noProperties() {
        return MAPPER.createObjectNode();
    }

    // -------------------------------------------------------------------------
    // Captured requests
    // -------------------------------------------------------------------------

    /**
     * Reads the single request body a stubbed chain received, as a tree.
     *
     * @param spec the stubbed chain step the body was handed to
     * @return the captured body
     */
    private static JsonNode capturedBody(RestClient.RequestBodySpec spec) {
        ArgumentCaptor<Object> sentBody = ArgumentCaptor.forClass(Object.class);
        verify(spec).body(sentBody.capture());
        return MAPPER.valueToTree(sentBody.getValue());
    }

    /**
     * Reads every request body a stubbed chain received, oldest first, as trees.
     *
     * @param spec the stubbed chain step the bodies were handed to
     * @param expectedCount the number of bodies the step is verified to have received
     * @return the captured bodies
     */
    private static List<JsonNode> capturedBodies(RestClient.RequestBodySpec spec, int expectedCount) {
        ArgumentCaptor<Object> sentBodies = ArgumentCaptor.forClass(Object.class);
        verify(spec, times(expectedCount)).body(sentBodies.capture());
        return sentBodies.getAllValues().stream()
                .map(body -> (JsonNode) MAPPER.valueToTree(body))
                .toList();
    }

    /**
     * Reads the property map of the single page-creation request.
     *
     * @return the captured property map
     */
    private JsonNode storedProperties() {
        return capturedBody(pageCreationSpec).path(KEY_PROPERTIES);
    }

    /**
     * Reads one property of the single page-creation request.
     *
     * @param propertyName the Notion property name
     * @return the captured property, or a missing node when the request carries no such property
     */
    private JsonNode storedProperty(String propertyName) {
        return storedProperties().path(propertyName);
    }

    /**
     * Reads one property of a captured page-creation request.
     *
     * @param request the captured request
     * @param propertyName the Notion property name
     * @return the captured property, or a missing node when the request carries no such property
     */
    private static JsonNode propertyOf(JsonNode request, String propertyName) {
        return request.path(KEY_PROPERTIES).path(propertyName);
    }

    /**
     * Reads the path of the single {@code uri} call a stubbed spec received.
     *
     * @param spec the stubbed spec
     * @return the requested path
     */
    private static String capturedUriTemplate(RestClient.RequestBodyUriSpec spec) {
        return uriInvocationOf(spec).getArgument(0, String.class);
    }

    /**
     * Reads the single path variable of the single {@code uri} call a stubbed spec received.
     *
     * @param spec the stubbed spec
     * @return the supplied path variable
     */
    private static Object capturedUriVariable(RestClient.RequestBodyUriSpec spec) {
        Invocation invocation = uriInvocationOf(spec);
        assertThat(invocation.getArguments()).hasSize(2);
        return invocation.getArgument(1, Object.class);
    }

    /**
     * Reads the single {@code uri} invocation a stubbed spec received.
     *
     * @param spec the stubbed spec
     * @return the invocation
     */
    private static Invocation uriInvocationOf(RestClient.RequestBodyUriSpec spec) {
        List<Invocation> uriInvocations = uriInvocationsOf(spec);
        assertThat(uriInvocations).hasSize(1);
        return uriInvocations.get(0);
    }

    /**
     * Reads every {@code uri} invocation a stubbed spec received, oldest first.
     *
     * @param spec the stubbed spec
     * @return the invocations
     */
    private static List<Invocation> uriInvocationsOf(RestClient.RequestBodyUriSpec spec) {
        return mockingDetails(spec).getInvocations().stream()
                .filter(invocation -> "uri".equals(invocation.getMethod().getName()))
                .toList();
    }

    /**
     * Reads the path of every request the stubbed transport received, oldest first per spec.
     *
     * @return the requested paths
     */
    private List<String> requestedUriTemplates() {
        return Stream.of(postSpec, patchSpec)
                .flatMap(spec -> uriInvocationsOf(spec).stream())
                .map(invocation -> invocation.getArgument(0, String.class))
                .toList();
    }

    /**
     * Reads the name of every method invoked on the stubbed transport.
     *
     * @return the invoked method names
     */
    private List<String> invokedMethodNames() {
        return transportMocks().stream()
                .flatMap(mock -> mockingDetails(mock).getInvocations().stream())
                .map(invocation -> invocation.getMethod().getName())
                .toList();
    }

    /**
     * Renders every argument handed to the stubbed transport.
     *
     * @return the rendered arguments
     */
    private List<String> invocationArgumentTexts() {
        return transportMocks().stream()
                .flatMap(mock -> mockingDetails(mock).getInvocations().stream())
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .map(String::valueOf)
                .toList();
    }

    /**
     * Lists every stubbed transport mock.
     *
     * @return the transport, the two uri specs, the three body specs and the three response specs
     */
    private List<Object> transportMocks() {
        return List.of(restClient, postSpec, patchSpec, pageCreationSpec, databaseQuerySpec,
                pageUpdateSpec, pageCreationResponse, databaseQueryResponse, pageUpdateResponse);
    }

    // -------------------------------------------------------------------------
    // Notion payload readers
    // -------------------------------------------------------------------------

    /**
     * Reads the literal text of the first item of a title or rich-text property.
     *
     * @param property the captured property
     * @param containerKey {@link #KEY_TITLE} or {@link #KEY_RICH_TEXT}
     * @return the literal text, or an empty string when the property carries none
     */
    private static String textOf(JsonNode property, String containerKey) {
        return property.path(containerKey).path(0).path(KEY_TEXT).path(KEY_CONTENT).asText();
    }

    /**
     * Lists the member names of an object node, in encounter order.
     *
     * @param node the node to read
     * @return the member names, empty when the node is not an object
     */
    private static List<String> propertyNamesOf(JsonNode node) {
        return node.propertyStream().map(Map.Entry::getKey).toList();
    }

    // -------------------------------------------------------------------------
    // Declared surface readers
    // -------------------------------------------------------------------------

    /**
     * Lists the public methods {@link NotionService} declares.
     *
     * @return the declared public methods
     */
    private static List<Method> publicDeclaredMethods() {
        return Arrays.stream(NotionService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
    }

    /**
     * Lists the public constructors {@link NotionService} declares.
     *
     * @return the declared public constructors
     */
    private static List<Constructor<?>> publicDeclaredConstructors() {
        return Arrays.stream(NotionService.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList();
    }

    /**
     * Lists every distinct raw type reachable from the return type and the parameter types of the
     * public surface {@link NotionService} declares, including the type arguments of a generic type.
     *
     * @return the raw types the public surface names
     */
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

    /**
     * Resolves a reflected type into the raw types it names, descending into type arguments, array
     * components, wildcard bounds and type-variable bounds.
     *
     * @param type the reflected type
     * @return the raw types
     */
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

    /**
     * Lists the type of every field {@link NotionService} declares.
     *
     * @return the declared field types
     */
    private static List<Class<?>> declaredFieldTypes() {
        return Arrays.stream(NotionService.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getType)
                .toList();
    }

    /**
     * Lists the parameter type of every public constructor {@link NotionService} declares, in
     * declaration order.
     *
     * @return the declared constructor parameter types
     */
    private static List<Class<?>> declaredConstructorParameterTypes() {
        return publicDeclaredConstructors().stream()
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .toList();
    }

    /**
     * Lists every type {@link NotionService} declares as a field or as a constructor parameter.
     *
     * @return the declared dependency types
     */
    private static List<Class<?>> declaredDependencyTypes() {
        return Stream.concat(
                        declaredFieldTypes().stream(),
                        declaredConstructorParameterTypes().stream())
                .toList();
    }

    /**
     * Lists every runtime-visible annotation on the type, the public methods, the public
     * constructors and their parameters that {@link NotionService} declares.
     *
     * @return the annotations
     */
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

    /**
     * Reports whether an annotation is a Bean Validation constraint.
     *
     * @param annotation the annotation to classify
     * @return {@code true} when the annotation type belongs to Bean Validation or to its reference
     *     implementation
     */
    private static boolean isValidationConstraint(Annotation annotation) {
        String annotationType = annotation.annotationType().getName();
        return annotationType.startsWith(BEAN_VALIDATION_PACKAGE)
                || annotationType.startsWith(VALIDATOR_PACKAGE);
    }
}
