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
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;

// Ported from backend/app/api/tweets.py:L1-55 (faithful port) — see docs/DECISION_LOG.md
/**
 * Serves the three HTTP routes of the {@code tweets} resource.
 *
 * <p>Replaces the Flask blueprint {@code tweets_bp}, declared at
 * {@code backend/app/api/tweets.py:L7} and registered on the application object at
 * {@code backend/app/main.py:L26}. The three routes declared here are the three the blueprint
 * declared.
 *
 * <table border="1">
 * <caption>Route surface</caption>
 * <tr><th>Method and path</th><th>Handler</th><th>Success</th><th>Source</th></tr>
 * <tr>
 *   <td>{@code GET /tweets}</td>
 *   <td>{@link #getTweets(int, int)}</td>
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
 * <p>All three paths are unprefixed, as the blueprint spelled them: no {@code /api} segment and no
 * version segment. Each path is spelled in full on its own handler, and no route beyond these three
 * is declared.
 *
 * <p>{@code GET /tweets} reads two query parameters, {@code page} and {@code per_page}, whose wire
 * names and defaults of 1 and 10 are those of {@code backend/app/api/tweets.py:L12-13}. Both bound
 * values reach the service exactly as received: neither is bounded, rounded or re-based here. The
 * conversion between the 1-based wire page number and the 0-based repository page index, and the
 * counters of the {@code pagination} block, belong to {@code service.TwitterService} and
 * {@code dto.PaginationDto} — see docs/DECISION_LOG.md DL-038.
 *
 * <p>The path variable of the two addressed routes is bound as a {@link String}, the type Flask's
 * default path converter delivered at {@code backend/app/api/tweets.py:L23} and {@code :L36}. A
 * value of any spelling reaches the service, and a value that names no row is reported as absent —
 * see docs/DECISION_LOG.md DL-048.
 *
 * <p>This class selects the status 200 and builds no error body. The error status of the source
 * routes is produced away from here: {@code NotFoundException} carrying {@code Tweet not found}, the
 * wire literal of {@code backend/app/api/tweets.py:L32} and {@code :L43}, is raised by
 * {@code service.TwitterService.getTweet} and {@code service.TwitterService.updateTweetAnalysis} for
 * an identifier that does not parse, is {@code null}, or parses but addresses no row, and
 * {@link GlobalExceptionHandler} answers it with 404 and that same literal.
 *
 * <p>On the analyze route the row is read before the sentiment request is issued, the order of
 * {@code backend/app/api/tweets.py:L40} ahead of {@code :L45-46}, so the absent-row branch of
 * {@code :L42-43} is reported without any external call being made.
 *
 * <p>{@code TwitterService()} was instantiated per request at {@code backend/app/api/tweets.py:L15},
 * {@code :L26} and {@code :L39}, and {@code SentimentAnalysis()} at {@code :L45}. Both are injected
 * singletons here, held in final fields.
 *
 * <p>{@code tweet.to_dict()}, invoked at {@code backend/app/api/tweets.py:L19} and {@code :L30}, was
 * defined nowhere in the retired tree. That conversion is performed by
 * {@code service.mapper.TweetMapper} through {@code service.TwitterService}; this class performs no
 * conversion and reads no repository. Every wire key is snake_case and every identifier serialises
 * as a string, as declared by the {@code dto} records — see docs/DECISION_LOG.md DL-022 and DL-023.
 *
 * <p>Authentication is enforced by the security filter chain, which runs ahead of the
 * {@code DispatcherServlet}, in place of the bare {@code @jwt_required} at
 * {@code backend/app/api/tweets.py:L10}, {@code :L24} and {@code :L37} — see docs/DECISION_LOG.md
 * DL-021.
 *
 * <p>No route declared here, and no collaborator reached from here, publishes to X. The two injected
 * services read the {@code tweets} table, the {@code settings} table and the Natural Language API;
 * neither exposes a publish operation.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-021, DL-022,
 * DL-023, DL-036, DL-037, DL-038, DL-048 and DL-059; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Both collaborators are held in final fields and are themselves
 * singletons, and this class holds no other state, so every member declared here is safe for
 * concurrent use.
 */
