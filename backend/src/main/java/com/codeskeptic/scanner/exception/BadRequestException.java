package com.codeskeptic.scanner.exception;

/**
 * Signals a request that the service rejects with HTTP 400.
 *
 * <p>{@link #getMessage()} returns the construction message unaltered — it is not normalised,
 * truncated or deduplicated — so each wire literal round-trips character-for-character.
 *
 * <p>The set of client-visible messages this type can carry is closed: it is exactly the three
 * literals declared as constants here, and each is reached through its own factory —
 * {@link #tweetIdRequired()} carries {@value #TWEET_ID_IS_REQUIRED}
 * ({@code backend/app/api/responses.py:L41}), {@link #updateDataRequired()} carries
 * {@value #UPDATE_DATA_IS_REQUIRED} ({@code backend/app/api/responses.py:L57}) and
 * {@link #noValueProvided()} carries {@value #NO_VALUE_PROVIDED}
 * ({@code backend/app/api/settings.py:L18}).
 *
 * <p>The constructor is private. No caller-supplied text, and no text taken from a database driver,
 * an external vendor response or any other throwable, can become the client-visible message. A
 * triggering throwable is attached with {@link #initCause(Throwable)} by
 * {@link #withCause(Throwable)} and is reachable through {@link #getCause()} only; it never appears
 * in {@link #getMessage()}.
 *
 * <p>Serialization: no instance crosses a serialization boundary — an instance is created, thrown,
 * caught by the error-handling advice in the same JVM and rendered as JSON. The type is serializable
 * through {@link RuntimeException} and declares a fixed {@code serialVersionUID}.
 */
// Ported from the inline HTTP 400 branches at backend/app/api/responses.py:L41,L57 and
// backend/app/api/settings.py:L18 (faithful port) — DL-212 — see docs/DECISION_LOG.md
public final class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Wire literal of {@code backend/app/api/responses.py:L41}. */
    public static final String TWEET_ID_IS_REQUIRED = "Tweet ID is required";

    /** Wire literal of {@code backend/app/api/responses.py:L57}. */
    public static final String UPDATE_DATA_IS_REQUIRED = "Update data is required";

    /** Wire literal of {@code backend/app/api/settings.py:L18}. */
    public static final String NO_VALUE_PROVIDED = "No value provided";

    /**
     * Creates the exception carrying the wire literal for the reporting branch.
     *
     * @param message one of the three approved wire literals declared by this type
     */
    private BadRequestException(String message) {
        super(message);
    }

    /**
     * Reports a {@code POST /responses} body that carries no tweet identifier.
     *
     * @return an exception whose message is {@value #TWEET_ID_IS_REQUIRED}
     */
    public static BadRequestException tweetIdRequired() {
        return new BadRequestException(TWEET_ID_IS_REQUIRED);
    }

    /**
     * Reports a {@code PUT /responses/{responseId}} body that carries no update data.
     *
     * @return an exception whose message is {@value #UPDATE_DATA_IS_REQUIRED}
     */
    public static BadRequestException updateDataRequired() {
        return new BadRequestException(UPDATE_DATA_IS_REQUIRED);
    }

    /**
     * Reports a {@code PUT /settings/{key}} body that carries no value.
     *
     * @return an exception whose message is {@value #NO_VALUE_PROVIDED}
     */
    public static BadRequestException noValueProvided() {
        return new BadRequestException(NO_VALUE_PROVIDED);
    }

    /**
     * Attaches a triggering throwable for server-side logging and returns this exception.
     *
     * <p>The message is unchanged. The cause is not rendered by {@link #getMessage()}.
     *
     * @param cause the throwable that triggered this report; ignored when {@code null}
     * @return this exception
     */
    public BadRequestException withCause(Throwable cause) {
        if (cause != null && getCause() == null) {
            initCause(cause);
        }
        return this;
    }
}
