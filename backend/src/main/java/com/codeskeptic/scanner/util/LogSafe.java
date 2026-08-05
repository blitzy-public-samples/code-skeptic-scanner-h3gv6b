package com.codeskeptic.scanner.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Net-new shared log-metadata utility — see docs/DECISION_LOG.md DL-119 and DL-149
/**
 * Produces fixed, non-reversible metadata for diagnostic log events.
 *
 * <p>{@link #logSafe(String)} is the guard every caller-supplied value passes through before it
 * reaches a log record — see docs/DECISION_LOG.md DL-149. {@link #correlation(Object)} and
 * {@link #type(Throwable)} render an identifier and a failure as fixed metadata — DL-119.
 */
public final class LogSafe {

    private static final String SHA_256 = "SHA-256";
    private static final int CORRELATION_BYTES = 8;

    /** Rendering of a {@code null} value. */
    private static final String ABSENT = "absent";

    /** Rendering of an empty value. */
    private static final String EMPTY = "empty";

    /** Longest guarded value a log record carries — DL-149. */
    private static final int LOG_VALUE_LIMIT = 64;

    /** Lowest code point carried literally by {@link #logSafe(String)}. */
    private static final char FIRST_PRINTABLE_ASCII = ' ';

    /** Highest code point carried literally by {@link #logSafe(String)}. */
    private static final char LAST_PRINTABLE_ASCII = '~';

    /** Rendered in place of a character outside printable ASCII — DL-149. */
    private static final char REPLACEMENT = '?';

    private LogSafe() {
    }

    // Log-injection guard applied to every caller-supplied value — see docs/DECISION_LOG.md DL-149
    /**
     * Renders a caller-supplied or externally supplied value for a log record.
     *
     * <p>Every character outside printable ASCII — which includes the carriage return and line feed a
     * decoded {@code %0D%0A} carries — becomes {@value #REPLACEMENT}, and the value is truncated to
     * {@value #LOG_VALUE_LIMIT} characters. A caller can therefore neither forge a record boundary nor
     * flood a record with an unbounded value.
     *
     * @param value the caller-supplied value, possibly {@code null}
     * @return {@code absent}, {@code empty}, or the guarded rendering; never {@code null}
     */
    public static String logSafe(String value) {
        if (value == null) {
            return ABSENT;
        }
        if (value.isEmpty()) {
            return EMPTY;
        }
        int length = Math.min(value.length(), LOG_VALUE_LIMIT);
        StringBuilder guarded = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            guarded.append((character >= FIRST_PRINTABLE_ASCII && character <= LAST_PRINTABLE_ASCII)
                    ? character
                    : REPLACEMENT);
        }
        return guarded.toString();
    }

    /**
     * Returns a stable correlation token without reproducing the supplied value.
     *
     * @param value an identifier or external resource value, possibly {@code null}
     * @return {@code absent}, {@code empty}, or a truncated SHA-256 token
     */
    public static String correlation(Object value) {
        if (value == null) {
            return ABSENT;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return EMPTY;
        }
        byte[] digest = sha256().digest(text.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(digest, 0, CORRELATION_BYTES);
    }

    /**
     * Returns the runtime type of a failure without including its message.
     *
     * @param failure the failure, possibly {@code null}
     * @return the simple type name, or {@code absent}
     */
    public static String type(Throwable failure) {
        return failure == null ? ABSENT : failure.getClass().getSimpleName();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
