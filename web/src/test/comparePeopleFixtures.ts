// Compare people (report 18) test fixtures: a response typed against the generated schema, so a spec drift breaks the fixtures and not
// just the page. Ann (Alpha) is side A and Cy (Beta) side B — two people in two teams, the case the report exists for. The figures are
// hand-written to show every kind of verdict: a better side per direction (higher, lower, closer to the target), a tie, a neutral
// row, a side under the minimum sample (`minSampleSize` 5) and a withheld figure.
import type { ComparePeopleReport, CompareCell, CompareRow, Distribution } from "../api/reports";
import type { ReportDrillItems } from "../api/reportDrill";

const META: ComparePeopleReport["meta"] = {
  derivedAt: 1_780_000_000_000,
  configRevision: 4,
  from: "2026-07-02",
  to: "2026-09-29",
  level: "USER",
  domainView: "TASK",
  resolvedSprints: [],
  minSampleSize: 5,
  historyHorizon: null,
};

const cell = (value: number | null, n: number, hidden = false): CompareCell => ({ value, n, hidden });

type RowSpec = Pick<CompareRow, "key" | "group" | "unit" | "direction" | "tolerance"> &
  Partial<Pick<CompareRow, "target" | "better" | "difference" | "margin" | "drill">>;

const row = (spec: RowSpec, a: CompareCell, b: CompareCell): CompareRow => ({ ...spec, a, b });

const ANN = { accountId: "acc-ann", label: "Ann Author" };
const CY = { accountId: "acc-cy", label: "Cy Coder" };

/** A visible distribution over `counts.length` equal buckets of `step` from 0; `byUser` splits every bucket per person. */
function union(counts: number[], step: number, a: number[], b: number[], stats: { p50: number; p90: number; p95: number; mean: number }): Distribution {
  return {
    n: counts.reduce((sum, count) => sum + count, 0),
    hidden: false,
    ...stats,
    min: 0,
    max: step * counts.length,
    histogram: counts.map((count, i) => ({ from: i * step, to: i === counts.length - 1 ? null : (i + 1) * step, count })),
    byUser: [
      { ...ANN, counts: a },
      { ...CY, counts: b },
    ],
  };
}

/**
 * The rows every caller gets (32), in the server's table order, then the four capacity rows (an editor of both teams only). Verdicts, by
 * row: delivered MD and items (higher: A), task size (lower: A) and reopened share (lower: A), cycle p50 (lower: A by 1.4 days), p90 (a tie), elapsed p50 (B), flow efficiency (A),
 * reported time (target 1: A is closer), PRED(25) at start (a tie) and at done (B), re-estimated share (neutral), the median change (B
 * withheld), average WIP (lower: A), WIP at the end (A) and its age (B), the blocked shares (a tie, then A), logged (neutral), foreign
 * (neutral), coverage (B), late (A) and no estimate (a tie at zero).
 */
