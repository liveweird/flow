import type { HomeDrillView, HomeFigure, HomeOverview, HomePrevious, HomeSprint, HomeTeam, HomeWip } from "../api/home";
import type { DrillAnchor, DrillTarget, DrillUnit, SprintBucket } from "./reportDrill";
import { serializeReportFilter, type ReportFilterState } from "./reportFilter";
import { DAY_MS } from "./isoDate";
import { reportHref } from "./reportLinks";

/**
 * The Home overview's pure logic: ONE request (`GET /reports/home`) answers the page, so everything here only reshapes that
 * response into the rows the charts and their hidden tables share. No figure is recomputed and no verdict is judged here — the
 * server already compared each figure with the previous window by the figure's own direction.
 */

// ---- state and links ---------------------------------------------------------------------

/** The sprint section's period: each team's own last closed sprint (Commitments, Throughput and Sprint consistency all take it). */
export const LAST_SPRINT_FILTER: ReportFilterState = { lastSprints: 1 };

/** Nothing derived yet: the server answered an empty overview (the page then shows its empty state instead of empty sections). */
export function nothingDerived(overview: HomeOverview): boolean {
  return overview.meta.derivedAt === null;
}

/** The window the figures describe — also the period every link of the page carries (a bare report URL would start on the remembered team). */
export function windowFilter(overview: HomeOverview): ReportFilterState {
  return { from: overview.window.from, to: overview.window.to };
}

/** A report route carrying the given filter (and page-local params, e.g. aging WIP's `kind`) — via `reportHref`, so report-specific params stay dropped. */
export function overviewLink(path: string, filter: ReportFilterState, extra: Record<string, string> = {}): string {
  const params = serializeReportFilter(filter);
  for (const [key, value] of Object.entries(extra)) params.set(key, value);
  return reportHref(path, params.toString());
}

// ---- the trend ---------------------------------------------------------------------------

/** How the current figure reads against the previous window: the server's verdict (better, worse), a tie within the tolerance, or no comparison possible. */
export type TrendTone = "better" | "worse" | "same" | "none";

export interface Trend {
  tone: TrendTone;
  /** The direction of the VALUE (not of the verdict): up, down or unchanged; `null` when either side is missing. */
  direction: "up" | "down" | "flat" | null;
}

function directionOf(difference: number | null): Trend["direction"] {
  if (difference === null) return null;
  if (difference > 0) return "up";
  return difference < 0 ? "down" : "flat";
}

/** The length of the overview's window in days, inclusive (both ends are days of the configured zone): "the previous N days" in the copy. */
export function windowDays(window: { from: string; to: string }): number {
  const day = (iso: string) => Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)));
  return Math.round((day(window.to) - day(window.from)) / DAY_MS) + 1;
}

/** Whether a drill view lists epics (the copy then counts "epics", not "tasks"). */
export function isEpicView(view: HomeDrillView): boolean {
  return view.startsWith("EPIC_") || view === "WIP_EPICS" || view.startsWith("QUALITY_EPICS");
}

/** Whether the Code section is drawn at all: only while a GitLab source is active (the server sends no `code` figure without one). */
export function hasCodeSection(overview: HomeOverview): boolean {
  return overview.code.sources.length > 0;
}

/** The height of a small multiple's chart: a fixed chrome and a band per team, so the page does not jump when a chart mounts. */
export function teamBarsHeight(teams: number): number {
  return Math.max(110, 56 + teams * 30);
}

/** The trend of a figure; `null` for a figure that has none (the server sends no `previous`). */
export function trendOf(previous: HomePrevious | null): Trend | null {
  if (previous === null) return null;
  const { verdict, difference } = previous;
  let tone: TrendTone = "none";
  if (verdict === "BETTER") tone = "better";
  else if (verdict === "WORSE") tone = "worse";
  else if (difference !== null) tone = "same";
  return { tone, direction: directionOf(difference) };
}

// ---- figure rows (the small multiples and their tables) ---------------------------------------

/** One team's bar of one chart, with what its hidden table says about it. */
export interface HomeFigureRow {
  key: string;
  teamId: number;
  team: string;
  /** The first column of the row's table: the team, or a PRED line ("Tasks ±25%"). */
  label: string;
  view: HomeDrillView;
  /** The figure; `null` while hidden (under the minimum sample) or undefined. */
  value: number | null;
  /** The sample behind the figure. */
  n: number;
  hidden: boolean;
  /** The items the drill lists (a PRED's items within the share, else the sample): 0 = nothing to list. */
  count: number;
  previous: HomePrevious | null;
  trend: Trend | null;
}

/** The row of a team's `HomeFigure` (a median, a PRED share, a total, a count). */
export function figureRow(team: HomeTeam, label: string, view: HomeDrillView, figure: HomeFigure): HomeFigureRow {
  return {
    key: `${view}-${team.teamId}-${label}`,
    teamId: team.teamId,
    team: team.name,
    label,
    view,
    value: figure.value,
    n: figure.n,
    hidden: figure.hidden,
    count: figure.numerator ?? figure.n,
    previous: figure.previous,
    trend: trendOf(figure.previous),
  };
}

