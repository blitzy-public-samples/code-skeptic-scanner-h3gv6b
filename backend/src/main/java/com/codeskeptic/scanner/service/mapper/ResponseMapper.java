package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;

// Net-new (no Python counterpart method) — call sites backend/app/api/responses.py:L18,L29,L47,L63 —
// DL-023, DL-080 and DL-081 — see docs/DECISION_LOG.md
/**
 * Converts {@link Response} entities into their {@link ResponseDto} wire form.
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
 * <p>Null policy — see docs/DECISION_LOG.md DL-080 and DL-081. The entity columns remain nullable,
 * but a row carrying no {@code id}, {@code content}, {@code generated_at}, {@code is_approved} or
 * associated {@code tweet_id} has no {@link ResponseDto} wire form and is rejected here. No neutral
 * value is substituted.
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
     *         tweet identifier each rendered as a string; or {@code null} when {@code response} is
     *         {@code null}
     * @throws IllegalStateException if the entity carries {@code null} in any column the wire
     *         contract of {@code backend/app/schema/response.py:L5-9} declares required
     */
    public ResponseDto toDto(Response response) {
        if (response == null) {
            return null;
        }
        Integer identifier = response.getId();
        if (identifier == null) {
            throw new IllegalStateException(
                    "A response that carries no identifier has not been stored and has no wire form.");
        }
        Tweet tweet = required(response.getTweet(), "tweet_id");
        return new ResponseDto(
                String.valueOf(identifier),
                required(response.getContent(), "content"),
                required(response.getGeneratedAt(), "generated_at"),
                required(response.getIsApproved(), "is_approved"),
                identifierAsString(required(tweet.getId(), "tweet_id")));
    }

    /**
     * Converts a list of response entities into their wire form, preserving the order of the input.
     *
     * @param responses the entities to convert, may be {@code null}, may be empty and may contain
     *                  {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code responses} is
     *         {@code null} or empty. Never {@code null}
     * @throws IllegalStateException if an element carries no value for a source-required wire field
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
     * Renders a persistent identifier as its decimal string form.
     *
     * @param identifier the identifier to render, never {@code null}
     * @return the decimal string form of {@code identifier}
     */
    private static String identifierAsString(Integer identifier) {
        return String.valueOf(identifier);
    }

    // The required fields of backend/app/schema/response.py:L5-9 — DL-080, DL-081 — see
    // docs/DECISION_LOG.md
    /**
     * Returns a column value the wire contract declares required.
     *
     * @param value  the stored column value, or the association carrying it
     * @param column the wire key of the column, used in the failure message
     * @param <T>    the column's value type
     * @return {@code value}
     * @throws IllegalStateException if {@code value} is {@code null}
     */
    private static <T> T required(T value, String column) {
        if (value == null) {
            throw new IllegalStateException("A responses row carrying no " + column
                    + " has no wire form: backend/app/schema/response.py declares the field "
                    + "required.");
        }
        return value;
    }

}
