package com.codeskeptic.scanner.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-024, DL-164
// Reconciles the storage shape at backend/app/db/models.py:L15,L18 (media and
// ai_tools_mentioned each declared Column(String)) with the domain shape at
// backend/app/schema/tweet.py:L11,L14 (both declared List[str]).
/**
 * JPA attribute converter mapping a {@code List<String>} entity attribute onto a single
 * comma-delimited {@code String} column value, and the one authorized codec for that
 * representation.
 *
 * <p>It is applied to individual attributes with {@code @Convert(converter =
 * DelimitedStringListConverter.class)}. The column remains a plain character column: this
 * converter declares no column type, length or nullability facet.</p>
 *
 * <h2>Encoding</h2>
 * <p>Elements are trimmed and joined with a comma. No character is given a special meaning
 * inside an element and no character is substituted: a comma, a backslash, a quote and any
 * non-ASCII character are all written literally. A read splits on every comma. An element that
 * itself contains a comma is read back as several elements — DL-164.</p>
 *
 * <h2>Write contract</h2>
 * <p>{@link #convertToDatabaseColumn(List)} and {@link #encode(List)} behave as follows.</p>
 * <ul>
 *   <li>A {@code null} attribute yields a {@code null} column value.</li>
 *   <li>Elements that are {@code null}, or empty once trimmed, are omitted.</li>
 *   <li>Retained elements are trimmed, then joined with a comma. Their content is otherwise
 *       unaltered: it is not re-cased, sorted, de-duplicated or re-ordered.</li>
 *   <li>An attribute that retains no elements yields a {@code null} column value, never
 *       the empty string.</li>
 * </ul>
 *
 * <h2>Read contract</h2>
 * <p>{@link #convertToEntityAttribute(String)} and {@link #decode(String)} behave as follows.</p>
 * <ul>
 *   <li>{@code null} or blank column data yields an empty list.</li>
 *   <li>Any other value is split on every comma and each token is trimmed. Tokens that are empty
 *       once trimmed are dropped, which is what a hand-edited value carrying a repeated or
 *       trailing comma produces.</li>
 *   <li>The result is never {@code null} and never contains an empty string.</li>
 *   <li>The result is always a new mutable list. Callers may modify it, and successive
 *       calls share no state.</li>
 * </ul>
 *
 * <h2>Round trip</h2>
 * <p>Reading back a written value returns the written list element for element for any list whose
 * elements are non-{@code null}, not blank and free of the delimiter. That holds for an element
 * containing a backslash, a double quote, a non-ASCII character, an emoji or interior whitespace.
 * Three kinds of element are not carried through unchanged — see docs/DECISION_LOG.md DL-164: a
 * {@code null} element and an element that is blank once trimmed are read back as absent, so a list
 * consisting only of those reads back empty; leading and trailing whitespace is discarded; and an
 * element containing a comma is read back as several elements.</p>
 *
 * <p>{@link #encode(List)} and {@link #decode(String)} are the static form of the same two
 * conversions, so the representation can be exercised and asserted without an entity: the two
 * instance methods delegate to them, and {@code repository/JpaMappingIntegrationTest} calls them
 * directly. No other production class calls either member — the Notion mirror carries neither of the
 * two delimited values, so it performs no delimited conversion — DL-164, DL-088.</p>
 *
 * <p>No method throws for any input. Instances hold no mutable state and are thread-safe.</p>
 */
@Converter
public class DelimitedStringListConverter implements AttributeConverter<List<String>, String> {

    /** Separates elements in the delimited value. */
    private static final String DELIMITER = ",";

    /**
     * Converts a list attribute into the single delimited column value.
     *
     * @param attribute the entity attribute, may be {@code null} and may contain
     *                  {@code null} or blank elements
     * @return the delimited column value, or {@code null} when the attribute is
     *         {@code null} or retains no elements
     */
    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return encode(attribute);
    }

    /**
     * Converts the single delimited column value into a list attribute.
     *
     * @param dbData the column value, may be {@code null}, blank, or contain empty tokens
     * @return a new mutable list of trimmed, non-empty elements; empty when {@code dbData}
     *         holds no such element. Never {@code null}
     */
    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        return decode(dbData);
    }

    /**
     * Renders a list as the single delimited value this representation defines.
     *
     * @param values the list to render, may be {@code null} and may contain {@code null} or blank
     *               elements
     * @return the delimited value, or {@code null} when {@code values} is {@code null} or retains
     *         no element
     */
    public static String encode(List<String> values) {
        if (values == null) {
            return null;
        }
        // A retained element is never empty after trimming. An empty join result identifies
        // a list that retained no element.
        String delimited = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(element -> !element.isEmpty())
                .collect(Collectors.joining(DELIMITER));
        return delimited.isEmpty() ? null : delimited;
    }

    /**
     * Reads a single delimited value back into a list.
     *
     * @param delimited the delimited value, may be {@code null}, blank, or contain empty tokens
     * @return a new mutable list of trimmed, non-empty elements; empty when {@code delimited} holds
     *         no such element. Never {@code null}
     */
    public static List<String> decode(String delimited) {
        // "".split(DELIMITER) yields one empty token. The blank check excludes that input
        // from the split path. The token filter below drops empty tokens from every other
        // input, including a value consisting only of delimiters and whitespace.
        if (delimited == null || delimited.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(delimited.split(DELIMITER))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
