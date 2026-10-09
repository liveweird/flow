import type { AgingWipReport, DataQualityReport, ReportMeta, SprintConsistencyReport } from "../api/reports";
import type { CardId } from "../components/DataQualityCard";
import { bandTone, type BandTone } from "./agingReport";
import { presetRange, serializeReportFilter, type ReportFilterState } from "./reportFilter";
import { todayIsoDate } from "./isoDate";
import { reportHref } from "./reportLinks";

/**
 * The Home overview's pure logic (plan amendment A9): the unit's dashboard reads the SAME report
 * endpoints as the report pages, at UNIT level, over ONE fixed default period each — four calls, no
 * aggregator. Everything here only reshapes those responses; no figure is recomputed.
 */

/** Velocity, throughput and sprint consistency: each team's own last closed sprint. */
export const LAST_SPRINT_FILTER: ReportFilterState = { lastSprints: 1 };

/** How many finding kinds the data-quality tile lists before pointing at the full report. */
export const HIGHLIGHT_LIMIT = 5;

// ---- velocity vs throughput ---------------------------------------------------------------

interface FrozenFigures {
  committedMd: number;
  finalMd: number;
  deliveredMd: number;
}

export interface OverviewTeamRow {
  /** Stable row key: a team can in principle list two sprints if two connections both close one. */
  key: string;
  team: string;
  sprint: string;
  /** Completion instant; null for an open sprint (`lastSprints` never returns one, so a fallback only). */
  completedAt: number | null;
  /** Initial = the committed scope (velocity's "initial"). */
  initialMd: number;
  finalMd: number;
  deliveredMd: number;
  /** The live figures moved since the sprint closed (D13): the frozen ones are shown as text beside the badge. */
  drift: boolean;
  frozen: FrozenFigures | null;
}

/**
 * One row per (team, last closed sprint), all three figures off the SAME `fact_sprint` row the
 * sprint-consistency report reads (committed = velocity's initial, final, delivered = throughput's
 * sprint view), so one request answers the tile. The team name comes from the UNIT groups (one per
 * team); a team the groups no longer name reads `#id`. Sorted by team name so the chart and the
 * table agree.
 */
export function overviewTeamRows(report: SprintConsistencyReport): OverviewTeamRow[] {
  return report.sprints
    .map((sprint) => ({
      key: `${sprint.teamId}-${sprint.sprintId}`,
      team: report.groups.find((group) => group.teamId === sprint.teamId)?.label ?? `#${sprint.teamId}`,
      sprint: sprint.name,
      completedAt: sprint.completedAt,
      initialMd: sprint.committedMd,
      finalMd: sprint.finalMd,
      deliveredMd: sprint.deliveredMd,
      drift: sprint.drift,
      frozen: sprint.snapshot
        ? { committedMd: sprint.snapshot.committedMd, finalMd: sprint.snapshot.finalMd, deliveredMd: sprint.snapshot.deliveredMd }
        : null,
    }))
    .sort((a, b) => a.team.localeCompare(b.team));
}

// ---- WIP and aging -----------------------------------------------------------------------

export interface PastThreshold {
  percentile: number;
  count: number;
}

export interface AgingSummary {
  /** Tasks in progress now (epics have their own scale and are not counted). */
  wip: number;
  /** Above the second-highest configured threshold (p85 by default) — includes the ones above the top. */
  pastOrange: PastThreshold | null;
  /** Above the highest configured threshold (p95 by default). */
  pastRed: PastThreshold | null;
  /** Fewer finished tasks than the minimum sample: no threshold value, no band. */
  hidden: boolean;
  /** The server lists at most 500 items, oldest first: every figure is then "at least". */
  atLeast: boolean;
}

/**
 * The WIP tile's three numbers, read off the SERVER's bands (never recomputed from the age): the two
 * top configured thresholds keep `agingReport.ts`'s tones — the highest is red, the next orange.
 */
export function agingSummary(report: AgingWipReport): AgingSummary {
  const tasks = report.items.filter((item) => item.itemKind === "TASK");
  const ranked = [...report.thresholds.percentiles].sort((a, b) => a.percentile - b.percentile);
  const countTones = (tones: readonly BandTone[]) =>
    tasks.filter((item) => tones.includes(bandTone(item.band, report.thresholds))).length;
  const top = ranked[ranked.length - 1];
  const next = ranked[ranked.length - 2];
  return {
    wip: tasks.length,
    pastOrange: next === undefined ? null : { percentile: next.percentile, count: countTones(["orange", "red"]) },
    pastRed: top === undefined ? null : { percentile: top.percentile, count: countTones(["red"]) },
    hidden: report.thresholds.hidden,
    atLeast: report.itemsTruncated,
  };
}

