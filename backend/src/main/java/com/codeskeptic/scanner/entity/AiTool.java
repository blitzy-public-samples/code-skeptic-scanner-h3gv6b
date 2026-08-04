package com.codeskeptic.scanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code ai_tools} table.
 *
 * <p>Columns: {@code id}, {@code name}, {@code description}. The table has no association.
 * Schema generation is driven from these annotations (see docs/DECISION_LOG.md DL-026).
 * {@code id} is carried as a {@link String} at the wire boundary (see docs/DECISION_LOG.md DL-023).
 *
 * <p>{@code id} is persisted as {@link Long}, the identifier type AAP §0.4.1.4 declares for
 * {@code AiToolRepository} and the type {@link Tweet} and {@link Response} also carry. TR-3 maps the
 * source {@code Column(Integer, primary_key=True)} of backend/app/db/models.py:L35 onto
 * {@code Integer} or {@code Long} — DL-070 — see docs/DECISION_LOG.md.
 *
 * <p>{@code name} and {@code description} declare {@code length = Integer.MAX_VALUE}, which renders
 * each vendor's unbounded character type and reproduces the unbounded {@code Column(String)} at
 * backend/app/db/models.py:L36-37 — DL-068 — see docs/DECISION_LOG.md.
 */
// Ported from backend/app/db/models.py:L32-37 (faithful port) — see docs/DECISION_LOG.md
// equals(Object) and hashCode() are net-new Java persistence mechanics — DL-023 — see
// docs/DECISION_LOG.md
@Entity
@Table(name = "ai_tools")
public class AiTool {

    // backend/app/db/models.py:L35
    // Long persistence type, per AAP §0.4.1.4 and TR-3 — DL-070 — see docs/DECISION_LOG.md
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // backend/app/db/models.py:L36
    // Unbounded character mapping — DL-068 — see docs/DECISION_LOG.md
    @Column(name = "name", length = Integer.MAX_VALUE)
    private String name;

    // backend/app/db/models.py:L37
    // Unbounded character mapping — DL-068 — see docs/DECISION_LOG.md
    @Column(name = "description", length = Integer.MAX_VALUE)
    private String description;

    /**
     * No-argument constructor mandated by the JPA specification. Field values are populated by the
     * persistence provider or by the accessors below.
     */
    public AiTool() {
    }

    /**
     * Convenience constructor that populates both non-identifier columns. The identifier is
     * assigned by the database on insert.
     *
     * @param name        value for column {@code ai_tools.name}; may be {@code null}
     * @param description value for column {@code ai_tools.description}; may be {@code null}
     */
    public AiTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    // Net-new Java persistence mechanics (no Python counterpart) — DL-023 — see
    // docs/DECISION_LOG.md
    /**
     * Compares two instances on the persistent identifier.
     *
     * <p>Yields {@code true} for the same reference, and for any instance of this type — a
     * persistence-provider proxy included — whose identifier is non-{@code null} and equal to this
     * identifier. Yields {@code false} whenever either identifier is {@code null} and the two
     * references differ; two instances that have not yet been persisted never compare equal. The
     * identifier is read through {@link #getId()} on both sides.
     *
     * @param other the object to compare with
     * @return {@code true} when both instances denote the same {@code ai_tools} row
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AiTool that)) {
            return false;
        }
        Long thisId = this.getId();
        return thisId != null && thisId.equals(that.getId());
    }

    // Net-new Java persistence mechanics (no Python counterpart) — DL-023 — see
    // docs/DECISION_LOG.md
    /**
     * Returns a hash code derived from the entity type. The value is identical for every instance of
     * this type and for every proxy of it, and is unchanged by assignment of the identifier on
     * insert.
     *
     * @return the hash code of this entity's type
     */
    @Override
    public int hashCode() {
        return AiTool.class.hashCode();
    }
}
