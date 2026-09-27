import type { TFunction } from "i18next";
import type { DataSourceState } from "../api/dataSources";

/**
 * The data-source state badge colour — the app-wide vocabulary, no new hue: teal success, red
 * blocking, gray neutral. Shared by the list page and the details page's connection summary.
 */
export function dataSourceStateColor(state: DataSourceState): "teal" | "red" | "gray" {
  if (state === "CURRENT") return "teal";
  if (state === "FAILED") return "red";
  return "gray";
}

/**
 * `YYYY-MM-DD HH:mm` sliced from an epoch-millis timestamp, like `VersionStamp` — deterministic
 * across test/CI locales, never `toLocaleString()`. `null`/`undefined` renders the "never"
 * fallback (the list's last-success column, the details page's job timestamps).
 */
export function formatEpochMillis(epochMillis: number | null | undefined, t: TFunction): string {
  if (epochMillis == null) return t("dataSources.never");
  return new Date(epochMillis).toISOString().slice(0, 16).replace("T", " ");
}
