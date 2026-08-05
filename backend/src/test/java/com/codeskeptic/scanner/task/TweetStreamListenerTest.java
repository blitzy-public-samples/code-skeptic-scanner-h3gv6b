package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import com.fasterxml.jackson.databind.ObjectMapper;

// Net-new (no source construct that runs): backend/tests/test_tasks.py:L3 imported
// `from backend.tasks import monitor_tweets, generate_response`, and neither the module nor either
// symbol existed — see docs/DECISION_LOG.md
/**
 * Exercises {@link TweetStreamListener} over synthesized filtered-stream records.
 *
 * <p>Assertions cover the four documented steps, the shapes every wire-required member must carry
 * before a row is stored, and the two secondary Notion mirror writes — the tweet before the trigger
 * and the generated reply after it.
 */
@DisplayName("TweetStreamListener")
class TweetStreamListenerTest {

    /** Identifier the stubbed repository assigns to a stored row. */
    private static final int STORED_ID = 7;

    /** Sentiment score the stubbed analysis service reports. */
    private static final double SCORE = -0.4D;

    /** Doubt rating the stubbed analysis service derives. */
    private static final double DOUBT_RATING = 7.0D;

    /** Valid stream creation time used by records not testing that member. */
    private static final String CREATED_AT = "2026-08-03T15:11:52.000Z";

    /** Stored UTC-local form of {@link #CREATED_AT}. */
    private static final LocalDateTime STORED_CREATED_AT =
            LocalDateTime.of(2026, 8, 3, 15, 11, 52);

    /** Valid author identifier used by records not testing that member. */
    private static final String AUTHOR_ID = "4242";

