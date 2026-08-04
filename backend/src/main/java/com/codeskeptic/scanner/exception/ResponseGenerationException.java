package com.codeskeptic.scanner.exception;

/**
 * Signals that generation of a reply for a tweet did not complete.
 *
 * <p>Materialises the HTTP 500 branch of {@code backend/app/api/responses.py:L49}. In that
 * source construct the {@code POST /responses} route declared at {@code :L33} evaluated the
 * result of {@code response_service.generate_response(tweet_id)} from {@code :L44} in the
 * guard at {@code :L46}, and on a falsey result returned the literal
 * {@code Failed to generate response} with status 500.
 *
 * <p>Contract: {@link #getMessage()} returns the message supplied at construction unchanged,
 * and {@code api/GlobalExceptionHandler} maps this type to HTTP 500, emitting that message
 * verbatim as the {@code error} value of the response body. A cause passed to the
 * two-argument constructor is returned by {@link #getCause()} and does not appear in
 * {@link #getMessage()}.
 *
 * <p>See docs/DECISION_LOG.md.
 */
public class ResponseGenerationException extends RuntimeException {

    /**
     * Creates an exception whose message is emitted verbatim as the {@code error} value of the
     * HTTP 500 response body.
     *
     * @param message the error text; {@code Failed to generate response} at the
     *                {@code POST /responses} call site
     */
    public ResponseGenerationException(String message) {
        super(message);
    }

    /**
     * Creates an exception whose message is emitted verbatim as the {@code error} value of the
     * HTTP 500 response body, retaining the throwable that triggered it.
     *
     * @param message the error text; {@code Failed to generate response} at the
     *                {@code POST /responses} call site
     * @param cause   the underlying throwable; it is returned by {@link #getCause()} and is
     *                absent from {@link #getMessage()}
     */
    public ResponseGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