// ---- data quality ------------------------------------------------------------------------

export interface QualityHighlight {
  card: CardId;
  count: number;
}

/** Configuration findings: something an admin must fix before the numbers can be trusted — never crowded out by volume. */
const CONFIGURATION_CARDS: ReadonlySet<CardId> = new Set<CardId>([
  "deriveWarnings",
  "unmappedStatuses",
  "unmappedBoards",
  "domainsNoOwner",
]);

/**
 * Every finding kind that found something: the configuration kinds first (a wrong mapping skews
 * everything else), then the volume kinds, each group largest count first (ties keep the report
 * page's order).
 * The counts are the ones the data-quality cards state — a capped list's `total`, not its length —
 * and a work-category finding only counts where a work-category field is configured.
 */
export function qualityHighlights(report: DataQualityReport): QualityHighlight[] {
  const { missing } = report;
  const all: QualityHighlight[] = [
    { card: "coverage", count: report.worklogCoverage.without.total },
    { card: "authors", count: report.authorsWithoutTeam.total },
    { card: "noEstimate", count: missing.noEstimate.total },
    { card: "noEpic", count: missing.noEpic.total },
    { card: "noWorkCategory", count: missing.workCategoryConfigured ? missing.noWorkCategory.total : 0 },
    { card: "unassigned", count: missing.unassigned.total },
    { card: "epicsNoEstimate", count: missing.epicsWithoutEstimate.total },
    { card: "epicsNoDates", count: missing.epicsWithoutDates.total },
    { card: "epicsHorizon", count: missing.epicsOutsidePvHorizon.total },
    { card: "epicDrift", count: report.epicDrift.total },
    { card: "outsideSprint", count: report.outsideSprint.total },
    { card: "crossDomain", count: report.crossDomain.total },
    { card: "snapshotDrift", count: report.snapshotDrift.total },
    { card: "domainsNoOwner", count: report.domainsWithoutOwner.total },
    { card: "unmappedStatuses", count: report.unmappedStatuses.total },
    { card: "unmappedBoards", count: report.unmappedBoards.total },
    { card: "deriveWarnings", count: report.deriveWarnings.length },
  ];
  const configFirst = (entry: QualityHighlight) => (CONFIGURATION_CARDS.has(entry.card) ? 0 : 1);
  return all.filter((entry) => entry.count > 0).sort((a, b) => configFirst(a) - configFirst(b) || b.count - a.count);
}

// ---- state and links ---------------------------------------------------------------------

/**
 * True once every request has settled without a failure, at least one answered, and every answer says
 * nothing has been derived yet — the page then shows its empty state instead of a grid of empty tiles.
 * A failure keeps the grid: the empty state must never hide a tile's own error.
 */
export function nothingDerived(metas: ReadonlyArray<ReportMeta | undefined>, anyPending: boolean, anyFailed: boolean): boolean {
  if (anyPending || anyFailed) return false;
  const answered = metas.filter((meta): meta is ReportMeta => meta !== undefined);
  return answered.length > 0 && answered.every((meta) => meta.derivedAt === null);
}

/**
 * The period a tile's link carries — never none, since a bare report URL would also start on the
 * remembered team (the overview is unit-wide). It is the period the server's default resolved to
 * (its trailing 90 days in the configured zone), read off a from/to report's `meta`; while none has
 * answered (or both failed) it is the same trailing 90 days computed locally — in the configured
 * `timeZone` once the reference data loaded, in UTC dates until then (or if it failed).
 */
export function overviewPeriod(
  timeZone: string | undefined,
  ...metas: ReadonlyArray<ReportMeta | undefined>
): { from: string; to: string } {
  for (const meta of metas) {
    if (meta?.from != null && meta.to != null) return { from: meta.from, to: meta.to };
  }
  return presetRange("last90", todayIsoDate(timeZone));
}

/** A report route carrying the given filter — via `reportHref`, so report-specific params stay dropped. */
export function overviewLink(path: string, filter: ReportFilterState): string {
  return reportHref(path, serializeReportFilter(filter).toString());
}
