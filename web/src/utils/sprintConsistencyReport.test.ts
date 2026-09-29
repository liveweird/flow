import { describe, expect, test } from "vitest";
import { CONSISTENCY_UNIT, FILTERS } from "../test/reportFixtures";
import { sprintConsistencyRows } from "./sprintConsistencyReport";

describe("sprintConsistencyRows", () => {
  test("one row per sprint in completion order, carrying the MD figures the charts plot", () => {
    const rows = sprintConsistencyRows(CONSISTENCY_UNIT.sprints, FILTERS);
    expect(rows.map((r) => r.label)).toEqual(["Beta · Beta 1", "Alpha · Alpha 2"]);
    expect(rows[1]).toEqual({
      label: "Alpha · Alpha 2",
      committedMd: 20,
      deliveredMd: 18,
      carriedOverMd: 4,
      droppedMd: 2.5,
      addedMd: 4.5,
      removedMd: 1,
    });
  });
});
