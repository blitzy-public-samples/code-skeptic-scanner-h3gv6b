package com.codeskeptic.scanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the {@code settings} table and its three columns:
 * {@code key} (the primary key), {@code value} and {@code description}.
 *
 * <p>The table is created from these annotations by
 * {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md DL-026.
 */
// Ported from backend/app/db/models.py:L39-44 (faithful port) — see docs/DECISION_LOG.md
@Entity
@Table(name = "settings")
public class Setting {

    /**
     * Primary key, mapped to the {@code settings.key} column. The value is
     * assigned by the caller; it is never generated.
     */
    @Id
    @Column(name = "\"key\"")
    private String key;

    /**
     * Free-form setting payload, mapped to the {@code settings.value} column.
     */
    @Column(name = "\"value\"")
    private String value;

    /**
     * Human-readable label for the setting, mapped to the
     * {@code settings.description} column.
     */
    @Column(name = "description")
    private String description;

    /**
     * No-argument constructor required by JPA for entity instantiation.
     */
    public Setting() {
        // Every field is populated by the persistence provider or by a setter.
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

    /**
     * Returns the primary key of this setting.
     *
     * @return the {@code settings.key} value, or {@code null} when unset
     */
    public String getKey() {
        return key;
    }

    /**
     * Assigns the primary key of this setting.
     *
     * @param key the {@code settings.key} value
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * Returns the payload of this setting.
     *
     * @return the {@code settings.value} value, or {@code null} when unset
     */
    public String getValue() {
        return value;
    }

    /**
     * Assigns the payload of this setting.
     *
     * @param value the {@code settings.value} value
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Returns the human-readable label of this setting.
     *
     * @return the {@code settings.description} value, or {@code null} when unset
     */
    public String getDescription() {
        return description;
    }

    /**
     * Assigns the human-readable label of this setting.
     *
     * @param description the {@code settings.description} value
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Compares two settings by their {@code key} identifier. A {@code null}
     * identifier is handled without throwing.
     *
     * @param other the object to compare with this setting
     * @return {@code true} when {@code other} is a {@code Setting} carrying an
     *         equal {@code key}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Setting that)) {
            return false;
        }
        return Objects.equals(this.key, that.key);
    }

    /**
     * Derives the hash code from the {@code key} identifier. A {@code null}
     * identifier is handled without throwing.
     *
     * @return the hash code of the {@code key} identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
