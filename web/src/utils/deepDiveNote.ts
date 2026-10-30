import type { DeepDiveReport } from "../api/reports";

/** The server's `note` classified by kind: what the Deep dive page says in place of (or beside) the matrix. */

export type DeepDiveNoteKind = "RANGE_CLAMPED" | "HISTORY_HORIZON" | "NOT_DERIVED" | "OTHER";

/**
 * The server's `note` with its kind; `null` when absent. `historyHorizon` is set whenever the note carries the history horizon's message
 * (A48): alone it is the whole note (kind `HISTORY_HORIZON`), beside a span clamp it follows it, joined with ". " (kind `RANGE_CLAMPED`, the
 * note's first part). The page says each part in its own words.
 */
export type DeepDiveNote = { kind: DeepDiveNoteKind; text: string; historyHorizon: boolean } | null;

const HISTORY_HORIZON_CODE = "HISTORY_HORIZON";

/** Whether the SELECTED connection has derived (`meta.derivedAt` spans every connection in scope, so it cannot say). */
export const isDerived = (report: DeepDiveReport) => report.range.asOfDay !== null;

export function noteOf(report: DeepDiveReport): DeepDiveNote {
  if (report.note === null) return null;
  const historyHorizon = report.note.includes(HISTORY_HORIZON_CODE);
  if (report.note.startsWith("RANGE_CLAMPED")) return { kind: "RANGE_CLAMPED", text: report.note, historyHorizon };
  if (historyHorizon) return { kind: "HISTORY_HORIZON", text: report.note, historyHorizon };
  const notDerived = !isDerived(report) && report.tasks.length === 0;
  return { kind: notDerived ? "NOT_DERIVED" : "OTHER", text: report.note, historyHorizon };
}
