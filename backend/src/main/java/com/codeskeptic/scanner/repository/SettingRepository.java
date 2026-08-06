package com.codeskeptic.scanner.repository;

import com.codeskeptic.scanner.entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 * <p>Two declared statements carry the compare-and-set the background-ownership lease is claimed
 * and renewed with — DL-281. Both are single {@code UPDATE} statements whose {@code WHERE} clause
 * names the value the caller last read, so of two processes that read the same value exactly one
 * update matches a row. Both are expressed in JPQL over {@link Setting}, so the quoted
 * {@code settings."key"} and {@code settings."value"} column names of DL-061 are rendered by the
 * persistence provider and no native statement names them.
 *
 * <p>Transactions are demarcated by the calling service method, and a {@link Setting} is mapped to
 * its wire representation inside that same boundary. The {@code settings} table is created from the
 * annotations on {@link Setting} by {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md
 * DL-026.
 */
// Ported from backend/app/db/database.py:L10-13 (faithful port) — see docs/DECISION_LOG.md
// The identifier type parameter is String, matching the assigned settings.key primary key — DL-069
// — see docs/DECISION_LOG.md
public interface SettingRepository extends JpaRepository<Setting, String> {

    // Net-new: the compare-and-set of the ownership lease — DL-281 — see docs/DECISION_LOG.md
    /**
     * Replaces the value of one row when it still holds the value the caller read.
     *
     * <p>The statement is atomic. Of several callers that read {@code expected} concurrently, exactly
     * one sees a row count of one and the rest see zero: the first update leaves the column holding
     * {@code replacement}, which the {@code WHERE} clause no longer matches — DL-281.
     *
     * @param key the primary key of the row to replace, must not be {@code null}
     * @param expected the value the row must still hold, must not be {@code null}
     * @param replacement the value to write, must not be {@code null}
     * @return {@code 1} when the row was replaced, {@code 0} when the key is absent or the row holds
     *     another value
     */
    @Modifying
    @Query("update Setting s set s.value = :replacement "
            + "where s.key = :key and s.value = :expected")
    int replaceValueIfUnchanged(@Param("key") String key,
            @Param("expected") String expected,
            @Param("replacement") String replacement);

    // Net-new: the compare-and-set of the ownership lease — DL-281 — see docs/DECISION_LOG.md
    /**
     * Replaces the value of one row when it holds no value at all.
     *
     * <p>The statement is the {@code null}-valued case of {@link #replaceValueIfUnchanged(String,
     * String, String)}, which a JPQL equality comparison cannot express, and carries the same
     * atomicity.
     *
     * @param key the primary key of the row to replace, must not be {@code null}
     * @param replacement the value to write, must not be {@code null}
     * @return {@code 1} when the row was replaced, {@code 0} when the key is absent or the row holds
     *     a value
     */
    @Modifying
    @Query("update Setting s set s.value = :replacement "
            + "where s.key = :key and s.value is null")
    int replaceAbsentValue(@Param("key") String key, @Param("replacement") String replacement);
}
