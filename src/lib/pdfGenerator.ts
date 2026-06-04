import { PDFDocument, StandardFonts, type PDFPage, type PDFFont } from "pdf-lib";
import { Duration, DateTimeFormatter } from "@js-joda/core";
import { Effect } from "effect";
import type { Timesheet } from "./xslxParser";

const TEMPLATE_URL = "/stundenzettel-vorlage.pdf";

const LAYOUT = {
    fontSize: 10,
    yearMonth: { x: 285, y: 715 },
    employeeDetails: {
        fullName: { x: 20, y: 686 },
        employeeId: { x: 162, y: 686 },
        department: { x: 310, y: 686 },
    },
    table: {
        firstRowY: 584.5,
        rowHeight: 14.52,
        columns: {
            start: 76,
            finish: 177,
            break: 264,
            workedHours: 338,
            remark: 394,
        },
    },
    totalWorkedHours: { x: 337, y: 134 },
} as const;

const TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

const GERMAN_MONTHS = [
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
] as const;

export function generatePdf(timesheet: Timesheet, year: number, month: number): Effect.Effect<Uint8Array, Error> {
    return Effect.gen(function* () {
        const templateBytes = yield* Effect.tryPromise({
            try: () => fetch(TEMPLATE_URL).then((response) => {
                if (!response.ok) throw new Error(`Status ${response.status}`);
                return response.arrayBuffer();
            }),
            catch: (e) => new Error(`PDF-Vorlage "${TEMPLATE_URL}" konnte nicht geladen werden: ${e}`),
        });

        return yield* Effect.tryPromise({
            try: () => stampTimesheet(templateBytes, timesheet, year, month),
            catch: (e) => new Error(`PDF konnte nicht erzeugt werden: ${e}`),
        });
    });
}

async function stampTimesheet(templateBytes: ArrayBuffer, timesheet: Timesheet, year: number, month: number): Promise<Uint8Array> {
    const pdf = await PDFDocument.load(templateBytes);
    const font = await pdf.embedFont(StandardFonts.Helvetica);
    const page = pdf.getPages()[0];

    const draw = drawTextAt(page, font);

    draw(`${GERMAN_MONTHS[month - 1]} ${year}`, LAYOUT.yearMonth.x, LAYOUT.yearMonth.y);

    const { firstName, lastName, employeeId, department } = timesheet.employeeDetails;
    draw(`${lastName}, ${firstName}`, LAYOUT.employeeDetails.fullName.x, LAYOUT.employeeDetails.fullName.y);
    draw(employeeId, LAYOUT.employeeDetails.employeeId.x, LAYOUT.employeeDetails.employeeId.y);
    draw(department, LAYOUT.employeeDetails.department.x, LAYOUT.employeeDetails.department.y);

    const { firstRowY, rowHeight, columns } = LAYOUT.table;
    let totalWorked = Duration.ZERO;
    for (const entry of timesheet.entries) {
        const y = firstRowY - (entry.date.dayOfMonth() - 1) * rowHeight;
        draw(entry.start.format(TIME_FORMAT), columns.start, y);
        draw(entry.finish.format(TIME_FORMAT), columns.finish, y);
        draw(formatDecimalHours(entry.break), columns.break, y);
        draw(formatDecimalHours(entry.workedHours), columns.workedHours, y);
        draw(entry.remark, columns.remark, y);
        totalWorked = totalWorked.plus(entry.workedHours);
    }

    draw(formatDecimalHours(totalWorked), LAYOUT.totalWorkedHours.x, LAYOUT.totalWorkedHours.y);

    return pdf.save();
}

function drawTextAt(page: PDFPage, font: PDFFont) {
    return (text: string, x: number, y: number) =>
        page.drawText(text, { x, y, size: LAYOUT.fontSize, font });
}

function formatDecimalHours(duration: Duration): string {
    const hours = duration.toMillis() / 3_600_000;
    return hours.toFixed(2).replace(".", ",");
}
