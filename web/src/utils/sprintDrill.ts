import type { CommitmentsSprint, ReportFilters, SprintConsistencySprint, ThroughputSprint } from "../api/reports";
import type { ChartBarHit } from "./chartDrill";
import type { DrillTarget, SprintBucket } from "./reportDrill";
import type { ReportFilterState } from "./reportFilter";
import { labelledSprints, type SprintIdentity } from "./reportSprints";

type SprintTarget = Extract<DrillTarget, { kind: "sprint" }>;

/** The sprint a chart row names: its team AND id (a bare sprint id is shared by every connection to one Jira site). */
interface RowSprint {
  teamId: number;
  sprintId: number;
}

/**
 * The sprint-scope drill's page-side helpers, shared by every page with a sprint bar (Commitments, Throughput's sprint chart,
 * the Home chart): a chart row's `id` is `<teamId>:<sprintId>` (`labelledSprints` order); the sprint's own id is what the API
 * takes as the period and its team pins WHICH team's sprint of that id the bar was drawn from.
 */
export function sprintOfRow(rowId: string): RowSprint | null {
  const [team, sprint, ...rest] = rowId.split(":");
  const teamId = parseId(team);
  const sprintId = parseId(sprint);
  return rest.length === 0 && teamId !== null && sprintId !== null ? { teamId, sprintId } : null;
}

function parseId(raw: string | undefined): number | null {
  return raw !== undefined && /^[1-9]\d*$/.test(raw) ? Number(raw) : null;
}

/** The target of a sprint bar, `null` for a row id that names no sprint. */
export function sprintTarget(rowId: string, bucket: SprintBucket, segmentAccountId?: string): DrillTarget | null {
  const sprint = sprintOfRow(rowId);
  if (sprint === null) return null;
  return segmentAccountId === undefined ? { kind: "sprint", ...sprint, bucket } : { kind: "sprint", ...sprint, bucket, segmentAccountId };
}

/**
 * The filter a sprint drill is requested with: the page's own filter (members, connection, slices stay exactly as the bar was
 * drawn) with the period narrowed to the ONE sprint, because a sprint bar is one sprint of the period, and the team narrowed to
 * the bar's own (a unit-wide chart carries no team filter, yet the same sprint id on another team's connection is another sprint).
 */
export function sprintDrillFilter(filter: ReportFilterState, sprint: RowSprint): ReportFilterState {
  const next = { ...filter, sprintId: sprint.sprintId, teamId: sprint.teamId };
  delete next.from;
  delete next.to;
  delete next.lastSprints;
  return next;
}

/** The sprint's chart label (its name, team-prefixed where several teams share the chart) by team and sprint id, for a drawer title. */
export function sprintLabeller<S extends SprintIdentity>(
  sprints: readonly S[] | undefined,
  filters: ReportFilters | undefined,
): (sprint: RowSprint) => string | undefined {
  if (!sprints || !filters) return () => undefined;
  const labels = new Map(labelledSprints(sprints, filters).map(({ sprint, label }) => [`${sprint.teamId}:${sprint.sprintId}`, label]));
  return ({ teamId, sprintId }) => labels.get(`${teamId}:${sprintId}`);
}

/** Whether a report's sprint row is the one a target names. */
function isTargetSprint(candidate: RowSprint, target: RowSprint): boolean {
  return candidate.sprintId === target.sprintId && candidate.teamId === target.teamId;
}

/** Commitments' series → bucket: `initialMd` is the committed scope, `finalMd` the final one. */
const COMMITMENTS_BUCKETS = new Map<string, SprintBucket>([
  ["initialMd", "COMMITTED"],
  ["finalMd", "FINAL"],
]);

/** The person of a user-mode series (`c<i>`: committed stack, `f<i>`: final stack) with the bucket that stack is. */
interface CommitmentsSegmentUser {
  accountId: string;
  committedKey: string;
  finalKey: string;
}

/**
 * What a double-click on Commitments' chart opens: a team-mode bar is the sprint's committed or final scope; a user-mode segment
 * is one person's share of one of the two stacks (their `accountId` rides along as the segment).
 */
