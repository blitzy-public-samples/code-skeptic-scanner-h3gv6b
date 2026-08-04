package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /responses}.
 *
 * <p>A two-key envelope. The keys are {@code responses} and {@code pagination}, in the declaration
 * order of the source dictionary ({@code backend/app/api/responses.py:L18-19}). Keys are snake_case
 * at both levels. This type is outbound only and declares no validation constraint.
 *
 * <p>The nested {@code pagination} object's keys are {@code page}, {@code per_page}, {@code total}
 * and {@code total_pages}, declared by {@link PaginationDto} — see docs/DECISION_LOG.md DL-038. Its
 * {@code page} value is 1-based, matching the {@code page} query parameter the route reads with a
 * default of 1 ({@code backend/app/api/responses.py:L11}); the {@code per_page} parameter defaults
 * to 10 ({@code backend/app/api/responses.py:L12}). The 1-based-to-0-based conversion against Spring
 * Data is performed by {@code service/ResponseService}, not by this record.
 *
 * <p>The list element type is {@link ResponseDto} from this package — the wire shape of a
 * {@code responses} table row, not a framework response type. The source class name is retained —
 * see docs/DECISION_LOG.md DL-025.
 *
 * <p>Page 1 of 10 per page over a single row serialises as:
 *
 * <pre>{@code
 * {"responses":[{"id":"1","content":"...","generated_at":"2026-01-31T09:15:00",
 *                "is_approved":false,"tweet_id":"7"}],
 *  "pagination":{"page":1,"per_page":10,"total":1,"total_pages":1}}
 * }</pre>
 *
 * <p>An empty page serialises {@code responses} as an empty array, never {@code null}:
 * {@code {"responses":[],"pagination":{"page":1,"per_page":10,"total":0,"total_pages":0}}}. The list
 * is supplied by {@code service/ResponseService}.
 *
 * @param responses the page of mapped replies, one {@link ResponseDto} per row, in the order the
 *     query returned them ({@code backend/app/api/responses.py:L18})
 * @param pagination the page counters describing this result
 *     ({@code backend/app/api/responses.py:L19})
 */
// Ported from backend/app/api/responses.py:L17-20 (faithful port of the GET /responses envelope) — see docs/DECISION_LOG.md
public record PaginatedResponsesDto(

        @JsonProperty("responses") List<ResponseDto> responses,

        @JsonProperty("pagination") PaginationDto pagination) {
}
