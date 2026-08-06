package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Setting} aggregate, which maps the {@code settings}
 * table.
 *
 * <p>The identifier type is {@link String}. The {@code settings.key} column is a natural primary
 * key; the caller assigns it and the persistence provider never generates it.
 *
 * <p>Consumers use the operations inherited from {@link JpaRepository}:
 *
 * <ul>
 *   <li>{@code findAll()} returns every row as a {@code List<Setting>}, the unpaged collection
 *       rendered by {@code GET /settings}.</li>
 *   <li>{@code findById(String)} returns one row wrapped in an {@link java.util.Optional}, and an
 *       empty {@code Optional} denotes a key that is not present.</li>
 *   <li>{@code existsById(String)} reports the presence of a key without loading the row.</li>
 *   <li>{@code save(Setting)} inserts a row whose key is absent and updates a row whose key is
 *       already present.</li>
 *   <li>{@code count()} returns the number of rows.</li>
 * </ul>
 *
 * <p>Transactions are demarcated by the calling service method, and a {@link Setting} is mapped to
 * its wire representation inside that same boundary. The {@code settings} table is created from the
 * annotations on {@link Setting} by {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md
 * DL-026.
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The identifier type parameter is String, matching the assigned settings.key primary key — DL-244
// — see docs/DECISION_LOG.md
public interface SettingRepository extends JpaRepository<Setting, String> {
}
