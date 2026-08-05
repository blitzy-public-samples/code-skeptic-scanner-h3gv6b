package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Tweet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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
 * <p>Four members are declared below. Every other operation the consumers perform is inherited from
 * {@link JpaRepository}:
 *
 * <ul>
 *   <li>{@link #findAll(Pageable)} returns one page of {@code responses} rows as a {@link Page},
 *       which {@code ResponseService} renders as the {@code responses} and {@code pagination}
 *       envelope of {@code GET /responses} ({@code backend/app/api/responses.py:L15-20}). The caller
 *       constructs the {@link Pageable} and converts the 1-based wire {@code page}
 *       ({@code backend/app/api/responses.py:L11-12}) to the 0-based index this operation takes —
 *       see docs/DECISION_LOG.md DL-038.
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
 * Page<Response> page = responseRepository.findAll(PageRequest.of(wirePage - 1, perPage));
 * long approved = responseRepository.countByIsApprovedTrue();
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
// The identifier type parameter is Integer, matching responses.id — DL-070, DL-138 — see
// docs/DECISION_LOG.md
public interface ResponseRepository extends JpaRepository<Response, Integer> {

    // Re-declared to attach an entity graph — DL-087 — see docs/DECISION_LOG.md
    /**
     * Returns one page of {@code responses} rows with the {@code tweet} association fetched in the
     * same statement.
     *
     * <p>The entity graph makes the {@code tweet} association part of the page query: rendering a
     * page issues one statement for the rows plus the count statement {@link Page} requires, and no
     * per-row statement for the association {@code ResponseMapper} reads to render {@code tweet_id} —
     * DL-087.
     *
     * <p>The returned page is identical in content, order, size and pagination metadata to the
     * inherited operation this declaration overrides.
     *
     * @param pageable the 0-based page request the caller builds from the 1-based wire {@code page}
     *                 ({@code backend/app/api/responses.py:L11-12}) — see docs/DECISION_LOG.md
     *                 DL-038
     * @return one page of rows, each holding its associated {@link Tweet}
     */
    @Override
    @EntityGraph(attributePaths = "tweet")
    Page<Response> findAll(Pageable pageable);

    // The approved_responses metric of dto/SummaryDto, over the is_approved column at
    // backend/app/db/models.py:L26 — DL-041 — see docs/DECISION_LOG.md
    /**
     * Returns the number of {@code responses} rows whose {@code is_approved} column is {@code true}.
     *
     * <p>Spring Data derives the count query from the method name. Rows carrying {@code false} or
     * {@code null} are excluded — see docs/DECISION_LOG.md DL-041.
     *
     * @return the approved row count, never negative; {@code 0} when no row is approved
     */
    long countByIsApprovedTrue();

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
     * @param tweetId the primary key of the {@code tweets} row to test; a {@code null} value reports
     *                {@code false}
     * @return {@code true} when at least one {@code responses} row names {@code tweetId}
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
     * this operation waits until the first commits. Every column of the row is therefore read, mutated
     * and written without another writer observing the intermediate state, which is what keeps the two
     * independently writable columns {@code content} and {@code is_approved} from overwriting one
     * another when two {@code PUT /responses/{responseId}} requests are served at the same moment —
     * DL-122.
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
    @Query("select r from Response r where r.id = :id")
    Optional<Response> findByIdForUpdate(@Param("id") Integer id);
}
