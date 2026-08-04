package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound wire contract for a monitored X post.
 *
 * <p>Ported from {@code backend/app/schema/tweet.py:L5-14} (faithful port) — see
 * docs/DECISION_LOG.md.
 *
 * <p>The Pydantic source declares nine fields at L6-L14, typed and optional only. It contains no
 * {@code Field(...)}, no {@code @validator}, no {@code Config}, no default and no constraint. This
 * record declares those same nine components in the same order and carries no validation
 * annotation.
 *
 * <p>Every component declares its JSON key explicitly. All keys are snake_case and match the Python
 * field names character for character — see docs/DECISION_LOG.md DL-022.
 *
 * <p>{@code id} is a {@code String} on the wire. The corresponding column is
 * {@code Column(Integer, primary_key=True)} at {@code backend/app/db/models.py:L10} — see
 * docs/DECISION_LOG.md DL-023.
 *
 * <p>{@code media} and {@code ai_tools_mentioned} serialise as JSON arrays. The corresponding
 * columns are single delimited {@code Column(String)} values at
 * {@code backend/app/db/models.py:L15} and {@code backend/app/db/models.py:L18} — see
 * docs/DECISION_LOG.md DL-024. Conversion between the delimited and list forms is performed by
 * {@code util.DelimitedStringListConverter} and {@code service.mapper.TweetMapper}.
 *
 * <p>Instances are immutable and every component is supplied through the canonical constructor. A
 * null {@code quotedTweetId} is permitted.
 *
 * <p>Serialised form:
 *
 * <pre>
 * {
 *   "id": "1",
 *   "content": "...",
 *   "like_count": 0,
 *   "created_at": "2026-01-01T00:00:00",
 *   "doubt_rating": 0.0,
 *   "media": [],
 *   "quoted_tweet_id": null,
 *   "user_id": "...",
 *   "ai_tools_mentioned": []
 * }
 * </pre>
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
}
