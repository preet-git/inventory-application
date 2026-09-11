package com.ablsoft.inventory.repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record Cursor(String sortValue, long id) {

    private static final char SEPARATOR = '|';

    public String encode() {
        String raw = sortValue + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cursor is not a valid token", e);
        }
        int separator = decoded.lastIndexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("Cursor is not a valid token");
        }
        try {
            return new Cursor(decoded.substring(0, separator),
                    Long.parseLong(decoded.substring(separator + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cursor is not a valid token", e);
        }
    }
}
