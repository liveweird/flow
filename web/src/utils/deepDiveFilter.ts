import { isIntegerText, MAX_TEXT, parseBoundedInteger, validRange } from "./filterPrimitives";
import { isValidIsoDate } from "./isoDate";

/**
 * The Deep dive's selection, (de)serialized to the URL search params — the URL is the selection
 * (deep-linkable), following `reportFilter.ts`: the parser is forgiving (an invalid or conflicting
 * value is DROPPED, never sent), the serialization is canonical (fixed key order, repeated values
 * de-duplicated and sorted) and foreign params are left alone. It does NOT reuse `ReportFilterState`:
 * the report has its own parser server-side (`reports/DeepDiveSelection.kt`) with repeated keys and
 * no default period. Layer toggles and drill state are not part of the URL.
 *
 * The mode is inferred from the params exactly as the server does:
 *  - (a) `domain` + `sprintId`* — SPRINTS (1 to 52 sprints);
 *  - (b) `epicId`* — EPICS (1 to 50 epics);
 *  - (c) ONE `epicId` + `issueId`* — TASKS (1 to 500 tasks).
 * Where the server would answer `400` for a mix, the parser keeps one mode and drops the rest, by
 * precedence (a) over (c) over (b): sprint ids WITHOUT a domain are dropped (and a domain without
 * sprints), `issueId`s with other than exactly one `epicId` are dropped, and a value group over its
 * limit is dropped as a whole rather than truncated.
 */

export type DeepDiveMode = "SPRINTS" | "EPICS" | "TASKS";

export interface DeepDiveSelection {
  /** Mode (a): the domain key whose level-0 tasks are shown; required with `sprintIds`. */
  domain?: string;
  /** Mode (a): Jira sprint ids. */
  sprintIds?: number[];
  /** Mode (b): epic issue keys; mode (c): exactly one. */
  epicIds?: string[];
  /** Mode (c): the handpicked tasks' issue keys, all under the one epic. */
  issueIds?: string[];
  connectionId?: number;
  /** ISO dates, inclusive; clip the range the server derives from the selection. */
  from?: string;
  to?: string;
}

export const MAX_DEEP_DIVE_SPRINTS = 52;
export const MAX_DEEP_DIVE_EPICS = 50;
export const MAX_DEEP_DIVE_ISSUES = 500;

/** Every param this module owns — anything else in the query string is left alone. Canonical order. */
const MANAGED_KEYS = [
  "domain",
  "sprintId",
  "epicId",
  "issueId",
  "connectionId",
  "from",
  "to",
] as const;

/** C0, DEL and C1 control characters — Kotlin's `isISOControl`, which the server answers `400` to. */
const CONTROL_CHARS = "[\\u0000-\\u001f\\u007f-\\u009f]";
const HAS_CONTROL_CHAR = new RegExp(CONTROL_CHARS);
const ALL_CONTROL_CHARS = new RegExp(CONTROL_CHARS, "g");

/** Free text for an option-list param: control characters stripped, trimmed; `undefined` when nothing is left. */
export function cleanSearchText(
  value: string | null | undefined,
): string | undefined {
  const cleaned = (value ?? "").replace(ALL_CONTROL_CHARS, "").trim();
  return cleaned === "" ? undefined : cleaned;
}

function cleanText(value: string | null | undefined): string | undefined {
  if (value == null) return undefined;
  const trimmed = value.trim();
  return trimmed !== "" &&
    trimmed.length <= MAX_TEXT &&
    !HAS_CONTROL_CHAR.test(trimmed)
    ? trimmed
    : undefined;
}

/** `FLO-12` → its project prefix (`FLO-`) and number; `null` for anything not shaped like an issue key. */
function splitIssueKey(key: string): { prefix: string; n: number } | null {
  const dash = key.lastIndexOf("-");
  const digits = dash < 0 ? "" : key.slice(dash + 1);
  return isIntegerText(digits)
    ? { prefix: key.slice(0, dash + 1), n: Number(digits) }
    : null;
}

/** Issue-key order: by project prefix, then by the NUMBER (`FLO-2` before `FLO-10`), else plain text. */
export function compareIssueKeys(a: string, b: string): number {
  const ka = splitIssueKey(a);
  const kb = splitIssueKey(b);
  if (ka && kb && ka.prefix === kb.prefix) return ka.n - kb.n;
  return a < b ? -1 : a > b ? 1 : 0;
}

function distinct<T>(
  values: ReadonlyArray<T | undefined> | undefined,
  compare: (a: T, b: T) => number,
): T[] {
  const kept = new Set<T>();
  for (const value of values ?? []) if (value !== undefined) kept.add(value);
  return [...kept].sort(compare);
}

const within = (list: readonly unknown[], max: number) =>
  list.length >= 1 && list.length <= max;

function cleanDate(value: string | undefined): string | undefined {
  return value !== undefined && isValidIsoDate(value) ? value : undefined;
}