    /** Reads a synthesized record into a tree. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TwitterService twitterService = mock(TwitterService.class);

    private final SentimentAnalysisService sentimentAnalysisService =
            mock(SentimentAnalysisService.class);

    private final TweetRepository tweetRepository = mock(TweetRepository.class);

    private final ResponseService responseService = mock(ResponseService.class);

    private final NotionService notionService = mock(NotionService.class);

    private final TweetMapper tweetMapper = mock(TweetMapper.class);

    private TweetStreamListener listener;

    @BeforeEach
    void createListener() {
        listener = new TweetStreamListener(twitterService, sentimentAnalysisService, tweetRepository,
                responseService, notionService, tweetMapper);
    }

    @Nested
    @DisplayName("payload validation")
    class PayloadValidation {

        @Test
        @DisplayName("stores nothing for a null payload")
        void ignoresANullPayload() {
            assertThat(listener.onStatus(null)).isTrue();
            verifyNoInteractions(tweetRepository, twitterService, sentimentAnalysisService);
        }

        @Test
        @DisplayName("stores nothing when the text member is a boolean")
        void rejectsANonTextualText() {
            JsonNode record = read("{\"data\":{\"text\":true,"
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(twitterService);
        }

        @Test
        @DisplayName("stores nothing when the text member is a number")
        void rejectsANumericText() {
            JsonNode record = read("{\"data\":{\"text\":1234,"
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips a fractional required like count instead of truncating it")
        void rejectsAFractionalLikeCount() {
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":100.9}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(twitterService, sentimentAnalysisService);
        }

        @Test
        @DisplayName("skips a record whose required like count is absent")
        void rejectsAnAbsentLikeCount() {
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\"}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(twitterService, sentimentAnalysisService);
        }

        @Test
        @DisplayName("skips a required like count carried as a string")
        void rejectsAStringLikeCount() {
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":\"500\"}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(twitterService, sentimentAnalysisService);
        }

        @Test
        @DisplayName("offers an integral like count to the popularity gate")
        void acceptsAnIntegralLikeCount() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":100}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(100);
            assertThat(storedTweet().getLikeCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("stores nothing when the popularity gate reports false")
        void honoursThePopularityGate() {
            when(twitterService.meetsPopularityThreshold(any())).thenReturn(false);
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":1}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(1);
            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }
    }

    @Nested
    @DisplayName("creation time")
    class CreationTime {

        @Test
        @DisplayName("normalises an offset-bearing ISO-8601 value to UTC")
        void normalisesAnIsoValue() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"2026-08-03T17:11:52.000+02:00\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getCreatedAt())
                    .isEqualTo(LocalDateTime.of(2026, 8, 3, 15, 11, 52));
        }

        @Test
        @DisplayName("skips the record when the required creation time is absent")
        void skipsTheRecordWhenCreationTimeIsAbsent() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }

        @Test
        @DisplayName("skips the record when the required creation time is not ISO-8601")
        void skipsTheRecordWhenCreationTimeIsUnparseable() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"created_at\":\"garbage\","
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }

        @Test
        @DisplayName("skips the record when the required creation time is numeric")
        void skipsTheRecordWhenCreationTimeIsNumeric() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"created_at\":1756900000,"
                    + "\"author_id\":\"" + AUTHOR_ID + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }
    }

    @Nested
    @DisplayName("stored columns")
    class StoredColumns {

        @Test
        @DisplayName("maps every delivered member onto its column")
        void mapsEveryMember() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"GPT-4 doubts\",\"author_id\":\"4242\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"public_metrics\":{\"like_count\":500},"
                    + "\"attachments\":{\"media_keys\":[\"m1\",\"m2\"]},"
                    + "\"referenced_tweets\":[{\"type\":\"replied_to\",\"id\":\"1\"},"
                    + "{\"type\":\"quoted\",\"id\":\"99\"}]},"
                    + "\"matching_rules\":[{\"id\":\"r1\",\"tag\":\"GPT-4\"},"
                    + "{\"id\":\"r2\",\"tag\":\"AI coding tool\"}]}");

            listener.onStatus(record);

            Tweet stored = storedTweet();
            assertThat(stored.getContent()).isEqualTo("GPT-4 doubts");
            assertThat(stored.getUserId()).isEqualTo("4242");
            assertThat(stored.getCreatedAt()).isEqualTo(STORED_CREATED_AT);
            assertThat(stored.getDoubtRating()).isEqualTo(DOUBT_RATING);
            assertThat(stored.getMedia()).containsExactly("m1", "m2");
            assertThat(stored.getQuotedTweetId()).isEqualTo("99");
            assertThat(stored.getAiToolsMentioned()).containsExactly("GPT-4", "AI coding tool");
        }

        @Test
        @DisplayName("skips the record when the required author_id is numeric")
        void rejectsANumericAuthorId() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"author_id\":4242,"
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }

        @Test
        @DisplayName("skips the record when the required author_id is absent")
        void rejectsAnAbsentAuthorId() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"created_at\":\"" + CREATED_AT + "\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(tweetRepository, never()).save(any());
            verifyNoInteractions(sentimentAnalysisService, notionService, responseService);
        }

        @Test
        @DisplayName("maps every stored ingested row through the real tweet mapper")
        void mapsEveryStoredIngestedRowThroughTheRealTweetMapper() {
            acceptEverything();
            TweetStreamListener listenerWithRealMapper =
                    new TweetStreamListener(twitterService, sentimentAnalysisService, tweetRepository,
                            responseService, notionService, new TweetMapper());

            assertThat(listenerWithRealMapper.onStatus(popularRecord())).isTrue();

            ArgumentCaptor<TweetDto> mirrored = ArgumentCaptor.forClass(TweetDto.class);
            verify(notionService).storeTweet(mirrored.capture());
            assertThat(mirrored.getValue().id()).isEqualTo(String.valueOf(STORED_ID));
            assertThat(mirrored.getValue().content()).isEqualTo("doubtful");
            assertThat(mirrored.getValue().likeCount()).isEqualTo(500);
            assertThat(mirrored.getValue().createdAt()).isEqualTo(STORED_CREATED_AT);
            assertThat(mirrored.getValue().doubtRating()).isEqualTo(DOUBT_RATING);
            assertThat(mirrored.getValue().userId()).isEqualTo(AUTHOR_ID);
        }
    }

    @Nested
    @DisplayName("Notion mirrors")
    class NotionMirrors {

        @Test
        @DisplayName("mirrors the tweet and then the generated reply")
        void mirrorsBothTheTweetAndTheReply() {
            acceptEverything();

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(notionService).storeTweet(any(TweetDto.class));
            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            verify(notionService).updateTweetResponse(String.valueOf(STORED_ID), "a draft reply");
        }

        @Test
        @DisplayName("still triggers generation when the tweet mirror fails")
        void keepsGoingWhenTheTweetMirrorFails() {
            acceptEverything();
            when(notionService.storeTweet(any())).thenThrow(new IllegalStateException("mirror down"));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(responseService).generateResponseIfAbsent(String.valueOf(STORED_ID));
            verify(notionService).updateTweetResponse(String.valueOf(STORED_ID), "a draft reply");
        }

        @Test
        @DisplayName("mirrors no reply when generation fails")
        void mirrorsNoReplyWhenGenerationFails() {
            acceptEverything();
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenThrow(new IllegalStateException("model down"));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("mirrors no reply when generation yields no content")
        void mirrorsNoReplyWithoutContent() {
            acceptEverything();
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenReturn(Optional.of(new ResponseDto("11", null, LocalDateTime.now(),
                            Boolean.FALSE, String.valueOf(STORED_ID))));

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("mirrors no reply when the row already carried one")
        void mirrorsNoReplyWhenTheClaimIsLost() {
            acceptEverything();
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenReturn(Optional.empty());

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("leaves the stored rows in place when the reply mirror fails")
        void keepsRowsWhenTheReplyMirrorFails() {
            acceptEverything();
            org.mockito.Mockito.doThrow(new IllegalStateException("mirror down"))
                    .when(notionService).updateTweetResponse(anyString(), anyString());

            assertThat(listener.onStatus(popularRecord())).isTrue();

            verify(tweetRepository).save(any(Tweet.class));
        }
    }

    private JsonNode popularRecord() {
        return read("{\"data\":{\"text\":\"doubtful\","
                + "\"created_at\":\"" + CREATED_AT + "\","
                + "\"author_id\":\"" + AUTHOR_ID + "\","
                + "\"public_metrics\":{\"like_count\":500}}}");
    }

    private void acceptEverything() {
        when(twitterService.meetsPopularityThreshold(any())).thenReturn(true);
        when(sentimentAnalysisService.analyzeSentiment(anyString())).thenReturn(SCORE);
        when(sentimentAnalysisService.calculateDoubtRating(anyDouble())).thenReturn(DOUBT_RATING);
        when(tweetRepository.save(any(Tweet.class))).thenAnswer(invocation -> {
            Tweet candidate = invocation.getArgument(0);
            candidate.setId(STORED_ID);
            return candidate;
        });
        when(tweetMapper.toDto(any(Tweet.class))).thenReturn(new TweetDto(
                String.valueOf(STORED_ID), "doubtful", 500, STORED_CREATED_AT, DOUBT_RATING,
                List.of(), null, AUTHOR_ID, List.of()));
        when(notionService.storeTweet(any(TweetDto.class))).thenReturn("notion-page-id");
        when(responseService.generateResponseIfAbsent(eq(String.valueOf(STORED_ID))))
                .thenReturn(Optional.of(new ResponseDto("11", "a draft reply", LocalDateTime.now(),
                        Boolean.FALSE, String.valueOf(STORED_ID))));
    }

    private Tweet storedTweet() {
        ArgumentCaptor<Tweet> captor = ArgumentCaptor.forClass(Tweet.class);
        verify(tweetRepository).save(captor.capture());
        return captor.getValue();
    }

    private static JsonNode read(String record) {
        try {
            return JSON.readTree(record);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unparseable) {
            throw new IllegalStateException("The synthesized record does not parse.", unparseable);
        }
    }
}
