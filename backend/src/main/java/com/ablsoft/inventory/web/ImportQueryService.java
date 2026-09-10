package com.ablsoft.inventory.web;

import com.ablsoft.inventory.model.ImportSnapshot;
import com.ablsoft.inventory.model.Product;
import com.ablsoft.inventory.model.RejectedRow;
import com.ablsoft.inventory.pipeline.ImportSnapshotStore;
import com.ablsoft.inventory.web.dto.ImportSummaryResponse;
import com.ablsoft.inventory.web.dto.PageResponse;
import com.ablsoft.inventory.web.dto.ProductResponse;
import com.ablsoft.inventory.web.dto.RejectedRowResponse;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the application: sorts and pages the active snapshot.
 *
 * <p>Every method reads the snapshot exactly once into a local variable. An import could replace
 * it mid-request, and taking one reference means a single response is built entirely from one
 * import rather than half from each. Nothing here mutates anything, so no locking is involved
 * and reads never contend with a running import.
 *
 * <p><b>Stock ages are computed against the import's reference date, not against today.</b> That
 * reverses what I wrote in the {@code ImportSnapshot} javadoc, and the reason is consistency:
 * the summary's average is committed with the import, so if the products endpoint aged rows
 * against the current date, the average would stop being the mean of the displayed ages after
 * midnight. As written, every figure the API reports comes from one moment and adds up. The
 * cost is that a snapshot left in memory for days reports ages from its import date — acceptable
 * for an in-memory application whose data is lost on restart, and easy to flip if you would
 * rather have freshness than internal consistency.
 *
 * <p>The one-line correction to file 12's javadoc: "the products endpoint recomputes stock age
 * against the current date" should read "all stock-age figures are computed against this date".
 */
@Service
public class ImportQueryService {

    /** Fixed by the brief for both paginated endpoints. */
    public static final int PAGE_SIZE = 10;

    private final ImportSnapshotStore snapshotStore;
    private final Clock clock;

    public ImportQueryService(ImportSnapshotStore snapshotStore, Clock clock) {
        this.snapshotStore = snapshotStore;
        this.clock = clock;
    }

    public PageResponse<ProductResponse> products(int page, String sortBy, String direction) {
        requireValidPage(page);

        ImportSnapshot snapshot = snapshotStore.current();
        ProductSort.Field field = ProductSort.field(sortBy);
        ProductSort.Direction sortDirection = ProductSort.direction(direction);
        LocalDate asOf = referenceDate(snapshot);

        List<Product> sorted = new ArrayList<>(snapshot.products());
        sorted.sort(ProductSort.comparator(field, sortDirection, asOf));

        return PageResponse.slice(
                sorted, page, PAGE_SIZE, field.parameter(), sortDirection.name(),
                product -> ProductResponse.from(product, asOf));
    }

    public ImportSummaryResponse summary() {
        return ImportSummaryResponse.from(snapshotStore.current());
    }

    /**
     * Rejections are always ordered by row number, ascending. They are recorded in that order by
     * the coordinator, so no sort is needed — and a rejection list that reordered itself would
     * be useless for working through a spreadsheet top to bottom.
     */
    public PageResponse<RejectedRowResponse> rejections(int page) {
        requireValidPage(page);

        List<RejectedRow> rejections = snapshotStore.current().rejectedRows();

        return PageResponse.slice(
                rejections, page, PAGE_SIZE, "rowNumber", ProductSort.Direction.ASC.name(),
                RejectedRowResponse::from);
    }

    /**
     * Guards the page index here as well as at the controller boundary, so the service is safe
     * when called directly — which is what the tests do.
     */
    private static void requireValidPage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must be zero or greater, but was " + page);
        }
    }

    /** The empty snapshot has no reference date; it also has no products to age. */
    private LocalDate referenceDate(ImportSnapshot snapshot) {
        return snapshot.isEmpty() ? LocalDate.now(clock) : snapshot.referenceDate();
    }
}