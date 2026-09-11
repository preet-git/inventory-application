package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.RawRow;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ColumnLayout {

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

    private final Map<Column, Integer> positions;
    private final int width;

    private ColumnLayout(Map<Column, Integer> positions) {
        this.positions = new EnumMap<>(positions);
        this.width = positions.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    public static Optional<ColumnLayout> tryResolve(List<String> cells) {
        Map<Column, Integer> found = new EnumMap<>(Column.class);
        for (int i = 0; i < cells.size(); i++) {
            String normalised = normalise(cells.get(i));
            if (normalised.isEmpty()) {
                continue;
            }
            for (Column column : Column.values()) {
                if (!found.containsKey(column) && normalise(column.header()).equals(normalised)) {
                    found.put(column, i);
                }
            }
        }
        return found.size() == Column.values().length ? Optional.of(new ColumnLayout(found)) : Optional.empty();
    }

    public static ImportException headerNotFound(List<String> closestCandidate) {
        List<String> present = closestCandidate.stream().map(ColumnLayout::normalise).toList();
        List<String> missing = new ArrayList<>();
        for (Column column : Column.values()) {
            if (!present.contains(normalise(column.header()))) {
                missing.add(column.header());
            }
        }
        return ImportException.invalidFile(
                "No header row found. Missing required columns: " + String.join(", ", missing) + ".");
    }

    public int width() {
        return width;
    }

    public Optional<Column> columnAt(int index) {
        return positions.entrySet().stream()
                .filter(entry -> entry.getValue() == index)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public RawRow toRawRow(int rowNumber, List<String> cells) {
        return RawRow.of(rowNumber,
                valueAt(cells, Column.PRODUCT_SKU),
                valueAt(cells, Column.PRODUCT_NAME),
                valueAt(cells, Column.CATEGORY),
                valueAt(cells, Column.PURCHASE_DATE),
                valueAt(cells, Column.UNIT_PRICE),
                valueAt(cells, Column.QUANTITY));
    }

    private String valueAt(List<String> cells, Column column) {
        int index = positions.get(column);
        return index < cells.size() ? cells.get(index) : "";
    }

    private static String normalise(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
