package com.ablsoft.inventory.read;

import com.ablsoft.inventory.model.FileFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A validated upload, staged on disk and ready to read.
 *
 * <p>Staged rather than held in memory because the Excel path needs a {@code File} for POI and
 * because both paths must be able to read the bytes twice: once to check the magic number, once
 * to parse. Multipart is configured with a zero-byte memory threshold, so Tomcat has already
 * written the part to disk before we see it.
 *
 * <p>Constructing one of these is the proof that format validation passed; the pipeline takes an
 * {@code UploadedFile} and needs no further checks.
 */
public record UploadedFile(Path path, String originalFileName, FileFormat format, long sizeBytes) {

    public InputStream openStream() throws IOException {
        return Files.newInputStream(path);
    }

    /**
     * Opens a reader appropriate to the format. Called once per import, on the reader thread,
     * which is what establishes the single-thread ownership the Excel reader depends on.
     */
    public RowReader openReader(int maxDataRows) throws IOException {
        return switch (format) {
            case CSV -> new CsvRowReader(openStream(), maxDataRows);
            case XLSX, XLS -> new ExcelRowReader(path, maxDataRows);
        };
    }
}