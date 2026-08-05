package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// Ported from the `type=int` conversion at backend/app/api/tweets.py:L12-13 and
// backend/app/api/responses.py:L11-12 — DL-193, DL-217 — see docs/DECISION_LOG.md
/**
 * Exercises the query-parameter conversion the two list routes perform.
 *
 * <p>The contract is the one {@code request.args.get(name, default, type=int)} implemented: the
 * default is returned whenever {@code int()} would raise, so a request carrying a malformed value is
 * served rather than rejected.
 */
@DisplayName("QueryParameters")
class QueryParametersTest {

    /** The default the {@code page} parameter carries. */
    private static final int PAGE_DEFAULT = 1;

    /** The default the {@code per_page} parameter carries. */
    private static final int PER_PAGE_DEFAULT = 10;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "\t", "abc", "3.5", "1e3", "0x10", "10px", "ten", "--1", "1,000",
            "99999999999999999999", "-99999999999999999999", "2147483648", "-2147483649"})
    @DisplayName("returns the default for a value an int cannot hold")
    void returnsTheDefaultForAValueAnIntCannotHold(String rawValue) {
        assertThat(QueryParameters.intOrDefault(rawValue, PAGE_DEFAULT)).isEqualTo(PAGE_DEFAULT);
        assertThat(QueryParameters.intOrDefault(rawValue, PER_PAGE_DEFAULT))
                .isEqualTo(PER_PAGE_DEFAULT);
    }

    @ParameterizedTest(name = "\"{0}\" reads as {1}")
    @CsvSource({
            "3,3",
            "'  25  ',25",
            "+7,7",
            "-2,-2",
            "0,0",
            "٣,3",
            "2147483647,2147483647",
            "-2147483648,-2147483648"
    })
    @DisplayName("returns the value a decimal int can hold, ignoring surrounding whitespace")
    void returnsTheValueADecimalIntCanHold(String rawValue, int expected) {
        assertThat(QueryParameters.intOrDefault(rawValue, PAGE_DEFAULT)).isEqualTo(expected);
    }

    @Test
    @DisplayName("declares one operation and cannot be instantiated")
    void declaresOneOperationAndCannotBeInstantiated() throws Exception {
        Constructor<QueryParameters> constructor = QueryParameters.class.getDeclaredConstructor();

        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(QueryParameters.class.getModifiers())).isTrue();

        constructor.setAccessible(true);
        assertThatCode(constructor::newInstance).doesNotThrowAnyException();
    }
}
