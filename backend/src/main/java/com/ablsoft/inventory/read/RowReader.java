package com.ablsoft.inventory.read;

import com.ablsoft.inventory.model.RawRow;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface RowReader extends Closeable {

    int HEADER_SCAN_LIMIT = 20;

    void forEachRow(Consumer<RawRow> consumer) throws IOException;

    static RowReader open(Path path, FileType type) throws IOException {
        return type.isExcel()
                ? new ExcelRowReader(path, type)
                : new CsvRowReader(Files.newInputStream(path));
    }
}
