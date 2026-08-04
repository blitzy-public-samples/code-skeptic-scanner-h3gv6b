package com.codeskeptic.scanner.exception;

/**
 * Signals that generation of a reply for a tweet did not complete, and is reported with HTTP 500.
 *
 * <p>{@link #getMessage()} always returns {@value #FAILED_TO_GENERATE_RESPONSE}, the literal at the
 * source call site ({@code backend/app/api/responses.py:L49}). The message is fixed by this type and
 * cannot be supplied, extended or replaced by a caller, so no OpenAI, database or other
 * infrastructure text can reach the client through it.
 *
 * <p>A throwable passed to {@link #ResponseGenerationException(Throwable)} is returned by
 * {@link #getCause()} and does not appear in {@link #getMessage()}.
 *
 * <p>Serialization: no instance crosses a serialization boundary — an instance is created, thrown,
 * caught by the error-handling advice in the same JVM and rendered as JSON. The type is serializable
 * through {@link RuntimeException} and declares a fixed {@code serialVersionUID}.
 */
// Ported from the inline HTTP 500 branch at backend/app/api/responses.py:L46,L49 (faithful port) —
// see docs/DECISION_LOG.md
public final class ResponseGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Wire literal of {@code backend/app/api/responses.py:L49}; the only message this type emits. */
    public static final String FAILED_TO_GENERATE_RESPONSE = "Failed to generate response";

    /**
     * Creates the exception carrying the wire literal for the reporting branch.
     */
    public ResponseGenerationException() {
        super(FAILED_TO_GENERATE_RESPONSE);
    }

    /**
     * Creates the exception carrying the wire literal and the throwable that triggered it.
     *
     * @param cause the underlying throwable; it is returned by {@link #getCause()} and is absent
     *              from {@link #getMessage()}
     */
    public ResponseGenerationException(Throwable cause) {
        super(FAILED_TO_GENERATE_RESPONSE, cause);
    }
}
