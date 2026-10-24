import type { TFunction } from "i18next";
import type { TelemetrySeries } from "../api/telemetry";
import { formatDateTime } from "./formatDate";
import { roleName } from "./telemetryRole";
import { formatBytes, formatCount, formatDuration, formatPercent } from "./telemetryFormat";

// Turns the history endpoint's series into the columns of one section's trend: a few plotted lines (ONE unit, one axis) and the
// full numbers table beside them. Pure and per section; `components/TelemetryTrend.tsx` queries and renders.

/** A ratio series is shown in percent; every other unit is the wire's. */
export type TrendUnit = "BYTES" | "MILLISECONDS" | "COUNT" | "PERCENT";

export interface TrendColumn {
  id: string;
  label: string;
  unit: TrendUnit;
  /** Drawn as a line (all plotted columns of one section share a unit); every column is in the table. */
  plot: boolean;
  /** Bucket start (epoch millis) to the figure. */
  values: ReadonlyMap<number, number>;
}

type Field = "avg" | "max";

export function formatTrendValue(unit: TrendUnit, value: number): string {
  switch (unit) {
    case "BYTES":
      return formatBytes(value);
    case "MILLISECONDS":
      return formatDuration(value);
    case "PERCENT":
      return formatPercent(value);
    case "COUNT":
      return formatCount(value);
  }
}

function trendUnit(series: TelemetrySeries): TrendUnit {
  return series.unit === "RATIO" ? "PERCENT" : series.unit;
}

function toColumn(series: TelemetrySeries, label: string, field: Field, plot: boolean): TrendColumn {
  const scale = series.unit === "RATIO" ? 100 : 1;
  return {
    id: `${series.key}:${field}`,
    label,
    unit: trendUnit(series),
    plot,
    values: new Map(series.points.map((point) => [point.t, point[field] * scale])),
  };
}

/** More than one process role sampled the metric (web and worker): their lines are told apart by a role suffix. */
function hasSeveralRoles(series: readonly TelemetrySeries[]): boolean {
  return new Set(series.flatMap((s) => (s.role == null ? [] : [s.role]))).size > 1;
}

/** Adds the role in words ("Web", "Worker") to a column label, only when several roles sampled the metric. */
type Namer = (label: string, role: string | null) => string;

function namer(series: readonly TelemetrySeries[], t: TFunction): Namer {
  const several = hasSeveralRoles(series);
  return (label, role) => (several && role != null ? `${label} (${roleName(t, role)})` : label);
}

function columnsOf(
  series: readonly TelemetrySeries[],
  metric: string,
  label: string,
  field: Field,
  plot: boolean,
  name: Namer,
): TrendColumn[] {
  return series.filter((s) => s.metric === metric).map((s) => toColumn(s, name(label, s.role), field, plot));
}

/** The sum of several same-role series (a metric sampled per GC collector) at each bucket, `avg` field. */
function summed(parts: readonly TelemetrySeries[], id: string, label: string): TrendColumn | null {
  const first = parts[0];
  if (first === undefined) return null;
  const values = new Map<number, number>();
  for (const part of parts) {
    for (const point of part.points) values.set(point.t, (values.get(point.t) ?? 0) + point.avg);
  }
  return { id, label, unit: trendUnit(first), plot: false, values };
}

/** A count column of zeros over the buckets of a reference series: the server omits a series that never left zero (absent = 0). */
function zeros(reference: TelemetrySeries | undefined, id: string, label: string): TrendColumn[] {
  if (reference === undefined) return [];
  return [{ id, label, unit: "COUNT", plot: false, values: new Map(reference.points.map((point) => [point.t, 0])) }];
}

/**
 * Memory: the container working set (the figure the kubelet evicts on) per role. With a known container limit for every role
 * (`limits`: role to bytes, from the current answer) the DRAWN line is its share of the limit in percent - proximity to the limit is
 * the point - and the table adds the bytes; without one the bytes are drawn, and without any working set the heap is.
 */
export function memoryColumns(series: readonly TelemetrySeries[], t: TFunction, limits: ReadonlyMap<string, number>): TrendColumn[] {
  const name = namer(series, t);
  const workingSets = series.filter((s) => s.metric === "mem.cgroup_working_set");
  const hasWorkingSet = workingSets.length > 0;
  const shares = hasWorkingSet && workingSets.every((s) => s.role != null && limits.has(s.role));
  const share = (field: Field, label: string, plot: boolean): TrendColumn[] =>
    shares
      ? workingSets.map((s) => {
          const limit = limits.get(s.role ?? "") ?? 1;
          const column = toColumn(s, name(label, s.role), field, plot);
          return { ...column, id: `${column.id}:share`, unit: "PERCENT", values: new Map([...column.values].map(([at, bytes]) => [at, (bytes / limit) * 100])) };
        })
      : [];
  return [
    ...share("avg", t("telemetry.trend.shareOfLimit"), true),
    ...share("max", t("telemetry.trend.shareOfLimitPeak"), false),
    ...columnsOf(series, "mem.cgroup_working_set", t("telemetry.trend.workingSet"), "avg", !shares, name),
    ...columnsOf(series, "mem.cgroup_working_set", t("telemetry.trend.workingSetPeak"), "max", false, name),
    ...columnsOf(series, "mem.heap_used", t("telemetry.trend.heapUsed"), "avg", !hasWorkingSet, name),
    ...columnsOf(series, "mem.old_gen_peak", t("telemetry.trend.oldGenPeak"), "max", false, name),
  ];
}

