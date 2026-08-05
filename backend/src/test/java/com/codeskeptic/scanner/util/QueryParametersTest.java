package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

// Ported from the `type=int` conversion at backend/app/api/tweets.py:L12-13 and
// backend/app/api/responses.py:L11-12 — DL-193, DL-217 — see docs/DECISION_LOG.md
/**
 * Exercises the query-parameter conversion the two list routes perform.
 *
 * <p>The contract is the one {@code request.args.get(name, default, type=int)} implemented: the
 * default is returned whenever {@code int()} would raise, so a request carrying a malformed value is
 * served rather than rejected.
 *
 * <p>Also exercises the offset ceiling a converted page number can still exceed — DL-225.
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

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page index {0} of size {1} is within the queryable offset")
    @CsvSource({
            "0,10",
            "1,10",
            "214748364,10",
            "0,2147483647",
            "1,2147483647",
            "2147483646,1",
            "2147483,1000"
    })
    @DisplayName("reports a page request whose offset an int can hold as queryable")
    void reportsAPageRequestWhoseOffsetAnIntCanHoldAsQueryable(int pageIndex, int pageSize) {
        PageRequest request = PageRequest.of(pageIndex, pageSize);

        assertThat(request.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThat(QueryParameters.withinQueryableOffset(request)).isTrue();
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "page index {0} of size {1} is beyond the queryable offset")
    @CsvSource({
            "214748365,10",
            "2147483646,10",
            "2147483646,2147483647",
            "2,2147483647",
            "99999998,99999999"
    })
    @DisplayName("reports a page request whose offset exceeds an int as not queryable")
    void reportsAPageRequestWhoseOffsetExceedsAnIntAsNotQueryable(int pageIndex, int pageSize) {
        PageRequest request = PageRequest.of(pageIndex, pageSize);

        assertThat(request.getOffset()).isGreaterThan(Integer.MAX_VALUE);
        assertThat(QueryParameters.withinQueryableOffset(request)).isFalse();
    }

    // The offset ceiling of a paged query — DL-225 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reads an offset of exactly Integer.MAX_VALUE as queryable")
    void readsAnOffsetOfExactlyIntegerMaxValueAsQueryable() {
        PageRequest request = PageRequest.of(1, Integer.MAX_VALUE);

        assertThat(request.getOffset()).isEqualTo(Integer.MAX_VALUE);
        assertThat(QueryParameters.withinQueryableOffset(request)).isTrue();
    }

    @Test
    @DisplayName("rejects a null page request")
    void rejectsANullPageRequest() {
        assertThatThrownBy(() -> QueryParameters.withinQueryableOffset(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("declares two operations and cannot be instantiated")
    void declaresTwoOperationsAndCannotBeInstantiated() throws Exception {
        Constructor<QueryParameters> constructor = QueryParameters.class.getDeclaredConstructor();

        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(QueryParameters.class.getModifiers())).isTrue();

        constructor.setAccessible(true);
        assertThatCode(constructor::newInstance).doesNotThrowAnyException();
    }
}
