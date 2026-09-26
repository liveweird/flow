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
