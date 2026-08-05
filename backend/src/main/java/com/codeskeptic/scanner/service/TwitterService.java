package com.codeskeptic.scanner.service;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import com.codeskeptic.scanner.util.LogSafe;

// Ported from backend/app/services/twitter_service.py:L6-50 (faithful port) — see docs/DECISION_LOG.md
/**
 * Application service for the {@code tweets} table.
 *
 * <p>Four operations are exposed, each one named by a caller that already existed in the retired
 * module:
 *
 * <ul>
 *   <li>{@link #getPaginatedTweets(int, int)} renders one page of rows and backs
 *       {@code GET /tweets}.
 *   <li>{@link #getTweet(String)} renders one row by identifier and backs
 *       {@code GET /tweets/{tweetId}}.
 *   <li>{@link #updateTweetAnalysis(String, double)} writes the {@code doubt_rating} column of one
 *       row and backs {@code POST /tweets/{tweetId}/analyze}.
 *   <li>{@link #meetsPopularityThreshold(Integer)} evaluates the ingestion popularity gate.
 * </ul>
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
 *
 * @see TweetRepository
 * @see TweetMapper
 * @see SentimentAnalysisService
 */
@Service
public class TwitterService {

    /** Records page rendering, threshold resolution and each doubt-rating write. */
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

    /** Lowest {@code per_page} a page request accepts. */
    private static final int MINIMUM_PER_PAGE = 1;

    /** Data access for the {@code tweets} table. */
    private final TweetRepository tweetRepository;

    /** Data access for the {@code settings} table, read for the popularity-threshold override. */
    private final SettingRepository settingRepository;

    /** Bound configuration supplying {@code scanner.popularity-threshold}. */
    private final ScannerProperties properties;

    /** Converts a {@link Tweet} into its {@link TweetDto} wire form. */
    private final TweetMapper tweetMapper;

    /** Supplies the doubt rating written to {@code tweets.doubt_rating}. */
    private final SentimentAnalysisService sentimentAnalysisService;

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
     * DL-077. No upper bound is applied to {@code perPage} — see docs/DECISION_LOG.md DL-200.
     *
     * <p>A {@code page} beyond the last populated page yields an empty {@link
     * PaginatedTweetsDto#tweets()} list while {@link PaginationDto#total()} and
     * {@link PaginationDto#totalPages()} continue to describe the whole table. An empty table yields
     * an empty list, a {@code total} of {@code 0} and a {@code totalPages} of {@code 0}.
     *
     * <p>The rows are converted inside this method's transaction and the returned lists are
     * unmodifiable.
     *
     * @param page    the 1-based page number requested through the {@code page} query parameter; a
     *                value below {@value #DEFAULT_PAGE} is read as {@value #DEFAULT_PAGE}
     * @param perPage the page size requested through the {@code per_page} query parameter; a value
     *                below {@value #MINIMUM_PER_PAGE} is read as {@value #DEFAULT_PER_PAGE} and no
     *                larger value is reduced
     * @return the {@code tweets} and {@code pagination} pair rendered by {@code GET /tweets}, never
     *         {@code null}
     */
    @Transactional(readOnly = true)
    public PaginatedTweetsDto getPaginatedTweets(int page, int perPage) {
        // Only the values PageRequest.of cannot express are replaced; a large per_page is honoured
        // as requested — DL-193, DL-217 — see docs/DECISION_LOG.md
        int effectivePage = (page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
        // No upper bound is applied; the source declared none — see docs/DECISION_LOG.md DL-123
        int effectivePerPage = (perPage < MINIMUM_PER_PAGE) ? DEFAULT_PER_PAGE : perPage;
        if (effectivePage != page || effectivePerPage != perPage) {
            log.warn("Read requested page {} size {} as page {} size {}.",
                    page, perPage, effectivePage, effectivePerPage);
        }

        // Wire page numbers are 1-based and repository page indexes are 0-based — DL-038
        Page<Tweet> tweetPage =
                tweetRepository.findAll(PageRequest.of(effectivePage - 1, effectivePerPage));

        List<TweetDto> tweets = tweetMapper.toDtoList(tweetPage.getContent());
        PaginationDto pagination = new PaginationDto(
                tweetPage.getNumber() + 1,
                tweetPage.getSize(),
                tweetPage.getTotalElements(),
                tweetPage.getTotalPages());

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
    // left unimplemented behind the note at :L48-49 — see docs/DECISION_LOG.md
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
    @Transactional
    public void updateTweetAnalysis(String tweetId, double analysisResult) {
        int identifier = parseTweetIdOrNotFound(tweetId);
        Tweet tweet = tweetRepository.findById(identifier)
                .orElseThrow(NotFoundException::tweetNotFound);

        double doubtRating = sentimentAnalysisService.calculateDoubtRating(analysisResult);
        tweet.setDoubtRating(doubtRating);
        tweetRepository.save(tweet);

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
     * <p>The threshold is resolved on every call. The {@code settings} row named
     * {@value #POPULARITY_THRESHOLD_SETTING_KEY} takes precedence when it is present and holds an
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

        int popularityThreshold = resolvePopularityThreshold();

        // backend/app/services/twitter_service.py:L46 — inclusive comparison
        boolean meetsThreshold = likeCount >= popularityThreshold;

        log.debug("Popularity gate reports {} for like count {} against threshold {}.",
                meetsThreshold, likeCount, popularityThreshold);
        return meetsThreshold;
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
            log.debug("Reporting tweet identifier '{}' as a row that is not present.",
                    LogSafe.logSafe(tweetId));
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
     * logged once at {@code WARN} and names the key without recording the stored value.
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
                return Integer.parseInt(storedThreshold.trim());
            } catch (NumberFormatException ex) {
                log.warn("Setting '{}' does not hold an integer; applying "
                        + "scanner.popularity-threshold instead.", POPULARITY_THRESHOLD_SETTING_KEY);
            }
        }

        // backend/app/services/twitter_service.py:L43 — the configured threshold
        return properties.popularityThreshold();
    }
}
