import { useState } from "react";
import { useMediaQuery } from "@mantine/hooks";
import { readStoredJson, writeStoredJson } from "./useStoredState";

const STORAGE_KEY = "appShell.navCollapsed";
const TABLET_QUERY = "(min-width: 48em) and (max-width: 74.99375em)";

/**
 * Uses a compact rail as the unconfigured tablet default without overwriting the
 * user's existing desktop/sidebar choice. Flow is a client-only SPA, so reading
 * matchMedia for the initial render avoids a full-sidebar flash at the breakpoint.
 */
export function useResponsiveNav(): [collapsed: boolean, setCollapsed: (collapsed: boolean) => void] {
  const tablet = useMediaQuery(TABLET_QUERY, undefined, { getInitialValueInEffect: false });
  const [preference, setPreference] = useState<boolean | null>(() => {
    const stored = readStoredJson(STORAGE_KEY);
    return typeof stored === "boolean" ? stored : null;
  });

  function setCollapsed(collapsed: boolean) {
    setPreference(collapsed);
    writeStoredJson(STORAGE_KEY, collapsed);
  }

  return [preference ?? tablet, setCollapsed];
}
