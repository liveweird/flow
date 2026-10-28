import type { TFunction } from "i18next";
import type { PersonTrendMixSeries, PersonTrendReport, PersonTrendRow } from "../api/reports";
import { drillCount, rowFormat } from "./comparePeopleReport";
import type { Trend } from "./homeOverview";
import { dropReportSpecific, type ReportControls, type ReportFilterState } from "./reportFilter";
import { personTrendPath, reportSpecificKeep } from "./reportLinks";

/**
 * Person trend (report 20): ONE person's Compare-people figures over rolling 90-day windows, across every team. The bar offers the date period
 * (a year to start with, `defaultPeriod`, written into the URL) and the slices the person is read through — no team, member or sprint period
 * (the person is picked beside the bar, `PersonTrendPicker`). A task is a person's by its assignee at done, so the domain view starts on TASK.
 */
export const PERSON_TREND_CONTROLS: ReportControls = {
  peopleOnly: true,
  peopleCount: 1,
  defaultPeriod: "last365",
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
};

/**
 * Person trend's whole filter, made answerable: no team, single `accountId` or sprint-relative period (it reads one person across every team over a
 * date range), none of another report's keys, and only the first of any more people — the URL is rewritten to say so (a `normalize` step for
 * `useReportPage`, a module-level function so its identity is stable).
 */
export function normalizePersonTrendFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(personTrendPath));
  delete next.teamId;
  delete next.accountId;
  delete next.lastSprints;
  delete next.sprintId;
  if (next.accountIds !== undefined) next.accountIds = next.accountIds.slice(0, 1);
  return next;
}

type TrendKey = PersonTrendRow["key"];

/**
 * The page's charts, in the server's row order: one chart per row, except the two cycle-time percentiles, which share ONE chart (the median
 * solid, the p90 dashed — the cycle-time report's own trend). `id` is the first row's key (the React key).
 */
export const TREND_CHARTS: ReadonlyArray<{ id: TrendKey; keys: readonly [TrendKey] | readonly [TrendKey, TrendKey] }> = [
  { id: "cycleWorkingDaysP50", keys: ["cycleWorkingDaysP50", "cycleWorkingDaysP90"] },
  { id: "flowEfficiencyP50", keys: ["flowEfficiencyP50"] },
  { id: "deliveredItems", keys: ["deliveredItems"] },
  { id: "deliveredMd", keys: ["deliveredMd"] },
  { id: "accuracyAtStartPred25", keys: ["accuracyAtStartPred25"] },
  { id: "avgWip", keys: ["avgWip"] },
  { id: "wipAtEndMedianAgeWd", keys: ["wipAtEndMedianAgeWd"] },
  { id: "blockedItemsShare", keys: ["blockedItemsShare"] },
  { id: "worklogCoverage", keys: ["worklogCoverage"] },
  { id: "lateLoggingShare", keys: ["lateLoggingShare"] },
  { id: "noEstimateShare", keys: ["noEstimateShare"] },
  { id: "reopenedShare", keys: ["reopenedShare"] },
  { id: "taskSizeP50", keys: ["taskSizeP50"] },
];

/** The focus-return anchor of a chart card (`useReportDrill`, `ReportChartCard`'s `drillAnchor`). */
export function trendAnchor(chartId: string): string {
  return `trend-${chartId}`;
}

/** The anchor of the card that draws a row (the cycle-time card holds two rows); an unknown row falls back to its own key. */
export function trendAnchorOfRow(rowKey: string): string {
  return trendAnchor(TREND_CHARTS.find((chart) => (chart.keys as readonly string[]).includes(rowKey))?.id ?? rowKey);
}

/** The views whose figure's sample `n` IS the number of items the drill lists (a share lists its numerator, the coverage its gap, WIP its tasks). */
const SAMPLE_VIEWS: ReadonlySet<string> = new Set(["DELIVERED", "CYCLE_WORKING_DAYS", "FLOW_EFFICIENCY", "ACCURACY_AT_START", "TASK_SIZE"]);

/**
 * The item count the chart's point stands behind for the row's view — what the drawer's own count is compared with — or `undefined` where the
 * point's `n` is not a count of the listed items (the drawer then has nothing to reconcile).
 */
export function pointCountOf(row: Pick<PersonTrendRow, "drill">, n: number): number | undefined {
  return row.drill !== undefined && SAMPLE_VIEWS.has(row.drill) ? n : undefined;
}

/** How many items a point's figure stands for (a table figure is a button to them when above zero): Compare people's rule, on the chart point (a gap has none). */
export function pointDrillCount(row: Pick<PersonTrendRow, "drill">, point: TrendChartPoint): number {
  return drillCount(row, point);
}

/** One point of a chart: the window's end, the figure (null = a GAP — hidden, undefined or a window with nothing derived), and what the tooltip and table say about it. */
export interface TrendChartPoint {
  /** The window's last day, `YYYY-MM-DD`: the chart's x label. */
  label: string;
  value: number | null;
  n: number;
  /** The figure is withheld under the minimum sample. */
  hidden: boolean;
  coverage: PersonTrendReport["windows"][number]["coverage"];
  /** The team the point is read against (the one on the window's last day), absent when no membership covers it. */
  team?: { id: number; name: string };
  /** The team's median at this point: `value` null = too few peers (a GAP in the reference line); absent = no team, or a window with nothing derived. */
  reference?: { value: number | null; peers: number };
}

