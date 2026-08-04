package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;

// Net-new (no Python counterpart method) — call sites backend/app/api/tweets.py:L19,L30 —
// DL-023, DL-024 — see docs/DECISION_LOG.md
/**
 * Converts {@link Tweet} entities into their {@link TweetDto} wire form.
 *
 * <p>A conversion copies all nine {@code tweets} columns in the source declaration order —
 * {@code id}, {@code content}, {@code like_count}, {@code created_at}, {@code doubt_rating},
 * {@code media}, {@code quoted_tweet_id}, {@code user_id} and {@code ai_tools_mentioned} — and
 * applies exactly two transformations to them:
 *
 * <ul>
 *   <li>the {@link Long} identifier becomes its decimal {@link String} form, and becomes
 *       {@code null} when the entity carries no identifier;
 *   <li>{@code media} and {@code aiToolsMentioned} become an empty list whenever the entity holds
 *       {@code null}; neither is ever handed on as {@code null}.
 * </ul>
 *
 * <p>Every other component is copied verbatim. No value is formatted, rounded, trimmed, re-cased,
 * defaulted, de-duplicated or re-ordered, and {@code quotedTweetId} — the sole {@code Optional[str]}
 * field of the source contract at {@code backend/app/schema/tweet.py:L12} — is carried across as
 * {@code null} when it is {@code null}. The entity's {@code responses} association is not read: a
 * conversion triggers no lazy load.
 *
 * <p>Conversion runs in one direction only: this mapper declares no entity-producing operation, and
 * it performs no persistence access and no outbound call. Instances hold no state and are
 * thread-safe.
 */
@Component
public final class TweetMapper {

    /**
     * Converts a single tweet entity into its wire form.
     *
     * @param tweet the entity to convert, may be {@code null}
     * @return a DTO holding the entity's nine column values, with the identifier rendered as a
     *         string and with {@code media} and {@code aiToolsMentioned} never {@code null}; or
     *         {@code null} when {@code tweet} is {@code null}
     * @throws NullPointerException if the entity holds {@code null} where {@link TweetDto} requires
     *         a value, or if either list attribute holds a {@code null} element
     */
    public TweetDto toDto(Tweet tweet) {
        if (tweet == null) {
            return null;
        }
        Long identifier = tweet.getId();
        return new TweetDto(
                identifier == null ? null : String.valueOf(identifier),
                tweet.getContent(),
                tweet.getLikeCount(),
                tweet.getCreatedAt(),
                tweet.getDoubtRating(),
                emptyWhenNull(tweet.getMedia()),
                tweet.getQuotedTweetId(),
                tweet.getUserId(),
                emptyWhenNull(tweet.getAiToolsMentioned()));
    }

    /**
     * Converts a list of tweet entities into their wire form, preserving the order of the input.
     *
     * @param tweets the entities to convert, may be {@code null}, may be empty and may contain
     *               {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code tweets} is
     *         {@code null} or empty. Never {@code null}
     * @throws NullPointerException if an element holds {@code null} where {@link TweetDto} requires
     *         a value, or if an element's list attribute holds a {@code null} element
     */
    public List<TweetDto> toDtoList(List<Tweet> tweets) {
        if (tweets == null || tweets.isEmpty()) {
            return List.of();
        }
        return tweets.stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns the given list of column values, substituting an empty unmodifiable list for
     * {@code null}. A non-{@code null} list is returned exactly as received: it is neither copied,
     * sorted, filtered nor de-duplicated.
     *
     * @param values the list of column values, may be {@code null} and may be empty
     * @return {@code values} when it is non-{@code null}, otherwise an empty list. Never
     *         {@code null}
     */
    private static List<String> emptyWhenNull(List<String> values) {
        return values == null ? List.of() : values;
    }
}
