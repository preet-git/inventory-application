package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.RawRow;
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
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class CsvRowReader implements RowReader {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setIgnoreEmptyLines(false)
            .setTrim(false)
            .get();

    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private final Reader reader;

    CsvRowReader(InputStream inputStream) {
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.reader = new InputStreamReader(inputStream, strictUtf8);
    }

    @Override
    public void forEachRow(Consumer<RawRow> consumer) throws IOException {
        ColumnLayout layout = null;
        List<String> closestCandidate = List.of();
        int scanned = 0;

        try (CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord record : parser) {
                List<String> cells = toList(record);
                if (isBlank(cells)) {
                    continue;
                }

                if (layout == null) {
                    stripByteOrderMark(cells);
                    Optional<ColumnLayout> resolved = ColumnLayout.tryResolve(cells);
                    if (resolved.isPresent()) {
                        layout = resolved.get();
                        continue;
                    }
                    if (closestCandidate.isEmpty()) {
                        closestCandidate = cells;
                    }
                    if (++scanned >= HEADER_SCAN_LIMIT) {
                        break;
                    }
                    continue;
                }

                // Record numbers count from 1 and include the header, so they are the row numbers
                // the user sees.
                consumer.accept(layout.toRawRow((int) record.getRecordNumber(), cells));
            }
        } catch (RuntimeException e) {
            // Commons CSV wraps read failures, and the wrapper type has changed across versions.
            // Inspect the cause chain rather than betting on one of them.
            CharacterCodingException encodingFailure = encodingFailure(e);
            if (encodingFailure != null) {
                throw ImportException.invalidFile(
                        "File is not valid UTF-8. Re-save the CSV as UTF-8.", encodingFailure);
            }
            throw e;
        }

        if (layout == null) {
            throw ColumnLayout.headerNotFound(closestCandidate);
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

    private static CharacterCodingException encodingFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof CharacterCodingException failure) {
                return failure;
            }
        }
        return null;
    }
}
