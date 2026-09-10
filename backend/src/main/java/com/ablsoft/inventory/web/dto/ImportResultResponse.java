package com.ablsoft.inventory.web.dto;

import com.ablsoft.inventory.model.ImportSnapshot;
import java.math.BigDecimal;
import java.util.List;

/**
 * The body of {@code POST /api/imports}: everything the brief requires the upload to return.
 *
 * <p>{@code dateFormatUsed} is the one field beyond the brief's list, and it earns its place.
 * The sample data {@code 09/06/2026} parses cleanly under both {@code MM/dd/yyyy} (6 September)
 * and {@code dd/MM/yyyy} (9 June) with no error either way, so without this echo a caller who
 * omitted the parameter cannot tell which of two entirely different dates was stored.
 *
 * <p>Every rejected row is included rather than a page of them, because the response is the one
 * chance to hand back a complete report of what went wrong with this upload. A file that is
 * mostly invalid therefore produces a large response; if you would rather cap it and point
 * callers at the paginated rejections endpoint, that is a one-line change.
 */
public record ImportResultResponse(
        String fileName,
        String dateFormatUsed,
        int successfulImportCount,
        int rejectedRowCount,
        BigDecimal totalInventoryValue,
        BigDecimal averageStockAgeDays,
        List<RejectedRowResponse> rejectedRows) {

    public static ImportResultResponse from(ImportSnapshot snapshot) {
        return new ImportResultResponse(
                snapshot.sourceFileName(),
                snapshot.dateFormat().pattern(),
                snapshot.successfulImportCount(),
                snapshot.rejectedRowCount(),
                snapshot.totalInventoryValue(),
                snapshot.averageStockAgeDays(),
                snapshot.rejectedRows().stream().map(RejectedRowResponse::from).toList());
    }
}