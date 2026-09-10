package com.ablsoft.inventory.read;

import com.ablsoft.inventory.config.ImportProperties;
import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.FileFormat;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.poifs.filesystem.FileMagic;

/**
 * The "Format Validation" stage: decides whether a file is worth parsing at all.
 *
 * <p>Three independent checks, in increasing order of cost and authority.
 *
 * <ol>
 *   <li><b>Extension.</b> Cheap, and the only thing that tells us which parser to use.
 *   <li><b>Declared MIME type.</b> Corroboration only. The brief is explicit that the browser's
 *       type is not to be trusted alone, and it is genuinely unreliable — Windows reports
 *       {@code application/vnd.ms-excel} for .csv files. Absent or blank types pass.
 *   <li><b>Magic number.</b> Authoritative. Reads the leading bytes and asks what the file
 *       actually is, so a .exe renamed to .xlsx is rejected before POI opens it.
 * </ol>
 *
 * <p>Order matters: the size limit is enforced before the content check, and both before any
 * parser touches the file. POI must hold a workbook in memory to evaluate formulas, so the point
 * of these limits is to bound what can ever become resident.
 */
public final class UploadFormatValidator {

    /** Container formats POI recognises. A CSV must be none of them. */
    private static final Set<FileMagic> SPREADSHEET_CONTAINERS = EnumSet.of(FileMagic.OLE2, FileMagic.OOXML);

    private final ImportProperties properties;

    public UploadFormatValidator(ImportProperties properties) {
        this.properties = properties;
    }

    /**
     * @param storedFile        the staged upload on disk
     * @param originalFileName  the client-supplied name; the extension source
     * @param declaredMimeType  the client-supplied content type, possibly null
     * @throws ImportException.UnsupportedFileType for a type we cannot parse (415)
     * @throws ImportException.InvalidFile         for a file that is empty, oversized, or not
     *                                             what its name claims (400)
     */
    public UploadedFile validate(Path storedFile, String originalFileName, String declaredMimeType)
            throws IOException {

        FileFormat format = FileFormat.fromFileName(originalFileName)
                .orElseThrow(() -> new ImportException.UnsupportedFileType(
                        "File type of '" + describe(originalFileName) + "' is not supported. Supported types: "
                                + FileFormat.supportedExtensions()));

        if (!format.acceptsMimeType(declaredMimeType)) {
            throw new ImportException.UnsupportedFileType(
                    "Declared content type '" + declaredMimeType + "' does not match a "
                            + format.extension() + " file");
        }

        long sizeBytes = Files.size(storedFile);
        if (sizeBytes == 0) {
            throw new ImportException.InvalidFile("Uploaded file is empty");
        }
        if (sizeBytes > properties.maxUploadBytes()) {
            throw new ImportException.InvalidFile(
                    "File is " + describeSize(sizeBytes) + ", which exceeds the maximum of "
                            + describeSize(properties.maxUploadBytes()));
        }

        verifyContentMatchesFormat(storedFile, format, originalFileName);

        return new UploadedFile(storedFile, originalFileName, format, sizeBytes);
    }

    /**
     * Compares the file's leading bytes against what the extension promised.
     *
     * <p>For CSV the check is inverted: there is no CSV magic number, so we assert only that the
     * bytes are <em>not</em> a spreadsheet container. That catches the common mistake of renaming
     * an .xlsx to .csv, without rejecting legitimately unusual but valid text.
     */
    private void verifyContentMatchesFormat(Path storedFile, FileFormat format, String fileName)
            throws IOException {

        FileMagic magic;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(storedFile))) {
            magic = FileMagic.valueOf(FileMagic.prepareToCheckMagic(in));
        }

        boolean matches = switch (format) {
            case XLSX -> magic == FileMagic.OOXML;
            case XLS -> magic == FileMagic.OLE2;
            case CSV -> !SPREADSHEET_CONTAINERS.contains(magic);
        };

        if (!matches) {
            throw new ImportException.InvalidFile(
                    "'" + describe(fileName) + "' is not a valid " + format.extension()
                            + " file; its contents do not match its extension");
        }
    }

    private static String describe(String fileName) {
        return Optional.ofNullable(fileName).filter(name -> !name.isBlank()).orElse("(unnamed file)");
    }

    private static String describeSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KiB";
        }
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}