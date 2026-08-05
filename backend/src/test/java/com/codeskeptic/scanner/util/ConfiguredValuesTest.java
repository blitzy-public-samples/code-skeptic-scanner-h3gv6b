package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

// Net-new shared helper extracted from three copies — DL-197 — see docs/DECISION_LOG.md
/**
 * Verifies the single definition of the unresolved-placeholder shape that
 * {@code security/SecurityConfig}, {@code security/JwtService} and
 * {@code config/DatabaseUrlTranslator} now share.
 */
class ConfiguredValuesTest {

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        "${SECRET_KEY}",
        "${AUTH_PASSWORD_HASH}",
        "${DATABASE_URL}",
        "${A}",
        "${}",
        "${WITH:default}",
        "${OUTER${INNER}}",
    })
    @DisplayName("recognises an unresolved placeholder")
    void recognisesAnUnresolvedPlaceholder(String value) {
        assertThat(ConfiguredValues.isUnresolvedPlaceholder(value)).isTrue();
        assertThat(ConfiguredValues.isUnset(value)).isTrue();
    }

    // The trim keeps all three call sites symmetric — finding 21 — DL-197
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        " ${SECRET_KEY}",
        "${SECRET_KEY} ",
        "   ${SECRET_KEY}   ",
        "\t${SECRET_KEY}\n",
    })
    @DisplayName("recognises a placeholder carrying surrounding whitespace")
    void recognisesAPlaceholderCarryingSurroundingWhitespace(String value) {
        assertThat(ConfiguredValues.isUnresolvedPlaceholder(value)).isTrue();
        assertThat(ConfiguredValues.isUnset(value)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        "a-real-secret",
        "postgresql://host/db",
        "$SECRET_KEY",
        "{SECRET_KEY}",
        "${SECRET_KEY}trailing",
        "leading${SECRET_KEY}",
        "$2a$10$abcdefghijklmnopqrstuv",
    })
    @DisplayName("treats a configured value as resolved")
    void treatsAConfiguredValueAsResolved(String value) {
        assertThat(ConfiguredValues.isUnresolvedPlaceholder(value)).isFalse();
        assertThat(ConfiguredValues.isUnset(value)).isFalse();
    }

    @Test
    @DisplayName("recognises a placeholder spanning more than one line")
    void recognisesAPlaceholderSpanningMoreThanOneLine() {
        assertThat(ConfiguredValues.isUnresolvedPlaceholder("${WITH\nNEWLINE}")).isTrue();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", " ", "\t", "\n"})
    @DisplayName("treats a null or blank value as unset but not as a placeholder")
    void treatsANullOrBlankValueAsUnsetButNotAsAPlaceholder(String value) {
        assertThat(ConfiguredValues.isUnresolvedPlaceholder(value)).isFalse();
        assertThat(ConfiguredValues.isUnset(value)).isTrue();
    }

    @Test
    @DisplayName("cannot be instantiated")
    void cannotBeInstantiated() throws ReflectiveOperationException {
        assertThat(Modifier.isFinal(ConfiguredValues.class.getModifiers())).isTrue();
        Constructor<?>[] constructors = ConfiguredValues.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }
}
