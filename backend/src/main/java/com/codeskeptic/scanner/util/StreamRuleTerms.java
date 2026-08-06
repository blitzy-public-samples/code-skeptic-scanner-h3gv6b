package com.codeskeptic.scanner.util;

import java.util.ArrayList;
import java.util.List;

// Net-new (no source construct: backend/app/tasks/tweet_monitoring.py:L53-55 left the keyword set
// undefined and validated nothing) — see docs/DECISION_LOG.md DL-249
/**
 * The X filtered-stream rule grammar this application holds its stream terms to.
 *
 * <p>One term becomes one rule. A term is carried as its own match expression, wrapped in double
 * quotes when it holds whitespace so the phrase matches as a phrase. The grammar gives a meaning to
 * the double quote and the backslash inside a quoted phrase, and a rule expression is bounded at
 * {@value #MAX_RULE_EXPRESSION_LENGTH} characters, so a term carrying either character, carrying an
 * ISO control character, or rendering past that bound cannot be registered.
 *
 * <p>Two callers share this rule: {@code task/TweetStreamClient}, which drops an unusable term before
 * it mutates the registered rule set, and {@code service/SettingsService}, which reports an
 * operator edit of the {@code stream_keywords} row that would leave no usable term.
 *
 * <p>No method here records a log event and no method reproduces a term, so a term never reaches a
 * log record through this class — DL-249, DL-208.
 *
 * <p>This class holds no state and every method is safe for concurrent use.
 */
public final class StreamRuleTerms {

    /**
     * Longest match expression the X standard filtered-stream rule grammar accepts. A term whose
     * rendered expression exceeds this cannot be registered.
     */
    public static final int MAX_RULE_EXPRESSION_LENGTH = 1024;

    /**
     * The characters a term may not carry: the double quote and the backslash, the two the grammar
     * gives a meaning to inside a quoted phrase.
     */
    public static final String REJECTED_CHARACTERS = "\"\\";

    /** Separator of the terms held in the {@code stream_keywords} row. */
    public static final String TERM_DELIMITER = ",";

    private StreamRuleTerms() {
        throw new AssertionError("StreamRuleTerms holds only static members.");
    }

    /**
     * Reports whether a term can be carried as a match expression.
     *
     * @param term the term to test, possibly {@code null}
     * @return {@code true} when {@code term} is non-{@code null}, non-blank once trimmed, carries no
     *     character of {@link #REJECTED_CHARACTERS} and no ISO control character, and renders to an
     *     expression of at most {@value #MAX_RULE_EXPRESSION_LENGTH} characters
     */
    public static boolean isUsable(String term) {
        if (term == null) {
            return false;
        }
        String trimmed = term.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (REJECTED_CHARACTERS.indexOf(character) >= 0 || Character.isISOControl(character)) {
                return false;
            }
        }
        return expressionOf(trimmed).length() <= MAX_RULE_EXPRESSION_LENGTH;
    }

    /**
     * Renders one term as a match expression.
     *
     * <p>A term holding whitespace is wrapped in double quotes, which matches it as a phrase; every
     * other term is carried unchanged. No escaping is applied: {@link #isUsable(String)} admits no term
     * carrying a character escaping addresses.
     *
     * @param term the term to render, must not be {@code null}
     * @return the match expression, never {@code null}
     */
    public static String expressionOf(String term) {
        return holdsWhitespace(term) ? '"' + term + '"' : term;
    }

    /**
     * Splits a delimited value into its terms.
     *
     * @param value the delimited value, possibly {@code null}
     * @return the parts of {@code value} split on {@value #TERM_DELIMITER}, or an empty collection
     *     when {@code value} is {@code null} or blank
     */
    public static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(TERM_DELIMITER));
    }

    /**
     * Counts the terms of a delimited value that {@link #isUsable(String)} admits.
     *
     * @param value the delimited value, possibly {@code null}
     * @return the number of usable terms, never negative
     */
    public static int countUsable(String value) {
        List<String> usable = new ArrayList<>();
        for (String candidate : split(value)) {
            if (isUsable(candidate)) {
                usable.add(candidate);
            }
        }
        return usable.size();
    }

    /**
     * Reports whether a term holds a whitespace character.
     *
     * @param term the term to inspect, must not be {@code null}
     * @return {@code true} when at least one character is whitespace
     */
    private static boolean holdsWhitespace(String term) {
        return term.chars().anyMatch(Character::isWhitespace);
    }
}
