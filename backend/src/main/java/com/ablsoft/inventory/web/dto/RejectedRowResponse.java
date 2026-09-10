package com.ablsoft.inventory.web.dto;

import com.ablsoft.inventory.model.RejectedRow;
import java.util.List;

/**
 * One rejected row, as the API returns it.
 *
 * <p>Carries the row number, the SKU as it appeared in the file, and every reason. Both a list
 * and a joined string are exposed: the list is what a UI should render, and {@code reason} is a
 * single readable line for logs, CSV exports and anything that expects one field.
 */
public record RejectedRowResponse(int rowNumber, String productSku, List<String> reasons, String reason) {

    public static RejectedRowResponse from(RejectedRow rejected) {
        return new RejectedRowResponse(
                rejected.rowNumber(),
                rejected.productSku(),
                rejected.reasons(),
                String.join("; ", rejected.reasons()));
    }
}