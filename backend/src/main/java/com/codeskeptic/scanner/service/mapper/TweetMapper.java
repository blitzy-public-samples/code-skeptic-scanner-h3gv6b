package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;

// Net-new (no Python counterpart method) — call sites backend/app/api/tweets.py:L19,L30 —
// DL-023, DL-024 and DL-080 — see docs/DECISION_LOG.md
/**
 * Converts {@link Tweet} entities into their {@link TweetDto} wire form.
 *
 * <p>A conversion copies all nine {@code tweets} columns in the source declaration order —
 * {@code id}, {@code content}, {@code like_count}, {@code created_at}, {@code doubt_rating},
 * {@code media}, {@code quoted_tweet_id}, {@code user_id} and {@code ai_tools_mentioned} — and
 * applies exactly one transformation: the {@link Integer} identifier becomes its decimal
 * {@link String} form.
 *
 * <p>Every other column value is copied verbatim.
 *
 * <p>Null policy — see docs/DECISION_LOG.md DL-080. The columns stay nullable, so a stored row can
 * carry {@code null} in a column that {@code backend/app/schema/tweet.py:L6-14} declares required.
 * Such a row has no wire form and is rejected here, naming the column, rather than serialised with a
 * JSON {@code null}. {@code quoted_tweet_id} is the sole column that may be {@code null}.
 * {@link TweetDto} normalises the two list components. The entity's {@code responses} association is
 * not read.
 *
 * <p>Conversion runs in one direction: this mapper declares no entity-producing operation and
 * performs no persistence access and no outbound call. Instances hold no state and are thread-safe.
 */
@Component
public final class TweetMapper {

    /**
     * Converts a single tweet entity into its wire form.
     *
     * @param tweet the entity to convert, may be {@code null}
     * @return a DTO holding the entity's nine column values with the identifier rendered as a
     *         string, or {@code null} when {@code tweet} is {@code null}
     * @throws IllegalStateException if the row carries {@code null} in a column the wire contract of
     *         {@code backend/app/schema/tweet.py:L6-14} declares required
     */
    public TweetDto toDto(Tweet tweet) {
        if (tweet == null) {
            return null;
        }
        return new TweetDto(
                String.valueOf(required(tweet.getId(), "id")),
                required(tweet.getContent(), "content"),
                required(tweet.getLikeCount(), "like_count"),
                required(tweet.getCreatedAt(), "created_at"),
                required(tweet.getDoubtRating(), "doubt_rating"),
                tweet.getMedia(),
                tweet.getQuotedTweetId(),
                required(tweet.getUserId(), "user_id"),
                tweet.getAiToolsMentioned());
    }

    /**
     * Converts a list of tweet entities into their wire form, preserving the order of the input.
     *
     * @param tweets the entities to convert, may be {@code null}, may be empty and may contain
     *               {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code tweets} is
     *         {@code null} or empty. Never {@code null}
     */
    public List<TweetDto> toDtoList(List<Tweet> tweets) {
        if (tweets == null || tweets.isEmpty()) {
            return List.of();
        }
        return tweets.stream()
                .map(this::toDto)
                .toList();
    }

    // The required fields of backend/app/schema/tweet.py:L6-14 — DL-080 — see docs/DECISION_LOG.md
    /**
     * Returns a column value the wire contract declares required.
     *
     * @param value  the stored column value
     * @param column the wire key of the column, used in the failure message
     * @param <T>    the column's value type
     * @return {@code value}
     * @throws IllegalStateException if {@code value} is {@code null}
     */
    private static <T> T required(T value, String column) {
        if (value == null) {
            throw new IllegalStateException("A tweets row carrying no " + column
                    + " has no wire form: backend/app/schema/tweet.py declares the field required.");
        }
        return value;
    }
}
