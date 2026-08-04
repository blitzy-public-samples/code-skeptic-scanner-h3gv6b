package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound request body of {@code POST /responses}.
 *
 * <p>The Flask handler at backend/app/api/responses.py:L33-49 read exactly one
 * key out of the free-form JSON body, {@code tweet_id} (L38), and passed it
 * unchanged to the response-generation service (L44). This record is the typed
 * form of that body: one component, bound from the same snake_case key, and
 * carried as a {@link String} in the form the handler received it.</p>
 *
 * <p>The required-component constraint on {@link #tweetId()} corresponds to the
 * guard at L40-41, which answered an absent {@code tweet_id} with HTTP 400 and
 * the body {@code {"error": "Tweet ID is required"}}. The route's two remaining
 * outcomes are produced elsewhere: HTTP 201 carrying the generated response
 * (L47) and HTTP 500 with {@code {"error": "Failed to generate response"}}
 * (L49).</p>
 *
 * <p>Bound by a controller parameter annotated {@code @Valid @RequestBody}.
 * A conforming body is:</p>
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
