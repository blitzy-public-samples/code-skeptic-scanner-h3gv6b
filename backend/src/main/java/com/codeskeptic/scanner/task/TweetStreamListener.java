package com.codeskeptic.scanner.task;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.fasterxml.jackson.databind.JsonNode;

// Ported from backend/app/tasks/tweet_monitoring.py:L8-34 (faithful port) — see
// docs/DECISION_LOG.md
// The scaffolding annotation at backend/app/tasks/tweet_monitoring.py:L13-14 is resolved by this
// implementation and is not carried forward — see docs/DECISION_LOG.md
/**
 * Handles one record delivered by the X filtered stream.
 *
 * <p>{@link #onStatus(JsonNode)} is the single operation. It performs the four steps documented for
 * {@code on_status} at {@code documentation/Code Structure.md:L1407-1411}, in that order: it
 * evaluates the popularity gate, obtains the sentiment score and the doubt rating, stores one
 * {@code tweets} row, and triggers response generation for the stored row. A Notion mirror write is
 * issued between the storing step and the trigger.
 *
 * <p>The relational database is the system of record and the Notion write is a secondary mirror: a
 * failed mirror write leaves the stored row in place and does not stop the trigger, and a failed
 * trigger leaves the stored row in place.
 *
 * <p>This class issues no outbound request of its own and carries no X protocol surface. The stream
 * connection, its rule set and its reconnection handling belong to {@code TweetStreamClient}, which
 * calls this operation once per delivered record.
 *
 * <p>Two values this operation depends on are computed elsewhere and are not derived here: the
 * popularity gate is evaluated by {@link TwitterService#meetsPopularityThreshold(Integer)}, and the
 * doubt rating written to {@code tweets.doubt_rating} is produced by
 * {@link SentimentAnalysisService#calculateDoubtRating(double)}.
 *
 * <p>Ingestion stores every accepted record: no stored row is read back for comparison and no
 * identifier of the delivered record is matched against the table. A record delivered twice stores
 * two rows — see docs/DECISION_LOG.md DL-049.
 *
 * <p>{@code spring.jpa.open-in-view} is {@code false}. The stored entity is converted to its wire
 * form by {@link TweetMapper}, which reads only loaded scalar values and never traverses the lazy
 * {@code responses} association, and no operation here returns an entity.
 *
 * <p>Collaborators arrive through the constructor and are held for the lifetime of the singleton, in
 * place of the {@code TwitterService()} and {@code SentimentAnalysis()} instantiation performed at
 * {@code backend/app/tasks/tweet_monitoring.py:L40-41}. The class carries no mutable state, so it is
 * safe for concurrent use.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-049 and DL-052;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @see TwitterService#meetsPopularityThreshold(Integer)
 * @see SentimentAnalysisService#calculateDoubtRating(double)
 * @see ResponseService#generateResponse(String)
 */
@Component
public class TweetStreamListener {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    /** Records each accepted record, each skipped record and each failed collaborator call. */
    private static final Logger log = LoggerFactory.getLogger(TweetStreamListener.class);

    /**
     * Longest rendered payload prefix a log record carries. A rendered payload longer than this many
     * characters is truncated to this length and marked with a trailing {@value #TRUNCATION_MARK}.
     */
    private static final int PAYLOAD_EXCERPT_LIMIT = 120;

    /** Appended to a rendered payload that was truncated to {@value #PAYLOAD_EXCERPT_LIMIT}. */
    private static final String TRUNCATION_MARK = "...";

    /** Payload member carrying the delivered post object. */
    private static final String KEY_DATA = "data";

    /** Post member carrying the text of the post, mapped to {@code tweets.content}. */
    private static final String KEY_TEXT = "text";

    /** Post member carrying the engagement counters. */
    private static final String KEY_PUBLIC_METRICS = "public_metrics";

    /** Counter member mapped to {@code tweets.like_count}. */
    private static final String KEY_LIKE_COUNT = "like_count";

