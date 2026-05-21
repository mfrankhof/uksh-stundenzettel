import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "uksh-stundenzettel", mixinStandardHelpOptions = true, versionProvider = UkshStundenzettel.VersionProvider.class, description = "Füllt den Stundenzettel für studentische Hilfskräfte des UKSH auf Basis einer Excel-Datei aus")
class UkshStundenzettel implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(UkshStundenzettel.class);

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

    private static final float FONT_SIZE = 11f;
    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Parameters(paramLabel = "XSLX", description = "Excel Stundenzettel")
    File xslxFile;

    @Parameters(paramLabel = "JAHR_MONAT", description = "Monat im Format JJJJ-MM")
    YearMonth yearMonth;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", description = "Name / Pfad der zu erzeugenden PDF-Datei")
    File outputFile;

    record TimeEntry(LocalDate date, LocalTime start, LocalTime end, Duration breakDuration, Duration total,
                     String remark) {
    }

    record Employee(String firstName, String lastName, String employeeId, String department) {
    }

    @Override
    public Integer call() throws Exception {
        Employee employee;
        List<TimeEntry> segments;
        try (XSSFWorkbook wb = new XSSFWorkbook(xslxFile)) {
            employee = readEmployeeDetails(wb);
            segments = readEntries(wb, yearMonth);
        }
        List<TimeEntry> days = mergeByDay(segments);
        LOG.info("Mitarbeiter: {} {} [{}] ({})",
                employee.firstName(), employee.lastName(), employee.employeeId(), employee.department());
        LOG.info("{} Tageseinträge aus {} Einzeleinträgen für {}", days.size(), segments.size(), yearMonth);
        for (TimeEntry d : days) {
            LOG.info("{} {}-{} Pause {} h → {} h ({})",
                    d.date(), d.start(), d.end(), formatDecimalHours(d.breakDuration()), formatDecimalHours(d.total()), d.remark());
        }
        Duration totalSum = days.stream().map(TimeEntry::total).reduce(Duration.ZERO, Duration::plus);
        LOG.info("Summe: {} h", formatDecimalHours(totalSum));
        File output = outputFile != null ? outputFile : new File("stundenzettel_" + yearMonth + ".pdf");
        writePdf(output, yearMonth, employee, days);
        return 0;
    }

    private static List<TimeEntry> readEntries(XSSFWorkbook wb, YearMonth month) throws IOException {
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

    private static Employee readEmployeeDetails(XSSFWorkbook wb) {
        return new Employee(
                readNamedString(wb, NAME_FIRST_NAME),
                readNamedString(wb, NAME_LAST_NAME),
                readNamedString(wb, NAME_EMPLOYEE_ID),
                readNamedString(wb, NAME_DEPARTMENT));
    }

    private static String readNamedString(XSSFWorkbook wb, String name) {
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

    private static List<TimeEntry> mergeByDay(List<TimeEntry> segments) {
        Map<LocalDate, List<TimeEntry>> byDate = segments.stream()
                .collect(Collectors.groupingBy(TimeEntry::date, TreeMap::new, Collectors.toList()));
        List<TimeEntry> days = new ArrayList<>(byDate.size());
        for (var group : byDate.values()) {
            LocalTime dayStart = group.stream().map(TimeEntry::start).min(Comparator.naturalOrder()).orElseThrow();
            LocalTime dayEnd = group.stream().map(TimeEntry::end).max(Comparator.naturalOrder()).orElseThrow();
            Duration worked = group.stream().map(TimeEntry::total).reduce(Duration.ZERO, Duration::plus);
            Duration breakDur = Duration.between(dayStart, dayEnd).minus(worked);
            if (breakDur.isNegative()) {
                throw new IllegalStateException("Überlappende Einträge am " + group.getFirst().date());
            }
            String remark = group.stream()
                    .map(TimeEntry::remark)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(", "));
            days.add(new TimeEntry(group.getFirst().date(), dayStart, dayEnd, breakDur, worked, remark));
        }
        return days;
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

    private static String formatDecimalHours(Duration d) {
        double hours = d.toSeconds() / 3600.0;
        return String.format(Locale.GERMAN, "%.2f", hours);
    }

    private void writePdf(File output, YearMonth month, Employee employee, List<TimeEntry> days) throws IOException {
        byte[] template;
        try (InputStream is = UkshStundenzettel.class.getResourceAsStream("/stundenzettel-template.pdf")) {
            if (is == null) throw new IOException("PDF-Vorlage stundenzettel-template.pdf nicht im Klassenpfad gefunden");
            template = is.readAllBytes();
        }
        try (PDDocument doc = Loader.loadPDF(template)) {
            PDPage page = doc.getPage(0);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setFont(font, FONT_SIZE);
                drawText(cs, 285f, 715f, month.format(MONTH_FORMATTER));
                float employeeDetailsY = 686f;
                drawText(cs, 20f, employeeDetailsY, employee.lastName() + ", " + employee.firstName());
                drawText(cs, 162f, employeeDetailsY, employee.employeeId());
                drawText(cs, 310f, employeeDetailsY, employee.department());

                for (TimeEntry d : days) {
                    int day = d.date().getDayOfMonth();
                    float y = 584f - (day - 1) * 14.52f;
                    drawText(cs, 75f, y, d.start().format(TIME_FORMATTER));
                    drawText(cs, 176f, y, d.end().format(TIME_FORMATTER));
                    drawText(cs, 263f, y, formatDecimalHours(d.breakDuration()));
                    drawText(cs, 337f, y, formatDecimalHours(d.total()));
                    drawText(cs, 393f, y, d.remark());
                }

                Duration totalSum = days.stream().map(TimeEntry::total).reduce(Duration.ZERO, Duration::plus);
                drawText(cs, 337f, 134f, formatDecimalHours(totalSum));
            }
            Files.createDirectories(output.getAbsoluteFile().getParentFile().toPath());
            doc.save(output);
        }
    }

    private static void drawText(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new UkshStundenzettel()).execute(args);
        System.exit(exitCode);
    }

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            Properties props = new Properties();
            try (InputStream is = UkshStundenzettel.class.getResourceAsStream("/version.properties")) {
                if (is == null) throw new IOException("version.properties nicht im Klassenpfad gefunden");
                props.load(is);
            }
            return new String[]{"uksh-stundenzettel " + props.getProperty("version")};
        }
    }
}
