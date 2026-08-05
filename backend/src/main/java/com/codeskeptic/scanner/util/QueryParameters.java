package com.codeskeptic.scanner.util;

// Ported from the `type=int` conversion of request.args.get at backend/app/api/tweets.py:L12-13 and
// backend/app/api/responses.py:L11-12 (faithful port) — DL-193, DL-217 — see docs/DECISION_LOG.md
/**
 * Converts a raw query-parameter value into an {@code int}, falling back to a default.
 *
 * <p>{@link #intOrDefault(String, int)} reproduces the conversion the retired Flask handlers
 * performed. {@code request.args.get('page', 1, type=int)} at
 * {@code backend/app/api/tweets.py:L12} and the three sibling calls at {@code :L13} and
 * {@code backend/app/api/responses.py:L11-12} hand the raw value to {@code int()} and return the
 * supplied default whenever that conversion raises, so an absent parameter, an empty value and a
 * value carrying anything other than a number all read as the default and the request is served.
 *
 * <p>Every member is static, the type holds no state and is not instantiable, and conversion mutates
 * nothing. This type is safe for concurrent use.
 */
public final class QueryParameters {

    private QueryParameters() {
    }

    /**
     * Converts one raw query-parameter value into an {@code int}.
     *
     * <p>The default is returned for a {@code null} value, which is an absent parameter; for a blank
     * value, which is a parameter present with nothing after the {@code =}; for a value carrying any
     * character a decimal {@code int} cannot hold, which includes a fractional value, a hexadecimal
     * value and a value carrying a unit; and for a value beyond the range of an {@code int}.
     * Surrounding whitespace is discarded before the conversion, matching {@code int()}, and a leading
     * sign is accepted.
     *
     * <p>Examples: {@code null} and {@code ""} and {@code "abc"} and {@code "3.5"} and
     * {@code "99999999999999999999"} all read as {@code defaultValue}; {@code " 3 "} and {@code "+3"}
     * read as {@code 3}; {@code "-2"} reads as {@code -2}.
     *
     * @param rawValue     the value as the request carried it, or {@code null} when the request
     *                     carried none
     * @param defaultValue the value to return when {@code rawValue} carries no {@code int}
     * @return the converted value, or {@code defaultValue}
     */
    public static int intOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notAnInteger) {
            return defaultValue;
        }
    }
}
