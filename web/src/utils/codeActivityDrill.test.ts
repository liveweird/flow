import { describe, expect, test } from "vitest";
import { CODE_VIEWS, codeDrillCount, codeViewOf, HISTOGRAM_VIEWS, MEMBER_CELLS, memberName } from "./codeActivityDrill";
import { ANN, BOB, CODE_ACTIVITY } from "../test/codeActivityFixtures";

describe("codeViewOf", () => {
  test("knows the figure views and nothing else - a histogram view is a bar, never a code target", () => {
    expect(codeViewOf("COMMITS")).toBe("COMMITS");
    expect(codeViewOf("WEEK")).toBe("WEEK");
    expect(codeViewOf("MR_SIZE_LINES")).toBeUndefined();
    expect(codeViewOf("commits")).toBeUndefined();
    expect(Object.keys(CODE_VIEWS)).toHaveLength(13);
    expect(HISTOGRAM_VIEWS).toHaveLength(5);
  });
});

describe("codeDrillCount", () => {
  test("a member's cell is the group's own count of that figure", () => {
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "COMMITS", segmentAccountId: "acc-ann" })).toBe(ANN.commits);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "OPEN_MRS", segmentAccountId: "acc-ann" })).toBe(ANN.openMrs);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "COMMENTS", segmentAccountId: "acc-bob" })).toBe(BOB.comments);
    for (const [, cell] of Object.entries(MEMBER_CELLS)) {
      expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: cell.view, segmentAccountId: "acc-ann" })).toBe(cell.count(ANN));
    }
  });

  test("a person the report does not list, or a view that is no member cell, has no figure", () => {
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "COMMITS", segmentAccountId: "acc-zed" })).toBeUndefined();
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "WEEK", segmentAccountId: "acc-ann" })).toBeUndefined();
  });

  test("the team's tiles and weeks are the flow's and the open tile's counts; a week it did not draw has none", () => {
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "MRS_MERGED" })).toBe(40);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "MERGED_WITHOUT_REVIEW" })).toBe(4);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "MERGED_WITHOUT_APPROVAL" })).toBe(9);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "OPEN_MRS_WAITING" })).toBe(6);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "WEEK", bucketStart: "2026-09-21" })).toBe(28);
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "WEEK", bucketStart: "2026-01-05" })).toBeUndefined();
    // A member view with no person is not a figure of the page.
    expect(codeDrillCount(CODE_ACTIVITY, { kind: "code", view: "COMMITS" })).toBeUndefined();
  });
});

describe("memberName", () => {
  test("the group's label, else the roster's display name, else the account id", () => {
    const roster = [{ accountId: "acc-cy", displayName: "Cy Coder" }];
    expect(memberName(CODE_ACTIVITY, roster, "acc-ann")).toBe("Ann Author");
    expect(memberName({ ...CODE_ACTIVITY, groups: [{ ...ANN, accountId: "acc-cy", label: undefined }] }, roster, "acc-cy")).toBe("Cy Coder");
    expect(memberName(CODE_ACTIVITY, roster, "acc-zed")).toBe("acc-zed");
  });
});
