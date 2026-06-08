import { PDFDocument, type PDFPage, type PDFFont } from "pdf-lib";
import fontkit from "@pdf-lib/fontkit";
import { Duration, DateTimeFormatter } from "@js-joda/core";
import { Effect } from "effect";
import type { Timesheet } from "./xslxParser";
import { GERMAN_MONTHS } from "./months";
const TEMPLATE_URL = "/stundenzettel-vorlage.pdf";
const FONT_URL = "/fonts/JetBrainsMono-Regular.ttf";

const LAYOUT = {
    fontSize: 10,
    yearMonth: { x: 285, y: 715 },
    employeeDetails: {
        fullName: { x: 20, y: 686 },
        employeeId: { x: 162, y: 686 },
        department: { x: 310, y: 686 },
    },
    table: {
        firstRowY: 584.25,
        rowHeight: 14.52,
        columns: {
            start: 76,
            finish: 177,
            break: 263,
            workedHours: 337,
            remark: 393,
        },
    },
    totalWorkedHours: { x: 337, y: 134 },
} as const;

const TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");


export function generatePdf(timesheet: Timesheet, year: number, month: number): Effect.Effect<Uint8Array, Error> {
    return Effect.gen(function* () {
        const [templateBytes, fontBytes] = yield* Effect.all([
            fetchArrayBuffer(TEMPLATE_URL, `PDF-Vorlage "${TEMPLATE_URL}" konnte nicht geladen werden`),
            fetchArrayBuffer(FONT_URL, `Schriftart "JetBrains Mono" konnte nicht geladen werden`),
        ], { concurrency: "unbounded" });

        return yield* Effect.tryPromise({
            try: () => stampTimesheet(templateBytes, fontBytes, timesheet, year, month),
            catch: (e) => new Error(`PDF konnte nicht erzeugt werden: ${e}`),
        });
    });
}

function fetchArrayBuffer(url: string, errorMessage: string): Effect.Effect<ArrayBuffer, Error> {
    return Effect.tryPromise({
        try: () => fetch(url).then((response) => {
            if (!response.ok) throw new Error(`Status ${response.status}`);
            return response.arrayBuffer();
        }),
        catch: (e) => new Error(`${errorMessage}: ${e}`),
    });
}

async function stampTimesheet(templateBytes: ArrayBuffer, fontBytes: ArrayBuffer, timesheet: Timesheet, year: number, month: number): Promise<Uint8Array> {
    const pdf = await PDFDocument.load(templateBytes);
    pdf.registerFontkit(fontkit);
    const font = await pdf.embedFont(fontBytes, { subset: true });
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
        const remark = entry.remark.length > 18 ? entry.remark.slice(0, 18) + "..." : entry.remark;
        draw(remark, columns.remark, y);
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
