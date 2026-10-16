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
  ])("%s round-trips", (_name, target, spelled) => {
    expect(serializeDrill(target)).toBe(spelled);
    expect(parseDrill(spelled)).toEqual(target);
  });

  test.each([
    ["absent", null],
    ["empty", ""],
    ["an unknown kind", "sprint:123:COMMITTED"],
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

  test("missing values print as a dash or 'Unassigned', the date is read in the configured zone, MD has no trailing zeros", () => {
    const cells = (row: ReportDrillItem, zone = "UTC") => drillItemColumns(t, "md", "done", zone).map((c) => c.render(row));
    expect(cells(item)).toEqual(["FLO-1", "—", "Unassigned", "2026-03-02", "—"]);
    // 23:30 UTC is already the next day in Warsaw.
    expect(cells(item, "Europe/Warsaw")[3]).toBe("2026-03-03");
    expect(cells({ ...item, summary: "Ship it", assignee: "Ann", figure: 2.5 })).toEqual(["FLO-1", "Ship it", "Ann", "2026-03-02", "2.5"]);
    expect(cells({ ...item, anchorAt: null })[3]).toBe("—");
  });
});
