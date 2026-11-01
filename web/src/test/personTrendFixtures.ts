// Person trend (report 20) test fixtures: a response typed against the generated schema, so a spec drift breaks the fixtures and not just the
// page. Ann (Alpha) over FIVE windows, a point every 14 days: the first ends before the derived data starts (NONE), the second starts before it
// (PARTIAL), the other three are FULL. The figures are hand-written to show every kind of cue (better, worse, a tie, none), a hidden figure
// (`minSampleSize` 5), a count of 0 in the NONE window (which must be a gap, never a zero) and a partial point.
import type { PersonTrendPoint, PersonTrendReport, PersonTrendRow } from "../api/reports";

const ENDS = ["2026-06-30", "2026-07-14", "2026-07-28", "2026-08-11", "2026-08-25"] as const;
const FROM = ["2026-04-02", "2026-04-16", "2026-04-30", "2026-05-14", "2026-05-28"] as const;
const COVERAGE = ["NONE", "PARTIAL", "FULL", "FULL", "FULL"] as const;

/** The value for each window (`undefined` = absent), the sample sizes and the windows whose figure is withheld. */
type Series = { values: Array<number | undefined>; n?: number[]; hidden?: number[] };

const points = ({ values, n = [0, 8, 9, 9, 10], hidden = [] }: Series): PersonTrendPoint[] =>
  ENDS.map((end, i) => ({ end, ...(hidden.includes(i) || values[i] === undefined ? {} : { value: values[i] }), n: n[i], hidden: hidden.includes(i) }));

type Spec = Pick<PersonTrendRow, "key" | "group" | "unit" | "direction" | "tolerance" | "drill">;

function row(spec: Spec, series: Series, trend?: PersonTrendRow["trend"]): PersonTrendRow {
  return { ...spec, points: points(series), ...(trend === undefined ? {} : { trend }) };
}

/** The cue between two window ends (indexes into `ENDS`). */
const cue = (from: number, to: number, verdict?: "BETTER" | "WORSE", difference?: number): PersonTrendRow["trend"] => ({
  fromEnd: ENDS[from],
  toEnd: ENDS[to],
  ...(verdict === undefined ? {} : { verdict }),
  ...(difference === undefined ? {} : { difference }),
});

export const PERSON_TREND_ROWS: PersonTrendRow[] = [
  row(
    { key: "cycleWorkingDaysP50", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.5, drill: "CYCLE_WORKING_DAYS" },
    { values: [undefined, 6.5, 5.5, 5, 4] },
    cue(2, 4, "BETTER", -1.5),
  ),
  row(
    { key: "cycleWorkingDaysP90", group: "CYCLE", unit: "DAYS", direction: "LOWER", tolerance: 0.5, drill: "CYCLE_WORKING_DAYS" },
    { values: [undefined, 11, 10, 9, undefined], hidden: [4], n: [0, 8, 9, 9, 3] },
    cue(2, 3, "BETTER", -1),
  ),
  row(
    { key: "flowEfficiencyP50", group: "CYCLE", unit: "SHARE", direction: "HIGHER", tolerance: 0.02, drill: "FLOW_EFFICIENCY" },
    { values: [undefined, 0.3, 0.32, 0.33, 0.333] },
    cue(2, 4, undefined, 0.013),
  ),
  row(
    { key: "deliveredItems", group: "DELIVERY", unit: "ITEMS", direction: "HIGHER", tolerance: 0, drill: "DELIVERED" },
    { values: [0, 4, 6, 7, 9], n: [0, 4, 6, 7, 9] },
    cue(2, 4, "BETTER", 3),
  ),
  row({ key: "deliveredMd", group: "DELIVERY", unit: "MD", direction: "HIGHER", tolerance: 0, drill: "DELIVERED" }, { values: [0, 7.5, 9, 10.5, 12] }, cue(2, 4, "BETTER", 3)),
  row(
    { key: "accuracyAtStartPred25", group: "ESTIMATION", unit: "SHARE", direction: "HIGHER", tolerance: 0.05, drill: "ACCURACY_AT_START" },
    { values: [undefined, 0.5, 0.55, 0.5, 0.45] },
    cue(2, 4, "WORSE", -0.1),
  ),
  row({ key: "avgWip", group: "WIP", unit: "ITEMS", direction: "LOWER", tolerance: 0.2, drill: "WIP_TASK_DAYS" }, { values: [undefined, 3.2, 3, 2.8, 2.5] }, cue(2, 4, "BETTER", -0.5)),
  row(
    { key: "wipAtEndMedianAgeWd", group: "WIP", unit: "DAYS", direction: "LOWER", tolerance: 1, drill: "WIP_AT_END" },
    { values: [undefined, 8, 7, 7, 9] },
    cue(2, 4, "WORSE", 2),
  ),
  // No verdict although the share moved by far more than the tolerance: a side is under the minimum sample (the server will not call it).
  row({ key: "blockedItemsShare", group: "QUALITY", unit: "SHARE", direction: "LOWER", tolerance: 0.02, drill: "BLOCKED" }, { values: [undefined, 0.2, 0.15, 0.1, 0.1] }, cue(2, 4, undefined, -0.05)),
  row({ key: "worklogCoverage", group: "TIME", unit: "SHARE", direction: "HIGHER", tolerance: 0.02, drill: "NO_WORKLOGS" }, { values: [undefined, 0.8, 0.85, 0.9, 0.95] }, cue(2, 4, "BETTER", 0.1)),
  row({ key: "lateLoggingShare", group: "TIME", unit: "SHARE", direction: "LOWER", tolerance: 0.02, drill: "LATE" }, { values: [undefined, 0.3, 0.25, 0.25, 0.25] }, cue(2, 4, undefined, 0)),
  row({ key: "noEstimateShare", group: "QUALITY", unit: "SHARE", direction: "LOWER", tolerance: 0.02, drill: "NO_ESTIMATE" }, { values: [undefined, 0.1, 0.1, 0.05, 0.05] }, cue(2, 4, "BETTER", -0.05)),
  row({ key: "reopenedShare", group: "DELIVERY", unit: "SHARE", direction: "LOWER", tolerance: 0.02, drill: "REOPENED" }, { values: [undefined, 0.05, 0.05, 0.1, 0.12] }, cue(2, 4, "WORSE", 0.07)),
  // No cue: the person had a task size in one full window only.
  row({ key: "taskSizeP50", group: "DELIVERY", unit: "MD", direction: "LOWER", tolerance: 0.25, drill: "TASK_SIZE" }, { values: [undefined, undefined, 2, undefined, undefined], hidden: [3, 4], n: [0, 0, 7, 2, 1] }),
  // The CODE rows (A50): the merged merge requests are NEUTRAL context — the cue is a change, never a verdict —, the cycle p50 is LOWER, the reviews NEUTRAL.
  row({ key: "mrsMerged", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "MRS_MERGED" }, { values: [0, 3, 4, 6, 8], n: [0, 3, 4, 6, 8] }, cue(2, 4, undefined, 4)),
  row({ key: "mrCycleWorkingDaysP50", group: "CODE", unit: "DAYS", direction: "LOWER", tolerance: 0.05, drill: "MR_CYCLE_WORKING_DAYS" }, { values: [undefined, 4, 3.5, 3, 2], n: [0, 3, 4, 6, 8] }, cue(2, 4, "BETTER", -1.5)),
  row({ key: "reviewsGiven", group: "CODE", unit: "ITEMS", direction: "NEUTRAL", tolerance: 0, drill: "REVIEWS_GIVEN" }, { values: [0, 2, 2, 5, 9], n: [0, 2, 2, 5, 9] }, cue(2, 4, undefined, 7)),
];

