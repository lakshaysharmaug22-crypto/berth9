package dev.berth9.server.processing;

import dev.berth9.engine.read.Row;
import dev.berth9.engine.read.RowSource;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Feeds spreadsheet rows to the engine's tabular reader. Cells are read as displayed
 * ({@link DataFormatter}), so a quantity shows as "120", not "120.0", and formulas are evaluated.
 */
public final class XlsxRowSource implements RowSource {

    private final Workbook workbook;
    private final Sheet sheet;
    private final DataFormatter formatter = new DataFormatter();
    private final FormulaEvaluator evaluator;
    private int next;

    public XlsxRowSource(InputStream in, String sheetName) throws IOException {
        this.workbook = WorkbookFactory.create(in);
        Sheet chosen = sheetName == null ? null : workbook.getSheet(sheetName);
        this.sheet = chosen != null ? chosen : workbook.getSheetAt(0);
        this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        this.next = sheet.getFirstRowNum();
    }

    @Override
    public Row next() {
        if (next > sheet.getLastRowNum()) {
            return null;
        }
        int index = next++;
        org.apache.poi.ss.usermodel.Row row = sheet.getRow(index);
        List<String> cells = new ArrayList<>();
        if (row != null) {
            int last = Math.max(row.getLastCellNum(), 0);
            for (int c = 0; c < last; c++) {
                Cell cell = row.getCell(c);
                cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator));
            }
        }
        return new Row(index + 1L, cells);
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
