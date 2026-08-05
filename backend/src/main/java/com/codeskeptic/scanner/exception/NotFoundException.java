package com.codeskeptic.scanner.exception;

/**
 * Signals that a requested entity was not located, and is reported with HTTP 404.
 *
 * <p>{@link #getMessage()} returns the construction message unaltered — it is not normalised,
 * truncated or deduplicated — so each wire literal round-trips character-for-character.
 *
 * <p>The set of client-visible messages this type can carry is closed: it is exactly the four
 * literals declared as constants here, reached from five source branches, and each is carried by its
 * own factory — {@link #tweetNotFound()} carries {@value #TWEET_NOT_FOUND}
 * ({@code backend/app/api/tweets.py:L32} and {@code :L43}), {@link #responseNotFound()} carries
 * {@value #RESPONSE_NOT_FOUND} ({@code backend/app/api/responses.py:L31}),
 * {@link #responseNotFoundOrUpdateFailed()} carries
 * {@value #RESPONSE_NOT_FOUND_OR_UPDATE_FAILED} ({@code backend/app/api/responses.py:L65}) and
 * {@link #settingNotFound()} carries {@value #SETTING_NOT_FOUND}
 * ({@code backend/app/api/settings.py:L22}). The two response-scoped literals stay separate messages
 * at the same status, the shorter one reporting the read branch and the longer one the update branch.
 *
 * <p>An unparseable path variable is reported through this type as well — see docs/DECISION_LOG.md
 * DL-048.
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
// Ported from the inline HTTP 404 branches at backend/app/api/tweets.py:L32,L43,
// backend/app/api/responses.py:L31,L65 and backend/app/api/settings.py:L22 (faithful port) — see
// docs/DECISION_LOG.md DL-048, DL-212
public final class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Wire literal of {@code backend/app/api/tweets.py:L32} and {@code :L43}. */
    public static final String TWEET_NOT_FOUND = "Tweet not found";

    /** Wire literal of {@code backend/app/api/responses.py:L31}. */
    public static final String RESPONSE_NOT_FOUND = "Response not found";

    /** Wire literal of {@code backend/app/api/responses.py:L65}. */
    public static final String RESPONSE_NOT_FOUND_OR_UPDATE_FAILED = "Response not found or update failed";

    /** Wire literal of {@code backend/app/api/settings.py:L22}. */
    public static final String SETTING_NOT_FOUND = "Setting not found";

    /**
     * Creates the exception carrying the wire literal for the reporting branch.
     *
     * @param message one of the four approved wire literals declared by this type
     */
    private NotFoundException(String message) {
        super(message);
    }

    /**
     * Reports an absent tweet.
     *
     * @return an exception whose message is {@value #TWEET_NOT_FOUND}
     */
    public static NotFoundException tweetNotFound() {
        return new NotFoundException(TWEET_NOT_FOUND);
    }

    /**
     * Reports an absent response on the read branch.
     *
     * @return an exception whose message is {@value #RESPONSE_NOT_FOUND}
     */
    public static NotFoundException responseNotFound() {
        return new NotFoundException(RESPONSE_NOT_FOUND);
    }

    /**
     * Reports an absent response on the update branch.
     *
     * @return an exception whose message is {@value #RESPONSE_NOT_FOUND_OR_UPDATE_FAILED}
     */
    public static NotFoundException responseNotFoundOrUpdateFailed() {
        return new NotFoundException(RESPONSE_NOT_FOUND_OR_UPDATE_FAILED);
    }

    /**
     * Reports an absent setting.
     *
     * @return an exception whose message is {@value #SETTING_NOT_FOUND}
     */
    public static NotFoundException settingNotFound() {
        return new NotFoundException(SETTING_NOT_FOUND);
    }

    /**
     * Attaches a triggering throwable for server-side logging and returns this exception.
     *
     * <p>The message is unchanged. The cause is not rendered by {@link #getMessage()}.
     *
     * @param cause the throwable that triggered this report; ignored when {@code null}
     * @return this exception
     */
    public NotFoundException withCause(Throwable cause) {
        if (cause != null && getCause() == null) {
            initCause(cause);
        }
        return this;
    }
}
