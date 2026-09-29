// Shared helpers for the timing scripts (node built-ins only; runs on node >= 18).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export const BUDGETS = JSON.parse(readFileSync(join(here, "budgets.json"), "utf8"));

/** Budget entry for `scope` ("ci" | "local") and `name`, or undefined. */
export function budgetFor(scope, name) {
  return BUDGETS.budgets[scope]?.[name];
}

/** Resolve a dotted "scope.name" key (e.g. "ci.server") to its budget entry, or undefined. */
export function budgetByKey(key) {
  const dot = key.indexOf(".");
  return dot < 0 ? undefined : budgetFor(key.slice(0, dot), key.slice(dot + 1));
}

export function median(values) {
  if (values.length === 0) return undefined;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** Human duration: 850ms, 4.2s, 45s, 3m05s, 1h02m (rounding never yields "60s" or "1000ms"). */
export function fmtDur(seconds) {
  if (seconds === undefined || Number.isNaN(seconds)) return "-";
  if (seconds < 1) {
    const ms = Math.round(seconds * 1000);
    return ms >= 1000 ? "1.0s" : `${ms}ms`;
  }
  if (seconds < 10) {
    const tenths = seconds.toFixed(1);
    if (tenths !== "10.0") return `${tenths}s`;
  }
  const s = Math.round(seconds);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m${String(s % 60).padStart(2, "0")}s`;
  return `${Math.floor(s / 3600)}h${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}m`;
}

/**
 * Trend analysis over `samples` (chronological, oldest first, seconds).
 * recent = median of the last `window`, previous = median of the `window` before those.
 */
export function analyse(samples, budget) {
  const { window, ratio, minDeltaSeconds, minDeltaFromSeconds } = BUDGETS.trend;
  const recentSamples = samples.slice(-window);
  const previousSamples = samples.slice(-2 * window, -window);
  const recent = median(recentSamples);
  const previous = previousSamples.length >= 3 ? median(previousSamples) : undefined;
  const flags = [];
  // Below `minDeltaFromSeconds` the trend is relative-only; from there on it also needs an absolute rise.
  const floor = recent < minDeltaFromSeconds ? 0 : minDeltaSeconds;
  if (previous !== undefined && recent > previous * ratio && recent - previous >= floor) {
    flags.push(`TREND +${Math.round((recent / previous - 1) * 100)}%`);
  }
  if (budget && recent > budget.alarm) flags.push("OVER ALARM");
  else if (budget && recent > budget.target) flags.push("over target");
  return {
    n: samples.length,
    latest: samples[samples.length - 1],
    recent,
    previous,
    min: Math.min(...samples),
    max: Math.max(...samples),
    flags,
  };
}

/** Plain-text table; `rows` are arrays of strings, `align` per column: "l" | "r". */
export function table(header, rows, align = []) {
  const all = [header, ...rows];
  const widths = header.map((_, c) => Math.max(...all.map((r) => String(r[c] ?? "").length)));
  const line = (r) =>
    r
      .map((cell, c) => {
        const text = String(cell ?? "");
        return align[c] === "r" ? text.padStart(widths[c]) : text.padEnd(widths[c]);
      })
      .join("  ")
      .trimEnd();
  return [line(header), widths.map((w) => "-".repeat(w)).join("  "), ...rows.map(line)].join("\n");
}

export function budgetCell(budget) {
  return budget ? `${fmtDur(budget.target)} / ${fmtDur(budget.alarm)}` : "-";
}

/** The rows of an analysis table shared by ci-times and local-times report. */
export const ANALYSIS_HEADER = ["name", "n", "latest", "median 5", "prev 5", "min", "max", "target / alarm", "flags"];

export function analysisRow(name, a, budget) {
  return [
    name,
    String(a.n),
    fmtDur(a.latest),
    fmtDur(a.recent),
    fmtDur(a.previous),
    fmtDur(a.min),
    fmtDur(a.max),
    budgetCell(budget),
    a.flags.join(", "),
  ];
}

export const ANALYSIS_ALIGN = ["l", "r", "r", "r", "r", "r", "r", "r", "l"];
