package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.poifs.filesystem.FileMagic;

public enum FileType {

    CSV(".csv"),
    XLS(".xls"),
    XLSX(".xlsx");

    private final String extension;

    FileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public boolean isExcel() {
        return this != CSV;
    }

    public static FileType detect(String fileName, Path path) throws IOException {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        FileType type = null;
        for (FileType candidate : values()) {
            if (name.endsWith(candidate.extension)) {
                type = candidate;
                break;
            }
        }
        if (type == null) {
            throw ImportException.unsupportedType("'" + describe(fileName)
                    + "' is not a supported file. Upload a .csv, .xls or .xlsx file.");
        }

        FileMagic magic;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            magic = FileMagic.valueOf(FileMagic.prepareToCheckMagic(in));
        }
        // CSV has no magic number, so the test is inverted: it must not be a workbook container.
        boolean contentMatches = switch (type) {
            case XLSX -> magic == FileMagic.OOXML;
            case XLS -> magic == FileMagic.OLE2;
            case CSV -> magic != FileMagic.OOXML && magic != FileMagic.OLE2;
        };
        if (!contentMatches) {
            throw ImportException.invalidFile("'" + describe(fileName) + "' is not a real "
                    + type.extension + " file; its contents do not match its extension.");
        }
        return type;
    }

    private static String describe(String fileName) {
        return fileName == null || fileName.isBlank() ? "(unnamed file)" : fileName;
    }
}
