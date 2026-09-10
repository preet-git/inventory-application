package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.RowData;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the six required headers to column positions, once, before any data row is read.
 *
 * <p>Doing this up front turns per-row parsing into an array index rather than a name lookup,
 * which matters across 50,000 rows, and means a file with a missing header fails immediately
 * instead of producing 50,000 identical rejections.
 *
 * <p>Shared by both readers, so CSV and Excel cannot drift in how they interpret a header row.
 */
public final class ColumnLayout {

    /** The six logical headers the brief requires, in their canonical spelling. */
    public enum Column {

        PRODUCT_SKU("Product SKU"),
        PRODUCT_NAME("Product Name"),
        CATEGORY("Category"),
        PURCHASE_DATE("Purchase Date"),
        UNIT_PRICE("Unit Price"),
        QUANTITY("Quantity");

        private final String header;

        Column(String header) {
            this.header = header;
        }

        public String header() {
            return header;
        }
    }

    private final Map<Column, Integer> indexes;
    private final int width;

    private ColumnLayout(Map<Column, Integer> indexes) {
        this.indexes = new EnumMap<>(indexes);
        this.width = indexes.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    /**
     * Resolves a header row. Matching ignores casing and surrounding whitespace, and collapses
     * runs of internal whitespace, so {@code "  product   sku "} matches {@code "Product SKU"}.
     *
     * <p>All six must be present exactly once. Unrecognised extra columns are ignored rather
     * than rejected — a spreadsheet often carries notes columns, and refusing them would fail
     * files that contain everything we need.
     *
     * @throws ImportException.InvalidFile listing every missing and every duplicated header, so
     *                                     one upload surfaces all header problems at once
     */
    public static ColumnLayout resolve(List<String> headerCells) {
        Map<String, List<Integer>> positionsByHeader = new HashMap<>();
        for (int i = 0; i < headerCells.size(); i++) {
            String normalised = normalise(headerCells.get(i));
            if (!normalised.isEmpty()) {
                positionsByHeader.computeIfAbsent(normalised, key -> new ArrayList<>()).add(i);
            }
        }

        Map<Column, Integer> resolved = new EnumMap<>(Column.class);
        List<String> missing = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();

        for (Column column : Column.values()) {
            List<Integer> positions = positionsByHeader.get(normalise(column.header()));
            if (positions == null) {
                missing.add(column.header());
            } else if (positions.size() > 1) {
                duplicated.add(column.header());
            } else {
                resolved.put(column, positions.get(0));
            }
        }

        if (!missing.isEmpty() || !duplicated.isEmpty()) {
            StringBuilder message = new StringBuilder("Invalid header row.");
            if (!missing.isEmpty()) {
                message.append(" Missing required columns: ").append(String.join(", ", missing)).append('.');
            }
            if (!duplicated.isEmpty()) {
                message.append(" Duplicated columns: ").append(String.join(", ", duplicated)).append('.');
            }
            throw new ImportException.InvalidFile(message.toString());
        }

        return new ColumnLayout(resolved);
    }

    /** Number of cells a reader must materialise per row to cover every required column. */
    public int width() {
        return width;
    }

    /** Which required column sits at this index, if any. Used to name a column in an error. */
    public Optional<Column> columnAt(int index) {
        return indexes.entrySet().stream()
                .filter(entry -> entry.getValue() == index)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Maps a row's cells onto the six fields, tolerating rows shorter than the header. */
    public RowData toRowData(int rowNumber, List<String> cells) {
        return RowData.of(
                rowNumber,
                valueAt(cells, Column.PRODUCT_SKU),
                valueAt(cells, Column.PRODUCT_NAME),
                valueAt(cells, Column.CATEGORY),
                valueAt(cells, Column.PURCHASE_DATE),
                valueAt(cells, Column.UNIT_PRICE),
                valueAt(cells, Column.QUANTITY));
    }

    private String valueAt(List<String> cells, Column column) {
        int index = indexes.get(column);
        return index < cells.size() ? cells.get(index) : "";
    }

    private static String normalise(String header) {
        if (header == null) {
            return "";
        }
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}