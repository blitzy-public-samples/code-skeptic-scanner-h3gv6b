package com.codeskeptic.scanner.util;

import java.util.regex.Pattern;

// Net-new shared configuration-value utility — DL-186, DL-189, DL-197 — see docs/DECISION_LOG.md
/**
 * Inspects values produced by configuration binding.
 *
 * <p>Holds the single definition of the unresolved-placeholder shape previously repeated in
 * {@code security/SecurityConfig}, {@code security/JwtService} and {@code config/DatabaseUrlTranslator}
 * — DL-197.
 */
public final class ConfiguredValues {

    /**
     * Shape of a Spring property placeholder that resolved to nothing. Configuration binding leaves
     * such a placeholder in place as literal text when the environment variable behind it is absent,
     * so the bound value is neither {@code null} nor blank — DL-186.
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

    private ConfiguredValues() {
    }

    /**
     * Reports whether a bound value is an unresolved Spring property placeholder.
     *
     * <p>The value is trimmed before matching, so a placeholder carrying surrounding whitespace is
     * recognised as well as the exact literal an absent environment variable binds.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is a literal {@code ${...}} placeholder
     */
    public static boolean isUnresolvedPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

    /**
     * Reports whether a bound value carries no usable configuration.
     *
     * <p>A {@code null} value, a blank value and an unresolved placeholder are all treated as unset,
     * which is the condition each caller's fail-fast guard tests.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is {@code null}, blank, or an unresolved placeholder
     */
    public static boolean isUnset(String value) {
        return value == null || value.isBlank() || isUnresolvedPlaceholder(value);
    }
}
