package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Streams a UTF-8 CSV file record by record.
 *
 * <p>Nothing larger than one record is held at a time, so the row limit is a guard against
 * pathological files rather than a memory necessity — which is the opposite of the Excel path,
 * where the whole workbook must be resident for formula evaluation.
 *
 * <p>Encoding is strictly enforced. The brief requires UTF-8, and a lenient decoder would
 * silently substitute U+FFFD for bad bytes, turning a mis-encoded file into a file full of
 * corrupted SKUs rather than an error someone can act on.
 */
public final class CsvRowReader implements RowReader {

    /**
     * Empty lines are deliberately not ignored. Commons CSV numbers records as it emits them,
     * so skipping blanks silently would shift every subsequent record number away from the line
     * the user sees in their editor, and every rejection would name the wrong row.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setIgnoreEmptyLines(false)
            .setTrim(false)
            .get();

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final Reader reader;
    private final int maxDataRows;

    public CsvRowReader(InputStream inputStream, int maxDataRows) {
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.reader = new InputStreamReader(inputStream, strictUtf8);
        this.maxDataRows = maxDataRows;
    }

    @Override
    public void forEachRow(RowConsumer consumer) throws IOException, InterruptedException {
        ColumnLayout layout = null;
        int dataRows = 0;

        try (CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord record : parser) {
                List<String> cells = toList(record);

                if (layout == null) {
                    if (isBlank(cells)) {
                        continue;   // tolerate blank lines above the header
                    }
                    stripByteOrderMark(cells);
                    layout = ColumnLayout.resolve(cells);
                    continue;
                }

                if (isBlank(cells)) {
                    continue;
                }

                dataRows++;
                if (dataRows > maxDataRows) {
                    throw new ImportException.InvalidFile(
                            "File contains more than " + maxDataRows + " data rows");
                }

                // Record number counts from 1 and includes the header, so it is the row number
                // the user sees. Safe to narrow: the row cap bounds it long before int does.
                consumer.accept(layout.toRowData((int) record.getRecordNumber(), cells));
            }
        } catch (RuntimeException e) {
            // Commons CSV wraps read failures, and the exact wrapper type has changed across
            // versions. Inspect the cause chain rather than betting on one of them.
            CharacterCodingException encodingFailure = findEncodingFailure(e);
            if (encodingFailure != null) {
                throw new ImportException.InvalidFile(
                        "File is not valid UTF-8. Re-save the CSV with UTF-8 encoding.", encodingFailure);
            }
            throw e;
        }

        if (layout == null) {
            throw new ImportException.InvalidFile("File is empty: no header row found");
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static List<String> toList(CSVRecord record) {
        List<String> cells = new ArrayList<>(record.size());
        record.forEach(cells::add);
        return cells;
    }

    private static boolean isBlank(List<String> cells) {
        return cells.stream().allMatch(cell -> cell == null || cell.isBlank());
    }

    private static void stripByteOrderMark(List<String> headerCells) {
        if (headerCells.isEmpty()) {
            return;
        }
        String first = headerCells.get(0);
        if (first != null && !first.isEmpty() && first.charAt(0) == BYTE_ORDER_MARK) {
            headerCells.set(0, first.substring(1));
        }
    }

    private static CharacterCodingException findEncodingFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof CharacterCodingException encodingFailure) {
                return encodingFailure;
            }
        }
        return null;
    }
}