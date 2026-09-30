import { describe, expect, test } from "vitest";
import { FILTERS } from "../test/reportFixtures";
import { labelledSprints, sortSprints } from "./reportSprints";

const sprint = (over: Partial<Parameters<typeof sortSprints>[0][number]> & { name?: string; teamId?: number }) => ({
  sprintId: 1,
  name: "S",
  teamId: 1,
  completedAt: 1000 as number | null,
  ...over,
});

describe("reportSprints", () => {
  test("sorts by completion, an open sprint last, ties by id", () => {
    expect(
      sortSprints([sprint({ sprintId: 3, completedAt: null }), sprint({ sprintId: 9, completedAt: 5 }), sprint({ sprintId: 2, completedAt: 5 })]).map(
        (s) => s.sprintId,
      ),
    ).toEqual([2, 9, 3]);
  });

  test("labels carry the team only when several teams share the chart", () => {
    expect(labelledSprints([sprint({ name: "A" })], FILTERS).map((l) => l.label)).toEqual(["A"]);
    expect(
      labelledSprints([sprint({ sprintId: 1, name: "A", teamId: 1 }), sprint({ sprintId: 2, name: "B", teamId: 2 })], FILTERS).map(
        (l) => l.label,
      ),
    ).toEqual(["Alpha · A", "Beta · B"]);
  });
});
