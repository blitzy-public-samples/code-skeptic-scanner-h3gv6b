package com.codeskeptic.scanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the {@code settings} table and its three columns:
 * {@code key} (the primary key), {@code value} and {@code description}.
 *
 * <p>{@code key} and {@code value} are mapped as JPA quoted identifiers; the physical column names
 * are exactly {@code key} and {@code value}. The primary key is assigned by the caller; it is never
 * generated. The table is created from these annotations by
 * {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md DL-026.
 *
 * <p>{@code value} and {@code description} declare {@code length = Integer.MAX_VALUE}, which renders
 * each vendor's unbounded character type and reproduces the unbounded {@code Column(String)} at
 * backend/app/db/models.py:L43-44 — DL-068 — see docs/DECISION_LOG.md. {@code key} declares
 * {@code length = 255}: it is the primary key, and an unbounded character column cannot be indexed
 * on every supported vendor — DL-069 — see docs/DECISION_LOG.md.
 */
// Ported from backend/app/db/models.py:L39-44 (faithful port) — see docs/DECISION_LOG.md
// Deviation from the literal @Column(name = "key") / @Column(name = "value") mapping: both are
// declared as JPA quoted identifiers; the physical column names are key and value — DL-061 — see
// docs/DECISION_LOG.md
// Deviation from an unbounded primary-key column: key declares an explicit length — DL-069 — see
// docs/DECISION_LOG.md
// equals(Object) and hashCode() are net-new Java persistence mechanics — DL-023 — see
// docs/DECISION_LOG.md
@Entity
@Table(name = "settings")
public class Setting {

    /**
     * Declared character length of the {@code settings.key} primary-key column — DL-069 — see
     * docs/DECISION_LOG.md.
     */
    private static final int KEY_LENGTH = 255;

    // backend/app/db/models.py:L42
    // Quoted-identifier deviation — DL-061 — see docs/DECISION_LOG.md
    // Explicit primary-key length — DL-069 — see docs/DECISION_LOG.md
    @Id
    @Column(name = "\"key\"", length = KEY_LENGTH)
    private String key;

    // backend/app/db/models.py:L43
    // Quoted-identifier deviation — DL-061 — see docs/DECISION_LOG.md
    // Unbounded character mapping — DL-068 — see docs/DECISION_LOG.md
    @Column(name = "\"value\"", length = Integer.MAX_VALUE)
    private String value;

    // backend/app/db/models.py:L44
    // Unbounded character mapping — DL-068 — see docs/DECISION_LOG.md
    @Column(name = "description", length = Integer.MAX_VALUE)
    private String description;

    /**
     * No-argument constructor required by JPA for entity instantiation. Field values are populated by
     * the persistence provider or by the accessors below.
     */
    public Setting() {
    }

    /**
     * Creates a fully populated instance.
     *
     * @param key         the primary key of the setting
     * @param value       the setting payload
     * @param description the human-readable label for the setting
     */
    public Setting(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
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
     * Compares two settings by their {@code key} identifier.
     *
     * <p>Yields {@code true} for the same reference, and for any instance of this
     * type — a persistence-provider proxy included — whose {@code key} is
     * non-{@code null} and equal to this {@code key}. Yields {@code false}
     * whenever either {@code key} is {@code null} and the two references differ,
     * so two settings that carry no identifier yet never compare equal. The
     * identifier is read through {@link #getKey()} on both sides. A {@code null}
     * identifier is handled without throwing.
     *
     * @param other the object to compare with this setting
     * @return {@code true} when both instances denote the same {@code settings}
     *         row
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Setting that)) {
            return false;
        }
        String thisKey = this.getKey();
        return thisKey != null && thisKey.equals(that.getKey());
    }

    // Net-new Java persistence mechanics (no Python counterpart) — DL-023 — see
    // docs/DECISION_LOG.md
    /**
     * Returns a hash code derived from the entity type. The value is identical
     * for every instance of this type and for every proxy of it, and is
     * unchanged by assignment of the {@code key} identifier.
     *
     * @return the hash code of this entity's type
     */
    @Override
    public int hashCode() {
        return Setting.class.hashCode();
    }
}
