package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

// Net-new (no Python counterpart; backend/app/api/responses.py:L3 imports a service that exists
// nowhere in the repository) — see docs/DECISION_LOG.md
/**
 * Exercises the four operations of {@link ResponseService}: the page it lists, the row it reads, the
 * row it generates and the row it updates.
 *
 * <p>Five client-visible messages are reachable from this class, and each is asserted as its own exact
 * string: {@code Tweet ID is required}, {@code Response not found},
 * {@code Failed to generate response}, {@code Update data is required} and
 * {@code Response not found or update failed}. The two {@link NotFoundException} messages are also
 * asserted against each other. No test here asserts an HTTP status; {@code api.GlobalExceptionHandler}
 * selects those and {@code api.ResponseControllerTest} asserts them.
 *
 * <p>Every collaborator is a Mockito double. No Spring context is started, and no network, database,
 * filesystem or credential resource is reached. {@link LlmService} is never called for real.
 *
 * <p>Construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseService")
class ResponseServiceTest {

    /** Raw path form of the {@code tweets} row every generation test replies to. */
    private static final String TWEET_ID = "4711";

    /** Numeric form of {@link #TWEET_ID}, the value {@link TweetRepository} is queried with. */
    private static final int TWEET_KEY = 4711;

    /** Raw path form of the {@code responses} row the read and update tests address. */
    private static final String RESPONSE_ID = "88";

    /** Numeric form of {@link #RESPONSE_ID}, the value {@link ResponseRepository} is queried with. */
    private static final int RESPONSE_KEY = 88;

    /** A raw path segment that carries no number. */
    private static final String UNPARSEABLE_ID = "eighty-eight";

    /** Text {@link LlmService} returns for an accepted generation. */
    private static final String GENERATED_TEXT = "Even seasoned reviewers disagree.";

    /** Content the stored {@code responses} row carries before an update. */
    private static final String STORED_CONTENT = "Reviewers still disagree about the generated diff.";

    /** Content an update writes over {@link #STORED_CONTENT}. */
    private static final String REVISED_CONTENT = "Two reviewers read the generated diff line by line.";

    /** Generation time the stored {@code responses} row carries. */
    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 1, 31, 9, 16);

    /** Creation time the replied-to {@code tweets} row carries. */
    private static final LocalDateTime TWEET_AT = LocalDateTime.of(2026, 1, 31, 9, 15);

    /** Text of the replied-to {@code tweets} row. */
    private static final String TWEET_CONTENT = "Nobody reviews what the assistant writes.";

    /** The message {@code api.GlobalExceptionHandler} renders for an unmapped failure. */
    private static final String CATCH_ALL_MESSAGE = "Internal server error";

    /** The message {@code api.GlobalExceptionHandler} renders for an unmapped route. */
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
    @DisplayName("rejects an update whose body carries neither writable key")
    void rejectsAnUpdateWhoseBodyCarriesNeitherWritableKey() {
        UpdateResponseRequest emptyBody = new UpdateResponseRequest(null, null);

        assertThat(emptyBody.carriesNoUpdatableField()).isTrue();
        assertThatThrownBy(() -> service.updateResponse(RESPONSE_ID, emptyBody))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Update data is required");

        verifyNoInteractions(responseRepository, responseMapper);
    }

    // backend/app/api/responses.py:L62-65
    @Test
    @DisplayName("reports that the response was not found or the update failed when it names no row")
    void reportsThatTheResponseWasNotFoundOrTheUpdateFailedWhenItNamesNoRow() {
        when(responseRepository.findById(RESPONSE_KEY)).thenReturn(Optional.empty());

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

    // backend/app/api/responses.py:L44,L46-47
    @Test
    @DisplayName("stores the generated text as an unapproved row and returns the stored row")
    void storesTheGeneratedTextAsAnUnapprovedRowAndReturnsTheStoredRow() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
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

        LocalDateTime beforeTheCall = LocalDateTime.now();
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
        orchestration.verify(tweetRepository).findById(TWEET_KEY);
        orchestration.verify(responseRepository).save(any(Response.class));
        orchestration.verify(responseMapper).toDto(any(Response.class));
        orchestration.verifyNoMoreInteractions();
    }

    // backend/app/db/models.py:L26-28 with backend/app/api/responses.py:L44
    @Test
    @DisplayName("associates the stored row with the loaded tweet and stores it unapproved")
    void associatesTheStoredRowWithTheLoadedTweetAndStoresItUnapproved() {
        Tweet subject = tweetCarryingTheKey();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
        when(tweetMapper.toDto(subject)).thenReturn(tweetDto());
        when(llmService.generateResponse(any(TweetDto.class))).thenReturn(GENERATED_TEXT);
        when(responseRepository.save(any(Response.class))).thenAnswer(returnsTheRowWithAnAssignedId());
        when(responseMapper.toDto(any(Response.class))).thenReturn(storedDto());

        LocalDateTime beforeTheCall = LocalDateTime.now();
        service.generateResponse(TWEET_ID);
        LocalDateTime afterTheCall = LocalDateTime.now();

        ArgumentCaptor<Response> submitted = ArgumentCaptor.forClass(Response.class);
        verify(responseRepository).save(submitted.capture());
        Response written = submitted.getValue();

        assertThat(written.getIsApproved()).isNotNull();
        assertThat(written.getIsApproved()).isFalse();
        assertThat(written.getGeneratedAt()).isNotNull();
        assertThat(written.getGeneratedAt()).isBetween(beforeTheCall, afterTheCall);
        assertThat(written.getTweet()).isSameAs(subject);
        assertThat(written.getContent()).isEqualTo(GENERATED_TEXT);
    }

    // backend/app/tasks/response_generation.py:L25-26,L33
    @Test
    @DisplayName("returns the row the mapper built from the stored entity")
    void returnsTheRowTheMapperBuiltFromTheStoredEntity() {
        Tweet subject = tweetCarryingTheKey();
        ResponseDto mapped = storedDto();
        when(tweetRepository.findById(TWEET_KEY)).thenReturn(Optional.of(subject));
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

    // backend/app/db/models.py:L26 with backend/app/api/responses.py:L60
    @Test
    @DisplayName("leaves approval untouched when the body omits it")
    void leavesApprovalUntouchedWhenTheBodyOmitsIt() {
        Response existing = storedRowCarryingApproval(true);
        UpdateResponseRequest withoutApproval = contentOnly();
        stubTheUpdateOf(existing);

        assertThat(withoutApproval.approvalPresent()).isFalse();
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

        assertThat(revokingApproval.approvalPresent()).isTrue();
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
        when(responseRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOfStoredRows(0, 10, 25L));

        service.getPaginatedResponses(1, 10);

        Pageable requested = theRequestedPage();
        assertThat(requested.getPageNumber()).isZero();
        assertThat(requested.getPageSize()).isEqualTo(10);
    }

    // backend/app/api/responses.py:L11,L15
    @Test
    @DisplayName("requests the third wire page as index two and reports it as page three")
    void requestsTheThirdWirePageAsIndexTwoAndReportsItAsPageThree() {
        when(responseRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOfStoredRows(2, 10, 25L));

        PaginatedResponsesDto envelope = service.getPaginatedResponses(3, 10);

        Pageable requested = theRequestedPage();
        assertThat(requested.getPageNumber()).isEqualTo(2);
        assertThat(requested.getPageSize()).isEqualTo(10);
        assertThat(envelope.pagination().page()).isEqualTo(3);
    }

    // backend/app/api/responses.py:L17-20
    @Test
    @DisplayName("builds the pagination block from the page it read")
    void buildsThePaginationBlockFromThePageItRead() {
        Page<Response> read = pageOfStoredRows(0, 10, 25L);
        when(responseRepository.findAll(any(Pageable.class))).thenReturn(read);

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
        Page<Response> read = pageOfStoredRows(0, 10, 25L);
        List<ResponseDto> converted = List.of(storedDto());
        when(responseRepository.findAll(any(Pageable.class))).thenReturn(read);
        when(responseMapper.toDtoList(read.getContent())).thenReturn(converted);

        PaginatedResponsesDto envelope = service.getPaginatedResponses(1, 10);

        assertThat(envelope.responses()).isSameAs(converted);
        verify(responseMapper).toDtoList(read.getContent());
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
                TransactionTemplate.class.getName());
    }

    @Test
    @DisplayName("declares exactly four operations and none of them publishes")
    void declaresExactlyFourOperationsAndNoneOfThemPublishes() {
        List<Method> declared = Arrays.stream(ResponseService.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();

        assertThat(namesAPublication("publishReply")).isTrue();
        assertThat(namesAPublication("postToX")).isTrue();
        assertThat(namesAPublication("sendTweet")).isTrue();

        assertThat(declared).isNotEmpty();
        assertThat(declared)
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("getPaginatedResponses", "getResponseById",
                        "generateResponse", "updateResponse");
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
        verify(responseRepository).findById(RESPONSE_KEY);
        verify(responseRepository).save(existing);
        verifyNoMoreInteractions(responseRepository);
        verify(responseMapper).toDto(existing);
        verifyNoMoreInteractions(responseMapper);
    }

    /**
     * Reports whether a type name belongs to a client that performs outbound HTTP calls.
     *
     * @param typeName the fully qualified name of a declared field or constructor parameter type
     * @return {@code true} when the name matches an outbound client
     */
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

    /**
     * Reports whether a method name names an operation that sends content outward.
     *
     * @param methodName the simple name of a declared method
     * @return {@code true} when the name matches a publishing verb
     */
    private static boolean namesAPublication(String methodName) {
        String normalised = methodName.toLowerCase(Locale.ROOT);
        return normalised.contains("publish")
                || normalised.contains("post")
                || normalised.contains("send")
                || normalised.contains("reply")
                || normalised.contains("tweetback");
    }

    /**
     * Builds a request body carrying the {@code content} key alone.
     *
     * @return a request whose {@code content} is {@link #REVISED_CONTENT} and whose approval key is
     *         absent
     */
    private static UpdateResponseRequest contentOnly() {
        return new UpdateResponseRequest(TextNode.valueOf(REVISED_CONTENT), null);
    }

    /**
     * Builds the {@code tweets} row every generation test replies to.
     *
     * @return an entity carrying {@link #TWEET_KEY} as its identifier
     */
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

    /**
     * Builds the wire form of the replied-to post.
     *
     * @return a DTO carrying {@link #TWEET_ID} as its identifier
     */
    private static TweetDto tweetDto() {
        return new TweetDto(TWEET_ID, TWEET_CONTENT, 250, TWEET_AT, 7.5d, List.of(), null, "99",
                List.of("GitHub Copilot"));
    }

    /**
     * Builds a stored {@code responses} row carrying the given approval flag.
     *
     * @param approval the value of the {@code is_approved} column
     * @return an entity carrying {@link #RESPONSE_KEY}, {@link #STORED_CONTENT}, {@link #STORED_AT} and
     *         an association to {@link #tweetCarryingTheKey()}
     */
    private static Response storedRowCarryingApproval(Boolean approval) {
        Response response = new Response();
        response.setId(RESPONSE_KEY);
        response.setContent(STORED_CONTENT);
        response.setGeneratedAt(STORED_AT);
        response.setIsApproved(approval);
        response.setTweet(tweetCarryingTheKey());
        return response;
    }

    /**
     * Builds the wire form of the stored {@code responses} row.
     *
     * @return a DTO carrying {@link #RESPONSE_ID} as its identifier
     */
    private static ResponseDto storedDto() {
        return new ResponseDto(RESPONSE_ID, STORED_CONTENT, STORED_AT, false, TWEET_ID);
    }

    /**
     * Builds a page of stored {@code responses} rows.
     *
     * @param index the 0-based index the page reports
     * @param size  the number of rows a full page holds
     * @param total the number of rows the table holds
     * @return a page carrying one stored row
     */
    private static Page<Response> pageOfStoredRows(int index, int size, long total) {
        return new PageImpl<>(List.of(storedRowCarryingApproval(false)), PageRequest.of(index, size),
                total);
    }

    /**
     * Answers a {@code save} call by assigning {@link #RESPONSE_KEY} to the submitted row.
     *
     * @return an answer returning the submitted row with an identifier assigned
     */
    private static Answer<Response> returnsTheRowWithAnAssignedId() {
        return invocation -> {
            Response submitted = invocation.getArgument(0);
            submitted.setId(RESPONSE_KEY);
            return submitted;
        };
    }

    /**
     * Stubs the read, the write and the conversion an accepted update performs on a stored row.
     *
     * @param existing the row {@link ResponseRepository} returns and receives back
     */
    private void stubTheUpdateOf(Response existing) {
        when(responseRepository.findById(RESPONSE_KEY)).thenReturn(Optional.of(existing));
        when(responseRepository.save(existing)).thenReturn(existing);
        when(responseMapper.toDto(existing)).thenReturn(storedDto());
    }

    /**
     * Captures the row an accepted update submitted to {@link ResponseRepository}.
     *
     * @param existing the row the repository returned for the read
     * @return the captured row, which is {@code existing}
     */
    private Response theRowSubmittedForUpdate(Response existing) {
        ArgumentCaptor<Response> submitted = ArgumentCaptor.forClass(Response.class);
        verify(responseRepository).save(submitted.capture());
        assertThat(submitted.getValue()).isSameAs(existing);
        return submitted.getValue();
    }

    /**
     * Captures the {@link Pageable} a listing submitted to {@link ResponseRepository}.
     *
     * @return the captured page request
     */
    private Pageable theRequestedPage() {
        ArgumentCaptor<Pageable> requested = ArgumentCaptor.forClass(Pageable.class);
        verify(responseRepository).findAll(requested.capture());
        return requested.getValue();
    }

    /**
     * Builds a {@link TransactionTemplate} whose callback runs on the calling thread against a
     * transaction manager that starts, commits and rolls back nothing.
     *
     * @return a template that executes its callback inline
     */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // No transaction is started, and nothing is committed.
            }

            @Override
            public void rollback(TransactionStatus status) {
                // No transaction is started, and nothing is rolled back.
            }
        });
    }
}
