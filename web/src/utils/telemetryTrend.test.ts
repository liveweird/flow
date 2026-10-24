import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import i18n from "../i18n";
import { GENERATED_AT, MB, seriesFixture } from "../test/telemetryFixtures";
import {
  chartRows,
  formatTrendValue,
  jobColumns,
  jvmColumns,
  memoryColumns,
  requestsColumns,
  storageColumns,
  trendTimes,
} from "./telemetryTrend";

const t = i18n.t.bind(i18n) as TFunction;

describe("telemetryTrend", () => {
  test("memory plots the working set per role and tells roles apart only when there are several", () => {
    const one = memoryColumns([seriesFixture("mem.cgroup_working_set", [100 * MB, 200 * MB]), seriesFixture("mem.heap_used", [50 * MB])], t, new Map());
    expect(one.map((c) => [c.label, c.plot])).toEqual([
      ["Working set (mean)", true],
      ["Working set (peak)", false],
      ["Heap used (mean)", false],
    ]);
    const two = memoryColumns(
      [
        seriesFixture("mem.cgroup_working_set", [1], { role: "web" }),
        seriesFixture("mem.cgroup_working_set", [2], { role: "worker" }),
      ],
      t,
      new Map(),
    );
    expect(two.filter((c) => c.plot).map((c) => c.label)).toEqual(["Working set (mean) (Web)", "Working set (mean) (Worker)"]);
    // The peak column reads the bucket's largest sample (1.5 x the mean in the fixture).
    expect(two.find((c) => c.label === "Working set (peak) (Web)")?.values.get(GENERATED_AT - 3_600_000)).toBe(1.5);
  });

  test("without a container the heap is what memory draws", () => {
    const columns = memoryColumns([seriesFixture("mem.heap_used", [10])], t, new Map());
    expect(columns.filter((c) => c.plot).map((c) => c.label)).toEqual(["Heap used (mean)"]);
  });

  test("with a container limit for every role the DRAWN memory line is the share of it, and the bytes stay in the table", () => {
    const ws = [seriesFixture("mem.cgroup_working_set", [384 * MB], { role: "web" }), seriesFixture("mem.cgroup_working_set", [256 * MB], { role: "worker" })];
    const columns = memoryColumns(ws, t, new Map([["web", 768 * MB], ["worker", 512 * MB]]));
    const plotted = columns.filter((c) => c.plot);
    expect(plotted.map((c) => [c.label, c.unit])).toEqual([["Share of the limit (mean) (Web)", "PERCENT"], ["Share of the limit (mean) (Worker)", "PERCENT"]]);
    expect([...plotted[0]!.values.values()]).toEqual([50]);
    expect([...plotted[1]!.values.values()]).toEqual([50]);
    // The peak share is the largest sample over the limit (1.5 x in the fixture); the bytes columns follow, undrawn.
    expect([...columns.find((c) => c.label === "Share of the limit (peak) (Web)")!.values.values()]).toEqual([75]);
    expect(columns.find((c) => c.label === "Working set (mean) (Web)")).toMatchObject({ unit: "BYTES", plot: false });
    // One role without a limit and the bytes are drawn for both (one axis, one unit).
    const partial = memoryColumns(ws, t, new Map([["web", 768 * MB]]));
    expect(partial.filter((c) => c.plot).map((c) => c.unit)).toEqual(["BYTES", "BYTES"]);
    expect(partial.some((c) => c.label.startsWith("Share of the limit"))).toBe(false);
  });

  test("storage draws the database and every schema", () => {
    const columns = storageColumns(
      [
        seriesFixture("db.size", [5], { role: null }),
        seriesFixture("db.schema.size", [3], { role: null, label: "raw" }),
        seriesFixture("conn.issues", [3], { role: null, label: "5", unit: "COUNT" }),
      ],
      t,
    );
    expect(columns.map((c) => c.label)).toEqual(["Database", "raw"]);
    expect(columns.every((c) => c.plot)).toBe(true);
  });

  test("requests keep the all-routes series: p50 and p95 drawn, the counts beside them", () => {
    // Requests merge every serving role: their series carry no role.
    const columns = requestsColumns(
      [
        seriesFixture("http.p50", [10], { label: "*", role: null, unit: "MILLISECONDS", kind: "PERCENTILE" }),
        seriesFixture("http.p95", [90], { label: "*", role: null, unit: "MILLISECONDS", kind: "PERCENTILE" }),
        seriesFixture("http.count", [7], { label: "*", role: null, unit: "COUNT", kind: "DELTA" }),
        seriesFixture("http.p95", [400], { label: "/api/v1/reports/home", role: null, unit: "MILLISECONDS", kind: "PERCENTILE" }),
      ],
      t,
    );
    expect(columns.map((c) => [c.label, c.plot])).toEqual([["p50", true], ["p95", true], ["Requests", false]]);
  });

  test("jvm shows CPU as a percentage and sums GC time over the collectors of a role", () => {
    const columns = jvmColumns(
      [
        seriesFixture("jvm.cpu_ratio", [0.25], { unit: "RATIO" }),
        seriesFixture("jvm.gc_time_ms", [10, 20], { unit: "MILLISECONDS", kind: "DELTA", label: "G1 Young" }),
        seriesFixture("jvm.gc_time_ms", [5, 5], { unit: "MILLISECONDS", kind: "DELTA", label: "G1 Old" }),
        seriesFixture("jvm.threads", [40], { unit: "COUNT" }),
      ],
      t,
    );
    const cpu = columns.find((c) => c.label === "CPU (mean)");
    expect(cpu?.unit).toBe("PERCENT");
    expect([...(cpu?.values.values() ?? [])]).toEqual([25]);
    expect(cpu?.plot).toBe(true);
    const gc = columns.find((c) => c.label === "GC time");
    expect([...(gc?.values.values() ?? [])]).toEqual([15, 25]);
    expect(columns.some((c) => c.label === "Threads (mean)")).toBe(true);
  });

  test("a connection's sync trend draws the mean duration and shows zero for the series the server left out", () => {
    const own = { connectionId: 5, role: null, label: "5" };
    const columns = jobColumns(
      [
        seriesFixture("sync.runs", [2, 3], { ...own, unit: "COUNT", kind: "DELTA" }),
        seriesFixture("sync.duration_ms", [1000, 2000], { ...own, unit: "MILLISECONDS" }),
        seriesFixture("sync.runs", [9], { connectionId: 6, role: null, label: "6", unit: "COUNT", kind: "DELTA" }),
        seriesFixture("queue.pending", [1], { role: null, unit: "COUNT" }),
      ],
      5,
      "sync",
      t,
    );
    expect(columns.filter((c) => c.plot).map((c) => c.label)).toEqual(["Duration (mean)"]);
    const failures = columns.find((c) => c.label === "Failures");
    expect([...(failures?.values.values() ?? [])]).toEqual([0, 0]);
    expect(columns.find((c) => c.label === "Jira 429s")).toBeDefined();
    expect(columns.find((c) => c.label === "Queue pending (peak)")).toBeDefined();
    expect(columns.find((c) => c.label === "Runs")?.values.size).toBe(2);
  });

  test("a DERIVE trend adds the rows written and has no queue", () => {
    const own = { connectionId: 5, role: null, label: "5" };
    const columns = jobColumns(
      [
        seriesFixture("derive.duration_ms", [1000], { ...own, unit: "MILLISECONDS" }),
        seriesFixture("derive.failures", [1], { ...own, unit: "COUNT", kind: "DELTA" }),
        seriesFixture("derive.rows_written", [10], { ...own, unit: "COUNT", kind: "DELTA" }),
      ],
      5,
      "derive",
      t,
    );
    expect(columns.map((c) => c.label)).toEqual(["Duration (mean)", "Duration (longest)", "Failures", "Rows written"]);
    expect(jobColumns([], 5, "derive", t)).toEqual([]);
  });

  test("times are the union of the columns' buckets, and chart rows leave a gap where a column has none", () => {
    const a = seriesFixture("db.size", [1, 2], { role: null });
    const b = seriesFixture("db.schema.size", [9], { role: null, label: "raw" });
    const columns = storageColumns([a, b], t);
    const times = trendTimes(columns);
    expect(times).toHaveLength(2);
    const rows = chartRows(columns, times);
    expect(rows[0]).toMatchObject({ s0: 1, s1: undefined });
    expect(rows[1]).toMatchObject({ s0: 2, s1: 9 });
    expect(rows[1]?.label).toBe("2026-10-24 11:00");
  });

  test("values format by unit", () => {
    expect(formatTrendValue("BYTES", 1536)).toBe("1.5 KB");
    expect(formatTrendValue("MILLISECONDS", 1500)).toBe("1.5 s");
    expect(formatTrendValue("PERCENT", 12.34)).toBe("12.3%");
    expect(formatTrendValue("COUNT", 12.4)).toBe("12");
  });
});
