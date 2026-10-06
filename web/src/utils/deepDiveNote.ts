import type { DeepDiveReport } from "../api/reports";

/** The server's `note` classified by kind: what the Deep dive page says in place of (or beside) the matrix. */

export type DeepDiveNoteKind = "RANGE_CLAMPED" | "NOT_DERIVED" | "OTHER";

/** The server's `note` with its kind; `null` when absent. */
export type DeepDiveNote = { kind: DeepDiveNoteKind; text: string } | null;

/** Whether the SELECTED connection has derived (`meta.derivedAt` spans every connection in scope, so it cannot say). */
export const isDerived = (report: DeepDiveReport) => report.range.asOfDay !== null;

export function noteOf(report: DeepDiveReport): DeepDiveNote {
  if (report.note === null) return null;
  if (report.note.startsWith("RANGE_CLAMPED"))
    return { kind: "RANGE_CLAMPED", text: report.note };
  const notDerived = !isDerived(report) && report.tasks.length === 0;
  return { kind: notDerived ? "NOT_DERIVED" : "OTHER", text: report.note };
}
