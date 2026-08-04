package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shape of the {@code pagination} sub-object carried by both list envelopes,
 * {@code PaginatedTweetsDto} and {@code PaginatedResponsesDto}.
 *
 * <p>The serialised keys are {@code page}, {@code per_page}, {@code total} and
 * {@code total_pages}. The Flask handlers this envelope replaces read {@code page} with a
 * default of 1 and {@code per_page} with a default of 10
 * (backend/app/api/tweets.py:L12-13 and backend/app/api/responses.py:L11-12).
 *
 * <p>Mapping from a Spring Data {@code Page}, applied by the services and mappers:
 * <ul>
 *   <li>{@code page} = {@code getNumber() + 1}</li>
 *   <li>{@code per_page} = {@code getSize()}</li>
 *   <li>{@code total} = {@code getTotalElements()}</li>
 *   <li>{@code total_pages} = {@code getTotalPages()}</li>
 * </ul>
 *
 * <p>{@code page} is 1-based on the wire and 0-based in Spring Data. The outbound half of
 * that conversion is the {@code getNumber() + 1} above; the inbound half,
 * {@code PageRequest.of(page - 1, perPage)}, is applied by the services.
 *
 * <p>{@code total} is typed {@code Long}, matching the {@code long} returned by
 * {@code Page#getTotalElements()}; the other three components are {@code Integer}.
 *
 * <p>Page 1 of 10 per page over 25 rows serialises as
 * {@code {"page":1,"per_page":10,"total":25,"total_pages":3}}.
 */
// Envelope sub-object for backend/app/api/tweets.py:L20 and backend/app/api/responses.py:L19; keys defined here (producing methods absent in source) — see docs/DECISION_LOG.md DL-038
public record PaginationDto(

        @JsonProperty("page")
        Integer page,

        @JsonProperty("per_page")
        Integer perPage,

        @JsonProperty("total")
        Long total,

        @JsonProperty("total_pages")
        Integer totalPages) {
}
