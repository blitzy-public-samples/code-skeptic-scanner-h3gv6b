package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /responses}: the two-key envelope
 * {@code responses} and {@code pagination}, in the declaration order of the source dictionary at
 * {@code backend/app/api/responses.py:L18-19}, snake_case at both levels — DL-022.
 *
 * <p>The nested object's keys are {@code page}, {@code per_page}, {@code total} and
 * {@code total_pages}, declared by {@link PaginationDto} — DL-038. {@code page} is 1-based on the wire,
 * matching the query parameter of {@code :L11} whose default is 1, and {@code per_page} defaults to 10
 * ({@code :L12}); {@code service/ResponseService} performs the 1-based-to-0-based conversion.
 *
 * <p>The list element type is {@link ResponseDto}, the wire shape of a {@code responses} table row; the
 * source class name is retained — DL-025.
 *
 * <pre>{@code
 * {"responses":[{"id":"1","content":"...","generated_at":"2026-01-31T09:15:00",
 *                "is_approved":false,"tweet_id":"7"}],
 *  "pagination":{"page":1,"per_page":10,"total":1,"total_pages":1}}
 * }</pre>
 *
 * <p>An empty page serialises {@code responses} as an empty array.
 *
 * @param responses  the page of mapped replies, in the order the query returned them ({@code :L18})
 * @param pagination the page counters describing this result ({@code :L19})
 */
// Ported from backend/app/api/responses.py:L17-20 (faithful port of the GET /responses envelope) — see docs/DECISION_LOG.md
public record PaginatedResponsesDto(

        @JsonProperty("responses") List<ResponseDto> responses,

        @JsonProperty("pagination") PaginationDto pagination) {
}
