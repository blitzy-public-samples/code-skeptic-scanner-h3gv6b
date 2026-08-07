package com.codeskeptic.scanner.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.PaginationDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.mapper.TweetMapper;

// Ported from backend/app/services/twitter_service.py:L6-50 (faithful port) — see docs/DECISION_LOG.md
/**
 * Application service for the {@code tweets} table.
 *
 * <p>Six operations are exposed, each named by a caller that already existed in the retired module:
 * {@link #getPaginatedTweets(int, int)} backs {@code GET /tweets}; {@link #getTweet(String)} backs
 * {@code GET /tweets/{tweetId}}; {@link #analyzeTweet(String)} scores one row and records the derived
 * doubt rating, backing {@code POST /tweets/{tweetId}/analyze} — DL-263;
 * {@link #updateTweetAnalysis(String, double)} writes {@code doubt_rating} from a score the caller
 * already holds; {@link #meetsPopularityThreshold(Integer)} and
 * {@link #meetsPopularityThreshold(Integer, int)} evaluate the ingestion popularity gate against the
 * threshold in force and against one the caller resolved; and
 * {@link #popularityThresholdInForce()} resolves that threshold once per cycle — DL-255.
 *
 * <p>Every operation is a repository read, a repository write, or a comparison against a configured
 * threshold. The X protocol surface belongs to {@code task.TweetStreamClient} — DL-045.
 *
 * <p>{@code spring.jpa.open-in-view} is {@code false}. Every {@link Tweet} loaded here is converted to
 * its wire form by {@link TweetMapper} inside the transaction that loaded it, no operation returns an
 * entity, and the lazy {@link Tweet#getResponses()} collection is never traversed.
 *
 * <p>The doubt rating written by {@link #updateTweetAnalysis(String, double)} is produced by
 * {@link SentimentAnalysisService#calculateDoubtRating(double)} — DL-036.
 *
 * <p>Collaborators arrive through the constructor and are held for the lifetime of the singleton, in
 * place of the {@code TwitterService()} instantiation performed inside each request handler at
 * {@code backend/app/api/tweets.py:L14,L26,L39} and the {@code get_settings()} call performed inside
 * the constructor at {@code backend/app/services/twitter_service.py:L11}. The class carries no mutable
 * state, and every method is safe for concurrent use.
 *
 * <p>Two further methods of the retired class are implemented elsewhere:
 * {@code stream_tweets} at {@code backend/app/services/twitter_service.py:L19-23} by
 * {@code task.TweetStreamClient}, and {@code process_tweet} at {@code :L25-40} by {@link TweetMapper}
 * together with {@code task.TweetStreamListener}.
 */
@Service
public class TwitterService {

    private static final Logger log = LoggerFactory.getLogger(TwitterService.class);

    // Row key seeded by SettingsService — DL-040 — see docs/DECISION_LOG.md
    /**
     * Primary key of the {@code settings} row whose value overrides
     * {@code scanner.popularity-threshold}.
     */
    private static final String POPULARITY_THRESHOLD_SETTING_KEY = "tweet_popularity_threshold";

    // Query-parameter defaults from backend/app/api/tweets.py:L12-13 — see docs/DECISION_LOG.md
    /** Wire value of {@code page} when the request omits it, and the lowest value accepted here. */
    private static final int DEFAULT_PAGE = 1;

    /** Wire value of {@code per_page} when the request omits it. */
    private static final int DEFAULT_PER_PAGE = 10;

    /**
     * Lowest {@code per_page} a page request accepts. A page size below it reads as
     * {@value #DEFAULT_PER_PAGE}: a paged query cannot express a page of no rows. No upper bound is
     * declared — DL-217.
     */
    private static final int MINIMUM_PER_PAGE = 1;

    /** Order of every page read: {@code tweets.id} ascending. */
    private static final Sort PAGE_ORDER = Sort.by(Sort.Direction.ASC, "id");

    private final TweetRepository tweetRepository;

    private final SettingRepository settingRepository;

    private final ScannerProperties properties;

    private final TweetMapper tweetMapper;

    private final SentimentAnalysisService sentimentAnalysisService;

