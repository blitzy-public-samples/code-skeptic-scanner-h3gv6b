package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound request body of {@code POST /responses}.
 *
 * <p>One component, bound from the single key the source handler read out of the free-form body,
 * {@code tweet_id} ({@code backend/app/api/responses.py:L38}), carried as a {@link String} in the form
 * the handler received it.
 *
 * <p>{@code @NotNull} on {@link #tweetId()} is the only constraint declared and corresponds to the
 * guard at {@code backend/app/api/responses.py:L40-41}, which answered an absent {@code tweet_id} with
 * HTTP 400 and the body {@code {"error": "Tweet ID is required"}} — see docs/DECISION_LOG.md DL-050.
 *
 * <p>A conforming body is:
 *
 * <pre>{@code {"tweet_id": "1889999999999999999"}}</pre>
 *
 * @param tweetId identifier of the tweet a response is generated for, bound
 *                from the JSON key {@code tweet_id}; required
 */
// Ported from backend/app/api/responses.py:L38,L40-41 (faithful port) — see docs/DECISION_LOG.md DL-050
public record CreateResponseRequest(

        @NotNull
        @JsonProperty("tweet_id")
        String tweetId) {
}
