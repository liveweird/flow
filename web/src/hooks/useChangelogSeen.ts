import { useSyncExternalStore } from "react";
import { APP_VERSION } from "../changelog/version";

// Device-level "what's new" state for the changelog (like flow.lang, it survives logout).
// A module-level listener set + useSyncExternalStore lets the navbar dot clear the moment
// the Changelog page marks the version seen — a plain localStorage read in the shell would
// only refresh on the next navigation. Hand-rolled guarded read/write on purpose:
// useStoredState owns the flow.viewSettings.* namespace, and this is not view state.
const STORAGE_KEY = "flow.changelog";

const listeners = new Set<() => void>();

function readSeenVersion(): string | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { seenVersion?: unknown };
      if (typeof parsed.seenVersion === "string") return parsed.seenVersion;
    }
  } catch {
    // Corrupt state → treat as never seen.
  }
  return null;
}

/** Records the current app version as seen and refreshes any mounted indicators. */
export function markChangelogSeen() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ seenVersion: APP_VERSION }));
  } catch {
    // Storage unavailable → the dot simply stays.
  }
  listeners.forEach((notify) => notify());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** True while the newest changelog entry has not been viewed on this device. */
export function useChangelogUnseen(): boolean {
  return useSyncExternalStore(subscribe, () => readSeenVersion() !== APP_VERSION);
}
