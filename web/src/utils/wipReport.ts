import type { ParseKeys, TFunction } from "i18next";
import type { WipKey, WipPoint, WipReport } from "../api/reports";
import { BAND_CYCLE, CHART_COLORS } from "./chartColors";
import { formatOneDecimal } from "./reportFormat";

/** One band of the stacked WIP chart: a legend key with its chart data name, display label and colour. */
export interface WipBand {
  /** The server's key (a stage, a Jira status id, a board column name). */
  key: string;
  /** The data name in the chart rows — index-based, so a key with a dot or a space is never read as a path. */
  name: string;
  label: string;
}

/** A band as drawn: its colour depends on the bands currently shown, so it is added last. */
export interface PaintedWipBand extends WipBand {
  color: string;
}

const STAGE_LABELS: Readonly<Record<string, ParseKeys>> = {
  NOT_STARTED: "reports.wip.stage.NOT_STARTED",
  IN_PROGRESS: "reports.wip.stage.IN_PROGRESS",
  DONE: "reports.wip.stage.DONE",
  UNMAPPED: "reports.wip.stage.UNMAPPED",
};

const STAGE_COLORS: Readonly<Record<string, string>> = {
  NOT_STARTED: CHART_COLORS.stageNotStarted,
  IN_PROGRESS: CHART_COLORS.stageInProgress,
  DONE: CHART_COLORS.stageDone,
  UNMAPPED: CHART_COLORS.stageUnmapped,
};

/** The board-column key of a status no column holds (the server's own literal). */
const NO_COLUMN_KEY = "(no column)";

/**
 * The bands hidden until the reader ticks them. `DONE` only ever grows and `NOT_STARTED` holds the
 * whole backlog — either would flatten the work actually in progress; a status or a column is not
 * known to be either (the report does not say which stage it belongs to), so those start complete.
 */
export const DEFAULT_HIDDEN_BANDS: Readonly<Record<WipReport["by"], readonly string[]>> = {
  STAGE: ["NOT_STARTED", "DONE"],
  STATUS: [],
  COLUMN: [],
};

function bandLabel(by: WipReport["by"], entry: WipKey, t: TFunction): string {
  if (by === "STAGE") {
    const key = STAGE_LABELS[entry.key];
    return key === undefined ? entry.label : t(key);
  }
  return by === "COLUMN" && entry.key === NO_COLUMN_KEY ? t("reports.wip.noColumn") : entry.label;
}

/** The report's legend as bands (data name + label); colours come with `paintBands`. */
export function wipBands(by: WipReport["by"], keys: readonly WipKey[], t: TFunction): WipBand[] {
  return keys.map((entry, index) => ({ key: entry.key, name: `b${index}`, label: bandLabel(by, entry, t) }));
}

/**
 * Colours the bands being drawn. A stage keeps ITS colour (the app's vocabulary: gray not started,
 * blue in progress, teal done, orange unmapped). Statuses and columns walk the neutral cycle by
 * position among the bands SHOWN, so neighbouring bands never share a hue whichever are ticked.
 */
export function paintBands(by: WipReport["by"], shown: readonly WipBand[]): PaintedWipBand[] {
  return shown.map((band, index) => ({
    ...band,
    color: (by === "STAGE" ? STAGE_COLORS[band.key] : undefined) ?? BAND_CYCLE[index % BAND_CYCLE.length],
  }));
}

/** The chart rows: one per day, the day and each band's count under its data name. */
export function wipChartRows(series: readonly WipPoint[], bands: readonly WipBand[]): Array<Record<string, string | number>> {
  return series.map((point) => {
    const row: Record<string, string | number> = { day: point.day };
    for (const band of bands) row[band.name] = point.counts[band.key] ?? 0;
    return row;
  });
}

export interface WipBandSummary {
  latest: number;
  /** Mean daily count over the whole series, one decimal. */
  average: string;
  peak: number;
}

/** A band's latest-day count, average and peak over the series — the chart's text alternative. */
export function wipBandSummary(series: readonly WipPoint[], key: string): WipBandSummary {
  const counts = series.map((point) => point.counts[key] ?? 0);
  const total = counts.reduce((sum, n) => sum + n, 0);
  return {
    latest: counts.at(-1) ?? 0,
    average: formatOneDecimal(counts.length === 0 ? 0 : total / counts.length),
    peak: counts.length === 0 ? 0 : Math.max(...counts),
  };
}
