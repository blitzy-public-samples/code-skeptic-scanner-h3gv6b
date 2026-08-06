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
 * {@link Response} — see docs/DECISION_LOG.md DL-025 and DL-049. An identifier reaches this
 * interface already parsed; it is carried as a {@link String} only at the wire boundary — see
 * docs/DECISION_LOG.md DL-023 and DL-048.
 *
 * <p>Six members are declared below — {@link #findAllRows(Pageable)}, {@link #findRowChunk(Pageable)},
 * {@link #findApprovalCounts()}, {@link #existsByTweetId(Integer)},
 * {@link #findByIdForUpdate(Integer)} and the {@link ResponseRow} projection they share. Every other
 * operation the consumers perform is inherited from {@link JpaRepository}:
 *
 * <ul>
 *   <li>{@link #findAllRows(Pageable)} returns one page of projected {@code responses} rows as a
 *       {@link Page}, which {@code ResponseService} renders as the {@code responses} and
 *       {@code pagination} envelope of {@code GET /responses}
 *       ({@code backend/app/api/responses.py:L15-20}). The caller constructs the {@link Pageable} and
 *       converts the 1-based wire {@code page} ({@code backend/app/api/responses.py:L11-12}) to the
 *       0-based index this operation takes — see docs/DECISION_LOG.md DL-038. A page larger than the
 *       caller's chunk bound is read through {@link #findRowChunk(Pageable)} instead — DL-249.
 *   <li>{@code findById(Integer)} returns one row wrapped in an {@link java.util.Optional}. An empty
 *       {@link java.util.Optional} denotes an identifier that is not present, which
 *       {@code ResponseService} translates into the 404 bodies at
 *       {@code backend/app/api/responses.py:L31} and {@code :L65}.
 *   <li>{@code save(Response)} inserts a row whose identifier is {@code null} and updates a row
 *       whose identifier is already assigned, serving {@code POST /responses}
 *       ({@code backend/app/api/responses.py:L44}) and {@code PUT /responses/{responseId}}
 *       ({@code backend/app/api/responses.py:L60}). It replaces the {@code response.save()} call at
 *       {@code backend/app/tasks/response_generation.py:L26}. The caller assigns {@code content},
 *       {@code generated_at} and {@code is_approved} before the call.
 *   <li>{@code count()} issues a row count against {@code responses} and {@code AnalyticsService}
 *       reports it as {@code total_responses}, the metric named at
 *       {@code backend/tests/test_api.py:L51} — see docs/DECISION_LOG.md DL-041.
 * </ul>
 *
 * <p>Spring Data supplies the implementation as a runtime proxy. Transaction boundaries are declared
 * on the {@code @Service} methods that call this interface, and a {@link Response} is mapped to its
 * wire representation inside that same boundary. The {@code responses} table is created from the
 * annotations on {@link Response} by {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md
 * DL-026.
 *
 * <p>{@code responses.is_approved} carries the approval flag a human reviewer reads
 * ({@code backend/app/db/models.py:L26}).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Page<ResponseRow> page = responseRepository.findAllRows(PageRequest.of(wirePage - 1, perPage));
 * List<ResponseRow> chunk = responseRepository.findRowChunk(PageRequest.of(window, chunkRows));
 * ApprovalCounts totals = responseRepository.findApprovalCounts();
 * boolean answered = responseRepository.existsByTweetId(tweetId);
 * Optional<Response> locked = responseRepository.findByIdForUpdate(responseId);
 * }</pre>
 *
 * @see Response
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
     * persistence context — DL-245, DL-249.
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

    /**
     * Returns one bounded chunk of the projected {@code responses} rows, positioned and ordered by
     * {@code chunk}.
     *
     * <p>The projection, the columns read and the absence of a {@code tweets} join are those of
     * {@link #findAllRows(Pageable)}. This operation issues no row count: the window's first row, its
     * row bound and its order are those {@code chunk} carries, and nothing else is read.
     *
     * <p>{@code service.ResponseService} reads one page of {@code GET /responses} as consecutive
     * chunks of this shape when the requested {@code per_page} exceeds the chunk bound, so the rows one
     * statement holds are bounded independently of {@code per_page} — see docs/DECISION_LOG.md DL-249.
     *
     * @param chunk the window's position, row bound and sort; never {@code null}
     * @return the projected rows the window covers, in the requested order; an empty list when it
     *         covers none. Never {@code null}
     */
    // Net-new (no Python counterpart: get_paginated_responses at backend/app/api/responses.py:L15 did
    // not exist) — DL-245, DL-249 — see docs/DECISION_LOG.md
    @Query("""
            select r.id as id,
                   r.content as content,
                   r.generatedAt as generatedAt,
                   r.isApproved as isApproved,
                   r.tweet.id as tweetId
            from Response r
            """)
    List<ResponseRow> findRowChunk(Pageable chunk);

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
     *                rows that name no tweet rather than reporting {@code false}. No delivered caller
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
     * this operation waits until the first commits. Every column of the row is read, mutated and
     * written with no other writer observing the intermediate state, and the two independently
     * writable columns {@code content} and {@code is_approved} do not overwrite one another when two
     * {@code PUT /responses/{responseId}} requests are served at the same moment — DL-122.
     *
     * <p>The wait is bounded: {@link #LOCK_WAIT_HINT} caps the statement at
     * {@link #LOCK_WAIT_MILLIS} milliseconds, after which the provider reports the contention rather
     * than waiting further — see docs/DECISION_LOG.md DL-246.
     *
     * <p>The lock is acquired for the duration of the caller's transaction, so this operation must be
     * called from inside one; {@code ResponseService.updateResponse} declares
     * {@link org.springframework.transaction.annotation.Transactional}. It is not called on the read
     * path, where {@code findById} is used and no lock is taken.
     *
     * <p>The row content, order and identifier semantics are those of {@code findById(Integer)}: an
     * empty {@link java.util.Optional} denotes an identifier that is not present, which the caller
     * reports with the wire literal of {@code backend/app/api/responses.py:L65}.
     *
     * @param id the parsed identifier of the row to lock and read
     * @return the row, or an empty {@link java.util.Optional} when the identifier is not present
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
     * — see docs/DECISION_LOG.md DL-068 and DL-080.
     */
    // Net-new (no Python counterpart; the five members of dto/ResponseDto) — DL-245 — see
    // docs/DECISION_LOG.md
    interface ResponseRow {

        /**
         * Returns the {@code responses.id} value of this row.
         *
         * @return the primary key, or {@code null} when the column holds none
         */
        Integer getId();

        /**
         * Returns the {@code responses.content} value of this row.
         *
         * @return the stored reply text, or {@code null} when the column holds none
         */
        String getContent();

        /**
         * Returns the {@code responses.generated_at} value of this row.
         *
         * @return the generation instant, or {@code null} when the column holds none
         */
        LocalDateTime getGeneratedAt();

        /**
         * Returns the {@code responses.is_approved} value of this row, the flag a human reviewer
         * reads ({@code backend/app/db/models.py:L26}).
         *
         * @return the approval flag, or {@code null} when the column holds none
         */
        Boolean getIsApproved();

        /**
         * Returns the {@code responses.tweet_id} value of this row.
         *
         * @return the parent identifier, or {@code null} when the column holds none
         */
        Integer getTweetId();
    }

    /**
     * The two {@code responses} metrics of the analytics summary, produced by
     * {@link ResponseRepository#findApprovalCounts()}.
     *
     * <p>A closed projection over the {@code responses} table. Spring Data binds each accessor to the
     * select alias of the same name — see docs/DECISION_LOG.md DL-180.
     */
    // Net-new (no Python counterpart; two members of dto/SummaryDto) — DL-180 — see
    // docs/DECISION_LOG.md
    interface ApprovalCounts {

        /**
         * Returns the number of {@code responses} rows.
         *
         * @return the row count, never {@code null} and never negative
         */
        Long getResponseCount();

        /**
         * Returns the number of {@code responses} rows whose {@code is_approved} column holds
         * {@code true}.
         *
         * @return the approved row count, never {@code null} and never negative
         */
        Long getApprovedResponseCount();
    }
}
