package com.codeskeptic.scanner.exception;

// Materialises the HTTP 404 branches of the retired Flask controllers, each of which was an inline
// `return jsonify({'error': <literal>}), 404`:
//   backend/app/api/tweets.py:L32     GET  /tweets/<tweet_id>          'Tweet not found'
//   backend/app/api/tweets.py:L43     POST /tweets/<tweet_id>/analyze  'Tweet not found'
//   backend/app/api/responses.py:L31  GET  /responses/<response_id>    'Response not found'
//   backend/app/api/responses.py:L65  PUT  /responses/<response_id>    'Response not found or update failed'
//   backend/app/api/settings.py:L22   PUT  /settings/<key>             'Setting not found'
// See docs/DECISION_LOG.md DL-048.

/**
 * Signals that a requested entity was not located.
 *
 * <p>{@code com.codeskeptic.scanner.api.GlobalExceptionHandler} maps this type to HTTP status 404
 * and emits the construction message verbatim as the {@code error} value of the response body. The
 * message is held unaltered - it is not normalised, truncated or deduplicated - so every literal
 * round-trips through {@link #getMessage()} character-for-character.
 *
 * <p>Four literals reach this type from five call branches: {@code Tweet not found},
 * {@code Response not found}, {@code Response not found or update failed} and
 * {@code Setting not found}. The two response-scoped literals stay separate messages at the same
 * status, the shorter one reporting the read branch and the longer one the update branch.
 *
 * <p>An unparseable path variable is reported through this type as well (DL-048).
 */
public class NotFoundException extends RuntimeException {

    /**
     * Creates an exception reporting an absent entity.
     *
     * @param message the wire literal for the reporting branch, emitted unaltered as the
     *                {@code error} value of the HTTP 404 response body
     */
    public NotFoundException(String message) {
        super(message);
    }
}