/** One row per team for a chart that shows one figure of each. */
export function teamFigureRows(teams: readonly HomeTeam[], view: HomeDrillView, pick: (team: HomeTeam) => HomeFigure): HomeFigureRow[] {
  return teams.map((team) => figureRow(team, team.name, view, pick(team)));
}

/** One row per team for a WIP chart: the count of items in progress now, or one age percentile (hidden under the minimum sample). */
export function wipRows(
  teams: readonly HomeTeam[],
  view: Extract<HomeDrillView, "WIP_TASKS" | "WIP_EPICS">,
  wipOf: (team: HomeTeam) => HomeWip,
  pick: (wip: HomeWip) => number | null,
): HomeFigureRow[] {
  return teams.map((team) => {
    const wip = wipOf(team);
    const value = pick(wip);
    return {
      key: `${view}-${team.teamId}`,
      teamId: team.teamId,
      team: team.name,
      label: team.name,
      view,
      value,
      n: wip.count,
      hidden: value === null && wip.count > 0,
      count: wip.count,
      previous: null,
      trend: null,
    };
  });
}

// ---- the sprint rows ----------------------------------------------------------------------

interface FrozenFigures {
  committedMd: number;
  finalMd: number;
  deliveredMd: number;
}

export interface HomeSprintRow {
  /** Stable row key and the row's identity in the drill (`<teamId>:<sprintId>`, as every sprint chart row). */
  id: string;
  teamId: number;
  sprintId: number;
  team: string;
  sprint: string;
  completedAt: number;
  capacityMd: number | null;
  /** Initial = the committed scope (the commitments report's "initial"). */
  initialMd: number;
  finalMd: number;
  deliveredMd: number;
  /** The items behind the three bars — what a drill of the bar is compared with. */
  initialItems: number;
  finalItems: number;
  deliveredItems: number;
  /** The live figures moved since the sprint closed (D13): the frozen ones are shown as text beside the badge. */
  drift: boolean;
  frozen: FrozenFigures | null;
}

/** One row per team that has a closed sprint (the teams without one are named in a note), in the overview's team order. */
export function sprintRows(teams: readonly HomeTeam[]): HomeSprintRow[] {
  return teams.flatMap((team) => {
    const sprint = team.lastSprint;
    if (sprint === null) return [];
    return [
      {
        id: `${team.teamId}:${sprint.sprintId}`,
        teamId: team.teamId,
        sprintId: sprint.sprintId,
        team: team.name,
        sprint: sprint.name,
        completedAt: sprint.completedAt,
        capacityMd: sprint.capacityMd,
        initialMd: sprint.initialMd,
        finalMd: sprint.finalMd,
        deliveredMd: sprint.deliveredMd,
        initialItems: sprint.initialItems,
        finalItems: sprint.finalItems,
        deliveredItems: sprint.deliveredItems,
        drift: sprint.drift,
        frozen: sprint.snapshot
          ? { committedMd: sprint.snapshot.committedMd, finalMd: sprint.snapshot.finalMd, deliveredMd: sprint.snapshot.deliveredMd }
          : null,
      },
    ];
  });
}

// ---- the drill ----------------------------------------------------------------------------

/** The items behind a sprint bar of the overview — what a drill of the bar is compared with; `undefined` for a bucket Home draws no bar for. */
export function sprintBucketItems(
  sprint: Pick<HomeSprint, "initialItems" | "finalItems" | "deliveredItems">,
  bucket: SprintBucket,
): number | undefined {
  if (bucket === "COMMITTED") return sprint.initialItems;
  if (bucket === "FINAL") return sprint.finalItems;
  return bucket === "DELIVERED" ? sprint.deliveredItems : undefined;
}

/** Opens a drill from a Home chart: `trigger` is the pressed table figure (focus returns to it), `anchor` the section card a pointer double-click returns to. */
export type OpenDrill = (target: DrillTarget, from?: { trigger?: HTMLElement | null; anchor?: string }) => void;

/** How a view's list is drawn (the figure column's unit, the date column) and how many items the overview's own figure says are behind it. */
interface ViewSpec {
  unit: DrillUnit;
  anchor: DrillAnchor;
  count: (team: HomeTeam) => number;
}

const countOf = (figure: HomeFigure) => figure.numerator ?? figure.n;

