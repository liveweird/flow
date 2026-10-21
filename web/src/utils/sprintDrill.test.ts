import { describe, expect, test } from "vitest";
import type { CommitmentsSprint, ReportFilters, SprintConsistencySprint, ThroughputSprint } from "../api/reports";
import type { SprintBucket } from "./reportDrill";
import {
  commitmentsDrillCount,
  commitmentsHitTarget,
  consistencyDrillCount,
  consistencyHitTarget,
  homeHitTarget,
  sprintDrillFilter,
  sprintLabeller,
  sprintOfRow,
  sprintTarget,
  throughputSprintDrillCount,
} from "./sprintDrill";

describe("the sprint drill helpers", () => {
  test("a row id names its team and sprint (`<teamId>:<sprintId>`), anything else names none", () => {
    expect(sprintOfRow("1:12")).toEqual({ teamId: 1, sprintId: 12 });
    for (const bad of ["", "12", "1:", ":12", "1:abc", "1:0", "0:12", "1:-3", "1:1.5", "1:2:3"]) expect(sprintOfRow(bad)).toBeNull();
  });

  test("a target carries the team, the sprint, the bucket and, when given, the person's segment", () => {
    expect(sprintTarget("1:12", "COMMITTED")).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "COMMITTED" });
    expect(sprintTarget("1:12", "DELIVERED", "acc-bob")).toEqual({
      kind: "sprint",
      teamId: 1,
      sprintId: 12,
      bucket: "DELIVERED",
      segmentAccountId: "acc-bob",
    });
    expect(sprintTarget("nope", "FINAL")).toBeNull();
  });

  test("the drill filter narrows the period to the one sprint and keeps everything else", () => {
    const filter = { lastSprints: 6, from: "2026-01-01", to: "2026-02-01", teamId: 3, accountIds: ["a", "b"], connectionId: 7, activityType: "Bug" };
    expect(sprintDrillFilter(filter, { teamId: 3, sprintId: 12 })).toEqual({
      sprintId: 12,
      teamId: 3,
      accountIds: ["a", "b"],
      connectionId: 7,
      activityType: "Bug",
    });
    expect(filter.lastSprints).toBe(6);
  });

  test("the drill filter pins the bar's own team, also on a unit-wide page that has none (Home)", () => {
    expect(sprintDrillFilter({ lastSprints: 1 }, { teamId: 5, sprintId: 12 })).toEqual({ sprintId: 12, teamId: 5 });
  });
});

const USERS = [
  { accountId: "acc-ann", committedKey: "c0", finalKey: "f0" },
  { accountId: "acc-bob", committedKey: "c1", finalKey: "f1" },
];

describe("what a chart hit opens", () => {
  test("Commitments: the initial bar is the committed scope, the final bar the final scope", () => {
    expect(commitmentsHitTarget({ rowId: "1:12", series: "initialMd" }, [])).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "COMMITTED" });
    expect(commitmentsHitTarget({ rowId: "1:12", series: "finalMd" }, [])).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "FINAL" });
    expect(commitmentsHitTarget({ rowId: "1:12", series: "capacityMd" }, [])).toBeNull();
    expect(commitmentsHitTarget({ rowId: "nope", series: "initialMd" }, [])).toBeNull();
  });

  test("Commitments, user mode: a person's committed segment and final segment carry that person", () => {
    expect(commitmentsHitTarget({ rowId: "1:12", series: "c1" }, USERS)).toEqual({
      kind: "sprint",
      teamId: 1,
      sprintId: 12,
      bucket: "COMMITTED",
      segmentAccountId: "acc-bob",
    });
    expect(commitmentsHitTarget({ rowId: "1:12", series: "f0" }, USERS)).toEqual({
      kind: "sprint",
      teamId: 1,
      sprintId: 12,
      bucket: "FINAL",
      segmentAccountId: "acc-ann",
    });
  });

  test("Home: initial, final and delivered; anything else opens nothing", () => {
    expect(homeHitTarget({ rowId: "1:12", series: "deliveredMd" })).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "DELIVERED" });
    expect(homeHitTarget({ rowId: "1:12", series: "initialMd" })).toMatchObject({ bucket: "COMMITTED" });
    expect(homeHitTarget({ rowId: "1:12", series: "finalMd" })).toMatchObject({ bucket: "FINAL" });
    expect(homeHitTarget({ rowId: "1:12", series: "constructor" })).toBeNull();
  });

  test("Sprint consistency: every chart series is its own bucket; FINAL is drawn by none, anything else opens nothing", () => {
    const bucketOf = (series: string) => (consistencyHitTarget({ rowId: "1:12", series }) as { bucket?: string } | null)?.bucket;
    expect(
      ["committedMd", "deliveredMd", "carriedOverMd", "droppedMd", "addedMd", "removedMd"].map(bucketOf),
    ).toEqual(["COMMITTED", "DELIVERED", "CARRIED_OVER", "DROPPED", "ADDED", "REMOVED"]);
    expect(consistencyHitTarget({ rowId: "2:30", series: "addedMd" })).toEqual({ kind: "sprint", teamId: 2, sprintId: 30, bucket: "ADDED" });
    expect(bucketOf("finalMd")).toBeUndefined();
    expect(consistencyHitTarget({ rowId: "nope", series: "addedMd" })).toBeNull();
  });
});

