package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Outbound wire contract for a monitored X post.
 *
 * <p>Nine components in source declaration order. Every JSON key is declared explicitly and is
 * snake_case — see docs/DECISION_LOG.md DL-022. No validation annotation is declared, matching the
 * source model, which declared types and optionality only — see docs/DECISION_LOG.md DL-050.
 *
 * <p>{@code id} is a {@code String} on the wire while the column is
 * {@code Column(Integer, primary_key=True)} at {@code backend/app/db/models.py:L10} — see
 * docs/DECISION_LOG.md DL-023. {@code media} and {@code ai_tools_mentioned} serialise as JSON arrays
 * while the columns are single delimited {@code Column(String)} values at
 * {@code backend/app/db/models.py:L15} and {@code :L18} — see docs/DECISION_LOG.md DL-024.
 *
 * <p>Record components are {@code final}. {@code quotedTweetId} is the only component that accepts
 * {@code null}; the canonical constructor rejects a {@code null} value for each of the other eight,
 * which the source declares as required ({@code backend/app/schema/tweet.py:L6-11,L13-14}). The two
 * {@code List} components are replaced by unmodifiable copies, so a list the caller later mutates
 * does not change this record.
 *
 * <p>Serialised form:
 *
 * <pre>{@code
 * {"id":"1","content":"...","like_count":0,"created_at":"2026-01-01T00:00:00","doubt_rating":0.0,
 *  "media":[],"quoted_tweet_id":null,"user_id":"...","ai_tools_mentioned":[]}
 * }</pre>
 *
 * @param id post identifier, serialised as a string ({@code backend/app/schema/tweet.py:L6})
 * @param content post body text ({@code backend/app/schema/tweet.py:L7})
 * @param likeCount number of likes recorded for the post
 *     ({@code backend/app/schema/tweet.py:L8})
 * @param createdAt time the post was created ({@code backend/app/schema/tweet.py:L9})
 * @param doubtRating doubt rating on a 0-10 scale ({@code backend/app/schema/tweet.py:L10})
 * @param media media references attached to the post ({@code backend/app/schema/tweet.py:L11})
 * @param quotedTweetId identifier of the quoted post, or {@code null}; the sole
 *     {@code Optional[str]} field in the source ({@code backend/app/schema/tweet.py:L12})
 * @param userId identifier of the post author ({@code backend/app/schema/tweet.py:L13})
 * @param aiToolsMentioned names of the AI tools named in the post
 *     ({@code backend/app/schema/tweet.py:L14})
 */
// Ported from backend/app/schema/tweet.py:L5-14 (faithful port) — see docs/DECISION_LOG.md
public record TweetDto(

        @JsonProperty("id") String id,

        @JsonProperty("content") String content,

        @JsonProperty("like_count") int likeCount,

        @JsonProperty("created_at") LocalDateTime createdAt,

        @JsonProperty("doubt_rating") double doubtRating,

        @JsonProperty("media") List<String> media,

        // Sole Optional[str] field in the source (backend/app/schema/tweet.py:L12); may be null.
        @JsonProperty("quoted_tweet_id") String quotedTweetId,

        @JsonProperty("user_id") String userId,

        @JsonProperty("ai_tools_mentioned") List<String> aiToolsMentioned) {

    /**
     * Rejects a null value for each required component and replaces {@code media} and
     * {@code aiToolsMentioned} with unmodifiable copies.
     *
     * @throws NullPointerException if {@code id}, {@code content}, {@code createdAt},
     *     {@code media}, {@code userId} or {@code aiToolsMentioned} is null, or if either list
     *     holds a null element
     */
    public TweetDto {
        Objects.requireNonNull(id, "id must not be null.");
        Objects.requireNonNull(content, "content must not be null.");
        Objects.requireNonNull(createdAt, "createdAt must not be null.");
        Objects.requireNonNull(media, "media must not be null.");
        Objects.requireNonNull(userId, "userId must not be null.");
        Objects.requireNonNull(aiToolsMentioned, "aiToolsMentioned must not be null.");
        media = List.copyOf(media);
        aiToolsMentioned = List.copyOf(aiToolsMentioned);
    }
}
