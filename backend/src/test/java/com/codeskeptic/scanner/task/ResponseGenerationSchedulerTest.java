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
import static org.mockito.Mockito.mock;
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
import java.util.Map;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.entity.Tweet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

// Net-new (retired test antecedent: backend/tests/test_tasks.py). Unit under test: a faithful port of
// schedule_response_generation() at backend/app/tasks/response_generation.py:L35-50, absorbing the
// task body at :L10-33 — see docs/DECISION_LOG.md DL-047, DL-227
/**
 * Exercises {@link ResponseGenerationScheduler}.
 *
 * <p>Assertions cover the scheduling surface the pass presents — the public no-argument pass, the
 * public interval resolver the registered trigger consults, and the absence of any pacing annotation
 * on the class, since the pacing lives on the registration in {@code config/AsyncSchedulingConfig} —
 * together with the five collaborators its single constructor binds.
 *
 * <p>Assertions then cover the pass itself: one reply generated and mirrored per candidate, in
 * candidate order and on the thread that started the pass; each reply mirrored against the candidate
 * it was generated for; a candidate another path already answered storing and mirroring nothing; a
 * failing candidate, a failing candidate query and a rejected mirror each leaving the pass returning
 * normally; and the candidate row handed on as it was selected.
 *
 * <p>Assertions then cover the interval itself: the {@code response_generation_delay} {@code settings}
 * row in force when it holds a positive whole number of seconds,
 * {@code scanner.response-generation-delay-seconds} when the row is absent, holds {@code null}, does
 * not parse or is not positive, and one warning per distinct unusable value — see docs/DECISION_LOG.md
 * DL-227, DL-309. The scheduling capability that runs the pass, and the trigger that consults this
 * interval, are both declared by {@code config/AsyncSchedulingConfig} — DL-047, DL-228, DL-309.
 *
 * <p>Every collaborator is a Mockito double. No Spring context is started, no scheduler thread is
 * created, no network call is made and no database is reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseGenerationScheduler")
class ResponseGenerationSchedulerTest {
    private static final String PASS_METHOD_NAME = "generatePendingResponses";

    private static final String DELAY_METHOD_NAME = "responseGenerationDelaySecondsInForce";

    /** Key of the settings row that sets the interval — DL-227, DL-309. */
    private static final String DELAY_SETTING_KEY = "response_generation_delay";

    /** Value of {@code scanner.response-generation-delay-seconds} every case here binds. */
    private static final long CONFIGURED_DELAY_SECONDS = 60L;

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

    /**
     * Consecutive failures after which the pass sets a candidate aside instead of reaching the
     * generator for it — DL-300 — see docs/DECISION_LOG.md.
     */
    private static final int CANDIDATE_FAILURE_LIMIT = 3;

    /** Rows one candidate statement returns, mirroring {@code CANDIDATE_BATCH_ROWS} — DL-248. */
    private static final int CANDIDATE_BATCH_ROWS = 100;

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseService responseService;

    @Mock
    private NotionService notionService;

    /** Reads the response_generation_delay row the interval is resolved from — DL-309. */
    @Mock
    private SettingRepository settingRepository;

    private ResponseGenerationScheduler scheduler;

    /**
     * Assembles the pass over its three doubles and a bound configuration.
     *
     * <p>The configuration carries a candidate ceiling no case of this class reaches — DL-282.
     */
    @BeforeEach
    void assemblePass() {
        scheduler = new ResponseGenerationScheduler(tweetRepository, responseService, notionService,
                boundWith(UNREACHABLE_CEILING), settingRepository);
    }

    private static ScannerProperties boundWith(int candidateCeiling) {
        return new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null,
                new ScannerProperties.Background(true, true, true, candidateCeiling));
    }

    @Test
    @DisplayName("presents the pass and the interval resolver as its public no-argument methods")
    void presentsThePassAndTheIntervalResolverAsItsPublicNoArgumentMethods() {
        List<Method> entryPoints = Arrays.stream(ResponseGenerationScheduler.class
                        .getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .toList();

        assertThat(entryPoints)
                .as("public no-argument methods")
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(PASS_METHOD_NAME, DELAY_METHOD_NAME);

        assertThat(entryPoints)
                .filteredOn(method -> PASS_METHOD_NAME.equals(method.getName()))
                .singleElement()
                .satisfies(pass -> {
                    assertThat(pass.getReturnType()).isEqualTo(void.class);
                    assertThat(pass.getExceptionTypes()).isEmpty();
                });
        assertThat(entryPoints)
                .filteredOn(method -> DELAY_METHOD_NAME.equals(method.getName()))
                .singleElement()
                .satisfies(resolver -> {
                    assertThat(resolver.getReturnType()).isEqualTo(long.class);
                    assertThat(resolver.getExceptionTypes()).isEmpty();
                });
    }

    // The pacing is on the registration, not on this class: the interval has to be re-read once per
    // pass, and a declarative fixed delay is resolved once by construction — DL-227, DL-309 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("declares no pacing annotation of its own, so no interval is resolved once at "
            + "registration")
    void declaresNoPacingAnnotationOfItsOwn() {
        assertThat(ResponseGenerationScheduler.class.getDeclaredMethods())
                .as("methods carrying their own interval, rate or schedule")
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)
                        || method.isAnnotationPresent(Schedules.class));

        assertThat(ResponseGenerationScheduler.class.getDeclaredMethods())
                .as("methods declared asynchronous")
                .noneMatch(method -> method.isAnnotationPresent(Async.class));

        assertThat(ResponseGenerationScheduler.class.getAnnotations())
                .as("annotations on the type")
                .extracting(annotation -> annotation.annotationType().getName())
                .doesNotContain(EnableScheduling.class.getName(), EnableAsync.class.getName());
    }

    // The settings row sets the interval — DL-227, DL-309 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("takes the interval from the response_generation_delay row when it holds a positive "
            + "whole number of seconds")
    void takesTheIntervalFromTheSettingRow() {
        when(settingRepository.findById(DELAY_SETTING_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_SETTING_KEY, " 7 ", "seeded")));

        assertThat(scheduler.responseGenerationDelaySecondsInForce()).isEqualTo(7L);
    }

    @Test
    @DisplayName("takes the configured interval when the row is absent")
    void takesTheConfiguredIntervalWhenTheRowIsAbsent() {
        when(settingRepository.findById(DELAY_SETTING_KEY)).thenReturn(Optional.empty());

        assertThat(scheduler.responseGenerationDelaySecondsInForce())
                .isEqualTo(CONFIGURED_DELAY_SECONDS);
    }

    @Test
    @DisplayName("takes the configured interval when the row holds null")
    void takesTheConfiguredIntervalWhenTheRowHoldsNull() {
        when(settingRepository.findById(DELAY_SETTING_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_SETTING_KEY, null, "seeded")));

        assertThat(scheduler.responseGenerationDelaySecondsInForce())
                .isEqualTo(CONFIGURED_DELAY_SECONDS);
    }

    // A non-positive interval would place the next instant at or before the previous completion —
    // DL-309 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] stored value {0}")
    @ValueSource(strings = {"0", "-1", "-86400", "", " ", "sixty", "7.5", "9999999999999999999999"})
    @DisplayName("takes the configured interval for a stored value that is not a positive whole "
            + "number of seconds")
    void takesTheConfiguredIntervalForAnUnusableStoredValue(String stored) {
        when(settingRepository.findById(DELAY_SETTING_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_SETTING_KEY, stored, "seeded")));

        assertThat(scheduler.responseGenerationDelaySecondsInForce())
                .isEqualTo(CONFIGURED_DELAY_SECONDS);
    }

    @Test
    @DisplayName("binds exactly the five collaborators one pass uses")
    void bindsExactlyTheFiveCollaboratorsOnePassUses() {
        Constructor<?>[] constructors = ResponseGenerationScheduler.class.getDeclaredConstructors();

        assertThat(constructors).as("declared constructors").hasSize(1);

        Class<?>[] parameterTypes = constructors[0].getParameterTypes();
        assertThat(parameterTypes)
                .as("collaborators bound by the constructor")
                .containsExactly(TweetRepository.class, ResponseService.class, NotionService.class,
                        ScannerProperties.class, SettingRepository.class);
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
                        notionService, bound, settingRepository))
                .withMessageContaining("tweetRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, null,
                        notionService, bound, settingRepository))
                .withMessageContaining("responseService");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        null, bound, settingRepository))
                .withMessageContaining("notionService");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        notionService, null, settingRepository))
                .withMessageContaining("properties");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        notionService, bound, null))
                .withMessageContaining("settingRepository");
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

        new ResponseGenerationScheduler(tweetRepository, responseService, notionService, withheld,
                settingRepository).generatePendingResponses();

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

        new ResponseGenerationScheduler(tweetRepository, responseService, notionService, unbound,
                settingRepository).generatePendingResponses();

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
                            + "(1 stored without a Notion mirror), 0 skipped, 0 failed, 0 set aside "
                            + "after 3 consecutive failures, over 1 batch(es)");
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
                            + "(0 stored without a Notion mirror), 0 skipped, 0 failed, 0 set aside "
                            + "after 3 consecutive failures, over 1 batch(es)");
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
                            + "(0 stored without a Notion mirror), 1 skipped, 0 failed, 0 set aside "
                            + "after 3 consecutive failures, over 1 batch(es)");
        } finally {
            detachAppender(recorded);
        }

        verifyNoInteractions(notionService);
    }

    // dto/ResponseDto carries a null content, so a pass handed one counts the candidate as succeeded,
    // reports a zero length and skips the mirror rather than failing — DL-080 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("counts a generated reply that carries no content as stored and reports no length")
    void countsAGeneratedReplyThatCarriesNoContentAsStored() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, null, FIRST_CANDIDATE_ID_TEXT)));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .isEqualTo("Response generation pass finished: 1 attempted, 1 succeeded "
                            + "(0 stored without a Notion mirror), 0 skipped, 0 failed, 0 set aside "
                            + "after 3 consecutive failures, over 1 batch(es)");
            assertThat(recorded.list).as("the stored-reply record")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("Stored response " + FIRST_REPLY_ID)
                            .contains("(0 character(s))"));
        } finally {
            detachAppender(recorded);
        }
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

    // -------------------------------------------------------------------------
    // A candidate failing for a reason that will not change stops consuming a provider call on every
    // pass — DL-300
    // -------------------------------------------------------------------------

    // A candidate whose handling keeps raising is attempted on three consecutive passes and passed
    // over on every later pass, so the generator is reached exactly three times — DL-300
    @Test
    @DisplayName("stops reaching the generator for a candidate that failed on three consecutive "
            + "passes")
    void stopsReachingTheGeneratorForACandidateThatFailedOnThreeConsecutivePasses() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenThrow(new IllegalStateException("the stored row cannot be written"));

        for (int pass = 0; pass < 8; pass++) {
            scheduler.generatePendingResponses();
        }

        verify(responseService, times(CANDIDATE_FAILURE_LIMIT))
                .generateResponseIfAbsentFor(only);
        verifyNoInteractions(notionService);
    }

    // The pass that first reaches the bound records it once at WARN, naming the candidate and the
    // number of consecutive failures — DL-300
    @Test
    @DisplayName("records once that a candidate is set aside, naming it and the failure count")
    void recordsOnceThatACandidateIsSetAside() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenThrow(new IllegalStateException("the stored row cannot be written"));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            for (int pass = 0; pass < 5; pass++) {
                scheduler.generatePendingResponses();
            }

            List<ILoggingEvent> setAside = recorded.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("is set aside"))
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();

            assertThat(setAside).as("records naming the candidate as set aside").hasSize(1);
            assertThat(setAside.get(0).getFormattedMessage())
                    .contains(FIRST_CANDIDATE_ID_TEXT)
                    .contains("failed generation on " + CANDIDATE_FAILURE_LIMIT
                            + " consecutive passes");
        } finally {
            detachAppender(recorded);
        }
    }

    // A set-aside candidate is counted separately from an attempted one, and the summary states the
    // count — DL-300
    @Test
    @DisplayName("counts a set-aside candidate in the pass summary and not as attempted")
    void countsASetAsideCandidateInThePassSummaryAndNotAsAttempted() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenThrow(new IllegalStateException("the stored row cannot be written"));

        for (int pass = 0; pass < CANDIDATE_FAILURE_LIMIT; pass++) {
            scheduler.generatePendingResponses();
        }

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .isEqualTo("Response generation pass finished: 0 attempted, 0 succeeded "
                            + "(0 stored without a Notion mirror), 0 skipped, 0 failed, 1 set aside "
                            + "after " + CANDIDATE_FAILURE_LIMIT
                            + " consecutive failures, over 1 batch(es)");
        } finally {
            detachAppender(recorded);
        }
    }

    // A candidate that fails and then succeeds carries no count into the next pass, so a transient
    // failure never accumulates towards the bound — DL-300
    @Test
    @DisplayName("forgets a candidate's failures once it is handled without raising")
    void forgetsACandidateFailuresOnceItIsHandledWithoutRaising() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"));

        for (int pass = 0; pass < 8; pass++) {
            scheduler.generatePendingResponses();
        }

        // Two failures, a success that clears the count, then three more failures reaching the bound
        verify(responseService, times(6)).generateResponseIfAbsentFor(only);
    }

    // A candidate another path answered clears the count too: it did not raise — DL-195, DL-300
    @Test
    @DisplayName("forgets a candidate's failures when another path answered it")
    void forgetsACandidateFailuresWhenAnotherPathAnsweredIt() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsentFor(only))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"))
                .thenThrow(new IllegalStateException("a transient refusal"));

        for (int pass = 0; pass < 8; pass++) {
            scheduler.generatePendingResponses();
        }

        verify(responseService, times(6)).generateResponseIfAbsentFor(only);
    }

    // Setting one candidate aside leaves every other candidate of the same batch attempted — DL-300
    @Test
    @DisplayName("keeps attempting the other candidates of a batch while one is set aside")
    void keepsAttemptingTheOtherCandidatesOfABatchWhileOneIsSetAside() {
        Tweet failing = candidate(FIRST_CANDIDATE_ID);
        Tweet healthy = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(failing, healthy));
        when(responseService.generateResponseIfAbsentFor(failing))
                .thenThrow(new IllegalStateException("the stored row cannot be written"));
        when(responseService.generateResponseIfAbsentFor(healthy))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        for (int pass = 0; pass < 5; pass++) {
            scheduler.generatePendingResponses();
        }

        verify(responseService, times(CANDIDATE_FAILURE_LIMIT))
                .generateResponseIfAbsentFor(failing);
        verify(responseService, times(5)).generateResponseIfAbsentFor(healthy);
        verify(notionService, times(5))
                .updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
    }

    // A pass whose only candidates are set aside still closes with a summary rather than reporting an
    // empty backlog, so the set-aside count is never invisible — DL-300
    @Test
    @DisplayName("closes with a summary when every candidate of the pass is set aside")
    void closesWithASummaryWhenEveryCandidateOfThePassIsSetAside() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class)))
                .thenThrow(new IllegalStateException("the stored row cannot be written"));

        for (int pass = 0; pass < CANDIDATE_FAILURE_LIMIT; pass++) {
            scheduler.generatePendingResponses();
        }

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(passSummary(recorded))
                    .contains("0 attempted")
                    .contains("2 set aside after " + CANDIDATE_FAILURE_LIMIT
                            + " consecutive failures");
        } finally {
            detachAppender(recorded);
        }
        verifyNoInteractions(notionService);
    }

    // The bookkeeping holds identifiers and counts only: no tweet or reply data, and no read is
    // served from it — DL-300
    @Test
    @DisplayName("holds nothing but candidate identifiers and their failure counts")
    void holdsNothingButCandidateIdentifiersAndTheirFailureCounts() {
        List<Field> retained = Arrays.stream(ResponseGenerationScheduler.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Map.class.isAssignableFrom(field.getType()))
                .toList();

        assertThat(retained).as("map-typed instance fields").singleElement()
                .satisfies(field -> {
                    assertThat(field.getGenericType().getTypeName())
                            .as("what the failure bookkeeping holds")
                            .isEqualTo("java.util.Map<java.lang.Integer, java.lang.Integer>");
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("the bookkeeping reference is final").isTrue();
                });
    }

    // -------------------------------------------------------------------------
    // A pass in flight abandons its remainder once the context begins to close — DL-282
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("declares the context close notification as the stop signal it listens for")
    void declaresTheContextCloseNotificationAsTheStopSignalItListensFor() {
        assertThat(ApplicationListener.class)
                .as("the stop signal the class subscribes to")
                .isAssignableFrom(ResponseGenerationScheduler.class);
        assertThat(Arrays.stream(ResponseGenerationScheduler.class.getGenericInterfaces())
                .map(java.lang.reflect.Type::getTypeName)
                .toList())
                .contains("org.springframework.context.ApplicationListener<"
                        + "org.springframework.context.event.ContextClosedEvent>");
    }

    @Test
    @DisplayName("reads no candidate batch at all when the context has already begun to close")
    void readsNoCandidateBatchAtAllWhenTheContextHasAlreadyBegunToClose() {
        beginContextClose();

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(abandonedSummary(recorded))
                    .contains("0 attempted")
                    .contains("over 0 batch(es)");
        } finally {
            detachAppender(recorded);
        }
        verifyNoInteractions(tweetRepository, responseService, notionService);
    }

    @Test
    @DisplayName("stops between two candidates once the context begins to close, leaving the rest "
            + "unanswered")
    void stopsBetweenTwoCandidatesOnceTheContextBeginsToClose() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        Tweet third = candidate(THIRD_CANDIDATE_ID);
        when(tweetRepository.findUnansweredBatchAfter(isNull(), any(Pageable.class)))
                .thenReturn(List.of(first, second, third));
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class))).thenAnswer(invocation -> {
            beginContextClose();
            return Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT, FIRST_CANDIDATE_ID_TEXT));
        });

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(abandonedSummary(recorded))
                    .as("the candidate in flight completed and none behind it was started")
                    .contains("1 attempted")
                    .contains("1 succeeded");
        } finally {
            detachAppender(recorded);
        }
        // The candidate in flight is completed and mirrored; the two behind it are never reached
        verify(responseService, times(1)).generateResponseIfAbsentFor(any(Tweet.class));
        verify(notionService, times(1)).updateTweetResponse(anyString(), anyString());
    }

    @Test
    @DisplayName("reads no further batch once the context begins to close during a full one")
    void readsNoFurtherBatchOnceTheContextBeginsToCloseDuringAFullOne() {
        List<Tweet> fullBatch = new ArrayList<>(CANDIDATE_BATCH_ROWS);
        for (int index = 0; index < CANDIDATE_BATCH_ROWS; index++) {
            fullBatch.add(candidate(FIRST_CANDIDATE_ID + index));
        }
        when(tweetRepository.findUnansweredBatchAfter(any(), any(Pageable.class)))
                .thenReturn(fullBatch);
        when(responseService.generateResponseIfAbsentFor(any(Tweet.class))).thenAnswer(invocation -> {
            beginContextClose();
            return Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT, FIRST_CANDIDATE_ID_TEXT));
        });

        scheduler.generatePendingResponses();

        // A full batch would otherwise be followed by a second keyset read
        verify(tweetRepository, times(1)).findUnansweredBatchAfter(any(), any(Pageable.class));
        verify(responseService, times(1)).generateResponseIfAbsentFor(any(Tweet.class));
    }

    @Test
    @DisplayName("honours the calling thread's interrupt status without clearing it")
    void honoursTheCallingThreadsInterruptStatusWithoutClearingIt() {
        Thread.currentThread().interrupt();
        try {
            scheduler.generatePendingResponses();

            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt status is read and left in place")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(tweetRepository, responseService, notionService);
    }

    @Test
    @DisplayName("records no closing summary of a completed pass when it abandoned one")
    void recordsNoClosingSummaryOfACompletedPassWhenItAbandonedOne() {
        beginContextClose();

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            scheduler.generatePendingResponses();

            assertThat(recorded.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Response generation pass finished:"))
                    .toList())
                    .as("a pass that stopped early does not report itself as finished")
                    .isEmpty();
        } finally {
            detachAppender(recorded);
        }
    }

    @Test
    @DisplayName("records the context close it observed once, and starts no pass afterwards")
    void recordsTheContextCloseItObservedOnce() {
        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            beginContextClose();

            assertThat(recorded.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Context close observed"))
                    .toList())
                    .hasSize(1);
        } finally {
            detachAppender(recorded);
        }
    }

    /**
     * Raises the stop signal by handing the unit the notification the context publishes first.
     */
    private void beginContextClose() {
        scheduler.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContext.class)));
    }

    /**
     * Returns the single abandonment summary the pass recorded.
     *
     * @param appender the appender that recorded the pass
     * @return the formatted summary message
     */
    private static String abandonedSummary(ListAppender<ILoggingEvent> appender) {
        List<String> summaries = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(
                        "Response generation pass abandoned because the context is closing:"))
                .toList();

        assertThat(summaries).hasSize(1);
        return summaries.get(0);
    }

    /**
     * Returns the single closing summary the pass recorded.
     *
     * @param appender the appender that recorded the pass
     * @return the formatted summary message
     */
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
