package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /tweets}.
 *
 * <p>A two-key envelope. The keys are {@code tweets} and {@code pagination}, in the declaration
 * order of the source dictionary ({@code backend/app/api/tweets.py:L19-20}). Keys are snake_case at
 * both levels. This type is outbound only and declares no validation constraint.
 *
 * <p>The nested {@code pagination} object's keys are {@code page}, {@code per_page}, {@code total}
 * and {@code total_pages}, declared by {@link PaginationDto} — see docs/DECISION_LOG.md DL-038. Its
 * {@code page} value is 1-based, matching the {@code page} query parameter the route reads with a
 * default of 1 ({@code backend/app/api/tweets.py:L12}); the {@code per_page} parameter defaults to 10
 * ({@code backend/app/api/tweets.py:L13}). The 1-based-to-0-based conversion against Spring Data is
 * performed by {@code service/TwitterService}, not by this record.
 *
 * <p>Page 1 of 10 per page over 25 rows serialises as:
 *
 * <pre>{@code
 * {"tweets":[{"id":"1","content":"...","like_count":120,"created_at":"2026-08-01T12:00:00",
 *             "doubt_rating":6.5,"media":[],"quoted_tweet_id":null,"user_id":"42",
 *             "ai_tools_mentioned":["GPT-4"]}],
 *  "pagination":{"page":1,"per_page":10,"total":25,"total_pages":3}}
 * }</pre>
 *
 * <p>An empty page serialises {@code tweets} as an empty array, never {@code null}:
 * {@code {"tweets":[],"pagination":{"page":1,"per_page":10,"total":0,"total_pages":0}}}. The list is
 * supplied by {@code service/TwitterService}.
 *
 * @param tweets the page of mapped posts, one {@link TweetDto} per row, in the order the query
 *     returned them ({@code backend/app/api/tweets.py:L19})
 * @param pagination the page counters describing this result
 *     ({@code backend/app/api/tweets.py:L20})
 */
// Ported from backend/app/api/tweets.py:L18-21 (faithful port of the GET /tweets envelope) — see docs/DECISION_LOG.md
public record PaginatedTweetsDto(

        @JsonProperty("tweets") List<TweetDto> tweets,

        @JsonProperty("pagination") PaginationDto pagination) {
}
