package com.codeskeptic.scanner.util;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Net-new shared log-metadata utility — see docs/DECISION_LOG.md DL-119 and DL-149
/**
 * Produces fixed, non-reversible metadata for diagnostic log events.
 *
 * <p>{@link #logSafe(String)} is the guard every caller-supplied value passes through before it
 * reaches a log record — see docs/DECISION_LOG.md DL-149. {@link #correlation(Object)},
 * {@link #token(String)} and {@link #type(Throwable)} render an identifier, a provider-published
 * machine-readable field and a failure as fixed metadata — DL-119.
 *
 * <p>{@link #correlation(Object)} keys its digest with a random secret this process generates at class
 * initialisation, so a token is stable for the life of the process and carries no relation to the same
 * value's token in any other process — DL-119. {@link #typeChain(Throwable)},
 * {@link #originFrame(Throwable)} and {@link #failureDetail(Throwable)} render a failure's shape
 * without its message, and the last of the three renders every message it carries through
 * {@link #logSafe(String, int)} — DL-197.
 */
public final class LogSafe {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int CORRELATION_BYTES = 8;
    private static final int CORRELATION_KEY_BYTES = 32;

    /**
     * Rendering prefix of a correlation token, naming the primitive that produced it — DL-119.
     */
    private static final String CORRELATION_PREFIX = "hmac256:";

    /**
     * Secret keying every correlation token this process renders. It is 32 random bytes drawn from
     * {@link SecureRandom} once, at class initialisation, and is never rendered, logged or configured
     * — DL-119 — see docs/DECISION_LOG.md.
     */
    private static final SecretKeySpec CORRELATION_KEY = randomCorrelationKey();

    /** Package whose frames {@link #originFrame(Throwable)} reports first — DL-197. */
    private static final String APPLICATION_PACKAGE = "com.codeskeptic.scanner";

    /** Links of a cause chain {@link #typeChain(Throwable)} renders — DL-197. */
    private static final int TYPE_CHAIN_LIMIT = 5;

    /** Separator between two links of a rendered cause chain. */
    private static final String CAUSE_SEPARATOR = " <- ";

    /** Rendered in place of the links beyond {@value #TYPE_CHAIN_LIMIT}. */
    private static final String CHAIN_CONTINUES = "...";

    /** Frames {@link #failureDetail(Throwable)} renders for the outermost failure — DL-197. */
    private static final int DETAIL_FRAME_LIMIT = 10;

    /** Longest sanitized failure message {@link #failureDetail(Throwable)} carries — DL-197. */
    private static final int DETAIL_MESSAGE_LIMIT = 256;

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

    /** Accepted shape of a provider-published machine-readable field — DL-119. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_.\\-\\[\\]]{1,64}");

    private LogSafe() {
    }

    // Log-injection guard applied to every caller-supplied value — see docs/DECISION_LOG.md DL-149
    /**
     * Renders a caller-supplied or externally supplied value for a log record.
     *
     * <p>Every character outside printable ASCII — which includes the carriage return and line feed a
     * decoded {@code %0D%0A} carries — becomes {@value #REPLACEMENT}, and the value is truncated to
     * {@value #LOG_VALUE_LIMIT} characters. A caller forges no record boundary and floods no record
     * with an unbounded value.
     *
     * @param value the caller-supplied value, possibly {@code null}
     * @return {@code absent}, {@code empty}, or the guarded rendering; never {@code null}
     */
    public static String logSafe(String value) {
        return logSafe(value, LOG_VALUE_LIMIT);
    }

