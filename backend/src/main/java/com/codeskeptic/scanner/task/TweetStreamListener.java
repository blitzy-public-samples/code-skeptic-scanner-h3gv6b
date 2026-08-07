package com.codeskeptic.scanner.task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

// Ported from the TweetListener class at backend/app/tasks/tweet_monitoring.py:L8-34 (faithful port
// of intent) — see docs/DECISION_LOG.md DL-049
// Net-new: the persistence step the source left unimplemented at :L29 and the generation trigger it
// left unimplemented at :L32; both resolutions are recorded in docs/TRACEABILITY_MATRIX.md §1.9 —
// see docs/DECISION_LOG.md DL-049
/**
 * Handles one record delivered by the X filtered stream.
 *
 * <p>{@link #onStatus(JsonNode)} is the single operation. It performs the four steps documented for
 * {@code on_status} at {@code documentation/Code Structure.md:L1407-1411}, in that order: it
 * evaluates the popularity gate, obtains the sentiment score and the doubt rating, stores one
 * {@code tweets} row, and triggers response generation for the stored row. Two Notion mirror writes
 * are issued: the stored row is mirrored between the storing step and the trigger, and the generated
 * reply is mirrored onto the same page once the trigger has stored it.
 *
 * <p>The relational database is the system of record and both Notion writes are secondary mirrors: a
 * failed row mirror leaves the stored row in place and does not stop the trigger, a failed trigger
 * leaves the stored row in place and issues no reply mirror, and a failed reply mirror leaves the
 * stored reply in place.
 *
 * <p>This class issues no outbound request of its own and carries no X protocol surface. The stream
 * connection, its rule set and its reconnection handling belong to {@link TweetStreamClient}, which
 * calls this operation once per delivered record.
 *
 * <p>Two values this operation depends on are computed elsewhere: the popularity gate is evaluated by
 * {@link TwitterService#meetsPopularityThreshold(Integer)}, and the doubt rating written to
 * {@code tweets.doubt_rating} is produced by
 * {@link SentimentAnalysisService#calculateDoubtRating(double)}.
 *
 * <p>A record that passes the popularity gate reaches the sentiment call directly: no allowance,
 * window, attempt counter or failure circuit stands between the two. A sentiment call that raises is
 * named at {@code DEBUG} and the record is skipped — no row is stored, nothing is mirrored and no
 * generation is triggered — and the next delivered record reaches the sentiment call in the same way.
 *
 * <p>Ingestion stores every accepted record: no stored row is read back for comparison and no
 * identifier of the delivered record is matched against the table. A record delivered twice stores
 * two rows — DL-049.
 *
 * <p>Every row this class stores has a wire form: a record whose {@code data.text},
 * {@code data.public_metrics.like_count}, {@code data.created_at} or {@code data.author_id} is absent
 * or does not carry its wire type is named at {@code WARN} and skipped, no row is stored for it, and no
 * neutral value is substituted for it — DL-080, DL-223. No stored row is left unrenderable by
 * {@link TweetMapper}, unmirrorable or unanswerable. Preparing a stored row's wire form is
 * nevertheless guarded, and a failure there is reported at {@code WARN} — see docs/DECISION_LOG.md
 * DL-224.
 *
 * <p>{@code spring.jpa.open-in-view} is {@code false}. The stored entity is converted to its wire
 * form by {@link TweetMapper}, which reads only loaded scalar values and never traverses the lazy
 * {@code responses} association, and no operation here returns an entity.
 *
 * <p>Collaborators arrive through the constructor and are held for the lifetime of the singleton, in
 * place of the {@code TwitterService()} and {@code SentimentAnalysis()} instantiation performed at
 * {@code backend/app/tasks/tweet_monitoring.py:L40-41}. This class is thread-safe and carries no
 * mutable state.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-049, DL-052,
 * DL-080, DL-194, DL-195, DL-197 and DL-199; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * @see TwitterService#meetsPopularityThreshold(Integer)
 * @see SentimentAnalysisService#calculateDoubtRating(double)
 * @see ResponseService#generateResponseIfAbsent(String)
 */
@Component
public class TweetStreamListener {