describe("the chart figure a drill is compared with", () => {
  const commitments = [
    {
      sprintId: 12,
      teamId: 1,
      initialItems: 8,
      finalItems: 9,
      byUser: [{ accountId: "acc-ann", initialItems: 5, finalItems: 6 }],
    },
  ] as unknown as CommitmentsSprint[];
  const sprint = (bucket: "COMMITTED" | "FINAL" | "DELIVERED" | "ADDED", segmentAccountId?: string) =>
    ({ kind: "sprint", teamId: 1, sprintId: 12, bucket, segmentAccountId }) as const;

  test("Commitments: the sprint's committed or final count, or one person's share of it", () => {
    expect(commitmentsDrillCount(commitments, sprint("COMMITTED"))).toBe(8);
    expect(commitmentsDrillCount(commitments, sprint("FINAL"))).toBe(9);
    expect(commitmentsDrillCount(commitments, sprint("FINAL", "acc-ann"))).toBe(6);
    expect(commitmentsDrillCount(commitments, sprint("FINAL", "acc-zed"))).toBeUndefined();
    expect(commitmentsDrillCount(commitments, sprint("ADDED"))).toBeUndefined();
    expect(commitmentsDrillCount(commitments, { ...sprint("FINAL"), sprintId: 99 })).toBeUndefined();
    expect(commitmentsDrillCount(commitments, { ...sprint("FINAL"), teamId: 2 })).toBeUndefined();
    expect(commitmentsDrillCount(undefined, sprint("FINAL"))).toBeUndefined();
  });

  test("Throughput: the delivered count of the sprint or of one person's share", () => {
    const throughput = [{ sprintId: 12, teamId: 1, deliveredItems: 7, byUser: [{ accountId: "acc-ann", deliveredItems: 3 }] }] as unknown as ThroughputSprint[];
    expect(throughputSprintDrillCount(throughput, sprint("DELIVERED"))).toBe(7);
    expect(throughputSprintDrillCount(throughput, sprint("DELIVERED", "acc-ann"))).toBe(3);
    expect(throughputSprintDrillCount(throughput, sprint("DELIVERED", "acc-zed"))).toBeUndefined();
    expect(throughputSprintDrillCount(throughput, sprint("COMMITTED"))).toBeUndefined();
    expect(throughputSprintDrillCount(undefined, sprint("DELIVERED"))).toBeUndefined();
  });
});

