package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Retired test antecedent: backend/tests/test_tasks.py, whose :L3 named a module and two symbols
// that existed nowhere in the source tree, and whose :L7 and :L12 fixtures patched two further
// unresolvable roots — see docs/DECISION_LOG.md
// Unit under test: the TweetListener of backend/app/tasks/tweet_monitoring.py:L8-34 (faithful port
// of intent), whose on_status at :L15-34 left persistence unimplemented at :L29 and the generation
// trigger unimplemented at :L32 — see docs/DECISION_LOG.md DL-049
/**
 * Exercises {@link TweetStreamListener#onStatus(JsonNode)} over synthesized filtered-stream records.
 *
 * <p>Collaborators are Mockito mocks under {@code Strictness.STRICT_STUBS}. No application context is
 * started, no outbound request is issued, no database is reached and no credential is read.
 *
 * <p>Assertions cover the four ordered steps, the shapes every wire-required member must carry before
 * a row is stored, the record-to-column mapping, the identifier handed to the generation trigger, and
 * the two secondary Notion mirror writes with the level each failure is reported at.
 *
 * <p>The doubt rating is obtained from {@link SentimentAnalysisService#calculateDoubtRating(double)}
 * and this class asserts the value that collaborator reports; the expression producing it is asserted
 * by {@code service/SentimentAnalysisServiceTest}.
 *
 * <p>Decisions covering the behaviour asserted here are recorded in {@code docs/DECISION_LOG.md};
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TweetStreamListener")
class TweetStreamListenerTest {
    private static final int STORED_ID = 7;

    private static final String DELIVERED_POST_ID = "1111111111";

    private static final int POPULAR_LIKE_COUNT = 500;

    private static final String POST_TEXT = "doubtful";

    private static final double SENTIMENT_SCORE = -0.6D;

    private static final double REPORTED_DOUBT_RATING = 7.25D;

    private static final String CREATED_AT = "2026-08-03T15:11:52.000Z";

    private static final LocalDateTime STORED_CREATED_AT =
            LocalDateTime.of(2026, 8, 3, 15, 11, 52);

    private static final String AUTHOR_ID = "4242";

    private static final String GENERATED_ID = "11";

    private static final String GENERATED_CONTENT = "a draft reply";

    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 3, 15, 12, 0);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private TwitterService twitterService;

    @Mock
    private SentimentAnalysisService sentimentAnalysisService;

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseService responseService;

    @Mock
    private NotionService notionService;

    @Mock
    private TweetMapper tweetMapper;

    private TweetStreamListener listener;

    @BeforeEach
    void assembleListener() {
        listener = new TweetStreamListener(twitterService, sentimentAnalysisService, tweetRepository,
                responseService, notionService, tweetMapper);
    }

    @Nested
    @DisplayName("record validation")
    class RecordValidation {
        @Test
        @DisplayName("returns true and stores nothing for a null payload")
        void returnsTrueAndStoresNothingForANullPayload() {
            assertThat(listener.onStatus(null)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("returns true and stores nothing when the record carries no data object")
        void returnsTrueAndStoresNothingWhenTheRecordCarriesNoDataObject() {
            JsonNode record = read("{\"matching_rules\":[{\"id\":\"r1\",\"tag\":\"GPT-4\"}]}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the text member is a boolean")
        void storesNothingWhenTheTextMemberIsABoolean() {
            JsonNode record = read("{\"data\":{\"text\":true,"
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the text member is a number")
        void storesNothingWhenTheTextMemberIsANumber() {
            JsonNode record = read("{\"data\":{\"text\":1234,"
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the text member holds only whitespace")
        void storesNothingWhenTheTextMemberHoldsOnlyWhitespace() {
            JsonNode record = read("{\"data\":{\"text\":\"   \","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required like count is fractional")
        void storesNothingWhenTheRequiredLikeCountIsFractional() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":100.9}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required like count is absent")
        void storesNothingWhenTheRequiredLikeCountIsAbsent() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\"}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required like count is carried as a string")
        void storesNothingWhenTheRequiredLikeCountIsCarriedAsAString() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":\"500\"}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required author_id is a number")
        void storesNothingWhenTheRequiredAuthorIdIsANumber() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":4242,"
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required author_id is absent")
        void storesNothingWhenTheRequiredAuthorIdIsAbsent() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required author_id holds only whitespace")
        void storesNothingWhenTheRequiredAuthorIdHoldsOnlyWhitespace() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"   \","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        // Every stored row carries a creation time — see docs/DECISION_LOG.md DL-223
        @Test
        @DisplayName("stores nothing when the required creation time is absent")
        void storesNothingWhenTheRequiredCreationTimeIsAbsent() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required creation time is not ISO-8601")
        void storesNothingWhenTheRequiredCreationTimeIsNotIso8601() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"garbage\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        @Test
        @DisplayName("stores nothing when the required creation time is a number")
        void storesNothingWhenTheRequiredCreationTimeIsANumber() {
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":1756900000,"
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        // A timestamp beyond the arrival instant is not stored — DL-247 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("stores nothing when the creation time is stamped beyond the accepted "
                + "tolerance")
        void storesNothingWhenTheCreationTimeIsStampedBeyondTheAcceptedTolerance() {
            JsonNode record = recordCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

            assertThat(listener.onStatus(record)).isTrue();

            verifyNoCollaboratorWasReached();
        }

        // A timestamp inside the tolerance is stored — DL-247 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("stores a record whose creation time is inside the accepted tolerance")
        void storesARecordWhoseCreationTimeIsInsideTheAcceptedTolerance() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            OffsetDateTime stamped = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);

            assertThat(listener.onStatus(recordCreatedAt(stamped))).isTrue();

            assertThat(storedRow().getCreatedAt()).isEqualTo(stamped.toLocalDateTime());
        }
    }

    // The gate reads the threshold at backend/app/services/twitter_service.py:L43 and compares
    // inclusively at :L46; the source called an absent meets_popularity_threshold at
    // backend/app/tasks/tweet_monitoring.py:L17 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("popularity gate")
    class PopularityGate {
        @Test
        @DisplayName("offers an integral like count to the popularity gate and stores it")
        void offersAnIntegralLikeCountToThePopularityGateAndStoresIt() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":100}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(100);
            assertThat(storedRow().getLikeCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("returns true and stores nothing when the popularity gate reports false")
        void returnsTrueAndStoresNothingWhenThePopularityGateReportsFalse() {
            stubGateRejects();
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":1}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(1);
            verifyNoMoreInteractions(twitterService);
            verifyNoInteractions(sentimentAnalysisService, tweetRepository, responseService,
                    notionService, tweetMapper);
        }
    }

    // The four steps of on_status at backend/app/tasks/tweet_monitoring.py:L15-34 — see
    // docs/DECISION_LOG.md
    @Nested
    @DisplayName("ingestion pipeline")
    class IngestionPipeline {
        @Test
        @DisplayName("evaluates the gate, then the sentiment, then stores the row")
        void evaluatesTheGateThenTheSentimentThenStoresTheRow() {
            stubGateAccepts();
            stubSentiment();
            stubSave();

            assertThat(listener.onStatus(popularRecord())).isTrue();

            InOrder pipeline = inOrder(twitterService, sentimentAnalysisService, tweetRepository);
            pipeline.verify(twitterService).meetsPopularityThreshold(POPULAR_LIKE_COUNT);
            pipeline.verify(sentimentAnalysisService).analyzeSentiment(POST_TEXT);
            pipeline.verify(sentimentAnalysisService).calculateDoubtRating(SENTIMENT_SCORE);
            pipeline.verify(tweetRepository).save(any(Tweet.class));
            pipeline.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("stores the doubt rating the analysis service reports for the forwarded score")
        void storesTheDoubtRatingTheAnalysisServiceReportsForTheForwardedScore() {
            stubGateAccepts();
            stubSentiment();
            stubSave();

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(sentimentAnalysisService).analyzeSentiment(POST_TEXT);
            verify(sentimentAnalysisService).calculateDoubtRating(SENTIMENT_SCORE);
            assertThat(storedRow().getDoubtRating()).isEqualTo(REPORTED_DOUBT_RATING);
        }

        @Test
        @DisplayName("returns true and stores nothing when the doubt rating cannot be obtained")
        void returnsTrueAndStoresNothingWhenTheDoubtRatingCannotBeObtained() {
            stubGateAccepts();
            when(sentimentAnalysisService.analyzeSentiment(anyString()))
                    .thenThrow(new IllegalStateException("analysis unavailable"));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verifyNoInteractions(tweetRepository, responseService, notionService, tweetMapper);
        }

        // Ingestion matches no delivered identifier against the table — see docs/DECISION_LOG.md
        // DL-049
        @Test
        @DisplayName("stores twice when the same record arrives twice")
        void storesTwiceWhenTheSameRecordArrivesTwice() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            JsonNode record = popularRecord();

            assertThat(listener.onStatus(record)).isTrue();
            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, times(2)).save(any(Tweet.class));
        }
    }

    // The columns of backend/app/db/models.py:L10-18, in place of the id, text, user and sentiment
    // arguments at backend/app/tasks/tweet_monitoring.py:L22-28 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("stored columns")
    class StoredColumns {
        @Test
        @DisplayName("maps every delivered member onto its column")
        void mapsEveryDeliveredMemberOntoItsColumn() {
            stubGateAccepts();
            stubSentiment();
            stubSave();

            assertThat(listener.onStatus(fullRecord())).isTrue();

            Tweet stored = storedRow();
            assertThat(stored.getContent()).isEqualTo("GPT-4 doubts");
            assertThat(stored.getLikeCount()).isEqualTo(POPULAR_LIKE_COUNT);
            assertThat(stored.getCreatedAt()).isEqualTo(STORED_CREATED_AT);
            assertThat(stored.getDoubtRating()).isEqualTo(REPORTED_DOUBT_RATING);
            assertThat(stored.getUserId()).isEqualTo(AUTHOR_ID);
            assertThat(stored.getMedia()).containsExactly("media-1", "media-2");
            assertThat(stored.getQuotedTweetId()).isEqualTo("99");
            assertThat(stored.getAiToolsMentioned()).containsExactly("GPT-4", "AI coding tool");
        }

        @Test
        @DisplayName("stores no quoted identifier when the only reference is a reply")
        void storesNoQuotedIdentifierWhenTheOnlyReferenceIsAReply() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "},"
                    + "\"referenced_tweets\":[{\"type\":\"replied_to\",\"id\":\"1\"}]}}");

            assertThat(listener.onStatus(record)).isTrue();

            assertThat(storedRow().getQuotedTweetId()).isNull();
        }

        @Test
        @DisplayName("reads the rule tags from the root of the record")
        void readsTheRuleTagsFromTheRootOfTheRecord() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "},"
                    + "\"matching_rules\":[{\"id\":\"r9\",\"tag\":\"nested\"}]},"
                    + "\"matching_rules\":[{\"id\":\"r1\",\"tag\":\"GPT-4\"}]}");

            assertThat(listener.onStatus(record)).isTrue();

            assertThat(storedRow().getAiToolsMentioned()).containsExactly("GPT-4");
        }

        @Test
        @DisplayName("stores empty column values when the record declares no optional member")
        void storesEmptyColumnValuesWhenTheRecordDeclaresNoOptionalMember() {
            stubGateAccepts();
            stubSentiment();
            stubSave();

            assertThat(listener.onStatus(popularRecord())).isTrue();

            Tweet stored = storedRow();
            assertThat(stored.getMedia()).isEmpty();
            assertThat(stored.getAiToolsMentioned()).isEmpty();
            assertThat(stored.getQuotedTweetId()).isNull();
            assertThat(stored.getContent()).isEqualTo(POST_TEXT);
        }

        @Test
        @DisplayName("touches no response of the stored row")
        void touchesNoResponseOfTheStoredRow() {
            stubGateAccepts();
            stubSentiment();
            stubSave();

            assertThat(listener.onStatus(fullRecord())).isTrue();

            assertThat(storedRow().getResponses()).isEmpty();
        }

        // The absent to_dict() at backend/app/api/tweets.py:L19 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("mirrors the stored row through the real tweet mapper")
        void mirrorsTheStoredRowThroughTheRealTweetMapper() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            TweetStreamListener listenerWithRealMapper = new TweetStreamListener(twitterService,
                    sentimentAnalysisService, tweetRepository, responseService, notionService,
                    new TweetMapper());

            assertThat(listenerWithRealMapper.onStatus(popularRecord())).isTrue();

            ArgumentCaptor<TweetDto> mirrored = ArgumentCaptor.forClass(TweetDto.class);
            verify(notionService).storeTweet(mirrored.capture());
            TweetDto wireForm = mirrored.getValue();
            assertThat(wireForm.id()).isEqualTo(String.valueOf(STORED_ID));
            assertThat(wireForm.content()).isEqualTo(POST_TEXT);
            assertThat(wireForm.likeCount()).isEqualTo(POPULAR_LIKE_COUNT);
            assertThat(wireForm.createdAt()).isEqualTo(STORED_CREATED_AT);
            assertThat(wireForm.doubtRating()).isEqualTo(REPORTED_DOUBT_RATING);
            assertThat(wireForm.userId()).isEqualTo(AUTHOR_ID);
            assertThat(wireForm.media()).isEmpty();
            assertThat(wireForm.aiToolsMentioned()).isEmpty();
            assertThat(wireForm.quotedTweetId()).isNull();
        }
    }

    // Closes the unimplemented persistence at backend/app/tasks/tweet_monitoring.py:L29 and the
    // unimplemented generation trigger at :L32 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("response generation trigger")
    class ResponseGenerationTrigger {
        @Test
        @DisplayName("stores the row and triggers generation with the identifier assigned to it")
        void storesTheRowAndTriggersGenerationWithTheIdentifierAssignedToIt() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubGeneration(false);

            assertThat(listener.onStatus(fullRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            verify(responseService, never()).generateResponseIfAbsent(DELIVERED_POST_ID);
            verifyNoMoreInteractions(responseService);
        }

        @Test
        @DisplayName("reaches the popularity gate and no other Twitter operation")
        void reachesThePopularityGateAndNoOtherTwitterOperation() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubGeneration(false);

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(twitterService).meetsPopularityThreshold(POPULAR_LIKE_COUNT);
            verifyNoMoreInteractions(twitterService);
        }

        // The approval flag of backend/app/db/models.py:L26 is read by a human reviewer — IR7 — see
        // docs/DECISION_LOG.md
        @ParameterizedTest(name = "is_approved={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("performs the same interactions whichever approval flag the reply carries")
        void performsTheSameInteractionsWhicheverApprovalFlagTheReplyCarries(boolean approved) {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubWireForm();
            stubGeneration(approved);

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(twitterService).meetsPopularityThreshold(POPULAR_LIKE_COUNT);
            verify(sentimentAnalysisService).analyzeSentiment(POST_TEXT);
            verify(sentimentAnalysisService).calculateDoubtRating(SENTIMENT_SCORE);
            verify(tweetRepository).save(any(Tweet.class));
            verify(tweetMapper).toDto(any(Tweet.class));
            verify(notionService).storeTweet(any(TweetDto.class));
            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            verify(notionService).updateTweetResponse(String.valueOf(STORED_ID), GENERATED_CONTENT);
            verifyNoMoreInteractions(twitterService, sentimentAnalysisService, tweetRepository,
                    tweetMapper, notionService, responseService);
        }
    }

    // Ported from store_tweet at backend/app/services/notion_service.py:L12-28 and from the call to
    // the absent NotionService.update_tweet_response at
    // backend/app/tasks/response_generation.py:L30 — see docs/DECISION_LOG.md DL-194
    @Nested
    @DisplayName("Notion mirrors")
    class NotionMirrors {
        @Test
        @DisplayName("mirrors the stored row, then triggers generation, then mirrors the reply")
        void mirrorsTheStoredRowThenTriggersGenerationThenMirrorsTheReply() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubWireForm();
            stubGeneration(false);

            assertThat(listener.onStatus(popularRecord())).isTrue();

            InOrder mirrors = inOrder(notionService, responseService);
            mirrors.verify(notionService).storeTweet(any(TweetDto.class));
            mirrors.verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            mirrors.verify(notionService)
                    .updateTweetResponse(String.valueOf(STORED_ID), GENERATED_CONTENT);
            mirrors.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("keeps the stored row and triggers generation when the row mirror fails")
        void keepsTheStoredRowAndTriggersGenerationWhenTheRowMirrorFails() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubWireForm();
            stubGeneration(false);
            when(notionService.storeTweet(any(TweetDto.class)))
                    .thenThrow(new IllegalStateException("the mirror is unavailable"));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            verify(notionService).updateTweetResponse(String.valueOf(STORED_ID), GENERATED_CONTENT);
        }

        @Test
        @DisplayName("keeps the stored row and mirrors no reply when generation fails")
        void keepsTheStoredRowAndMirrorsNoReplyWhenGenerationFails() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenThrow(new IllegalStateException("the model is unavailable"));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        // The failing layer owns the ERROR record — DL-252 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("records a failed generation trigger at DEBUG and never at ERROR")
        void recordsAFailedGenerationTriggerAtDebugAndNeverAtError() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenThrow(new IllegalStateException("the model is unavailable"));

            ListAppender<ILoggingEvent> recorded = attachRecordingAppender();
            try {
                assertThat(listener.onStatus(popularRecord())).isTrue();

                assertThat(recorded.list).noneMatch(event -> event.getLevel() == Level.ERROR);
                assertThat(recorded.list)
                        .filteredOn(event -> event.getLevel() == Level.DEBUG
                                && event.getFormattedMessage()
                                        .contains("Triggering response generation for tweet row"))
                        .hasSize(1)
                        .allSatisfy(event -> assertThat(event.getFormattedMessage())
                                .contains("IllegalStateException")
                                .contains("ingestion continues")
                                .doesNotContain("the model is unavailable"));
            } finally {
                detachRecordingAppender(recorded);
            }
        }

        @Test
        @DisplayName("mirrors no reply when the stored row already carried one")
        void mirrorsNoReplyWhenTheStoredRowAlreadyCarriedOne() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            when(responseService.generateResponseIfAbsent(anyString())).thenReturn(Optional.empty());

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("keeps the stored row and the stored reply when the reply mirror fails")
        void keepsTheStoredRowAndTheStoredReplyWhenTheReplyMirrorFails() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubGeneration(false);
            doThrow(new IllegalStateException("the mirror is unavailable"))
                    .when(notionService).updateTweetResponse(anyString(), anyString());

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(notionService).updateTweetResponse(String.valueOf(STORED_ID), GENERATED_CONTENT);
        }

        // dto/ResponseDto rejects a null content — AAP TR-6, DL-080 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("cannot be handed a generated reply that carries no content")
        void cannotBeHandedAGeneratedReplyThatCarriesNoContent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ResponseDto(GENERATED_ID, null, GENERATED_AT,
                            Boolean.FALSE, String.valueOf(STORED_ID)))
                    .withMessage("content must not be null.");

            verifyNoInteractions(notionService);
        }

        // Mirror-preparation failures are reported here — see docs/DECISION_LOG.md DL-224
        @Test
        @DisplayName("names a failed wire-form preparation at WARN and still triggers generation")
        void namesAFailedWireFormPreparationAtWarnAndStillTriggersGeneration() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            when(tweetMapper.toDto(any(Tweet.class))).thenThrow(new IllegalStateException(
                    "A tweets row carrying no user_id has no wire form."));
            ListAppender<ILoggingEvent> recorded = attachRecordingAppender();
            try {
                assertThat(listener.onStatus(popularRecord())).isTrue();

                List<String> warnings = recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("Preparing the Notion mirror"))
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.get(0))
                        .contains(String.valueOf(STORED_ID))
                        .contains("IllegalStateException")
                        .doesNotContain("user_id");
            } finally {
                detachRecordingAppender(recorded);
            }
            verify(notionService, never()).storeTweet(any(TweetDto.class));
            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
        }

        // service/NotionService owns the adapter failure record — see docs/DECISION_LOG.md DL-224
        @Test
        @DisplayName("names a failed row mirror below WARN")
        void namesAFailedRowMirrorBelowWarn() {
            stubGateAccepts();
            stubSentiment();
            stubSave();
            stubWireForm();
            when(notionService.storeTweet(any(TweetDto.class)))
                    .thenThrow(new IllegalStateException("the mirror is unavailable"));
            ListAppender<ILoggingEvent> recorded = attachRecordingAppender();
            try {
                assertThat(listener.onStatus(popularRecord())).isTrue();

                assertThat(recorded.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("Notion"))
                        .toList()).isEmpty();
                assertThat(recorded.list.stream()
                        .filter(event -> event.getLevel() == Level.DEBUG)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("Mirroring tweet row"))
                        .toList()).hasSize(1);
            } finally {
                detachRecordingAppender(recorded);
            }
        }
    }

    @Test
    @DisplayName("evaluates the gate against a supplied threshold and resolves none itself")
    void evaluatesTheGateAgainstASuppliedThresholdAndResolvesNoneItself() {
        when(twitterService.meetsPopularityThreshold(any(), anyInt())).thenReturn(true);
        stubSentiment();
        stubSave();
        JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                + "\"created_at\":\"" + CREATED_AT + "\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");

        assertThat(listener.onStatus(record, 250)).isTrue();

        verify(twitterService).meetsPopularityThreshold(POPULAR_LIKE_COUNT, 250);
        verify(twitterService, never()).meetsPopularityThreshold(any());
        verify(twitterService, never()).popularityThresholdInForce();
    }

    @Test
    @DisplayName("stores nothing when a supplied threshold rejects the like count")
    void storesNothingWhenASuppliedThresholdRejectsTheLikeCount() {
        when(twitterService.meetsPopularityThreshold(any(), anyInt())).thenReturn(false);
        JsonNode record = read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                + "\"created_at\":\"" + CREATED_AT + "\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"public_metrics\":{\"like_count\":1}}}");

        assertThat(listener.onStatus(record, 250)).isTrue();

        verify(twitterService).meetsPopularityThreshold(1, 250);
        verify(tweetRepository, never()).save(any(Tweet.class));
    }

    @Test
    @DisplayName("resolves no threshold for a record the payload checks reject")
    void resolvesNoThresholdForARecordThePayloadChecksReject() {
        assertThat(listener.onStatus(read("{}"), 250)).isTrue();

        verifyNoInteractions(twitterService);
    }

    @Test
    @DisplayName("reports the threshold in force from the twitter service")
    void reportsTheThresholdInForceFromTheTwitterService() {
        when(twitterService.popularityThresholdInForce()).thenReturn(321);

        assertThat(listener.popularityThresholdInForce()).isEqualTo(321);

        verify(twitterService).popularityThresholdInForce();
    }

    private void stubGateAccepts() {
        when(twitterService.meetsPopularityThreshold(any())).thenReturn(true);
    }

    private void stubGateRejects() {
        when(twitterService.meetsPopularityThreshold(any())).thenReturn(false);
    }

    private void stubSentiment() {
        when(sentimentAnalysisService.analyzeSentiment(anyString())).thenReturn(SENTIMENT_SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(SENTIMENT_SCORE))
                .thenReturn(REPORTED_DOUBT_RATING);
    }

    private void stubSave() {
        when(tweetRepository.save(any(Tweet.class))).thenAnswer(invocation -> {
            Tweet candidate = invocation.getArgument(0);
            candidate.setId(STORED_ID);
            return candidate;
        });
    }

    private void stubWireForm() {
        when(tweetMapper.toDto(any(Tweet.class))).thenReturn(new TweetDto(
                String.valueOf(STORED_ID), POST_TEXT, POPULAR_LIKE_COUNT, STORED_CREATED_AT,
                REPORTED_DOUBT_RATING, List.of(), null, AUTHOR_ID, List.of()));
    }

    private void stubGeneration(boolean approved) {
        when(responseService.generateResponseIfAbsent(String.valueOf(STORED_ID)))
                .thenReturn(Optional.of(new ResponseDto(GENERATED_ID, GENERATED_CONTENT,
                        GENERATED_AT, approved, String.valueOf(STORED_ID))));
    }

    private Tweet storedRow() {
        ArgumentCaptor<Tweet> captor = ArgumentCaptor.forClass(Tweet.class);
        verify(tweetRepository).save(captor.capture());
        return captor.getValue();
    }

    private void verifyNoCollaboratorWasReached() {
        verifyNoInteractions(twitterService, sentimentAnalysisService, tweetRepository,
                responseService, notionService, tweetMapper);
    }

    private static JsonNode recordCreatedAt(OffsetDateTime stamped) {
        return read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                + "\"created_at\":\"" + stamped.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + "\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");
    }

    private static JsonNode popularRecord() {
        return read("{\"data\":{\"text\":\"" + POST_TEXT + "\","
                + "\"created_at\":\"" + CREATED_AT + "\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "}}}");
    }

    private static JsonNode fullRecord() {
        return read("{\"data\":{\"id\":\"" + DELIVERED_POST_ID + "\","
                + "\"text\":\"GPT-4 doubts\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"created_at\":\"" + CREATED_AT + "\","
                + "\"public_metrics\":{\"like_count\":" + POPULAR_LIKE_COUNT + "},"
                + "\"attachments\":{\"media_keys\":[\"media-1\",\"media-2\"]},"
                + "\"referenced_tweets\":[{\"type\":\"replied_to\",\"id\":\"1\"},"
                + "{\"type\":\"quoted\",\"id\":\"99\"}]},"
                + "\"matching_rules\":[{\"id\":\"r1\",\"tag\":\"GPT-4\"},"
                + "{\"id\":\"r2\",\"tag\":\"AI coding tool\"}]}");
    }

    private static JsonNode read(String record) {
        try {
            return JSON.readTree(record);
        } catch (JsonProcessingException unparseable) {
            throw new IllegalStateException("The synthesized record does not parse.", unparseable);
        }
    }

    private static ListAppender<ILoggingEvent> attachRecordingAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TweetStreamListener.class);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachRecordingAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(TweetStreamListener.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
        appender.stop();
    }
}
