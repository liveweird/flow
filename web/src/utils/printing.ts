import { useSyncExternalStore } from "react";

/**
 * Whether the page is being printed right now — a tiny module-level store (never persisted) that
 * `PrintAwareMantineProvider` reads to force the light colour scheme through React, so components that
 * pick colours with `useComputedColorScheme` (heat cells, chart marks) print in their light colours too.
 * Written only by `usePrintLifecycle`.
 */
let printing = false;
const listeners = new Set<() => void>();

export function setPrinting(next: boolean): void {
  if (printing === next) return;
  printing = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function usePrinting(): boolean {
  return useSyncExternalStore(subscribe, () => printing);
}