export function commitmentsHitTarget(hit: ChartBarHit, users: readonly CommitmentsSegmentUser[]): DrillTarget | null {
  const user = users.find((candidate) => candidate.committedKey === hit.series || candidate.finalKey === hit.series);
  if (user) return sprintTarget(hit.rowId, hit.series === user.committedKey ? "COMMITTED" : "FINAL", user.accountId);
  const bucket = COMMITMENTS_BUCKETS.get(hit.series);
  return bucket === undefined ? null : sprintTarget(hit.rowId, bucket);
}

/** The items Commitments shows for the open bar (or person's share of it): the chart's own figure, to compare the drill with. */
export function commitmentsDrillCount(sprints: readonly CommitmentsSprint[] | undefined, target: SprintTarget): number | undefined {
  const sprint = sprints?.find((candidate) => isTargetSprint(candidate, target));
  if (!sprint || (target.bucket !== "COMMITTED" && target.bucket !== "FINAL")) return undefined;
  const figures = target.segmentAccountId ? sprint.byUser.find((share) => share.accountId === target.segmentAccountId) : sprint;
  if (!figures) return undefined;
  return target.bucket === "COMMITTED" ? figures.initialItems : figures.finalItems;
}

/** The Home chart's series → bucket. */
const HOME_BUCKETS = new Map<string, SprintBucket>([
  ["initialMd", "COMMITTED"],
  ["finalMd", "FINAL"],
  ["deliveredMd", "DELIVERED"],
]);

/** What a double-click on the Home chart opens: the team's last sprint, committed, final or delivered. */
export function homeHitTarget(hit: ChartBarHit): DrillTarget | null {
  const bucket = HOME_BUCKETS.get(hit.series);
  return bucket === undefined ? null : sprintTarget(hit.rowId, bucket);
}

/** The delivered items Throughput's sprint chart shows for the open bar (or person's share of it). */
export function throughputSprintDrillCount(sprints: readonly ThroughputSprint[] | undefined, target: SprintTarget): number | undefined {
  const sprint = sprints?.find((candidate) => isTargetSprint(candidate, target));
  if (!sprint || target.bucket !== "DELIVERED") return undefined;
  if (!target.segmentAccountId) return sprint.deliveredItems;
  return sprint.byUser.find((share) => share.accountId === target.segmentAccountId)?.deliveredItems;
}

/** Sprint consistency's series → bucket: every chart series is one bucket; FINAL is drawn by no series (only the table drills it). */
const CONSISTENCY_BUCKETS = new Map<string, SprintBucket>([
  ["committedMd", "COMMITTED"],
  ["deliveredMd", "DELIVERED"],
  ["carriedOverMd", "CARRIED_OVER"],
  ["droppedMd", "DROPPED"],
  ["addedMd", "ADDED"],
  ["removedMd", "REMOVED"],
]);

/** What a double-click on one of Sprint consistency's three charts opens: the sprint's bucket that bar or stacked segment draws. */
export function consistencyHitTarget(hit: ChartBarHit): DrillTarget | null {
  const bucket = CONSISTENCY_BUCKETS.get(hit.series);
  return bucket === undefined ? null : sprintTarget(hit.rowId, bucket);
}

/** The items Sprint consistency's table shows for the open bucket: the figure to compare the drill with. */
export function consistencyDrillCount(sprints: readonly SprintConsistencySprint[] | undefined, target: SprintTarget): number | undefined {
  const sprint = sprints?.find((candidate) => isTargetSprint(candidate, target));
  if (!sprint) return undefined;
  const counts: Record<SprintBucket, number> = {
    COMMITTED: sprint.committedItems,
    ADDED: sprint.addedItems,
    REMOVED: sprint.removedItems,
    FINAL: sprint.finalItems,
    DELIVERED: sprint.deliveredItems,
    CARRIED_OVER: sprint.carriedOverItems,
    DROPPED: sprint.droppedItems,
  };
  return counts[target.bucket];
}
