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
 */
// Ported from backend/app/db/models.py:L32-37 (faithful port) — see docs/DECISION_LOG.md
@Entity
@Table(name = "ai_tools")
public class AiTool {

    /**
     * Column {@code ai_tools.id}, the generated primary key.
     *
     * <p>Ported from backend/app/db/models.py:L35 — see docs/DECISION_LOG.md DL-023.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Column {@code ai_tools.name}.
     *
     * <p>Ported from backend/app/db/models.py:L36 — see docs/DECISION_LOG.md.
     */
    @Column(name = "name")
    private String name;

    /**
     * Column {@code ai_tools.description}.
     *
     * <p>Ported from backend/app/db/models.py:L37 — see docs/DECISION_LOG.md.
     */
    @Column(name = "description")
    private String description;

    /**
     * No-argument constructor mandated by the JPA specification.
     */
    public AiTool() {
        // Field values are populated by the persistence provider or by the accessors below.
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

    /**
     * Returns the generated primary key, or {@code null} for an instance that has not been
     * persisted.
     *
     * @return the value of column {@code ai_tools.id}
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the primary key.
     *
     * @param id value for column {@code ai_tools.id}
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the tool name.
     *
     * @return the value of column {@code ai_tools.name}
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the tool name.
     *
     * @param name value for column {@code ai_tools.name}
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the tool description.
     *
     * @return the value of column {@code ai_tools.description}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the tool description.
     *
     * @param description value for column {@code ai_tools.description}
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Compares two instances on the persistent identifier.
     *
     * <p>Yields {@code true} for the same reference, and for an argument of this exact type whose
     * identifier is non-{@code null} and equal to this identifier. Yields {@code false} whenever
     * either identifier is {@code null} and the two references differ; two instances that have not
     * yet been persisted never compare equal.
     *
     * @param other the object to compare with
     * @return {@code true} when both instances denote the same {@code ai_tools} row
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        AiTool that = (AiTool) other;
        return id != null && that.id != null && id.equals(that.id);
    }

    /**
     * Returns a hash code derived from the entity type. The value is constant for every instance
     * and is unchanged by assignment of the identifier on insert.
     *
     * @return the hash code of this entity's class
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