const BASE_ROWS: CompareRow[] = [
  row(
    { key: "deliveredMd", group: "DELIVERY", unit: "MD", direction: "HIGHER", tolerance: 0.005, drill: "DELIVERED", better: "A", difference: -7.5, margin: 7.5 },
    cell(31.5, 14),
    cell(24, 10),
  ),
  row(
    { key: "deliveredItems", group: "DELIVERY", unit: "ITEMS", direction: "HIGHER", tolerance: 0, drill: "DELIVERED", better: "A", difference: -4, margin: 4 },
    cell(14, 14),
    cell(10, 10),
  ),
  row(
    { key: "taskSizeP50", group: "DELIVERY", unit: "MD", direction: "LOWER", tolerance: 0.005, drill: "TASK_SIZE", better: "A", difference: 1, margin: 1 },
    cell(2, 12),
    cell(3, 7),
  ),
  row(
    { key: "reopenedShare", group: "DELIVERY", unit: "SHARE", direction: "LOWER", tolerance: 0.005, drill: "REOPENED", better: "A", difference: 0.2, margin: 0.2 },
    cell(0.1, 14),
    cell(0.3, 10),
  ),
  row(
    { key: "cycleWorkingDaysP50", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "CYCLE_WORKING_DAYS", better: "A", difference: 1.4, margin: 1.4 },
    cell(3.5, 12),
    cell(4.9, 7),
  ),
  row(
    { key: "cycleWorkingDaysP90", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "CYCLE_WORKING_DAYS", difference: 0.02 },
    cell(7.5, 12),
    cell(7.52, 7),
  ),
  row(
    { key: "cycleElapsedDaysP50", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "CYCLE_ELAPSED_DAYS", better: "B", difference: -0.8, margin: 0.8 },
    cell(5, 12),
    cell(4.2, 7),
  ),
  row(
    { key: "flowEfficiencyP50", group: "CYCLE", unit: "SHARE", direction: "HIGHER", tolerance: 0.005, drill: "FLOW_EFFICIENCY", better: "A", difference: -0.12, margin: 0.12 },
    cell(0.42, 12),
    cell(0.3, 7),
  ),
  row(
    { key: "reportedTimeRatioP50", group: "CYCLE", unit: "RATIO", direction: "TARGET", target: 1, tolerance: 0.01, drill: "REPORTED_TIME_RATIO", better: "A", difference: 0.5, margin: 0.1 },
    cell(0.8, 11),
    cell(1.3, 6),
  ),
  row(
    { key: "accuracyAtStartPred25", group: "ESTIMATION", unit: "SHARE", direction: "HIGHER", tolerance: 0.005, drill: "ACCURACY_AT_START", difference: 0 },
    cell(0.5, 10),
    cell(0.5, 8),
  ),
  row(
    { key: "accuracyAtDonePred25", group: "ESTIMATION", unit: "SHARE", direction: "HIGHER", tolerance: 0.005, drill: "ACCURACY_AT_DONE", better: "B", difference: 0.1, margin: 0.1 },
    cell(0.6, 10),
    cell(0.7, 8),
  ),
  row(
    { key: "adjustedShare", group: "ESTIMATION", unit: "SHARE", direction: "NEUTRAL", tolerance: 0.005, drill: "ADJUSTED", difference: 0.15 },
    cell(0.2, 10),
    cell(0.35, 8),
  ),
  row(
    { key: "adjustmentMedianChange", group: "ESTIMATION", unit: "RATIO", direction: "NEUTRAL", tolerance: 0.01, drill: "ADJUSTMENT_CHANGE" },
    cell(0.25, 6),
    cell(null, 2, true),
  ),
  row(
    { key: "avgWip", group: "WIP", unit: "ITEMS_PER_DAY", direction: "LOWER", tolerance: 0.05, drill: "WIP_TASK_DAYS", better: "A", difference: 0.7, margin: 0.7 },
    cell(2.4, 60),
    cell(3.1, 60),
  ),
  row(
    { key: "wipAtEndItems", group: "WIP", unit: "ITEMS", direction: "LOWER", tolerance: 0, drill: "WIP_AT_END", better: "A", difference: 2, margin: 2 },
    cell(6, 6),
    cell(8, 8),
  ),
  row(
    { key: "wipAtEndMedianAgeWd", group: "WIP", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "WIP_AT_END", better: "B", difference: -0.5, margin: 0.5 },
    cell(4, 6),
    cell(3.5, 8),
  ),
  row(
    { key: "blockedItemsShare", group: "WIP", unit: "SHARE", direction: "LOWER", tolerance: 0.005, drill: "BLOCKED", difference: 0 },
    cell(0.1, 14),
    cell(0.1, 10),
  ),
  row(
    { key: "blockedShareOfCycle", group: "WIP", unit: "SHARE", direction: "LOWER", tolerance: 0.005, drill: "BLOCKED", better: "A", difference: 0.02, margin: 0.02 },
    cell(0.04, 14),
    cell(0.06, 10),
  ),
  row(
    { key: "loggedMd", group: "TIME", unit: "MD", direction: "NEUTRAL", tolerance: 0.005, drill: "LOGGED", difference: -1.5 },
    cell(20, 15),
    cell(18.5, 12),
  ),
  row(
    { key: "foreignShare", group: "TIME", unit: "SHARE", direction: "NEUTRAL", tolerance: 0.005, drill: "FOREIGN", difference: -0.1 },
    cell(0.1, 15),
    cell(0, 12),
  ),
  row(
    { key: "worklogCoverage", group: "QUALITY", unit: "SHARE", direction: "HIGHER", tolerance: 0.005, drill: "NO_WORKLOGS", better: "B", difference: 0.1, margin: 0.1 },
    cell(0.9, 14),
    cell(1, 10),
  ),
  row(
    { key: "lateLoggingShare", group: "QUALITY", unit: "SHARE", direction: "LOWER", tolerance: 0.005, drill: "LATE", better: "A", difference: 0.15, margin: 0.15 },
    cell(0.05, 15),
    cell(0.2, 12),
  ),
  row(
    { key: "noEstimateShare", group: "QUALITY", unit: "SHARE", direction: "LOWER", tolerance: 0.005, drill: "NO_ESTIMATE", difference: 0 },
    cell(0, 14),
    cell(0, 10),
  ),
  // The CODE group (A50, Report 21's member cells): the volumes are NEUTRAL (a difference, never a side), the two p50s LOWER. Ann has merged merge requests and
  // reviewed none, Cy none merged (his p50s are withheld: no sample) and a few reviewed.
  row({ key: "commits", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "COMMITS", difference: -20 }, cell(52, 52), cell(32, 32)),
  row({ key: "linesChanged", group: "CODE", unit: "LINES", direction: "NEUTRAL", tolerance: 1, drill: "COMMITS", difference: -1200 }, cell(3400, 52), cell(2200, 32)),
  row({ key: "mrsMerged", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "MRS_MERGED", difference: -9 }, cell(14, 14), cell(5, 5)),
  row(
    { key: "mrCycleWorkingDaysP50", group: "CODE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "MR_CYCLE_WORKING_DAYS", better: "B", difference: -1.5, margin: 1.5 },
    cell(3, 14),
    cell(1.5, 5),
  ),
  row({ key: "mrSizeLinesP50", group: "CODE", unit: "LINES", direction: "LOWER", tolerance: 1, drill: "MR_SIZE_LINES" }, cell(120, 14), cell(null, 3, true)),
  row({ key: "reviewsGiven", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "REVIEWS_GIVEN", difference: 7 }, cell(0, 0), cell(7, 7)),
  row({ key: "approvalsGiven", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "APPROVALS_GIVEN", difference: 4 }, cell(0, 0), cell(4, 4)),
  row({ key: "comments", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "COMMENTS", difference: 12 }, cell(3, 3), cell(15, 15)),
];

const CAPACITY_ROWS: CompareRow[] = [
  row(
    { key: "deliveredMdPerAvailableMd", group: "DELIVERY", unit: "MD_PER_MD", direction: "HIGHER", tolerance: 0.005, drill: "DELIVERED", better: "B", difference: 0.05, margin: 0.05 },
    cell(1.05, 14),
    cell(1.1, 10),
  ),
  row(
    { key: "deliveredItemsPerAvailableMd", group: "DELIVERY", unit: "ITEMS_PER_MD", direction: "HIGHER", tolerance: 0.05, drill: "DELIVERED", better: "A", difference: -0.2, margin: 0.2 },
    cell(0.7, 14),
    cell(0.5, 10),
  ),
  row(
    { key: "availableMd", group: "TIME", unit: "MD", direction: "NEUTRAL", tolerance: 0.005, difference: -10 },
    cell(30, 90),
    cell(20, 90),
  ),
  row(
    { key: "loggedPerAvailableMd", group: "TIME", unit: "MD_PER_MD", direction: "TARGET", target: 1, tolerance: 0.005, drill: "LOGGED", better: "A", difference: -0.12, margin: 0.12 },
    cell(0.95, 15),
    cell(0.83, 12),
  ),
];

/** The rows in the server's order: the capacity rows sit in their own groups (delivery after the reopened share, time after the logged MD). */
function withCapacity(): CompareRow[] {
  const find = (key: string) => CAPACITY_ROWS.find((candidate) => candidate.key === key) as CompareRow;
  const out: CompareRow[] = [];
  for (const base of BASE_ROWS) {
    out.push(base);
    if (base.key === "reopenedShare") out.push(find("deliveredMdPerAvailableMd"), find("deliveredItemsPerAvailableMd"));
    if (base.key === "loggedMd") out.push(find("availableMd"), find("loggedPerAvailableMd"));
  }
  return out;
}

/** What every caller gets: two people in two teams, no capacity block and none of its four rows. */
export const COMPARE_PEOPLE: ComparePeopleReport = {
  meta: META,
  people: [ANN, CY],
  rows: BASE_ROWS,
  cycleTime: {
    workingDays: union([3, 6, 5, 3, 2], 2, [2, 4, 3, 2, 1], [1, 2, 2, 1, 1], { p50: 3.9, p90: 7.5, p95: 9, mean: 4.4 }),
    elapsedDays: union([2, 5, 6, 4, 2], 3, [1, 4, 4, 2, 1], [1, 1, 2, 2, 1], { p50: 4.8, p90: 11, p95: 13, mean: 6 }),
  },
  workMix: {
    activityType: [
      {
        key: "Story",
        a: { md: 21, items: 9, share: 0.6667 },
        b: { md: 12, items: 5, share: 0.5 },
      },
      {
        key: "Bug",
        a: { md: 10.5, items: 5, share: 0.3333 },
        b: { md: 12, items: 5, share: 0.5 },
      },
      { key: null, a: { md: 0, items: 0, share: 0 }, b: { md: 0, items: 0, share: 0 } },
    ],
    workCategory: [
      { key: "UNCATEGORIZED", a: { md: 31.5, items: 14, share: 1 }, b: { md: 24, items: 10, share: 1 } },
    ],
    domain: [
      { key: "FLO", a: { md: 31.5, items: 14, share: 1 }, b: { md: 20, items: 8, share: 0.8333 } },
      { key: null, a: { md: 0, items: 0, share: 0 }, b: { md: 4, items: 2, share: 0.1667 } },
    ],
  },
  wipDays: { workingDays: 60, firstDay: "2026-07-02", lastDay: "2026-09-29" },
};

/** The same comparison for an administrator or the manager of both people's teams: the `capacity` block and the four capacity rows. */
export const COMPARE_PEOPLE_CAPACITY: ComparePeopleReport = {
  ...COMPARE_PEOPLE,
  rows: withCapacity(),
  capacity: { days: 90 },
};

/** A pair with almost nothing to compare: B finished three tasks (under the minimum), so only the pure item count keeps its cue and B's distributions are withheld. */
export const COMPARE_PEOPLE_THIN: ComparePeopleReport = {
  ...COMPARE_PEOPLE,
  rows: [
    row(
      { key: "deliveredItems", group: "DELIVERY", unit: "ITEMS", direction: "HIGHER", tolerance: 0, drill: "DELIVERED", better: "A", difference: -11, margin: 11 },
      cell(14, 14),
      cell(3, 3),
    ),
    row(
      { key: "cycleWorkingDaysP50", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "CYCLE_WORKING_DAYS" },
      cell(3.5, 12),
      cell(null, 3, true),
    ),
  ],
  cycleTime: {
    workingDays: { ...COMPARE_PEOPLE.cycleTime.workingDays, n: 4, hidden: true, histogram: [], byUser: undefined },
    elapsedDays: { ...COMPARE_PEOPLE.cycleTime.elapsedDays, n: 4, hidden: true, histogram: [], byUser: undefined },
  },
  workMix: { activityType: [], workCategory: [], domain: [] },
  note: "No day has been derived yet for the work in progress.",
};

/**
 * [count] GitLab items of one person (the CODE rows' lists, A50): commits (`group/app@3f9a1c2b4d5e`) or merge requests (`group/app!12`), no team, and
 * [figure] (a commit's counted lines, a merge request's cycle or size; null for a review act).
 */
export function codeCompareItems(kind: "COMMIT" | "MERGE_REQUEST", count: number, figure: number | null = null): ReportDrillItems {
  return {
    meta: META,
    bar: { count, sumMd: null, from: null, to: null },
    items: Array.from({ length: count }, (_, index) => ({
      issueKey: kind === "COMMIT" ? `group/app@3f9a1c2b4d${String(index).padStart(2, "0")}` : `group/app!${index + 1}`,
      summary: kind === "COMMIT" ? `Commit ${index + 1}` : `Merge request ${index + 1}`,
      itemKind: kind,
      accountId: "acc-ann",
      assignee: "Ann Author",
      teamId: null,
      anchorAt: Date.UTC(2026, 8, 9, 10),
      figure,
    })),
    total: count,
    truncated: false,
  };
}

/** [count] items of one person, each with [figure], the drill's `bar` recomputed over them (the shape every compare-people view answers). */
export function compareItems(count: number, sumMd: number | null = null, figure: number | null = 1): ReportDrillItems {
  return {
    meta: META,
    bar: { count, sumMd, from: null, to: null },
    items: Array.from({ length: count }, (_, index) => ({
      issueKey: `FLO-${index + 1}`,
      summary: `Task ${index + 1}`,
      itemKind: "TASK" as const,
      accountId: "acc-ann",
      assignee: "Ann Author",
      teamId: 1,
      anchorAt: Date.UTC(2026, 8, 9, 10),
      figure,
    })),
    total: count,
    truncated: false,
  };
}