    /** Post member mapped to {@code tweets.created_at}. */
    private static final String KEY_CREATED_AT = "created_at";

    /** Post member mapped to {@code tweets.user_id}. */
    private static final String KEY_AUTHOR_ID = "author_id";

    /** Post member carrying the attachment references. */
    private static final String KEY_ATTACHMENTS = "attachments";

    /** Attachment member mapped to {@code tweets.media}. */
    private static final String KEY_MEDIA_KEYS = "media_keys";

    /** Payload member carrying the stream rules the record matched. */
    private static final String KEY_MATCHING_RULES = "matching_rules";

    /** Rule member mapped to {@code tweets.ai_tools_mentioned}. */
    private static final String KEY_TAG = "tag";

    /** Post member carrying the posts this record references. */
    private static final String KEY_REFERENCED_TWEETS = "referenced_tweets";

    /** Reference member naming the kind of reference. */
    private static final String KEY_TYPE = "type";

    /** Reference member carrying the referenced post's identifier. */
    private static final String KEY_ID = "id";

    /** Value of {@value #KEY_TYPE} that maps a reference to {@code tweets.quoted_tweet_id}. */
    private static final String QUOTED_REFERENCE_TYPE = "quoted";

    /** Evaluates the popularity gate over a like count. */
    private final TwitterService twitterService;

    /** Supplies the document sentiment score and the doubt rating derived from it. */
    private final SentimentAnalysisService sentimentAnalysisService;

    /** Data access for the {@code tweets} table. */
    private final TweetRepository tweetRepository;

    /** Generates and stores the reply to a stored {@code tweets} row. */
    private final ResponseService responseService;

    /** Mirrors a stored row to the Notion database. */
    private final NotionService notionService;

    /** Converts a stored {@link Tweet} into the wire form the Notion mirror write carries. */
    private final TweetMapper tweetMapper;

