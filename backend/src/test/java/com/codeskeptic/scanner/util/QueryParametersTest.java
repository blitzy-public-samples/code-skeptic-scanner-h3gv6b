package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// Ported from the `type=int` conversion at backend/app/api/tweets.py:L12-13 and
// backend/app/api/responses.py:L11-12 — DL-217 — see docs/DECISION_LOG.md
/**
 * Exercises the query-parameter conversion the two list routes perform.
 *
 * <p>The contract is the one {@code request.args.get(name, default, type=int)} implemented: the
 * default is returned for every value {@code int()} cannot convert, so a request carrying a malformed
 * value is served and is not rejected.
 *
 * <p>Also exercises the offset ceiling a converted page number can still exceed — DL-225, and the
 * finite page-size bound both list routes serve — DL-123.
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

    // The finite page-size bound both list routes serve — DL-123 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "a requested size of {0} is served as {1}")
    @CsvSource({
            "1,1",
            "2,2",
            "10,10",
            "999,999",
            "1000,1000",
            "1001,1000",
            "1002,1000",
            "10000,1000",
            "1073741824,1000",
            "2147483647,1000"
    })
    @DisplayName("serves a size within the bounds unchanged and reduces a larger one to the maximum")
    void servesASizeWithinTheBoundsUnchangedAndReducesALargerOneToTheMaximum(int requestedSize,
            int servedSize) {

        assertThat(QueryParameters.boundPageSize(requestedSize, PER_PAGE_DEFAULT))
                .isEqualTo(servedSize);
    }

    @ParameterizedTest(name = "a requested size of {0} is served the route default")
    @ValueSource(ints = {0, -1, -10, -2147483648})
    @DisplayName("serves the route default for a size below the minimum")
    void servesTheRouteDefaultForASizeBelowTheMinimum(int requestedSize) {
        assertThat(QueryParameters.boundPageSize(requestedSize, PER_PAGE_DEFAULT))
                .isEqualTo(PER_PAGE_DEFAULT);
        assertThat(QueryParameters.boundPageSize(requestedSize, 25)).isEqualTo(25);
    }

    @Test
    @DisplayName("declares a minimum of one and a finite maximum above the two route defaults")
    void declaresAMinimumOfOneAndAFiniteMaximumAboveTheTwoRouteDefaults() {
        assertThat(QueryParameters.MINIMUM_PAGE_SIZE).isEqualTo(1);
        assertThat(QueryParameters.MAXIMUM_PAGE_SIZE)
                .isGreaterThan(PER_PAGE_DEFAULT)
                .isLessThan(Integer.MAX_VALUE)
                .isEqualTo(1_000);
    }

    @Test
    @DisplayName("keeps every served size within the declared bounds for every requested size")
    void keepsEverySizeWithinTheDeclaredBoundsForEveryRequestedSize() {
        int[] requested = {Integer.MIN_VALUE, -1, 0, 1, 10, 999, 1_000, 1_001, 65_536,
                Integer.MAX_VALUE};

        for (int size : requested) {
            int served = QueryParameters.boundPageSize(size, PER_PAGE_DEFAULT);
            assertThat(served).as("served size for a requested %s", size)
                    .isBetween(QueryParameters.MINIMUM_PAGE_SIZE, QueryParameters.MAXIMUM_PAGE_SIZE);
        }
    }

    @Test
    @DisplayName("rejects a null page request")
    void rejectsANullPageRequest() {
        assertThatThrownBy(() -> QueryParameters.withinQueryableOffset(null))
                .isInstanceOf(NullPointerException.class);
    }

    // The bounded read of one page — DL-249 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reads a page at or below the chunk bound as one window equal to the page")
    void readsAPageAtOrBelowTheChunkBoundAsOneWindowEqualToThePage() {
        List<Pageable> windows = new ArrayList<>();

        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(2, 10), 500,
                window -> {
                    windows.add(window);
                    return rowsNumbered(20, 10);
                },
                QueryParametersTest::renderRows);

        assertThat(windows).as("windows the reader was asked for").hasSize(1);
        assertThat(windows.get(0).getPageSize()).as("rows the window asked for").isEqualTo(10);
        assertThat(windows.get(0).getOffset()).as("first row of the window").isEqualTo(20L);
        assertThat(mapped).as("mapped values").hasSize(10).first().isEqualTo("row-20");
    }

    @Test
    @DisplayName("reads a page larger than the chunk bound as consecutive bounded windows")
    void readsAPageLargerThanTheChunkBoundAsConsecutiveBoundedWindows() {
        List<Pageable> windows = new ArrayList<>();

        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(0, 1_000), 400,
                window -> {
                    windows.add(window);
                    return rowsNumbered(window.getOffset(), window.getPageSize());
                },
                QueryParametersTest::renderRows);

        assertThat(windows).extracting(Pageable::getPageSize)
                .as("rows each window asked for").containsExactly(400, 400, 400);
        assertThat(windows).extracting(Pageable::getOffset)
                .as("first row of each window").containsExactly(0L, 400L, 800L);
        assertThat(mapped).as("mapped values").hasSize(1_000);
        assertThat(mapped.get(0)).isEqualTo("row-0");
        assertThat(mapped.get(999)).isEqualTo("row-999");
        assertThat(mapped).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("discards the rows a window holds before the page opens")
    void discardsTheRowsAWindowHoldsBeforeThePageOpens() {
        List<Pageable> windows = new ArrayList<>();

        // Page index 1 of size 30 opens at row 30, which lies inside the second window of 20.
        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(1, 30), 20,
                window -> {
                    windows.add(window);
                    return rowsNumbered(window.getOffset(), window.getPageSize());
                },
                QueryParametersTest::renderRows);

        assertThat(windows).extracting(Pageable::getOffset)
                .as("first row of each window").containsExactly(20L, 40L);
        assertThat(mapped).as("mapped values").hasSize(30);
        assertThat(mapped.get(0)).as("first mapped value").isEqualTo("row-30");
        assertThat(mapped.get(29)).as("last mapped value").isEqualTo("row-59");
    }

    @Test
    @DisplayName("stops at the window that comes back short of the bound it asked for")
    void stopsAtTheWindowThatComesBackShort() {
        List<Pageable> windows = new ArrayList<>();

        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(0, Integer.MAX_VALUE), 500,
                window -> {
                    windows.add(window);
                    return rowsNumbered(window.getOffset(), 3);
                },
                QueryParametersTest::renderRows);

        assertThat(windows).as("windows the reader was asked for").hasSize(1);
        assertThat(mapped).as("mapped values").containsExactly("row-0", "row-1", "row-2");
    }

    @Test
    @DisplayName("returns no value when the page opens past the last row")
    void returnsNoValueWhenThePageOpensPastTheLastRow() {
        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(4, 25), 10,
                window -> List.of(),
                QueryParametersTest::renderRows);

        assertThat(mapped).as("mapped values").isEmpty();
    }

    @Test
    @DisplayName("returns an unmodifiable list and rejects an unusable argument")
    void returnsAnUnmodifiableListAndRejectsAnUnusableArgument() {
        List<String> mapped = QueryParameters.mapInChunks(PageRequest.of(0, 5), 5,
                window -> rowsNumbered(0, 2), QueryParametersTest::renderRows);

        assertThatThrownBy(() -> mapped.add("row-9"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> QueryParameters.mapInChunks(PageRequest.of(0, 5), 0,
                window -> List.of(), QueryParametersTest::renderRows))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkRows");
        assertThatThrownBy(() -> QueryParameters.mapInChunks(null, 5,
                window -> List.of(), QueryParametersTest::renderRows))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueryParameters.mapInChunks(PageRequest.of(0, 5), 5,
                null, QueryParametersTest::renderRows))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> QueryParameters.mapInChunks(PageRequest.of(0, 5), 5,
                window -> List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // The total_pages member of the pagination envelope — DL-038, DL-249 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "{0} rows at {1} per page span {2} page(s)")
    @CsvSource({
            "0,10,0",
            "1,10,1",
            "10,10,1",
            "11,10,2",
            "42,5,9",
            "30,2147483647,1",
            "0,0,1",
            "7,0,1"
    })
    @DisplayName("reports the page count a repository page reports for the same total and size")
    void reportsThePageCountARepositoryPageReports(long total, int pageSize, int expected) {
        assertThat(QueryParameters.totalPages(total, pageSize)).isEqualTo(expected);
        if (pageSize > 0) {
            assertThat(QueryParameters.totalPages(total, pageSize))
                    .as("the value PageImpl reports")
                    .isEqualTo(new PageImpl<>(List.of(), PageRequest.of(0, pageSize), total)
                            .getTotalPages());
        }
    }

    @Test
    @DisplayName("declares four operations and cannot be instantiated")
    void declaresFourOperationsAndCannotBeInstantiated() throws Exception {
        Constructor<QueryParameters> constructor = QueryParameters.class.getDeclaredConstructor();

        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(QueryParameters.class.getModifiers())).isTrue();

        constructor.setAccessible(true);
        assertThatCode(constructor::newInstance).doesNotThrowAnyException();
    }

    /**
     * Builds consecutively numbered row values starting at the supplied first row.
     *
     * @param firstRow the number the first value carries
     * @param rows     the number of values to build
     * @return the values
     */
    private static List<Long> rowsNumbered(long firstRow, int rows) {
        List<Long> built = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            built.add(firstRow + row);
        }
        return List.copyOf(built);
    }

    /**
     * Renders each row value as the text a mapper produces.
     *
     * @param rows the values to render
     * @return one rendered value per row
     */
    private static List<String> renderRows(List<Long> rows) {
        return rows.stream().map(row -> "row-" + row).toList();
    }
}
