package com.ablsoft.inventory.web.dto;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results, in the exact shape the brief specifies.
 *
 * <p>Deliberately not Spring Data's {@code Page}: that type serialises with a large, unstable set
 * of extra fields ({@code pageable}, {@code numberOfElements}, {@code first}, {@code empty}, …)
 * whose JSON has changed across versions. Since there is no database here, the only thing Spring
 * Data would contribute is an unpredictable contract for the Angular client.
 *
 * <p>Pagination is over an already-sorted in-memory list. That is appropriate at this scale —
 * 50,000 records sort in single-digit milliseconds — and only the ten records on the requested
 * page are mapped to DTOs.
 *
 * @param page          zero-based index of this page
 * @param size          fixed page size
 * @param totalElements total across all pages, not just this one
 * @param totalPages    zero when there are no elements
 * @param sortBy        the sort field actually applied, echoed so the client can render state
 * @param direction     {@code ASC} or {@code DESC}, likewise echoed
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sortBy,
        String direction) {

    /**
     * Slices a sorted list into one page, mapping only the elements on that page.
     *
     * <p>A page index past the end yields empty content rather than an error, matching how
     * pagination behaves everywhere else: a table whose data shrank between requests should show
     * an empty page, not fail.
     */
    public static <S, T> PageResponse<T> slice(
            List<S> sorted,
            int page,
            int size,
            String sortBy,
            String direction,
            Function<S, T> mapper) {

        long total = sorted.size();
        int totalPages = (int) Math.ceil((double) total / size);

        // long arithmetic: a large page index must not overflow into a negative fromIndex.
        long from = Math.min((long) page * size, total);
        long to = Math.min(from + size, total);

        List<T> content = sorted.subList((int) from, (int) to).stream().map(mapper).toList();

        return new PageResponse<>(content, page, size, total, totalPages, sortBy, direction);
    }
}