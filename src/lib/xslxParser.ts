import { read, utils, SSF, type Range, type WorkBook, type WorkSheet } from "xlsx";
import { LocalDate, LocalTime, Duration } from "@js-joda/core";
import { Effect } from "effect";

export interface EmployeeDetails {
    firstName: string;
    lastName: string;
    employeeId: string;
    department: string;
}

export interface TimesheetEntry {
    date: LocalDate;
    start: LocalTime;
    finish: LocalTime;
    break: Duration;
    workedHours: Duration;
    remark: string;
}

export interface Timesheet {
    employeeDetails: EmployeeDetails;
    entries: TimesheetEntry[];
}

const TIME_ENTRIES_SHEET_NAME = "Einträge";
const EMPLOYEE_DETAILS_SHEET_NAME = "Stammdaten";
const EMPLOYEE_DETAILS_NAMES = {
    firstName: "Vorname",
    lastName: "Nachname",
    employeeId: "Personalnummer",
    department: "Einsatzbereich",
} as const;
const REQUIRED_COLUMN_NAMES = {
    date: "Datum",
    start: "Beginn",
    finish: "Ende",
    break: "Pause",
    remark: "Bemerkung",
} as const;

export function parseXlsx(xlsxFile: File, year: number, month: number): Effect.Effect<Timesheet, Error> {
    return Effect.gen(function* () {
        const bytes = yield* toArrayBuffer(xlsxFile);
        const workbook = read(bytes, { sheets: [TIME_ENTRIES_SHEET_NAME, EMPLOYEE_DETAILS_SHEET_NAME] });
        const employeeDetails = yield* extractEmployeeDetails(workbook);
        const entries = yield* extractTimeEntries(workbook, year, month);
        return { employeeDetails, entries };
    });
}

const toArrayBuffer = (file: File) => Effect.tryPromise({
    try: () => file.arrayBuffer(),
    catch: (e) => new Error(`Datei konnte nicht gelesen werden: ${e}`),
})

function extractEmployeeDetails(workbook: WorkBook): Effect.Effect<EmployeeDetails, Error> {
    return Effect.all({
        firstName: resolveNamedRange(workbook, EMPLOYEE_DETAILS_NAMES.firstName),
        lastName: resolveNamedRange(workbook, EMPLOYEE_DETAILS_NAMES.lastName),
        employeeId: resolveNamedRange(workbook, EMPLOYEE_DETAILS_NAMES.employeeId),
        department: resolveNamedRange(workbook, EMPLOYEE_DETAILS_NAMES.department),
    });
}

function extractTimeEntries(workbook: WorkBook, year: number, month: number): Effect.Effect<TimesheetEntry[], Error> {
    const sheet = workbook.Sheets[TIME_ENTRIES_SHEET_NAME];
    if (!sheet) return Effect.fail(new Error(`Tabellenblatt ${TIME_ENTRIES_SHEET_NAME} nicht gefunden`));
    if (!sheet["!ref"]) return Effect.succeed([]);

    return Effect.gen(function* () {
        const range = utils.decode_range(sheet["!ref"]!);
        const { headerRow, columns } = yield* locateColumns(sheet, range);
        const date1904 = workbook.Workbook?.WBProps?.date1904 ?? false;

        const entries: TimesheetEntry[] = [];
        for (let row = headerRow + 1; row <= range.e.r; row++) {
            const serial = cellNumber(sheet, row, columns.date);
            const dateParts = serial === null ? null : SSF.parse_date_code(serial, { date1904 });
            // Skip rows without a usable date (blank rows, totals, etc.).
            if (!dateParts) continue;
            // Only keep entries from the requested month.
            if (dateParts.y !== year || dateParts.m !== month) continue;

            const date = LocalDate.of(dateParts.y, dateParts.m, dateParts.d);
            
            const startSec = dayFractionToSeconds(cellNumber(sheet, row, columns.start) ?? 0);
            const finishSec = dayFractionToSeconds(cellNumber(sheet, row, columns.finish) ?? 0);
            const breakSec = dayFractionToSeconds(cellNumber(sheet, row, columns.break) ?? 0);

            entries.push({
                date,
                start: LocalTime.ofSecondOfDay(startSec),
                finish: LocalTime.ofSecondOfDay(finishSec),
                break: Duration.ofSeconds(breakSec),
                workedHours: Duration.ofSeconds(finishSec - startSec - breakSec),
                remark: cellString(sheet, row, columns.remark),
            });
        }

        return yield* mergeSameDayEntries(entries);
    });
}

