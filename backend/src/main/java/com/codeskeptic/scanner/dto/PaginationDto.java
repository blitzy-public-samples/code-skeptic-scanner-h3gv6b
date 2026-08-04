package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shape of the {@code pagination} sub-object of the two list envelopes.
 *
 * <p>The serialised keys are {@code page}, {@code per_page}, {@code total} and {@code total_pages}.
 * {@code page} is 1-based on the wire and 0-based in Spring Data, so the outbound value is
 * {@code Page#getNumber() + 1} and the inbound request is {@code PageRequest.of(page - 1, perPage)}.
 * The source handlers read {@code page} with a default of 1 and {@code per_page} with a default of 10
 * ({@code backend/app/api/tweets.py:L12-13}, {@code backend/app/api/responses.py:L11-12}).
 *
 * <p>Page 1 of 10 per page over 25 rows serialises as
 * {@code {"page":1,"per_page":10,"total":25,"total_pages":3}}.
 *
 * @param page       1-based page number, from {@code Page#getNumber() + 1}
 * @param perPage    page size, from {@code Page#getSize()}
 * @param total      total matching rows, from {@code Page#getTotalElements()}; typed {@code Long} to
 *                   match its {@code long} return
 * @param totalPages total page count, from {@code Page#getTotalPages()}
 */
// Net-new (the producing methods were absent from the source; the envelope slot exists at
// backend/app/api/tweets.py:L20 and backend/app/api/responses.py:L19, and the keys are defined
// here) — see docs/DECISION_LOG.md DL-038
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
