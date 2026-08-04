package com.codeskeptic.scanner.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.util.DelimitedStringListConverter;
import com.fasterxml.jackson.databind.JsonNode;

// Ported from backend/app/services/notion_service.py:L5-53 (faithful port) — see docs/DECISION_LOG.md
// DL-013, DL-050, DL-052, DL-058.
/**
 * Adapter for the Notion API and the single home of the Notion mirror.
 *
 * <p>Three operations are exposed. {@link #storeTweet(TweetDto)} replaces {@code store_tweet} at
 * {@code backend/app/services/notion_service.py:L12-28}. {@link #getTweets(int, String)} replaces
 * {@code get_tweets} at {@code backend/app/services/notion_service.py:L32-53}.
 * {@link #updateTweetResponse(String, String)} is net-new: no counterpart exists on the source
 * class, and {@code backend/app/tasks/response_generation.py:L30} calls it.
 *
 * <p>Transport is the {@link RestClient} bean published by
 * {@code config/RestClientConfig#notionRestClient}, which replaces the {@code notion_client.Client}
 * constructed at {@code backend/app/services/notion_service.py:L8}. That bean already carries the
 * Notion API host, the {@code Notion-Version} header and the {@code Authorization} header holding
 * {@code scanner.notion.api-key}; none of the three is set here. Every request path declared below
 * is relative to that base URL. {@code scanner.notion.database-id} is read here and reaches each
 * request as a payload or URI value, replacing the two reads at
 * {@code backend/app/services/notion_service.py:L24} and
 * {@code backend/app/services/notion_service.py:L35}, neither of which the source ever declared
 * ({@code backend/app/core/config.py:L4-11}).
 *
 * <p>The property map written to Notion is rebuilt. The source map at
 * {@code backend/app/services/notion_service.py:L14-20} read {@code tweet.content} together with
 * {@code tweet.author}, {@code tweet.timestamp}, {@code tweet.sentiment} and
 * {@code tweet.engagement}; the last four name no field of the source model
 * ({@code backend/app/schema/tweet.py:L5-14}) and no component of {@link TweetDto}. The map written
 * here carries one property per component of {@link TweetDto} — DL-088:
 *
 * <table border="1">
 *   <caption>Notion property map</caption>
 *   <tr><th>Property</th><th>Type</th><th>Source</th></tr>
 *   <tr><td>{@code Content}</td><td>title</td><td>{@link TweetDto#content()}</td></tr>
 *   <tr><td>{@code Author}</td><td>rich_text</td><td>{@link TweetDto#userId()}</td></tr>
 *   <tr><td>{@code Timestamp}</td><td>date</td><td>{@link TweetDto#createdAt()}</td></tr>
 *   <tr><td>{@code Doubt Rating}</td><td>number</td><td>{@link TweetDto#doubtRating()}</td></tr>
 *   <tr><td>{@code Engagement}</td><td>number</td><td>{@link TweetDto#likeCount()}</td></tr>
 *   <tr><td>{@code Tweet Id}</td><td>rich_text</td><td>{@link TweetDto#id()}</td></tr>
 *   <tr><td>{@code Media}</td><td>rich_text</td><td>{@link TweetDto#media()}, delimited</td></tr>
 *   <tr><td>{@code Quoted Tweet Id}</td><td>rich_text</td>
 *       <td>{@link TweetDto#quotedTweetId()}</td></tr>
 *   <tr><td>{@code AI Tools Mentioned}</td><td>rich_text</td>
 *       <td>{@link TweetDto#aiToolsMentioned()}, delimited</td></tr>
 *   <tr><td>{@code Response}</td><td>rich_text</td>
 *       <td>{@link #updateTweetResponse(String, String)} only</td></tr>
 * </table>
 *
 * <p>{@code Doubt Rating} replaces the {@code Sentiment} select of
 * {@code backend/app/services/notion_service.py:L18}; {@code Tweet Id}, {@code Media},
 * {@code Quoted Tweet Id}, {@code AI Tools Mentioned} and {@code Response} are additions — DL-088.
 * Notion is a secondary mirror; the relational store remains the system of record.
 *
 * <p>A read returns what the page carries: an absent property yields a {@code null} component, and an
 * absent delimited property yields an empty list — DL-090. A page whose {@code Tweet Id} property
 * carries no text is left out of the result, and the Notion page identifier is never read into
 * {@link TweetDto#id()}. A structurally invalid successful response — an empty body, an absent
 * {@code results} array, or a created page carrying no identifier — is reported as a failure rather
 * than read as an empty result — DL-089. The source indexed {@code [0]} directly at
 * {@code backend/app/services/notion_service.py:L45-49}.
 *
 * <p>This class reaches no repository and holds no entity. No credential is read at construction and
 * no request is issued there, so the application context loads with {@code NOTION_API_KEY} and
 * {@code NOTION_DATABASE_ID} unset and the absence of {@code scanner.notion.database-id} surfaces from
 * each method as an {@link IllegalStateException}. No credential or key material is logged at any
 * level — DL-052.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-013, DL-050, DL-052,
 * DL-088, DL-089 and DL-090; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Both fields are {@code final}, every remaining member is a constant or a
 * stateless method, and the injected {@link RestClient} is safe for concurrent use, so every operation
 * declared here is safe for concurrent use.
 *
 * @see ScannerProperties.Notion
 */