// The PDF has one row per day, so multiple sessions on the same date are merged
// into a single entry: earliest start, latest finish, summed breaks plus the gaps
// between sessions, and comma-joined remarks. Overlapping sessions are an error.
function mergeSameDayEntries(entries: TimesheetEntry[]): Effect.Effect<TimesheetEntry[], Error> {
    const byDate = new Map<string, TimesheetEntry[]>();
    for (const entry of entries) {
        const key = entry.date.toString();
        const group = byDate.get(key);
        if (group) group.push(entry);
        else byDate.set(key, [entry]);
    }

    const merged: TimesheetEntry[] = [];
    for (const group of byDate.values()) {
        if (group.length === 1) {
            merged.push(group[0]);
            continue;
        }

        const sessions = [...group].sort((a, b) => a.start.compareTo(b.start));

        let totalBreak = Duration.ZERO;
        for (const session of sessions) totalBreak = totalBreak.plus(session.break);

        for (let i = 1; i < sessions.length; i++) {
            const prev = sessions[i - 1];
            const curr = sessions[i];
            if (curr.start.isBefore(prev.finish)) {
                return Effect.fail(new Error(`Überlappende Arbeitszeiten am ${curr.date.toString()}`));
            }
            // The gap between two sessions counts as break time.
            totalBreak = totalBreak.plus(Duration.between(prev.finish, curr.start));
        }

        const start = sessions[0].start;
        const finish = sessions[sessions.length - 1].finish;
        const remark = sessions.map((s) => s.remark).filter((r) => r.length > 0).join(", ");

        merged.push({
            date: sessions[0].date,
            start,
            finish,
            break: totalBreak,
            workedHours: Duration.between(start, finish).minus(totalBreak),
            remark,
        });
    }

    return Effect.succeed(merged);
}

type ColumnMap = Record<keyof typeof REQUIRED_COLUMN_NAMES, number>

function locateColumns(sheet: WorkSheet, range: Range): Effect.Effect<{ headerRow: number; columns: ColumnMap }, Error> {
    const required = Object.entries(REQUIRED_COLUMN_NAMES) as [keyof typeof REQUIRED_COLUMN_NAMES, string][];

    for (let row = range.s.r; row <= range.e.r; row++) {
        const found: Partial<ColumnMap> = {};
        for (let col = range.s.c; col <= range.e.c; col++) {
            const cell = sheet[utils.encode_cell({ r: row, c: col })];
            const value = cell?.v;
            const key = required.find(([, name]) => name === String(value).trim())?.[0];
            if (key) found[key] = col;
        }
        if (required.every(([key]) => found[key] !== undefined)) {
            return Effect.succeed({ headerRow: row, columns: found as ColumnMap });
        }
    }
    return Effect.fail(new Error(`Kopfzeile mit den Spalten ${Object.values(REQUIRED_COLUMN_NAMES).join(", ")} nicht gefunden`));
}

const SECONDS_PER_DAY = 86400;

// Excel stores times as fractions of a 24-hour day; convert to whole seconds.
function dayFractionToSeconds(fraction: number): number {
    return Math.round(fraction * SECONDS_PER_DAY);
}

function cellNumber(sheet: WorkSheet, row: number, col: number): number | null {
    const cell = sheet[utils.encode_cell({ r: row, c: col })];
    if (!cell || cell.v == null) return null;
    if (typeof cell.v === "number") return cell.v;
    const n = Number(cell.v);
    return Number.isFinite(n) ? n : null;
}

function cellString(sheet: WorkSheet, row: number, col: number): string {
    const cell = sheet[utils.encode_cell({ r: row, c: col })];
    return cell?.v == null ? "" : String(cell.v);
}

function resolveNamedRange(workbook: WorkBook, name: string): Effect.Effect<string, Error> {
    const def = workbook.Workbook?.Names?.find((n) => n.Name === name);
    if (!def) return Effect.fail(new Error(`Benannter Bereich "${name}" konnte nicht gefunden werden`));

    const bang = def.Ref.lastIndexOf("!");
    const sheetName = def.Ref.slice(0, bang);
    const address = def.Ref.slice(bang + 1).replace(/\$/g, "");

    const cell = workbook.Sheets[sheetName]?.[address];
    if (!cell) return Effect.fail(new Error(`Zelle "${address}" in Blatt "${sheetName}" ist leer`));
    return Effect.succeed(String(cell.v));
}