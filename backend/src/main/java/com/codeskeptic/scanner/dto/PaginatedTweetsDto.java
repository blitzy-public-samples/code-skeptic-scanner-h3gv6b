package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire contract for the {@code 200 OK} body of {@code GET /tweets}: the two-key envelope
 * {@code tweets} and {@code pagination}, in the declaration order of the source dictionary at
 * {@code backend/app/api/tweets.py:L19-20}, snake_case at both levels — DL-022.
 *
 * <p>The nested object's keys are {@code page}, {@code per_page}, {@code total} and
 * {@code total_pages}, declared by {@link PaginationDto} — DL-038. {@code page} is 1-based on the wire,
 * matching the query parameter of {@code :L12} whose default is 1, and {@code per_page} defaults to 10
 * ({@code :L13}); {@code service/TwitterService} performs the 1-based-to-0-based conversion.
 *
 * <pre>{@code
 * {"tweets":[{"id":"1","content":"...","like_count":120,"created_at":"2026-08-01T12:00:00",
 *             "doubt_rating":6.5,"media":[],"quoted_tweet_id":null,"user_id":"42",
 *             "ai_tools_mentioned":["GPT-4"]}],
 *  "pagination":{"page":1,"per_page":10,"total":25,"total_pages":3}}
 * }</pre>
 *
 * <p>An empty page serialises {@code tweets} as an empty array.
 *
 * @param tweets     the page of mapped posts, in the order the query returned them ({@code :L19})
 * @param pagination the page counters describing this result ({@code :L20})
 */
// Ported from backend/app/api/tweets.py:L18-21 (faithful port of the GET /tweets envelope) — see docs/DECISION_LOG.md
public record PaginatedTweetsDto(

        @JsonProperty("tweets") List<TweetDto> tweets,

        @JsonProperty("pagination") PaginationDto pagination) {
}