@Service
public class NotionService {

    // Logging baseline — see docs/DECISION_LOG.md DL-052
    private static final Logger log = LoggerFactory.getLogger(NotionService.class);

    // Request paths, each relative to the base URL carried by the injected RestClient
    // (config/RestClientConfig), which is rooted at the Notion API host and carries no /v1 prefix.

    /**
     * Page creation path. Replaces {@code client.pages.create} at
     * {@code backend/app/services/notion_service.py:L23-26}.
     */
    private static final String PAGES_PATH = "/v1/pages";

    /** Page update path. The page identifier is supplied as the {@code pageId} URI variable. */
    private static final String PAGE_PATH = "/v1/pages/{pageId}";

    /**
     * Database query path. Replaces {@code client.databases.query} at
     * {@code backend/app/services/notion_service.py:L34-38}, whose {@code database_id} keyword
     * argument reaches the {@code databaseId} URI variable.
     */
    private static final String DATABASE_QUERY_PATH = "/v1/databases/{databaseId}/query";

    // Notion property names. Six are written by storeTweet; Response is written only by
    // updateTweetResponse.

    /** Title property, carrying the post body. Preserved from
     * {@code backend/app/services/notion_service.py:L15}. */
    private static final String PROPERTY_CONTENT = "Content";

    /** Rich-text property, carrying the author identifier. Name preserved from
     * {@code backend/app/services/notion_service.py:L16}. */
    private static final String PROPERTY_AUTHOR = "Author";

    /** Date property, carrying the post creation time. Name preserved from
     * {@code backend/app/services/notion_service.py:L17}. */
    private static final String PROPERTY_TIMESTAMP = "Timestamp";

    /** Number property, carrying the doubt rating. Replaces the {@code Sentiment} select of
     * {@code backend/app/services/notion_service.py:L18} — see docs/DECISION_LOG.md. */
    private static final String PROPERTY_DOUBT_RATING = "Doubt Rating";

    /** Number property, carrying the like count. Name preserved from
     * {@code backend/app/services/notion_service.py:L19}. */
    private static final String PROPERTY_ENGAGEMENT = "Engagement";

    /** Rich-text property, carrying the post identifier and correlating a page with a tweet.
     * Addition — see docs/DECISION_LOG.md. */
    private static final String PROPERTY_TWEET_ID = "Tweet Id";

    /** Rich-text property, carrying the media references. Addition — see docs/DECISION_LOG.md
     * DL-088. */
    private static final String PROPERTY_MEDIA = "Media";

    /** Rich-text property, carrying the quoted-post identifier. Addition — see
     * docs/DECISION_LOG.md DL-088. */
    private static final String PROPERTY_QUOTED_TWEET_ID = "Quoted Tweet Id";

    /** Rich-text property, carrying the AI tool names. Addition — see docs/DECISION_LOG.md
     * DL-088. */
    private static final String PROPERTY_AI_TOOLS_MENTIONED = "AI Tools Mentioned";

    /** Rich-text property, carrying the generated reply. Addition — see docs/DECISION_LOG.md. */
    private static final String PROPERTY_RESPONSE = "Response";

    // Notion JSON member names, transcribed from the payload shapes at
    // backend/app/services/notion_service.py:L14-20 and :L34-38.

    /** Container of a title property's text items. */
    private static final String KEY_TITLE = "title";

    /** Container of a rich-text property's text items, and of the rich-text filter condition. */
    private static final String KEY_RICH_TEXT = "rich_text";

    /** Text object of a single title or rich-text item. */
    private static final String KEY_TEXT = "text";

    /** Literal text carried by a {@link #KEY_TEXT} object. */
    private static final String KEY_CONTENT = "content";

    /** Flattened text of a single title or rich-text item, read when {@link #KEY_TEXT} is absent. */
    private static final String KEY_PLAIN_TEXT = "plain_text";

    /** Container of a date property's endpoints. */
    private static final String KEY_DATE = "date";

