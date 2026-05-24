import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

class PdfGenerator {
    private static final float FONT_SIZE = 11f;
    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    static void writePdf(File output, YearMonth month, Employee employee, List<TimeEntry> days) throws IOException {
        byte[] template;
        try (InputStream is = PdfGenerator.class.getResourceAsStream("/stundenzettel-template.pdf")) {
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

    static String formatDecimalHours(Duration d) {
        double hours = d.toSeconds() / 3600.0;
        return String.format(Locale.GERMAN, "%.2f", hours);
    }

    private static void drawText(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
