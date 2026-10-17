import { describe, expect, it } from "vitest";
import { sprintLabel } from "./sprintLabel";

const DAY = 86_400_000;
const START = Date.UTC(2026, 9, 5);

describe("sprintLabel", () => {
  it("is just the name for a sprint without a start date", () => {
    expect(sprintLabel({ name: "FLO Sprint 9", startAt: null, endAt: START + 14 * DAY, completeAt: null }, "UTC")).toBe("FLO Sprint 9");
  });

  it("shows `name · from → to`, the last day being the one holding the final millisecond", () => {
    // Ends at midnight after Sunday the 18th: the label says the 18th, not the 19th.
    expect(sprintLabel({ name: "FLO Sprint 9", startAt: START, endAt: START + 14 * DAY, completeAt: START + 13 * DAY }, "UTC")).toBe(
      "FLO Sprint 9 · 2026-10-05 → 2026-10-18",
    );
  });

  it("falls back to the completion date where the planned end is absent, and to a dash with neither", () => {
    expect(sprintLabel({ name: "FLO Sprint 9", startAt: START, completeAt: START + 14 * DAY }, "UTC")).toBe(
      "FLO Sprint 9 · 2026-10-05 → 2026-10-18",
    );
    expect(sprintLabel({ name: "FLO Sprint 9", startAt: START, endAt: null, completeAt: null }, "UTC")).toBe("FLO Sprint 9 · 2026-10-05 → —");
  });

  it("reads the dates in the given zone", () => {
    expect(sprintLabel({ name: "S", startAt: Date.UTC(2026, 9, 5, 23, 0), endAt: Date.UTC(2026, 9, 19, 23, 0), completeAt: null }, "Europe/Warsaw")).toBe(
      "S · 2026-10-06 → 2026-10-20",
    );
  });
});
