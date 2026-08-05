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
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Net-new (no source construct that runs): backend/tests/test_tasks.py:L3 imported
// `from backend.tasks import monitor_tweets, generate_response`, and neither the module nor either
// symbol existed — see docs/DECISION_LOG.md
/**
 * Exercises {@link TweetStreamListener} over synthesized filtered-stream records.
 *
 * <p>Assertions cover the four documented steps, the shapes a delivered member must carry before it
 * reaches a stored column, the nullable creation time, and the two secondary Notion mirror writes —
 * the tweet before the trigger and the generated reply after it.
 */
@DisplayName("TweetStreamListener")
class TweetStreamListenerTest {

    /** Identifier the stubbed repository assigns to a stored row. */
    private static final int STORED_ID = 7;

    /** Sentiment score the stubbed analysis service reports. */
    private static final double SCORE = -0.4D;

    /** Doubt rating the stubbed analysis service derives. */
    private static final double DOUBT_RATING = 7.0D;

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
        @DisplayName("reads a fractional like count as absent instead of truncating it")
        void rejectsAFractionalLikeCount() {
            when(twitterService.meetsPopularityThreshold(null)).thenReturn(false);
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"public_metrics\":{\"like_count\":100.9}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(null);
            verify(tweetRepository, never()).save(any());
        }

        @Test
        @DisplayName("reads a like count carried as a string as absent")
        void rejectsAStringLikeCount() {
            when(twitterService.meetsPopularityThreshold(null)).thenReturn(false);
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"public_metrics\":{\"like_count\":\"500\"}}}");

            assertThat(listener.onStatus(record)).isTrue();

            verify(twitterService).meetsPopularityThreshold(null);
            verify(tweetRepository, never()).save(any());
        }

        @Test
        @DisplayName("offers an integral like count to the popularity gate")
        void acceptsAnIntegralLikeCount() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
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
                    + "\"public_metrics\":{\"like_count\":1}}}");

            assertThat(listener.onStatus(record)).isTrue();

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
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getCreatedAt())
                    .isEqualTo(LocalDateTime.of(2026, 8, 3, 15, 11, 52));
        }

        @Test
        @DisplayName("stores no creation time when the member is absent")
        void storesNoTimeWhenAbsent() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getCreatedAt()).isNull();
        }

        @Test
        @DisplayName("stores the current UTC time when the member is not ISO-8601")
        void storesNoTimeWhenUnparseable() {
            acceptEverything();
            LocalDateTime beforeTheCall = LocalDateTime.now(ZoneOffset.UTC);
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"created_at\":\"garbage\","
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getCreatedAt())
                    .isNotNull()
                    .isBetween(beforeTheCall, LocalDateTime.now(ZoneOffset.UTC));
        }

        @Test
        @DisplayName("stores no creation time when the member is a number")
        void storesNoTimeWhenNumeric() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"created_at\":1756900000,"
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getCreatedAt()).isNull();
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
            assertThat(stored.getDoubtRating()).isEqualTo(DOUBT_RATING);
            assertThat(stored.getMedia()).containsExactly("m1", "m2");
            assertThat(stored.getQuotedTweetId()).isEqualTo("99");
            assertThat(stored.getAiToolsMentioned()).containsExactly("GPT-4", "AI coding tool");
        }

        @Test
        @DisplayName("reads a numeric author_id as absent")
        void rejectsANumericAuthorId() {
            acceptEverything();
            JsonNode record = read("{\"data\":{\"text\":\"doubtful\",\"author_id\":4242,"
                    + "\"public_metrics\":{\"like_count\":500}}}");

            listener.onStatus(record);

            assertThat(storedTweet().getUserId()).isNull();
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
        return read("{\"data\":{\"text\":\"doubtful\",\"public_metrics\":{\"like_count\":500}}}");
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
                String.valueOf(STORED_ID), "doubtful", 500, null, DOUBT_RATING, List.of(), null,
                null, List.of()));
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