    /**
     * Value of the {@value #POPULARITY_THRESHOLD_SETTING_KEY} row that the last unparseable-threshold
     * warning was recorded for, {@code null} until one has been recorded — DL-255.
     */
    private final AtomicReference<String> lastUnparseableThreshold = new AtomicReference<>();

    /**
     * Creates the bean with its five collaborators.
     *
     * @param tweetRepository          data access for the {@code tweets} table, must not be
     *                                 {@code null}
     * @param settingRepository        data access for the {@code settings} table, must not be
     *                                 {@code null}
     * @param properties               bound configuration supplying the popularity threshold, must
     *                                 not be {@code null}
     * @param tweetMapper              entity-to-wire converter, must not be {@code null}
     * @param sentimentAnalysisService supplier of the doubt rating, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Replaces __init__ at backend/app/services/twitter_service.py:L10-14, which built an
    // OAuthHandler and a tweepy API client (faithful port of the injection points only) — see
    // docs/DECISION_LOG.md DL-012
    public TwitterService(TweetRepository tweetRepository,
            SettingRepository settingRepository,
            ScannerProperties properties,
            TweetMapper tweetMapper,
            SentimentAnalysisService sentimentAnalysisService) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.tweetMapper = Objects.requireNonNull(tweetMapper, "tweetMapper must not be null.");
        this.sentimentAnalysisService = Objects.requireNonNull(sentimentAnalysisService,
                "sentimentAnalysisService must not be null.");
    }

    // Net-new implementation of the method called at backend/app/api/tweets.py:L16 — see
    // docs/DECISION_LOG.md DL-038
    /**
     * Renders one page of the {@code tweets} table together with the pagination block that describes
     * it.
     *
     * <p>{@code page} is 1-based, matching the wire parameter whose default is declared at
     * {@code backend/app/api/tweets.py:L12}, and is converted to the 0-based index the repository
     * takes. Requesting page 1 reads repository index {@code 0}, and the
     * {@link PaginationDto#page()} of the result restates the 1-based number.
     *
     * <p>Arguments outside the accepted range are replaced and the replacement is logged at
     * {@code WARN}: a {@code page} below {@value #DEFAULT_PAGE} is read as {@value #DEFAULT_PAGE}, and
     * a {@code perPage} below {@value #MINIMUM_PER_PAGE} is read as {@value #DEFAULT_PER_PAGE} —
     * DL-217. No upper bound is applied to {@code perPage}, and the page size the pagination block
     * restates is the size served.
     *
     * <p>A {@code page} beyond the last populated page yields an empty {@link
     * PaginatedTweetsDto#tweets()} list while {@link PaginationDto#total()} and
     * {@link PaginationDto#totalPages()} continue to describe the whole table. An empty table yields
     * an empty list, a {@code total} of {@code 0} and a {@code totalPages} of {@code 0}. A
     * {@code page} whose first row lies beyond {@link Integer#MAX_VALUE} rows is answered the same
     * way, with the requested page number and page size restated — DL-225.
     *
     * <p>The page is read by one paged query ordered by {@code tweets.id} ascending, the rows are
     * converted inside this method's transaction, and the returned lists are unmodifiable.
     *
     * @param page    the 1-based page number requested through the {@code page} query parameter; a
     *                value below {@value #DEFAULT_PAGE} is read as {@value #DEFAULT_PAGE}
     * @param perPage the page size requested through the {@code per_page} query parameter; a value
     *                below {@value #MINIMUM_PER_PAGE} is read as {@value #DEFAULT_PER_PAGE} and no
     *                value is reduced
     * @return the {@code tweets} and {@code pagination} pair rendered by {@code GET /tweets}, never
     *         {@code null}
     */
    @Transactional(readOnly = true)
    public PaginatedTweetsDto getPaginatedTweets(int page, int perPage) {
        // A page number below the first page reads as the first page — DL-217 — see
        // docs/DECISION_LOG.md
        int effectivePage = (page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
        // A page size below one reads as the route default; no upper bound is applied — DL-217 — see
        // docs/DECISION_LOG.md
        int effectivePerPage = (perPage < MINIMUM_PER_PAGE) ? DEFAULT_PER_PAGE : perPage;
        if (effectivePage != page || effectivePerPage != perPage) {
            log.warn("Read requested page {} size {} as page {} size {}.",
                    page, perPage, effectivePage, effectivePerPage);
        }

        // Wire page numbers are 1-based and repository page indexes are 0-based — DL-038
        PageRequest requested =
                PageRequest.of(effectivePage - 1, effectivePerPage, PAGE_ORDER);

        List<TweetDto> tweets;
        long total;
        if (!withinQueryableOffset(requested)) {
            // A page whose first row lies past the offset the query can express is answered without a
            // paged query — DL-225 — see docs/DECISION_LOG.md
            tweets = List.of();
            total = tweetRepository.count();
        } else {
            Page<Tweet> tweetPage = tweetRepository.findAll(requested);
            tweets = tweetMapper.toDtoList(tweetPage.getContent());
            total = tweetPage.getTotalElements();
        }

        PaginationDto pagination = new PaginationDto(
                effectivePage,
                effectivePerPage,
                total,
                totalPages(total, effectivePerPage));

        log.debug("Rendering {} tweet row(s) for page {} of {}, {} row(s) in total.",
                tweets.size(), pagination.page(), pagination.totalPages(), pagination.total());
        return new PaginatedTweetsDto(tweets, pagination);
    }

    // Net-new implementation of the method called at backend/app/api/tweets.py:L27,L40 — see
    // docs/DECISION_LOG.md DL-048
    /**
     * Renders the {@code tweets} row carrying the given identifier.
     *
     * <p>The identifier is the path value exactly as the route received it. A value that does not parse
     * as an {@code int}, a {@code null} value, and a value that parses but matches no row are all
     * reported as a {@link NotFoundException} carrying {@link NotFoundException#TWEET_NOT_FOUND}, which
     * the error-handling advice renders as HTTP 404 with the body
     * {@code {"error": "Tweet not found"}} — DL-048.
     *
     * <p>The row is converted inside this method's transaction.
     *
     * @param tweetId the {@code tweetId} path value, as received
     * @return the wire form of the addressed row, never {@code null}
     * @throws NotFoundException when the identifier does not parse, or parses but addresses no row
     */
    @Transactional(readOnly = true)
    public TweetDto getTweet(String tweetId) {
        int identifier = parseTweetIdOrNotFound(tweetId);
        Tweet tweet = tweetRepository.findById(identifier)
                .orElseThrow(NotFoundException::tweetNotFound);
        log.debug("Rendering tweet row {}.", identifier);
        return tweetMapper.toDto(tweet);
    }

    // Net-new implementation of the method called at backend/app/api/tweets.py:L50, which the source
    // left unimplemented behind the note at :L48-49 — mapped by the scaffolding-marker row for
    // api/tweets.py:L34 in docs/TRACEABILITY_MATRIX.md — see docs/DECISION_LOG.md DL-037, DL-263
    /**
     * Writes the doubt rating derived from a document sentiment score onto the addressed
     * {@code tweets} row.
     *
     * <p>The rating is obtained from {@link SentimentAnalysisService#calculateDoubtRating(double)} and
     * stored in the {@code doubt_rating} column, which is the only column this method writes.
     *
     * <p>The identifier is handled as {@link #getTweet(String)} handles it: a value that does not
     * parse, a {@code null} value, and a value addressing no row all raise a
     * {@link NotFoundException} carrying {@link NotFoundException#TWEET_NOT_FOUND} — DL-048. Nothing is
     * written when the row is not found.
     *
     * <p>Nothing is returned. The caller of {@code POST /tweets/{tweetId}/analyze} builds its response
     * body from the path value and the score it already holds, as the handler at
     * {@code backend/app/api/tweets.py:L52-55} does — DL-037.
     *
     * @param tweetId        the {@code tweetId} path value, as received
     * @param analysisResult a document sentiment score, such as one returned by
     *                       {@link SentimentAnalysisService#analyzeSentiment(String)}
     * @throws NotFoundException when the identifier does not parse, or parses but addresses no row
     */
    public void updateTweetAnalysis(String tweetId, double analysisResult) {
        recordDoubtRating(parseTweetIdOrNotFound(tweetId), analysisResult);
    }

    // Net-new orchestration of the three steps of backend/app/api/tweets.py:L36-55 — DL-263 — see
    // docs/DECISION_LOG.md
    /**
     * Scores one {@code tweets} row and records the doubt rating derived from that score.
     *
     * <p>The three steps of {@code backend/app/api/tweets.py:L36-55} run here in their original order
     * and with their original outcomes, over one read and one write:
     *
     * <ol>
     *   <li>The addressed row's identifier and {@code content} are read in one statement. An
     *       identifier that does not parse, a {@code null} identifier and an identifier addressing no
     *       row all raise a {@link NotFoundException} carrying
     *       {@link NotFoundException#TWEET_NOT_FOUND} — DL-048, and nothing further happens.</li>
     *   <li>The text is scored by {@link SentimentAnalysisService#analyzeSentiment(String)}. No
     *       transaction is open and no database connection is held while that call runs.</li>
     *   <li>{@code doubt_rating} is written by one statement addressing the row by identifier, whether
     *       or not the row already carried a rating — DL-263.</li>
     * </ol>
     *
     * <p>A row deleted between the read and the write is reported with
     * {@link NotFoundException#TWEET_NOT_FOUND}, the same literal an unknown identifier produces.
     *
     * @param tweetId the {@code tweetId} path value, as received
     * @return the document sentiment score, which the caller carries as {@code analysis_result}
     * @throws NotFoundException when the identifier does not parse, or parses but addresses no row
     */
    public double analyzeTweet(String tweetId) {
        int identifier = parseTweetIdOrNotFound(tweetId);

        TweetRepository.AnalysisSubject subject = tweetRepository
                .findAnalysisSubjectById(identifier)
                .orElseThrow(NotFoundException::tweetNotFound);
        // dto/TweetDto declares content non-null — DL-080 — so the analyze route rejects a row that
        // carries none in exactly the way the wire form does
        String content = Objects.requireNonNull(subject.getContent(), "content must not be null.");

        double analysisResult = sentimentAnalysisService.analyzeSentiment(content);

        recordDoubtRating(identifier, analysisResult);

        return analysisResult;
    }

    /**
     * Derives the doubt rating from a document sentiment score and writes it by identifier.
     *
     * @param identifier     the parsed identifier of the row to write
     * @param analysisResult the document sentiment score
     * @throws NotFoundException when {@code identifier} addresses no row
     */
    // Net-new single-statement write — DL-263 — see docs/DECISION_LOG.md
    private void recordDoubtRating(int identifier, double analysisResult) {
        double doubtRating = sentimentAnalysisService.calculateDoubtRating(analysisResult);

        if (tweetRepository.updateDoubtRating(identifier, doubtRating) == 0) {
            log.debug("Reporting tweet identifier {} as a row that is not present.", identifier);
            throw NotFoundException.tweetNotFound();
        }

        log.info("Recorded doubt rating {} on tweet row {}.", doubtRating, identifier);
    }

    // Ported from check_popularity_threshold at backend/app/services/twitter_service.py:L42-50
    // (faithful port of the comparison at :L46 and the threshold read at :L43; the parameter is the
    // like count, called at backend/app/tasks/tweet_monitoring.py:L17) — see docs/DECISION_LOG.md
    // DL-040
    /**
     * Reports whether a like count reaches the popularity threshold.
     *
     * <p>The comparison is inclusive, reproducing {@code backend/app/services/twitter_service.py:L46}:
     * against the default threshold of {@code 100}, a count of {@code 99} does not reach it while
     * {@code 100} and {@code 101} do. The like count is the only quantity compared.
     *
     * <p>This overload resolves the threshold, which reads the {@code settings} table. A caller that
     * evaluates the gate repeatedly resolves it once with {@link #popularityThresholdInForce()} and
     * calls {@link #meetsPopularityThreshold(Integer, int)} instead — DL-255. The {@code settings} row
     * named {@value #POPULARITY_THRESHOLD_SETTING_KEY} takes precedence when it is present and holds an
     * integer; otherwise {@code scanner.popularity-threshold} applies, whose default is declared at
     * {@code backend/app/core/config.py:L10} — DL-040. A row that does not hold an integer is logged at
     * {@code WARN} and the configured value applies.
     *
     * <p>A {@code null} like count reports {@code false}.
     *
     * @param likeCount the value of the {@code like_count} column, or of the equivalent field of an
     *                  ingested payload; may be {@code null}
     * @return {@code true} when the like count is at least the resolved threshold, {@code false} when
     *         it is below the threshold or {@code null}
     */
    @Transactional(readOnly = true)
    public boolean meetsPopularityThreshold(Integer likeCount) {
        if (likeCount == null) {
            log.debug("Popularity gate reports false for an absent like count.");
            return false;
        }

        return meetsPopularityThreshold(likeCount, resolvePopularityThreshold());
    }

    // Net-new: the comparison alone, for a caller holding a resolved threshold — DL-255 — see
    // docs/DECISION_LOG.md
    /**
     * Reports whether a like count reaches an already resolved popularity threshold.
     *
     * <p>The comparison is the one of {@code backend/app/services/twitter_service.py:L46} and is
     * identical to the one {@link #meetsPopularityThreshold(Integer)} performs. This overload reads no
     * table and opens no transaction, so a caller that evaluates the gate once per delivered record
     * pays for one threshold resolution per cycle, and not one per record — DL-255.
     *
     * <p>A {@code null} like count reports {@code false}.
     *
     * @param likeCount          the value of the {@code like_count} column, or of the equivalent
     *                           field of an ingested payload; may be {@code null}
     * @param popularityThreshold the threshold to compare against, as returned by
     *                           {@link #popularityThresholdInForce()}
     * @return {@code true} when the like count is at least {@code popularityThreshold},
     *         {@code false} when it is below it or {@code null}
     */
    public boolean meetsPopularityThreshold(Integer likeCount, int popularityThreshold) {
        if (likeCount == null) {
            log.debug("Popularity gate reports false for an absent like count.");
            return false;
        }

        // backend/app/services/twitter_service.py:L46 — inclusive comparison
        boolean meetsThreshold = likeCount >= popularityThreshold;

        log.debug("Popularity gate reports {} for like count {} against threshold {}.",
                meetsThreshold, likeCount, popularityThreshold);
        return meetsThreshold;
    }

    // Net-new: one threshold resolution per ingestion cycle — DL-255 — see docs/DECISION_LOG.md
    /**
     * Resolves the popularity threshold in force and returns it.
     *
     * <p>The precedence is the one of {@link #meetsPopularityThreshold(Integer)}: the
     * {@value #POPULARITY_THRESHOLD_SETTING_KEY} {@code settings} row when it holds an integer, and
     * {@code scanner.popularity-threshold} otherwise.
     *
     * <p>The value is not retained: each call reads the table once, so a caller decides how often the
     * threshold is re-resolved — DL-255.
     *
     * @return the threshold a like count is compared against
     */
    @Transactional(readOnly = true)
    public int popularityThresholdInForce() {
        return resolvePopularityThreshold();
    }

    // Shared by getTweet and updateTweetAnalysis, both of which receive the path value of a route
    // declared with Flask's default string converter at backend/app/api/tweets.py:L23,L36 — see
    // docs/DECISION_LOG.md DL-048
    /**
     * Parses a path identifier into the repository identifier type.
     *
     * <p>A {@code null} value, a blank value and a value that is not an {@code int} all raise a
     * {@link NotFoundException} carrying {@link NotFoundException#TWEET_NOT_FOUND} — DL-048. A value
     * beyond the range of an {@code int} is reported the same way. The triggering
     * {@link NumberFormatException} is attached as the cause and never reaches the client-visible
     * message.
     *
     * @param tweetId the path value, as received
     * @return the parsed identifier
     * @throws NotFoundException when the value does not parse as an {@code int}
     */
    private int parseTweetIdOrNotFound(String tweetId) {
        if (tweetId == null) {
            log.debug("Reporting an absent tweet identifier as a row that is not present.");
            throw NotFoundException.tweetNotFound();
        }
        try {
            return Integer.parseInt(tweetId);
        } catch (NumberFormatException ex) {
            log.debug("Reporting a tweet identifier that does not parse as an integer as a row "
                    + "that is not present.");
            throw NotFoundException.tweetNotFound().withCause(ex);
        }
    }

    // Setting row overrides configuration, the precedence documented for
    // check_popularity_threshold in documentation/Code Structure.md — DL-040 — see
    // docs/DECISION_LOG.md
    /**
     * Resolves the popularity threshold currently in force.
     *
     * <p>The {@code settings} row named {@value #POPULARITY_THRESHOLD_SETTING_KEY} is read first and
     * its value, once surrounding whitespace is discarded, is used when it parses as an
     * {@code int}. The configured {@code scanner.popularity-threshold} is read only when that row is
     * absent, holds {@code null}, or holds a value that does not parse; the last of those cases is
     * logged at {@code WARN} and names the key without recording the stored value.
     *
     * <p>The warning is recorded once per distinct unparseable value: a value identical to the one the
     * last warning was recorded for is recorded at {@code DEBUG} instead, so a row that stays
     * malformed cannot fill the log — DL-255. A value that parses clears that memory, so the same
     * malformed value is reported again if it returns.
     *
     * <p>A negative stored threshold is honoured as stored — DL-040.
     *
     * @return the threshold to compare a like count against
     */
    private int resolvePopularityThreshold() {
        String storedThreshold = settingRepository.findById(POPULARITY_THRESHOLD_SETTING_KEY)
                .map(settingRow -> settingRow.getValue())
                .orElse(null);

        if (storedThreshold != null) {
            try {
                int parsed = Integer.parseInt(storedThreshold.trim());
                lastUnparseableThreshold.set(null);
                return parsed;
            } catch (NumberFormatException ex) {
                reportUnparseableThreshold(storedThreshold);
            }
        } else {
            lastUnparseableThreshold.set(null);
        }

        // backend/app/services/twitter_service.py:L43 — the configured threshold
        return properties.popularityThreshold();
    }

    // Net-new: one warning per distinct unparseable value — DL-255 — see docs/DECISION_LOG.md
    /**
     * Records that the {@value #POPULARITY_THRESHOLD_SETTING_KEY} row does not hold an integer.
     *
     * <p>The record is emitted at {@code WARN} when the offending value differs from the one the last
     * warning was recorded for, and at {@code DEBUG} otherwise. Neither record carries the stored
     * value; the value is held only to compare against the next one — DL-052.
     *
     * @param storedThreshold the value that failed to parse, never {@code null}
     */
    private void reportUnparseableThreshold(String storedThreshold) {
        String previous = lastUnparseableThreshold.getAndSet(storedThreshold);
        if (storedThreshold.equals(previous)) {
            log.debug("Setting '{}' still does not hold an integer; applying "
                    + "scanner.popularity-threshold instead.", POPULARITY_THRESHOLD_SETTING_KEY);
            return;
        }

        log.warn("Setting '{}' does not hold an integer; applying "
                + "scanner.popularity-threshold instead.", POPULARITY_THRESHOLD_SETTING_KEY);
    }

    // The offset ceiling org.springframework.data.jpa.support.PageableUtils enforces — DL-225 — see
    // docs/DECISION_LOG.md
    /**
     * Reports whether a page request names an offset a paged query can position its first row at.
     *
     * <p>The largest offset a paged query can express is {@link Integer#MAX_VALUE}, which it passes as
     * an {@code int}. The offset compared here is the 0-based page index multiplied by the page size,
     * computed as a {@code long} and free of overflow. An offset of exactly {@link Integer#MAX_VALUE}
     * is expressible and reads as {@code true} — DL-225.
     *
     * @param request the page request to test; must not be {@code null}
     * @return {@code true} when the request's offset is at most {@link Integer#MAX_VALUE}
     */
    private static boolean withinQueryableOffset(Pageable request) {
        return request.getOffset() <= Integer.MAX_VALUE;
    }

    // The total_pages member of the pagination envelope — DL-038 — see docs/DECISION_LOG.md
    /**
     * Returns the number of pages a page size divides a row total into, which is the
     * {@code total_pages} member of the pagination envelope.
     *
     * <p>The value is the one {@link org.springframework.data.domain.Page#getTotalPages()} reports for
     * the same total and size: the total divided by the size and rounded up. A total of {@code 0}
     * yields {@code 0}.
     *
     * @param total    the number of rows the table holds; never negative
     * @param pageSize the page size the envelope restates; at least one
     * @return the number of pages, never negative
     */
    private static int totalPages(long total, int pageSize) {
        return (int) Math.ceil((double) total / (double) pageSize);
    }

}
