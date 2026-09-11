package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.RawRow;
import com.github.pjfanning.xlsx.StreamingReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class ExcelRowReader implements RowReader {

    private static final String UNCALCULATED_FORMULAS =
            "This file contains formulas that have never been calculated, so it holds no values to "
            + "import. Open it in Excel, save it, and upload it again.";

    private final Workbook workbook;

    private final FormulaEvaluator evaluator;

    private boolean sawUncalculatedFormula;

    ExcelRowReader(Path path, FileType type) throws IOException {
        if (type == FileType.XLSX) {
            this.workbook = StreamingReader.builder()
                    .rowCacheSize(256)
                    .bufferSize(8192)
                    // Spill the shared-string table to disk instead of the heap. Large real-world
                    // exports keep every distinct string in that table, and it can dwarf the rows.
                    .setUseSstTempFile(true)
                    .open(path.toFile());
            this.evaluator = null;
        } else {
            this.workbook = WorkbookFactory.create(path.toFile(), null, true);
            this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            // A formula pointing at another workbook cannot be resolved here; treat it as an
            // unevaluated cell rather than failing the whole import.
            this.evaluator.setIgnoreMissingWorkbooks(true);
        }
    }

    @Override
    public void forEachRow(Consumer<RawRow> consumer) {
        List<String> closestCandidate = List.of();

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            ColumnLayout layout = null;
            int scanned = 0;
            int readable = 0;

            for (Row row : sheet) {
                if (isBlank(row)) {
                    continue;
                }

                if (layout == null) {
                    List<String> cells = headerCells(row);
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

                int rowNumber = row.getRowNum() + 1;   // POI counts from 0; users count from 1
                try {
                    consumer.accept(layout.toRawRow(rowNumber, readCells(row, layout)));
                    readable++;
                } catch (FormulaFailure failure) {
                    // Reject this row and carry on, rather than failing the whole import.
                    consumer.accept(RawRow.unreadable(rowNumber, failure.getMessage()));
                }
            }

            if (layout != null) {
                if (readable == 0 && sawUncalculatedFormula) {
                    // Every row was unreadable and formulas are why: the file was saved before Excel
                    // ever calculated it, and re-saving it is the only thing that will help.
                    throw ImportException.invalidFile(UNCALCULATED_FORMULAS);
                }
                return;
            }
        }
        throw ColumnLayout.headerNotFound(closestCandidate);
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    private static List<String> headerCells(Row row) {
        int width = Math.max(row.getLastCellNum(), 0);
        List<String> cells = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            Cell cell = row.getCell(i);
            cells.add(cell != null && cell.getCellType() == CellType.STRING
                    ? cell.getStringCellValue()
                    : "");
        }
        return cells;
    }

    private List<String> readCells(Row row, ColumnLayout layout) {
        List<String> cells = new ArrayList<>(layout.width());
        for (int i = 0; i < layout.width(); i++) {
            cells.add(cellText(row.getCell(i), i, layout));
        }
        return cells;
    }

    private String cellText(Cell cell, int index, ColumnLayout layout) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() != CellType.FORMULA) {
            return literalText(cell);
        }
        return evaluator != null ? evaluatedText(cell, index, layout) : cachedText(cell, index, layout);
    }

    private String evaluatedText(Cell cell, int index, ColumnLayout layout) {
        org.apache.poi.ss.usermodel.CellValue evaluated;
        try {
            evaluated = evaluator.evaluate(cell);
        } catch (RuntimeException e) {
            // POI throws several unrelated runtime types for unsupported or malformed formulas.
            throw new FormulaFailure(columnName(index, layout), cell, e.getMessage());
        }
        if (evaluated == null) {
            throw new FormulaFailure(columnName(index, layout), cell, "evaluation produced no value");
        }
        return switch (evaluated.getCellType()) {
            case STRING -> evaluated.getStringValue();
            case NUMERIC -> numericText(cell, evaluated.getNumberValue());
            case BOOLEAN -> Boolean.toString(evaluated.getBooleanValue());
            case ERROR -> throw new FormulaFailure(columnName(index, layout), cell,
                    org.apache.poi.ss.usermodel.FormulaError.forInt(evaluated.getErrorValue()).getString());
            default -> "";
        };
    }

    private String cachedText(Cell cell, int index, ColumnLayout layout) {
        CellType cached = cell.getCachedFormulaResultType();
        return switch (cached) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> throw new FormulaFailure(columnName(index, layout), cell, errorText(cell));
            case NUMERIC -> {
                if (cell.getStringCellValue().isEmpty()) {
                    // One uncalculated cell is a bad row, not a bad file: a dirty sheet can carry a
                    // stray '=1+1' among thousands of good rows. Only a file with nothing readable
                    // at all is reported as uncalculated, once the sheet has been walked.
                    sawUncalculatedFormula = true;
                    throw new FormulaFailure(
                            columnName(index, layout), cell, "it has never been calculated");
                }
                yield numericText(cell, cell.getNumericCellValue());
            }
            default -> "";
        };
    }

    private static String errorText(Cell cell) {
        String text = cell.getStringCellValue();
        int marker = text.indexOf('#');
        return marker >= 0 ? text.substring(marker) : text;
    }

    private static String literalText(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String numericText(Cell cell, double value) {
        if (isDateFormatted(cell)) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(DateUtil.getLocalDateTime(value).toLocalDate());
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean isDateFormatted(Cell cell) {
        try {
            return DateUtil.isCellDateFormatted(cell);
        } catch (RuntimeException e) {
            return false;   // the cached value is not numeric after all
        }
    }

    private static String columnName(int index, ColumnLayout layout) {
        return layout.columnAt(index)
                .map(ColumnLayout.Column::header)
                .orElse("Column " + (index + 1));
    }

    private static boolean isBlank(Row row) {
        if (row == null || row.getLastCellNum() < 0) {
            return true;
        }
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static final class FormulaFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        FormulaFailure(String columnName, Cell cell, String detail) {
            super(columnName + " formula '" + safeFormula(cell) + "' could not be evaluated: " + detail,
                    null, false, false);
        }

        private static String safeFormula(Cell cell) {
            try {
                return "=" + cell.getCellFormula();
            } catch (RuntimeException e) {
                return "unknown";
            }
        }
    }
}
