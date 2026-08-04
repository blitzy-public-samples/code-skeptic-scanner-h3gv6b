package com.codeskeptic.scanner.exception;

/**
 * Signals a request that the service rejects with HTTP 400.
 *
 * <p>The message supplied to the constructor is emitted verbatim as the {@code error}
 * value of the JSON response body. {@code api/GlobalExceptionHandler} maps this type to
 * HTTP 400 and passes the message through unaltered.
 */
// Materialises the inline HTTP 400 branches of
// backend/app/api/responses.py:L41 ('Tweet ID is required'),
// backend/app/api/responses.py:L57 ('Update data is required') and
// backend/app/api/settings.py:L18 ('No value provided'), each of which was a
// `return jsonify({'error': ...}), 400`.
// See docs/DECISION_LOG.md.
public class BadRequestException extends RuntimeException {

    /**
     * Creates an exception carrying the message to be returned to the caller.
     *
     * @param message text emitted verbatim as the {@code error} value of the HTTP 400
     *                response body
     */
    public BadRequestException(String message) {
        super(message);
    }
}
