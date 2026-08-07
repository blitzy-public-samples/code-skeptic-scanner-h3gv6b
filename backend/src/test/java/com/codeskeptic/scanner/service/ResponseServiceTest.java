package com.codeskeptic.scanner.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.dto.UpdateResponseRequest;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.exception.ResponseGenerationException;
import com.codeskeptic.scanner.repository.ResponseRepository;
import com.codeskeptic.scanner.repository.ResponseRepository.ResponseRow;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Net-new coverage of the four call sites at backend/app/api/responses.py:L15,L26,L44,L60, whose
// service was imported at :L3 and existed nowhere — see docs/DECISION_LOG.md DL-076, DL-084, DL-086,
// DL-081 and DL-195
/**
 * Exercises the five operations of {@link ResponseService}: the page it lists, the row it reads, the
 * route-owned row it generates, the claim-aware background generation path and the row it updates.
 *
 * <p>Five client-visible messages are reachable from this class and each is asserted as its own exact
 * string: {@code Tweet ID is required}, {@code Response not found},
 * {@code Failed to generate response}, {@code Update data is required} and
 * {@code Response not found or update failed}. The two {@link NotFoundException} messages are also
 * asserted against each other. No test here asserts an HTTP status.
 *
 * <p>Every collaborator is a Mockito double, no Spring context is started and no network, database,
 * filesystem or credential resource is reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseService")
class ResponseServiceTest {
    private static final String TWEET_ID = "4711";

    private static final int TWEET_KEY = 4711;

    private static final String RESPONSE_ID = "88";

    private static final int RESPONSE_KEY = 88;

    private static final String UNPARSEABLE_ID = "eighty-eight";

    private static final String GENERATED_TEXT = "Even seasoned reviewers disagree.";

    private static final String STORED_CONTENT = "Reviewers still disagree about the generated diff.";

    private static final String REVISED_CONTENT = "Two reviewers read the generated diff line by line.";

    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 1, 31, 9, 16);

    private static final LocalDateTime TWEET_AT = LocalDateTime.of(2026, 1, 31, 9, 15);

    private static final String TWEET_CONTENT = "Nobody reviews what the assistant writes.";

    private static final String CATCH_ALL_MESSAGE = "Internal server error";

    private static final String CATCH_ALL_NOT_FOUND = "Not found";

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

    // The storage transaction carries the statement bound — DL-246 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("bounds its storage transaction at the statement bound and leaves the injected "
            + "template unmodified")
    void boundsItsStorageTransactionAtTheStatementBoundAndLeavesTheInjectedTemplateUnmodified()
            throws Exception {
        TransactionTemplate injected = directTransactionTemplate();
        int injectedTimeoutBefore = injected.getTimeout();

        ResponseService bounded = new ResponseService(responseRepository, tweetRepository, llmService,
                responseMapper, tweetMapper, injected);

        Field field = ResponseService.class.getDeclaredField("transactionTemplate");
        field.setAccessible(true);
        TransactionTemplate held = (TransactionTemplate) field.get(bounded);

        assertThat(held.getTimeout())
                .as("timeout of the template the service holds, in seconds")
                .isEqualTo(Integer.parseInt(ResponseRepository.LOCK_WAIT_MILLIS) / 1000);
        assertThat(held).as("the service holds a copy, not the injected instance")
                .isNotSameAs(injected);
        assertThat(injected.getTimeout()).as("timeout of the injected template after construction")
                .isEqualTo(injectedTimeoutBefore);
        assertThat(held.getTransactionManager())
                .as("the copy runs on the injected template's transaction manager")
                .isSameAs(injected.getTransactionManager());
    }

    // The storage transaction needs a manager to open — DL-246 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses a transaction template that carries no transaction manager")
    void refusesATransactionTemplateThatCarriesNoTransactionManager() {
        TransactionTemplate withoutManager = new TransactionTemplate();

        assertThatThrownBy(() -> new ResponseService(responseRepository, tweetRepository, llmService,
                responseMapper, tweetMapper, withoutManager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transactionTemplate must carry a transaction manager.");
    }

    // backend/app/api/responses.py:L40-41
    @Test
    @DisplayName("rejects a generation request that carries no tweet identifier")
    void rejectsAGenerationRequestThatCarriesNoTweetIdentifier() {
        assertThatThrownBy(() -> service.generateResponse(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Tweet ID is required");

        verifyNoInteractions(tweetRepository, tweetMapper, llmService, responseRepository,
                responseMapper);
    }

    // backend/app/api/responses.py:L40-41
    @Test
    @DisplayName("rejects a generation request that carries an empty tweet identifier")
    void rejectsAGenerationRequestThatCarriesAnEmptyTweetIdentifier() {
        assertThatThrownBy(() -> service.generateResponse(""))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Tweet ID is required");

        verifyNoInteractions(tweetRepository, tweetMapper, llmService, responseRepository,
                responseMapper);
    }

    // backend/app/api/responses.py:L28-31
    @Test
    @DisplayName("reports that the response was not found when the read names no row")
    void reportsThatTheResponseWasNotFoundWhenTheReadNamesNoRow() {
        when(responseRepository.findById(RESPONSE_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResponseById(RESPONSE_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found");

        verifyNoInteractions(responseMapper);
    }

    // backend/app/api/responses.py:L22,L28-31
    @Test
    @DisplayName("reports that the response was not found when the read carries no number")
    void reportsThatTheResponseWasNotFoundWhenTheReadCarriesNoNumber() {
        assertThatThrownBy(() -> service.getResponseById(UNPARSEABLE_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found");

        verifyNoInteractions(responseRepository, responseMapper);
    }

    // backend/app/api/responses.py:L56-57
    @Test
    @DisplayName("rejects an update that carries no request body")
    void rejectsAnUpdateThatCarriesNoRequestBody() {
        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Update data is required");

        verifyNoInteractions(responseRepository, responseMapper);
    }

    // backend/app/api/responses.py:L56-57
    @Test
    @DisplayName("rejects an update whose body carries neither updatable member")
    void rejectsAnUpdateWhoseBodyCarriesNeitherUpdatableMember() {
        UpdateResponseRequest emptyBody = new UpdateResponseRequest(null, null);

        assertThat(emptyBody.carriesNoUpdatableMember()).isTrue();
        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, emptyBody))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Update data is required");

        verifyNoInteractions(responseRepository, responseMapper);
    }

    // backend/app/api/responses.py:L62-65
    @Test
    @DisplayName("reports that the response was not found or the update failed when it names no row")
    void reportsThatTheResponseWasNotFoundOrTheUpdateFailedWhenItNamesNoRow() {
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, contentOnly()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // backend/app/api/responses.py:L51,L62-65
    @Test
    @DisplayName("reports that the response was not found or the update failed when it carries no number")
    void reportsThatTheResponseWasNotFoundOrTheUpdateFailedWhenItCarriesNoNumber() {
        assertThatThrownBy(() -> service.updateResponse(UNPARSEABLE_ID, contentOnly()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verifyNoInteractions(responseRepository, responseMapper);
    }

    // backend/app/api/responses.py:L31 against :L65
    @Test
    @DisplayName("reports a different message for a missing row on a read than on an update")
    void reportsADifferentMessageForAMissingRowOnAReadThanOnAnUpdate() {
        when(responseRepository.findById(RESPONSE_KEY)).thenReturn(Optional.empty());
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY)).thenReturn(Optional.empty());

        Throwable onRead = catchThrowable(() -> service.getResponseById(RESPONSE_ID));
        Throwable onUpdate = catchThrowable(() -> service.updateResponse(RESPONSE_ID, contentOnly()));

        assertThat(onRead).isInstanceOf(NotFoundException.class).hasMessage("Response not found");
        assertThat(onUpdate).isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");
        assertThat(onRead.getMessage()).isNotEqualTo(onUpdate.getMessage());
        assertThat(onUpdate.getMessage()).isNotEqualTo(onRead.getMessage());
    }

    // backend/app/api/responses.py:L41,L31,L49,L57,L65 against backend/app/main.py:L33,L37
    @Test
    @DisplayName("carries five distinct client-visible messages and none of the catch-all messages")
    void carriesFiveDistinctClientVisibleMessagesAndNoneOfTheCatchAllMessages() {
        assertThat(BadRequestException.TWEET_ID_IS_REQUIRED).isEqualTo("Tweet ID is required");
        assertThat(NotFoundException.RESPONSE_NOT_FOUND).isEqualTo("Response not found");
        assertThat(ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE)
                .isEqualTo("Failed to generate response");
        assertThat(BadRequestException.UPDATE_DATA_IS_REQUIRED).isEqualTo("Update data is required");
        assertThat(NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED)
                .isEqualTo("Response not found or update failed");

        List<String> reachableMessages = List.of(
                BadRequestException.TWEET_ID_IS_REQUIRED,
                NotFoundException.RESPONSE_NOT_FOUND,
                ResponseGenerationException.FAILED_TO_GENERATE_RESPONSE,
                BadRequestException.UPDATE_DATA_IS_REQUIRED,
                NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED);

        assertThat(reachableMessages).doesNotHaveDuplicates();
        assertThat(reachableMessages).doesNotContain(CATCH_ALL_MESSAGE, CATCH_ALL_NOT_FOUND);
    }

    // backend/app/api/responses.py:L46-49
    @Test
    @DisplayName("reports a generation failure when the tweet identifier names no row")
    void reportsAGenerationFailureWhenTheTweetIdentifierNamesNoRow() {
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.generateResponse(TWEET_ID));

        assertThat(thrown).isInstanceOf(ResponseGenerationException.class)
                .hasMessage("Failed to generate response");
        assertThat(thrown).isNotInstanceOf(NotFoundException.class);
        verifyNoInteractions(tweetMapper, llmService, responseMapper);
        verify(responseRepository, never()).save(any(Response.class));
    }

    // backend/app/api/responses.py:L46-49
    @Test
    @DisplayName("reports a generation failure when the tweet identifier carries no number")
    void reportsAGenerationFailureWhenTheTweetIdentifierCarriesNoNumber() {
        Throwable thrown = catchThrowable(() -> service.generateResponse(UNPARSEABLE_ID));

        assertThat(thrown).isInstanceOf(ResponseGenerationException.class)
                .hasMessage("Failed to generate response");
        assertThat(thrown).isNotInstanceOf(NotFoundException.class);
        assertThat(thrown.getMessage()).isNotEqualTo(NotFoundException.TWEET_NOT_FOUND);
        verifyNoInteractions(tweetRepository, tweetMapper, llmService, responseRepository,
                responseMapper);
    }

    @Test
    @DisplayName("bounds a caller-supplied identifier on the failure-wrapping path as well as on the "
            + "success path")
    void boundsACallerSuppliedIdentifierOnTheFailureWrappingPath() {
        String unboundedIdentifier = "0".repeat(400) + TWEET_ID;
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class)))
                .thenThrow(new IllegalStateException("The provider answered no usable choice."));

        ListAppender<ILoggingEvent> recorded = attachAppender();
        try {
            Throwable thrown = catchThrowable(() -> service.generateResponse(unboundedIdentifier));

            assertThat(thrown).isInstanceOf(ResponseGenerationException.class)
                    .hasMessage("Failed to generate response");
            // The record SEC-010 named is the one written while the failure is wrapped; assert it
            // was actually written — DL-252.
            assertThat(recorded.list).extracting(ILoggingEvent::getFormattedMessage)
                    .as("records written while wrapping the failure")
                    .anyMatch(message -> message.contains("failed")
                            && message.contains("responses.py:L49"));
            for (ILoggingEvent event : recorded.list) {
                assertThat(event.getFormattedMessage()).as("record written at %s", event.getLevel())
                        .doesNotContain(unboundedIdentifier)
                        .hasSizeLessThan(unboundedIdentifier.length());
            }
        } finally {
            detachAppender(recorded);
        }
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ResponseService.class);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ResponseService.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    // backend/app/api/responses.py:L46-49
    @Test
    @DisplayName("reports a generation failure when the text generator fails")
    void reportsAGenerationFailureWhenTheTextGeneratorFails() {
        Tweet subject = tweetCarryingTheKey();
        IllegalStateException unusableCompletion =
                new IllegalStateException("The Chat Completions response carried blank generated text.");
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenThrow(unusableCompletion);

        Throwable thrown = catchThrowable(() -> service.generateResponse(TWEET_ID));

        assertThat(thrown).isInstanceOf(ResponseGenerationException.class)
                .hasMessage("Failed to generate response");
        assertThat(thrown.getCause()).isSameAs(unusableCompletion);
        assertThat(thrown.getMessage()).doesNotContain("Chat Completions");
        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // backend/app/api/responses.py:L46-49
    @Test
    @DisplayName("reports a generation failure when storing the row fails")
    void reportsAGenerationFailureWhenStoringTheRowFails() {
        Tweet subject = tweetCarryingTheKey();
        DataIntegrityViolationException rejectedInsert =
                new DataIntegrityViolationException("could not execute statement [23502]");
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenThrow(rejectedInsert);

        Throwable thrown = catchThrowable(() -> service.generateResponse(TWEET_ID));

        assertThat(thrown).isInstanceOf(ResponseGenerationException.class)
                .hasMessage("Failed to generate response");
        assertThat(thrown.getCause()).isSameAs(rejectedInsert);
        assertThat(thrown.getMessage()).doesNotContain("23502");
        verifyNoInteractions(responseMapper);
    }

    // backend/app/api/responses.py:L49 against backend/app/main.py:L33,L37
    @Test
    @DisplayName("reports the generation failure message and neither catch-all message")
    void reportsTheGenerationFailureMessageAndNeitherCatchAllMessage() {
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.generateResponse(TWEET_ID));

        assertThat(thrown.getMessage())
                .isEqualTo("Failed to generate response")
                .isNotEqualTo(CATCH_ALL_MESSAGE)
                .isNotEqualTo(CATCH_ALL_NOT_FOUND)
                .isNotEqualTo(NotFoundException.RESPONSE_NOT_FOUND)
                .isNotEqualTo(NotFoundException.RESPONSE_NOT_FOUND_OR_UPDATE_FAILED)
                .isNotEqualTo(BadRequestException.TWEET_ID_IS_REQUIRED);
    }

    // The single background generation entry point — DL-195, DL-252
    @Test
    @DisplayName("stores nothing and makes no provider call for a background pass when the row "
            + "already carries a response")
    void storesNothingForABackgroundPassWhenTheRowAlreadyCarriesAResponse() {
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(true);

        Optional<ResponseDto> stored = service.generateResponseIfAbsent(TWEET_ID);

        assertThat(stored).isEmpty();
        // The stored state is read once, before any provider call — DL-252
        verify(responseRepository).existsByTweetId(TWEET_KEY);
        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(llmService);
    }

    // The single background generation entry point — DL-195
    @Test
    @DisplayName("stores one row for a background pass when the row carries no response")
    void storesOneRowForABackgroundPassWhenTheRowCarriesNoResponse() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            saved.setId(RESPONSE_KEY);
            return saved;
        });
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        Optional<ResponseDto> stored = service.generateResponseIfAbsent(TWEET_ID);

        assertThat(stored).isPresent();
        assertThat(stored.get().id()).isEqualTo(RESPONSE_ID);
        // Once before the subject read — DL-252 — and once inside the storing transaction under the
        // parent lock — DL-195
        verify(responseRepository, times(2)).existsByTweetId(TWEET_KEY);
        verify(responseRepository).save(any(Response.class));
    }

    // The existence guard is read before the provider call — DL-252 — and again inside the
    // transaction that takes the parent lock and inserts — DL-195
    @Test
    @DisplayName("tests existence before the model call and again inside the storing transaction")
    void testsExistenceBeforeTheModelCallAndAgainInsideTheStoringTransaction() {
        Tweet subject = tweetCarryingTheKey();
        TweetDto subjectDto = tweetDto();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(subjectDto);
        when(llmService.generateResponse(subjectDto)).thenReturn(GENERATED_TEXT);
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false, true);

        service.generateResponseIfAbsent(TWEET_ID);

        InOrder ordering = inOrder(tweetRepository, llmService, responseRepository);
        ordering.verify(responseRepository).existsByTweetId(TWEET_KEY);
        ordering.verify(tweetRepository).findById(TWEET_KEY);
        ordering.verify(tweetRepository).existsById(TWEET_KEY);
        ordering.verify(llmService).generateResponse(subjectDto);
        ordering.verify(tweetRepository).findByIdForUpdate(TWEET_KEY);
        ordering.verify(responseRepository).existsByTweetId(TWEET_KEY);
        ordering.verifyNoMoreInteractions();
    }

    // The presence test of DL-252 answers before the paid call for a row that vanished
    @Test
    @DisplayName("makes no provider call for a background pass whose tweets row is gone")
    void makesNoProviderCallForABackgroundPassWhoseTweetsRowIsGone() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(false);
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());

        assertThat(service.generateResponseIfAbsent(TWEET_ID)).isEmpty();

        verifyNoInteractions(llmService, responseMapper);
        verify(responseRepository, never()).save(any(Response.class));
        // The reply check is read before the subject; the row being gone is what stops the provider
        // call — DL-252
        verify(responseRepository).existsByTweetId(TWEET_KEY);
    }

    // The route path takes the parent lock but not the existence guard — DL-195
    @Test
    @DisplayName("does not consult the existence guard on the route path")
    void doesNotConsultTheExistenceGuardOnTheRoutePath() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            saved.setId(RESPONSE_KEY);
            return saved;
        });
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        service.generateResponse(TWEET_ID);

        verify(responseRepository, never()).existsByTweetId(any());
        verify(responseRepository).save(any(Response.class));
    }

    // backend/app/api/responses.py:L40-41 applies to the background path too — DL-195
    @Test
    @DisplayName("refuses a background generation request carrying no identifier")
    void refusesABackgroundGenerationRequestCarryingNoIdentifier() {
        assertThatThrownBy(() -> service.generateResponseIfAbsent(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BadRequestException.TWEET_ID_IS_REQUIRED);
        assertThatThrownBy(() -> service.generateResponseIfAbsent(""))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BadRequestException.TWEET_ID_IS_REQUIRED);

        verifyNoInteractions(llmService, responseRepository);
    }

    // The same operation for a caller that already holds the row — DL-226 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("refuses a background generation request carrying a row that has not been stored")
    void refusesABackgroundGenerationRequestCarryingAnUnstoredRow() {
        assertThatThrownBy(() -> service.generateResponseIfAbsentFor(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.generateResponseIfAbsentFor(new Tweet()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(BadRequestException.TWEET_ID_IS_REQUIRED);

        verifyNoInteractions(llmService, responseRepository, tweetRepository, tweetMapper);
    }

    // The same operation for a caller that already holds the row — DL-226 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("generates from a supplied row without reading the tweets table")
    void generatesFromASuppliedRowWithoutReadingTheTweetsTable() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        Optional<ResponseDto> stored = service.generateResponseIfAbsentFor(subject);

        assertThat(stored).as("row the background pass stored").isPresent();
        verify(tweetMapper).toDto(subject);
        // The supplied row is never selected again; the presence test of DL-252 reads no row and the
        // storing transaction takes the locking read — DL-226
        verify(tweetRepository, never()).findById(TWEET_KEY);
        verify(tweetRepository, times(1)).existsById(TWEET_KEY);
        verify(tweetRepository, times(1)).findByIdForUpdate(TWEET_KEY);
        // Once before the subject read — DL-252 — and once inside the storing transaction under the
        // parent lock — DL-195
        verify(responseRepository, times(2)).existsByTweetId(TWEET_KEY);
        verify(responseRepository).save(any(Response.class));
    }

    // The reply check covers the supplied row too, so no paid call is made — DL-195, DL-226, DL-252
    @Test
    @DisplayName("stores nothing and makes no provider call for a supplied row that already carries "
            + "a reply")
    void storesNothingForASuppliedRowThatAlreadyCarriesAReply() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        lenient().when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        lenient().when(tweetRepository.findByIdForUpdate(TWEET_KEY))
                .thenReturn(Optional.of(subject));
        // Absent when the pass starts, present by the time the storing transaction reads it under the
        // parent lock — DL-195, DL-252
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false, true);

        Optional<ResponseDto> stored = service.generateResponseIfAbsentFor(subject);

        assertThat(stored).as("row the background pass stored").isEmpty();
        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper, llmService);
        verify(tweetRepository, never()).findByIdForUpdate(any());
    }

    // backend/app/api/responses.py:L44,L46-47
    @Test
    @DisplayName("stores the generated text as an unapproved row and returns the stored row")
    void storesTheGeneratedTextAsAnUnapprovedRowAndReturnsTheStoredRow() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            saved.setId(RESPONSE_KEY);
            return saved;
        });
        when(responseMapper.toDto(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            return new ResponseDto(String.valueOf(saved.getId()), saved.getContent(),
                    saved.getGeneratedAt(), saved.getIsApproved(), TWEET_ID);
        });

        // The mint is truncated to microseconds, so the window is too — DL-232
        LocalDateTime beforeTheCall = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ResponseDto stored = service.generateResponse(TWEET_ID);
        LocalDateTime afterTheCall = LocalDateTime.now();

        assertThat(stored).isNotNull();
        assertThat(stored.id()).isEqualTo(RESPONSE_ID);
        assertThat(stored.content()).isEqualTo(GENERATED_TEXT);
        assertThat(stored.isApproved()).isFalse();
        assertThat(stored.tweetId()).isEqualTo(TWEET_ID);
        assertThat(stored.generatedAt()).isBetween(beforeTheCall, afterTheCall);
    }

    // backend/app/api/responses.py:L44 with backend/app/tasks/response_generation.py:L16,L22,L25-26
    @Test
    @DisplayName("reads the tweet, maps it, generates text, then stores and converts the row")
    void readsTheTweetMapsItGeneratesTextThenStoresAndConvertsTheRow() {
        Tweet subject = tweetCarryingTheKey();
        TweetDto subjectDto = tweetDto();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(subjectDto);
        when(llmService.generateResponse(subjectDto)).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        service.generateResponse(TWEET_ID);

        InOrder orchestration =
                inOrder(tweetRepository, tweetMapper, llmService, responseRepository, responseMapper);
        orchestration.verify(tweetRepository).findById(TWEET_KEY);
        orchestration.verify(tweetMapper).toDto(subject);
        orchestration.verify(llmService).generateResponse(subjectDto);
        orchestration.verify(tweetRepository).findByIdForUpdate(TWEET_KEY);
        orchestration.verify(responseRepository).save(any(Response.class));
        orchestration.verify(responseMapper).toDto(any(Response.class));
        orchestration.verifyNoMoreInteractions();
    }

    // A row another process already answered costs no provider call — DL-252 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("makes no provider call for a row that already carries a reply")
    void skipsAnAutomaticGenerationForARowThatAlreadyCarriesAReply() {
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(true);

        assertThat(service.generateResponseIfAbsent(TWEET_ID)).isEmpty();

        verify(responseRepository).existsByTweetId(TWEET_KEY);
        verify(responseRepository, never()).save(any(Response.class));
        verify(tweetRepository, never()).findById(TWEET_KEY);
        verify(tweetRepository, never()).findByIdForUpdate(TWEET_KEY);
        verifyNoInteractions(tweetMapper, llmService, responseMapper);
    }

    @Test
    @DisplayName("stores nothing when the row is taken between the model request and the insert")
    void storesNothingWhenTheRowIsTakenDuringGeneration() {
        Tweet subject = tweetCarryingTheKey();
        // Absent when the pass starts, present by the time the storing transaction reads it under the
        // parent lock — DL-195, DL-252
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false, true);
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);

        assertThat(service.generateResponseIfAbsent(TWEET_ID)).isEmpty();

        verify(llmService).generateResponse(any(TweetDto.class));
        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    @Test
    @DisplayName("stores the reply when the row still carries none at the insert")
    void storesTheReplyWhenTheRowCarriesNone() {
        Tweet subject = tweetCarryingTheKey();
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        assertThat(service.generateResponseIfAbsent(TWEET_ID))
                .isPresent()
                .get()
                .extracting(ResponseDto::id)
                .isEqualTo(RESPONSE_ID);

        // Once before the subject read — DL-252 — and once inside the storing transaction under the
        // parent lock — DL-195
        verify(responseRepository, times(2)).existsByTweetId(TWEET_KEY);
        verify(responseRepository).save(any(Response.class));
    }

    // The per-instance claim is keyed by the parsed Integer, and not by the raw path text — DL-195.
    @Test
    @DisplayName("treats alternate decimal spellings as one in-process generation claim")
    void treatsAlternateDecimalSpellingsAsOneInProcessGenerationClaim() throws Exception {
        Tweet subject = tweetCarryingTheKey();
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenAnswer(invocation -> {
            modelEntered.countDown();
            if (!releaseModel.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the generated response.");
            }
            return GENERATED_TEXT;
        });
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ResponseDto>> first =
                    executor.submit(() -> service.generateResponseIfAbsent(TWEET_ID));
            assertThat(modelEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Optional<ResponseDto> alias = service.generateResponseIfAbsent("0" + TWEET_ID);

            assertThat(alias).isEmpty();
            releaseModel.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            releaseModel.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(llmService, times(1)).generateResponse(any(TweetDto.class));
        verify(responseRepository, times(1)).save(any(Response.class));
    }

    // The route POST /responses is unaffected by the claim — backend/app/api/responses.py:L44 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("tests no claim on the reviewer-initiated route")
    void testsNoClaimOnTheReviewerInitiatedRoute() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        service.generateResponse(TWEET_ID);

        verify(responseRepository, never()).existsByTweetId(any());
    }

    // backend/app/db/models.py:L26-28 with backend/app/api/responses.py:L44
    @Test
    @DisplayName("associates the stored row with the loaded tweet and stores it unapproved")
    void associatesTheStoredRowWithTheLoadedTweetAndStoresItUnapproved() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        // The mint is truncated to microseconds, so the window is too — DL-232
        LocalDateTime beforeTheCall = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        service.generateResponse(TWEET_ID);
        LocalDateTime afterTheCall = LocalDateTime.now();

        ArgumentCaptor<Response> submitted = ArgumentCaptor.forClass(Response.class);
        verify(responseRepository).save(submitted.capture());
        Response written = submitted.getValue();

        assertThat(written.getIsApproved()).isNotNull();
        assertThat(written.getIsApproved()).isFalse();
        assertThat(written.getGeneratedAt()).isNotNull();
        assertThat(written.getGeneratedAt()).isBetween(beforeTheCall, afterTheCall);
        assertThat(written.getGeneratedAt().getNano() % 1_000).isZero();
        assertThat(written.getTweet()).isSameAs(subject);
        assertThat(written.getContent()).isEqualTo(GENERATED_TEXT);
    }

    // The minted timestamp carries the precision the column stores — DL-232 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("mints generated_at truncated to microseconds so a read reports the same value")
    void mintsGeneratedAtTruncatedToMicrosecondsSoAReadReportsTheSameValue() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        service.generateResponse(TWEET_ID);

        ArgumentCaptor<Response> submitted = ArgumentCaptor.forClass(Response.class);
        verify(responseRepository).save(submitted.capture());
        LocalDateTime minted = submitted.getValue().getGeneratedAt();

        assertThat(minted).isNotNull();
        assertThat(minted.getNano() % 1_000)
                .as("nanosecond field of a microsecond-truncated timestamp").isZero();
        assertThat(minted).isEqualTo(minted.truncatedTo(ChronoUnit.MICROS));
    }

    // backend/app/tasks/response_generation.py:L25-26,L33
    @Test
    @DisplayName("returns the row the mapper built from the stored entity")
    void returnsTheRowTheMapperBuiltFromTheStoredEntity() {
        Tweet subject = tweetCarryingTheKey();
        ResponseDto mapped = storedDto();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(invocation -> {
            Response submitted = invocation.getArgument(0);
            assertThat(submitted.getId()).isNull();
            submitted.setId(RESPONSE_KEY);
            return submitted;
        });
        when(responseMapper.toDto(any(Response.class))).thenAnswer(invocation -> {
            Response saved = invocation.getArgument(0);
            assertThat(saved.getId()).isEqualTo(RESPONSE_KEY);
            return mapped;
        });

        ResponseDto returned = service.generateResponse(TWEET_ID);

        assertThat(returned).isSameAs(mapped);
        assertThat(returned.id()).isEqualTo(RESPONSE_ID);
    }

    // backend/app/api/responses.py:L60,L62-63
    @Test
    @DisplayName("writes only the content when the body carries content alone")
    void writesOnlyTheContentWhenTheBodyCarriesContentAlone() {
        Response existing = storedRowCarryingApproval(true);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID, contentOnly());

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getContent()).isEqualTo(REVISED_CONTENT);
        assertThat(written.getIsApproved()).isTrue();
    }

    // backend/app/api/responses.py:L60,L62-63
    @Test
    @DisplayName("writes only the approval flag when the body carries approval alone")
    void writesOnlyTheApprovalFlagWhenTheBodyCarriesApprovalAlone() {
        Response existing = storedRowCarryingApproval(false);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID, new UpdateResponseRequest(null, BooleanNode.TRUE));

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getIsApproved()).isTrue();
        assertThat(written.getContent()).isEqualTo(STORED_CONTENT);
    }

    // backend/app/api/responses.py:L60,L62-63
    @Test
    @DisplayName("writes both columns when the body carries both")
    void writesBothColumnsWhenTheBodyCarriesBoth() {
        Response existing = storedRowCarryingApproval(false);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), BooleanNode.TRUE));

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getContent()).isEqualTo(REVISED_CONTENT);
        assertThat(written.getIsApproved()).isTrue();
    }

    // An explicit json null writes the nullable column — DL-244 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports an explicit json null content as a write of null")
    void reportsAnExplicitJsonNullContentAsAWriteOfNull() {
        UpdateResponseRequest request =
                new UpdateResponseRequest(NullNode.getInstance(), BooleanNode.TRUE);

        assertThat(request.carriesNoUpdatableMember()).isFalse();
        assertThat(request.writesContent()).isTrue();
        assertThat(request.contentValue()).isNull();
    }

    // An explicit json null writes the nullable column — DL-244 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports an explicit json null approval flag as a write of null")
    void reportsAnExplicitJsonNullApprovalFlagAsAWriteOfNull() {
        UpdateResponseRequest request =
                new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT),
                        NullNode.getInstance());

        assertThat(request.carriesNoUpdatableMember()).isFalse();
        assertThat(request.writesApproval()).isTrue();
        assertThat(request.approvalValue()).isNull();
    }

    // Bounded lock wait with an explicit contention outcome — DL-246 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports a contended update row with the update-failed literal")
    void reportsAContendedUpdateRowWithTheUpdateFailedLiteral() {
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY))
                .thenThrow(new PessimisticLockingFailureException("row locked"));

        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, contentOnly()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // Bounded lock wait with an explicit contention outcome — DL-246 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports a contended update row that timed out with the update-failed literal")
    void reportsAContendedUpdateRowThatTimedOutWithTheUpdateFailedLiteral() {
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY))
                .thenThrow(new QueryTimeoutException("statement bound reached"));

        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, contentOnly()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // Bounded lock wait with an explicit contention outcome — DL-246 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("stores nothing when the parent row of a background generation stays contended")
    void storesNothingWhenTheParentRowOfABackgroundGenerationStaysContended() {
        Tweet subject = new Tweet();
        subject.setId(TWEET_KEY);
        subject.setContent("A doubtful post");
        lenient().when(llmService.generateResponse(any(TweetDto.class)))
                .thenReturn("A generated reply");
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        lenient().when(tweetRepository.findByIdForUpdate(TWEET_KEY))
                .thenThrow(new PessimisticLockingFailureException("parent locked"));

        assertThat(service.generateResponseIfAbsentFor(subject)).isEmpty();

        verify(responseRepository, never()).save(any(Response.class));
    }

    // dto/ResponseDto declares both members required — DL-080, DL-244 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports an update that would empty the content with the update-failed literal")
    void reportsAnUpdateThatWouldEmptyTheContentWithTheUpdateFailedLiteral() {
        Response existing = storedRowCarryingApproval(false);
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(NullNode.getInstance(), null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // dto/ResponseDto declares both members required — DL-080, DL-244 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports an update that would empty the approval flag with the update-failed "
            + "literal")
    void reportsAnUpdateThatWouldEmptyTheApprovalFlagWithTheUpdateFailedLiteral() {
        Response existing = storedRowCarryingApproval(false);
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(null, NullNode.getInstance())))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).save(any(Response.class));
        verifyNoInteractions(responseMapper);
    }

    // A usable carried value, an explicit JSON null included, is a write — DL-050, DL-082, DL-244 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest
    @MethodSource("usableUpdateBodies")
    @DisplayName("builds a request from any usable carried value and reports it as a write")
    void buildsARequestFromAnyUsableCarriedValueAndReportsItAsAWrite(
            JsonNode content, JsonNode isApproved) {
        UpdateResponseRequest request = new UpdateResponseRequest(content, isApproved);

        assertThat(request.carriesNoUpdatableMember()).isFalse();
        assertThat(request.writesContent()).isEqualTo(content != null);
        assertThat(request.writesApproval()).isEqualTo(isApproved != null);

        verifyNoInteractions(responseRepository, responseMapper);
    }

    private static Stream<Arguments> usableUpdateBodies() {
        return Stream.of(
                Arguments.of(NullNode.getInstance(), null),
                Arguments.of(TextNode.valueOf(REVISED_CONTENT), null),
                Arguments.of(TextNode.valueOf(""), null),
                Arguments.of(null, NullNode.getInstance()),
                Arguments.of(null, BooleanNode.TRUE),
                Arguments.of(null, BooleanNode.FALSE),
                Arguments.of(NullNode.getInstance(), NullNode.getInstance()));
    }

    // Every JSON type a body may carry is accepted, which is validation parity with the free-form
    // request.json of backend/app/api/responses.py:L54 — DL-050, DL-231 — see docs/DECISION_LOG.md
    @ParameterizedTest
    @MethodSource("carriedValuesOfEveryJsonType")
    @DisplayName("accepts a carried value of any JSON type and reads the value the column stores")
    void acceptsACarriedValueOfAnyJsonType(JsonNode content, JsonNode isApproved,
            String expectedContent, Boolean expectedApproval) {
        UpdateResponseRequest request = new UpdateResponseRequest(content, isApproved);

        assertThat(request.contentValue()).as("value the content column stores")
                .isEqualTo(expectedContent);
        assertThat(request.approvalValue()).as("value the is_approved column stores")
                .isEqualTo(expectedApproval);
        verifyNoInteractions(responseRepository, responseMapper);
    }

    private static Stream<Arguments> carriedValuesOfEveryJsonType() {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        object.put("x", 1);
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        array.add("a");
        return Stream.of(
                Arguments.of(IntNode.valueOf(123), null, "123", null),
                Arguments.of(BooleanNode.TRUE, null, "true", null),
                Arguments.of(array, null, "[\"a\"]", null),
                Arguments.of(object, null, "{\"x\":1}", null),
                Arguments.of(null, IntNode.valueOf(1), null, Boolean.TRUE),
                Arguments.of(null, IntNode.valueOf(0), null, Boolean.FALSE),
                Arguments.of(null, TextNode.valueOf("true"), null, Boolean.TRUE),
                Arguments.of(null, TextNode.valueOf("no"), null, Boolean.FALSE),
                Arguments.of(null, array, null, Boolean.FALSE),
                Arguments.of(null, object, null, Boolean.FALSE),
                Arguments.of(TextNode.valueOf(REVISED_CONTENT), TextNode.valueOf("true"),
                        REVISED_CONTENT, Boolean.TRUE),
                Arguments.of(IntNode.valueOf(7), BooleanNode.TRUE, "7", Boolean.TRUE));
    }

    // Presence decides whether a column is written and the carried value decides what is stored, each
    // member independently of the other — DL-082, DL-244 — see docs/DECISION_LOG.md
    @ParameterizedTest
    @MethodSource("mixedUpdateBodies")
    @DisplayName("writes each carried member of a mixed body and leaves an omitted member untouched")
    void writesEachCarriedMemberOfAMixedBody(JsonNode content, JsonNode isApproved,
            String expectedContent, Boolean expectedApproval) {
        Response existing = storedRowCarryingApproval(false);
        existing.setContent(STORED_CONTENT);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID, new UpdateResponseRequest(content, isApproved));

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getContent()).isEqualTo(expectedContent);
        assertThat(written.getIsApproved()).isEqualTo(expectedApproval);
    }

    private static Stream<Arguments> mixedUpdateBodies() {
        return Stream.of(
                Arguments.of(TextNode.valueOf(REVISED_CONTENT), BooleanNode.TRUE,
                        REVISED_CONTENT, Boolean.TRUE),
                Arguments.of(TextNode.valueOf(REVISED_CONTENT), null,
                        REVISED_CONTENT, Boolean.FALSE),
                Arguments.of(null, BooleanNode.TRUE,
                        STORED_CONTENT, Boolean.TRUE),
                Arguments.of(TextNode.valueOf(""), BooleanNode.FALSE,
                        "", Boolean.FALSE));
    }

    // A carried value is read without coercion — DL-231, DL-244 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reads a carried value as the type its target column holds")
    void readsACarriedValueAsTheTypeItsTargetColumnHolds() {
        assertThat(new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), null).contentValue())
                .isEqualTo(REVISED_CONTENT);
        assertThat(new UpdateResponseRequest(TextNode.valueOf(""), null).contentValue()).isEmpty();
        assertThat(new UpdateResponseRequest(NullNode.getInstance(), null).contentValue()).isNull();
        assertThat(new UpdateResponseRequest(null, BooleanNode.TRUE).approvalValue()).isTrue();
        assertThat(new UpdateResponseRequest(null, BooleanNode.FALSE).approvalValue()).isFalse();
        assertThat(new UpdateResponseRequest(null, NullNode.getInstance()).approvalValue()).isNull();
    }

    // The two column types of backend/app/db/models.py:L24,L26 — DL-082 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("builds a request from a json string content and a json boolean approval flag")
    void buildsARequestFromAJsonStringContentAndAJsonBooleanApprovalFlag() {
        UpdateResponseRequest usable = new UpdateResponseRequest(
                TextNode.valueOf(REVISED_CONTENT), BooleanNode.FALSE);

        assertThat(usable.writesContent()).isTrue();
        assertThat(usable.contentValue()).isEqualTo(REVISED_CONTENT);
        assertThat(usable.writesApproval()).isTrue();
        assertThat(usable.approvalValue()).isFalse();
    }

    @Test
    @DisplayName("maps an accepted update through the real response mapper")
    void mapsAnAcceptedUpdateThroughTheRealResponseMapper() {
        Response existing = storedRowCarryingApproval(false);
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY)).thenReturn(Optional.of(existing));
        when(responseRepository.save(existing)).thenReturn(existing);
        ResponseService serviceWithRealMapper =
                new ResponseService(responseRepository, tweetRepository, llmService,
                        new ResponseMapper(), tweetMapper, directTransactionTemplate());

        ResponseDto updated = serviceWithRealMapper.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), BooleanNode.TRUE));

        assertThat(updated.id()).isEqualTo(RESPONSE_ID);
        assertThat(updated.content()).isEqualTo(REVISED_CONTENT);
        assertThat(updated.isApproved()).isTrue();
        assertThat(updated.tweetId()).isEqualTo(TWEET_ID);
        verify(responseRepository).save(existing);
    }

    @Test
    @DisplayName("reports the required tweet_id when a response has no tweet association")
    void reportsTheRequiredTweetIdWhenAResponseHasNoTweetAssociation() {
        Response withoutTweet = storedRowCarryingApproval(false);
        withoutTweet.setTweet(null);

        Throwable thrown = catchThrowable(() -> new ResponseMapper().toDto(withoutTweet));

        assertThat(thrown)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tweet_id must not be null.");
    }

    // backend/app/db/models.py:L26 with backend/app/api/responses.py:L60
    @Test
    @DisplayName("leaves approval untouched when the body omits it")
    void leavesApprovalUntouchedWhenTheBodyOmitsIt() {
        Response existing = storedRowCarryingApproval(true);
        UpdateResponseRequest withoutApproval = contentOnly();
        stubTheUpdateOf(existing);

        assertThat(withoutApproval.writesApproval()).isFalse();
        service.updateResponse(RESPONSE_ID, withoutApproval);

        assertThat(theRowSubmittedForUpdate(existing).getIsApproved()).isTrue();
    }

    // backend/app/db/models.py:L26 with backend/app/api/responses.py:L60
    @Test
    @DisplayName("revokes approval when the body carries it as false")
    void revokesApprovalWhenTheBodyCarriesItAsFalse() {
        Response existing = storedRowCarryingApproval(true);
        UpdateResponseRequest revokingApproval = new UpdateResponseRequest(null, BooleanNode.FALSE);
        stubTheUpdateOf(existing);

        assertThat(revokingApproval.writesApproval()).isTrue();
        assertThat(revokingApproval.approvalValue()).isFalse();
        service.updateResponse(RESPONSE_ID, revokingApproval);

        assertThat(theRowSubmittedForUpdate(existing).getIsApproved()).isFalse();
    }

    // backend/app/db/models.py:L23-28
    @Test
    @DisplayName("leaves the identifier, the generation time and the tweet association untouched on an update")
    void leavesTheIdentifierTheGenerationTimeAndTheTweetAssociationUntouchedOnAnUpdate() {
        Response existing = storedRowCarryingApproval(false);
        Tweet association = existing.getTweet();
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), BooleanNode.TRUE));

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getId()).isEqualTo(RESPONSE_KEY);
        assertThat(written.getGeneratedAt()).isEqualTo(STORED_AT);
        assertThat(written.getTweet()).isSameAs(association);
        assertThat(written.getTweet().getId()).isEqualTo(TWEET_KEY);
    }

    // backend/app/schema/response.py:L4-9
    @Test
    @DisplayName("accepts an empty content value")
    void acceptsAnEmptyContentValue() {
        Response existing = storedRowCarryingApproval(false);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID, new UpdateResponseRequest(TextNode.valueOf(""), null));

        assertThat(theRowSubmittedForUpdate(existing).getContent()).isEmpty();
    }

    // backend/app/schema/response.py:L4-9
    @Test
    @DisplayName("accepts a content value of any length")
    void acceptsAContentValueOfAnyLength() {
        Response existing = storedRowCarryingApproval(false);
        String longContent = "reviewed ".repeat(2_000);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(TextNode.valueOf(longContent), null));

        Response written = theRowSubmittedForUpdate(existing);
        assertThat(written.getContent()).isEqualTo(longContent);
        assertThat(written.getContent()).hasSize(18_000);
    }

    // backend/app/api/responses.py:L11,L15
    @Test
    @DisplayName("requests the first wire page as index zero")
    void requestsTheFirstWirePageAsIndexZero() {
        when(responseRepository.findAllRows(any(Pageable.class)))
                .thenReturn(pageOfStoredRows(0, 10, 25L));

        service.getPaginatedResponses(1, 10);

        Pageable requested = theRequestedPage();
        assertThat(requested.getPageNumber()).isZero();
        assertThat(requested.getPageSize()).isEqualTo(10);
    }

    // No page size is reduced — DL-217 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} is served unreduced")
    @ValueSource(ints = {100, 101, 500, 1_000, 1_001, 10_000, Integer.MAX_VALUE})
    @DisplayName("serves every per_page it is given without reducing it")
    void servesEveryPerPageWithoutReducingIt(int perPage) {
        stubEveryWindowRead(0L);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(1, perPage);

        assertThat(envelope.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(perPage);
        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allSatisfy(window -> assertThat(window.getPageSize())
                        .as("rows the statement was asked for").isEqualTo(perPage));
    }

    // One paged query answers a page of any size — DL-217 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} is read by the single page statement")
    @ValueSource(ints = {1, 10, 499, 500, 1_000, 5_000})
    @DisplayName("reads a page of any size with one page statement and no row count")
    void readsAPageOfAnySizeWithOnePageStatement(int perPage) {
        when(responseRepository.findAllRows(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.<Pageable>getArgument(0), 0L));

        service.getPaginatedResponses(1, perPage);

        assertThat(theRequestedPage().getPageSize())
                .as("rows the single statement was asked for").isEqualTo(perPage);
        verify(responseRepository, never()).count();
    }

    // Every page read carries a total order — DL-038 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("orders every page read by identifier ascending")
    void ordersEveryPageReadByIdentifierAscending() {
        stubEveryWindowRead(0L);

        service.getPaginatedResponses(2, 10);

        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allSatisfy(window -> assertThat(window.getSort())
                        .as("sort of one window").isEqualTo(Sort.by(Sort.Direction.ASC, "id")));
    }

    // backend/app/api/responses.py:L11-12 declares defaults and no bound — DL-217
    @Test
    @DisplayName("restates the unreduced per_page in the pagination block")
    void restatesTheUnreducedPerPageInThePaginationBlock() {
        when(responseRepository.findAllRows(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0L));

        PaginatedResponsesDto envelope = service.getPaginatedResponses(1, 500);

        assertThat(envelope.pagination().perPage()).isEqualTo(500);
    }

    // A page whose first row lies beyond Integer.MAX_VALUE rows holds no row — DL-225 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} renders the empty page")
    @CsvSource({
            "214748366,10",
            "2147483647,10",
            "2147483647,1000",
            "2147485,1000"
    })
    @DisplayName("renders the empty page for a page whose first row lies beyond the largest "
            + "addressable offset, without asking the repository for it")
    void rendersTheEmptyPageBeyondTheLargestAddressableOffset(int page, int perPage) {
        when(responseRepository.count()).thenReturn(21L);

        PaginatedResponsesDto rendered = service.getPaginatedResponses(page, perPage);

        assertThat(rendered.responses()).as("rows of the rendered page").isEmpty();
        assertThat(rendered.pagination().page()).as("page the envelope restates").isEqualTo(page);
        assertThat(rendered.pagination().perPage()).as("per_page the envelope restates")
                .isEqualTo(perPage);
        assertThat(rendered.pagination().total()).as("total the envelope reports").isEqualTo(21L);
        assertThat(rendered.pagination().totalPages()).as("total_pages the envelope reports")
                .isEqualTo((int) Math.ceil(21.0d / perPage));
        // The row count alone builds the envelope; the paged query is never issued — DL-225
        verify(responseRepository, never()).findAllRows(any(Pageable.class));
        verify(responseRepository).count();
    }

    // A page whose first row lies at most Integer.MAX_VALUE rows in is read as any other page —
    // DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} still reaches the repository")
    @CsvSource({
            "214748365,10",
            "2147483647,1",
            "1000000,10"
    })
    @DisplayName("reads a page whose first row lies within the largest addressable offset")
    void readsAPageWhoseFirstRowLiesWithinTheLargestAddressableOffset(int page, int perPage) {
        when(responseRepository.findAllRows(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(page - 1, perPage), 21L));

        PaginatedResponsesDto rendered = service.getPaginatedResponses(page, perPage);

        assertThat(theRequestedPage().getOffset())
                .as("offset of the page request").isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThat(rendered.responses()).as("rows of a page beyond the last one").isEmpty();
        verify(responseRepository, never()).count();
    }

    // backend/app/api/responses.py:L11,L15
    @Test
    @DisplayName("requests the third wire page as index two and reports it as page three")
    void requestsTheThirdWirePageAsIndexTwoAndReportsItAsPageThree() {
        when(responseRepository.findAllRows(any(Pageable.class)))
                .thenReturn(pageOfStoredRows(2, 10, 25L));

        PaginatedResponsesDto envelope = service.getPaginatedResponses(3, 10);

        Pageable requested = theRequestedPage();
        assertThat(requested.getPageNumber()).isEqualTo(2);
        assertThat(requested.getPageSize()).isEqualTo(10);
        assertThat(envelope.pagination().page()).isEqualTo(3);
    }

    // No upper bound is applied to per_page — DL-217 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a per_page of {0} reads a page of the same size")
    @ValueSource(ints = {99, 100, 101, 250, 1_000, 1_001, Integer.MAX_VALUE})
    @DisplayName("passes every page size through unreduced")
    void passesEveryPageSizeThroughUnreduced(int perPage) {
        stubEveryWindowRead(0L);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(1, perPage);

        assertThat(envelope.pagination().perPage()).isEqualTo(perPage);
    }

    // A page size is restated as requested — DL-217 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reports the supplied page size in the pagination block it builds")
    void reportsTheSuppliedPageSizeInThePaginationBlockItBuilds() {
        when(responseRepository.findAllRows(any(Pageable.class)))
                .thenReturn(pageOfStoredRows(0, 500, 0L));

        PaginationDto pagination = service.getPaginatedResponses(1, 500).pagination();

        assertThat(pagination.perPage()).isEqualTo(500);
    }

    // Lower bounds applied to page and per_page — DL-217 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} reads page index {2} of size {3}")
    @CsvSource({
            "0,10,0,10",
            "-1,10,0,10",
            "-2147483648,10,0,10",
            "1,0,0,10",
            "1,-1,0,10",
            "0,0,0,10"
    })
    @DisplayName("reads a page or per_page below the lower bound as its default")
    void readsAPageOrPerPageBelowTheLowerBoundAsItsDefault(int page, int perPage,
            int expectedIndex, int expectedSize) {
        when(responseRepository.findAllRows(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.<Pageable>getArgument(0), 0L));

        PaginatedResponsesDto envelope = service.getPaginatedResponses(page, perPage);

        Pageable requested = theRequestedPage();
        assertThat(requested.getPageNumber())
                .as("page index the repository was asked for").isEqualTo(expectedIndex);
        assertThat(requested.getPageSize())
                .as("page size the repository was asked for").isEqualTo(expectedSize);
        assertThat(envelope.pagination().page()).as("page the envelope restates").isEqualTo(1);
        assertThat(envelope.pagination().perPage())
                .as("per_page the envelope restates").isEqualTo(expectedSize);
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} is queried, its offset being at most 2147483647")
    @CsvSource({
            "214748365,10",
            "214748364,10",
            "2,1000"
    })
    @DisplayName("queries a page whose offset the paged query can express")
    void queriesAPageWhoseOffsetThePagedQueryCanExpress(int page, int perPage) {
        stubEveryWindowRead(2L);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(page, perPage);

        assertThat(pageRequestsIssued()).as("windows the page read asked for")
                .isNotEmpty()
                .allMatch(window -> window.getOffset() <= Integer.MAX_VALUE);
        assertThat(envelope.pagination().page()).isEqualTo(page);
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page {0} of size {1} is answered empty without a paged query")
    @CsvSource({
            "2147483647,10",
            "2147483646,10",
            "99999999,1000",
            "214748366,10",
            "2147485,1000"
    })
    @DisplayName("answers a page beyond the queryable offset with an empty page and no query")
    void answersAPageBeyondTheQueryableOffsetWithAnEmptyPageAndNoQuery(int page, int perPage) {
        when(responseRepository.count()).thenReturn(25L);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(page, perPage);

        assertThat(envelope.responses()).isEmpty();
        assertThat(envelope.pagination().page()).isEqualTo(page);
        assertThat(envelope.pagination().perPage()).isEqualTo(perPage);
        assertThat(envelope.pagination().total()).isEqualTo(25L);
        verify(responseRepository, never()).findAllRows(any(Pageable.class));
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("counts the pages a beyond-the-ceiling page reports from the table size")
    void countsThePagesABeyondTheCeilingPageReportsFromTheTableSize() {
        when(responseRepository.count()).thenReturn(25L);

        PaginationDto pagination =
                service.getPaginatedResponses(Integer.MAX_VALUE, 10).pagination();

        assertThat(pagination.totalPages()).isEqualTo(3);
        assertThat(pagination.total()).isEqualTo(25L);
    }

    // backend/app/api/responses.py:L17-20
    @Test
    @DisplayName("builds the pagination block from the page it read")
    void buildsThePaginationBlockFromThePageItRead() {
        Page<ResponseRow> read = pageOfStoredRows(0, 10, 25L);
        when(responseRepository.findAllRows(any(Pageable.class))).thenReturn(read);

        PaginationDto pagination = service.getPaginatedResponses(1, 10).pagination();

        assertThat(pagination.page()).isEqualTo(read.getNumber() + 1);
        assertThat(pagination.perPage()).isEqualTo(read.getSize());
        assertThat(pagination.total()).isEqualTo(read.getTotalElements());
        assertThat(pagination.totalPages()).isEqualTo(read.getTotalPages());

        assertThat(pagination.page()).isEqualTo(1);
        assertThat(pagination.perPage()).isEqualTo(10);
        assertThat(pagination.total()).isEqualTo(25L);
        assertThat(pagination.totalPages()).isEqualTo(3);
    }

    // backend/app/api/responses.py:L18
    @Test
    @DisplayName("takes the response list from the mapper")
    void takesTheResponseListFromTheMapper() {
        Page<ResponseRow> read = pageOfStoredRows(0, 10, 25L);
        List<ResponseDto> converted = List.of(storedDto());
        when(responseRepository.findAllRows(any(Pageable.class))).thenReturn(read);
        when(responseMapper.toDtoRowList(read.getContent())).thenReturn(converted);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(1, 10);

        assertThat(envelope.responses()).isSameAs(converted);
        verify(responseMapper).toDtoRowList(read.getContent());
    }

    // backend/app/api/responses.py:L26,L28-29
    @Test
    @DisplayName("returns the mapped row for an identifier that names a row")
    void returnsTheMappedRowForAnIdentifierThatNamesARow() {
        Response existing = storedRowCarryingApproval(false);
        ResponseDto mapped = storedDto();
        when(responseRepository.findById(RESPONSE_KEY)).thenReturn(Optional.of(existing));
        when(responseMapper.toDto(existing)).thenReturn(mapped);

        ResponseDto read = service.getResponseById(RESPONSE_ID);

        assertThat(read).isSameAs(mapped);
        assertThat(read.tweetId()).isEqualTo(TWEET_ID);
        verify(responseRepository, never()).save(any(Response.class));
    }

    @Test
    @DisplayName("holds no collaborator that can reach X")
    void holdsNoCollaboratorThatCanReachX() {
        Set<String> collaboratorTypes = new LinkedHashSet<>();
        for (Field field : ResponseService.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                collaboratorTypes.add(field.getType().getName());
            }
        }
        for (Constructor<?> constructor : ResponseService.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                collaboratorTypes.add(parameterType.getName());
            }
        }

        assertThat(carriesAnOutboundClient(
                "org.springframework.web.reactive.function.client.WebClient")).isTrue();
        assertThat(carriesAnOutboundClient("org.springframework.web.client.RestClient")).isTrue();
        assertThat(carriesAnOutboundClient("com.codeskeptic.scanner.service.TwitterService")).isTrue();

        assertThat(collaboratorTypes).isNotEmpty();
        assertThat(collaboratorTypes).allSatisfy(type -> assertThat(carriesAnOutboundClient(type))
                .as("collaborator type %s", type)
                .isFalse());
        assertThat(collaboratorTypes).containsExactlyInAnyOrder(
                ResponseRepository.class.getName(),
                TweetRepository.class.getName(),
                LlmService.class.getName(),
                ResponseMapper.class.getName(),
                TweetMapper.class.getName(),
                TransactionTemplate.class.getName(),
                // The in-process generation claim of DL-195 — a Set of integer identifiers, not a
                // collaborator
                Set.class.getName());
    }

    // The two claim-aware entry points the automatic paths share carry distinct names — DL-226 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("declares exactly six distinctly named operations and none of them publishes")
    void declaresExactlySixOperationsAndNoneOfThemPublishes() {
        List<Method> declared = Arrays.stream(ResponseService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();

        assertThat(namesAPublication("publishReply")).isTrue();
        assertThat(namesAPublication("postToX")).isTrue();
        assertThat(namesAPublication("sendTweet")).isTrue();

        assertThat(declared).isNotEmpty();
        List<Method> publicOperations = declared.stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(publicOperations)
                .extracting(Method::getName)
                .as("names of the public operations")
                .containsExactlyInAnyOrder("getPaginatedResponses", "getResponseById",
                        "generateResponse", "generateResponseIfAbsent", "generateResponseIfAbsentFor",
                        "updateResponse");
        // The operation both background paths call is declared once per subject form, under a name of
        // its own, so no name is overloaded and no call can be ambiguous — DL-226
        assertThat(publicOperations)
                .extracting(Method::getName)
                .as("names of the public operations")
                .doesNotHaveDuplicates();
        assertThat(publicOperations)
                .filteredOn(method -> "generateResponseIfAbsent".equals(method.getName()))
                .singleElement()
                .extracting(method -> method.getParameterTypes()[0])
                .isEqualTo(String.class);
        assertThat(publicOperations)
                .filteredOn(method -> "generateResponseIfAbsentFor".equals(method.getName()))
                .singleElement()
                .extracting(method -> method.getParameterTypes()[0])
                .isEqualTo(Tweet.class);
        assertThat(publicOperations).as("declared public operations").hasSize(6);
        assertThat(declared).allSatisfy(method -> assertThat(namesAPublication(method.getName()))
                .as("declared method %s", method.getName())
                .isFalse());
    }

    // backend/app/db/models.py:L26
    @Test
    @DisplayName("makes no outbound call when approval is granted")
    void makesNoOutboundCallWhenApprovalIsGranted() {
        Response existing = storedRowCarryingApproval(false);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID, new UpdateResponseRequest(null, BooleanNode.TRUE));

        assertThat(existing.getIsApproved()).isTrue();
        verifyNoInteractions(llmService, tweetRepository, tweetMapper);
        verify(responseRepository).findByIdForUpdate(RESPONSE_KEY);
        verify(responseRepository, never()).findById(RESPONSE_KEY);
        verify(responseRepository).save(existing);
        verifyNoMoreInteractions(responseRepository);
        verify(responseMapper).toDto(existing);
        verifyNoMoreInteractions(responseMapper);
    }

    // Pessimistic write lock ahead of a partial update — DL-122 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reads the row through the locked finder before writing either column")
    void readsTheRowThroughTheLockedFinderBeforeWritingEitherColumn() {
        Response existing = storedRowCarryingApproval(false);
        stubTheUpdateOf(existing);

        service.updateResponse(RESPONSE_ID,
                new UpdateResponseRequest(new TextNode("edited"), BooleanNode.TRUE));

        InOrder lockedUpdate = inOrder(responseRepository);
        lockedUpdate.verify(responseRepository).findByIdForUpdate(RESPONSE_KEY);
        lockedUpdate.verify(responseRepository).save(existing);
        lockedUpdate.verifyNoMoreInteractions();
        verify(responseRepository, never()).findById(RESPONSE_KEY);
    }

    // Pessimistic write lock ahead of a partial update — DL-122 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("declares the locked finder with a pessimistic write lock and the read finder without")
    void declaresTheLockedFinderWithAPessimisticWriteLockAndTheReadFinderWithout()
            throws NoSuchMethodException {
        Lock declared = ResponseRepository.class
                .getMethod("findByIdForUpdate", Integer.class)
                .getAnnotation(Lock.class);

        assertThat(declared).as("@Lock on findByIdForUpdate").isNotNull();
        assertThat(declared.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(ResponseRepository.class.getMethod("findAll", Pageable.class)
                .getAnnotation(Lock.class)).as("@Lock on the paged read").isNull();
    }

    // Pessimistic write lock ahead of a partial update — DL-122 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("takes no lock when the identifier carries no number")
    void takesNoLockWhenTheIdentifierCarriesNoNumber() {
        assertThatThrownBy(() -> service.updateResponse("not-a-number", contentOnly()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Response not found or update failed");

        verify(responseRepository, never()).findByIdForUpdate(any());
        verify(responseRepository, never()).save(any(Response.class));
    }

    private static boolean carriesAnOutboundClient(String typeName) {
        return typeName.contains("WebClient")
                || typeName.contains("RestClient")
                || typeName.contains("RestTemplate")
                || typeName.contains("HttpClient")
                || typeName.contains("OpenAIClient")
                || typeName.contains("TwitterService")
                || typeName.contains("TweetStream")
                || typeName.startsWith("com.openai")
                || typeName.startsWith("twitter4j")
                || typeName.startsWith("okhttp3");
    }

    private static boolean namesAPublication(String methodName) {
        String normalised = methodName.toLowerCase(Locale.ROOT);
        return normalised.contains("publish")
                || normalised.contains("post")
                || normalised.contains("send")
                || normalised.contains("reply")
                || normalised.contains("tweetback");
    }

    private static UpdateResponseRequest contentOnly() {
        return new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), null);
    }

    private static Tweet tweetCarryingTheKey() {
        Tweet tweet = new Tweet();
        tweet.setId(TWEET_KEY);
        tweet.setContent(TWEET_CONTENT);
        tweet.setLikeCount(250);
        tweet.setCreatedAt(TWEET_AT);
        tweet.setDoubtRating(7.5d);
        tweet.setUserId("99");
        tweet.setAiToolsMentioned(List.of("GitHub Copilot"));
        return tweet;
    }

    private static TweetDto tweetDto() {
        return new TweetDto(TWEET_ID, TWEET_CONTENT, 250, TWEET_AT, 7.5d, List.of(), null, "99",
                List.of("GitHub Copilot"));
    }

    private static Response storedRowCarryingApproval(Boolean approval) {
        Response response = new Response();
        response.setId(RESPONSE_KEY);
        response.setContent(STORED_CONTENT);
        response.setGeneratedAt(STORED_AT);
        response.setIsApproved(approval);
        response.setTweet(tweetCarryingTheKey());
        return response;
    }

    private static ResponseDto storedDto() {
        return new ResponseDto(RESPONSE_ID, STORED_CONTENT, STORED_AT, false, TWEET_ID);
    }

    private static Page<ResponseRow> pageOfStoredRows(int index, int size, long total) {
        return new PageImpl<>(List.of(projectedRow()), PageRequest.of(index, size), total);
    }

    private static ResponseRow projectedRow() {
        return new ResponseRow() {
            @Override
            public Integer getId() {
                return RESPONSE_KEY;
            }

            @Override
            public String getContent() {
                return STORED_CONTENT;
            }

            @Override
            public java.time.LocalDateTime getGeneratedAt() {
                return STORED_AT;
            }

            @Override
            public Boolean getIsApproved() {
                return Boolean.FALSE;
            }

            @Override
            public Integer getTweetId() {
                return TWEET_KEY;
            }
        };
    }

    private static Answer<Response> returnsTheRowWithAnAssignedId() {
        return invocation -> {
            Response submitted = invocation.getArgument(0);
            submitted.setId(RESPONSE_KEY);
            return submitted;
        };
    }

    private void stubTheUpdateOf(Response existing) {
        when(responseRepository.findByIdForUpdate(RESPONSE_KEY)).thenReturn(Optional.of(existing));
        when(responseRepository.save(existing)).thenReturn(existing);
        when(responseMapper.toDto(existing)).thenReturn(storedDto());
    }

    private Response theRowSubmittedForUpdate(Response existing) {
        ArgumentCaptor<Response> submitted = ArgumentCaptor.forClass(Response.class);
        verify(responseRepository).save(submitted.capture());
        assertThat(submitted.getValue()).isSameAs(existing);
        return submitted.getValue();
    }

    private Pageable theRequestedPage() {
        ArgumentCaptor<Pageable> requested = ArgumentCaptor.forClass(Pageable.class);
        verify(responseRepository).findAllRows(requested.capture());
        return requested.getValue();
    }

    // One diagnostic owner per failing layer — DL-252 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a provider failure at DEBUG only, leaving the ERROR record to the adapter")
    void recordsAProviderFailureAtDebugOnlyLeavingTheErrorRecordToTheAdapter() {
        Tweet subject = tweetCarryingTheKey();
        IllegalStateException providerFailure = new IllegalStateException("BLANK_TEXT");
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenThrow(providerFailure);

        List<ILoggingEvent> records =
                recordsOf(() -> catchThrowable(() -> service.generateResponse(TWEET_ID)));

        assertThat(records).noneMatch(record -> record.getLevel() == Level.ERROR);
        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.DEBUG)
                .hasSize(1)
                .allSatisfy(record -> assertThat(record.getFormattedMessage())
                        .contains("The generation provider failed for tweet")
                        .contains(IllegalStateException.class.getSimpleName())
                        .doesNotContain("BLANK_TEXT"));
    }

    // One diagnostic owner per failing layer — DL-252 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a repository failure on the route path once at ERROR, naming no statement")
    void recordsARepositoryFailureOnTheRoutePathOnceAtErrorNamingNoStatement() {
        Tweet subject = tweetCarryingTheKey();
        DataIntegrityViolationException rejectedInsert =
                new DataIntegrityViolationException("could not execute statement [23502]");
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenThrow(rejectedInsert);

        List<ILoggingEvent> records =
                recordsOf(() -> catchThrowable(() -> service.generateResponse(TWEET_ID)));

        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.ERROR)
                .hasSize(1)
                .allSatisfy(record -> assertThat(record.getFormattedMessage())
                        .contains("Generating a response for the requested tweet failed")
                        .contains(DataIntegrityViolationException.class.getSimpleName())
                        .doesNotContain("23502")
                        .doesNotContain("could not execute statement"));
    }

    // One diagnostic owner per failing layer — DL-252 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a provider failure on the background path at DEBUG and never at ERROR")
    void recordsAProviderFailureOnTheBackgroundPathAtDebugAndNeverAtError() {
        Tweet subject = tweetCarryingTheKey();
        IllegalStateException providerFailure = new IllegalStateException("REFUSAL");
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(llmService.generateResponse(any(TweetDto.class))).thenThrow(providerFailure);

        List<ILoggingEvent> records = recordsOf(
                () -> catchThrowable(() -> service.generateResponseIfAbsentFor(subject)));

        assertThat(records).noneMatch(record -> record.getLevel() == Level.ERROR);
        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.DEBUG)
                .anySatisfy(record -> assertThat(record.getFormattedMessage())
                        .contains("The generation provider failed for tweet")
                        .doesNotContain("REFUSAL"));
    }

    // One diagnostic owner per failing layer — DL-252 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("records a repository failure on the background path once at ERROR")
    void recordsARepositoryFailureOnTheBackgroundPathOnceAtError() {
        Tweet subject = tweetCarryingTheKey();
        DataIntegrityViolationException rejectedInsert =
                new DataIntegrityViolationException("could not execute statement [23502]");
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(tweetRepository.existsById(TWEET_KEY)).thenReturn(true);
        when(responseRepository.existsByTweetId(TWEET_KEY)).thenReturn(false);
        when(tweetRepository.findByIdForUpdate(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenThrow(rejectedInsert);

        List<ILoggingEvent> records = recordsOf(
                () -> catchThrowable(() -> service.generateResponseIfAbsentFor(subject)));

        assertThat(records)
                .filteredOn(record -> record.getLevel() == Level.ERROR)
                .hasSize(1)
                .allSatisfy(record -> assertThat(record.getFormattedMessage())
                        .contains("Response generation failed for tweet")
                        .contains(DataIntegrityViolationException.class.getSimpleName())
                        .doesNotContain("23502"));
    }

    private static List<ILoggingEvent> recordsOf(Runnable call) {
        ch.qos.logback.classic.Logger serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ResponseService.class);
        Level restoreLevel = serviceLogger.getLevel();
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        serviceLogger.addAppender(records);
        serviceLogger.setLevel(Level.DEBUG);
        try {
            call.run();
        } finally {
            serviceLogger.setLevel(restoreLevel);
            serviceLogger.detachAppender(records);
            records.stop();
        }
        return List.copyOf(records.list);
    }

    private void stubEveryWindowRead(long total) {
        lenient().when(responseRepository.findAllRows(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.<Pageable>getArgument(0), total));
        lenient().when(responseRepository.count()).thenReturn(total);
    }

    private List<Pageable> pageRequestsIssued() {
        return mockingDetails(responseRepository).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .filter(Pageable.class::isInstance)
                .map(Pageable.class::cast)
                .toList();
    }

    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private static ListAppender<ILoggingEvent> attachLogRecorder() {
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        ((Logger) org.slf4j.LoggerFactory.getLogger(ResponseService.class)).addAppender(records);
        return records;
    }

    private static void detachLogRecorder(ListAppender<ILoggingEvent> records) {
        ((Logger) org.slf4j.LoggerFactory.getLogger(ResponseService.class)).detachAppender(records);
        records.stop();
    }

    private static List<String> renderedRecords(ListAppender<ILoggingEvent> records) {
        return records.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
