package com.codeskeptic.scanner.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// Ported from the `type=int` conversion of request.args.get at backend/app/api/tweets.py:L12-13 and
// backend/app/api/responses.py:L11-12 (faithful port) — DL-217 — see docs/DECISION_LOG.md
// withinQueryableOffset is net-new (no Python counterpart) — DL-225 — see docs/DECISION_LOG.md
/**
 * Reads the pagination input of the two list routes: converts a raw query-parameter value into an
 * {@code int}, and reports whether the page it names can be served by a paged query.
 *
 * <p>{@link #intOrDefault(String, int)} reproduces the conversion the retired Flask handlers
 * performed. {@code request.args.get('page', 1, type=int)} at
 * {@code backend/app/api/tweets.py:L12} and the three sibling calls at {@code :L13} and
 * {@code backend/app/api/responses.py:L11-12} hand the raw value to {@code int()} and return the
 * supplied default whenever that conversion raises, so an absent parameter, an empty value and a
 * value carrying anything other than a number all read as the default and the request is served.
 *
 * <p>{@link #withinQueryableOffset(Pageable)} reports the one limit a converted value can still
 * exceed: the offset a paged query can position its first row at — see docs/DECISION_LOG.md DL-225.
 *
 * <p>{@link #boundPageSize(int, int)} is the single declaration of the page size a list route
 * serves: it substitutes the route's default for a size below {@value #MINIMUM_PAGE_SIZE} and
 * reduces a size above {@value #MAXIMUM_PAGE_SIZE} to that maximum — see docs/DECISION_LOG.md
 * DL-123.
 *
 * <p>{@link #mapInChunks(Pageable, int, Function, Function)} reads the rows of one page as consecutive
 * bounded windows, so the rows one statement returns are bounded however large the converted
 * {@code per_page} is — see docs/DECISION_LOG.md DL-249.
 *
 * <p>Every member is static, the type holds no state and is not instantiable, and neither member
 * mutates anything. This type is safe for concurrent use.
 */
public final class QueryParameters {

    // The finite page-size bound both list routes serve — DL-123 — see docs/DECISION_LOG.md
    /**
     * Smallest {@code per_page} a list route serves. A request naming less than this is served the
     * route's declared default — DL-123.
     */
    public static final int MINIMUM_PAGE_SIZE = 1;

    /**
     * Largest {@code per_page} a list route serves. A request naming more than this is served this
     * many rows, and the pagination block restates this size — DL-123.
     */
    public static final int MAXIMUM_PAGE_SIZE = 1_000;

    private QueryParameters() {
    }

    // The finite page-size bound both list routes serve — DL-123 — see docs/DECISION_LOG.md
    /**
     * Returns the page size a list route serves for a requested size.
     *
     * <p>A size below {@value #MINIMUM_PAGE_SIZE} — including {@code 0} and every negative value —
     * reads as {@code defaultSize}, which is the conversion fallback the retired handlers declared at
     * {@code backend/app/api/tweets.py:L13} and {@code backend/app/api/responses.py:L12}. A size above
     * {@value #MAXIMUM_PAGE_SIZE} reads as {@value #MAXIMUM_PAGE_SIZE}. Every size between the two
     * bounds reads unchanged, {@code defaultSize} included.
     *
     * <p>Examples: {@code 0} and {@code -5} read as {@code defaultSize}; {@code 1},
     * {@code 10} and {@code 1000} read unchanged; {@code 1001} and {@link Integer#MAX_VALUE} read as
     * {@value #MAXIMUM_PAGE_SIZE}.
     *
     * @param requestedSize the size the request named, after conversion by
     *                      {@link #intOrDefault(String, int)}
     * @param defaultSize   the route's declared {@code per_page} default, served for a size below
     *                      {@value #MINIMUM_PAGE_SIZE}
     * @return a size within {@value #MINIMUM_PAGE_SIZE}..{@value #MAXIMUM_PAGE_SIZE} when
     *         {@code defaultSize} itself lies within those bounds
     */
    public static int boundPageSize(int requestedSize, int defaultSize) {
        if (requestedSize < MINIMUM_PAGE_SIZE) {
            return defaultSize;
        }
        return Math.min(requestedSize, MAXIMUM_PAGE_SIZE);
    }

