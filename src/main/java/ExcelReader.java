import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

class ExcelReader implements AutoCloseable {
    private static final String SHEET_NAME = "Einträge";
    private static final String TABLE_NAME = "Einträge";
    private static final String COL_DATE = "Datum";
    private static final String COL_START = "Beginn";
    private static final String COL_FINISH = "Ende";
    private static final String COL_BREAK = "Pause";
    private static final String COL_REMARK = "Bemerkung";

    private static final String NAME_FIRST_NAME = "Vorname";
    private static final String NAME_LAST_NAME = "Nachname";
    private static final String NAME_EMPLOYEE_ID = "Personalnummer";
    private static final String NAME_DEPARTMENT = "Einsatzbereich";

    private final XSSFWorkbook wb;

    ExcelReader(File file) throws IOException, InvalidFormatException {
        this.wb = new XSSFWorkbook(file);
    }

    Employee readEmployeeDetails() {
        return new Employee(
                readNamedString(NAME_FIRST_NAME),
                readNamedString(NAME_LAST_NAME),
                readNamedString(NAME_EMPLOYEE_ID),
                readNamedString(NAME_DEPARTMENT));
    }

    List<TimeEntry> readEntries(YearMonth month) throws IOException {
        List<TimeEntry> entries = new ArrayList<>();
        XSSFSheet sheet = wb.getSheet(SHEET_NAME);
        if (sheet == null) {
            throw new IOException("Blatt '" + SHEET_NAME + "' nicht gefunden");
        }
        XSSFTable table = sheet.getTables().stream()
                .filter(t -> t.getName().equals(TABLE_NAME))
                .findFirst()
                .orElseThrow(() -> new IOException("Tabelle '" + TABLE_NAME + "' nicht gefunden"));

        int colDatum = sheetColumn(table, COL_DATE);
        int colStart = sheetColumn(table, COL_START);
        int colEnde = sheetColumn(table, COL_FINISH);
        int colBreak = sheetColumn(table, COL_BREAK);
        int colRemark = sheetColumn(table, COL_REMARK);

        int firstDataRow = table.getStartCellReference().getRow() + table.getHeaderRowCount();
        int lastDataRow = table.getEndCellReference().getRow();
        for (int r = firstDataRow; r <= lastDataRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell datumCell = row.getCell(colDatum);
            if (datumCell == null || datumCell.getCellType() != CellType.NUMERIC) continue;
            LocalDate date = datumCell.getLocalDateTimeCellValue().toLocalDate();
            if (!YearMonth.from(date).equals(month)) continue;

            LocalTime start = row.getCell(colStart).getLocalDateTimeCellValue().toLocalTime();
            LocalTime end = row.getCell(colEnde).getLocalDateTimeCellValue().toLocalTime();
            Duration breakDuration = Duration.ofSeconds(Math.round(fractionalDayOrZero(row.getCell(colBreak)) * 86400));
            Duration total = Duration.between(start, end).minus(breakDuration);
            String remark = stringOrEmpty(row.getCell(colRemark));
            entries.add(new TimeEntry(date, start, end, breakDuration, total, remark));
        }
        return entries;
    }

    private String readNamedString(String name) {
        Name named = wb.getName(name);
        if (named == null) {
            throw new IllegalStateException("Benannter Bereich '" + name + "' nicht gefunden");
        }
        AreaReference area = new AreaReference(named.getRefersToFormula(), wb.getSpreadsheetVersion());
        CellReference ref = area.getFirstCell();
        XSSFSheet sheet = wb.getSheet(ref.getSheetName());
        if (sheet == null) {
            throw new IllegalStateException("Blatt '" + ref.getSheetName() + "' für '" + name + "' nicht gefunden");
        }
        Row row = sheet.getRow(ref.getRow());
        Cell cell = row != null ? row.getCell(ref.getCol()) : null;
        if (cell == null) {
            throw new IllegalStateException("Zelle für '" + name + "' ist leer");
        }
        return cell.getStringCellValue();
    }

    private static int sheetColumn(XSSFTable table, String columnHeader) {
        int idx = table.findColumnIndex(columnHeader);
        if (idx < 0) {
            throw new IllegalStateException(
                    "Spalte '" + columnHeader + "' nicht in Tabelle '" + table.getName() + "' gefunden");
        }
        return table.getStartCellReference().getCol() + idx;
    }

    private static double fractionalDayOrZero(Cell cell) {
        if (cell == null) return 0.0;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return type == CellType.NUMERIC ? cell.getNumericCellValue() : 0.0;
    }

    private static String stringOrEmpty(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return type == CellType.STRING ? cell.getStringCellValue() : "";
    }

    @Override
    public void close() throws IOException {
        wb.close();
    }
}
