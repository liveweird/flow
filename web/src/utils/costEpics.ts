import type { TFunction } from "i18next";
import type { CostEpicBreakdown, CostEpicColumn, CostEpicRow } from "../api/reports";
import type { EpicDrillTarget } from "./reportDrill";

/** The drill keys the server knows besides an epic id. */
const NO_EPIC_KEY = "NO_EPIC";
const OTHER_EPICS_KEY = "OTHER_EPICS";

/** The key a row drills with: its epic id, or the fixed word of the two synthetic rows. */
export function epicDrillKey(row: CostEpicRow): string {
  if (row.kind === "OTHER") return OTHER_EPICS_KEY;
  if (row.kind === "NO_EPIC") return NO_EPIC_KEY;
  return String(row.epicId);
}

/** A row in words: "FLO-10 Checkout", "Other epics (N)" or "No epic". */
export function epicRowName(t: TFunction, row: CostEpicRow): string {
  if (row.kind === "OTHER") return t("reports.costMatrix.epics.otherEpics", { count: row.count ?? 0 });
  if (row.kind === "NO_EPIC") return t("reports.costMatrix.epics.noEpic");
  return [row.issueKey ?? String(row.epicId), row.summary].filter((part) => part !== null && part !== "").join(" ");
}

/** A column in words: the author team (UNIT), or the author (TEAM / USER); the unassigned team and the no-author bucket have their own labels. */
export function epicColumnName(t: TFunction, column: CostEpicColumn, level: "UNIT" | "TEAM" | "USER"): string {
  if (level === "UNIT") return column.label ?? t("reports.groups.unassigned");
  return column.label ?? column.accountId ?? t("reports.costMatrix.noAuthor");
}

/**
 * The drawer heading of an epic-table figure: the row as the title ("FLO-10 Checkout", "No epic", "Other epics (N)"), the column it
 * sits in as the dimmed subtitle ("Alpha", "Ann"); a Total figure has no column, so no subtitle. The names come from the report in hand
 * (a deep link names a row or column the report does not list by its id / label as the URL spells it).
 */
export function epicHeading(t: TFunction, target: EpicDrillTarget, byEpic: CostEpicBreakdown, level: "UNIT" | "TEAM" | "USER"): { title: string; subtitle?: string } {
  const names = target.keys.map((key) => {
    const row = byEpic.rows.find((candidate) => epicDrillKey(candidate) === key);
    if (row !== undefined) return epicRowName(t, row);
    return key === NO_EPIC_KEY ? t("reports.costMatrix.epics.noEpic") : key;
  });
  const column =
    target.segmentAccountId !== undefined
      ? byEpic.columns.find((candidate) => candidate.accountId === target.segmentAccountId)
      : byEpic.columns.find((candidate) => target.teamId !== undefined && candidate.teamId === target.teamId && candidate.accountId === null);
  const subtitle = target.segmentAccountId !== undefined || target.teamId !== undefined ? (column ? epicColumnName(t, column, level) : (target.segmentAccountId ?? undefined)) : undefined;
  return { title: names.join(", "), subtitle };
}
