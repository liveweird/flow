import { useState } from "react";

const PREFIX = "flow.viewSettings.";

/**
 * Guarded localStorage JSON read for a view-settings key. Returns `undefined` when absent,
 * unreadable, or corrupt — persistence is best-effort and must never break a page. Shared by
 * [useStoredState] and `usePagedSort`'s paging persistence.
 */
export function readStoredJson(key: string): unknown {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw == null ? undefined : (JSON.parse(raw) as unknown);
  } catch {
    return undefined;
  }
}

/** Guarded localStorage JSON write for a view-settings key (best-effort, errors swallowed). */
export function writeStoredJson(key: string, value: unknown) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Best-effort persistence only.
  }
}

/**
 * The filter a screen was last left on (`flow.viewSettings.<screen>.filter`): the URL query its filter bar / selection wrote,
 * as a plain string. `null` = nothing remembered (never set, unreadable or not a string); `""` = the user cleared everything.
 * Read at render time, never held in state: the writers (a filter change, an in-page link) and the reader (the next bare
 * visit) sit in different hooks, and the localStorage value is the one truth between them.
 */
export function readRememberedQuery(screen: string): string | null {
  const stored = readStoredJson(`${screen}.filter`);
  return typeof stored === "string" ? stored : null;
}

/** Best-effort write of [readRememberedQuery]'s value. */
export function writeRememberedQuery(screen: string, query: string) {
  writeStoredJson(`${screen}.filter`, query);
}

/**
 * Forgets every remembered screen filter (`flow.viewSettings.<screen>.filter`, which can name people and teams) and the shared remembered
 * team (`reports.teamId`) — called when the session ends, so the next person on this browser starts clean. Best-effort; the list pages'
 * own `<view>.filter.<name>` text filters and every other view setting are untouched.
 */
export function clearRememberedFilters() {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (key !== null && key.startsWith(PREFIX) && (key.endsWith(".filter") || key === `${PREFIX}reports.teamId`)) doomed.push(key);
    }
    for (const key of doomed) localStorage.removeItem(key);
  } catch {
    // Best-effort, like every view-settings write.
  }
}

/**
 * A `useState` that persists to localStorage (under `flow.viewSettings.<key>`) and restores
 * on mount, so a list view's filters/sort survive navigation and reloads. `isValid` guards
 * against stale or corrupt stored values (e.g. an enum value that no longer exists) — anything
 * failing it falls back to `initial`.
 */
export function useStoredState<T>(
  key: string,
  initial: T,
  isValid: (v: unknown) => boolean,
): [T, (next: T) => void] {
  const [value, setValue] = useState<T>(() => {
    const stored = readStoredJson(key);
    return stored !== undefined && isValid(stored) ? (stored as T) : initial;
  });

  function set(next: T) {
    setValue(next);
    writeStoredJson(key, next);
  }

  return [value, set];
}

/** Validator for plain string state (the text filters). */
export const isString = (v: unknown): v is string => typeof v === "string";

/** Validator for plain boolean state (e.g. the FilterPanel open flag). */
export const isBoolean = (v: unknown): v is boolean => typeof v === "boolean";

/** Validator factory for single-select state persisted from a closed value list. */
export function isOneOf<T extends string>(values: readonly T[]) {
  return (v: unknown): v is T => values.includes(v as T);
}

/** Validator factory for nullable single-select state: null or one of the allowed values. */
export function isOneOfOrNull<T extends string>(values: readonly T[]) {
  return (v: unknown): v is T | null => v === null || values.includes(v as T);
}
