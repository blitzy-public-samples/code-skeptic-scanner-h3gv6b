package com.codeskeptic.scanner.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// Net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-024
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
 * <h2>Write contract</h2>
 * <p>{@link #convertToDatabaseColumn(List)} behaves as follows.</p>
 * <ul>
 *   <li>A {@code null} attribute yields a {@code null} column value.</li>
 *   <li>Elements that are {@code null}, or empty once trimmed, are omitted.</li>
 *   <li>Retained elements are trimmed, then joined with a comma.</li>
 *   <li>An attribute that retains no elements yields a {@code null} column value, never
 *       the empty string.</li>
 * </ul>
 *
 * <h2>Read contract</h2>
 * <p>{@link #convertToEntityAttribute(String)} behaves as follows.</p>
 * <ul>
 *   <li>{@code null} or blank column data yields an empty list.</li>
 *   <li>Any other value is split on the comma, each token is trimmed, and tokens that are
 *       empty once trimmed are dropped.</li>
 *   <li>The result is never {@code null} and never contains an empty string.</li>
 *   <li>The result is always a new mutable list. Callers may modify it, and successive
 *       calls share no state.</li>
 * </ul>
 *
 * <h2>Round trip</h2>
 * <p>Reading back a written value produces the trimmed, blank-free form of the original
 * attribute. Two kinds of element are not carried through verbatim: an element containing
 * the comma is read back as separate elements, and an element that is still blank once
 * trimmed is read back as absent.</p>
 *
 * <p>Neither method throws for any input. Instances hold no mutable state and are
 * thread-safe.</p>
 */
@Converter
public class DelimitedStringListConverter implements AttributeConverter<List<String>, String> {

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
        if (attribute == null) {
            return null;
        }
        // An empty join result identifies an attribute that retained no element.
        String columnValue = attribute.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(element -> !element.isEmpty())
                .collect(Collectors.joining(DELIMITER));
        return columnValue.isEmpty() ? null : columnValue;
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
        // "".split(DELIMITER) yields one empty token. The blank check excludes that input
        // from the split path. The token filter below drops empty tokens from every other
        // input, including a value consisting only of delimiters and whitespace.
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(dbData.split(DELIMITER))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
