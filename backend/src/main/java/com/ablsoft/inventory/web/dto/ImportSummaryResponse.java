package com.ablsoft.inventory.web.dto;

import com.ablsoft.inventory.model.ImportSnapshot;
import java.math.BigDecimal;

/**
 * The four figures returned by {@code GET /api/imports/current/summary}, and the same four that
 * feed the dashboard's summary card.
 *
 * <p>Exactly the fields the brief lists, in that order — no import metadata is added here. Say
 * the word if you want {@code sourceFileName} or {@code importedAt} alongside them; I left them
 * out rather than quietly widen a contract you specified precisely.
 *
 * <p>Before any import has run this reports zeros rather than 404, so the dashboard renders a
 * valid empty state on first load.
 */
public record ImportSummaryResponse(
        int successfulImportCount,
        int rejectedRowCount,
        BigDecimal totalInventoryValue,
        BigDecimal averageStockAgeDays) {

    public static ImportSummaryResponse from(ImportSnapshot snapshot) {
        return new ImportSummaryResponse(
                snapshot.successfulImportCount(),
                snapshot.rejectedRowCount(),
                snapshot.totalInventoryValue(),
                snapshot.averageStockAgeDays());
    }
}