    private static final Logger log = LoggerFactory.getLogger(TweetStreamListener.class);

    /** Recorded when a delivered record carries no {@code data} object. */
    private static final String REJECTED_NO_DATA_OBJECT = "it carries no data object";

    /** Payload member carrying the delivered post object. */
    private static final String KEY_DATA = "data";
    private static final String KEY_TEXT = "text";
    private static final String KEY_PUBLIC_METRICS = "public_metrics";
    private static final String KEY_LIKE_COUNT = "like_count";
    private static final String KEY_CREATED_AT = "created_at";
    private static final String KEY_AUTHOR_ID = "author_id";
    private static final String KEY_ATTACHMENTS = "attachments";
    private static final String KEY_MEDIA_KEYS = "media_keys";
    private static final String KEY_MATCHING_RULES = "matching_rules";
    private static final String KEY_TAG = "tag";
    private static final String KEY_REFERENCED_TWEETS = "referenced_tweets";
    private static final String KEY_TYPE = "type";
    private static final String KEY_ID = "id";

    /** Value of {@value #KEY_TYPE} that maps a reference to {@code tweets.quoted_tweet_id}. */
    private static final String QUOTED_REFERENCE_TYPE = "quoted";

    /**
     * Minutes past the arrival instant a delivered {@code created_at} may carry and still be stored
     * — DL-247 — see docs/DECISION_LOG.md.
     */
    private static final long CREATED_AT_FUTURE_TOLERANCE_MINUTES = 5L;

    /** {@value #CREATED_AT_FUTURE_TOLERANCE_MINUTES} minutes, as a duration — DL-247. */
    private static final Duration CREATED_AT_FUTURE_TOLERANCE =
            Duration.ofMinutes(CREATED_AT_FUTURE_TOLERANCE_MINUTES);

    // Payload log redaction — DL-197 — see docs/DECISION_LOG.md
    /**
     * Payload members a log record may name. Only the member's name and whether the record carried it
     * are ever written; no member value and no rendered payload is written at any level.
     */
    private static final List<String> LOGGABLE_MEMBERS =
            List.of(KEY_DATA, KEY_TEXT, KEY_PUBLIC_METRICS, KEY_CREATED_AT, KEY_AUTHOR_ID,
                    KEY_ATTACHMENTS, KEY_REFERENCED_TWEETS, KEY_MATCHING_RULES);

    /** Evaluates the popularity gate over a like count. */
    private final TwitterService twitterService;

    private final SentimentAnalysisService sentimentAnalysisService;

    private final TweetRepository tweetRepository;

    private final ResponseService responseService;

    private final NotionService notionService;

    private final TweetMapper tweetMapper;

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

    // Ported from on_status at backend/app/tasks/tweet_monitoring.py:L15-34 (faithful port of
    // intent) — see docs/DECISION_LOG.md DL-049
    /**
     * Handles one stream record and reports whether streaming continues.
     *
     * <p>Every member is read through {@link JsonNode#path(String)}, which yields a missing node when
     * the member is absent. The four steps documented at
     * {@code documentation/Code Structure.md:L1407-1411} run in order: the popularity gate over
     * {@code data.public_metrics.like_count}, the sentiment score and doubt rating over
     * {@code data.text}, one stored {@code tweets} row, then the Notion mirror followed by the
     * generation trigger. The stored row's {@code id} is assigned by the database on insert and the
     * {@code responses} association is not touched.
     *
     * <p>{@code text}, {@code like_count}, {@code created_at} and {@code author_id} must carry their
     * wire types before any row is written. A missing or wrong-typed required member is named at
     * {@code WARN} and the record is skipped; no neutral value is stored — DL-080.
     *
     * <p>Four steps run in the order documented at
     * {@code documentation/Code Structure.md:L1407-1411}:
     *
     * <ol>
     *   <li>An integral {@code data.public_metrics.like_count} is offered to
     *       {@link TwitterService#meetsPopularityThreshold(Integer)}. A missing or invalid count is
     *       skipped at {@code WARN}; a valid count the gate rejects is recorded at {@code DEBUG} and
     *       nothing further happens to it.</li>
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
     *       {@link ResponseService#generateResponseIfAbsent(String)} is called with the assigned
     *       identifier. That call is the single background generation entry point and stores nothing
     *       when the row already carries a reply — see docs/DECISION_LOG.md DL-195. The generated row
     *       is stored by that call awaiting review; nothing here reads or writes its approval
     *       flag.</li>
     * </ol>
     *
     * <p>Every handled outcome reports {@code true}, matching
     * {@code backend/app/tasks/tweet_monitoring.py:L34}. Invalid payloads and failures before storage
     * are logged and skipped. Mirror and generation failures are logged after the stored row commits.
     * A persistence failure propagates to the caller.
     *
     * @param payload one record of the filtered stream, may be {@code null}
     * @return {@code true} to continue streaming, {@code false} to stop, as documented at
     *         {@code documentation/Code Structure.md:L1393}
     */
    // Payload type at the ingestion boundary — see docs/DECISION_LOG.md DL-199
    public boolean onStatus(JsonNode payload) {
        return handleRecord(payload, null);
    }

