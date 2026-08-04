package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;

// Net-new (no Python counterpart method) — call sites backend/app/api/responses.py:L18,L29,L47,L63 —
// DL-023 — see docs/DECISION_LOG.md
/**
 * Converts {@link Response} entities into their {@link ResponseDto} wire form.
 *
 * <p>A conversion copies the five {@code responses} columns in the source declaration order —
 * {@code id}, {@code content}, {@code generated_at}, {@code is_approved} and {@code tweet_id} — and
 * applies one transformation, twice: a {@link Long} identifier becomes its decimal {@link String}
 * form, and becomes {@code null} when no identifier is held.
 *
 * <p>The {@code tweet_id} value is read through the {@code tweet} association, which is
 * {@link Response}'s single mapping of that column. Both hops are guarded: the value is {@code null}
 * when the association is absent, and {@code null} when the associated {@link Tweet} carries no
 * identifier. Nothing else is read from the association: a conversion builds no nested tweet.
 *
 * <p>The remaining three components are copied verbatim. {@code content} is not trimmed or re-cased;
 * {@code generatedAt} is neither formatted nor shifted to another time zone and is never replaced
 * with the current time; {@code isApproved} is carried across as it stands, neither defaulted nor
 * read as a condition.
 *
 * <p>Conversion runs in one direction only: this mapper declares no entity-producing operation, and
 * it performs no persistence access and no outbound call. Instances hold no state and are
 * thread-safe.
 */
@Component
public final class ResponseMapper {

    /**
     * Converts a single response entity into its wire form.
     *
     * @param response the entity to convert, may be {@code null}
     * @return a DTO holding the entity's five column values, with the identifier and the associated
     *         tweet identifier each rendered as a string; or {@code null} when {@code response} is
     *         {@code null}
     * @throws NullPointerException if the entity holds {@code null} where {@link ResponseDto}
     *         requires a value
     */
    public ResponseDto toDto(Response response) {
        if (response == null) {
            return null;
        }
        Tweet tweet = response.getTweet();
        return new ResponseDto(
                identifierAsString(response.getId()),
                response.getContent(),
                response.getGeneratedAt(),
                response.getIsApproved(),
                tweet == null ? null : identifierAsString(tweet.getId()));
    }

    /**
     * Converts a list of response entities into their wire form, preserving the order of the input.
     *
     * @param responses the entities to convert, may be {@code null}, may be empty and may contain
     *                  {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code responses} is
     *         {@code null} or empty. Never {@code null}
     * @throws NullPointerException if an element holds {@code null} where {@link ResponseDto}
     *         requires a value
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
     * @param identifier the identifier to render, may be {@code null}
     * @return the decimal string form of {@code identifier}, or {@code null} when
     *         {@code identifier} is {@code null}; never the four-character text {@code "null"}
     */
    private static String identifierAsString(Long identifier) {
        return identifier == null ? null : String.valueOf(identifier);
    }
}
