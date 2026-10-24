import { useSyncExternalStore } from "react";
import { readStoredJson, writeStoredJson } from "./useStoredState";

// Whether the telemetry panel is open (`flow.viewSettings.telemetry.panelOpen`, default closed). Three places act on it: the
// account menu item, the command-palette action and the shell's aside, so it cannot be a per-component `useStoredState`. A
// module-level listener set + useSyncExternalStore (the useChangelogSeen idiom) lets every consumer follow a write at once;
// the snapshot is read from localStorage each time, so there is no module state to leak between tests. Whether the panel is
// SHOWN also needs ADMIN (App.tsx): a stored `true` never reaches a non-admin.
const KEY = "telemetry.panelOpen";

const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function readOpen(): boolean {
  return readStoredJson(KEY) === true;
}

/**
 * Opens or closes the panel on this device and refreshes every mounted consumer. Closing hands focus to the page (the panel's own
 * controls unmount with it, and the palette and the close button would otherwise drop focus on the body).
 */
export function setTelemetryPanelOpen(open: boolean) {
  writeStoredJson(KEY, open);
  listeners.forEach((notify) => notify());
  if (!open) document.getElementById("main-content")?.focus();
}

/** The stored open flag. Combine with `useAdmin()` before showing anything. */
export function useTelemetryPanelOpen(): boolean {
  return useSyncExternalStore(subscribe, readOpen);
}