@RestController
public class TweetController {

    /** Records the values bound from the request before the collaborators read them. */
    private static final Logger log = LoggerFactory.getLogger(TweetController.class);

    /** Reads the {@code tweets} table and writes the {@code doubt_rating} column. */
    private final TwitterService twitterService;

    /** Requests the document sentiment score of a supplied text. */
    private final SentimentAnalysisService sentimentAnalysisService;

    /**
     * Creates the controller with the two collaborators the source instantiated per request at
     * {@code backend/app/api/tweets.py:L15,L26,L39} and {@code :L45}.
     *
     * @param twitterService           the service serving all three routes, must not be {@code null}
     * @param sentimentAnalysisService the service serving the analyze route, must not be
     *                                 {@code null}
     * @throws NullPointerException when either argument is {@code null}
     */
    public TweetController(TwitterService twitterService,
            SentimentAnalysisService sentimentAnalysisService) {
        this.twitterService = Objects.requireNonNull(twitterService,
                "twitterService must not be null.");
        this.sentimentAnalysisService = Objects.requireNonNull(sentimentAnalysisService,
                "sentimentAnalysisService must not be null.");
    }

    // backend/app/api/tweets.py:L9 — defaults 1 / 10 at :L12-13; pagination conversion owned by
    // service.TwitterService — DL-038
    /**
     * Renders one page of the {@code tweets} table together with the block that describes it.
     *
     * <p>Reproduces {@code GET /tweets} at {@code backend/app/api/tweets.py:L9-21}. The body is the
     * two-key envelope of {@code :L18-21}: {@code tweets} carries one object per row of the page and
     * {@code pagination} carries the page counters. The status is 200 for every accepted request,
     * including one whose page holds no row.
     *
     * <p>The two query parameters are read under the wire names {@code page} and {@code per_page},
     * the spellings of {@code :L12-13}, and their defaults are 1 and 10, the defaults declared on
     * those two lines. An absent parameter takes its default; the request is accepted either way.
     * The snake_case spelling is the only spelling bound: a query parameter of any other spelling is
     * ignored and the default applies.
     *
     * <p>Both bound values are passed on as received. This method applies no minimum, no maximum and
     * no re-basing; {@code service.TwitterService.getPaginatedTweets} reads a 1-based
     * {@code page} and requests the matching 0-based repository index, and the {@code page} value it
     * reports back is 1-based — see docs/DECISION_LOG.md DL-038.
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
     * @param page    the 1-based page number, read from the {@code page} query parameter; 1 when the
     *                parameter is absent
     * @param perPage the page size, read from the {@code per_page} query parameter; 10 when the
     *                parameter is absent
     * @return 200 carrying the {@code tweets} and {@code pagination} pair
     */
    @GetMapping("/tweets")
    public ResponseEntity<PaginatedTweetsDto> getTweets(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "10") int perPage) {
        log.debug("Serving GET /tweets for page {} of size {}.", page, perPage);

        // backend/app/api/tweets.py:L16 — absent from the source TwitterService class
        return ResponseEntity.ok(twitterService.getPaginatedTweets(page, perPage));
    }

    // backend/app/api/tweets.py:L23 — String path variable — DL-048; :L32 literal 'Tweet not found'
    /**
     * Renders the {@code tweets} row the path addresses.
     *
     * <p>Reproduces {@code GET /tweets/<tweet_id>} at {@code backend/app/api/tweets.py:L23-32}. The
     * body is the wire form of the addressed row, rendered unwrapped, matching the bare
     * {@code jsonify(tweet.to_dict())} at {@code :L30}.
     *
     * <p>The path value is bound as a {@link String} and is handed to the service exactly as
     * received: it is not parsed, normalised, trimmed or padded here. A value that does not parse as
     * a number, and a value that parses but addresses no row, are both reported as absent, which
     * {@link GlobalExceptionHandler} answers with 404 and the body
     * {@code {"error":"Tweet not found"}}, the literal of {@code :L32} — see
     * docs/DECISION_LOG.md DL-048.
     *
     * <p>The route reads no query parameter and no request body, as at {@code :L23}.
     *
     * <p>Example response body:
     *
     * <pre>{@code
     * {"id":"1","content":"...","like_count":120,"created_at":"2026-08-01T12:00:00",
     *  "doubt_rating":6.5,"media":[],"quoted_tweet_id":null,"user_id":"42",
     *  "ai_tools_mentioned":["GPT-4"]}
     * }</pre>
     *
     * @param tweetId the {@code tweetId} path value, as received
     * @return 200 carrying the wire form of the addressed row
     */
    @GetMapping("/tweets/{tweetId}")
    public ResponseEntity<TweetDto> getTweet(@PathVariable("tweetId") String tweetId) {
        // backend/app/api/tweets.py:L27 — absent from the source TwitterService class
        return ResponseEntity.ok(twitterService.getTweet(tweetId));
    }

    // backend/app/api/tweets.py:L36 — String path variable — DL-048; :L43 literal 'Tweet not found'
    /**
     * Scores the text of the addressed {@code tweets} row, records the derived doubt rating and
     * renders the score.
     *
     * <p>Reproduces {@code POST /tweets/<tweet_id>/analyze} at
     * {@code backend/app/api/tweets.py:L36-55}. The route reads no request body, no query parameter
     * and no header; the path value is its only input, bound as a {@link String} exactly as
     * {@link #getTweet(String)} binds it — see docs/DECISION_LOG.md DL-048.
     *
     * <p>Three steps run in the order of the source handler:
     *
     * <ol>
     *   <li>the addressed row is read ({@code :L40}), so an identifier that does not parse or
     *       addresses no row is reported as absent before any external call is made, which is the
     *       branch at {@code :L42-43} and which {@link GlobalExceptionHandler} answers with 404 and
     *       the body {@code {"error":"Tweet not found"}};</li>
     *   <li>the text of that row is scored ({@code :L46}), the parameter being the tweet text — see
     *       docs/DECISION_LOG.md DL-036;</li>
     *   <li>the score is handed to {@code service.TwitterService.updateTweetAnalysis}
     *       ({@code :L50}), which derives the doubt rating and writes the {@code doubt_rating}
     *       column. The derivation is declared once, on
     *       {@code service.SentimentAnalysisService.calculateDoubtRating}, and is not restated
     *       here.</li>
     * </ol>
     *
     * <p>The body carries the two keys of {@code :L52-55}. {@code tweet_id} is the path value as
     * received, taken from the path and not from the row that was read — see
     * docs/DECISION_LOG.md DL-037. {@code analysis_result} is the document sentiment score itself,
     * the single value the scoring method returns; the derived doubt rating is not part of this
     * body.
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
        // backend/app/api/tweets.py:L40 — the absent-row branch of :L42-43 is reported from here
        TweetDto tweet = twitterService.getTweet(tweetId);

        // backend/app/api/tweets.py:L46 — the argument is the tweet text — DL-036
        double analysisResult = sentimentAnalysisService.analyzeSentiment(tweet.content());

        // backend/app/api/tweets.py:L50 — absent from the source TwitterService class
        twitterService.updateTweetAnalysis(tweetId, analysisResult);

        log.info("Served an analysis of one tweet row with document sentiment score {}.",
                analysisResult);

        // backend/app/api/tweets.py:L53 — tweet_id is the path string as received — DL-037
        return ResponseEntity.ok(new AnalysisResultDto(tweetId, analysisResult));
    }
}
