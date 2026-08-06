package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.ResponseRepository.ResponseRow;

// Net-new (no Python counterpart method) — call sites backend/app/api/responses.py:L18,L29,L47,L63 —
// DL-023, DL-080, DL-081 — see docs/DECISION_LOG.md
/**
 * Converts {@link Response} entities and {@link ResponseRow} page projections into their
 * {@link ResponseDto} wire form.
 *
 * <p>A conversion copies the five {@code responses} columns in the source declaration order —
 * {@code id}, {@code content}, {@code generated_at}, {@code is_approved} and {@code tweet_id} — and
 * applies one transformation, twice: an {@link Integer} identifier becomes its decimal
 * {@link String}
 * form.
 *
 * <p>The {@code tweet_id} value is read through the {@code tweet} association, which is
 * {@link Response}'s single mapping of that column. Nothing else is read from the association.
 *
 * <p>The remaining three components are copied verbatim after the source-required wire fields are
 * validated.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080. Every one of the five
 * {@code responses} columns is declared without {@code nullable=false}, so a stored row may carry
 * {@code null} in any of them, and this class carries a {@code null} column value through as a
 * {@code null} component. No conversion here unboxes a column value, defaults a component,
 * substitutes a neutral value or rejects a column value; the requirement is declared in exactly one
 * place, {@link ResponseDto}, which rejects a {@code null} for each of the five components the wire
 * contract of {@code backend/app/schema/response.py:L5-9} declares required. Converting a row that
 * leaves such a column empty therefore fails in the record's constructor rather than here. An absent
 * {@code tweet} association yields a {@code null} {@code tweet_id} rather than dereferencing the
 * association.
 *
 * <p>Conversion runs in one direction: this mapper declares no entity-producing operation and
 * performs no persistence access and no outbound call. Instances hold no state and are thread-safe.
 */
@Component
public final class ResponseMapper {

    /**
     * Converts a single stored response entity into its wire form.
     *
     * @param response the entity to convert, may be {@code null}
     * @return a DTO holding the entity's five column values, with the identifier and the associated
     *         tweet identifier each rendered as a string and every {@code null} column carried as a
     *         {@code null} component; or {@code null} when {@code response} is {@code null}
     */
    public ResponseDto toDto(Response response) {
        if (response == null) {
            return null;
        }
        Tweet tweet = response.getTweet();
        String tweetId = (tweet == null) ? null : identifierAsString(tweet.getId());
        return new ResponseDto(
                identifierAsString(response.getId()),
                response.getContent(),
                response.getGeneratedAt(),
                response.getIsApproved(),
                tweetId);
    }

    /**
     * Converts a list of response entities into their wire form, preserving the order of the input.
     *
     * @param responses the entities to convert, may be {@code null}, may be empty and may contain
     *                  {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code responses} is
     *         {@code null} or empty. Never {@code null}
     */
    public List<ResponseDto> toDtoList(List<Response> responses) {
        if (responses == null || responses.isEmpty()) {
            return List.of();
        }
        return responses.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Converts a projected page row into its wire form.
     *
     * <p>The projection already carries {@code responses.tweet_id} as a value, so no association is
     * traversed here — see docs/DECISION_LOG.md DL-245.
     *
     * @param row the projected row to convert, may be {@code null}
     * @return a DTO holding the row's five values, with both identifiers rendered as strings and every
     *         {@code null} value carried as a {@code null} component; or {@code null} when
     *         {@code row} is {@code null}
     */
    public ResponseDto toDto(ResponseRow row) {
        if (row == null) {
            return null;
        }
        return new ResponseDto(
                identifierAsString(row.getId()),
                row.getContent(),
                row.getGeneratedAt(),
                row.getIsApproved(),
                identifierAsString(row.getTweetId()));
    }

    /**
     * Converts a list of projected page rows into their wire form, preserving the order of the input.
     *
     * @param rows the projected rows to convert, may be {@code null}, may be empty and may contain
     *             {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code rows} is
     *         {@code null} or empty. Never {@code null}
     */
    public List<ResponseDto> toDtoRowList(List<ResponseRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Renders a persistent identifier as its decimal string form.
     *
     * @param identifier the identifier to render, may be {@code null}
     * @return the decimal string form of {@code identifier}, or {@code null} when {@code identifier}
     *         is {@code null}
     */
    private static String identifierAsString(Integer identifier) {
        return (identifier == null) ? null : identifier.toString();
    }

}
