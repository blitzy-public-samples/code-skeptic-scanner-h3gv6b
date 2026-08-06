package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Tweet;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for the {@link Tweet} aggregate, which maps the {@code tweets} table.
 *
 * <p>The identifier type is {@link Integer}, matching the {@code @Id} field of {@link Tweet}.
 *
 * <p>Consumers reach the following operations through the surface inherited from
 * {@link JpaRepository}:
 *
 * <ul>
 *   <li>{@code findAll(Pageable)} returns one page of rows and backs {@code GET /tweets}. The caller
 *       builds the {@code Pageable}: the wire {@code page} parameter is 1-based and Spring Data is
 *       0-based — see docs/DECISION_LOG.md DL-038.
 *   <li>{@code findById(Integer)} returns one row wrapped in an {@link java.util.Optional} and backs
 *       {@code GET /tweets/{tweetId}} and {@code POST /tweets/{tweetId}/analyze}. An empty
 *       {@link java.util.Optional} denotes a row that is not present. The caller parses the path
 *       value before calling, so this operation only ever receives an {@link Integer} — see
 *       docs/DECISION_LOG.md DL-048.
 *   <li>{@code save(Tweet)} inserts an ingested row and writes back an updated
 *       {@code doubt_rating} — see docs/DECISION_LOG.md DL-049.
 *   <li>{@code count()} returns the number of rows, which {@code TwitterService} reports as the
 *       {@code total} of a chunked page read — see docs/DECISION_LOG.md DL-249.
 * </ul>
 *
 * <p>Seven members are declared below: {@link #findByIdForUpdate(Integer)},
 * {@link #findUnansweredBatchAfter(Integer, Pageable)}, {@link #findAggregates()},
 * {@link #findDailyTrendsBetween(java.time.LocalDateTime, java.time.LocalDateTime)},
 * {@link #findChunk(Pageable)}, {@link #findAnalysisSubjectById(Integer)} and
 * {@link #updateDoubtRating(Integer, Double)}.
 *
 * <p>Spring Data supplies the implementation as a runtime proxy. Transaction boundaries are declared
 * on the {@code @Service} and {@code @Component} methods that call this interface, and the
 * {@code tweets} table is created from the annotations on {@link Tweet} by
 * {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md DL-026.
 *
 * <p>Every query declared below is JPQL and names no vendor, vendor-specific function or dialect, so
 * a single artifact serves PostgreSQL, MySQL/MariaDB and H2 — see docs/DECISION_LOG.md DL-027.
 *
 * <p>{@link Tweet#getResponses()} is lazy and {@code spring.jpa.open-in-view} is {@code false}. The
 * empty-collection predicate of {@link #findUnansweredBatchAfter(Integer, Pageable)} is evaluated in
 * SQL, and a {@link Tweet} is mapped to its wire representation inside the calling transaction.
 *
 * <p>{@code doubt_rating} is computed by {@code SentimentAnalysisService} and the popularity gate over
 * {@code like_count} is evaluated by {@code TwitterService}; neither value is derived here.
 *
 * @see Tweet
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The identifier type parameter is Integer, matching tweets.id — DL-138 — see
// docs/DECISION_LOG.md
public interface TweetRepository extends JpaRepository<Tweet, Integer> {

    /**
     * Returns one batch of the {@code tweets} rows that have no associated {@code responses} row,
     * taking the rows whose identifier is greater than {@code afterId}.
     *
     * <p>The {@code is empty} predicate over the {@code responses} collection property of
     * {@link Tweet} is evaluated in SQL as a correlated {@code not exists} subquery. The
     * {@code responses} collection of a returned {@link Tweet} is not initialised by this call.
     *
     * <p>{@code afterId} is the keyset cursor: {@code null} opens the sweep and takes the batch from
     * the first matching row, and any other value takes the batch from the first matching row whose
     * identifier is strictly greater. The caller advances the cursor to the identifier of the last row
     * it handled and calls again, so consecutive batches neither repeat nor skip a row — see
     * docs/DECISION_LOG.md DL-248.
     *
     * <p>{@code batch} carries the row bound of one call and the sort the cursor requires,
     * {@code id} ascending. A returned list shorter than the requested bound is the last batch of the
     * sweep.
     *
     * @param afterId identifier the returned rows must exceed, or {@code null} to open the sweep
     * @param batch   the row bound and sort of this call; never {@code null}
     * @return the batch, ordered by identifier ascending; an empty list when no matching row lies past
     *         the cursor. Never {@code null}
     */
    // Ported from backend/app/tasks/response_generation.py:L43, whose expression was
    // `Tweet.query.filter(Tweet.response == None).all()` (faithful port of the predicate) — DL-248,
    // DL-248 — see docs/DECISION_LOG.md
    @Query("""
            select t
            from Tweet t
            where t.responses is empty
              and (:afterId is null or t.id > :afterId)
            """)
    List<Tweet> findUnansweredBatchAfter(@Param("afterId") Integer afterId, Pageable batch);

    /**
     * Returns one bounded chunk of the {@code tweets} table, positioned and ordered by {@code chunk}.
     *
     * <p>No predicate is applied: the chunk is a window over every row. The window's first row, its
     * row bound and its order are those {@code chunk} carries, and no row count is issued.
     *
     * <p>{@code service.TwitterService} reads one page of {@code GET /tweets} as consecutive chunks
     * of this shape when the requested {@code per_page} exceeds the chunk bound, so the rows one
     * statement holds are bounded independently of {@code per_page} — see docs/DECISION_LOG.md
     * DL-249.
     *
     * @param chunk the window's position, row bound and sort; never {@code null}
     * @return the rows the window covers, in the requested order; an empty list when it covers none.
     *         Never {@code null}
     */
    // Net-new (no Python counterpart: get_paginated_tweets at backend/app/api/tweets.py:L16 did not
    // exist) — DL-249 — see docs/DECISION_LOG.md
    @Query("select t from Tweet t")
    List<Tweet> findChunk(Pageable chunk);

    // The per-tweet claim of service/ResponseService.generateResponseIfAbsent — DL-195 — see
    // docs/DECISION_LOG.md
    /**
     * Returns one {@code tweets} row, holding a write lock on it until the surrounding transaction
     * ends.
     *
     * <p>{@link LockModeType#PESSIMISTIC_WRITE} makes the provider append {@code for update} to the
     * select: a second transaction asking for the same row waits here until the first commits or rolls
     * back. A caller must hold a transaction, and the lock covers only the row this identifier
     * names.
     *
     * <p>The wait is bounded: {@link ResponseRepository#LOCK_WAIT_HINT} caps the statement at
     * {@link ResponseRepository#LOCK_WAIT_MILLIS} milliseconds, after which the provider reports the
     * contention rather than waiting further — see docs/DECISION_LOG.md DL-246.
     *
     * <p>The {@code responses} collection of the returned {@link Tweet} is not initialised by this
     * call. An empty {@link Optional} denotes a row that is not present, and no lock is then held.
     *
     * @param id identifier of the row to lock, never {@code null}
     * @return the row, or an empty {@link Optional} when the identifier names none
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = ResponseRepository.LOCK_WAIT_HINT,
            value = ResponseRepository.LOCK_WAIT_MILLIS))
    @Query("select t from Tweet t where t.id = :id")
    Optional<Tweet> findByIdForUpdate(@Param("id") Integer id);

    // Net-new projection: the analyze route reads the two columns it uses — DL-263 — see
    // docs/DECISION_LOG.md
    /**
     * Selects the identifier and the {@code content} of one {@code tweets} row.
     *
     * <p>Only those two columns are selected, so the seven remaining columns of the row are not
     * transferred and the lazy {@code responses} collection is not reachable. An empty
     * {@link Optional} denotes a row that is not present, which is distinct from a present row whose
     * {@code content} is {@code null}.
     *
     * @param id identifier of the row to read, never {@code null}
     * @return the two column values, or an empty {@link Optional} when the identifier names no row
     */
    @Query("select t.id as id, t.content as content from Tweet t where t.id = :id")
    Optional<AnalysisSubject> findAnalysisSubjectById(@Param("id") Integer id);

    // Net-new statement: the analyze route writes the one column it changes — DL-263 — see
    // docs/DECISION_LOG.md
    /**
     * Writes {@code doubt_rating} on one {@code tweets} row and reports whether a row was written.
     *
     * <p>One statement writes one column of one row; no row is read first and no other column is
     * touched. The value is written whether or not the row already carried one, reproducing the
     * unconditional write of {@code backend/app/api/tweets.py:L50}.
     *
     * <p>The method declares its own read-write transaction, which a modifying query on this
     * repository requires — see docs/DECISION_LOG.md DL-263.
     *
     * @param id          identifier of the row to write, never {@code null}
     * @param doubtRating the value to store in {@code doubt_rating}
     * @return {@code 1} when the identifier named a row, and {@code 0} when it named none
     */
    @Modifying
    @Transactional
    @Query("update Tweet t set t.doubtRating = :doubtRating where t.id = :id")
    int updateDoubtRating(@Param("id") Integer id, @Param("doubtRating") Double doubtRating);

    /**
     * Returns the three {@code tweets} metrics of {@code GET /analytics/summary} in one statement.
     *
     * <p>The row count, the mean {@code doubt_rating} and the mean {@code like_count} are selected
     * together, so the summary reads the {@code tweets} table exactly once — see
     * docs/DECISION_LOG.md DL-041 and DL-180.
     *
     * <p>{@code count(...)} is never {@code null}. Both means are boxed and are {@code null} over an
     * empty table and over a table in which no row carries the averaged column — DL-075. The
     * {@code like_count} mean widens the {@code Integer} column to a fractional value.
     *
     * @return the three metrics, never {@code null}
     */
    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the columns are
    // backend/app/db/models.py:L12,L14) — DL-041, DL-180 — see docs/DECISION_LOG.md
    @Query("""
            select count(t) as tweetCount,
                   avg(t.doubtRating) as averageDoubtRating,
                   avg(t.likeCount) as averageLikeCount
            from Tweet t
            """)
    TweetAggregate findAggregates();

    /**
     * Returns one {@link DailyTrend} row per calendar day on which at least one {@code tweets} row
     * was created within the closed interval {@code [since, until]}, in ascending day order.
     *
     * <p>The bucket key is {@code tweets.created_at} truncated to a calendar day. Rows whose
     * {@code created_at} is {@code null} do not satisfy the {@code >=} predicate and are absent from
     * every bucket. A day on which no row was created produces no element, so the series is sparse.
     *
     * <p>{@code AnalyticsService} derives {@code since} from the configuration property
     * {@code scanner.analytics.trend-window-days}, default 30, and maps each element onto a
     * {@code TrendsDto.TrendPoint} — see docs/DECISION_LOG.md DL-042. The bucketing expression, its
     * portability and this projection are DL-213.
     *
     * @param since the inclusive lower bound on {@code tweets.created_at}
     * @param until the inclusive upper bound on {@code tweets.created_at}
     * @return the day-bucketed series in ascending day order; an empty list when no row falls inside
     *         the window
     */
    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist and get_trends() at :L13-14 took no argument;
    // the columns are backend/app/db/models.py:L12-14) — DL-042, DL-213 — see docs/DECISION_LOG.md
    @Query("""
            select cast(t.createdAt as LocalDate) as bucketDate,
                   count(t) as tweetCount,
                   avg(t.doubtRating) as averageDoubtRating,
                   sum(t.likeCount) as totalLikes
            from Tweet t
            where t.createdAt >= :since and t.createdAt <= :until
            group by cast(t.createdAt as LocalDate)
            order by cast(t.createdAt as LocalDate)
            """)
    List<DailyTrend> findDailyTrendsBetween(@Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until);

    /**
     * One calendar-day bucket of the trend series produced by
     * {@link TweetRepository#findDailyTrendsBetween(LocalDateTime, LocalDateTime)}.
     *
     * <p>A closed projection over the {@code tweets} table. Spring Data binds each accessor to the
     * select alias of the same name, and the four values populate a {@code TrendsDto.TrendPoint} —
     * see docs/DECISION_LOG.md DL-213.
     */
    // Net-new (no Python counterpart; shape of TrendsDto.TrendPoint) — DL-213 — see
    // docs/DECISION_LOG.md
    interface DailyTrend {

        /**
         * Returns the calendar day this bucket covers, taken from {@code tweets.created_at}.
         *
         * @return the bucket day, never {@code null}
         */
        LocalDate getBucketDate();

        /**
         * Returns the number of {@code tweets} rows created on this day.
         *
         * @return the row count, at least 1 and never {@code null}
         */
        Long getTweetCount();

        /**
         * Returns the mean {@code tweets.doubt_rating} over this day's rows.
         *
         * @return the mean doubt rating, or {@code null} when no row in this bucket carries one
         */
        Double getAverageDoubtRating();

        /**
         * Returns the sum of {@code tweets.like_count} over this day's rows.
         *
         * @return the summed like count, or {@code null} when no row in this bucket carries one
         */
        Long getTotalLikes();
    }

    /**
     * The three {@code tweets} metrics of the analytics summary, produced by
     * {@link TweetRepository#findAggregates()}.
     *
     * <p>A closed projection over the {@code tweets} table. Spring Data binds each accessor to the
     * select alias of the same name — see docs/DECISION_LOG.md DL-180.
     */
    // Net-new (no Python counterpart; three members of dto/SummaryDto) — DL-180 — see
    // docs/DECISION_LOG.md
    interface TweetAggregate {

        /**
         * Returns the number of {@code tweets} rows.
         *
         * @return the row count, never {@code null} and never negative
         */
        Long getTweetCount();

        /**
         * Returns the mean {@code tweets.doubt_rating} over every row.
         *
         * @return the mean doubt rating, or {@code null} when no row carries one
         */
        Double getAverageDoubtRating();

        /**
         * Returns the mean {@code tweets.like_count} over every row.
         *
         * @return the mean like count, or {@code null} when no row carries one
         */
        Double getAverageLikeCount();
    }

    /**
     * The two {@code tweets} columns the analyze route reads, produced by
     * {@link TweetRepository#findAnalysisSubjectById(Integer)}.
     *
     * <p>A closed projection over the {@code tweets} table. Spring Data binds each accessor to the
     * select alias of the same name — see docs/DECISION_LOG.md DL-263.
     */
    // Net-new (no Python counterpart) — DL-263 — see docs/DECISION_LOG.md
    interface AnalysisSubject {

        /**
         * Returns the value of {@code tweets.id}.
         *
         * @return the identifier, never {@code null}
         */
        Integer getId();

        /**
         * Returns the value of {@code tweets.content}.
         *
         * @return the stored text, or {@code null} when the column holds none
         */
        String getContent();
    }
}