/** The drill list of every view: the unit and date of its columns, and the figure of the overview the list is compared with. */
export const HOME_VIEWS: Record<HomeDrillView, ViewSpec> = {
  TASK_ESTIMATE: { unit: "estimateMd", anchor: "done", count: (team) => countOf(team.granularity.estimateMd) },
  TASK_CYCLE: { unit: "wd", anchor: "done", count: (team) => countOf(team.granularity.cycleWorkingDays) },
  TASK_COST: { unit: "actualMd", anchor: "done", count: (team) => countOf(team.granularity.costMd) },
  TASK_PRED10: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.tasks.pred10) },
  TASK_PRED25: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.tasks.pred25) },
  TASK_PRED50: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.tasks.pred50) },
  EPIC_PRED10: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.epics.pred10) },
  EPIC_PRED25: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.epics.pred25) },
  EPIC_PRED50: { unit: "accuracy", anchor: "done", count: (team) => countOf(team.accuracy.epics.pred50) },
  WIP_TASKS: { unit: "ageWd", anchor: "started", count: (team) => team.wipTasks.count },
  WIP_EPICS: { unit: "ageWd", anchor: "started", count: (team) => team.wipEpics.count },
  QUALITY_TASKS_NO_ESTIMATE: { unit: "estimateMd", anchor: "done", count: (team) => team.quality.tasksNoEstimate.n },
  QUALITY_TASKS_UNASSIGNED: { unit: "estimateMd", anchor: "done", count: (team) => team.quality.tasksUnassigned.n },
  QUALITY_TASKS_ESTIMATED_LATE: { unit: "estimateMd", anchor: "done", count: (team) => team.quality.tasksEstimatedLate.n },
  QUALITY_EPICS_NO_ESTIMATE: { unit: "estimateMd", anchor: "done", count: (team) => team.quality.epicsNoEstimate.n },
  QUALITY_EPICS_NO_DATES: { unit: "estimateMd", anchor: "done", count: (team) => team.quality.epicsNoDates.n },
  QUALITY_EPICS_ESTIMATE_GAP: { unit: "gapMd", anchor: "done", count: (team) => team.quality.epicsEstimateGap.n },
  // The code views list merge requests (`GitLabKeyLink`s): the merged ones with their cycle, the waiting open ones with their age.
  CODE_MERGED: { unit: "wd", anchor: "merged", count: (team) => team.code?.merged.n ?? 0 },
  CODE_OPEN: { unit: "ageWd", anchor: "created", count: (team) => team.code?.openCount.n ?? 0 },
  CODE_MERGED_WITHOUT_REVIEW: { unit: "wd", anchor: "merged", count: (team) => team.code?.mergedWithoutReview.numerator ?? 0 },
};

/** Whether a view lists merge requests of the GitLab code layer (the drawer then links their keys to GitLab). */
export function isCodeView(view: HomeDrillView): boolean {
  return view.startsWith("CODE_");
}

/** The section a view belongs to — the card focus returns to after a pointer double-click, and a deep link's default. */
export type HomeSection = "sprints" | "granularity" | "accuracy" | "wipEpics" | "wipTasks" | "throughput" | "code" | "quality";

const SECTION_OF_VIEW: Record<HomeDrillView, HomeSection> = {
  TASK_ESTIMATE: "granularity",
  TASK_CYCLE: "granularity",
  TASK_COST: "granularity",
  TASK_PRED10: "accuracy",
  TASK_PRED25: "accuracy",
  TASK_PRED50: "accuracy",
  EPIC_PRED10: "accuracy",
  EPIC_PRED25: "accuracy",
  EPIC_PRED50: "accuracy",
  WIP_TASKS: "wipTasks",
  WIP_EPICS: "wipEpics",
  QUALITY_TASKS_NO_ESTIMATE: "quality",
  QUALITY_TASKS_UNASSIGNED: "quality",
  QUALITY_TASKS_ESTIMATED_LATE: "quality",
  QUALITY_EPICS_NO_ESTIMATE: "quality",
  QUALITY_EPICS_NO_DATES: "quality",
  QUALITY_EPICS_ESTIMATE_GAP: "quality",
  CODE_MERGED: "code",
  CODE_OPEN: "code",
  CODE_MERGED_WITHOUT_REVIEW: "code",
};

/** The `data-drill-anchor` of a section's card. */
export function sectionAnchor(section: HomeSection): string {
  return `home-${section}`;
}

/** The card a drill of [target] returns focus to when no section named itself (a deep link): its own section. */
export function drillAnchorOf(target: DrillTarget): string {
  if (target.kind === "sprint") return sectionAnchor("sprints");
  if (target.kind === "week") return sectionAnchor("throughput");
  if (target.kind === "home") return sectionAnchor(SECTION_OF_VIEW[target.view]);
  return sectionAnchor("sprints");
}

/**
 * Whether the overview drew the bar a target names: a team it lists (and, for a sprint, that team's last closed sprint; for a week,
 * a week of the window). A hand-edited or stale link names a bar that does not exist — it is closed, never a drawer that answers a 400.
 */
export function drillDrawn(target: DrillTarget, overview: HomeOverview): boolean {
  // A code view of a team the overview drew no Code figures for (no GitLab source, over the row budget) is a stale link too.
  if (target.kind === "home") {
    return overview.teams.some((team) => team.teamId === target.teamId && (!isCodeView(target.view) || team.code !== null));
  }
  const team = "teamId" in target ? overview.teams.find((candidate) => candidate.teamId === target.teamId) : undefined;
  if (target.kind === "sprint") return team?.lastSprint?.sprintId === target.sprintId;
  if (target.kind === "week") return team?.throughput.weeks.some((week) => week.bucketStart === target.bucketStart) ?? false;
  return false;
}
