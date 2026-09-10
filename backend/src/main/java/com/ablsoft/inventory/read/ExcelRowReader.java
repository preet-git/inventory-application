package com.ablsoft.inventory.read;

import com.ablsoft.inventory.exception.ImportException;
import com.ablsoft.inventory.model.RowData;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads an .xls or .xlsx workbook in file order, evaluating formulas as it goes.
 *
 * <p><b>Threading contract, and the reason this class exists.</b> POI's {@code Workbook},
 * {@code Sheet}, {@code Row}, {@code Cell} and {@code FormulaEvaluator} are not thread safe.
 * Exactly one workbook and one evaluator are created here, both confined to the single thread
 * that constructs this reader and drives {@link #forEachRow}. Cells become plain strings before
 * they leave, so no POI object is reachable from a validation worker. Sharing an evaluator
 * corrupts its internal dependency cache and yields wrong values rather than an exception, which
 * is precisely the kind of bug that survives testing — hence structural confinement rather than
 * a convention.
 *
 * <p>The row limit is re-checked here against the sheet's own row count, before any cell is
 * touched. Opening a workbook is what makes the file resident in memory, so the size limit must
 * already have been enforced by the format validator before this constructor runs.
 */
public final class ExcelRowReader implements RowReader {

    private final Workbook workbook;
    private final FormulaEvaluator evaluator;
    private final int maxDataRows;

    public ExcelRowReader(Path path, int maxDataRows) throws IOException {
        // Read-only: POI skips structures only needed for writing.
        this.workbook = WorkbookFactory.create(path.toFile(), null, true);
        this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        // A formula referencing another workbook cannot be resolved here; treat it as an
        // unevaluated cell rather than aborting the entire import.
        this.evaluator.setIgnoreMissingWorkbooks(true);
        this.maxDataRows = maxDataRows;
    }

    @Override
    public void forEachRow(RowConsumer consumer) throws IOException, InterruptedException {
        if (workbook.getNumberOfSheets() == 0) {
            throw new ImportException.InvalidFile("Workbook contains no sheets");
        }
        Sheet sheet = workbook.getSheetAt(0);

        // Cheap upper bound, checked before deep processing as the brief requires.
        int declaredRows = sheet.getLastRowNum();   // 0-based index of the last row
        if (declaredRows > maxDataRows) {
            throw new ImportException.InvalidFile(
                    "File contains more than " + maxDataRows + " data rows");
        }

        ColumnLayout layout = null;
        int dataRows = 0;

        for (Row row : sheet) {
            if (isBlank(row)) {
                continue;
            }
            if (layout == null) {
                layout = ColumnLayout.resolve(readCells(row, row.getLastCellNum(), null));
                continue;
            }

            dataRows++;
            if (dataRows > maxDataRows) {
                throw new ImportException.InvalidFile(
                        "File contains more than " + maxDataRows + " data rows");
            }

            int rowNumber = row.getRowNum() + 1;   // POI is 0-based; users count from 1
            try {
                consumer.accept(layout.toRowData(rowNumber, readCells(row, layout.width(), layout)));
            } catch (FormulaEvaluationFailure failure) {
                // Reject this row and keep going, rather than failing the whole import.
                consumer.accept(RowData.withFormulaError(rowNumber, failure.getMessage()));
            }
        }

        if (layout == null) {
            throw new ImportException.InvalidFile("File is empty: no header row found");
        }
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    private List<String> readCells(Row row, int width, ColumnLayout layout) {
        List<String> cells = new ArrayList<>(Math.max(width, 0));
        for (int i = 0; i < width; i++) {
            cells.add(cellText(row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), i, layout));
        }
        return cells;
    }

    /** Converts one cell to text, evaluating formulas on this thread and this thread only. */
    private String cellText(Cell cell, int index, ColumnLayout layout) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() != CellType.FORMULA) {
            return literalText(cell);
        }

        CellValue evaluated;
        try {
            evaluated = evaluator.evaluate(cell);
        } catch (RuntimeException e) {
            // POI throws several unrelated runtime types for unsupported or malformed formulas.
            throw new FormulaEvaluationFailure(columnName(index, layout), cell, e.getMessage());
        }
        if (evaluated == null) {
            throw new FormulaEvaluationFailure(columnName(index, layout), cell, "evaluation produced no value");
        }

        return switch (evaluated.getCellType()) {
            case STRING -> evaluated.getStringValue();
            case NUMERIC -> numericText(cell, evaluated.getNumberValue());
            case BOOLEAN -> Boolean.toString(evaluated.getBooleanValue());
            case ERROR -> throw new FormulaEvaluationFailure(
                    columnName(index, layout), cell, errorName(evaluated.getErrorValue()));
            default -> "";
        };
    }

    private static String literalText(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * Excel stores dates as numbers, so a date cell must be recognised by its format and emitted
     * as ISO text. Everything else becomes a plain decimal string with no exponent notation,
     * because {@code BigDecimal} later has to parse it.
     */
    private static String numericText(Cell cell, double value) {
        if (isDateFormatted(cell)) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(DateUtil.getLocalDateTime(value).toLocalDate());
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean isDateFormatted(Cell cell) {
        try {
            return DateUtil.isCellDateFormatted(cell);
        } catch (IllegalStateException e) {
            return false;   // cached formula result is not numeric
        }
    }

    private static String errorName(byte errorCode) {
        try {
            return FormulaError.forInt(errorCode).getString();   // "#DIV/0!", "#REF!", ...
        } catch (IllegalArgumentException e) {
            return "unknown error";
        }
    }

    private static String columnName(int index, ColumnLayout layout) {
        if (layout == null) {
            return "Column " + (index + 1);
        }
        return layout.columnAt(index)
                .map(ColumnLayout.Column::header)
                .orElse("Column " + (index + 1));
    }

    private static boolean isBlank(Row row) {
        if (row == null || row.getLastCellNum() < 0) {
            return true;
        }
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    /** Internal signal, caught one row later. Never escapes this class. */
    private static final class FormulaEvaluationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormulaEvaluationFailure(String columnName, Cell cell, String detail) {
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