package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

// Net-new (no source construct that runs): backend/tests/test_tasks.py:L3 imported
// `from backend.tasks import monitor_tweets, generate_response`, and neither the module nor either
// symbol existed — see docs/DECISION_LOG.md
/**
 * Exercises {@link ResponseGenerationScheduler}.
 *
 * <p>Assertions cover the pass itself: one reply generated and mirrored per candidate, the claim that
 * keeps a row another path already answered from being generated for again, the failure of one
 * candidate leaving the rest of the pass intact, and the candidate row being handed to
 * {@code ResponseService} as it was selected rather than read a second time — DL-226. The pacing of the
 * pass belongs to {@code config/AsyncSchedulingConfig} and is asserted by
 * {@code config/AsyncSchedulingConfigTest} — DL-227.
 */
@DisplayName("ResponseGenerationScheduler")
class ResponseGenerationSchedulerTest {

    /** Row identifier of the single candidate most tests use. */
    private static final int CANDIDATE_ID = 42;

    private final TweetRepository tweetRepository = mock(TweetRepository.class);

    private final ResponseService responseService = mock(ResponseService.class);

    private final NotionService notionService = mock(NotionService.class);

    private ResponseGenerationScheduler scheduler;

    @BeforeEach
    void createScheduler() {
        scheduler = new ResponseGenerationScheduler(tweetRepository, responseService, notionService);
    }

    @Nested
    @DisplayName("generation pass")
    class GenerationPass {

        @Test
        @DisplayName("generates and mirrors one reply per candidate")
        void generatesAndMirrorsEachCandidate() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate()));
            when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                    .thenReturn(Optional.of(reply("a draft reply")));

            scheduler.generatePendingResponses();

            ArgumentCaptor<Tweet> subject = ArgumentCaptor.forClass(Tweet.class);
            verify(responseService).generateResponseIfAbsent(subject.capture());
            assertThat(subject.getValue().getId()).isEqualTo(CANDIDATE_ID);
            verify(notionService)
                    .updateTweetResponse(String.valueOf(CANDIDATE_ID), "a draft reply");
        }

        @Test
        @DisplayName("mirrors nothing when the candidate already carried a reply")
        void mirrorsNothingWhenTheClaimIsLost() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate()));
            when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                    .thenReturn(Optional.empty());

            scheduler.generatePendingResponses();

            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        // dto/ResponseDto rejects a null content — AAP TR-6, DL-080 — see docs/DECISION_LOG.md
        @Test
        @DisplayName("cannot be handed a generated reply that carries no content")
        void cannotBeHandedAGeneratedReplyThatCarriesNoContent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> reply(null))
                    .withMessage("content must not be null.");

            verifyNoInteractions(notionService);
        }

        @Test
        @DisplayName("continues with the next candidate after a failure")
        void continuesAfterAFailure() {
            Tweet first = candidate();
            Tweet second = new Tweet();
            second.setId(43);
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
            when(responseService.generateResponseIfAbsent(first))
                    .thenThrow(new IllegalStateException("model down"));
            when(responseService.generateResponseIfAbsent(second))
                    .thenReturn(Optional.of(reply("second reply")));

            scheduler.generatePendingResponses();

            verify(notionService).updateTweetResponse("43", "second reply");
        }

        @Test
        @DisplayName("does nothing when no candidate awaits a reply")
        void doesNothingWithoutCandidates() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of());

            scheduler.generatePendingResponses();

            verifyNoInteractions(responseService, notionService);
        }

        @Test
        @DisplayName("returns normally when the candidate query fails")
        void survivesAFailingCandidateQuery() {
            when(tweetRepository.findByResponsesIsEmpty())
                    .thenThrow(new IllegalStateException("database down"));

            scheduler.generatePendingResponses();

            verifyNoInteractions(responseService, notionService);
        }

        // The candidate query is the pass's only read of the tweets table — DL-226 — see
        // docs/DECISION_LOG.md
        @Test
        @DisplayName("reads the tweets table once for the whole pass, however many candidates it "
                + "holds")
        void readsTheTweetsTableOnceForTheWholePass() {
            Tweet first = candidate();
            Tweet second = new Tweet();
            second.setId(43);
            Tweet third = new Tweet();
            third.setId(44);
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second, third));
            when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                    .thenReturn(Optional.of(reply("a draft reply")));

            scheduler.generatePendingResponses();

            verify(tweetRepository).findByResponsesIsEmpty();
            verify(tweetRepository, never()).findById(any());
            verifyNoMoreInteractions(tweetRepository);

            ArgumentCaptor<Tweet> subjects = ArgumentCaptor.forClass(Tweet.class);
            verify(responseService, times(3)).generateResponseIfAbsent(subjects.capture());
            assertThat(subjects.getAllValues())
                    .as("rows handed to the generator")
                    .containsExactly(first, second, third);
        }

        @Test
        @DisplayName("reads only the identifier of a candidate row")
        void readsOnlyTheIdentifier() {
            Tweet candidate = candidate();
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate));
            when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                    .thenReturn(Optional.of(reply("a draft reply")));

            scheduler.generatePendingResponses();

            assertThat(candidate.getResponses()).isEmpty();
            verify(tweetRepository, never()).save(any());
        }
    }

    private static Tweet candidate() {
        Tweet candidate = new Tweet();
        candidate.setId(CANDIDATE_ID);
        return candidate;
    }

    private static ResponseDto reply(String content) {
        return new ResponseDto("11", content, LocalDateTime.now(ZoneOffset.UTC), Boolean.FALSE,
                String.valueOf(CANDIDATE_ID));
    }
}
