package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;

// Net-new (no Python counterpart method) — the class is authorised by DL-295; call sites
// backend/app/api/tweets.py:L19,L30 — DL-023, DL-024, DL-080 — see docs/DECISION_LOG.md
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
 * <p>Null policy — see docs/DECISION_LOG.md DL-080. Every one of the nine {@code tweets}
 * columns is declared without {@code nullable=false}, so a stored row may carry {@code null} in any of
 * them, and this class carries a {@code null} column value through as a {@code null} component. No
 * conversion here unboxes a column value, defaults a component, substitutes a neutral value or
 * rejects a column value; the requirement is declared in exactly one place, {@link TweetDto}, which
 * rejects a {@code null} for each component the wire contract of
 * {@code backend/app/schema/tweet.py:L6-14} declares required. Converting a row that leaves such a
 * column empty fails in the record's constructor, not here. {@link TweetDto} also
 * normalises the two list components, so those two are the only components that are never
 * {@code null}. The entity's {@code responses} association is not read.
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
     * @return a DTO holding the entity's nine column values with the identifier rendered as a string
     *         and every {@code null} scalar column carried as a {@code null} component, or
     *         {@code null} when {@code tweet} is {@code null}
     */
    public TweetDto toDto(Tweet tweet) {
        if (tweet == null) {
            return null;
        }
        return new TweetDto(
                identifierAsString(tweet.getId()),
                tweet.getContent(),
                tweet.getLikeCount(),
                tweet.getCreatedAt(),
                tweet.getDoubtRating(),
                tweet.getMedia(),
                tweet.getQuotedTweetId(),
                tweet.getUserId(),
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

    /**
     * Renders a persistent identifier as its decimal string form.
     *
     * @param identifier the identifier to render, may be {@code null}
     * @return the decimal string form of {@code identifier}, or {@code null} when {@code identifier}
     *         is {@code null}
     */
    private static String identifierAsString(Integer identifier) {
        return (identifier == null) ? null : identifier.toString();
    }
}