    // Net-new: the threshold is resolved by the caller, once per ingestion cycle — DL-255 — see
    // docs/DECISION_LOG.md
    /**
     * Handles one delivered record against an already resolved popularity threshold.
     *
     * <p>Behaves exactly as {@link #onStatus(JsonNode)} in every respect except one: the popularity
     * threshold is the supplied value and the gate resolves none, so this overload reads the
     * {@code settings} table not at all — see docs/DECISION_LOG.md DL-255. The order of the four
     * steps, every log record and every stored value are unchanged.
     *
     * @param payload one record of the filtered stream, may be {@code null}
     * @param popularityThreshold the threshold the gate compares a like count against, as returned by
     *     {@link TwitterService#popularityThresholdInForce()}
     * @return {@code true} to continue streaming, {@code false} to stop, as documented at
     *         {@code documentation/Code Structure.md:L1393}
     */
    public boolean onStatus(JsonNode payload, int popularityThreshold) {
        return handleRecord(payload, popularityThreshold);
    }

    // The single body both overloads run — DL-255 — see docs/DECISION_LOG.md
    /**
     * Performs the four documented steps for one delivered record.
     *
     * @param payload one record of the filtered stream, may be {@code null}
     * @param resolvedThreshold the threshold the gate compares against, or {@code null} for the gate
     *     to resolve it itself at the moment it is evaluated
     * @return {@code true} to continue streaming, {@code false} to stop
     */
    private boolean handleRecord(JsonNode payload, Integer resolvedThreshold) {
        if (payload == null) {
            log.warn("Skipping a stream record that carries no payload.");
            return true;
        }

        JsonNode data = payload.path(KEY_DATA);
        if (!data.isObject()) {
            log.warn("Skipping a stream record: {}", REJECTED_NO_DATA_OBJECT);
            return true;
        }

        String text = readTextValue(data.path(KEY_TEXT));
        if (text == null) {
            // Member presence only; no payload content — DL-197 — see docs/DECISION_LOG.md
            log.warn("Skipping a stream record that carries no post text; members present: {}.",
                    presentMembers(payload));
            return true;
        }

        Integer likeCount = readInteger(data.path(KEY_PUBLIC_METRICS).path(KEY_LIKE_COUNT));
        LocalDateTime createdAt = readCreatedAt(data.path(KEY_CREATED_AT));
        String authorId = readTextValue(data.path(KEY_AUTHOR_ID));
        if (likeCount == null || createdAt == null || authorId == null) {
            List<String> invalidRequiredMembers = new ArrayList<>(3);
            if (likeCount == null) {
                invalidRequiredMembers.add(KEY_LIKE_COUNT);
            }
            if (createdAt == null) {
                invalidRequiredMembers.add(KEY_CREATED_AT);
            }
            if (authorId == null) {
                invalidRequiredMembers.add(KEY_AUTHOR_ID);
            }
            log.warn("Skipping a stream record whose required members are missing or invalid: {}.",
                    String.join(", ", invalidRequiredMembers));
            return true;
        }

        boolean popularEnough = (resolvedThreshold == null)
                ? twitterService.meetsPopularityThreshold(likeCount)
                : twitterService.meetsPopularityThreshold(likeCount, resolvedThreshold);
        if (!popularEnough) {
            log.debug("Skipping a stream record with like count {}: the popularity gate reports "
                    + "false.", likeCount);
            return true;
        }

        double doubtRating;
        try {
            double sentimentScore = sentimentAnalysisService.analyzeSentiment(text);
            doubtRating = sentimentAnalysisService.calculateDoubtRating(sentimentScore);
        } catch (RuntimeException failure) {
            // service/SentimentAnalysisService owns the failure record — see
            // docs/DECISION_LOG.md DL-197
            log.debug("Skipping a stream record: obtaining its doubt rating failed with {}.",
                    failure.getClass().getSimpleName());
            return true;
        }

        // Columns of backend/app/db/models.py:L10-18, in place of the id, text, user and sentiment
        // arguments at backend/app/tasks/tweet_monitoring.py:L22-28
        Tweet tweet = new Tweet();
        tweet.setContent(text);
        tweet.setLikeCount(likeCount);
        tweet.setCreatedAt(createdAt);
        tweet.setDoubtRating(doubtRating);
        tweet.setMedia(readTextArray(data.path(KEY_ATTACHMENTS).path(KEY_MEDIA_KEYS)));
        tweet.setQuotedTweetId(readQuotedTweetId(data.path(KEY_REFERENCED_TWEETS)));
        tweet.setUserId(authorId);
        tweet.setAiToolsMentioned(readRuleTags(payload.path(KEY_MATCHING_RULES)));

        // No de-duplication — see docs/DECISION_LOG.md DL-049
        Tweet saved = tweetRepository.save(tweet);
        log.info("Stored ingested tweet row {} with like count {}.", saved.getId(), likeCount);

        mirrorToNotion(saved);
        triggerResponseGeneration(saved);

        return true;
    }