    /** Start endpoint of a date property. */
    private static final String KEY_START = "start";

    /** Value of a number property. */
    private static final String KEY_NUMBER = "number";

    /** Parent reference of a page creation request. */
    private static final String KEY_PARENT = "parent";

    /** Target database of a page creation request. */
    private static final String KEY_DATABASE_ID = "database_id";

    /** Property map of a page creation, page update or page read. */
    private static final String KEY_PROPERTIES = "properties";

    /** Identifier of a Notion page. */
    private static final String KEY_ID = "id";

    /** Page array of a database query response. */
    private static final String KEY_RESULTS = "results";

    /** Page-size argument of a database query request. */
    private static final String KEY_PAGE_SIZE = "page_size";

    /** Opaque pagination cursor of a database query request. */
    private static final String KEY_START_CURSOR = "start_cursor";

    /** Filter of a database query request. */
    private static final String KEY_FILTER = "filter";

    /** Filtered property name of a database query filter. */
    private static final String KEY_PROPERTY = "property";

    /** Equality condition of a database query filter. */
    private static final String KEY_EQUALS = "equals";

    /**
     * Configuration key naming the target database, read at
     * {@code backend/app/services/notion_service.py:L24} and
     * {@code backend/app/services/notion_service.py:L35} and declared by neither
     * {@code backend/app/core/config.py:L4-11} nor any other source module.
     */
    private static final String DATABASE_ID_KEY = "scanner.notion.database-id";

    /**
     * Page size applied when the caller supplies a non-positive limit. Transcribes the
     * {@code limit: int = 10} default at {@code backend/app/services/notion_service.py:L32}.
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** Page size of the correlation query issued by {@link #updateTweetResponse(String, String)}. */
    private static final int SINGLE_PAGE = 1;

    /** Returned in place of a title, rich-text or identifier value that is absent. */
    private static final String EMPTY_TEXT = "";

    /**
     * Notion API transport, published by {@code config/RestClientConfig#notionRestClient}. Replaces
     * the {@code notion_client.Client} field assigned at
     * {@code backend/app/services/notion_service.py:L8}.
     */
    private final RestClient restClient;

    /**
     * Bound configuration root, supplying {@code scanner.notion.database-id}. Replaces the
     * {@code get_settings()} call at {@code backend/app/services/notion_service.py:L7}, which built
     * a new settings object per instantiation ({@code backend/app/core/config.py:L17-18}).
     */
    private final ScannerProperties properties;

