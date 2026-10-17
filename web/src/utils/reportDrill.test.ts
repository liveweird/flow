import { describe, expect, test } from "vitest";
import type { ReportDrillItem } from "../api/reportDrill";
import i18n from "../i18n";
import { drillItemColumns, parseDrill, serializeDrill, withSegment, type DrillTarget } from "./reportDrill";

describe("the drill param codec", () => {
  test.each<[string, DrillTarget, string]>([
    ["a bucket", { kind: "bucket", bucketStart: "2026-03-02" }, "bucket:2026-03-02"],
    [
      "one person's segment of a bucket (Jira ids carry colons)",
      { kind: "bucket", bucketStart: "2026-03-02", segmentAccountId: "712020:abc-def" },
      "bucket:2026-03-02;s=712020:abc-def",
    ],
    ["a sprint's bucket", { kind: "sprint", teamId: 4, sprintId: 123, bucket: "COMMITTED" }, "sprint:4:123:COMMITTED"],
    [
      "one person's segment of a sprint's bucket",
      { kind: "sprint", teamId: 4, sprintId: 123, bucket: "CARRIED_OVER", segmentAccountId: "712020:abc-def" },
      "sprint:4:123:CARRIED_OVER;s=712020:abc-def",
    ],
  ])("%s round-trips", (_name, target, spelled) => {
    expect(serializeDrill(target)).toBe(spelled);
    expect(parseDrill(spelled)).toEqual(target);
  });

  test.each([
    ["absent", null],
    ["empty", ""],
    ["an unknown kind", "pie:123:COMMITTED"],
    ["a sprint without a bucket", "sprint:4:123"],
    ["a sprint without its team (the pre-team spelling)", "sprint:123:COMMITTED"],
    ["a sprint with an unknown bucket", "sprint:4:123:ALL"],
    ["a sprint bucket in the wrong case", "sprint:4:123:committed"],
    ["a sprint id that is no whole number", "sprint:4:12.5:COMMITTED"],
    ["a sprint id of zero", "sprint:4:0:COMMITTED"],
    ["a sprint id in exponent form", "sprint:4:1e3:COMMITTED"],
    ["a team id that is no whole number", "sprint:x:123:COMMITTED"],
    ["a team id of zero", "sprint:0:123:COMMITTED"],
    ["a sprint with a stray part", "sprint:4:123:COMMITTED:extra"],
    ["no identity", "bucket:"],
    ["a date that is no date", "bucket:2026-02-30"],
    ["a date in the wrong shape", "bucket:03/02/2026"],
    ["a stray part", "bucket:2026-03-02:extra"],
    ["an empty segment", "bucket:2026-03-02;s="],
  ])("%s parses to nothing (a hand-edited link is ignored, never an error)", (_name, raw) => {
    expect(parseDrill(raw)).toBeNull();
  });

  test("withSegment sets and clears the segment without touching the bucket", () => {
    const whole: DrillTarget = { kind: "bucket", bucketStart: "2026-03-02" };
    const person = withSegment(whole, "acc-bob");
    expect(person).toEqual({ kind: "bucket", bucketStart: "2026-03-02", segmentAccountId: "acc-bob" });
    expect(withSegment(person, undefined)).toEqual(whole);
    expect("segmentAccountId" in withSegment(person, undefined)).toBe(false);
  });

  test("withSegment works on a sprint target too", () => {
    const whole: DrillTarget = { kind: "sprint", teamId: 2, sprintId: 7, bucket: "FINAL" };
    const person = withSegment(whole, "acc-bob");
    expect(person).toEqual({ kind: "sprint", teamId: 2, sprintId: 7, bucket: "FINAL", segmentAccountId: "acc-bob" });
    expect(withSegment(person, undefined)).toEqual(whole);
  });
});

describe("drillItemColumns", () => {
  const t = i18n.t.bind(i18n);
  const item: ReportDrillItem = {
    issueKey: "FLO-1",
    summary: null,
    itemKind: "TASK",
    accountId: null,
    assignee: null,
    teamId: null,
    anchorAt: Date.UTC(2026, 2, 2, 23, 30),
    figure: null,
  };

  test("headers are the issue, summary, assignee, the done date and the MD figure", () => {
    expect(drillItemColumns(t, "md", "done", "UTC").map((c) => c.header)).toEqual(["Issue", "Summary", "Assignee", "Done", "Delivered (MD)"]);
  });

  test("a sprint drill's estimate and completion columns", () => {
    const columns = drillItemColumns(t, "estimateMd", "completed", "UTC");
    expect(columns.map((c) => c.header)).toEqual(["Issue", "Summary", "Assignee", "Sprint completed", "Estimate (MD)"]);
    expect(columns.map((c) => c.render({ ...item, figure: 3 }))).toEqual(["FLO-1", "—", "Unassigned", "2026-03-02", "3"]);
    expect(columns[3].render({ ...item, anchorAt: null })).toBe("—");
  });

  test("missing values print as a dash or 'Unassigned', the date is read in the configured zone, MD has no trailing zeros", () => {
    const cells = (row: ReportDrillItem, zone = "UTC") => drillItemColumns(t, "md", "done", zone).map((c) => c.render(row));
    expect(cells(item)).toEqual(["FLO-1", "—", "Unassigned", "2026-03-02", "—"]);
    // 23:30 UTC is already the next day in Warsaw.
    expect(cells(item, "Europe/Warsaw")[3]).toBe("2026-03-03");
    expect(cells({ ...item, summary: "Ship it", assignee: "Ann", figure: 2.5 })).toEqual(["FLO-1", "Ship it", "Ann", "2026-03-02", "2.5"]);
    expect(cells({ ...item, anchorAt: null })[3]).toBe("—");
  });
});