    // Net-new: one threshold resolution per ingestion cycle — DL-255 — see docs/DECISION_LOG.md
    /**
     * Resolves the popularity threshold in force and returns it.
     *
     * <p>{@code task.TweetStreamClient} calls this once per connection cycle and hands the value to
     * {@link #onStatus(JsonNode, int)} for every record of that cycle, so the {@code settings} table
     * is read once per cycle — see docs/DECISION_LOG.md DL-255. The value is not retained by this
     * class.
     *
     * @return the threshold {@link TwitterService#popularityThresholdInForce()} reports
     */
    public int popularityThresholdInForce() {
        return twitterService.popularityThresholdInForce();
    }

    // Ported from store_tweet at backend/app/services/notion_service.py:L12-28 (faithful port) — see
    // docs/DECISION_LOG.md
    /**
     * Writes the secondary Notion mirror of a stored row.
     *
     * <p>The wire form handed to {@link NotionService#storeTweet(TweetDto)} is produced by
     * {@link TweetMapper#toDto(Tweet)}: the row's identifier is rendered as a string and its two
     * delimited columns as arrays.
     *
     * <p>The two steps report at different levels. A failure raised while the wire form is prepared
     * is recorded here at {@code WARN}; a failure raised by the mirror write itself is recorded here at
     * {@code DEBUG} and by {@link NotionService} at {@code ERROR} — see docs/DECISION_LOG.md DL-224.
     * Neither is rethrown and the stored row is unaffected either way.
     *
     * @param saved the stored row, never {@code null}
     */
    // Mirror-preparation failures are reported here — see docs/DECISION_LOG.md DL-224
    private void mirrorToNotion(Tweet saved) {
        TweetDto mirrored;
        try {
            mirrored = tweetMapper.toDto(saved);
        } catch (RuntimeException failure) {
            // The only report of this condition — see docs/DECISION_LOG.md DL-224
            log.warn("Preparing the Notion mirror of tweet row {} failed with {}; the row is stored "
                    + "and is not mirrored.", saved.getId(), failure.getClass().getSimpleName());
            return;
        }
        try {
            notionService.storeTweet(mirrored);
            log.debug("Mirrored tweet row {} to the Notion database.", saved.getId());
        } catch (RuntimeException failure) {
            // service/NotionService owns the failure record — see docs/DECISION_LOG.md DL-197
            log.debug("Mirroring tweet row {} to the Notion database failed with {}.",
                    saved.getId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Triggers response generation for a stored row and mirrors the generated reply to Notion.
     *
     * <p>{@link ResponseService#generateResponseIfAbsent(String)} is called in process with the
     * identifier the database assigned to the row. That call owns the language-model request, stores
     * the generated {@code responses} row and sets its approval flag; nothing here reads or writes that
     * flag. It is also the guard that keeps this trigger and the scheduled pass from both storing a
     * reply for one row — see docs/DECISION_LOG.md DL-195.
     *
     * <p>An empty result is recorded at {@code DEBUG}. A failure raised by the call is recorded at
     * {@code DEBUG} and is not rethrown; the stored row is unaffected. This method writes no
     * {@code ERROR} record; the layer that raised the failure writes the single one —
     * {@code service.LlmService} for a provider failure, {@code service.ResponseService} for a
     * repository or transaction failure — DL-252.
     *
     * @param saved the stored row, never {@code null}
     */
    private void triggerResponseGeneration(Tweet saved) {
        String tweetId = String.valueOf(saved.getId());
        ResponseDto generated;
        try {
            // Closes the unimplemented trigger at backend/app/tasks/tweet_monitoring.py:L32 — see
            // docs/DECISION_LOG.md
            // The single background generation entry point — DL-195 — see docs/DECISION_LOG.md
            Optional<ResponseDto> result = responseService.generateResponseIfAbsent(tweetId);
            if (result.isEmpty()) {
                log.debug("Response generation for tweet row {} stored nothing; the row already "
                        + "carries a response or one is being generated.", tweetId);
                return;
            }
            generated = result.get();
            log.info("Triggered response generation for tweet row {}; stored response {}.",
                    tweetId, generated.id());
        } catch (RuntimeException failure) {
            // The failing layer owns the ERROR record — DL-252 — see docs/DECISION_LOG.md
            log.debug("Triggering response generation for tweet row {} failed with {}; ingestion "
                    + "continues and the stored row carries no reply.",
                    tweetId, failure.getClass().getSimpleName());
            return;
        }
        mirrorGeneratedResponse(tweetId, generated);
    }

    /**
     * Writes the secondary Notion mirror of a generated reply.
     *
     * <p>This is the same mirror step {@code task/ResponseGenerationScheduler} performs after its own
     * generation, so a reply reaches the Notion {@code Response} property by whichever path generated
     * it. An absent reply is recorded at {@code WARN} and not mirrored; a reply that is present
     * carries content, since {@code dto/ResponseDto} rejects a {@code null} value for it — see
     * docs/DECISION_LOG.md DL-080.
     *
     * <p>A failure raised by the mirror write is recorded at {@code ERROR} and is not rethrown; the
     * stored reply is unaffected. The mirror is not re-attempted — see docs/DECISION_LOG.md DL-194.
     *
     * @param tweetId   identifier of the stored row the reply belongs to, never {@code null}
     * @param generated the stored reply, possibly {@code null}
     */
    // Implements the documented step "Update Notion database with response"
    // (documentation/Code Structure.md) on the ingestion path, closing the call to the absent
    // NotionService.update_tweet_response at backend/app/tasks/response_generation.py:L30 — see
    // docs/DECISION_LOG.md DL-194
    private void mirrorGeneratedResponse(String tweetId, ResponseDto generated) {
        if (generated == null) {
            log.warn("Generation for tweet row {} yielded no response; the Notion mirror is skipped.",
                    tweetId);
            return;
        }
        // dto/ResponseDto rejects a null content — DL-080 — see docs/DECISION_LOG.md
        String content = generated.content();
        try {
            notionService.updateTweetResponse(tweetId, content);
        } catch (RuntimeException failure) {
            // service/NotionService owns the failure record — see docs/DECISION_LOG.md DL-197
            log.debug("Mirroring response {} for tweet row {} to the Notion database failed with {}.",
                    generated.id(), tweetId, failure.getClass().getSimpleName());
        }
    }

    // Every stored row carries a creation time — see docs/DECISION_LOG.md DL-223
    /**
     * Reads the creation time of a delivered record.
     *
     * <p>An ISO-8601 value carrying an offset is read and normalised to UTC:
     * {@code 2026-08-03T15:11:52.000Z} yields {@code 2026-08-03T15:11:52}. An absent, blank,
     * wrong-typed or unparseable value yields {@code null}; {@link #onStatus(JsonNode)} then rejects
     * the record before persistence.
     *
     * <p>A parsed value more than {@value #CREATED_AT_FUTURE_TOLERANCE_MINUTES} minutes past the
     * arrival instant is treated as invalid — DL-247 — see docs/DECISION_LOG.md.
     *
     * @param node the {@code created_at} member, possibly a missing node
     * @return the creation time to store, or {@code null} when the member is missing, unparseable or
     *     stamped beyond the accepted tolerance
     */
    private static LocalDateTime readCreatedAt(JsonNode node) {
        String raw = readTextValue(node);
        if (raw == null) {
            return null;
        }
        LocalDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(raw)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
        // A timestamp beyond the arrival instant plus the tolerance is not stored — DL-247 — see
        // docs/DECISION_LOG.md
        return parsed.isAfter(LocalDateTime.now(ZoneOffset.UTC).plus(CREATED_AT_FUTURE_TOLERANCE))
                ? null
                : parsed;
    }

    /**
     * Reads the identifier of the quoted post a record references.
     *
     * <p>The first entry whose {@value #KEY_TYPE} is {@value #QUOTED_REFERENCE_TYPE} supplies the
     * value. A member that is not an array, an array holding no such entry, and an entry carrying no
     * identifier each yield {@code null}, the stored value of the nullable {@code quoted_tweet_id}
     * column.
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
            if (QUOTED_REFERENCE_TYPE.equals(readTextValue(reference.path(KEY_TYPE)))) {
                return readTextValue(reference.path(KEY_ID));
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
            String value = readTextValue(arrayNode.path(index));
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
            String tag = readTextValue(arrayNode.path(index).path(KEY_TAG));
            if (tag != null) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    /**
     * Reads one member as text.
     *
     * <p>Only a JSON string satisfies the contract. A missing node, a container node, a JSON
     * {@code null}, a number, a boolean and a blank string each yield {@code null}; a number or a
     * boolean is never coerced to text.
     *
     * @param node the member to read, possibly a missing node
     * @return the value the member holds, or {@code null} when it holds no non-blank string
     */
    private static String readTextValue(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return value.isBlank() ? null : value;
    }

    /**
     * Reads one member as a whole number.
     *
     * <p>Only an integral JSON number satisfies the contract. A missing node, a non-numeric value, a
     * fractional value and a value outside the range of an {@code int} each yield {@code null}, which
     * {@link TwitterService#meetsPopularityThreshold(Integer)} reads as a record that does not reach
     * the threshold. A fractional value is never truncated to a whole count.
     *
     * @param node the member to read, possibly a missing node
     * @return the value the member holds, or {@code null} when it holds no whole number
     */
    private static Integer readInteger(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return Integer.valueOf(node.intValue());
        }
        return null;
    }

    // Payload log redaction — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders which of the {@link #LOGGABLE_MEMBERS} a payload carries.
     *
     * <p>Only member names appear in the result. No member value, no identifier, no attachment key, no
     * rule tag and no rendered payload appears, whatever the payload holds. A member is reported as
     * carried when it is present and is not JSON {@code null}; the {@code text} member is looked up
     * inside {@code data}, matching where the record declares it.
     *
     * @param payload the payload to describe, never {@code null}
     * @return the carried member names in declaration order, or {@code none} when the payload carries
     *         no member of the allowlist
     */
    private static String presentMembers(JsonNode payload) {
        JsonNode data = payload.path(KEY_DATA);
        List<String> carried = new ArrayList<>();
        for (String member : LOGGABLE_MEMBERS) {
            JsonNode found = KEY_DATA.equals(member) ? data : data.path(member);
            if (found.isMissingNode() || found.isNull()) {
                found = payload.path(member);
            }
            if (!found.isMissingNode() && !found.isNull()) {
                carried.add(member);
            }
        }
        return carried.isEmpty() ? "none" : String.join(", ", carried);
    }
}
