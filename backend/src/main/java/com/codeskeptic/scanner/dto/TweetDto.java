package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Outbound wire contract for a monitored X post: nine components in source declaration order, every
 * JSON key declared explicitly and snake_case (DL-022), no validation annotation (DL-050).
 *
 * <p>{@code id} is a {@code String} on the wire while the column is
 * {@code Column(Integer, primary_key=True)} at {@code backend/app/db/models.py:L10} — DL-023.
 * {@code media} and {@code ai_tools_mentioned} serialise as JSON arrays while the columns are single
 * delimited {@code Column(String)} values at {@code :L15} and {@code :L18} — DL-024.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080. Every {@code tweets} column except the primary
 * key is nullable, and this record carries a {@code null} column value to the wire as JSON
 * {@code null}, which is what {@code tweet.to_dict()} at {@code backend/app/api/tweets.py:L19,L30}
 * produced for a column holding {@code None}. The canonical constructor therefore rejects exactly one
 * component, {@code id}: it is the primary key, a stored row always carries one, and rejecting
 * {@code null} keeps an unstored entity from acquiring a wire form. {@code content},
 * {@code likeCount}, {@code createdAt}, {@code doubtRating}, {@code quotedTweetId} and
 * {@code userId} may each be {@code null}. {@code likeCount} and {@code doubtRating} are boxed so an
 * empty column reads as {@code null} rather than as {@code 0}. No scalar is trimmed, rounded,
 * defaulted or substituted. The {@code tweets} columns stay nullable — see docs/DECISION_LOG.md
 * DL-080.
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
 * <p>A row stored but not yet analysed renders the same shape with the column that holds nothing
 * carried as JSON {@code null}:
 *
 * <pre>{@code
 * {"id":"1","content":"...","like_count":0,"created_at":"2026-01-01T00:00:00","doubt_rating":null,
 *  "media":[],"quoted_tweet_id":null,"user_id":"...","ai_tools_mentioned":[]}
 * }</pre>
 *
 * @param id post identifier, serialised as a string ({@code backend/app/schema/tweet.py:L6}); never
 *     {@code null}
 * @param content post body text ({@code backend/app/schema/tweet.py:L7}); may be {@code null} when
 *     the column holds none
 * @param likeCount number of likes recorded for the post
 *     ({@code backend/app/schema/tweet.py:L8}); may be {@code null} when the column holds none
 * @param createdAt time the post was created ({@code backend/app/schema/tweet.py:L9}); may be
 *     {@code null} when the column holds none
 * @param doubtRating doubt rating on a 0-10 scale ({@code backend/app/schema/tweet.py:L10}); may be
 *     {@code null} until {@code POST /tweets/{tweetId}/analyze} fills the column
 * @param media media references attached to the post ({@code backend/app/schema/tweet.py:L11});
 *     never {@code null}
 * @param quotedTweetId identifier of the quoted post; the sole {@code Optional[str]} field in the
 *     source ({@code backend/app/schema/tweet.py:L12}); may be {@code null}
 * @param userId identifier of the post author ({@code backend/app/schema/tweet.py:L13}); may be
 *     {@code null} when the column holds none
 * @param aiToolsMentioned names of the AI tools named in the post
 *     ({@code backend/app/schema/tweet.py:L14}); never {@code null}
 */
// Ported from backend/app/schema/tweet.py:L5-14 (faithful port) — see docs/DECISION_LOG.md
// Boxed like_count and doubt_rating, and the null policy that carries an empty column to the wire as
// JSON null, are recorded as DL-080 — see docs/DECISION_LOG.md
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
     * Rejects a {@code null} identifier and normalises the two list components.
     *
     * <p>{@code id} is the only rejected component: it renders {@code tweets.id}, the primary key a
     * stored row always carries, so a {@code null} names an entity that was never stored rather than
     * a column that holds nothing. Every other scalar is carried exactly as the column holds it,
     * {@code null} included, and none is defaulted, trimmed, rounded or substituted — see
     * docs/DECISION_LOG.md DL-080.
     *
     * <p>{@code media} and {@code aiToolsMentioned} become an empty unmodifiable list when
     * {@code null} and an unmodifiable copy otherwise; a {@code null} element is dropped — see
     * docs/DECISION_LOG.md DL-024.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    // The wire form of a stored row, whose nullable columns carry through as JSON null — DL-080 —
    // see docs/DECISION_LOG.md
    public TweetDto {
        Objects.requireNonNull(id, "id must not be null.");
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
