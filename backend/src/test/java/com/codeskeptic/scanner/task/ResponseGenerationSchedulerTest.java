package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

// Net-new (retired test antecedent: backend/tests/test_tasks.py). Unit under test: a faithful port of
// schedule_response_generation() at backend/app/tasks/response_generation.py:L35-50, absorbing the
// task body at :L10-33 — see docs/DECISION_LOG.md DL-047, DL-227
/**
 * Exercises {@link ResponseGenerationScheduler}.
 *
 * <p>Assertions cover the scheduling surface the pass presents — one public no-argument method
 * carrying exactly one {@code @Scheduled} declaration, whose fixed delay names
 * {@code scanner.response-generation-delay-seconds} in seconds — together with the four collaborators
 * its single constructor binds.
 *
 * <p>Assertions then cover the pass itself: one reply generated and mirrored per candidate, in
 * candidate order and on the thread that started the pass; each reply mirrored against the candidate
 * it was generated for; a candidate another path already answered storing and mirroring nothing; a
 * failing candidate, a failing candidate query and a rejected mirror each leaving the pass returning
 * normally; and the candidate row handed on as it was selected.
 *
 * <p>The interval between passes is declared on the pass itself and asserted here; the scheduling
 * capability that runs it is activated by {@code config/AsyncSchedulingConfig} — see
 * docs/DECISION_LOG.md DL-047, DL-227.
 *
 * <p>Every collaborator is a Mockito double. No Spring context is started, no scheduler thread is
 * created, no network call is made and no database is reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseGenerationScheduler")
class ResponseGenerationSchedulerTest {
    private static final String PASS_METHOD_NAME = "generatePendingResponses";

    private static final int FIRST_CANDIDATE_ID = 42;

    private static final int SECOND_CANDIDATE_ID = 43;

    private static final int THIRD_CANDIDATE_ID = 44;

    private static final String FIRST_CANDIDATE_ID_TEXT = "42";

    private static final String SECOND_CANDIDATE_ID_TEXT = "43";

    private static final String FIRST_REPLY_ID = "11";

    private static final String SECOND_REPLY_ID = "12";

    private static final String FIRST_REPLY_TEXT = "a first draft reply";

    private static final String SECOND_REPLY_TEXT = "a second draft reply";

    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    /** Candidate ceiling no case of this class reaches — DL-282. */
    private static final int UNREACHABLE_CEILING = 100_000;

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseService responseService;

    @Mock
    private NotionService notionService;

    private ResponseGenerationScheduler scheduler;

    /**
     * Assembles the pass over its three doubles and a bound configuration.
     *
     * <p>The configuration carries a candidate ceiling no case of this class reaches — DL-282.
     */
    @BeforeEach
    void assemblePass() {
        scheduler = new ResponseGenerationScheduler(tweetRepository, responseService, notionService,
                boundWith(UNREACHABLE_CEILING));
    }

    private static ScannerProperties boundWith(int candidateCeiling) {
        return new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null,
                new ScannerProperties.Background(true, true, true, candidateCeiling));
    }

    @Test
    @DisplayName("presents one public no-argument method as the pass a scheduler runs")
    void presentsOnePublicNoArgumentPass() {
        List<Method> entryPoints = Arrays.stream(ResponseGenerationScheduler.class
                        .getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .toList();

        assertThat(entryPoints)
                .as("public no-argument methods")
                .singleElement()
                .satisfies(pass -> {
                    assertThat(pass.getName()).isEqualTo(PASS_METHOD_NAME);
                    assertThat(pass.getReturnType()).isEqualTo(void.class);
                    assertThat(pass.getExceptionTypes()).isEmpty();
                });
    }

    // TR-12 and IR10: fixed DELAY, measured end-to-start, never fixedRate — DL-047 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("declares its pacing as one fixed delay in seconds naming the configured property")
    void declaresItsPacingAsOneFixedDelayInSeconds() throws NoSuchMethodException {
        Scheduled declared = ResponseGenerationScheduler.class
                .getDeclaredMethod(PASS_METHOD_NAME)
                .getAnnotation(Scheduled.class);

        assertThat(declared).as("@Scheduled on the pass").isNotNull();
        assertThat(declared.fixedDelayString())
                .as("the fixed delay in force")
                .isEqualTo("${scanner.response-generation-delay-seconds}");
        assertThat(declared.timeUnit()).as("unit of the declared delay").isEqualTo(TimeUnit.SECONDS);

        assertThat(declared.fixedRateString()).as("fixedRateString").isEmpty();
        assertThat(declared.fixedRate()).as("fixedRate").isEqualTo(-1L);
        assertThat(declared.cron()).as("cron").isEmpty();
        assertThat(declared.initialDelayString()).as("initialDelayString").isEmpty();
        assertThat(declared.initialDelay()).as("initialDelay").isEqualTo(-1L);
    }

    @Test
    @DisplayName("carries exactly one scheduled method and no asynchronous or enabling annotation")
    void carriesExactlyOneScheduledMethodAndNoEnablingAnnotation() {
        assertThat(ResponseGenerationScheduler.class.getDeclaredMethods())
                .as("methods carrying their own interval, rate or schedule")
                .filteredOn(method -> method.isAnnotationPresent(Scheduled.class)
                        || method.isAnnotationPresent(Schedules.class))
                .extracting(Method::getName)
                .containsExactly(PASS_METHOD_NAME);

        assertThat(ResponseGenerationScheduler.class.getDeclaredMethods())
                .as("methods declared asynchronous")
                .noneMatch(method -> method.isAnnotationPresent(Async.class));

        assertThat(ResponseGenerationScheduler.class.getAnnotations())
                .as("annotations on the type")
                .extracting(annotation -> annotation.annotationType().getName())
                .doesNotContain(EnableScheduling.class.getName(), EnableAsync.class.getName());
    }

    @Test
    @DisplayName("binds exactly the four collaborators one pass uses")
    void bindsExactlyTheFourCollaboratorsOnePassUses() {
        Constructor<?>[] constructors = ResponseGenerationScheduler.class.getDeclaredConstructors();

        assertThat(constructors).as("declared constructors").hasSize(1);

        Class<?>[] parameterTypes = constructors[0].getParameterTypes();
        assertThat(parameterTypes)
                .as("collaborators bound by the constructor")
                .containsExactly(TweetRepository.class, ResponseService.class, NotionService.class,
                        ScannerProperties.class);
        assertThat(parameterTypes)
                .extracting(Class::getSimpleName)
                .doesNotContain("LlmService");
    }

    @Test
    @DisplayName("rejects a missing collaborator, naming the one that is absent")
    void rejectsAMissingCollaborator() {
        ScannerProperties bound = boundWith(UNREACHABLE_CEILING);

        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(null, responseService,
                        notionService, bound))
                .withMessageContaining("tweetRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, null,
                        notionService, bound))
                .withMessageContaining("responseService");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        null, bound))
                .withMessageContaining("notionService");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        notionService, null))
                .withMessageContaining("properties");
    }

    // The pass runs only in a process that carries it — DL-250 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "enabled={0} responseGenerationEnabled={1}")
    @CsvSource({
        "false,true",
        "true,false",
        "false,false"
    })
    @DisplayName("reads no candidate in a process that does not carry the pass")
    void readsNoCandidateInAProcessThatDoesNotCarryThePass(boolean enabled,
            boolean responseGenerationEnabled) {
        ScannerProperties withheld = new ScannerProperties(null, 100, 60L, null, null, null, null,
                null, null, null,
                new ScannerProperties.Background(enabled, true, responseGenerationEnabled,
                        UNREACHABLE_CEILING));

        new ResponseGenerationScheduler(tweetRepository, responseService, notionService, withheld)
                .generatePendingResponses();

        verifyNoInteractions(tweetRepository, responseService, notionService);
    }

    // An unbound background group carries the pass, which is the declared default of both keys —
    // DL-250 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("runs the pass when the background group is unbound")
    void runsThePassWhenTheBackgroundGroupIsUnbound() {
        ScannerProperties unbound =
                new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null, null);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        new ResponseGenerationScheduler(tweetRepository, responseService, notionService, unbound)
                .generatePendingResponses();

        verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        verifyNoInteractions(responseService, notionService);
    }

    @Test
    @DisplayName("generates and mirrors one reply for each candidate, in candidate order")
    void generatesAndMirrorsOneReplyForEachCandidateInOrder() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        when(responseService.generateResponseIfAbsentFor(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        InOrder pass = inOrder(tweetRepository, responseService, notionService);
        pass.verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        pass.verify(responseService).generateResponseIfAbsentFor(first);
        pass.verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        pass.verify(responseService).generateResponseIfAbsentFor(second);
        pass.verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        pass.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("mirrors each generated reply against the candidate it was generated for")
    void mirrorsEachGeneratedReplyAgainstItsOwnCandidate() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        when(responseService.generateResponseIfAbsentFor(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        ArgumentCaptor<String> mirroredIdentifiers = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mirroredText = ArgumentCaptor.forClass(String.class);
        verify(notionService, times(2))
                .updateTweetResponse(mirroredIdentifiers.capture(), mirroredText.capture());

        assertThat(pairsOf(mirroredIdentifiers.getAllValues(), mirroredText.getAllValues()))
                .as("identifier and reply text of each mirror call")
                .containsExactly(FIRST_CANDIDATE_ID_TEXT + "=" + FIRST_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT + "=" + SECOND_REPLY_TEXT);
    }

    @Test
    @DisplayName("runs every collaborator call on the thread that started the pass")
    void runsEveryCollaboratorCallOnTheThreadThatStartedThePass() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        AtomicReference<Thread> generatingThread = new AtomicReference<>();
        AtomicReference<Thread> mirroringThread = new AtomicReference<>();
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class))).thenAnswer(invocation -> {
            generatingThread.set(Thread.currentThread());
            return Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT, FIRST_CANDIDATE_ID_TEXT));
        });
        doAnswer(invocation -> {
            mirroringThread.set(Thread.currentThread());
            return null;
        }).when(notionService).updateTweetResponse(anyString(), anyString());

        Thread caller = Thread.currentThread();
        scheduler.generatePendingResponses();

        assertThat(generatingThread.get()).as("thread the reply was generated on").isSameAs(caller);
        assertThat(mirroringThread.get()).as("thread the reply was mirrored on").isSameAs(caller);

        verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        verify(responseService).generateResponseIfAbsentFor(only);
        verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        verifyNoMoreInteractions(tweetRepository, responseService, notionService);
    }

    @ParameterizedTest(name = "is_approved = {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("reaches the same collaborators whatever the approval flag of a reply holds")
    void reachesTheSameCollaboratorsWhateverTheApprovalFlagHolds(boolean approved) {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT, approved)));
        when(responseService.generateResponseIfAbsentFor(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT, approved)));

        scheduler.generatePendingResponses();

        InOrder pass = inOrder(tweetRepository, responseService, notionService);
        pass.verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        pass.verify(responseService).generateResponseIfAbsentFor(first);
        pass.verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        pass.verify(responseService).generateResponseIfAbsentFor(second);
        pass.verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        pass.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("does nothing when no candidate awaits a reply")
    void doesNothingWhenNoCandidateAwaitsAReply() {
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of());

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verifyNoInteractions(responseService, notionService);
    }

    @Test
    @DisplayName("mirrors nothing when the candidate already carried a reply")
    void mirrorsNothingWhenTheCandidateAlreadyCarriedAReply() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.empty());

        scheduler.generatePendingResponses();

        verify(responseService).generateResponseIfAbsentFor(only);
        verifyNoInteractions(notionService);
    }

    @Test
    @DisplayName("continues with the next candidate after one candidate fails")
    void continuesWithTheNextCandidateAfterOneCandidateFails() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(first))
                .thenThrow(new IllegalStateException("generation refused"));
        when(responseService.generateResponseIfAbsentFor(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verify(responseService).generateResponseIfAbsentFor(second);
        verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        verifyNoMoreInteractions(notionService);
    }

    @Test
    @DisplayName("returns normally when the candidate query fails")
    void returnsNormallyWhenTheCandidateQueryFails() {
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("candidate query refused"));

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verifyNoInteractions(responseService, notionService);
    }

    @Test
    @DisplayName("returns normally when mirroring a stored reply is rejected")
    void returnsNormallyWhenMirroringAStoredReplyIsRejected() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        doThrow(new IllegalStateException("mirror refused"))
                .when(notionService).updateTweetResponse(anyString(), anyString());

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        verify(tweetRepository, never()).save(any());
        verifyNoMoreInteractions(notionService);
    }

    // The pass counts a stored reply whose mirror Notion refused separately — DL-253 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("counts a stored reply whose mirror was rejected as stored without a mirror")
    void countsAStoredReplyWhoseMirrorWasRejectedAsStoredWithoutAMirror() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        when(responseService.generateResponseIfAbsentFor(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));
        doThrow(new IllegalStateException("mirror refused"))
                .when(notionService)
                .updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .isEqualTo("Response generation pass finished: 2 attempted, 2 succeeded "
                            + "(1 stored without a Notion mirror), 0 skipped, 0 failed over "
                            + "1 batch(es)");
        } finally {
            detachAppender(recorded);
        }

        verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
    }

    // The pass counts a stored reply whose mirror landed separately — DL-253
    @Test
    @DisplayName("counts no reply as stored without a mirror when every mirror lands")
    void countsNoReplyAsStoredWithoutAMirrorWhenEveryMirrorLands() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .isEqualTo("Response generation pass finished: 1 attempted, 1 succeeded "
                            + "(0 stored without a Notion mirror), 0 skipped, 0 failed over "
                            + "1 batch(es)");
        } finally {
            detachAppender(recorded);
        }
    }

    // A row another path answered is neither stored nor mirrored — DL-195, DL-253
    @Test
    @DisplayName("counts a row another path answered as skipped and not as stored")
    void countsARowAnotherPathAnsweredAsSkippedAndNotAsStored() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class))).thenReturn(Optional.empty());

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .isEqualTo("Response generation pass finished: 1 attempted, 0 succeeded "
                            + "(0 stored without a Notion mirror), 1 skipped, 0 failed over "
                            + "1 batch(es)");
        } finally {
            detachAppender(recorded);
        }

        verifyNoInteractions(notionService);
    }

    // dto/ResponseDto rejects a null content — DL-080 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("cannot be handed a generated reply that carries no content")
    void cannotBeHandedAGeneratedReplyThatCarriesNoContent() {
        assertThatNullPointerException()
                .isThrownBy(() -> reply(FIRST_REPLY_ID, null, FIRST_CANDIDATE_ID_TEXT))
                .withMessage("content must not be null.");

        verifyNoInteractions(tweetRepository, responseService, notionService);
    }

    // One pass drains the whole backlog in consecutive bounded batches — DL-248 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("drains a backlog larger than one batch within a single pass, advancing the cursor")
    void drainsABacklogLargerThanOneBatchWithinASinglePass() {
        int batchRows = declaredBatchBound();
        List<Tweet> firstBatch = candidatesNumbered(1, batchRows);
        List<Tweet> secondBatch = candidatesNumbered(batchRows + 1, 3);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(firstBatch);
        when(tweetRepository.findUnansweredBatchAfter(eq(batchRows), any(Pageable.class)))
                .thenReturn(secondBatch);
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        verify(responseService, times(batchRows + secondBatch.size()))
                .generateResponseIfAbsentFor(any(Tweet.class));
        ArgumentCaptor<Pageable> batchRequests = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Integer> cursors = ArgumentCaptor.forClass(Integer.class);
        verify(tweetRepository, times(2))
                .findUnansweredBatchAfter(cursors.capture(), batchRequests.capture());
        assertThat(cursors.getAllValues()).as("cursor each batch was read from")
                .containsExactly(null, batchRows);
        assertThat(batchRequests.getAllValues()).as("row bound each batch asked for")
                .allSatisfy(request -> {
                    assertThat(request.getPageSize()).isEqualTo(batchRows);
                    assertThat(request.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
                });
    }

    // A batch that comes back short ends the sweep — DL-248 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("ends the sweep at the batch that comes back short of its row bound")
    void endsTheSweepAtTheBatchThatComesBackShortOfItsRowBound() {
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(candidate(FIRST_CANDIDATE_ID)));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        verify(tweetRepository, never())
                .findUnansweredBatchAfter(eq(FIRST_CANDIDATE_ID), any(Pageable.class));
    }

    // One statement per candidate batch, and no per-candidate read — DL-226, DL-248 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("reads the tweets table once per candidate batch and never per candidate")
    void readsTheTweetsTableOncePerCandidateBatchAndNeverPerCandidate() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        Tweet third = candidate(THIRD_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(first, second, third));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        verify(tweetRepository).findUnansweredBatchAfter(isNull(), any(Pageable.class));
        verifyNoMoreInteractions(tweetRepository);

        ArgumentCaptor<Tweet> handedOn = ArgumentCaptor.forClass(Tweet.class);
        verify(responseService, times(3)).generateResponseIfAbsentFor(handedOn.capture());
        assertThat(handedOn.getAllValues())
                .as("rows handed to the generator")
                .containsExactly(first, second, third);
    }

    @Test
    @DisplayName("leaves the responses collection of a candidate row untouched")
    void leavesTheResponsesCollectionOfACandidateRowUntouched() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class))).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        assertThat(only.getResponses()).as("responses collection of the candidate row").isEmpty();
        verify(tweetRepository, never()).save(any());
    }

    private static List<Tweet> candidatesNumbered(int firstIdentifier, int rows) {
        List<Tweet> built = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            built.add(candidate(firstIdentifier + row));
        }
        return List.copyOf(built);
    }

    private static int declaredBatchBound() {
        try {
            Field bound = ResponseGenerationScheduler.class
                    .getDeclaredField("CANDIDATE_BATCH_ROWS");
            bound.setAccessible(true);
            return (int) bound.get(null);
        } catch (ReflectiveOperationException absent) {
            throw new AssertionError(
                    "ResponseGenerationScheduler must declare CANDIDATE_BATCH_ROWS.", absent);
        }
    }

    private static Tweet candidate(int id) {
        Tweet candidate = new Tweet();
        candidate.setId(id);
        return candidate;
    }

    private static ResponseDto reply(String id, String content, String tweetId) {
        return reply(id, content, tweetId, false);
    }

    private static ResponseDto reply(String id, String content, String tweetId, boolean approved) {
        return new ResponseDto(id, content, GENERATED_AT, approved, tweetId);
    }

    private static List<String> pairsOf(List<String> identifiers, List<String> text) {
        List<String> pairs = new ArrayList<>(identifiers.size());
        for (int index = 0; index < identifiers.size(); index++) {
            pairs.add(identifiers.get(index) + "=" + text.get(index));
        }
        return pairs;
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ResponseGenerationScheduler.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ResponseGenerationScheduler.class))
                .detachAppender(appender);
    }

    private static String passSummary(ListAppender<ILoggingEvent> appender) {
        List<String> summaries = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Response generation pass finished:"))
                .toList();

        assertThat(summaries).hasSize(1);
        return summaries.get(0);
    }
}
