package com.codeskeptic.scanner.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-024, DL-164
// Reconciles the storage shape at backend/app/db/models.py:L15,L18 (media and
// ai_tools_mentioned each declared Column(String)) with the domain shape at
// backend/app/schema/tweet.py:L11,L14 (both declared List[str]).
/**
 * JPA attribute converter mapping a {@code List<String>} entity attribute onto a single
 * delimited {@code String} column value.
 *
 * <p>It is applied to individual attributes with {@code @Convert(converter =
 * DelimitedStringListConverter.class)}. The column remains a plain character column: this
 * converter declares no column type, length or nullability facet.</p>
 *
 * <h2>Encoding</h2>
 * <p>Elements are joined with a comma. Within an element, a comma is written as {@code \,} and a
 * backslash as {@code \\}; every other character, the double quote and every non-ASCII character
 * included, is written literally. A read reverses that substitution: a backslash consumes the
 * character that follows it, and only an unescaped comma separates elements — DL-164.</p>
 *
 * <h2>Write contract</h2>
 * <p>{@link #convertToDatabaseColumn(List)} behaves as follows.</p>
 * <ul>
 *   <li>A {@code null} attribute yields a {@code null} column value.</li>
 *   <li>Elements that are {@code null}, or empty once trimmed, are omitted.</li>
 *   <li>Retained elements are escaped as described above, then joined with a comma. Their
 *       content is otherwise unaltered: it is not trimmed, re-cased, sorted, de-duplicated or
 *       re-ordered.</li>
 *   <li>An attribute that retains no elements yields a {@code null} column value, never
 *       the empty string.</li>
 * </ul>
 *
 * <h2>Read contract</h2>
 * <p>{@link #convertToEntityAttribute(String)} behaves as follows.</p>
 * <ul>
 *   <li>{@code null} or blank column data yields an empty list.</li>
 *   <li>Any other value is split on its unescaped commas and each token is unescaped. Tokens
 *       that are empty once trimmed are dropped, which is what a hand-edited value carrying a
 *       repeated or trailing comma produces.</li>
 *   <li>The result is never {@code null} and never contains an empty string.</li>
 *   <li>The result is always a new mutable list. Callers may modify it, and successive
 *       calls share no state.</li>
 * </ul>
 *
 * <h2>Round trip</h2>
 * <p>Reading back a written value returns the written list element for element, with each
 * element's content byte-for-byte identical, for any list whose elements are non-{@code null}
 * and not blank. That holds for an element containing a comma, a backslash, a double quote, a
 * non-ASCII character or interior whitespace. Two kinds of element are not carried through, and
 * both are stated in the write contract above: a {@code null} element and an element that is
 * blank once trimmed are each read back as absent, so a list consisting only of those reads back
 * empty.</p>
 *
 * <p>Neither method throws for any input. Instances hold no mutable state and are
 * thread-safe.</p>
 */
@Converter
public class DelimitedStringListConverter implements AttributeConverter<List<String>, String> {

    /** Separates elements in the column value. */
    private static final char DELIMITER = ',';

    /** Removes the meaning of the character that follows it in the column value. */
    private static final char ESCAPE = '\\';

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
        if (attribute == null) {
            return null;
        }
        StringBuilder columnValue = new StringBuilder();
        for (String element : attribute) {
            if (element == null || element.trim().isEmpty()) {
                continue;
            }
            if (!columnValue.isEmpty()) {
                columnValue.append(DELIMITER);
            }
            appendEscaped(columnValue, element);
        }
        // An empty result identifies an attribute that retained no element.
        return columnValue.isEmpty() ? null : columnValue.toString();
    }

    /**
     * Converts the single delimited column value into a list attribute.
     *
     * @param dbData the column value, may be {@code null}, blank, or contain empty tokens
     * @return a new mutable list of unescaped, non-empty elements; empty when {@code dbData}
     *         holds no such element. Never {@code null}
     */
    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        List<String> attribute = new ArrayList<>();
        if (dbData == null || dbData.isBlank()) {
            return attribute;
        }
        StringBuilder element = new StringBuilder();
        for (int position = 0; position < dbData.length(); position++) {
            char character = dbData.charAt(position);
            if (character == ESCAPE && position + 1 < dbData.length()) {
                // The escape consumes the next character, whatever it is.
                element.append(dbData.charAt(++position));
            } else if (character == DELIMITER) {
                addIfNotBlank(attribute, element);
            } else if (character != ESCAPE) {
                element.append(character);
            }
            // A trailing escape carries no character to release and contributes nothing.
        }
        addIfNotBlank(attribute, element);
        return attribute;
    }

    /**
     * Appends one element, escaping the delimiter and the escape character itself.
     *
     * @param columnValue the column value under construction
     * @param element     the element to append; not {@code null}
     */
    private static void appendEscaped(StringBuilder columnValue, String element) {
        for (int position = 0; position < element.length(); position++) {
            char character = element.charAt(position);
            if (character == DELIMITER || character == ESCAPE) {
                columnValue.append(ESCAPE);
            }
            columnValue.append(character);
        }
    }

    /**
     * Adds the accumulated element to the attribute unless it is blank, and clears the
     * accumulator either way.
     *
     * @param attribute the attribute under construction
     * @param element   the accumulated element, cleared by this call
     */
    private static void addIfNotBlank(List<String> attribute, StringBuilder element) {
        String candidate = element.toString();
        if (!candidate.trim().isEmpty()) {
            attribute.add(candidate);
        }
        element.setLength(0);
    }
}
