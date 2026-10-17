import type { DeepDiveReport } from "../api/reports";
import { deepDiveMode, normalizeDeepDiveSelection, type DeepDiveMode, type DeepDiveSelection } from "./deepDiveFilter";
import { isValidIsoDate } from "./isoDate";

/** The pickers' option label for an epic: its key, then its summary when it has one (as the grid names its rows). */
export function issueLabel(key: string, summary: string | null | undefined): string {
  return summary === null || summary === undefined || summary === "" ? key : `${key} ${summary}`;
}

/**
 * Labels for the values a selection names, read off an answered report: sprint id → name, epic key →
 * `KEY summary`. A selection opened from a link carries ids and keys only; these give its pills their names.
 */
export function knownLabels(report: DeepDiveReport | undefined): Record<string, string> {
  const labels: Record<string, string> = {};
  if (report === undefined) return labels;
  for (const sprint of report.sprints) labels[String(sprint.id)] = sprint.name;
  for (const epic of report.epics) if (epic.key !== null) labels[epic.key] = issueLabel(epic.key, epic.summary);
  for (const task of report.tasks) labels[task.key] = issueLabel(task.key, task.summary);
  return labels;
}

/**
 * What the option lists said about a pick: the connection it lives in (`null`: not known — a link's pick on a stack with
 * one connection) and, for an epic, its own domain (`undefined`: not known; `null`: known to have none).
 */
export interface PickFacts {
  connectionId: number | null;
  domain?: string | null;
}

/** The picks a domain change took out of the draft, for the line under the pickers. */
interface RemovedNote {
  kind: "sprints" | "epics";
  count: number;
  domain: string;
}

/**
 * The selection panel's editable state — everything stays a string (the controls' values) until
 * `selectionOf` turns it into a `DeepDiveSelection`. The two modes keep their picks apart: mode (a) reads `domain` and
 * `sprints`, mode (b) reads `epics` (its `domain` only filters the epic list). There is no connection field: the
 * connection is derived from what the option lists said about the picks (`picked`). `awaitingSprintCheck` is set by a
 * domain change that kept sprints, until the new domain's sprint list can confirm them.
 */
export interface DeepDiveDraft {
  mode: DeepDiveMode;
  domain: string | null;
  sprints: string[];
  epics: string[];
  picked: Record<string, PickFacts>;
  awaitingSprintCheck: boolean;
  removed: RemovedNote | null;
  from: string;
  to: string;
}

/**
 * A draft showing what the URL's selection says (Sprints of a domain while nothing valid is selected). The link's own
 * connection travels with its picks, so showing the same selection again writes the same URL — unless the reference data
 * does not list that connection (`connectionIds`, a stale link): then the picks' connection is unknown.
 */
export function draftOf(selection: DeepDiveSelection, connectionIds?: readonly number[]): DeepDiveDraft {
  const sprints = (selection.sprintIds ?? []).map(String);
  const epics = selection.epicIds ?? [];
  const listed = selection.connectionId !== undefined && (connectionIds === undefined || connectionIds.includes(selection.connectionId));
  const facts: PickFacts = { connectionId: listed ? (selection.connectionId ?? null) : null };
  return {
    mode: deepDiveMode(selection) ?? "SPRINTS",
    domain: selection.domain ?? null,
    sprints,
    epics,
    picked: Object.fromEntries([...sprints, ...epics].map((value) => [value, facts])),
    awaitingSprintCheck: false,
    removed: null,
    from: selection.from ?? "",
    to: selection.to ?? "",
  };
}

/** The same draft in another mode: the picks are dropped (they belong to the old mode); the domain and the dates stay. */
export function withMode(draft: DeepDiveDraft, mode: DeepDiveMode): DeepDiveDraft {
  return { ...draft, mode, sprints: [], epics: [], picked: {}, awaitingSprintCheck: false, removed: null };
}

/**
 * The draft with another domain (`domainName` is how the viewer reads it, for the note). Epics mode: the domain only
 * filters the epic list, so an epic known to sit in another domain (or in none) is dropped — and counted — while one
 * whose domain is not known stays; clearing the domain keeps every epic. Sprints mode: the sprints stay, to be checked
 * against the new domain's sprint list when it arrives (`pruneSprints`).
 */
export function withDomain(draft: DeepDiveDraft, domain: string | null, domainName: string): DeepDiveDraft {
  const next: DeepDiveDraft = { ...draft, domain, removed: null };
  if (draft.mode === "SPRINTS") return { ...next, awaitingSprintCheck: domain !== null && draft.sprints.length > 0 };
  if (domain === null) return next;
  const keep = draft.epics.filter((key) => {
    const known = draft.picked[key]?.domain;
    return known === undefined || known === domain;
  });
  const count = draft.epics.length - keep.length;
  return { ...next, epics: keep, removed: count > 0 ? { kind: "epics", count, domain: domainName } : null };
}

/**
 * Settles a pending sprint check against an answered sprint list. Only the UNFILTERED answer counts (`q` undefined), and
 * only when it lists every match (`total` fits the page): then a sprint it does not list is not in the domain, so it is
 * dropped and counted (`domainName` is how the viewer reads the domain). A sprint is matched by value AND connection: a pick whose KNOWN
 * connection is not among those the list places that value in is dropped too (a pick with an unknown connection matches by value alone).
 * A truncated list cannot prove anything, so the picks stay either way (the server answers rows only for sprints holding the domain's tasks).
 */