    /**
     * Converts one raw query-parameter value into an {@code int}.
     *
     * <p>The default is returned for a {@code null} value, which is an absent parameter; for a blank
     * value, which is a parameter present with nothing after the {@code =}; for a value carrying any
     * character a decimal {@code int} cannot hold, which includes a fractional value, a hexadecimal
     * value and a value carrying a unit; and for a value beyond the range of an {@code int}.
     * Surrounding whitespace is discarded before the conversion, matching {@code int()}, and a leading
     * sign is accepted.
     *
     * <p>Examples: {@code null} and {@code ""} and {@code "abc"} and {@code "3.5"} and
     * {@code "99999999999999999999"} all read as {@code defaultValue}; {@code " 3 "} and {@code "+3"}
     * read as {@code 3}; {@code "-2"} reads as {@code -2}.
     *
     * @param rawValue     the value as the request carried it, or {@code null} when the request
     *                     carried none
     * @param defaultValue the value to return when {@code rawValue} carries no {@code int}
     * @return the converted value, or {@code defaultValue}
     */
    public static int intOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notAnInteger) {
            return defaultValue;
        }
    }

    // The offset ceiling org.springframework.data.jpa.support.PageableUtils enforces — DL-225 — see
    // docs/DECISION_LOG.md
    /**
     * Reports whether a page request names an offset a paged query can position its first row at.
     *
     * <p>The largest offset a paged query can express is {@link Integer#MAX_VALUE}, which it passes as
     * an {@code int}. The offset compared here is the 0-based page index multiplied by the page size,
     * computed as a {@code long} and free of overflow. An offset of exactly
     * {@link Integer#MAX_VALUE} is expressible and reads as {@code true} — DL-225.
     *
     * <p>Examples with a page size of {@code 10}: page index {@code 214748364}, whose offset is
     * {@code 2147483640}, reads as {@code true}; page index {@code 214748365}, whose offset is
     * {@code 2147483650}, reads as {@code false}.
     *
     * @param request the page request to test; must not be {@code null}
     * @return {@code true} when the request's offset is at most {@link Integer#MAX_VALUE}
     * @throws NullPointerException when {@code request} is {@code null}
     */
    public static boolean withinQueryableOffset(Pageable request) {
        Objects.requireNonNull(request, "request must not be null.");
        return request.getOffset() <= Integer.MAX_VALUE;
    }

    // The total_pages member of the pagination envelope — DL-038, DL-249 — see
    // docs/DECISION_LOG.md
    /**
     * Returns the number of pages a page size divides a row total into, which is the
     * {@code total_pages} member of the pagination envelope.
     *
     * <p>The value is the one {@link org.springframework.data.domain.Page#getTotalPages()} reports for
     * the same total and size: the total divided by the size and rounded up, and {@code 1} for a size
     * of {@code 0}. A total of {@code 0} yields {@code 0}.
     *
     * @param total    the number of rows the table holds; never negative
     * @param pageSize the page size the envelope restates
     * @return the number of pages, never negative
     */
    public static int totalPages(long total, int pageSize) {
        return pageSize == 0 ? 1 : (int) Math.ceil((double) total / (double) pageSize);
    }

    // The bounded read of one page — DL-249 — see docs/DECISION_LOG.md
    /**
     * Reads the rows of one page as consecutive bounded windows and maps each window as it arrives.
     *
     * <p>{@code reader} is called with windows of at most {@code chunkRows} rows, positioned to cover
     * exactly the rows {@code requested} names between them, and each window is handed to
     * {@code mapper} before the next one is read. The rows one {@code reader} call returns are bounded
     * by {@code chunkRows} however large the page is, and only the mapped values are accumulated.
     *
     * <p>The sort of {@code requested} is carried by every window, and consecutive windows are
     * disjoint and exhaustive. The reader is expected to apply no predicate and to issue no row
     * count.
     *
     * <p>Reading stops when the page's row bound is met, when a window comes back short of the bound it
     * requested — the table holds no further row — or when the next window's first row lies beyond
     * the offset a paged query can express, which is the bound {@link #withinQueryableOffset(Pageable)}
     * reports on.
     *
     * <p>A page size at or below {@code chunkRows} is read as a single window equal to the page itself.
     * A page whose first row lies past the end of the table yields an empty list.
     *
     * @param <E>        the row type the reader returns
     * @param <D>        the value type the mapper produces
     * @param requested  the page to read: its offset, its row bound and its sort; never {@code null}
     * @param chunkRows  the largest number of rows one reader call may be asked for; at least 1
     * @param reader     reads one window; never {@code null} and never returns {@code null}
     * @param mapper     maps one window; never {@code null} and never returns {@code null}
     * @return the mapped values of the page in the requested order, unmodifiable and never
     *         {@code null}
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when {@code chunkRows} is below 1
     */
    public static <E, D> List<D> mapInChunks(Pageable requested, int chunkRows,
            Function<Pageable, List<E>> reader, Function<List<E>, List<D>> mapper) {
        Objects.requireNonNull(requested, "requested must not be null.");
        Objects.requireNonNull(reader, "reader must not be null.");
        Objects.requireNonNull(mapper, "mapper must not be null.");
        if (chunkRows < 1) {
            throw new IllegalArgumentException("chunkRows must be at least 1; it is " + chunkRows
                    + ".");
        }

        int pageSize = requested.getPageSize();
        int windowRows = Math.min(pageSize, chunkRows);
        long pageOffset = requested.getOffset();
        // Windows are aligned to their own row bound, so the first one may open before the page does
        // and its leading rows are discarded.
        long windowIndex = pageOffset / windowRows;
        int leadingRowsToDiscard = (int) (pageOffset % windowRows);

        List<D> mapped = new ArrayList<>();
        int remaining = pageSize;
        boolean firstWindow = true;

        while (remaining > 0 && windowIndex * (long) windowRows <= Integer.MAX_VALUE) {
            List<E> read = reader.apply(
                    PageRequest.of((int) windowIndex, windowRows, requested.getSort()));
            int rowsRead = read.size();

            List<E> window = read;
            if (firstWindow && leadingRowsToDiscard > 0) {
                window = rowsRead > leadingRowsToDiscard
                        ? window.subList(leadingRowsToDiscard, rowsRead)
                        : List.of();
            }
            firstWindow = false;
            if (window.size() > remaining) {
                window = window.subList(0, remaining);
            }

            if (!window.isEmpty()) {
                mapped.addAll(mapper.apply(window));
                remaining -= window.size();
            }

            if (rowsRead < windowRows) {
                break;
            }
            windowIndex++;
        }

        return List.copyOf(mapped);
    }
}
