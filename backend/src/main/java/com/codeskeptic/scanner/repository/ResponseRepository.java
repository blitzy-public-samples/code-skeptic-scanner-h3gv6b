package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Response;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Response} entity, which maps the {@code responses}
 * table.
 *
 * <p>The identifier type is {@link Long}, matching the generated {@code @Id} field of
 * {@link Response} — see docs/DECISION_LOG.md DL-025 and DL-049. An identifier reaches this
 * interface already parsed; it is carried as a {@link String} only at the wire boundary — see
 * docs/DECISION_LOG.md DL-023 and DL-048.
 *
 * <p>One member is declared below. Every other operation the consumers perform is inherited from
 * {@link JpaRepository}:
 *
 * <ul>
 *   <li>{@code findAll(Pageable)} returns one page of {@code responses} rows as a
 *       {@link org.springframework.data.domain.Page}, which {@code ResponseService} renders as the
 *       {@code responses} and {@code pagination} envelope of {@code GET /responses}
 *       ({@code backend/app/api/responses.py:L15-20}). The caller constructs the
 *       {@link org.springframework.data.domain.Pageable} and converts the 1-based wire {@code page}
 *       ({@code backend/app/api/responses.py:L11-12}) to the 0-based index this operation takes —
 *       see docs/DECISION_LOG.md DL-038.
 *   <li>{@code findById(Long)} returns one row wrapped in an {@link java.util.Optional}. An empty
 *       {@link java.util.Optional} denotes an identifier that is not present, which
 *       {@code ResponseService} translates into the 404 bodies at
 *       {@code backend/app/api/responses.py:L31} and {@code :L65}.
 *   <li>{@code save(Response)} inserts a row whose identifier is {@code null} and updates a row
 *       whose identifier is already assigned, serving {@code POST /responses}
 *       ({@code backend/app/api/responses.py:L44}) and {@code PUT /responses/{responseId}}
 *       ({@code backend/app/api/responses.py:L60}). It replaces the {@code response.save()} call at
 *       {@code backend/app/tasks/response_generation.py:L26}, which named a method that a
 *       {@code declarative_base()} model does not define. The caller assigns {@code content},
 *       {@code generated_at} and {@code is_approved} before the call; this interface neither
 *       defaults nor validates them.
 *   <li>{@code count()} issues a row count against {@code responses} and {@code AnalyticsService}
 *       reports it as {@code total_responses}, the metric named at
 *       {@code backend/tests/test_api.py:L51} — see docs/DECISION_LOG.md DL-041.
 * </ul>
 *
 * <p>Spring Data supplies the implementation as a runtime proxy, registered by the component scan of
 * the application class. Transaction boundaries are declared on the {@code @Service} methods that
 * call this interface, and a {@link Response} is mapped to its wire representation inside that same
 * boundary. Creation of the {@code responses} table is driven by
 * {@code spring.jpa.hibernate.ddl-auto} from the annotations on {@link Response} — see
 * docs/DECISION_LOG.md DL-026; this interface contributes no schema and declares no JPQL and no SQL
 * — see docs/DECISION_LOG.md DL-027.
 *
 * <p>{@code responses.is_approved} carries an approval flag for a human reviewer to read. It is
 * never a trigger, and no operation reachable through this interface publishes to X.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Page<Response> page = responseRepository.findAll(PageRequest.of(wirePage - 1, perPage));
 * Optional<Response> found = responseRepository.findById(responseId);
 * Response saved = responseRepository.save(response);
 * long totalResponses = responseRepository.count();
 * long approvedResponses = responseRepository.countByIsApprovedTrue();
 * }</pre>
 *
 * @see Response
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The declared member has no source counterpart: backend/app/api/analytics.py:L3 imported an
// AnalyticsService that no module defined; the summary metric set is net-new — DL-041 — see
// docs/DECISION_LOG.md
public interface ResponseRepository extends JpaRepository<Response, Long> {

    // The approved_responses metric of dto/SummaryDto, over the is_approved column at
    // backend/app/db/models.py:L26 — DL-041 — see docs/DECISION_LOG.md
    /**
     * Counts the {@code responses} rows whose {@code is_approved} column holds {@code true}.
     *
     * <p>Spring Data derives the query from this method name: the {@code countBy} subject yields a
     * row count, the {@code True} keyword yields the predicate, and the remaining
     * {@code IsApproved} binds to the {@code isApproved} attribute of {@link Response}. The
     * resulting count excludes a row whose {@code is_approved} is {@code false} and a row whose
     * {@code is_approved} is {@code null}; {@code AnalyticsService} reports the number of those rows
     * as {@code pending_responses}, by subtracting this value from {@code count()} — see
     * docs/DECISION_LOG.md DL-041.
     *
     * <p>The {@code tweet} association of the counted rows is not traversed; the query reads the
     * {@code responses} table alone.
     *
     * @return the number of {@code responses} rows marked approved, and {@code 0} when the table
     *         holds no such row
     */
    long countByIsApprovedTrue();
}