describe("the chart figure of a by-epic segment", () => {
  const commitments = [
    {
      sprintId: 12,
      teamId: 1,
      initialItems: 8,
      finalItems: 9,
      byUser: [],
      byEpic: [
        { key: "101", initialItems: 3, finalItems: 4 },
        { key: "OTHER_EPICS", initialItems: 2, finalItems: 2 },
        { key: "NO_EPIC", initialItems: 3, finalItems: 3 },
      ],
    },
  ] as unknown as CommitmentsSprint[];
  const throughput = [
    { sprintId: 12, teamId: 1, deliveredItems: 7, byUser: [], byEpic: [{ key: "101", deliveredItems: 5 }, { key: "NO_EPIC", deliveredItems: 2 }] },
  ] as unknown as ThroughputSprint[];
  const epic = (bucket: SprintBucket, epicSegment: string) => ({ kind: "sprint", teamId: 1, sprintId: 12, bucket, epicSegment }) as const;

  test("Commitments: the segment's own initial or final count, not the sprint's", () => {
    expect(commitmentsDrillCount(commitments, epic("COMMITTED", "101"))).toBe(3);
    expect(commitmentsDrillCount(commitments, epic("FINAL", "101"))).toBe(4);
    expect(commitmentsDrillCount(commitments, epic("FINAL", "OTHER_EPICS"))).toBe(2);
    expect(commitmentsDrillCount(commitments, epic("COMMITTED", "NO_EPIC"))).toBe(3);
    expect(commitmentsDrillCount(commitments, epic("COMMITTED", "999"))).toBeUndefined();
  });

  test("Throughput: the segment's delivered count", () => {
    expect(throughputSprintDrillCount(throughput, epic("DELIVERED", "101"))).toBe(5);
    expect(throughputSprintDrillCount(throughput, epic("DELIVERED", "NO_EPIC"))).toBe(2);
    expect(throughputSprintDrillCount(throughput, epic("DELIVERED", "999"))).toBeUndefined();
  });
});

describe("Sprint consistency's drill count", () => {
  const sprints = [
    { sprintId: 12, teamId: 1, committedItems: 1, addedItems: 2, removedItems: 3, finalItems: 4, deliveredItems: 5, carriedOverItems: 6, droppedItems: 7 },
  ] as unknown as SprintConsistencySprint[];
  const target = (bucket: SprintBucket, sprintId = 12, teamId = 1) => ({ kind: "sprint", teamId, sprintId, bucket }) as const;

  test("each bucket reads its own item count of the sprint", () => {
    const buckets: SprintBucket[] = ["COMMITTED", "ADDED", "REMOVED", "FINAL", "DELIVERED", "CARRIED_OVER", "DROPPED"];
    expect(buckets.map((bucket) => consistencyDrillCount(sprints, target(bucket)))).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  test("an unknown sprint or no report has no figure", () => {
    expect(consistencyDrillCount(sprints, target("FINAL", 99))).toBeUndefined();
    expect(consistencyDrillCount(sprints, target("FINAL", 12, 2))).toBeUndefined();
    expect(consistencyDrillCount(undefined, target("FINAL"))).toBeUndefined();
  });
});

describe("sprintLabeller", () => {
  const filters = { teams: [{ id: 1, name: "Alpha" }, { id: 2, name: "Beta" }] } as unknown as ReportFilters;
  const identity = (sprintId: number, teamId: number, name: string) => ({ sprintId, teamId, name, completedAt: sprintId });

  test("a sprint is labelled as the chart labels it, with its team only where several teams share the chart", () => {
    expect(sprintLabeller([identity(12, 1, "Alpha 2")], filters)({ teamId: 1, sprintId: 12 })).toBe("Alpha 2");
    const both = sprintLabeller([identity(12, 1, "Alpha 2"), identity(21, 2, "Beta 1")], filters);
    const label = (teamId: number, sprintId: number) => both({ teamId, sprintId });
    expect([label(1, 12), label(2, 21), label(1, 99)]).toEqual(["Alpha · Alpha 2", "Beta · Beta 1", undefined]);
  });

  test("two teams carrying the same sprint id are labelled apart", () => {
    const shared = sprintLabeller([identity(12, 1, "Alpha 2"), { ...identity(12, 2, "Alpha 2"), completedAt: 99 }], filters);
    expect([shared({ teamId: 1, sprintId: 12 }), shared({ teamId: 2, sprintId: 12 })]).toEqual(["Alpha · Alpha 2", "Beta · Alpha 2"]);
  });

  test("without the report or the reference data nothing is labelled", () => {
    expect(sprintLabeller(undefined, filters)({ teamId: 1, sprintId: 12 })).toBeUndefined();
    expect(sprintLabeller([identity(12, 1, "Alpha 2")], undefined)({ teamId: 1, sprintId: 12 })).toBeUndefined();
  });
});