export function pruneSprints(
  draft: DeepDiveDraft,
  page: { options: ReadonlyArray<{ value: string; connectionId?: number }>; total: number },
  q: string | undefined,
  domainName: string,
): DeepDiveDraft {
  if (!draft.awaitingSprintCheck || q !== undefined) return draft;
  const settled = { ...draft, awaitingSprintCheck: false };
  if (page.total > page.options.length) return settled;
  const keep = draft.sprints.filter((id) => {
    const placed = page.options.filter((option) => option.value === id);
    const connectionId = draft.picked[id]?.connectionId ?? null;
    return placed.length > 0 && (connectionId === null || placed.some((option) => option.connectionId === undefined || option.connectionId === connectionId));
  });
  const count = draft.sprints.length - keep.length;
  if (count === 0) return settled;
  return { ...settled, sprints: keep, removed: { kind: "sprints", count, domain: domainName } };
}

/** What an option answer told about each of its values. */
export interface OptionFacts {
  value: string;
  connectionId?: number;
  domain?: string | null;
}

/**
 * The facts an answered list adds to the picks that had none (a link's picks), so a mixed-connection pick is noticed. A
 * value the list places in several connections says nothing about which one the pick is in.
 */
export function learnPicks(draft: DeepDiveDraft, options: ReadonlyArray<OptionFacts>): DeepDiveDraft {
  let picked = draft.picked;
  for (const value of picksOf(draft)) {
    const matching = options.filter((candidate) => candidate.value === value);
    const option = matching.length === 1 ? matching[0] : undefined;
    const known = picked[value];
    if (option === undefined || known === undefined) continue;
    const connectionId = known.connectionId ?? option.connectionId ?? null;
    const domain = known.domain === undefined ? option.domain : known.domain;
    if (connectionId !== known.connectionId || domain !== known.domain) picked = { ...picked, [value]: { connectionId, domain } };
  }
  return picked === draft.picked ? draft : { ...draft, picked };
}

/** The values picked in the draft's own mode. */
const picksOf = (draft: DeepDiveDraft): string[] => (draft.mode === "SPRINTS" ? draft.sprints : draft.epics);

/** The distinct connections the option lists placed the draft's picks in (picks whose connection is not known add none). */
export function pickConnections(draft: DeepDiveDraft): number[] {
  const connections = new Set<number>();
  for (const value of picksOf(draft)) {
    const connectionId = draft.picked[value]?.connectionId;
    if (connectionId !== null && connectionId !== undefined) connections.add(connectionId);
  }
  return [...connections];
}

/**
 * The selection the draft stands for, by its mode — not yet normalized, so an incomplete draft is visibly incomplete.
 * `connectionId` is written only on a stack with several connections (`multiConnection`) and only when the picks name
 * exactly one — so a single-connection URL stays `?domain=…&sprintId=…`.
 */
export function selectionOf(draft: DeepDiveDraft, multiConnection = false): DeepDiveSelection {
  const connections = pickConnections(draft);
  const common: DeepDiveSelection = {
    connectionId: multiConnection && connections.length === 1 ? connections[0] : undefined,
    from: draft.from === "" ? undefined : draft.from,
    to: draft.to === "" ? undefined : draft.to,
  };
  return draft.mode === "SPRINTS"
    ? { ...common, domain: draft.domain ?? undefined, sprintIds: draft.sprints.map(Number) }
    : { ...common, epicIds: draft.epics };
}

/** Typed text that is not (yet) a real `YYYY-MM-DD` date; an empty field is fine — both bounds are optional. */
export const isMalformedDate = (typed: string) => typed !== "" && !isValidIsoDate(typed);

export type DateProblem = "format" | "range" | null;

/** Why the typed dates cannot be applied: a malformed one, or a pair that is reversed or wider than the server's cap. */
export function dateProblem(draft: DeepDiveDraft): DateProblem {
  if (isMalformedDate(draft.from) || isMalformedDate(draft.to)) return "format";
  const kept = normalizeDeepDiveSelection(selectionOf(draft));
  // Each bound is well formed, so a bound that did not survive normalization was dropped with its PAIR.
  const survived = (typed: string, bound: string | undefined) => (typed === "" ? bound === undefined : typed === bound);
  return survived(draft.from, kept.from) && survived(draft.to, kept.to) ? null : "range";
}

/** Whether the draft can be shown: its own mode's picks are all there, within the limits, and the dates are usable. */
export function isComplete(draft: DeepDiveDraft): boolean {
  return deepDiveMode(selectionOf(draft)) === draft.mode && dateProblem(draft) === null;
}

export type Missing = "domainAndSprints" | "sprints" | "epics" | "oneConnection" | "dates";

/** What stops the draft from being shown, as the first thing the viewer still has to do; `null` when it is complete. */
export function missingOf(draft: DeepDiveDraft): Missing | null {
  if (isComplete(draft)) return pickConnections(draft).length > 1 ? "oneConnection" : null;
  if (draft.mode === "SPRINTS") {
    if (draft.domain === null) return "domainAndSprints";
    if (draft.sprints.length === 0) return "sprints";
  } else if (draft.epics.length === 0) return "epics";
  return pickConnections(draft).length > 1 ? "oneConnection" : "dates";
}