    // Replaces __init__ at backend/app/tasks/tweet_monitoring.py:L9-11 and the per-call
    // instantiation at :L40-41 (faithful port of the injection points) — see docs/DECISION_LOG.md
    /**
     * Creates the component with its six collaborators.
     *
     * <p>Construction resolves no credential, opens no connection and reads no configuration key.
     *
     * @param twitterService           evaluator of the popularity gate, must not be {@code null}
     * @param sentimentAnalysisService supplier of the sentiment score and the doubt rating, must not
     *                                 be {@code null}
     * @param tweetRepository          data access for the {@code tweets} table, must not be
     *                                 {@code null}
     * @param responseService          generator of the reply to a stored row, must not be
     *                                 {@code null}
     * @param notionService            adapter writing the secondary Notion mirror, must not be
     *                                 {@code null}
     * @param tweetMapper              entity-to-wire converter, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public TweetStreamListener(TwitterService twitterService,
            SentimentAnalysisService sentimentAnalysisService,
            TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService,
            TweetMapper tweetMapper) {
        this.twitterService = Objects.requireNonNull(twitterService,
                "twitterService must not be null.");
        this.sentimentAnalysisService = Objects.requireNonNull(sentimentAnalysisService,
                "sentimentAnalysisService must not be null.");
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
        this.notionService = Objects.requireNonNull(notionService,
                "notionService must not be null.");
        this.tweetMapper = Objects.requireNonNull(tweetMapper, "tweetMapper must not be null.");
    }

    // Ported from on_status at backend/app/tasks/tweet_monitoring.py:L15-34 (faithful port) — see
    // docs/DECISION_LOG.md
    /**
     * Handles one stream record and reports whether streaming continues.
     *
     * <p>The record is read as the X filtered stream delivers it: a {@code data} object carrying the
     * post, an optional {@code matching_rules} array naming the rules it matched, and any further
     * members. Every member is read through {@link JsonNode#path(String)}, which yields a missing
     * node when the member is absent.
     *
     * <p>Four steps run in the order documented at
     * {@code documentation/Code Structure.md:L1407-1411}:
     *
     * <ol>
     *   <li>The like count of {@code data.public_metrics.like_count} is offered to
     *       {@link TwitterService#meetsPopularityThreshold(Integer)}. A record the gate rejects is
     *       recorded at {@code DEBUG} and nothing further happens to it.</li>
     *   <li>The text of {@code data.text} is offered to
     *       {@link SentimentAnalysisService#analyzeSentiment(String)} and the returned score to
     *       {@link SentimentAnalysisService#calculateDoubtRating(double)}.</li>
     *   <li>One {@code tweets} row is stored, carrying the text as {@code content}, the like count,
     *       the creation time, the doubt rating from the previous step, {@code data.author_id} as
     *       {@code user_id}, {@code data.attachments.media_keys} as {@code media}, the {@code tag}
     *       values of {@code matching_rules} as {@code ai_tools_mentioned}, and the identifier of
     *       the first {@code data.referenced_tweets} entry of type {@value #QUOTED_REFERENCE_TYPE}
     *       as {@code quoted_tweet_id}. The row's {@code id} is assigned by the database on insert
     *       and the {@code responses} association is not touched.</li>
     *   <li>The stored row is mirrored to the Notion database, then
     *       {@link ResponseService#generateResponse(String)} is called with the assigned identifier.
     *       The generated row is stored by that call awaiting review; nothing here reads or writes
     *       its approval flag.</li>
     * </ol>
     *
     * <p>Every outcome reports {@code true}, reproducing the unconditional
     * {@code return True} at {@code backend/app/tasks/tweet_monitoring.py:L34}. Nothing is stored and
     * a record is reported as handled when {@code payload} is {@code null}, when {@code data} is
     * absent, when {@code data.text} is absent or blank, when the popularity gate reports
     * {@code false}, and when obtaining the sentiment score or the doubt rating fails. Once the row
     * is stored, a failure of the Notion mirror write and a failure of the trigger are each recorded
     * at {@code ERROR} and the row remains stored.
     *
     * <p>A malformed record never raises out of this method. A failure raised while storing the row
     * propagates to the caller unchanged.
     *
     * @param payload one record of the filtered stream, may be {@code null}
     * @return {@code true} to continue streaming, {@code false} to stop, as documented at
     *         {@code documentation/Code Structure.md:L1393}
     */
    public boolean onStatus(JsonNode payload) {
        if (payload == null) {
            log.warn("Skipping a stream record that carries no payload.");
            return true;
        }

        JsonNode data = payload.path(KEY_DATA);
        String text = readString(data.path(KEY_TEXT));
        if (text == null) {
            log.warn("Skipping a stream record that carries no post text: {}", excerpt(payload));
            return true;
        }

        Integer likeCount = readInteger(data.path(KEY_PUBLIC_METRICS).path(KEY_LIKE_COUNT));

        // Step 1 — the call at backend/app/tasks/tweet_monitoring.py:L17; the threshold and the
        // comparison belong to TwitterService — see docs/DECISION_LOG.md
        if (!twitterService.meetsPopularityThreshold(likeCount)) {
            log.debug("Skipping a stream record with like count {}: the popularity gate reports "
                    + "false.", likeCount);
            return true;
        }

        // Step 2 — the call at backend/app/tasks/tweet_monitoring.py:L19; the doubt-rating
        // calculation belongs to SentimentAnalysisService — see docs/DECISION_LOG.md
        double doubtRating;
        try {
            double sentimentScore = sentimentAnalysisService.analyzeSentiment(text);
            doubtRating = sentimentAnalysisService.calculateDoubtRating(sentimentScore);
        } catch (RuntimeException failure) {
            log.error("Skipping a stream record: obtaining its doubt rating failed with {}.",
                    failure.getClass().getSimpleName());
            return true;
        }

        // Step 3 — the columns of backend/app/db/models.py:L10-18, in place of the id, text, user and
        // sentiment arguments at backend/app/tasks/tweet_monitoring.py:L22-28 — see
        // docs/DECISION_LOG.md
        Tweet tweet = new Tweet();
        tweet.setContent(text);
        tweet.setLikeCount(likeCount);
        tweet.setCreatedAt(readCreatedAt(data.path(KEY_CREATED_AT)));
        tweet.setDoubtRating(doubtRating);
        tweet.setMedia(readTextArray(data.path(KEY_ATTACHMENTS).path(KEY_MEDIA_KEYS)));
        tweet.setQuotedTweetId(readQuotedTweetId(data.path(KEY_REFERENCED_TWEETS)));
        tweet.setUserId(readString(data.path(KEY_AUTHOR_ID)));
        tweet.setAiToolsMentioned(readRuleTags(payload.path(KEY_MATCHING_RULES)));

        // Closes the unimplemented persistence step at
        // backend/app/tasks/tweet_monitoring.py:L29 — see docs/DECISION_LOG.md
        // No de-duplication — see docs/DECISION_LOG.md DL-049
        Tweet saved = tweetRepository.save(tweet);
        log.info("Stored ingested tweet row {} with like count {}.", saved.getId(), likeCount);

        // Step 4 — the secondary mirror, then the trigger
        mirrorToNotion(saved);
        triggerResponseGeneration(saved);

        // backend/app/tasks/tweet_monitoring.py:L34
        return true;
    }

