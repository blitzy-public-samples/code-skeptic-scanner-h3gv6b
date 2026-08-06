package com.codeskeptic.scanner.util;

import java.util.List;

// Net-new (no source construct: backend/app/tasks/tweet_monitoring.py:L53-55 left the keyword set
// undefined and validated nothing) — see docs/DECISION_LOG.md DL-257
/**
 * The one X filtered-stream rule grammar this application holds its stream terms to.
 *
 * <p>One term becomes one rule. A term is carried as its own match expression, wrapped in double
 * quotes when it holds whitespace so the phrase matches as a phrase. A term is accepted only when it
 * holds at most {@value #MAX_TERM_CHARS} characters, every character is a letter, a digit or one of
 * {@value #ADDITIONAL_TERM_CHARACTERS}, and the first and last characters are a letter or a digit.
 * Every character the rule syntax gives a meaning to — the double quote, the backslash, the
 * parentheses, the colon, the leading negation, the hashtag, the mention sign and every control
 * character — is refused, and no term can close a quoted expression, introduce an operator or forge a
 * log line.
 *
 * <p>Both callers hold to this one rule, so a term reported usable in one place is never refused in
 * the other: {@code task/TweetStreamClient} drops an unusable term before it mutates the registered
 * rule set and renders an accepted one through {@link #expressionOf(String)}, and
 * {@code service/SettingsService} reports an operator edit of the {@code stream_keywords} row that
 * leaves no usable term — DL-257.
 *
 * <p>No method here records a log event and no method reproduces a term, so a term never reaches a
 * log record through this class — DL-052, DL-197.
 *
 * <p>This class holds no state and every method is safe for concurrent use.
 */
public final class StreamRuleTerms {

    /**
     * Most characters an accepted term holds. A longer term is refused, and the rendered expression is
     * bounded at {@value #MAX_TERM_CHARS} plus the two quotes a phrase carries — well inside the
     * length the X standard filtered-stream rule grammar accepts.
     */
    public static final int MAX_TERM_CHARS = 128;

    /**
     * The characters an accepted term may hold in addition to letters and digits. A term may not open
     * or close with one of them.
     */
    public static final String ADDITIONAL_TERM_CHARACTERS = " -_.'";

    /** Separator of the terms held in the {@code stream_keywords} row. */
    public static final String TERM_DELIMITER = ",";

    /**
     * Most segments a delimited value is split into. A value carrying more delimiters than this
     * leaves its whole tail in the last segment, which the grammar then refuses on length — DL-254.
     */
    public static final int MAX_SEGMENTS = 512;

    private StreamRuleTerms() {
        throw new AssertionError("StreamRuleTerms holds only static members.");
    }

    /**
     * Reports whether a term can be carried as a match expression.
     *
     * @param term the term to test, possibly {@code null}
     * @return {@code true} when {@code term} is non-{@code null} and, once trimmed, is non-empty,
     *     holds at most {@value #MAX_TERM_CHARS} characters, opens and closes with a letter or a
     *     digit, and holds nothing but letters, digits and the characters of
     *     {@value #ADDITIONAL_TERM_CHARACTERS}
     */
    public static boolean isUsable(String term) {
        if (term == null) {
            return false;
        }
        String trimmed = term.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TERM_CHARS) {
            return false;
        }
        if (!Character.isLetterOrDigit(trimmed.charAt(0))
                || !Character.isLetterOrDigit(trimmed.charAt(trimmed.length() - 1))) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && ADDITIONAL_TERM_CHARACTERS.indexOf(character) < 0) {
                return false;
            }
        }
        return true;
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
     * @return at most {@value #MAX_SEGMENTS} parts of {@code value} split on
     *     {@value #TERM_DELIMITER}, or an empty collection when {@code value} is {@code null} or
     *     blank
     */
    public static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(TERM_DELIMITER, MAX_SEGMENTS));
    }

    /**
     * Counts the terms of a delimited value that {@link #isUsable(String)} admits.
     *
     * @param value the delimited value, possibly {@code null}
     * @return the number of usable terms, never negative
     */
    public static int countUsable(String value) {
        int usable = 0;
        for (String candidate : split(value)) {
            if (isUsable(candidate)) {
                usable++;
            }
        }
        return usable;
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
