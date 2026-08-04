package com.codeskeptic.scanner.service.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.entity.Setting;

// Net-new (no Python counterpart method) — call sites backend/app/api/settings.py:L11,L24 —
// DL-058 — see docs/DECISION_LOG.md
/**
 * Converts {@link Setting} entities into their {@link SettingDto} wire form.
 *
 * <p>A conversion copies the three {@code settings} columns — {@code key}, {@code value} and
 * {@code description} — verbatim and in that order. No value is trimmed, re-cased, defaulted or
 * substituted, and a {@code null} field is carried through as a {@code null} component.
 *
 * <p>Conversion runs in one direction only: this mapper declares no entity-producing operation.
 * Instances hold no state and are thread-safe.
 */
@Component
public final class SettingMapper {

    /**
     * Converts a single setting entity into its wire form.
     *
     * @param setting the entity to convert, may be {@code null}
     * @return a DTO carrying the entity's {@code key}, {@code value} and {@code description}
     *         unchanged, or {@code null} when {@code setting} is {@code null}
     */
    public SettingDto toDto(Setting setting) {
        if (setting == null) {
            return null;
        }
        return new SettingDto(setting.getKey(), setting.getValue(), setting.getDescription());
    }

    /**
     * Converts a list of setting entities into their wire form, preserving the order of the input.
     *
     * @param settings the entities to convert, may be {@code null}, may be empty and may contain
     *                 {@code null} elements
     * @return an unmodifiable list holding one DTO per input element in the same order, where a
     *         {@code null} element yields a {@code null} element; empty when {@code settings} is
     *         {@code null} or empty. Never {@code null}
     */
    public List<SettingDto> toDtoList(List<Setting> settings) {
        if (settings == null || settings.isEmpty()) {
            return List.of();
        }
        return settings.stream()
                .map(this::toDto)
                .toList();
    }
}
