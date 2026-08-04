package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Ported from backend/app/main.py:L31-37 (faithful port of the 404/500 error envelopes) — see docs/DECISION_LOG.md
/**
 * Error body of every failing endpoint in the service.
 *
 * <p>Serialised form — a single-key JSON object carrying that one key and no other:
 *
 * <pre>{@code {"error": "Not found"}}</pre>
 *
 * <p>The retired Python tree emitted ten error bodies and every one was a single-key
 * {@code {'error': <string>}} object: the two global handlers at
 * {@code backend/app/main.py:L31-37}, and the per-route bodies at
 * {@code backend/app/api/tweets.py:L32,L43}, {@code backend/app/api/responses.py:L31,L41,L49,L57,L65}
 * and {@code backend/app/api/settings.py:L18,L22}. Full per-branch mapping is recorded in
 * docs/TRACEABILITY_MATRIX.md.
 *
 * <p>This record declares no message text of its own; each message literal lives at its throwing site
 * under {@code com.codeskeptic.scanner.exception}.
 *
 * @param error message text carried under the JSON key {@code error}
 */
public record ErrorResponse(@JsonProperty("error") String error) {
}
