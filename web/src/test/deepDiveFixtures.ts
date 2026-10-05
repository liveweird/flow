// Deep dive test fixtures: a typed report factory (defaults = an empty, derived report over a
// one-day range) and a task factory. Typed against the generated schema so a spec drift breaks the
// fixtures, not just the page.
import type {
  DeepDiveEpic,
  DeepDiveReport,
  DeepDiveTask,
} from "../api/reports";

export function deepDiveTask(
  key: string,
  partial: Partial<DeepDiveTask> = {},
): DeepDiveTask {
  return {
    key,
    summary: null,
    epicKey: null,
    planBasisMd: null,
    planSource: "NONE",
    noPlanReason: null,
    pv: [],
    exec: [],
    done: null,
    cost: [],
    totals: { pvMd: 0, execTaskDays: 0, evMd: 0, costMd: 0 },
    ...partial,
  };
}

export function deepDiveEpic(
  key: string | null,
  partial: Partial<DeepDiveEpic> = {},
): DeepDiveEpic {
  return {
    key,
    summary: null,
    plannedStart: null,
    plannedDue: null,
    budgetMd: null,
    ownCost: null,
    ...partial,
  };
}

export function deepDiveReport(
  partial: Partial<DeepDiveReport> = {},
): DeepDiveReport {
  return {
    meta: {
      derivedAt: 1_700_000_000_000,
      configRevision: 1,
      from: null,
      to: null,
      level: "UNIT",
      domainView: "TASK",
      resolvedSprints: [],
      minSampleSize: 5,
    },
    mode: "EPICS",
    range: { from: "2026-09-01", to: "2026-09-01", asOfDay: "2026-09-01" },
    nonWorkingDays: [],
    sprints: [],
    authors: [],
    epics: [],
    tasks: [],
    quality: {
      neverInSprint: 0,
      noEstimate: 0,
      noWorkingDay: 0,
      epicsWithoutWindow: 0,
      laterFallback: 0,
      epicOwnCostMd: 0,
    },
    note: null,
    ...partial,
  };
}

/**
 * 13 days across a month boundary that falls mid-week: Thu 2026-08-27 … Tue 2026-09-08 (Mon 08-31 and
 * Tue 09-01 share ISO week 36). Offsets 2, 3 and 9, 10 are the two weekends.
 */
export const MONTH_CROSSING = {
  range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-09-08" },
  nonWorkingDays: [2, 3, 9, 10],
} satisfies Pick<DeepDiveReport, "range" | "nonWorkingDays">;

/** 10 days across a year boundary: Sat 2025-12-27 … Mon 2026-01-05 (ISO week 2026-W01 starts Mon 12-29). */
export const YEAR_CROSSING = {
  range: { from: "2025-12-27", to: "2026-01-05", asOfDay: "2026-01-05" },
  nonWorkingDays: [0, 1, 7, 8],
} satisfies Pick<DeepDiveReport, "range" | "nonWorkingDays">;
