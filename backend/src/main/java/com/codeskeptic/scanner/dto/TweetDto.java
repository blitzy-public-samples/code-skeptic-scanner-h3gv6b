package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Outbound wire contract for a monitored X post.
 *
 * <p>Nine components in source declaration order. Every JSON key is declared explicitly and is
 * snake_case — see docs/DECISION_LOG.md DL-022. No validation annotation is declared — see
 * docs/DECISION_LOG.md DL-050.
 *
 * <p>{@code id} is a {@code String} on the wire while the column is
 * {@code Column(Integer, primary_key=True)} at {@code backend/app/db/models.py:L10} — see
 * docs/DECISION_LOG.md DL-023. {@code media} and {@code ai_tools_mentioned} serialise as JSON arrays
 * while the columns are single delimited {@code Column(String)} values at
 * {@code backend/app/db/models.py:L15} and {@code :L18} — see docs/DECISION_LOG.md DL-024.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080. {@code backend/app/schema/tweet.py:L6-14}
 * declares eight fields required and {@code quoted_tweet_id} the sole {@code Optional[str]}, so the
 * canonical constructor rejects a {@code null} {@code id}, {@code content}, {@code likeCount},
 * {@code createdAt}, {@code doubtRating} and {@code userId} with {@link NullPointerException}, and
 * {@code quotedTweetId} is the one component that may be {@code null}. {@code likeCount} and
 * {@code doubtRating} are boxed, and a {@code null} value for either is rejected rather than read as
 * {@code 0}. No scalar is trimmed, rounded, defaulted or substituted. The {@code tweets} columns stay
 * nullable — see docs/DECISION_LOG.md DL-080.
 *
 * <p>The two {@link List} components are never {@code null}: the canonical constructor replaces
 * {@code null} with an empty list and replaces a supplied list with an unmodifiable copy, so a list
 * the caller later mutates does not change this record.
 *
 * <p>Serialised form:
 *
 * <pre>{@code
 * {"id":"1","content":"...","like_count":0,"created_at":"2026-01-01T00:00:00","doubt_rating":0.0,
 *  "media":[],"quoted_tweet_id":null,"user_id":"...","ai_tools_mentioned":[]}
 * }</pre>
 *
 * @param id post identifier, serialised as a string ({@code backend/app/schema/tweet.py:L6}); never
 *     {@code null}
 * @param content post body text ({@code backend/app/schema/tweet.py:L7}); never {@code null}
 * @param likeCount number of likes recorded for the post
 *     ({@code backend/app/schema/tweet.py:L8}); never {@code null}
 * @param createdAt time the post was created ({@code backend/app/schema/tweet.py:L9}); never
 *     {@code null}
 * @param doubtRating doubt rating on a 0-10 scale ({@code backend/app/schema/tweet.py:L10}); never
 *     {@code null}
 * @param media media references attached to the post ({@code backend/app/schema/tweet.py:L11});
 *     never {@code null}
 * @param quotedTweetId identifier of the quoted post; the sole {@code Optional[str]} field in the
 *     source ({@code backend/app/schema/tweet.py:L12}); may be {@code null}
 * @param userId identifier of the post author ({@code backend/app/schema/tweet.py:L13}); never
 *     {@code null}
 * @param aiToolsMentioned names of the AI tools named in the post
 *     ({@code backend/app/schema/tweet.py:L14}); never {@code null}
 */
// Ported from backend/app/schema/tweet.py:L5-14 (faithful port) — see docs/DECISION_LOG.md
// Boxed like_count and doubt_rating, and the required-versus-optional contract of AAP TR-6, are
// recorded as DL-080 — see docs/DECISION_LOG.md
public record TweetDto(

        @JsonProperty("id") String id,

        @JsonProperty("content") String content,

        @JsonProperty("like_count") Integer likeCount,

        @JsonProperty("created_at") LocalDateTime createdAt,

        @JsonProperty("doubt_rating") Double doubtRating,

        @JsonProperty("media") List<String> media,

        // Sole Optional[str] field in the source (backend/app/schema/tweet.py:L12); may be null.
        @JsonProperty("quoted_tweet_id") String quotedTweetId,

        @JsonProperty("user_id") String userId,

        @JsonProperty("ai_tools_mentioned") List<String> aiToolsMentioned) {

    /**
     * Rejects a {@code null} value for a component the source schema declares required and
     * normalises the two list components.
     *
     * <p>The six source-required scalars are rejected; {@code quotedTweetId}, the sole
     * {@code Optional[str]} field of {@code backend/app/schema/tweet.py:L12}, is not. No scalar is
     * defaulted, trimmed, rounded or substituted.
     *
     * <p>{@code media} and {@code aiToolsMentioned} become an empty unmodifiable list when
     * {@code null} and an unmodifiable copy otherwise; a {@code null} element is dropped — see
     * docs/DECISION_LOG.md DL-024.
     *
     * @throws NullPointerException if {@code id}, {@code content}, {@code likeCount},
     *     {@code createdAt}, {@code doubtRating} or {@code userId} is {@code null}
     */
    // The required fields of backend/app/schema/tweet.py:L6-14 — AAP TR-6, DL-080 — see
    // docs/DECISION_LOG.md
    public TweetDto {
        Objects.requireNonNull(id, "id must not be null.");
        Objects.requireNonNull(content, "content must not be null.");
        Objects.requireNonNull(likeCount, "like_count must not be null.");
        Objects.requireNonNull(createdAt, "created_at must not be null.");
        Objects.requireNonNull(doubtRating, "doubt_rating must not be null.");
        Objects.requireNonNull(userId, "user_id must not be null.");
        media = unmodifiableCopy(media);
        aiToolsMentioned = unmodifiableCopy(aiToolsMentioned);
    }

    /**
     * Returns an unmodifiable copy of the supplied column values, holding no {@code null} element.
     *
     * @param values the list of column values; may be {@code null} and may hold {@code null}
     *     elements
     * @return an unmodifiable copy in the order supplied, empty when {@code values} is {@code null}
     *     or holds no non-{@code null} element; never {@code null}
     */
    private static List<String> unmodifiableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null)
                .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
    }
}