    // Log-injection guard with a caller-chosen bound — see docs/DECISION_LOG.md DL-149, DL-197
    /**
     * Renders a caller-supplied or externally supplied value for a log record, truncated to a bound
     * the caller chooses.
     *
     * <p>The guarding is that of {@link #logSafe(String)}: every character outside printable ASCII —
     * which includes the carriage return and line feed a decoded {@code %0D%0A} carries — becomes
     * {@value #REPLACEMENT}. Only the length bound differs, so a caller whose own contract fixes a
     * longer bound than {@value #LOG_VALUE_LIMIT} keeps that bound without a second implementation of
     * the guard.
     *
     * @param value the caller-supplied value, possibly {@code null}
     * @param limit the greatest number of characters to carry, at least {@code 1}
     * @return {@code absent}, {@code empty}, or the guarded rendering; never {@code null}
     * @throws IllegalArgumentException if {@code limit} is below one
     */
    public static String logSafe(String value, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1.");
        }
        if (value == null) {
            return ABSENT;
        }
        if (value.isEmpty()) {
            return EMPTY;
        }
        int length = Math.min(value.length(), limit);
        StringBuilder guarded = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            guarded.append((character >= FIRST_PRINTABLE_ASCII && character <= LAST_PRINTABLE_ASCII)
                    ? character
                    : REPLACEMENT);
        }
        return guarded.toString();
    }

    // Correlation token keyed with a per-process secret — DL-119 — see docs/DECISION_LOG.md
    /**
     * Returns a correlation token for a value, without reproducing any part of it.
     *
     * <p>The token is {@value #CORRELATION_PREFIX} followed by the first {@value #CORRELATION_BYTES}
     * bytes of an HMAC-SHA-256 over the value's text, keyed with {@link #CORRELATION_KEY}. Two calls
     * carrying equal text return equal tokens for as long as this process lives, and a restart or a
     * second replica renders the same text under a different key, so a token cannot be confirmed
     * against a dictionary of candidate values and cannot be joined across processes or environments —
     * DL-119.
     *
     * @param value an identifier or external resource value, possibly {@code null}
     * @return {@code absent}, {@code empty}, or a keyed, truncated token
     */
    public static String correlation(Object value) {
        if (value == null) {
            return ABSENT;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return EMPTY;
        }
        byte[] digest = hmac().doFinal(text.getBytes(StandardCharsets.UTF_8));
        return CORRELATION_PREFIX + HexFormat.of().formatHex(digest, 0, CORRELATION_BYTES);
    }

    // Shared shape check for every provider-published machine-readable field — DL-119, DL-084
    /**
     * Renders a provider-published machine-readable field for a log record.
     *
     * <p>The value is carried literally only when, with surrounding whitespace removed, it holds one
     * to {@value #LOG_VALUE_LIMIT} characters drawn from {@code A-Za-z0-9}, {@code _}, {@code .},
     * {@code -}, {@code [} and {@code ]} — the shape an enumerated error identifier, an opaque
     * request identifier and a parameter path all take. Anything else, including any value carrying a
     * carriage return or a line feed, is reported as {@value #ABSENT} and is not rendered, so no
     * free text can reach a log record through a provider field and no provider value can forge a
     * record boundary.
     *
     * @param value the provider-published field, possibly {@code null}
     * @return the field, or {@code absent} when the provider carried none of that shape
     */
    public static String token(String value) {
        if (value == null) {
            return ABSENT;
        }
        String trimmed = value.trim();
        return TOKEN_SHAPE.matcher(trimmed).matches() ? trimmed : ABSENT;
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

    // Bounded failure metadata for a log record — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders a failure's type and the types of its causes, and no message of any of them.
     *
     * <p>Up to {@value #TYPE_CHAIN_LIMIT} links are rendered as simple type names joined by
     * {@code " <- "}, outermost first; a deeper chain ends {@value #CHAIN_CONTINUES}. A chain that
     * refers back to a failure already rendered stops at that point, so a cyclic cause cannot loop.
     *
     * @param failure the failure, possibly {@code null}
     * @return the rendered chain, or {@code absent}
     */
    public static String typeChain(Throwable failure) {
        if (failure == null) {
            return ABSENT;
        }
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        Throwable slow = failure;
        int rendered = 0;
        boolean advanceSlow = false;
        while (current != null && rendered < TYPE_CHAIN_LIMIT) {
            if (rendered > 0) {
                chain.append(CAUSE_SEPARATOR);
            }
            chain.append(current.getClass().getSimpleName());
            rendered++;
            current = current.getCause();
            if (advanceSlow) {
                slow = slow.getCause();
            }
            advanceSlow = !advanceSlow;
            if (current != null && current == slow) {
                return chain.append(CAUSE_SEPARATOR).append(CHAIN_CONTINUES).toString();
            }
        }
        if (current != null) {
            chain.append(CAUSE_SEPARATOR).append(CHAIN_CONTINUES);
        }
        return chain.toString();
    }

    // Bounded failure metadata for a log record — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders the frame a failure was raised at, taking one belonging to this application first.
     *
     * <p>The first frame whose declaring class lies under {@value #APPLICATION_PACKAGE} is rendered as
     * {@code SimpleClass.method:line}; when the stack holds none, the topmost frame is rendered the
     * same way. A frame carries a class name, a method name and a line number and no data of any
     * request, so nothing caller-supplied can reach a record through it.
     *
     * @param failure the failure, possibly {@code null}
     * @return the rendered frame, or {@code absent} when the failure or its stack is empty
     */
    public static String originFrame(Throwable failure) {
        if (failure == null) {
            return ABSENT;
        }
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames == null || frames.length == 0) {
            return ABSENT;
        }
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith(APPLICATION_PACKAGE)) {
                return renderFrame(frame);
            }
        }
        return renderFrame(frames[0]);
    }

    // Sanitized failure detail for the diagnostic level — DL-197 — see docs/DECISION_LOG.md
    /**
     * Renders a failure's chain with each message sanitized, and a bounded number of frames.
     *
     * <p>Each of up to {@value #TYPE_CHAIN_LIMIT} links is rendered as
     * {@code SimpleType[message]}, where the message passes through
     * {@link #logSafe(String, int)} at {@value #DETAIL_MESSAGE_LIMIT} characters — so no message can
     * forge a record boundary and none is carried unbounded. Up to
     * {@value #DETAIL_FRAME_LIMIT} frames of the outermost failure follow.
     *
     * <p>This rendering is for a diagnostic level only: the record an unhandled failure leaves at
     * {@code ERROR} carries {@link #typeChain(Throwable)} and {@link #originFrame(Throwable)} and no
     * message at all — DL-197.
     *
     * @param failure the failure, possibly {@code null}
     * @return the rendered detail, or {@code absent}
     */
    public static String failureDetail(Throwable failure) {
        if (failure == null) {
            return ABSENT;
        }
        StringBuilder detail = new StringBuilder();
        Throwable current = failure;
        int rendered = 0;
        while (current != null && rendered < TYPE_CHAIN_LIMIT) {
            if (rendered > 0) {
                detail.append(CAUSE_SEPARATOR);
            }
            detail.append(current.getClass().getSimpleName())
                    .append('[')
                    .append(logSafe(current.getMessage(), DETAIL_MESSAGE_LIMIT))
                    .append(']');
            rendered++;
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        if (current != null) {
            detail.append(CAUSE_SEPARATOR).append(CHAIN_CONTINUES);
        }

        StackTraceElement[] frames = failure.getStackTrace();
        if (frames != null && frames.length > 0) {
            detail.append(" at ");
            int frameCount = Math.min(frames.length, DETAIL_FRAME_LIMIT);
            for (int index = 0; index < frameCount; index++) {
                if (index > 0) {
                    detail.append(", ");
                }
                detail.append(renderFrame(frames[index]));
            }
            if (frames.length > frameCount) {
                detail.append(", ").append(CHAIN_CONTINUES);
            }
        }
        return detail.toString();
    }

    /**
     * Renders one stack frame as {@code SimpleClass.method:line}.
     *
     * @param frame the frame to render, never {@code null}
     * @return the rendered frame
     */
    private static String renderFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');
        String simpleName = (lastDot < 0) ? className : className.substring(lastDot + 1);
        return simpleName + '.' + frame.getMethodName() + ':' + frame.getLineNumber();
    }

    /**
     * Draws the secret that keys every correlation token of this process.
     *
     * @return a 32-byte HMAC-SHA-256 key
     */
    private static SecretKeySpec randomCorrelationKey() {
        byte[] key = new byte[CORRELATION_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, HMAC_SHA_256);
    }

    /**
     * Returns a {@link Mac} initialised with this process's correlation key.
     *
     * <p>A new instance is returned per call, since {@link Mac} holds the state of one computation.
     *
     * @return an initialised MAC
     */
    private static Mac hmac() {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(CORRELATION_KEY);
            return mac;
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }
}
