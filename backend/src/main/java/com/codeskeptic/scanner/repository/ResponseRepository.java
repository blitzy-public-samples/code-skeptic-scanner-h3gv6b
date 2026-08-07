package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for the {@link Response} entity, which maps the {@code responses}
 * table.
 *
 * <p>The identifier type is {@link Integer}, matching the generated {@code @Id} field of
 * {@link Response} — DL-025, DL-049. An identifier reaches this interface already parsed; it is
 * carried as a {@link String} only at the wire boundary — DL-023, DL-048.
 *
 * <p>Five members are declared below — {@link #findAllRows(Pageable)},
 * {@link #findApprovalCounts()}, {@link #existsByTweetId(Integer)},
 * {@link #findByIdForUpdate(Integer)} and the {@link ResponseRow} projection they share.
 * {@link #findAllRows(Pageable)} returns one page of projected rows as a {@link Page}, which
 * {@code ResponseService} renders as the {@code responses} and {@code pagination} envelope of
 * {@code GET /responses} ({@code backend/app/api/responses.py:L15-20}); the caller constructs the
 * {@link Pageable} and converts the 1-based wire {@code page} ({@code :L11-12}) to the 0-based index
 * this operation takes — DL-038.
 *
 * <p>Every other operation the consumers perform is inherited from {@link JpaRepository}. An empty
 * {@link java.util.Optional} from {@code findById(Integer)} denotes an identifier that is not present,
 * which {@code ResponseService} translates into the 404 bodies at
 * {@code backend/app/api/responses.py:L31} and {@code :L65}. {@code save(Response)} serves
 * {@code POST /responses} ({@code :L44}) and {@code PUT /responses/{responseId}} ({@code :L60}) and
 * replaces the {@code response.save()} call at
 * {@code backend/app/tasks/response_generation.py:L26}, with {@code content}, {@code generated_at} and
 * {@code is_approved} assigned by the caller before the call. {@code count()} is reported by
 * {@code AnalyticsService} as {@code total_responses}, the metric named at
 * {@code backend/tests/test_api.py:L51} — DL-041.
 *
 * <p>Spring Data supplies the implementation as a runtime proxy. Transaction boundaries are declared
 * on the {@code @Service} methods that call this interface, and a {@link Response} is mapped to its
 * wire representation inside that same boundary. The {@code responses} table is created from the
 * annotations on {@link Response} by {@code spring.jpa.hibernate.ddl-auto} — DL-026.
 *
 * <p>{@code responses.is_approved} carries the approval flag a human reviewer reads
 * ({@code backend/app/db/models.py:L26}).
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The analytics aggregate has no source counterpart: backend/app/api/analytics.py:L3 imported an
// AnalyticsService that no module defined; the summary metric set is net-new — DL-041 — see
// docs/DECISION_LOG.md
// The identifier type parameter is Integer, matching responses.id — DL-138 — see
// docs/DECISION_LOG.md
public interface ResponseRepository extends JpaRepository<Response, Integer> {

    /**
     * Persistence hint naming the bound on how long a statement may run, in milliseconds. The
     * provider applies it to the JDBC statement, so it bounds the wait for a contended row on every
     * supported vendor — see docs/DECISION_LOG.md DL-246.
     */
    String LOCK_WAIT_HINT = "jakarta.persistence.query.timeout";

    /** Value of {@link #LOCK_WAIT_HINT}, in milliseconds — see docs/DECISION_LOG.md DL-246. */
    String LOCK_WAIT_MILLIS = "5000";

    // Net-new: the page read of GET /responses selects the five wire members and nothing else —
    // DL-245 — see docs/DECISION_LOG.md
    /**
     * Returns one page of {@code responses} rows as the five values the wire contract renders.
     *
     * <p>The select list is the four {@code responses} columns {@code ResponseDto} carries plus the
     * {@code responses.tweet_id} value, read through the identifier path of the {@code tweet}
     * association, which the persistence provider resolves from the owning foreign-key column without
     * joining the {@code tweets} table. Rendering a page issues one statement for the rows and the
     * declared count statement, reads no column of {@code tweets}, and places no entity in the
     * persistence context — DL-245.
     *
     * <p>Content, order, size and pagination metadata are those of the inherited
     * {@code findAll(Pageable)}: the query states no sort, so the order is the one the database
     * reports.
     *
     * @param pageable the 0-based page request the caller builds from the 1-based wire {@code page}
     *                 ({@code backend/app/api/responses.py:L11-12}) — see docs/DECISION_LOG.md
     *                 DL-038
     * @return one page of projected rows; empty when the page holds none
     */
    @Query(value = """
            select r.id as id,
                   r.content as content,
                   r.generatedAt as generatedAt,
                   r.isApproved as isApproved,
                   r.tweet.id as tweetId
            from Response r
            """,
            countQuery = "select count(r) from Response r")
    Page<ResponseRow> findAllRows(Pageable pageable);

    // The total_responses and approved_responses metrics of dto/SummaryDto, over the is_approved
    // column at backend/app/db/models.py:L26 — DL-041, DL-180 — see docs/DECISION_LOG.md
    /**
     * Returns the two {@code responses} metrics of {@code GET /analytics/summary} in one statement.
     *
     * <p>The row count and the approved-row count are selected together, so the summary reads the
     * {@code responses} table exactly once — see docs/DECISION_LOG.md DL-041 and DL-180.
     *
     * <p>Both values are a {@code count(...)} and neither is ever {@code null}. A row whose
     * {@code is_approved} column holds {@code false} or {@code null} is excluded from the approved
     * count — DL-041.
     *
     * @return the two metrics, never {@code null}
     */
    @Query("""
            select count(r) as responseCount,
                   count(case when r.isApproved = true then 1 end) as approvedResponseCount
            from Response r
            """)
    ApprovalCounts findApprovalCounts();

    // The existence guard of the single generation owner — DL-195 — see docs/DECISION_LOG.md
    /**
     * Reports whether the {@code responses} table already holds a row whose {@code tweet_id} column
     * names the supplied {@code tweets} row.
     *
     * <p>Spring Data derives the query from this method name: the {@code existsBy} subject yields an
     * existence check, and {@code Tweet} binds to the {@code tweet} association of {@link Response},
     * whose owning column is {@code tweet_id}. The check loads no row and traverses no association.
     *
     * <p>{@code ResponseService} reads it while holding the parent {@code tweets} row lock, inside the
     * same transaction that may insert a generated row. A second storage transaction for that parent
     * checks only after the first commits — see docs/DECISION_LOG.md DL-195.
     *
     * @param tweetId the primary key of the {@code tweets} row to test. A {@code null} argument makes
     *                the derived predicate {@code tweet_id is null}, so it tests for {@code responses}
     *                rows that name no tweet, and does not report {@code false}. No delivered caller
     *                passes {@code null}: {@code ResponseService} parses the identifier before it
     *                attempts ownership — see docs/DECISION_LOG.md DL-195.
     * @return {@code true} when at least one {@code responses} row names {@code tweetId}, or, for a
     *         {@code null} argument, when at least one row carries a null {@code tweet_id}
     */
    boolean existsByTweetId(Integer tweetId);

    // Pessimistic write lock ahead of a partial response update — DL-122 — see
    // docs/DECISION_LOG.md
    /**
     * Returns the {@code responses} row the identifier addresses, holding a write lock on it for the
     * remainder of the calling transaction.
     *
     * <p>{@link LockModeType#PESSIMISTIC_WRITE} makes the read issue a locking select — {@code for
     * update} on PostgreSQL, MySQL and H2 alike — so a second transaction reading the same row through
     * this operation waits until the first commits, and the two independently writable columns
     * {@code content} and {@code is_approved} do not overwrite one another when two
     * {@code PUT /responses/{responseId}} requests are served at the same moment — DL-122.
     *
     * <p>The wait is bounded: {@link #LOCK_WAIT_HINT} caps the statement at
     * {@link #LOCK_WAIT_MILLIS} milliseconds, after which the provider reports the contention and
     * does not wait further — DL-246.
     *
     * <p>The lock is held for the duration of the caller's transaction, so this operation must be
     * called from inside one; {@code ResponseService.updateResponse} declares
     * {@link org.springframework.transaction.annotation.Transactional}. It is not called on the read
     * path, where {@code findById} is used and no lock is taken.
     *
     * @param id the parsed identifier of the row to lock and read
     * @return the row, or an empty {@link java.util.Optional} when the identifier is not present,
     *         which the caller reports with the wire literal of
     *         {@code backend/app/api/responses.py:L65}
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = LOCK_WAIT_HINT, value = LOCK_WAIT_MILLIS))
    @Query("select r from Response r where r.id = :id")
    Optional<Response> findByIdForUpdate(@Param("id") Integer id);

    /**
     * One {@code responses} row as the five values {@code GET /responses} renders, produced by
     * {@link ResponseRepository#findAllRows(Pageable)}.
     *
     * <p>A closed projection: Spring Data binds each accessor to the select alias of the same name.
     * {@code service.mapper.ResponseMapper} converts an instance into a {@code dto.ResponseDto},
     * rendering the two identifiers as strings — see docs/DECISION_LOG.md DL-023 and DL-245.
     *
     * <p>Every accessor may report {@code null}: none of the five columns declares a not-null marker
     * — DL-068, DL-080. {@code getIsApproved()} carries the flag a human reviewer reads
     * ({@code backend/app/db/models.py:L26}).
     */
    // Net-new (no Python counterpart; the five members of dto/ResponseDto) — DL-245 — see
    // docs/DECISION_LOG.md
    interface ResponseRow {

        Integer getId();

        String getContent();

        LocalDateTime getGeneratedAt();

        Boolean getIsApproved();

        Integer getTweetId();
    }

    /**
     * The two {@code responses} metrics of the analytics summary, produced by
     * {@link ResponseRepository#findApprovalCounts()}.
     *
     * <p>A closed projection over the {@code responses} table. Spring Data binds each accessor to the
     * select alias of the same name — DL-180. Both counts are never {@code null} and never negative.
     */
    // Net-new (no Python counterpart; two members of dto/SummaryDto) — DL-180 — see
    // docs/DECISION_LOG.md
    interface ApprovalCounts {

        Long getResponseCount();

        Long getApprovedResponseCount();
    }

    // Net-new: one bounded window of a large page — DL-297 — see docs/DECISION_LOG.md
    /**
     * Reads the {@code responses} projections one window of a large page covers.
     *
     * <p>The window is expressed by {@code chunk}: its page index selects the window and its page size
     * is the window's row bound. No row total is read, so the statement is a bounded window read and
     * nothing more; {@link #count()} supplies the total the pagination block carries. The projection is
     * the same one {@link #findAllRows(Pageable)} reads, so the parent identifier is taken from the
     * foreign-key column without loading the parent row.
     *
     * @param chunk the window to read, must not be {@code null}
     * @return the projections the window covers, in the requested order; an empty list when it covers
     *         none. Never {@code null}
     */
    @Query("""
            select r.id as id,
                   r.content as content,
                   r.generatedAt as generatedAt,
                   r.isApproved as isApproved,
                   r.tweet.id as tweetId
            from Response r
            """)
    List<ResponseRow> findRowChunk(Pageable chunk);

}
