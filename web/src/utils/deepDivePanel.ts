import type { DeepDiveReport } from "../api/reports";
import { deepDiveMode, normalizeDeepDiveSelection, type DeepDiveMode, type DeepDiveSelection } from "./deepDiveFilter";
import { isValidIsoDate } from "./isoDate";

/** The pickers' option label for an epic or a task: its key, then its summary when it has one (as the grid names its rows). */
export function issueLabel(key: string, summary: string | null | undefined): string {
  return summary === null || summary === undefined || summary === "" ? key : `${key} ${summary}`;
}

/**
 * Labels for the values a selection names, read off an answered report: sprint id → name, epic and task key →
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
 * The selection panel's editable state — everything stays a string (the controls' values) until
 * `selectionOf` turns it into a `DeepDiveSelection`. The picks of the three modes share fields: mode (b) reads
 * `epics`, mode (c) reads the first of `epics` plus `tasks`.
 */
export interface DeepDiveDraft {
  mode: DeepDiveMode;
  domain: string | null;
  sprints: string[];
  epics: string[];
  tasks: string[];
  connectionId: string | null;
  from: string;
  to: string;
}

/** A draft showing what the URL's selection says (Sprints of a domain while nothing valid is selected). */
export function draftOf(selection: DeepDiveSelection): DeepDiveDraft {
  return {
    mode: deepDiveMode(selection) ?? "SPRINTS",
    domain: selection.domain ?? null,
    sprints: (selection.sprintIds ?? []).map(String),
    epics: selection.epicIds ?? [],
    tasks: selection.issueIds ?? [],
    connectionId: selection.connectionId === undefined ? null : String(selection.connectionId),
    from: selection.from ?? "",
    to: selection.to ?? "",
  };
}

/** The same draft in another mode: every pick is dropped (they belong to the old mode), connection and dates stay. */
export function withMode(draft: DeepDiveDraft, mode: DeepDiveMode): DeepDiveDraft {
  return { ...draft, mode, domain: null, sprints: [], epics: [], tasks: [] };
}

/** The selection the draft stands for, by its mode — not yet normalized, so an incomplete draft is visibly incomplete. */
export function selectionOf(draft: DeepDiveDraft): DeepDiveSelection {
  const common: DeepDiveSelection = {
    connectionId: draft.connectionId === null ? undefined : Number(draft.connectionId),
    from: draft.from === "" ? undefined : draft.from,
    to: draft.to === "" ? undefined : draft.to,
  };
  switch (draft.mode) {
    case "SPRINTS":
      return { ...common, domain: draft.domain ?? undefined, sprintIds: draft.sprints.map(Number) };
    case "EPICS":
      return { ...common, epicIds: draft.epics };
    case "TASKS":
      return { ...common, epicIds: draft.epics.slice(0, 1), issueIds: draft.tasks };
  }
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