    /**
     * Creates the service.
     *
     * <p>No credential is read, no configuration value is validated and no request is issued here.
     *
     * @param restClient the Notion API transport; must not be {@code null}
     * @param properties the bound configuration root; must not be {@code null}
     * @throws NullPointerException if {@code restClient} or {@code properties} is {@code null}
     */
    // Replaces the constructor at backend/app/services/notion_service.py:L6-8 (faithful port) — see
    // docs/DECISION_LOG.md DL-013
    public NotionService(RestClient restClient, ScannerProperties properties) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    /**
     * Mirrors one post to the Notion database as a new page and returns the created page's
     * identifier.
     *
     * <p>The request carries the target database as its parent, matching the {@code parent} argument
     * at {@code backend/app/services/notion_service.py:L24}, and the property map built by
     * {@link #buildProperties(TweetDto)}, matching the {@code properties} argument at
     * {@code backend/app/services/notion_service.py:L25}. {@code Response} is not written here.
     *
     * <p>The returned value is the created page's identifier, in place of the raw response object the
     * source returned at {@code backend/app/services/notion_service.py:L28} — see
     * {@code docs/DECISION_LOG.md}. An empty response body, and a created page carrying no identifier,
     * are each reported as a failure — DL-089.
     *
     * @param tweet the post to mirror; must not be {@code null}
     * @return the created Notion page's identifier, never {@code null} and never empty
     * @throws NullPointerException if {@code tweet} is {@code null}
     * @throws IllegalStateException if {@code scanner.notion.database-id} is unset or blank, if the
     *     response body is empty, or if the created page carries no identifier
     * @throws org.springframework.web.client.RestClientException if the request fails or Notion
     *     answers with a client or server error status
     */
    // Ported from backend/app/services/notion_service.py:L12-28 (faithful port) — see
    // docs/DECISION_LOG.md
    public String storeTweet(TweetDto tweet) {
        Objects.requireNonNull(tweet, "tweet must not be null.");
        String databaseId = requireDatabaseId();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put(KEY_PARENT, Map.of(KEY_DATABASE_ID, databaseId));
        request.put(KEY_PROPERTIES, buildProperties(tweet));

        log.info("Mirroring tweet {} to the Notion database", tweet.id());
        try {
            JsonNode response = restClient.post()
                    .uri(PAGES_PATH)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                throw new IllegalStateException(
                        "The Notion page-creation request returned an empty body.");
            }
            String pageId = readString(response.path(KEY_ID));
            if (pageId.isEmpty()) {
                throw new IllegalStateException(
                        "The Notion page-creation response carried no page identifier.");
            }
            log.info("Tweet {} is mirrored to the Notion database", tweet.id());
            return pageId;
        } catch (RuntimeException e) {
            logFailure("Mirroring tweet " + tweet.id() + " to the Notion database", e);
            throw e;
        }
    }

    /**
     * Reads one page of mirrored posts back from the Notion database.
     *
     * <p>The request carries the page size and, when a cursor is supplied, the pagination cursor,
     * matching the {@code page_size} and {@code start_cursor} arguments at
     * {@code backend/app/services/notion_service.py:L36-37}. A {@code null} or blank cursor is
     * omitted from the request. A non-positive {@code limit} is replaced by {@value #DEFAULT_PAGE_SIZE},
     * transcribing the {@code limit: int = 10} default at
     * {@code backend/app/services/notion_service.py:L32}.
     *
     * <p>Each returned page is mapped by {@link #toTweetDto(JsonNode)}, which reads back every
     * mirrored component — DL-088. A page that carries no {@code Tweet Id} property is left out of the
     * result — DL-090.
     *
     * @param limit maximum number of pages to read; a non-positive value is replaced by
     *     {@value #DEFAULT_PAGE_SIZE}
     * @param startCursor opaque pagination cursor from a previous response, or {@code null} for the
     *     first page
     * @return the mirrored posts in the order Notion returned them, empty when the query matched
     *     none; never {@code null} and never modifiable
     * @throws IllegalStateException if {@code scanner.notion.database-id} is unset or blank
     * @throws org.springframework.web.client.RestClientException if the request fails or Notion
     *     answers with a client or server error status
     */
    // Ported from backend/app/services/notion_service.py:L32-53 (faithful port) — see
    // docs/DECISION_LOG.md
    public List<TweetDto> getTweets(int limit, String startCursor) {
        String databaseId = requireDatabaseId();
        int pageSize = (limit <= 0) ? DEFAULT_PAGE_SIZE : limit;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put(KEY_PAGE_SIZE, pageSize);
        if (startCursor != null && !startCursor.isBlank()) {
            request.put(KEY_START_CURSOR, startCursor);
        }

        log.info("Querying the Notion database for up to {} mirrored tweet page(s)", pageSize);
        try {
            JsonNode response = restClient.post()
                    .uri(DATABASE_QUERY_PATH, databaseId)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            List<TweetDto> tweets = readTweets(response);
            log.info("The Notion database query returned {} mirrored tweet(s)", tweets.size());
            return tweets;
        } catch (RuntimeException e) {
            logFailure("Querying the Notion database for up to " + pageSize + " page(s)", e);
            throw e;
        }
    }

    /**
     * Mirrors a generated reply onto the Notion page that carries the supplied post identifier.
     *
     * <p>The page is located by a database query filtered on the {@code Tweet Id} rich-text property,
     * and the first matching page's {@code Response} rich-text property is then replaced. A
     * {@code null} reply is written as an empty string.
     *
     * <p>Two conditions produce a warning and a normal return, leaving Notion untouched: a
     * {@code null} or blank post identifier, and a query that matches no page. A structurally invalid
     * query response, and a matching page that carries no identifier, are each reported as a failure —
     * DL-089.
     *
     * <p>This operation writes to Notion only and reaches no repository.
     *
     * @param tweetId identifier of the post whose page is updated; a {@code null} or blank value
     *     leaves Notion untouched
     * @param responseText the generated reply to write; {@code null} is written as an empty string
     * @throws IllegalStateException if {@code scanner.notion.database-id} is unset or blank, if the
     *     query response is structurally invalid, or if the matched page carries no identifier
     * @throws org.springframework.web.client.RestClientException if either request fails or Notion
     *     answers with a client or server error status
     */
    // Net-new (no Python counterpart) — called at backend/app/tasks/response_generation.py:L30 —
    // see docs/DECISION_LOG.md
    public void updateTweetResponse(String tweetId, String responseText) {
        if (tweetId == null || tweetId.isBlank()) {
            log.warn("No tweet identifier was supplied; no Notion page is updated");
            return;
        }
        String databaseId = requireDatabaseId();
        String content = (responseText == null) ? EMPTY_TEXT : responseText;

        try {
            String pageId = findPageIdByTweetId(databaseId, tweetId);
            if (pageId == null) {
                log.warn("No Notion page carries the {} property {}; the generated response is not "
                        + "mirrored", PROPERTY_TWEET_ID, tweetId);
                return;
            }

            restClient.patch()
                    .uri(PAGE_PATH, pageId)
                    .body(Map.of(KEY_PROPERTIES,
                            Map.of(PROPERTY_RESPONSE, richTextProperty(content))))
                    .retrieve()
                    .toBodilessEntity();

            log.info("The generated response for tweet {} is mirrored to Notion", tweetId);
        } catch (RuntimeException e) {
            logFailure("Mirroring the generated response for tweet " + tweetId + " to Notion", e);
            throw e;
        }
    }

    /**
     * Records a failed Notion operation with sanitized metadata only.
     *
     * <p>The record carries the operation, the failure's type and, when the failure reports one, the
     * HTTP status code Notion answered with. It carries no exception message, no stack trace, no
     * request URI, no response body and no Notion page or database identifier — see
     * docs/DECISION_LOG.md DL-084. The failure itself is rethrown unchanged.
     *
     * @param operation the operation that failed, naming only this service's own identifiers
     * @param failure   the failure to record
     */
    // DL-084 — see docs/DECISION_LOG.md
    private static void logFailure(String operation, RuntimeException failure) {
        if (failure instanceof RestClientResponseException answered) {
            log.error("{} failed: {} after HTTP {}", operation,
                    failure.getClass().getSimpleName(), answered.getStatusCode().value());
            return;
        }
        log.error("{} failed: {}", operation, failure.getClass().getSimpleName());
    }

    /**
     * Returns the configured target database identifier, rejecting one that is absent or blank.
     *
     * <p>The failure message names the configuration key only — DL-052. The key binds from an
     * empty-safe placeholder in {@code src/main/resources/application.yml}, so an unset
     * {@code NOTION_DATABASE_ID} binds as a blank value, and a blank value and an absent one are
     * rejected alike.
     *
     * @return the configured database identifier with surrounding whitespace removed, guaranteed
     *     neither {@code null} nor blank
     * @throws IllegalStateException if {@code scanner.notion.database-id} is unset or blank
     */
    // Replaces the unguarded reads of settings.NOTION_DATABASE_ID at
    // backend/app/services/notion_service.py:L24 and :L35, a key the source never declared
    // (backend/app/core/config.py:L4-11) — see docs/DECISION_LOG.md
    private String requireDatabaseId() {
        ScannerProperties.Notion notion = properties.notion();
        String databaseId = (notion == null) ? null : notion.databaseId();
        if (databaseId == null || databaseId.isBlank()) {
            throw new IllegalStateException(
                    DATABASE_ID_KEY + " is not configured; set it to reach the Notion database.");
        }
        return databaseId.strip();
    }

    /**
     * Returns the identifier of the first page whose {@code Tweet Id} property equals the supplied
     * post identifier.
     *
     * @param databaseId the target database identifier; neither {@code null} nor blank
     * @param tweetId    the post identifier to match; neither {@code null} nor blank
     * @return the matching page's identifier, or {@code null} when the query matched no page or the
     *     matched pages carry no identifier
     */
    private String findPageIdByTweetId(String databaseId, String tweetId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(KEY_FILTER, Map.of(
                KEY_PROPERTY, PROPERTY_TWEET_ID,
                KEY_RICH_TEXT, Map.of(KEY_EQUALS, tweetId)));
        request.put(KEY_PAGE_SIZE, SINGLE_PAGE);

        JsonNode response = restClient.post()
                .uri(DATABASE_QUERY_PATH, databaseId)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        JsonNode results = requireResults(response);
        if (results.isEmpty()) {
            return null;
        }
        for (JsonNode page : results) {
            String pageId = readString(page.path(KEY_ID));
            if (!pageId.isEmpty()) {
                return pageId;
            }
        }
        throw new IllegalStateException(
                "The Notion database query matched a page that carried no page identifier.");
    }

    /**
     * Builds the property map written by {@link #storeTweet(TweetDto)}, one property per component
     * of the mirrored post.
     *
     * <p>All nine components of {@link TweetDto} are mirrored — see docs/DECISION_LOG.md DL-088. A
     * property whose value is absent, or whose numeric value is not finite, is omitted from the map, and
     * an omitted property reads back as a {@code null} component. {@code media} and
     * {@code aiToolsMentioned} are each written as one delimited rich-text value produced by
     * {@link DelimitedStringListConverter#encode(List)}, the same form the columns hold
     * ({@code backend/app/db/models.py:L15,L18}).
     * {@code Response} is not written here.
     *
     * @param tweet the post to map; not {@code null}
     * @return the property map, in the declaration order of the table on this class; never
     *     {@code null}
     */
    // Rebuilt from the property map at backend/app/services/notion_service.py:L14-20, four of whose
    // five reads named no field of backend/app/schema/tweet.py:L5-14 — DL-088 — see
    // docs/DECISION_LOG.md
    private static Map<String, Object> buildProperties(TweetDto tweet) {
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfPresent(properties, PROPERTY_CONTENT, titleProperty(tweet.content()));
        putIfPresent(properties, PROPERTY_AUTHOR, richTextProperty(tweet.userId()));
        putIfPresent(properties, PROPERTY_TIMESTAMP, dateProperty(tweet.createdAt()));
        putIfPresent(properties, PROPERTY_DOUBT_RATING, numberProperty(tweet.doubtRating()));
        putIfPresent(properties, PROPERTY_ENGAGEMENT, numberProperty(tweet.likeCount()));
        putIfPresent(properties, PROPERTY_TWEET_ID, richTextProperty(tweet.id()));
        putIfPresent(properties, PROPERTY_MEDIA, richTextProperty(delimited(tweet.media())));
        putIfPresent(properties, PROPERTY_QUOTED_TWEET_ID,
                richTextProperty(tweet.quotedTweetId()));
        putIfPresent(properties, PROPERTY_AI_TOOLS_MENTIONED,
                richTextProperty(delimited(tweet.aiToolsMentioned())));
        return properties;
    }

    /**
     * Renders a list component as the single delimited text one rich-text property carries.
     *
     * <p>The rendering is performed by {@link DelimitedStringListConverter#encode(List)}, the one
     * authorized codec for this representation, so the text written to Notion is byte-for-byte the
     * text the corresponding column holds. A {@code null} list, a list holding no element and a list
     * retaining no element after that codec's write contract is applied all yield {@code null}, which
     * omits the property.
     *
     * @param values the list component; may be {@code null} and may be empty
     * @return the delimited text, or {@code null} when the component carries no element
     */
    // DL-088, DL-164 — see docs/DECISION_LOG.md
    private static String delimited(List<String> values) {
        return DelimitedStringListConverter.encode(values);
    }

    /**
     * Adds one property to the map, skipping a {@code null} value.
     *
     * @param properties the map under construction; not {@code null}
     * @param name       the Notion property name; not {@code null}
     * @param value      the rendered property, or {@code null} to omit it
     */
    private static void putIfPresent(Map<String, Object> properties, String name,
            Map<String, Object> value) {
        if (value != null) {
            properties.put(name, value);
        }
    }

    /**
     * Renders a title property. Transcribes the shape at
     * {@code backend/app/services/notion_service.py:L15}.
     *
     * @param value the literal text, or {@code null}
     * @return the rendered property, or {@code null} when {@code value} is {@code null}
     */
    private static Map<String, Object> titleProperty(String value) {
        return (value == null) ? null : Map.of(KEY_TITLE, List.of(textItem(value)));
    }

    /**
     * Renders a rich-text property. Transcribes the shape at
     * {@code backend/app/services/notion_service.py:L16}.
     *
     * @param value the literal text, or {@code null}
     * @return the rendered property, or {@code null} when {@code value} is {@code null}
     */
    private static Map<String, Object> richTextProperty(String value) {
        return (value == null) ? null : Map.of(KEY_RICH_TEXT, List.of(textItem(value)));
    }

    /**
     * Renders the single text item shared by the title and rich-text shapes.
     *
     * @param value the literal text; not {@code null}
     * @return the rendered text item; never {@code null}
     */
    private static Map<String, Object> textItem(String value) {
        return Map.of(KEY_TEXT, Map.of(KEY_CONTENT, value));
    }

    /**
     * Renders a date property, formatted as an ISO-8601 local date-time. Transcribes the shape and
     * the {@code isoformat()} call at {@code backend/app/services/notion_service.py:L17}.
     *
     * @param value the date-time, or {@code null}
     * @return the rendered property, or {@code null} when {@code value} is {@code null}
     */
    private static Map<String, Object> dateProperty(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return Map.of(KEY_DATE,
                Map.of(KEY_START, value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
    }

    /**
     * Renders a number property. Transcribes the shape at
     * {@code backend/app/services/notion_service.py:L19}.
     *
     * @param value the numeric value, or {@code null}
     * @return the rendered property, or {@code null} when {@code value} is {@code null} or is not a
     *     finite number
     */
    private static Map<String, Object> numberProperty(Number value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            return null;
        }
        return Map.of(KEY_NUMBER, value);
    }

    /**
     * Maps every page of a database query response.
     *
     * <p>Replaces the loop at {@code backend/app/services/notion_service.py:L41-51}.
     *
     * <p>An empty body and a body whose {@code results} member is absent or is not an array are
     * reported as an adapter failure; an empty {@code results} array yields an empty list — see
     * docs/DECISION_LOG.md DL-089.
     *
     * <p>A page {@link #toTweetDto(JsonNode)} does not recognise as a mirrored post is reported at
     * {@code WARN} and left out of the list.
     *
     * @param response the parsed query response, or {@code null} when the body was empty
     * @return the mapped posts; never {@code null} and never modifiable
     * @throws IllegalStateException if the response carries no {@code results} array
     */
    // DL-089 — see docs/DECISION_LOG.md
    private static List<TweetDto> readTweets(JsonNode response) {
        JsonNode results = requireResults(response);
        List<TweetDto> tweets = new ArrayList<>();
        int skipped = 0;
        for (JsonNode page : results) {
            TweetDto tweet = toTweetDto(page);
            if (tweet == null) {
                skipped++;
                continue;
            }
            tweets.add(tweet);
        }
        if (skipped > 0) {
            log.warn("{} Notion page(s) carried no mirrored tweet identifier and were skipped",
                    skipped);
        }
        return List.copyOf(tweets);
    }

    /**
     * Returns the {@code results} array of a successful database query response.
     *
     * <p>An empty body, and a response whose {@code results} member is absent or is not an array, are
     * each reported as an adapter failure rather than read as "no match" — see docs/DECISION_LOG.md
     * DL-089. The message names neither the request URI nor any part of the response — DL-052.
     *
     * @param response the parsed query response, or {@code null} when the body was empty
     * @return the {@code results} array, possibly empty; never {@code null}
     * @throws IllegalStateException if the response carries no {@code results} array
     */
    // DL-089 — see docs/DECISION_LOG.md
    private static JsonNode requireResults(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException(
                    "The Notion database query returned an empty body.");
        }
        JsonNode results = response.path(KEY_RESULTS);
        if (!results.isArray()) {
            throw new IllegalStateException(
                    "The Notion database query response carried no results array.");
        }
        return results;
    }

    /**
     * Maps one Notion page onto a {@link TweetDto}, reading the property map written by
     * {@link #buildProperties(TweetDto)} in reverse.
     *
     * <p>All nine components are read back — see docs/DECISION_LOG.md DL-088. A property the page does
     * not carry yields a {@code null} component, and the two list components yield an empty list — see
     * docs/DECISION_LOG.md DL-090.
     *
     * <p>The {@code Tweet Id} property identifies a page as a mirrored post. A page whose
     * {@code Tweet Id} carries no text yields {@code null} and the caller leaves it out, and the Notion
     * page identifier is never read into {@link TweetDto#id()} — see docs/DECISION_LOG.md DL-090.
     *
     * @param page one element of a query response's {@code results} array; not {@code null}
     * @return the mapped post, or {@code null} when the page carries no mirrored tweet identifier
     */
    // Replaces the reconstruction at backend/app/services/notion_service.py:L44-50, which indexed
    // [0] directly and read four properties fed from fields the source model never declared —
    // DL-088, DL-090 — see docs/DECISION_LOG.md
    private static TweetDto toTweetDto(JsonNode page) {
        JsonNode properties = page.path(KEY_PROPERTIES);

        String id = readText(properties, PROPERTY_TWEET_ID, KEY_RICH_TEXT);
        if (id == null) {
            return null;
        }
        Double engagement = readNumber(properties, PROPERTY_ENGAGEMENT);

        return new TweetDto(
                id,
                readText(properties, PROPERTY_CONTENT, KEY_TITLE),
                (engagement == null) ? null : Integer.valueOf(engagement.intValue()),
                readTimestamp(properties),
                readNumber(properties, PROPERTY_DOUBT_RATING),
                readList(properties, PROPERTY_MEDIA),
                readText(properties, PROPERTY_QUOTED_TWEET_ID, KEY_RICH_TEXT),
                readText(properties, PROPERTY_AUTHOR, KEY_RICH_TEXT),
                readList(properties, PROPERTY_AI_TOOLS_MENTIONED));
    }

    /**
     * Reads one delimited rich-text property back into a list component.
     *
     * <p>The text is read by {@link DelimitedStringListConverter#decode(String)}, the one authorized
     * codec for this representation, so a mirrored value and the corresponding column value read back
     * identically — see docs/DECISION_LOG.md DL-024 and DL-164. An absent property yields an empty
     * list.
     *
     * @param properties   the page's property map; not {@code null}
     * @param propertyName the Notion property name; not {@code null}
     * @return the list component, empty when the property is absent or holds no token; never
     *     {@code null}
     */
    // DL-088, DL-164 — see docs/DECISION_LOG.md
    private static List<String> readList(JsonNode properties, String propertyName) {
        return DelimitedStringListConverter.decode(readText(properties, propertyName,
                KEY_RICH_TEXT));
    }

    /**
     * Reads the first non-empty literal text of a title or rich-text property.
     *
     * <p>Each item's {@code text.content} member is read first and its {@code plain_text} member
     * second. An absent property, a property carrying a container of another type and an empty item
     * array each yield {@code null} — see docs/DECISION_LOG.md DL-090.
     *
     * @param properties   the page's property map; not {@code null}
     * @param propertyName the Notion property name; not {@code null}
     * @param containerKey {@link #KEY_TITLE} or {@link #KEY_RICH_TEXT}
     * @return the literal text, or {@code null} when the property carries none
     */
    // DL-090 — see docs/DECISION_LOG.md
    private static String readText(JsonNode properties, String propertyName, String containerKey) {
        for (JsonNode item : properties.path(propertyName).path(containerKey)) {
            String content = readString(item.path(KEY_TEXT).path(KEY_CONTENT));
            if (!content.isEmpty()) {
                return content;
            }
            String plainText = readString(item.path(KEY_PLAIN_TEXT));
            if (!plainText.isEmpty()) {
                return plainText;
            }
        }
        return null;
    }

    /**
     * Reads a textual node.
     *
     * <p>Only a textual node yields its text. A missing node and a JSON null node both yield an
     * empty string.
     *
     * @param node the node to read; not {@code null}
     * @return the node's text, or an empty string when the node is not textual; never {@code null}
     */
    private static String readString(JsonNode node) {
        return node.isTextual() ? node.asText() : EMPTY_TEXT;
    }

    /**
     * Reads a number property.
     *
     * @param properties   the page's property map; not {@code null}
     * @param propertyName the Notion property name; not {@code null}
     * @return the numeric value, or {@code null} when the property is absent or carries no number
     */
    private static Double readNumber(JsonNode properties, String propertyName) {
        JsonNode number = properties.path(propertyName).path(KEY_NUMBER);
        return number.isNumber() ? number.doubleValue() : null;
    }

    /**
     * Reads the {@code Timestamp} property's start endpoint.
     *
     * <p>An absent property yields {@code null}, and so does a value no supported form parses — see
     * docs/DECISION_LOG.md DL-090. An unparseable value is reported at {@code WARN} by property name
     * alone.
     *
     * @param properties the page's property map; not {@code null}
     * @return the parsed date-time, or {@code null} when the property is absent or its value cannot
     *     be parsed
     */
    // DL-090 — see docs/DECISION_LOG.md
    private static LocalDateTime readTimestamp(JsonNode properties) {
        String raw = readString(properties.path(PROPERTY_TIMESTAMP).path(KEY_DATE).path(KEY_START));
        if (raw.isEmpty()) {
            return null;
        }
        LocalDateTime parsed = parseOffsetDateTime(raw);
        if (parsed == null) {
            log.warn("The Notion {} property carried an unparseable value; the mirrored post carries "
                    + "no creation time", PROPERTY_TIMESTAMP);
        }
        return parsed;
    }

    /**
     * Parses an ISO-8601 date-time carrying a zone offset, delegating to
     * {@link #parseLocalDateTime(String)} when the value carries none.
     *
     * @param raw the raw property value; neither {@code null} nor empty
     * @return the parsed date-time, or {@code null} when no supported form matched
     */
    private static LocalDateTime parseOffsetDateTime(String raw) {
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (DateTimeParseException noZoneOffset) {
            return parseLocalDateTime(raw);
        }
    }

    /**
     * Parses an ISO-8601 local date-time, delegating to {@link #parseLocalDate(String)} when the
     * value carries no time.
     *
     * @param raw the raw property value; neither {@code null} nor empty
     * @return the parsed date-time, or {@code null} when no supported form matched
     */
    private static LocalDateTime parseLocalDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException noTimeOfDay) {
            return parseLocalDate(raw);
        }
    }

    /**
     * Parses a date-only ISO-8601 value, resolved to the start of that day.
     *
     * @param raw the raw property value; neither {@code null} nor empty
     * @return the parsed date-time, or {@code null} when the value is not a date
     */
    private static LocalDateTime parseLocalDate(String raw) {
        try {
            return LocalDate.parse(raw).atStartOfDay();
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }
}