    // Implements the ingestion step documented at
    // documentation/Software Requirements Specifications (SRS).md:L226 by calling store_tweet, ported
    // from backend/app/services/notion_service.py:L12-28. The step at :L227 has no counterpart here
    // — see docs/DECISION_LOG.md
    /**
     * Writes the secondary Notion mirror of a stored row.
     *
     * <p>The wire form handed to {@link NotionService#storeTweet(TweetDto)} is produced by
     * {@link TweetMapper#toDto(Tweet)}, which is the one converter that renders the row's identifier
     * as a string and its two delimited columns as arrays.
     *
     * <p>A failure raised by the mirror write is recorded at {@code ERROR} and is not rethrown; the
     * stored row is unaffected.
     *
     * @param saved the stored row, never {@code null}
     */
    private void mirrorToNotion(Tweet saved) {
        try {
            TweetDto mirrored = tweetMapper.toDto(saved);
            String pageId = notionService.storeTweet(mirrored);
            log.debug("Mirrored tweet row {} to the Notion database as page {}.",
                    saved.getId(), pageId);
        } catch (RuntimeException failure) {
            log.error("Mirroring tweet row {} to the Notion database failed with {}.",
                    saved.getId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Triggers response generation for a stored row.
     *
     * <p>{@link ResponseService#generateResponse(String)} is called in process with the identifier the
     * database assigned to the row. That call owns the language-model request, stores the generated
     * {@code responses} row and sets its approval flag; nothing here reads or writes that flag.
     *
     * <p>A failure raised by the call is recorded at {@code ERROR} and is not rethrown; the stored row
     * is unaffected.
     *
     * @param saved the stored row, never {@code null}
     */
    private void triggerResponseGeneration(Tweet saved) {
        String tweetId = String.valueOf(saved.getId());
        try {
            // Closes the unimplemented trigger at backend/app/tasks/tweet_monitoring.py:L32 — see
            // docs/DECISION_LOG.md
            ResponseDto generated = responseService.generateResponse(tweetId);
            log.info("Triggered response generation for tweet row {}; stored response {}.",
                    tweetId, generated == null ? null : generated.id());
        } catch (RuntimeException failure) {
            log.error("Triggering response generation for tweet row {} failed with {}.",
                    tweetId, failure.getClass().getSimpleName());
        }
    }

    /**
     * Reads the creation time of a delivered record.
     *
     * <p>An ISO-8601 value carrying an offset is read and normalised to UTC, so
     * {@code 2026-08-03T15:11:52.000Z} yields {@code 2026-08-03T15:11:52}. An absent, blank or
     * unparseable value is recorded at {@code WARN} and the current UTC time is returned in its
     * place. Every stored row therefore carries a creation time.
     *
     * @param node the {@code created_at} member, possibly a missing node
     * @return the creation time to store, never {@code null}
     */
    private static LocalDateTime readCreatedAt(JsonNode node) {
        String raw = readString(node);
        if (raw == null) {
            log.warn("A stream record carries no created_at value; storing the current UTC time.");
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(raw)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException failure) {
            log.warn("A stream record carries the unusable created_at value '{}'; storing the "
                    + "current UTC time.", raw);
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * Reads the identifier of the quoted post a record references.
     *
     * <p>The first entry whose {@value #KEY_TYPE} is {@value #QUOTED_REFERENCE_TYPE} supplies the
     * value. A member that is not an array, an array holding no such entry, and an entry carrying no
     * identifier each yield {@code null}, which is the stored value of the one nullable column.
     *
     * @param arrayNode the {@code referenced_tweets} member, possibly a missing node
     * @return the quoted post's identifier, or {@code null} when the record quotes nothing
     */
    private static String readQuotedTweetId(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return null;
        }
        for (int index = 0; index < arrayNode.size(); index++) {
            JsonNode reference = arrayNode.path(index);
            if (QUOTED_REFERENCE_TYPE.equals(readString(reference.path(KEY_TYPE)))) {
                return readString(reference.path(KEY_ID));
            }
        }
        return null;
    }

    /**
     * Reads an array of text values in the order the record declares them.
     *
     * <p>A member that is not an array yields an empty list, and an element that holds no usable text
     * is left out. The result is never {@code null} and is unmodifiable.
     *
     * @param arrayNode the array member to read, possibly a missing node
     * @return the text values the array holds, empty when it holds none
     */
    private static List<String> readTextArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < arrayNode.size(); index++) {
            String value = readString(arrayNode.path(index));
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    /**
     * Reads the tag of every stream rule a record matched, in the order the record declares them.
     *
     * <p>A member that is not an array yields an empty list, and a rule carrying no tag is left out.
     * A tag declared twice is carried twice. The result is never {@code null} and is unmodifiable.
     *
     * @param arrayNode the {@code matching_rules} member, possibly a missing node
     * @return the rule tags the record matched, empty when it declares none
     */
    private static List<String> readRuleTags(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (int index = 0; index < arrayNode.size(); index++) {
            String tag = readString(arrayNode.path(index).path(KEY_TAG));
            if (tag != null) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    /**
     * Reads one member as text.
     *
     * <p>A missing node, a container node, a JSON {@code null} and a blank value each yield
     * {@code null}. A numeric or boolean value yields its rendered form.
     *
     * @param node the member to read, possibly a missing node
     * @return the value the member holds, or {@code null} when it holds none
     */
    private static String readString(JsonNode node) {
        if (!node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * Reads one member as a whole number.
     *
     * <p>A missing node, a non-numeric value and a numeric value outside the range of an {@code int}
     * each yield {@code null}, which {@link TwitterService#meetsPopularityThreshold(Integer)} reads as
     * a record that does not reach the threshold.
     *
     * @param node the member to read, possibly a missing node
     * @return the value the member holds, or {@code null} when it holds no whole number
     */
    private static Integer readInteger(JsonNode node) {
        if (node.isNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        return null;
    }

    /**
     * Renders a prefix of a payload for a log record.
     *
     * <p>The rendered form is cut to {@value #PAYLOAD_EXCERPT_LIMIT} characters and marked with
     * {@value #TRUNCATION_MARK} when it is longer. No log record carries more than
     * {@value #PAYLOAD_EXCERPT_LIMIT} characters of a payload.
     *
     * @param payload the payload to render, never {@code null}
     * @return a prefix of the rendered payload, never {@code null}
     */
    private static String excerpt(JsonNode payload) {
        String rendered = payload.toString();
        if (rendered.length() <= PAYLOAD_EXCERPT_LIMIT) {
            return rendered;
        }
        return rendered.substring(0, PAYLOAD_EXCERPT_LIMIT).concat(TRUNCATION_MARK);
    }
}
