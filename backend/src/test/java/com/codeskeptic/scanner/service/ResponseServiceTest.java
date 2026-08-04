package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;

// Verifies the port of backend/app/api/responses.py:L38-49 (400/201/500) — see
// docs/DECISION_LOG.md DL-076, DL-177, DL-178
/**
 * Exercises the {@code POST /responses} path of {@link ResponseService#generateResponse(String)}:
 * the row it stores on success, and the single failure it reports.
 *
 * <p>Every collaborator is a Mockito double, so no Spring context is started and no network,
 * database, filesystem or credential resource is reached. {@link LlmService} is never called for
 * real.
 *
 * <p>Construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseService")
class ResponseServiceTest {

    /** Identifier of the {@code tweets} row every test replies to. */
    private static final String TWEET_ID = "4711";

    /** Identifier the stored {@code responses} row carries. */
    private static final String RESPONSE_ID = "88";

    /** Numeric form of {@link #TWEET_ID}, the value the repository is queried with. */
    private static final long TWEET_KEY = 4711L;

    /** Text {@link LlmService} returns for an accepted generation. */
    private static final String GENERATED_TEXT = "Even seasoned reviewers disagree.";

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private LlmService llmService;

    @Mock
    private ResponseMapper responseMapper;

    @Mock
    private TweetMapper tweetMapper;

    private ResponseService service;

    @BeforeEach
    void setUp() {
        service = new ResponseService(responseRepository, tweetRepository, llmService,
                responseMapper, tweetMapper, directTransactionTemplate());
    }

    @Test
    @DisplayName("stores the generated text as an unapproved row and returns the stored row")
    void storesTheGeneratedTextAsAnUnapprovedRowAndReturnsTheStoredRow() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            saved.setId(Long.valueOf(RESPONSE_ID));
            return saved;
        });
        when(responseMapper.toDto(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            return new ResponseDto(String.valueOf(saved.getId()), saved.getContent(),
                    saved.getGeneratedAt(), saved.getIsApproved(), TWEET_ID);
        });

        LocalDateTime beforeTheCall = LocalDateTime.now();
        ResponseDto stored = service.generateResponse(TWEET_ID);
        LocalDateTime afterTheCall = LocalDateTime.now();

        assertThat(stored.id()).isEqualTo(RESPONSE_ID);
        assertThat(stored.content()).isEqualTo(GENERATED_TEXT);
        assertThat(stored.isApproved()).isFalse();
        assertThat(stored.tweetId()).isEqualTo(TWEET_ID);
        assertThat(stored.generatedAt()).isBetween(beforeTheCall, afterTheCall);
    }

    @Test
    @DisplayName("associates the stored row with the loaded tweet and sets no identifier of its own")
    void associatesTheStoredRowWithTheLoadedTweetAndSetsNoIdentifierOfItsOwn() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            assertThat(saved.getTweet()).isSameAs(subject);
            assertThat(saved.getContent()).isEqualTo(GENERATED_TEXT);
            assertThat(saved.getIsApproved()).isFalse();
            assertThat(saved.getGeneratedAt()).isNotNull();
            saved.setId(Long.valueOf(RESPONSE_ID));
            return saved;
        });
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        assertThat(service.generateResponse(TWEET_ID)).isEqualTo(storedDto());

        verify(responseRepository).save(any(Response.class));
    }

    @Test
    @DisplayName("reports the approved 500 literal and stores nothing when generation fails")
    void reportsTheApproved500LiteralAndStoresNothingWhenGenerationFails() {
        Tweet subject = tweetCarryingTheKey();
        IllegalStateException unusableCompletion =
                new IllegalStateException("The Chat Completions response carried blank generated text.");
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenThrow(unusableCompletion);

        Throwable thrown = catchThrowable(() -> service.generateResponse(TWEET_ID));

        assertThat(thrown).isInstanceOf(ResponseGenerationException.class);
        assertThat(thrown).hasMessage(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE);
        assertThat(thrown.getCause()).isSameAs(unusableCompletion);
        assertThat(thrown.getMessage()).doesNotContain("Chat Completions");
        verify(responseRepository, never()).save(any(Response.class));
    }

    /**
     * Builds the {@code tweets} row every test replies to.
     *
     * @return an entity carrying {@link #TWEET_KEY} as its identifier
     */
    private static Tweet tweetCarryingTheKey() {
        Tweet tweet = new Tweet();
        tweet.setId(TWEET_KEY);
        tweet.setContent("Nobody reviews what the assistant writes.");
        return tweet;
    }

    /**
     * Builds the wire form of the replied-to post.
     *
     * @return a DTO carrying {@link #TWEET_ID} as its identifier
     */
    private static TweetDto tweetDto() {
        return new TweetDto(TWEET_ID, "Nobody reviews what the assistant writes.", 250,
                LocalDateTime.of(2026, 1, 31, 9, 15), 7.5d, List.of(), null, "99",
                List.of("GitHub Copilot"));
    }

    /**
     * Builds the wire form of the stored {@code responses} row.
     *
     * @return a DTO carrying {@link #RESPONSE_ID} as its identifier
     */
    private static ResponseDto storedDto() {
        return new ResponseDto(RESPONSE_ID, GENERATED_TEXT,
                LocalDateTime.of(2026, 1, 31, 9, 16), false, TWEET_ID);
    }

    /**
     * Builds a {@link TransactionTemplate} that runs its callback inline against a transaction manager
     * which starts, commits and rolls back nothing.
     *
     * <p>{@code ResponseService} performs its insert inside an injected template so the blocking model
     * call sits outside any transaction. This fixture keeps that structure observable in a plain
     * Mockito test without a persistence context.
     *
     * @return a template that executes its callback on the calling thread
     */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // No transaction is started, so there is nothing to commit.
            }

            @Override
            public void rollback(TransactionStatus status) {
                // No transaction is started, so there is nothing to roll back.
            }
        });
    }
}