/**
 * The selection made consistent: values cleaned, de-duplicated and sorted, ONE mode kept (by the
 * precedence above), an invalid date pair dropped. Absent fields are omitted, never `undefined`/empty.
 */
export function normalizeDeepDiveSelection(
  selection: DeepDiveSelection,
): DeepDiveSelection {
  const out: DeepDiveSelection = {};
  const domain = cleanText(selection.domain);
  const sprintIds = distinct(
    selection.sprintIds?.map((id) => parseBoundedInteger(id, 1)),
    (a, b) => a - b,
  );
  const epicIds = distinct(selection.epicIds?.map(cleanText), compareIssueKeys);
  const issueIds = distinct(
    selection.issueIds?.map(cleanText),
    compareIssueKeys,
  );

  if (domain !== undefined && within(sprintIds, MAX_DEEP_DIVE_SPRINTS)) {
    out.domain = domain;
    out.sprintIds = sprintIds;
  } else if (within(epicIds, MAX_DEEP_DIVE_EPICS)) {
    out.epicIds = epicIds;
    if (epicIds.length === 1 && within(issueIds, MAX_DEEP_DIVE_ISSUES))
      out.issueIds = issueIds;
  }

  const connectionId = parseBoundedInteger(selection.connectionId, 1);
  if (connectionId !== undefined) out.connectionId = connectionId;
  const from = cleanDate(selection.from);
  const to = cleanDate(selection.to);
  if (validRange(from, to)) {
    if (from !== undefined) out.from = from;
    if (to !== undefined) out.to = to;
  }
  return out;
}

/** Which of the three selections the (normalized) selection runs; `null` while nothing valid is selected. */
export function deepDiveMode(
  selection: DeepDiveSelection,
): DeepDiveMode | null {
  const normalized = normalizeDeepDiveSelection(selection);
  if (normalized.sprintIds !== undefined) return "SPRINTS";
  if (normalized.issueIds !== undefined) return "TASKS";
  return normalized.epicIds !== undefined ? "EPICS" : null;
}

/** Forgiving parse: a selection whose values are all ones the server would accept, in canonical form. */
export function parseDeepDiveSelection(
  params: URLSearchParams,
): DeepDiveSelection {
  return normalizeDeepDiveSelection({
    domain: params.get("domain") ?? undefined,
    sprintIds: params
      .getAll("sprintId")
      .flatMap((value) => parseBoundedInteger(value, 1) ?? []),
    epicIds: params.getAll("epicId"),
    issueIds: params.getAll("issueId"),
    connectionId: parseBoundedInteger(params.get("connectionId"), 1),
    from: params.get("from") ?? undefined,
    to: params.get("to") ?? undefined,
  });
}

/** Writes the selection into `base` (a copy): managed keys are replaced, unrelated ones kept. */
export function applyDeepDiveSelection(
  base: URLSearchParams,
  selection: DeepDiveSelection,
): URLSearchParams {
  const next = new URLSearchParams(base);
  for (const key of MANAGED_KEYS) next.delete(key);
  const s = normalizeDeepDiveSelection(selection);
  if (s.domain !== undefined) next.set("domain", s.domain);
  for (const id of s.sprintIds ?? []) next.append("sprintId", String(id));
  for (const key of s.epicIds ?? []) next.append("epicId", key);
  for (const key of s.issueIds ?? []) next.append("issueId", key);
  if (s.connectionId !== undefined)
    next.set("connectionId", String(s.connectionId));
  if (s.from !== undefined) next.set("from", s.from);
  if (s.to !== undefined) next.set("to", s.to);
  return next;
}

/** The canonical serialization — the page query key and the request query. */
export function serializeDeepDiveSelection(
  selection: DeepDiveSelection,
): URLSearchParams {
  return applyDeepDiveSelection(new URLSearchParams(), selection);
}

export function deepDiveQuery(selection: DeepDiveSelection): string {
  return serializeDeepDiveSelection(selection).toString();
}

/**
 * The page's second view of the same selection: the matrix (the default, nothing in the URL) or the burn-up
 * (`view=burnup`). Not part of the selection — `view` is a foreign param to every function above, which keeps it
 * — so a link shares the tab without changing the query key or the request.
 */
export type DeepDiveView = "matrix" | "burnup";

const VIEW_KEY = "view";

export function parseDeepDiveView(params: URLSearchParams): DeepDiveView {
  return params.get(VIEW_KEY) === "burnup" ? "burnup" : "matrix";
}

/** `base` (a copy) with the view written: the matrix is the default, so it is the param's absence. */
export function applyDeepDiveView(base: URLSearchParams, view: DeepDiveView): URLSearchParams {
  const next = new URLSearchParams(base);
  if (view === "burnup") next.set(VIEW_KEY, "burnup");
  else next.delete(VIEW_KEY);
  return next;
}
