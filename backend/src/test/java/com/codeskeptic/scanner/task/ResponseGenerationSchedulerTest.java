package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

// Net-new (retired test antecedent: backend/tests/test_tasks.py). Unit under test: a faithful port of
// schedule_response_generation() at backend/app/tasks/response_generation.py:L35-50, absorbing the
// task body at :L10-33 — see docs/DECISION_LOG.md DL-047, DL-227
/**
 * Exercises {@link ResponseGenerationScheduler}.
 *
 * <p>Assertions cover the scheduling surface the pass presents — one public no-argument method, and
 * no interval, no rate and no schedule declared on this class — together with the three collaborators
 * its single constructor binds.
 *
 * <p>Assertions then cover the pass itself: one reply generated and mirrored per candidate, in
 * candidate order and on the thread that started the pass; each reply mirrored against the candidate
 * it was generated for; a candidate another path already answered storing and mirroring nothing; a
 * failing candidate, a failing candidate query and a rejected mirror each leaving the pass returning
 * normally; and the candidate row handed on as it was selected.
 *
 * <p>The interval between passes is declared by {@code config/AsyncSchedulingConfig} and asserted by
 * {@code config/AsyncSchedulingConfigTest} — see docs/DECISION_LOG.md DL-047, DL-227.
 *
 * <p>Every collaborator is a Mockito double. No Spring context is started, no scheduler thread is
 * created, no network call is made and no database is reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseGenerationScheduler")
class ResponseGenerationSchedulerTest {

    /** Name of the one method a scheduler invokes on this class. */
    private static final String PASS_METHOD_NAME = "generatePendingResponses";

    /** {@code tweets.id} of the first candidate. */
    private static final int FIRST_CANDIDATE_ID = 42;

    /** {@code tweets.id} of the second candidate. */
    private static final int SECOND_CANDIDATE_ID = 43;

    /** {@code tweets.id} of the third candidate. */
    private static final int THIRD_CANDIDATE_ID = 44;

    /** Identifier the first candidate is mirrored under. */
    private static final String FIRST_CANDIDATE_ID_TEXT = "42";

    /** Identifier the second candidate is mirrored under. */
    private static final String SECOND_CANDIDATE_ID_TEXT = "43";

    /** {@code responses.id} of the reply generated for the first candidate. */
    private static final String FIRST_REPLY_ID = "11";

    /** {@code responses.id} of the reply generated for the second candidate. */
    private static final String SECOND_REPLY_ID = "12";

    /** {@code responses.content} of the reply generated for the first candidate. */
    private static final String FIRST_REPLY_TEXT = "a first draft reply";

    /** {@code responses.content} of the reply generated for the second candidate. */
    private static final String SECOND_REPLY_TEXT = "a second draft reply";

    /** {@code responses.generated_at} every stubbed reply carries. */
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private ResponseService responseService;

    @Mock
    private NotionService notionService;

    @InjectMocks
    private ResponseGenerationScheduler scheduler;

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

    @Test
    @DisplayName("declares no interval, no rate and no schedule of its own")
    void declaresNoPacingOfItsOwn() {
        assertThat(ResponseGenerationScheduler.class.getDeclaredMethods())
                .as("methods carrying their own interval, rate or schedule")
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)
                        || method.isAnnotationPresent(Schedules.class)
                        || method.isAnnotationPresent(Async.class));

        assertThat(ResponseGenerationScheduler.class.getAnnotations())
                .as("annotations on the type")
                .extracting(annotation -> annotation.annotationType().getName())
                .doesNotContain(EnableScheduling.class.getName(), EnableAsync.class.getName());
    }

    @Test
    @DisplayName("binds exactly the three collaborators one pass uses")
    void bindsExactlyTheThreeCollaboratorsOnePassUses() {
        Constructor<?>[] constructors = ResponseGenerationScheduler.class.getDeclaredConstructors();

        assertThat(constructors).as("declared constructors").hasSize(1);

        Class<?>[] parameterTypes = constructors[0].getParameterTypes();
        assertThat(parameterTypes)
                .as("collaborators bound by the constructor")
                .containsExactly(TweetRepository.class, ResponseService.class, NotionService.class);
        assertThat(parameterTypes)
                .extracting(Class::getSimpleName)
                .doesNotContain("LlmService");
    }

    @Test
    @DisplayName("rejects a missing collaborator, naming the one that is absent")
    void rejectsAMissingCollaborator() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(null, responseService,
                        notionService))
                .withMessageContaining("tweetRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, null,
                        notionService))
                .withMessageContaining("responseService");
        assertThatNullPointerException()
                .isThrownBy(() -> new ResponseGenerationScheduler(tweetRepository, responseService,
                        null))
                .withMessageContaining("notionService");
    }

    @Test
    @DisplayName("generates and mirrors one reply for each candidate, in candidate order")
    void generatesAndMirrorsOneReplyForEachCandidateInOrder() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsent(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        when(responseService.generateResponseIfAbsent(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        InOrder pass = inOrder(tweetRepository, responseService, notionService);
        pass.verify(tweetRepository).findByResponsesIsEmpty();
        pass.verify(responseService).generateResponseIfAbsent(first);
        pass.verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        pass.verify(responseService).generateResponseIfAbsent(second);
        pass.verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        pass.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("mirrors each generated reply against the candidate it was generated for")
    void mirrorsEachGeneratedReplyAgainstItsOwnCandidate() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsent(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        when(responseService.generateResponseIfAbsent(second))
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
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsent(any(Tweet.class))).thenAnswer(invocation -> {
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

        verify(tweetRepository).findByResponsesIsEmpty();
        verify(responseService).generateResponseIfAbsent(only);
        verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        verifyNoMoreInteractions(tweetRepository, responseService, notionService);
    }

    @ParameterizedTest(name = "is_approved = {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("reaches the same collaborators whatever the approval flag of a reply holds")
    void reachesTheSameCollaboratorsWhateverTheApprovalFlagHolds(boolean approved) {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsent(first))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT, approved)));
        when(responseService.generateResponseIfAbsent(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT, approved)));

        scheduler.generatePendingResponses();

        InOrder pass = inOrder(tweetRepository, responseService, notionService);
        pass.verify(tweetRepository).findByResponsesIsEmpty();
        pass.verify(responseService).generateResponseIfAbsent(first);
        pass.verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        pass.verify(responseService).generateResponseIfAbsent(second);
        pass.verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        pass.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("does nothing when no candidate awaits a reply")
    void doesNothingWhenNoCandidateAwaitsAReply() {
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of());

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verifyNoInteractions(responseService, notionService);
    }

    @Test
    @DisplayName("mirrors nothing when the candidate already carried a reply")
    void mirrorsNothingWhenTheCandidateAlreadyCarriedAReply() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                .thenReturn(Optional.empty());

        scheduler.generatePendingResponses();

        verify(responseService).generateResponseIfAbsent(only);
        verifyNoInteractions(notionService);
    }

    @Test
    @DisplayName("continues with the next candidate after one candidate fails")
    void continuesWithTheNextCandidateAfterOneCandidateFails() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second));
        when(responseService.generateResponseIfAbsent(first))
                .thenThrow(new IllegalStateException("generation refused"));
        when(responseService.generateResponseIfAbsent(second))
                .thenReturn(Optional.of(reply(SECOND_REPLY_ID, SECOND_REPLY_TEXT,
                        SECOND_CANDIDATE_ID_TEXT)));

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verify(responseService).generateResponseIfAbsent(second);
        verify(notionService).updateTweetResponse(SECOND_CANDIDATE_ID_TEXT, SECOND_REPLY_TEXT);
        verifyNoMoreInteractions(notionService);
    }

    @Test
    @DisplayName("returns normally when the candidate query fails")
    void returnsNormallyWhenTheCandidateQueryFails() {
        when(tweetRepository.findByResponsesIsEmpty())
                .thenThrow(new IllegalStateException("candidate query refused"));

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verifyNoInteractions(responseService, notionService);
    }

    @Test
    @DisplayName("returns normally when mirroring a stored reply is rejected")
    void returnsNormallyWhenMirroringAStoredReplyIsRejected() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));
        doThrow(new IllegalStateException("mirror refused"))
                .when(notionService).updateTweetResponse(anyString(), anyString());

        assertThatCode(() -> scheduler.generatePendingResponses()).doesNotThrowAnyException();

        verify(notionService).updateTweetResponse(FIRST_CANDIDATE_ID_TEXT, FIRST_REPLY_TEXT);
        verify(tweetRepository, never()).save(any());
        verifyNoMoreInteractions(notionService);
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

    // The candidate query is the pass's only read of the tweets table — DL-226 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("reads the tweets table once for the whole pass, however many candidates it holds")
    void readsTheTweetsTableOnceForTheWholePass() {
        Tweet first = candidate(FIRST_CANDIDATE_ID);
        Tweet second = candidate(SECOND_CANDIDATE_ID);
        Tweet third = candidate(THIRD_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(first, second, third));
        when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        verify(tweetRepository).findByResponsesIsEmpty();
        verifyNoMoreInteractions(tweetRepository);

        ArgumentCaptor<Tweet> handedOn = ArgumentCaptor.forClass(Tweet.class);
        verify(responseService, times(3)).generateResponseIfAbsent(handedOn.capture());
        assertThat(handedOn.getAllValues())
                .as("rows handed to the generator")
                .containsExactly(first, second, third);
    }

    @Test
    @DisplayName("leaves the responses collection of a candidate row untouched")
    void leavesTheResponsesCollectionOfACandidateRowUntouched() {
        Tweet only = candidate(FIRST_CANDIDATE_ID);
        when(tweetRepository.findByResponsesIsEmpty()).thenReturn(List.of(only));
        when(responseService.generateResponseIfAbsent(any(Tweet.class)))
                .thenReturn(Optional.of(reply(FIRST_REPLY_ID, FIRST_REPLY_TEXT,
                        FIRST_CANDIDATE_ID_TEXT)));

        scheduler.generatePendingResponses();

        assertThat(only.getResponses()).as("responses collection of the candidate row").isEmpty();
        verify(tweetRepository, never()).save(any());
    }

    /**
     * Builds a {@code tweets} row carrying the supplied identifier and nothing else.
     *
     * @param id the {@code tweets.id} the row carries
     * @return a candidate row whose {@code responses} collection is empty
     */
    private static Tweet candidate(int id) {
        Tweet candidate = new Tweet();
        candidate.setId(id);
        return candidate;
    }

    /**
     * Builds a generated reply that is not approved.
     *
     * @param id      the {@code responses.id} the reply carries
     * @param content the {@code responses.content} the reply carries
     * @param tweetId the {@code responses.tweet_id} the reply carries
     * @return the generated reply
     */
    private static ResponseDto reply(String id, String content, String tweetId) {
        return reply(id, content, tweetId, false);
    }

    /**
     * Builds a generated reply carrying the supplied approval flag.
     *
     * @param id       the {@code responses.id} the reply carries
     * @param content  the {@code responses.content} the reply carries
     * @param tweetId  the {@code responses.tweet_id} the reply carries
     * @param approved the {@code responses.is_approved} the reply carries
     * @return the generated reply
     */
    private static ResponseDto reply(String id, String content, String tweetId, boolean approved) {
        return new ResponseDto(id, content, GENERATED_AT, approved, tweetId);
    }

    /**
     * Joins two equally sized capture lists element by element.
     *
     * @param identifiers the identifier captured from each call, in call order
     * @param text        the reply text captured from each call, in call order
     * @return one {@code identifier=text} entry per call, in call order
     */
    private static List<String> pairsOf(List<String> identifiers, List<String> text) {
        List<String> pairs = new ArrayList<>(identifiers.size());
        for (int index = 0; index < identifiers.size(); index++) {
            pairs.add(identifiers.get(index) + "=" + text.get(index));
        }
        return pairs;
    }
}