/** A window that only partly lies in the derived span: its point is drawn hollow and says "partial". */
export function isPartial(point: Pick<TrendChartPoint, "coverage">): boolean {
  return point.coverage === "PARTIAL";
}

/**
 * The row's points, one per window: a window with NOTHING derived (`NONE`) is a gap whatever the server's figures say (they are Compare
 * people's over an empty span — typically a zero, which would draw a lie); a hidden or undefined cell is a gap too, never a zero.
 */
export function chartPointsOf(report: PersonTrendReport, row: PersonTrendRow): TrendChartPoint[] {
  return row.points.map((point, index) => {
    const window = report.windows[index];
    const coverage = window?.coverage ?? "FULL";
    // A window with nothing derived has no peers' figures to speak of either: its reference is dropped like the person's own (the team stays: it names the window).
    const reference = coverage === "NONE" || point.reference === undefined ? undefined : { value: point.reference.value ?? null, peers: point.reference.peers };
    return {
      label: point.end,
      value: coverage === "NONE" || point.hidden ? null : (point.value ?? null),
      n: point.n,
      hidden: point.hidden,
      coverage,
      ...(window?.team === undefined ? {} : { team: window.team }),
      ...(reference === undefined ? {} : { reference }),
    };
  });
}

/** True when ANY point has a team median to draw — otherwise there is no second line and no legend entry (no team, or never enough peers). */
export function hasReference(points: readonly Pick<TrendChartPoint, "reference">[]): boolean {
  return points.some((point) => point.reference?.value != null);
}

/** True when ANY row of the report has a team median to draw (the page's hint says what the dotted line is only then). */
export function reportHasReference(report: PersonTrendReport): boolean {
  return report.rows.some((row) => hasReference(chartPointsOf(report, row)));
}

/** The point's team median in words for the tooltip ("Team median 4.2 (Platform, 5 peers)", or why there is none), `undefined` where the point has no team or reference. */
export function referenceNote(point: TrendChartPoint, format: (value: number) => string, t: TFunction): string | undefined {
  if (point.reference === undefined || point.team === undefined) return undefined;
  const { value, peers } = point.reference;
  const team = point.team.name;
  return value === null
    ? t("reports.personTrend.reference.tooShort", { count: peers, team })
    : t("reports.personTrend.reference.note", { value: format(value), count: peers, team });
}

/** True when ANY point has a figure — otherwise the chart would be an empty frame. */
export function hasFigures(points: readonly TrendChartPoint[]): boolean {
  return points.some((point) => point.value !== null);
}

/** The row's figure as the table prints it (its unit's formatter), a dash for a gap. */
export function figureText(row: PersonTrendRow, point: TrendChartPoint): string {
  return point.value === null ? "—" : rowFormat(row)(point.value);
}

/** What the tooltip says about a point beyond its figure, or `undefined` for a plain full-window point. */
export function pointNote(point: TrendChartPoint, minSampleSize: number, t: TFunction): string | undefined {
  if (point.coverage === "NONE") return t("reports.personTrend.coverage.NONE");
  const notes: string[] = [];
  if (point.hidden) notes.push(t("reports.personTrend.belowMin", { min: minSampleSize }));
  else if (point.value === null) notes.push(t("reports.personTrend.noFigure"));
  if (isPartial(point)) notes.push(t("reports.personTrend.coverage.PARTIAL"));
  return notes.length === 0 ? undefined : notes.join(", ");
}

/** The tooltip's heading of a point: its window end, the sample and any note — a window with nothing derived has no sample to name. */
export function pointTitle(point: TrendChartPoint, minSampleSize: number, t: TFunction): string {
  const note = pointNote(point, minSampleSize, t);
  if (point.coverage === "NONE") return `${point.label} · ${note}`;
  return `${point.label} · ${t("reports.comparePeople.n", { n: point.n })}${note === undefined ? "" : `, ${note}`}`;
}

function directionOf(difference: number | undefined): Trend["direction"] {
  if (difference === undefined) return null;
  if (difference > 0) return "up";
  return difference < 0 ? "down" : "flat";
}

/**
 * The row's cue as the shared trend mark draws it: the server's verdict (better, worse), a tie, or no call. The arrow follows the VALUE
 * (`difference` is the last figure minus the first), the colour the verdict. Without a verdict the server saw EITHER a tie within the row's
 * `tolerance` ("same") OR a side under the minimum sample, where the figures moved but the server will not judge them (tone "none": a gray
 * arrow and "Too few items to call", never "No change" for a change of 14 MD).
 */
export function trendOfCue(cue: PersonTrendRow["trend"], tolerance: number): Trend | null {
  if (cue === undefined) return null;
  const direction = directionOf(cue.difference);
  if (cue.verdict === "BETTER") return { tone: "better", direction };
  if (cue.verdict === "WORSE") return { tone: "worse", direction };
  const tie = cue.difference !== undefined && Math.abs(cue.difference) <= tolerance;
  return { tone: tie ? "same" : "none", direction };
}

/** The Mix chart's series: `s0`, `s1`… (a key may hold characters recharts reads as a path), each with its per-window share, a gap where nothing was delivered or derived. */
export function mixRowsOf(
  series: readonly PersonTrendMixSeries[],
  windows: PersonTrendReport["windows"],
): Array<Record<string, string | number | null>> {
  return windows.map((window, index) => {
    const row: Record<string, string | number | null> = { label: window.end };
    series.forEach((item, seriesIndex) => {
      row[`s${seriesIndex}`] = window.coverage === "NONE" ? null : (item.points[index]?.share ?? null);
    });
    return row;
  });
}
