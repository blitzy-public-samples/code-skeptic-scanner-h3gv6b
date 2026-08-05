package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Tweet;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 *   <li>{@code count()} returns the number of rows, which {@code AnalyticsService} reports as
 *       {@code total_tweets} — see docs/DECISION_LOG.md DL-041.
 * </ul>
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
 * empty-collection predicate of {@link #findByResponsesIsEmpty()} is evaluated in SQL, and a
 * {@link Tweet} is mapped to its wire representation inside the calling transaction.
 *
 * <p>{@code doubt_rating} is computed by {@code SentimentAnalysisService} and the popularity gate over
 * {@code like_count} is evaluated by {@code TwitterService}; neither value is derived here.
 *
 * @see Tweet
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The identifier type parameter is Integer, matching tweets.id — DL-070, DL-138 — see
// docs/DECISION_LOG.md
public interface TweetRepository extends JpaRepository<Tweet, Integer> {

    /**
     * Returns every {@code tweets} row that has no associated {@code responses} row.
     *
     * <p>Spring Data derives this query from the method name. The {@code IsEmpty} keyword applies to
     * the {@code responses} collection property of {@link Tweet} and renders an {@code is empty}
     * predicate, which the persistence provider issues as a correlated {@code not exists} subquery.
     * The {@code responses} collection of a returned {@link Tweet} is not initialised by this call.
     *
     * <p>The result is unbounded and unordered: every matching row is returned in one call, and the
     * method name states no sort, so the order is the one the database reports — see
     * docs/DECISION_LOG.md DL-182.
     *
     * @return every {@code tweets} row with zero associated {@code responses} rows; an empty list
     *         when every row has at least one. Never {@code null}
     */
    // Ported from backend/app/tasks/response_generation.py:L43, whose expression was
    // `Tweet.query.filter(Tweet.response == None).all()` (faithful port) — see docs/DECISION_LOG.md
    List<Tweet> findByResponsesIsEmpty();

    // The per-tweet claim of service/ResponseService.generateResponseIfAbsent — DL-190 — see
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
     * <p>The {@code responses} collection of the returned {@link Tweet} is not initialised by this
     * call. An empty {@link Optional} denotes a row that is not present, and no lock is then held.
     *
     * @param id identifier of the row to lock, never {@code null}
     * @return the row, or an empty {@link Optional} when the identifier names none
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tweet t where t.id = :id")
    Optional<Tweet> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Returns the mean of the {@code tweets.doubt_rating} column over every row of the table.
     *
     * <p>{@code AnalyticsService} reports this value as {@code average_doubt_rating} — see
     * docs/DECISION_LOG.md DL-041.
     *
     * <p>The return type is boxed. An {@code avg} over an empty table, and over a table in which
     * every {@code doubt_rating} is {@code null}, yields {@code null} — DL-075.
     *
     * @return the mean doubt rating, or {@code null} when no row carries one
     */
    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the column is
    // backend/app/db/models.py:L14) — DL-041 — see docs/DECISION_LOG.md
    @Query("select avg(t.doubtRating) from Tweet t")
    Double findAverageDoubtRating();

    /**
     * Returns the mean of the {@code tweets.like_count} column over every row of the table.
     *
     * <p>{@code AnalyticsService} reports this value as {@code average_like_count} — see
     * docs/DECISION_LOG.md DL-041.
     *
     * <p>The return type is boxed and widens the {@code Integer} column to a fractional mean. An
     * {@code avg} over an empty table, and over a table in which every {@code like_count} is
     * {@code null}, yields {@code null} — DL-075.
     *
     * @return the mean like count, or {@code null} when no row carries one
     */
    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the column is
    // backend/app/db/models.py:L12) — DL-041 — see docs/DECISION_LOG.md
    @Query("select avg(t.likeCount) from Tweet t")
    Double findAverageLikeCount();

    /**
     * Returns one {@link DailyTrend} row per calendar day on which at least one {@code tweets} row
     * was created at or after {@code since}, in ascending day order.
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
            where t.createdAt >= :since
            group by cast(t.createdAt as LocalDate)
            order by cast(t.createdAt as LocalDate)
            """)
    List<DailyTrend> findDailyTrendsSince(@Param("since") LocalDateTime since);

    /**
     * One calendar-day bucket of the trend series produced by
     * {@link TweetRepository#findDailyTrendsSince(LocalDateTime)}.
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
}
