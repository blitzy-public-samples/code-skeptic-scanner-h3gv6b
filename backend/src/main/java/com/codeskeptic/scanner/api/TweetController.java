package com.codeskeptic.scanner.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codeskeptic.scanner.dto.AnalysisResultDto;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.service.TwitterService;

// Endpoint contract ported from backend/app/api/tweets.py:L9-55 (faithful port) — see
// docs/DECISION_LOG.md DL-021, DL-022, DL-023, DL-036, DL-037, DL-038, DL-048, DL-059
/**
 * Serves the three HTTP routes of the {@code tweets} resource.
 *
 * <ul>
 *   <li>{@code GET /tweets} — {@link #getTweets(String, String)}, 200 with the two-key envelope
 *       ({@code backend/app/api/tweets.py:L9-21}).</li>
 *   <li>{@code GET /tweets/{tweetId}} — {@link #getTweet(String)}, 200 with one JSON object
 *       ({@code backend/app/api/tweets.py:L23-32}).</li>
 *   <li>{@code POST /tweets/{tweetId}/analyze} — {@link #analyzeTweet(String)}, 200 with a two-key
 *       object ({@code backend/app/api/tweets.py:L36-55}).</li>
 * </ul>
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code GET /tweets}</td>
 *   <td>{@link #getTweets(String, String)}</td>
 *   <td>200, a two-key envelope</td>
 *   <td>{@code backend/app/api/tweets.py:L9-21}</td>
 * </tr>
 * <tr>
 *   <td>{@code GET /tweets/{tweetId}}</td>
 *   <td>{@link #getTweet(String)}</td>
 *   <td>200, one JSON object</td>
 *   <td>{@code backend/app/api/tweets.py:L23-32}</td>
 * </tr>
 * <tr>
 *   <td>{@code POST /tweets/{tweetId}/analyze}</td>
 *   <td>{@link #analyzeTweet(String)}</td>
 *   <td>200, a two-key object</td>
 *   <td>{@code backend/app/api/tweets.py:L36-55}</td>
 * </tr>
 * </table>
 *
 * <p>Paths are unprefixed: no {@code /api} segment and no version segment.
 *
 * <p>{@code GET /tweets} reads the query parameters {@code page} and {@code per_page}, whose wire
 * names and defaults of 1 and 10 are those of {@code backend/app/api/tweets.py:L12-13}, and passes
 * both to the service unchanged. The 1-based wire page to 0-based repository index conversion and
 * the {@code pagination} counters belong to {@code service.TwitterService} and
 * {@code dto.PaginationDto} — DL-038, DL-217.
 *
 * <p>The path variable of the two addressed routes is bound as a {@link String}, the type Flask's
 * default path converter delivered at {@code backend/app/api/tweets.py:L23} and {@code :L36} —
 * DL-048.
 *
 * <p>This class selects status 200 and builds no error body. {@code service.TwitterService} raises
 * {@code NotFoundException} carrying {@code Tweet not found} — the wire literal of
 * {@code backend/app/api/tweets.py:L32} and {@code :L43} — for an identifier that does not parse, is
 * {@code null}, or parses but addresses no row, and {@link GlobalExceptionHandler} answers it with
 * 404 and that literal.
 *
 * <p>On the analyze route the row is read before the sentiment request is issued, the order of
 * {@code backend/app/api/tweets.py:L40} ahead of {@code :L45-46}.
 *
 * <p>Both collaborators are injected singletons held in final fields, in place of the per-request
 * {@code TwitterService()} at {@code backend/app/api/tweets.py:L15}, {@code :L26} and {@code :L39}
 * and {@code SentimentAnalysis()} at {@code :L45}. The conversion invoked as {@code tweet.to_dict()}
 * at {@code :L19} and {@code :L30} is performed by {@code service.mapper.TweetMapper} through
 * {@code service.TwitterService}; this class performs no conversion and reads no repository. Wire
 * keys are snake_case and identifiers serialise as strings — DL-022, DL-023.
 *
 * <p>Authentication is enforced by the security filter chain, which runs ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/tweets.py:L10}, {@code :L24} and {@code :L37} — DL-021.
 *
 * <p>No route declared here, and no collaborator reached from here, publishes to X.
 *
 * <p>This is a singleton bean holding both collaborators in final fields and no other state, so
 * every member declared here is safe for concurrent use.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-022,
 * DL-023, DL-036, DL-037, DL-038, DL-048, DL-059 and DL-217; construct-level provenance is recorded
 * in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@RestController
public class TweetController {

    private static final Logger log = LoggerFactory.getLogger(TweetController.class);

    /** Page number applied when {@code page} carries no number — {@code backend/app/api/tweets.py:L12}. */
    private static final int DEFAULT_PAGE = 1;

    /** Page size applied when {@code per_page} carries no number — {@code backend/app/api/tweets.py:L13}. */
    private static final int DEFAULT_PER_PAGE = 10;

    /**
     * Reads the {@code tweets} table, orchestrates the analyze route and writes the
     * {@code doubt_rating} column.
     */
    private final TwitterService twitterService;

    /**
     * Creates the controller with its single collaborator.
     *
     * <p>Scoring belongs to {@code service.TwitterService}, which orchestrates the read, the provider
     * call and the write of the analyze route in one operation — DL-263.
     *
     * @param twitterService the service serving all three routes, must not be {@code null}
     * @throws NullPointerException when the argument is {@code null}
     */
    public TweetController(TwitterService twitterService) {
        this.twitterService = Objects.requireNonNull(twitterService,
                "twitterService must not be null.");
    }

    // Ported from backend/app/api/tweets.py:L9-21 (faithful port) — DL-038, DL-217 — see
    // docs/DECISION_LOG.md
    /**
     * Renders one page of the {@code tweets} table together with the block that describes it.
     *
     * <p>Reproduces {@code GET /tweets} at {@code backend/app/api/tweets.py:L9-21}. The body is the
     * two-key envelope of {@code :L18-21}: {@code tweets} carries one object per row of the page and
     * {@code pagination} carries the page counters. The status is 200 for every accepted request,
     * including one whose page holds no row.
     *
     * <p>The two query parameters are read under the wire names {@code page} and {@code per_page} of
     * {@code :L12-13}, with the defaults 1 and 10 declared there. An absent parameter takes its
     * default and the request is accepted. Only those two spellings are bound; a query parameter of
     * any other spelling is ignored and the default applies.
     *
     * <p>Each parameter is bound as text and read as a whole number after trimming. A value that
     * holds no whole number — the empty string, a whitespace-only value, {@code abc}, {@code 2.5},
     * a value beyond {@code int} range — takes the same default an absent parameter takes and the
     * request is accepted; no such value is reported as a client error — see docs/DECISION_LOG.md
     * DL-217. A value that holds a whole number is passed on as received, including {@code 0} and a
     * negative value: this method applies no minimum, no maximum and no re-basing.
     * {@code service.TwitterService.getPaginatedTweets} reads a 1-based {@code page} and requests the
     * matching 0-based repository index, and the {@code page} value it reports back is 1-based — see
     * docs/DECISION_LOG.md DL-038. That method is also where a page size below one is read as the
     * route default; no page size is reduced and the status stays 200 — see docs/DECISION_LOG.md
     * DL-217.
     *
     * <p>Example request: {@code GET /tweets} carrying the query string {@code page=3} with
     * {@code per_page=25}, which reads the third page of 25 rows.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"tweets":[{"id":"1","content":"...","like_count":120,"created_at":"2026-08-01T12:00:00",
     *             "doubt_rating":6.5,"media":[],"quoted_tweet_id":null,"user_id":"42",
     *             "ai_tools_mentioned":["GPT-4"]}],
     *  "pagination":{"page":3,"per_page":25,"total":120,"total_pages":5}}
     * }</pre>
     *
     * @param rawPage    the {@code page} query parameter exactly as the request carried it, or
     *                   {@code null} when the request carried none
     * @param rawPerPage the {@code per_page} query parameter exactly as the request carried it, or
     *                   {@code null} when the request carried none
     * @return 200 carrying the {@code tweets} and {@code pagination} pair
     */
    @GetMapping("/tweets")
    public ResponseEntity<PaginatedTweetsDto> getTweets(
            @RequestParam(name = "page", required = false) String rawPage,
            @RequestParam(name = "per_page", required = false) String rawPerPage) {

        // backend/app/api/tweets.py:L12-13 — request.args.get(..., type=int) returns the default when
        // the conversion raises — DL-217 — see docs/DECISION_LOG.md
        int page = intOrDefault(rawPage, DEFAULT_PAGE);
        int perPage = intOrDefault(rawPerPage, DEFAULT_PER_PAGE);

        log.debug("Serving GET /tweets for page {} of size {}.", page, perPage);

        return ResponseEntity.ok(twitterService.getPaginatedTweets(page, perPage));
    }

    // Query-parameter conversion parity with request.args.get(..., type=int) at
    // backend/app/api/tweets.py:L12-13 — see docs/DECISION_LOG.md DL-048

    // backend/app/api/tweets.py:L23 — String path variable — DL-048; :L32 literal 'Tweet not found'
    /**
     * Renders the {@code tweets} row the path addresses.
     *
     * <p>Reproduces {@code GET /tweets/<tweet_id>} at {@code backend/app/api/tweets.py:L23-32}. The
     * body is the wire form of the addressed row, rendered unwrapped, as at {@code :L30}.
     *
     * <p>The path value is bound as a {@link String} and is handed to the service exactly as
     * received: it is not parsed, normalised, trimmed or padded here. A value that does not parse as
     * a number, and a value that parses but addresses no row, are both reported as absent, which
     * {@link GlobalExceptionHandler} answers with 404 and the body
     * {@code {"error":"Tweet not found"}}, the literal of {@code :L32} — DL-048.
     *
     * <p>The route reads no query parameter and no request body.
     *
     * @param tweetId the {@code tweetId} path value, as received
     * @return 200 carrying the wire form of the addressed row
     */
    @GetMapping("/tweets/{tweetId}")
    public ResponseEntity<TweetDto> getTweet(@PathVariable("tweetId") String tweetId) {
        return ResponseEntity.ok(twitterService.getTweet(tweetId));
    }

    // Ported from backend/app/api/tweets.py:L36-55 (faithful port) — DL-036, DL-037, DL-048 — see
    // docs/DECISION_LOG.md
    /**
     * Scores the text of the addressed {@code tweets} row, records the derived doubt rating and
     * renders the score.
     *
     * <p>Reproduces {@code POST /tweets/<tweet_id>/analyze} at
     * {@code backend/app/api/tweets.py:L36-55}. The route reads no request body, no query parameter
     * and no header; the path value is its only input, bound as a {@link String} — see
     * docs/DECISION_LOG.md DL-048.
     *
     * <p>Three steps run in the order of the source handler:
     *
     * <ol>
     *   <li>the addressed row is read ({@code :L40}); an identifier that does not parse, and one
     *       that addresses no row, are reported as absent with no external call made, the branch at
     *       {@code :L42-43}, which {@link GlobalExceptionHandler} answers with 404 and the body
     *       {@code {"error":"Tweet not found"}};</li>
     *   <li>the text of that row is scored ({@code :L46}) — see docs/DECISION_LOG.md DL-036;</li>
     *   <li>the score is handed to {@code service.TwitterService.updateTweetAnalysis}
     *       ({@code :L50}), which derives the doubt rating and writes the {@code doubt_rating}
     *       column.</li>
     * </ol>
     *
     * <p>All three steps run on every invocation, as at {@code backend/app/api/tweets.py:L36-55}: the
     * text is scored again and the {@code doubt_rating} column is written again, whether the row
     * already carried a rating or not.
     *
     * <p>The body carries the two keys of {@code :L52-55}. {@code tweet_id} is the path value as
     * received, taken from the path and not from the row that was read — see
     * docs/DECISION_LOG.md DL-037. {@code analysis_result} is the document sentiment score; the
     * derived doubt rating is not part of this body.
     *
     * <p>Example response body:
     *
     * <pre>{@code {"tweet_id":"1","analysis_result":-0.4}}</pre>
     *
     * @param tweetId the {@code tweetId} path value, as received
     * @return 200 carrying the addressed identifier and the document sentiment score
     */
    @PostMapping("/tweets/{tweetId}/analyze")
    public ResponseEntity<AnalysisResultDto> analyzeTweet(@PathVariable("tweetId") String tweetId) {
        // One orchestration: the row is read once, scored with no transaction open, and written once
        // — DL-263 — see docs/DECISION_LOG.md
        double analysisResult = twitterService.analyzeTweet(tweetId);

        log.info("Served an analysis of one tweet row with document sentiment score {}.",
                analysisResult);

        return ResponseEntity.ok(new AnalysisResultDto(tweetId, analysisResult));
    }


    // Query-parameter conversion of request.args.get(..., type=int) at
    // backend/app/api/tweets.py:L12-13 — see docs/DECISION_LOG.md DL-217
    /**
     * Converts one raw query-parameter value into an {@code int}.
     *
     * <p>The default is returned for a {@code null} value, which is an absent parameter; for a blank
     * value, which is a parameter present with nothing after the {@code =}; for a value carrying any
     * character a decimal {@code int} cannot hold, which includes a fractional value, a hexadecimal
     * value and a value carrying a unit; and for a value beyond the range of an {@code int}. That is
     * the fallback behaviour of Werkzeug's {@code type=int} conversion, which the retired handlers
     * relied on. Surrounding whitespace is discarded and a leading sign is accepted.
     *
     * @param rawValue     the value as the request carried it, or {@code null} when the request
     *                     carried none
     * @param defaultValue the value to return when {@code rawValue} carries no {@code int}
     * @return the converted value, or {@code defaultValue}
     */
    private static int intOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notAnInteger) {
            return defaultValue;
        }
    }

}
