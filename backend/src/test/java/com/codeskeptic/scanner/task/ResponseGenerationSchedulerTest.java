package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TriggerContext;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

// Net-new (no source construct that runs): backend/tests/test_tasks.py:L3 imported
// `from backend.tasks import monitor_tweets, generate_response`, and neither the module nor either
// symbol existed — see docs/DECISION_LOG.md
/**
 * Exercises {@link ResponseGenerationScheduler}.
 *
 * <p>Assertions cover the pacing contract — the interval resolved from the
 * {@code response_generation_delay} setting row in preference to
 * {@code scanner.response-generation-delay-seconds}, measured from the end of the previous pass — and
 * the pass itself, including the claim that keeps a row that already carries a reply from being
 * generated for again.
 */
@DisplayName("ResponseGenerationScheduler")
class ResponseGenerationSchedulerTest {

    /** Row identifier of the single candidate most tests use. */
    private static final int CANDIDATE_ID = 42;

    /** {@code settings} row carrying the interval. */
    private static final String DELAY_KEY = "response_generation_delay";

    /** Interval {@code scanner.response-generation-delay-seconds} carries in these tests. */
    private static final long CONFIGURED_DELAY = 60L;

    private final TweetRepository tweetRepository = mock(TweetRepository.class);

    private final ResponseService responseService = mock(ResponseService.class);

    private final NotionService notionService = mock(NotionService.class);

    private final SettingRepository settingRepository = mock(SettingRepository.class);

    private ResponseGenerationScheduler scheduler;

    @BeforeEach
    void createScheduler() {
        scheduler = new ResponseGenerationScheduler(tweetRepository, responseService, notionService,
                settingRepository, properties(CONFIGURED_DELAY));
    }

    @Nested
    @DisplayName("generation pass")
    class GenerationPass {

        @Test
        @DisplayName("generates and mirrors one reply per candidate")
        void generatesAndMirrorsEachCandidate() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate()));
            when(responseService.generateResponseIfAbsent(String.valueOf(CANDIDATE_ID)))
                    .thenReturn(Optional.of(reply("a draft reply")));

            scheduler.generatePendingResponses();

            verify(responseService).generateResponseIfAbsent(String.valueOf(CANDIDATE_ID));
            verify(notionService)
                    .updateTweetResponse(String.valueOf(CANDIDATE_ID), "a draft reply");
        }

        @Test
        @DisplayName("mirrors nothing when the candidate already carried a reply")
        void mirrorsNothingWhenTheClaimIsLost() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate()));
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenReturn(Optional.empty());

            scheduler.generatePendingResponses();

            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("mirrors nothing when the generated reply carries no content")
        void mirrorsNothingWithoutContent() {
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate()));
            when(responseService.generateResponseIfAbsent(anyString()))
                    .thenReturn(Optional.of(reply(null)));

            scheduler.generatePendingResponses();

            verify(notionService, never()).updateTweetResponse(anyString(), anyString());
        }

        @Test
        @DisplayName("continues with the next candidate after a failure")
        void continuesAfterAFailure() {
            Tweet first = candidate();
            Tweet second = new Tweet();
            second.setId(43);
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
            when(responseService.generateResponseIfAbsent("42"))
                    .thenThrow(new IllegalStateException("model down"));
            when(responseService.generateResponseIfAbsent("43"))
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

        @Test
        @DisplayName("reads only the identifier of a candidate row")
        void readsOnlyTheIdentifier() {
            Tweet candidate = candidate();
            when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(candidate));
            when(responseService.generateResponseIfAbsent(anyString()))
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

    private static ScannerProperties properties(long delaySeconds) {
        return new ScannerProperties("jdbc:h2:mem:unused", 100, delaySeconds, null, null, null, null,
                null, null, null);
    }

    private static TriggerContext context(Instant lastCompletion, Instant now) {
        return new TriggerContext() {

            @Override
            public Clock getClock() {
                return Clock.fixed(now, ZoneOffset.UTC);
            }

            @Override
            public Instant lastScheduledExecution() {
                return lastCompletion;
            }

            @Override
            public Instant lastActualExecution() {
                return lastCompletion;
            }

            @Override
            public Instant lastCompletion() {
                return lastCompletion;
            }
        };
    }
}
