package com.codeskeptic.scanner.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

/**
 * Inbound request body of {@code POST /responses}.
 *
 * <p>One component, bound from the single key the source handler read out of the free-form body,
 * {@code tweet_id} ({@code backend/app/api/responses.py:L38}), carried as the raw JSON node the
 * request supplied, which is the value the guard at {@code backend/app/api/responses.py:L40} is
 * applied to — see docs/DECISION_LOG.md DL-240.
 *
 * <p>{@code @NotNull} on {@link #tweetId()} is the only constraint declared and corresponds to that
 * guard, which answered an absent {@code tweet_id} with HTTP 400 and the body
 * {@code {"error": "Tweet ID is required"}} ({@code backend/app/api/responses.py:L40-41}) — see
 * docs/DECISION_LOG.md DL-050. It rejects a body carrying no {@code tweet_id} member at all;
 * {@link #usableTweetId()} answers for every carried value.
 *
 * <p>{@link #usableTweetId()} reports {@code null} for exactly the values Python read as false —
 * {@code null}, {@code ""}, {@code 0}, {@code 0.0}, {@code -0.0}, {@code false}, {@code []} and
 * {@code {}} — and the identifier text for every other value, which is what the source passed on to
 * generation — DL-240.
 *
 * <p>The value is the stringified {@code tweets.id} primary key — the generated surrogate declared at
 * {@code backend/app/db/models.py:L10} and referenced by {@code responses.tweet_id} at
 * {@code backend/app/db/models.py:L27} — not an X post identifier. A conforming body is:
 *
 * <pre>{@code {"tweet_id": "7"}}</pre>
 *
 * @param tweetId raw {@code tweet_id} node, {@code null} when the body omits the key; the value it
 *                carries is read by {@link #usableTweetId()}
 */
// Ported from backend/app/api/responses.py:L38,L40-41 (faithful port) — see docs/DECISION_LOG.md
// DL-050, DL-240
public record CreateResponseRequest(

        @NotNull
        @JsonProperty("tweet_id")
        JsonNode tweetId) {

    /**
     * Returns the identifier the source handler would have carried past its guard.
     *
     * <p>The guard at {@code backend/app/api/responses.py:L40} is {@code if not tweet_id}, so it
     * rejects an absent member, a JSON {@code null}, an empty string, a zero of any numeric form,
     * {@code false}, an empty array and an empty object, and accepts every other value — DL-240.
     *
     * <p>An accepted value is rendered as text: a JSON string yields its own characters, a number or
     * a boolean yields its JSON spelling, and an array or object yields its compact JSON document, so
     * a value that names no {@code tweets} row reaches the generation path and is reported with the
     * literal of {@code backend/app/api/responses.py:L49} rather than with the guard's literal.
     *
     * @return the identifier text to generate for, never empty; {@code null} when the carried value
     *         is one the source guard rejected
     */
    public String usableTweetId() {
        if (!namesTweet()) {
            return null;
        }
        return tweetId.isValueNode() ? tweetId.asText() : tweetId.toString();
    }

    /**
     * Reports whether the carried {@code tweet_id} value passes the source guard.
     *
     * @return {@code true} when the body carries a {@code tweet_id} value Python read as true
     */
    public boolean namesTweet() {
        return carriesValue(tweetId);
    }

    /**
     * Applies the truth value Python assigns to one decoded JSON value.
     *
     * <p>A boolean is its own truth value; a number is true unless it is zero; a string is true
     * unless it is empty; an array and an object are true unless they are empty; a {@code null} and
     * an absent member are false. A non-finite number is true, as it is in Python.
     *
     * @param value the carried node, or {@code null} when the body omitted the member
     * @return {@code true} when the source guard would have accepted the value
     */
    private static boolean carriesValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            double magnitude = value.doubleValue();
            if (Double.isNaN(magnitude) || Double.isInfinite(magnitude)) {
                return true;
            }
            return value.decimalValue().compareTo(BigDecimal.ZERO) != 0;
        }
        if (value.isTextual()) {
            return !value.textValue().isEmpty();
        }
        if (value.isContainerNode()) {
            return !value.isEmpty();
        }
        return true;
    }
}
