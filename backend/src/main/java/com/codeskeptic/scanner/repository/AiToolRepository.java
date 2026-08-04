package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link AiTool} entity, which maps the {@code ai_tools} table.
 *
 * <p>The identifier type is {@link Integer}, matching the {@code @Id} field of {@link AiTool} — see
 * docs/DECISION_LOG.md DL-070. The {@code ai_tools} table declares no association. Both consumers
 * call the inherited surface:
 *
 * <ul>
 *   <li>{@code findAll()} returns every {@code ai_tools} row, and {@code TweetStreamClient} takes
 *       the {@code name} value of each into the streaming rule set — see docs/DECISION_LOG.md
 *       DL-044.
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
 * List<String> toolNames = aiToolRepository.findAll().stream()
 *         .map(AiTool::getName)
 *         .filter(Objects::nonNull)
 *         .toList();
 * long trackedAiTools = aiToolRepository.count();
 * }</pre>
 *
 * @see AiTool
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
public interface AiToolRepository extends JpaRepository<AiTool, Integer> {
}