/** Storage: the database and each schema, all drawn (bytes). */
export function storageColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  return [
    ...columnsOf(series, "db.size", t("telemetry.trend.database"), "avg", true, (label) => label),
    ...series.filter((s) => s.metric === "db.schema.size").map((s) => toColumn(s, s.label, "avg", true)),
  ];
}

/** Requests: the all-routes total, p50 and p95 drawn (milliseconds), the counts beside them. */
export function requestsColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  // Requests merge every serving role (`role` null), so the all-routes total is one series per metric.
  const total = series.filter((s) => s.label === "*");
  const name = namer(total, t);
  return [
    ...columnsOf(total, "http.p50", t("telemetry.trend.p50"), "avg", true, name),
    ...columnsOf(total, "http.p95", t("telemetry.trend.p95"), "avg", true, name),
    ...columnsOf(total, "http.count", t("telemetry.trend.requests"), "avg", false, name),
    ...columnsOf(total, "http.5xx", t("telemetry.trend.serverErrors"), "avg", false, name),
    ...columnsOf(total, "http.429", t("telemetry.trend.rateLimited"), "avg", false, name),
  ];
}

/** JVM: CPU (percent of the available processors) drawn per role; threads, GC time and the pool beside it. */
export function jvmColumns(series: readonly TelemetrySeries[], t: TFunction): TrendColumn[] {
  const name = namer(series, t);
  const gc = [...new Set(series.filter((s) => s.metric === "jvm.gc_time_ms").map((s) => s.role))].flatMap((role) => {
    const parts = series.filter((s) => s.metric === "jvm.gc_time_ms" && s.role === role);
    const column = summed(parts, `jvm.gc_time_ms:${role ?? "all"}:sum`, name(t("telemetry.trend.gcTime"), role));
    return column === null ? [] : [column];
  });
  return [
    ...columnsOf(series, "jvm.cpu_ratio", t("telemetry.trend.cpu"), "avg", true, name),
    ...columnsOf(series, "jvm.cpu_ratio", t("telemetry.trend.cpuPeak"), "max", false, name),
    ...columnsOf(series, "jvm.threads", t("telemetry.trend.threads"), "avg", false, name),
    ...gc,
    ...columnsOf(series, "pool.acquired", t("telemetry.trend.poolAcquired"), "max", false, name),
    ...columnsOf(series, "pool.pending", t("telemetry.trend.poolPending"), "max", false, name),
  ];
}

/** One connection's job trend: sync (with the queue beside it) or DERIVE; the mean duration is drawn. */
export function jobColumns(
  series: readonly TelemetrySeries[],
  connectionId: number,
  kind: "sync" | "derive",
  t: TFunction,
): TrendColumn[] {
  const own = series.filter((s) => s.connectionId === connectionId);
  const of = (metric: string) => own.find((s) => s.metric === `${kind}.${metric}`);
  const runs = of("runs");
  const failures = of("failures");
  const duration = of("duration_ms");
  const parts: TrendColumn[] = [
    ...(duration === undefined ? [] : [toColumn(duration, t("telemetry.trend.durationMean"), "avg", true)]),
    ...(duration === undefined ? [] : [toColumn(duration, t("telemetry.trend.durationLongest"), "max", false)]),
    ...(runs === undefined ? [] : [toColumn(runs, t("telemetry.trend.runs"), "avg", false)]),
    ...(failures === undefined ? zeros(runs, `${kind}.failures:${connectionId}:zero`, t("telemetry.trend.failures")) : [toColumn(failures, t("telemetry.trend.failures"), "avg", false)]),
  ];
  if (kind === "derive") {
    const rows = of("rows_written");
    return [...parts, ...(rows === undefined ? [] : [toColumn(rows, t("telemetry.trend.rowsWritten"), "avg", false)])];
  }
  const limited = of("rate_limited");
  return [
    ...parts,
    ...(limited === undefined ? zeros(runs, `sync.rate_limited:${connectionId}:zero`, t("telemetry.trend.rateLimitedJira")) : [toColumn(limited, t("telemetry.trend.rateLimitedJira"), "avg", false)]),
    ...columnsOf(series, "queue.pending", t("telemetry.trend.queuePending"), "max", false, (label) => label),
    ...columnsOf(series, "queue.running", t("telemetry.trend.queueRunning"), "max", false, (label) => label),
  ];
}

/** The buckets any column has a figure for, ascending. */
export function trendTimes(columns: readonly TrendColumn[]): number[] {
  const times = new Set<number>();
  for (const column of columns) for (const t of column.values.keys()) times.add(t);
  return [...times].sort((a, b) => a - b);
}

/** One recharts row per bucket: the formatted time and `s<i>` for the i-th plotted column (absent = a gap). */
export function chartRows(plotted: readonly TrendColumn[], times: readonly number[]): Record<string, string | number | undefined>[] {
  return times.map((time) => {
    const row: Record<string, string | number | undefined> = { label: formatDateTime(time) };
    plotted.forEach((column, index) => {
      row[`s${index}`] = column.values.get(time);
    });
    return row;
  });
}
