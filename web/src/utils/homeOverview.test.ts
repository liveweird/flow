import { describe, expect, test } from "vitest";
import {
  agingSummary,
  nothingDerived,
  overviewLink,
  overviewPeriod,
  overviewTeamRows,
  qualityHighlights,
} from "./homeOverview";
import {
  AGING_EMPTY,
  AGING_HIDDEN,
  AGING_TRUNCATED,
  AGING_UNIT,
  DATA_QUALITY,
  DATA_QUALITY_CLEAN,
  CONSISTENCY_UNIT,
} from "../test/reportFixtures";
import { todayIsoDate } from "./isoDate";
import { presetRange } from "./reportFilter";

describe("overviewTeamRows", () => {
  test("one row per team's last sprint: initial = committed, final, delivered, the drift flag and the frozen figures", () => {
    expect(overviewTeamRows(CONSISTENCY_UNIT)).toEqual([
      {
        key: "1-12",
        team: "Alpha",
        sprint: "Alpha 2",
        completedAt: Date.UTC(2026, 8, 10),
        initialMd: 20,
        finalMd: 24.5,
        deliveredMd: 18,
        drift: true,
        frozen: { committedMd: 20, finalMd: 22, deliveredMd: 16.5 },
      },
      {
        key: "2-21",
        team: "Beta",
        sprint: "Beta 1",
        completedAt: Date.UTC(2026, 8, 1),
        initialMd: 10,
        finalMd: 10,
        deliveredMd: 10,
        drift: false,
        frozen: null,
      },
    ]);
  });

  test("a team the groups do not name reads #id, and rows sort by team name", () => {
    const report = {
      ...CONSISTENCY_UNIT,
      sprints: [...CONSISTENCY_UNIT.sprints, { ...CONSISTENCY_UNIT.sprints[0], sprintId: 99, teamId: 7, name: "Zed 1" }],
    };
    expect(overviewTeamRows(report).map((r) => r.team)).toEqual(["#7", "Alpha", "Beta"]);
  });
});

describe("agingSummary", () => {
  test("counts tasks only: past p85 includes the ones past p95, the server's band is what counts", () => {
    expect(agingSummary(AGING_UNIT)).toEqual({
      wip: 4,
      pastOrange: { percentile: 85, count: 2 },
      pastRed: { percentile: 95, count: 1 },
      hidden: false,
      atLeast: false,
    });
  });

  test("hidden thresholds keep the WIP count and flag the missing bands; a truncated list means at least", () => {
    expect(agingSummary(AGING_HIDDEN)).toMatchObject({ wip: 4, pastOrange: { count: 0 }, pastRed: { count: 0 }, hidden: true });
    expect(agingSummary(AGING_TRUNCATED).atLeast).toBe(true);
    expect(agingSummary(AGING_EMPTY).wip).toBe(0);
  });

  test("the WIP total counts waiting tasks too (A30: the server lists in-progress and waiting items)", () => {
    expect(AGING_UNIT.items.filter((item) => item.itemKind === "TASK" && item.waiting)).toHaveLength(1);
    expect(agingSummary(AGING_UNIT).wip).toBe(AGING_UNIT.items.filter((item) => item.itemKind === "TASK").length);
    expect(agingSummary({ ...AGING_UNIT, items: AGING_UNIT.items.filter((item) => item.waiting) }).wip).toBe(1);
  });

  test("a single configured percentile is the top (red) threshold and there is no orange one", () => {
    const only85 = {
      ...AGING_UNIT,
      thresholds: { ...AGING_UNIT.thresholds, percentiles: [{ percentile: 85, workingDays: 15 }] },
      items: [{ ...AGING_UNIT.items[2], band: "P85" }],
    };
    expect(agingSummary(only85)).toMatchObject({ wip: 1, pastOrange: null, pastRed: { percentile: 85, count: 1 } });
  });
});

describe("qualityHighlights", () => {
  test("configuration kinds first, then the volume kinds, each by count (ties in the page's order)", () => {
    const highlights = qualityHighlights(DATA_QUALITY);
    expect(highlights.slice(0, 7)).toEqual([
      { card: "domainsNoOwner", count: 1 },
      { card: "unmappedStatuses", count: 1 },
      { card: "unmappedBoards", count: 1 },
      { card: "deriveWarnings", count: 1 },
      { card: "noEpic", count: 7 },
      { card: "coverage", count: 4 },
      { card: "noEstimate", count: 4 },
    ]);
    expect(highlights.every((h) => h.count > 0)).toBe(true);
    expect(highlights).toHaveLength(18);
    // Issues above the epic level are a notice about the model, not a mapping to fix: a volume kind.
    expect(highlights.find((h) => h.card === "itemsAboveEpic")).toEqual({ card: "itemsAboveEpic", count: 1 });
  });

  test("a big configuration count still sorts among the configuration kinds by size", () => {
    const report = { ...DATA_QUALITY, unmappedStatuses: { total: 9, items: [] } };
    expect(qualityHighlights(report).slice(0, 2).map((h) => h.card)).toEqual(["unmappedStatuses", "domainsNoOwner"]);
  });

  test("a work-category finding is ignored where no field is configured; a clean report lists nothing", () => {
    const unconfigured = { ...DATA_QUALITY, missing: { ...DATA_QUALITY.missing, workCategoryConfigured: false } };
    expect(qualityHighlights(unconfigured).some((h) => h.card === "noWorkCategory")).toBe(false);
    expect(qualityHighlights(DATA_QUALITY_CLEAN)).toEqual([]);
  });
});

describe("nothingDerived", () => {
  const derived = CONSISTENCY_UNIT.meta;
  const empty = { ...derived, derivedAt: null };

  test("only when everything settled, something answered, and every answer says not derived", () => {
    expect(nothingDerived([empty, empty], false, false)).toBe(true);
    expect(nothingDerived([empty, undefined], false, false)).toBe(true);
    expect(nothingDerived([empty, derived], false, false)).toBe(false);
    expect(nothingDerived([empty, empty], true, false)).toBe(false);
    expect(nothingDerived([undefined, undefined], false, false)).toBe(false);
  });

  test("a failure keeps the grid, so the empty state never hides it", () => {
    expect(nothingDerived([empty, undefined], false, true)).toBe(false);
  });
});

describe("overviewPeriod and overviewLink", () => {
  const meta = CONSISTENCY_UNIT.meta;

  test("the period is the first from/to meta", () => {
    expect(overviewPeriod("Europe/Warsaw", undefined, meta)).toEqual({ from: "2026-07-02", to: "2026-09-29" });
  });

  test("without a from/to meta (none answered, or a sprint-relative one) it is the local trailing 90 days — never none", () => {
    expect(overviewPeriod(undefined)).toEqual(presetRange("last90", todayIsoDate()));
    expect(overviewPeriod("Pacific/Kiritimati", { ...meta, from: null, to: null }, undefined)).toEqual(
      presetRange("last90", todayIsoDate("Pacific/Kiritimati")),
    );
  });

  test("links serialize the filter canonically", () => {
    expect(overviewLink("/reports/velocity", { lastSprints: 1 })).toBe("/reports/velocity?lastSprints=1");
    expect(overviewLink("/reports/cycle-time", { from: "2026-07-02", to: "2026-09-29" })).toBe(
      "/reports/cycle-time?from=2026-07-02&to=2026-09-29",
    );
  });
});
