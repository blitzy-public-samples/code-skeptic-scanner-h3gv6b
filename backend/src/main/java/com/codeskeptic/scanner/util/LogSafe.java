package com.codeskeptic.scanner.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Net-new shared log-metadata utility — see docs/DECISION_LOG.md DL-119
/**
 * Produces fixed, non-reversible metadata for diagnostic log events.
 */
public final class LogSafe {

    private static final String SHA_256 = "SHA-256";
    private static final int CORRELATION_BYTES = 8;

    private LogSafe() {
    }

    /**
     * Returns a stable correlation token without reproducing the supplied value.
     *
     * @param value an identifier or external resource value, possibly {@code null}
     * @return {@code absent}, {@code empty}, or a truncated SHA-256 token
     */
    public static String correlation(Object value) {
        if (value == null) {
            return "absent";
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "empty";
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
        return failure == null ? "absent" : failure.getClass().getSimpleName();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
