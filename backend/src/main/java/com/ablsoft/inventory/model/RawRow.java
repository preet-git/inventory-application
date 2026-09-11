package com.ablsoft.inventory.model;

public record RawRow(
        int rowNumber,
        String productSku,
        String productName,
        String category,
        String purchaseDate,
        String unitPrice,
        String quantity,
        String readError) {

    public static RawRow of(int rowNumber, String productSku, String productName, String category,
                            String purchaseDate, String unitPrice, String quantity) {
        return new RawRow(rowNumber, productSku, productName, category, purchaseDate, unitPrice, quantity, null);
    }

    public static RawRow unreadable(int rowNumber, String readError) {
        return new RawRow(rowNumber, "", "", "", "", "", "", readError);
    }

    public boolean isUnreadable() {
        return readError != null;
    }
}
