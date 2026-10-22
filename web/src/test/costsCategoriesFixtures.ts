// The Costs work-category widgets' fixtures (`categoryTargets`, `categoryShareTrend`, A45) over `COSTS_TEAM`'s 14.5 MD: Run 9, Build 4.5,
// uncategorized 1. The figures are the server's: shares exact, deltas in percentage points rounded half-up to one decimal.
import type { CostsCategoryTargets, CostsCategoryTrend, CostsReport } from "../api/reports";
import { COSTS_TEAM } from "./reportFixtures";

/** Run 60 / Build 30 / Retired 10 (no longer mapped, nothing logged); the uncategorized time has no target. */
export const TARGETS_CONFIGURED: CostsCategoryTargets = {
  configured: true,
  totalMd: 14.5,
  rows: [
    { key: "Run", targetPercent: 60, actualMd: 9, actualShare: 9 / 14.5, deltaPoints: 2.1, mapped: true },
    { key: "Build", targetPercent: 30, actualMd: 4.5, actualShare: 4.5 / 14.5, deltaPoints: 1, mapped: true },
    { key: "Retired", targetPercent: 10, actualMd: 0, actualShare: 0, deltaPoints: -10, mapped: false },
    { key: "UNCATEGORIZED", targetPercent: 0, actualMd: 1, actualShare: 1 / 14.5, deltaPoints: 6.9, mapped: true },
  ],
};

/** No stored set: the rows carry the actuals only. */
export const TARGETS_UNCONFIGURED: CostsCategoryTargets = {
  configured: false,
  totalMd: 14.5,
  rows: [
    { key: "Run", targetPercent: null, actualMd: 9, actualShare: 9 / 14.5, deltaPoints: null, mapped: true },
    { key: "Build", targetPercent: null, actualMd: 4.5, actualShare: 4.5 / 14.5, deltaPoints: null, mapped: true },
  ],
};

/** A configured set over a period where nothing was logged: no actual share, so no delta. */
export const TARGETS_NOTHING_LOGGED: CostsCategoryTargets = {
  configured: true,
  totalMd: 0,
  rows: [
    { key: "Run", targetPercent: 70, actualMd: 0, actualShare: null, deltaPoints: null, mapped: true },
    { key: "Spike", targetPercent: 0, actualMd: 0, actualShare: null, deltaPoints: null, mapped: false },
  ],
};

const week = (weekStart: string, cumulativeMd: number, run: [number, number | null], build: [number, number | null], none: [number, number | null]) => ({
  weekStart,
  cumulativeMd,
  byCategory: [
    { key: "Run", cumulativeMd: run[0], share: run[1] },
    { key: "Build", cumulativeMd: build[0], share: build[1] },
    { key: "UNCATEGORIZED", cumulativeMd: none[0], share: none[1] },
  ],
});

/** Three weeks, cumulative from the period start; the last week is the whole period (14.5 MD). */
export const TREND_THREE_WEEKS: CostsCategoryTrend = {
  weeks: [
    week("2026-03-02", 4, [3, 0.75], [1, 0.25], [0, 0]),
    week("2026-03-09", 9, [6, 6 / 9], [2.5, 2.5 / 9], [0.5, 0.5 / 9]),
    week("2026-03-16", 14.5, [9, 9 / 14.5], [4.5, 4.5 / 14.5], [1, 1 / 14.5]),
  ],
};

/** A first week with nothing logged yet: its shares are null (a gap in the chart, a dash in the table). */
export const TREND_EMPTY_FIRST_WEEK: CostsCategoryTrend = {
  weeks: [week("2026-03-02", 0, [0, null], [0, null], [0, null]), TREND_THREE_WEEKS.weeks[2]],
};

export const COSTS_WITH_CATEGORIES: CostsReport = { ...COSTS_TEAM, categoryTargets: TARGETS_CONFIGURED, categoryShareTrend: TREND_THREE_WEEKS };

export const COSTS_CATEGORIES_UNCONFIGURED: CostsReport = { ...COSTS_TEAM, categoryTargets: TARGETS_UNCONFIGURED, categoryShareTrend: TREND_THREE_WEEKS };

/** One author picked (USER level): no targets (they are the team's), the trend of the person's own mix. */
export const COSTS_CATEGORIES_USER: CostsReport = {
  ...COSTS_TEAM,
  meta: { ...COSTS_TEAM.meta, level: "USER" },
  categoryTargets: null,
  categoryShareTrend: TREND_THREE_WEEKS,
};

/** A period of more than 160 weeks: the targets are answered, the trend is not. */
export const COSTS_CATEGORIES_TOO_LONG: CostsReport = { ...COSTS_TEAM, categoryTargets: TARGETS_CONFIGURED, categoryShareTrend: null };
