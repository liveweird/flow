import { describe, expect, test } from "vitest";
import { TASK_CODE } from "../test/taskCodeFixtures";
import {
  TASK_CODE_AFTER_ANCHOR,
  TASK_CODE_LEAD_ANCHOR,
  TASK_CODE_TASKS_ANCHOR,
  TASK_CODE_VIEWS,
  taskCodeAnchor,
  taskCodeDrillCount,
  taskCodeViewOf,
} from "./taskCodeDrill";

describe("Tasks and code's drill targets", () => {
  test("a count view is known by its server name only; a histogram view and a stray name are not counts", () => {
    expect(Object.keys(TASK_CODE_VIEWS)).toEqual(["TASKS_WITH_MR", "TASKS_WITHOUT_MR", "MERGED_AFTER_DONE"]);
    expect(taskCodeViewOf("MERGED_AFTER_DONE")).toBe("MERGED_AFTER_DONE");
    expect(taskCodeViewOf("CODE_LEAD_TIME")).toBeUndefined();
    expect(taskCodeViewOf("tasks_with_mr")).toBeUndefined();
  });

  test("each count is the shown report's own figure, which the drawer compares with the server's recomputed bar", () => {
    expect(taskCodeDrillCount(TASK_CODE, "TASKS_WITH_MR")).toBe(24);
    expect(taskCodeDrillCount(TASK_CODE, "TASKS_WITHOUT_MR")).toBe(16);
    expect(taskCodeDrillCount(TASK_CODE, "MERGED_AFTER_DONE")).toBe(6);
  });

  test("focus returns to the card a target belongs to", () => {
    expect(taskCodeAnchor({ kind: "taskcode", view: "TASKS_WITH_MR" })).toBe(TASK_CODE_TASKS_ANCHOR);
    expect(taskCodeAnchor({ kind: "taskcode", view: "TASKS_WITHOUT_MR" })).toBe(TASK_CODE_TASKS_ANCHOR);
    expect(taskCodeAnchor({ kind: "taskcode", view: "MERGED_AFTER_DONE" })).toBe(TASK_CODE_AFTER_ANCHOR);
    expect(taskCodeAnchor({ kind: "hist", view: "CODE_LEAD_TIME", bucketIndex: 1 })).toBe(TASK_CODE_LEAD_ANCHOR);
    expect(taskCodeAnchor({ kind: "hist", view: "MERGED_AFTER_DONE_LAG", bucketIndex: 0 })).toBe(TASK_CODE_AFTER_ANCHOR);
    expect(taskCodeAnchor({ kind: "bucket", bucketStart: "2026-03-02" })).toBe(TASK_CODE_TASKS_ANCHOR);
  });
});
