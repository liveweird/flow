import { describe, expect, test } from "vitest";
import { backlogRows, roundSprints, sprintsGap } from "./backlogReport";
import { BACKLOG, BACKLOG_NO_VELOCITY, BACKLOG_ZERO_VELOCITY } from "../test/reportFixtures";

describe("backlogRows", () => {
  test("one row per trend day, oldest first, as the API lists them", () => {
    expect(backlogRows(BACKLOG.trend)).toEqual([
      { day: "2026-09-27", md: 21, items: 10 },
      { day: "2026-09-28", md: 23.5, items: 11 },
      { day: "2026-09-29", md: 25, items: 12 },
    ]);
  });
});

describe("sprintsGap", () => {
  test("there is no gap while the figure exists", () => {
    expect(sprintsGap(BACKLOG.current)).toBeNull();
  });

  test("no mean is a missing velocity; a mean of exactly zero is its own reason", () => {
    expect(sprintsGap(BACKLOG_NO_VELOCITY.current)).toBe("noVelocity");
    expect(sprintsGap(BACKLOG_ZERO_VELOCITY.current)).toBe("zeroVelocity");
  });
});

describe("roundSprints", () => {
  test("one decimal", () => {
    expect(String(roundSprints(2.5))).toBe("2.5");
    expect(String(roundSprints(3.125))).toBe("3.1");
    expect(String(roundSprints(1))).toBe("1");
  });
});
