package com.codeskeptic.scanner.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.codeskeptic.scanner.entity.AiTool;

/**
 * Spring Data JPA repository for the {@link AiTool} entity, which maps the {@code ai_tools} table.
 *
 * <p>The identifier type is {@link Integer}, matching the {@code @Id} field of {@link AiTool} — see
 * docs/DECISION_LOG.md DL-070. The {@code ai_tools} table declares no association. Both consumers
 * call the inherited surface:
 *
 * <ul>
 *   <li>{@link #findNames(Pageable)} selects the {@code name} column alone, bounded by the supplied
 *       page, and {@code TweetStreamClient} takes those values into the streaming rule set — see
 *       docs/DECISION_LOG.md DL-044 and DL-254.
 *   <li>{@code count()} issues a row count against {@code ai_tools} and {@code AnalyticsService}
 *       reports it as {@code tracked_ai_tools} — see docs/DECISION_LOG.md DL-041.
 * </ul>
 *
 * <p>Spring Data supplies the implementation as a runtime proxy. Transaction boundaries are declared
 * on the {@code @Service} methods that call this interface, and the {@code ai_tools} table is created
 * from the {@link AiTool} annotations by {@code spring.jpa.hibernate.ddl-auto} — see
 * docs/DECISION_LOG.md DL-026.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * List<String> toolNames = aiToolRepository.findNames(PageRequest.of(0, 25));
 * long trackedAiTools = aiToolRepository.count();
 * }</pre>
 *
 * @see AiTool
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The identifier type parameter is Integer, matching ai_tools.id — DL-070 — see
// docs/DECISION_LOG.md
public interface AiToolRepository extends JpaRepository<AiTool, Integer> {

    // Net-new projection: only ai_tools.name reaches the rule set — DL-254 — see
    // docs/DECISION_LOG.md
    /**
     * Selects the {@code name} column of the {@code ai_tools} rows that hold one.
     *
     * <p>Only the {@code name} column is selected, so no other column of the table is transferred. A
     * row whose {@code name} is {@code null} or blank is excluded by the query, and the results are
     * ordered by {@code id} ascending so a bounded page is stable across calls.
     *
     * @param bound the page bounding the number of names returned, must not be {@code null}
     * @return the names the bounded page holds, in {@code id} order, never {@code null}
     */
    @Query("select t.name from AiTool t where t.name is not null and t.name <> '' order by t.id asc")
    List<String> findNames(Pageable bound);
}