const mixPoints = (md: number[], shares: Array<number | undefined>) => ENDS.map((end, i) => ({ end, md: md[i], ...(shares[i] === undefined ? {} : { share: shares[i] }) }));

export const PERSON_TREND: PersonTrendReport = {
  meta: {
    derivedAt: 1_780_000_000_000,
    configRevision: 4,
    from: "2026-06-30",
    to: "2026-08-25",
    level: "USER",
    domainView: "TASK",
    resolvedSprints: [],
    minSampleSize: 5,
    historyHorizon: null,
  },
  person: { accountId: "acc-ann", label: "Ann Author" },
  windowDays: 90,
  stepDays: 14,
  windows: ENDS.map((end, i) => ({ end, from: FROM[i], coverage: COVERAGE[i] })),
  rows: PERSON_TREND_ROWS,
  workMix: {
    activityType: [
      { key: "Story", points: mixPoints([0, 5, 6, 7, 8], [undefined, 0.5, 0.6, 0.7, 0.8]) },
      { key: "Bug", points: mixPoints([0, 5, 4, 3, 2], [undefined, 0.5, 0.4, 0.3, 0.2]) },
    ],
    workCategory: [
      { key: "Feature", points: mixPoints([0, 6, 6, 6, 6], [undefined, 0.6, 0.6, 0.6, 0.6]) },
      { key: "UNCATEGORIZED", points: mixPoints([0, 4, 4, 4, 4], [undefined, 0.4, 0.4, 0.4, 0.4]) },
    ],
  },
};

/** The same person with nothing derived at all: every window NONE, every figure the empty-span zero, no cue, no mix. */
export const PERSON_TREND_EMPTY: PersonTrendReport = {
  ...PERSON_TREND,
  note: "Nothing has been derived for the selected connections yet.",
  windows: PERSON_TREND.windows.map((window) => ({ ...window, coverage: "NONE" })),
  rows: PERSON_TREND_ROWS.map(({ trend: _trend, ...rest }) => ({ ...rest, points: rest.points.map((point) => ({ end: point.end, value: 0, n: 0, hidden: false })) })),
  workMix: { activityType: [], workCategory: [] },
};

/**
 * Ann with a team at every window - a MOVER: Alpha for the first three windows, Beta for the last two. The team median per window: the NONE window carries
 * one anyway (the client must drop it), then 4 peers, 5 peers, TOO FEW peers (2, no value), 3 peers. Every row's reference is its own figure plus a
 * fixed offset, so the figures are easy to read off.
 */
export const PERSON_TREND_TEAM: PersonTrendReport = {
  ...PERSON_TREND,
  windows: PERSON_TREND.windows.map((window, i) => ({ ...window, team: i < 3 ? { id: 1, name: "Alpha" } : { id: 2, name: "Beta" } })),
  rows: PERSON_TREND_ROWS.map((row) => ({
    ...row,
    points: row.points.map((point, i) => {
      const base = point.value ?? 1;
      const reference = [{ value: base + 1, peers: 4 }, { value: base + 1, peers: 4 }, { value: base + 1, peers: 5 }, { peers: 2 }, { value: base + 1, peers: 3 }][i];
      return { ...point, reference };
    }),
  })),
};
