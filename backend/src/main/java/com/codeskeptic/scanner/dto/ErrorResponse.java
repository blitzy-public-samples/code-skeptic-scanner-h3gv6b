package com.codeskeptic.scanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Ported from backend/app/main.py:L31-37 (faithful port of the 404/500 error envelopes) — see docs/DECISION_LOG.md
/**
 * Error body of every failing endpoint in the service.
 *
 * <p>Serialised form — a single-key JSON object:</p>
 *
 * <pre>{@code
 * {"error": "Not found"}
 * }</pre>
 *
 * <p>The record declares exactly one component, {@code error}. Jackson writes that
 * component under the wire name {@code error} and reads the same name back. The
 * serialised object carries that one key and no other.</p>
 *
 * <p>Provenance — the retired Python tree emitted ten distinct error bodies and each
 * one was a single-key {@code {'error': <string>}} object:</p>
 * <ul>
 *   <li>{@code backend/app/main.py:L31-37} — the two global handlers:
 *       {@code {"error": "Not found"}} at status 404, and
 *       {@code {"error": "Internal server error"}} at status 500.</li>
 *   <li>{@code backend/app/api/tweets.py:L32} and {@code :L43} — two bodies, both at
 *       status 404.</li>
 *   <li>{@code backend/app/api/responses.py:L31} and {@code :L65} at status 404,
 *       {@code :L41} and {@code :L57} at status 400, {@code :L49} at status 500.</li>
 *   <li>{@code backend/app/api/settings.py:L18} at status 400, {@code :L22} at
 *       status 404.</li>
 *   <li>{@code backend/app/api/analytics.py} — no error body.</li>
 * </ul>
 *
 * <p>This record declares no message text of its own. Each message literal lives at its
 * throwing site under {@code com.codeskeptic.scanner.exception}, and
 * {@code com.codeskeptic.scanner.api.GlobalExceptionHandler} places that message into
 * the {@code error} component.</p>
 *
 * @param error message text carried under the JSON key {@code error}
 */
public record ErrorResponse(@JsonProperty("error") String error) {
}
