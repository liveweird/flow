import { describe, expect, test } from "vitest";
import type { Distribution } from "../api/reports";
import { distributionPeople, histogramRowsWithShares } from "./distributionPeople";

const SPLIT: Distribution = {
  n: 6,
  hidden: false,
  histogram: [
    { from: 0, to: 1, count: 4 },
    { from: 1, to: 2, count: 1 },
    { from: 2, to: null, count: 1 },
  ],
  byUser: [
    { accountId: "acc-bob", label: "Bob", counts: [3, 0, 1] },
    { accountId: "acc-ann", label: "Ann", counts: [1, 1, 0] },
  ],
};

describe("distributionPeople", () => {
  test("numbers the people from 1 in byUser (request) order", () => {
    expect(distributionPeople(SPLIT)).toEqual([
      { accountId: "acc-bob", label: "Bob", position: 1 },
      { accountId: "acc-ann", label: "Ann", position: 2 },
    ]);
  });

  test("a distribution without a split has no people", () => {
    expect(distributionPeople({ ...SPLIT, byUser: undefined })).toEqual([]);
    expect(distributionPeople({ n: 2, hidden: true, histogram: [] })).toEqual([]);
  });
});

describe("histogramRowsWithShares", () => {
  test("each bucket carries one count per person, the open tail bucket included", () => {
    expect(histogramRowsWithShares(SPLIT, ["0 – 1", "1 – 2", "≥ 2"])).toEqual([
      { id: "0", label: "0 – 1", count: 4, open: false, shares: [3, 1] },
      { id: "1", label: "1 – 2", count: 1, open: false, shares: [0, 1] },
      { id: "2", label: "≥ 2", count: 1, open: true, shares: [1, 0] },
    ]);
  });

  test("without a split the rows have no shares at all", () => {
    const rows = histogramRowsWithShares({ ...SPLIT, byUser: undefined }, ["a", "b", "c"]);
    expect(rows.map((row) => row.shares)).toEqual([undefined, undefined, undefined]);
    expect(rows.every((row) => !("shares" in row))).toBe(true);
  });
});
