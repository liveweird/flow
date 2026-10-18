import { describe, expect, test } from "vitest";
import { HOME_DRILL_VIEWS } from "../api/home";
import { HOME, HOME_NOT_DERIVED, previous } from "../test/homeFixtures";
import {
  drillAnchorOf,
  drillDrawn,
  HOME_VIEWS,
  nothingDerived,
  overviewLink,
  sprintBucketItems,
  sprintRows,
  teamFigureRows,
  isEpicView,
  teamBarsHeight,
  trendOf,
  windowDays,
  windowFilter,
  wipRows,
} from "./homeOverview";

const [alpha, beta, gamma] = HOME.teams;

describe("trendOf", () => {
  test("no previous figure, no trend", () => {
    expect(trendOf(null)).toBeNull();
  });

  test("the server's verdict gives the tone and the VALUE's direction gives the arrow (lower can be better)", () => {
    expect(trendOf(previous(4, 10, "BETTER", -0.5))).toEqual({ tone: "better", direction: "down" });
    expect(trendOf(previous(4, 9, "WORSE", 1.5))).toEqual({ tone: "worse", direction: "up" });
  });

  test("a tie within the tolerance is 'same': compared, nothing to report", () => {
    expect(trendOf(previous(4.1, 8, null, -0.1))).toEqual({ tone: "same", direction: "down" });
    expect(trendOf(previous(2, 7, null, 0))).toEqual({ tone: "same", direction: "flat" });
  });

  test("a side that is hidden or missing cannot be compared: no tone, no arrow", () => {
    expect(trendOf(previous(null, 2, null, null, true))).toEqual({ tone: "none", direction: null });
    expect(trendOf(previous(null, 0, null, null))).toEqual({ tone: "none", direction: null });
  });
});

describe("figure rows", () => {
  test("one row per team in the overview's order; a PRED row counts the items WITHIN the share, a median its sample", () => {
    const pred = teamFigureRows(HOME.teams, "TASK_PRED25", (team) => team.accuracy.tasks.pred25);
    expect(pred.map((row) => [row.team, row.value, row.n, row.count])).toEqual([
      ["Alpha", 0.6, 10, 6],
      ["Beta", 0.75, 8, 6],
      ["Gamma", null, 2, 2],
    ]);
    const estimate = teamFigureRows(HOME.teams, "TASK_ESTIMATE", (team) => team.granularity.estimateMd);
    expect(estimate.map((row) => [row.value, row.n, row.count, row.hidden])).toEqual([
      [3.5, 12, 12, false],
      [2, 8, 8, false],
      [null, 2, 2, true],
    ]);
    expect(estimate[0].trend).toEqual({ tone: "better", direction: "down" });
    expect(estimate[0].previous?.value).toBe(4);
  });

  test("a figure with no trend carries none (the epic findings)", () => {
    const rows = teamFigureRows(HOME.teams, "QUALITY_EPICS_NO_ESTIMATE", (team) => team.quality.epicsNoEstimate);
    expect(rows.every((row) => row.previous === null && row.trend === null)).toBe(true);
  });

  test("WIP rows: the count is never hidden, a percentile is hidden under the minimum sample, zero items is just no age", () => {
    const count = wipRows(HOME.teams, "WIP_TASKS", (team) => team.wipTasks, (wip) => wip.count);
    expect(count.map((row) => [row.value, row.hidden, row.count])).toEqual([[6, false, 6], [3, false, 3], [1, false, 1]]);
    const p50 = wipRows(HOME.teams, "WIP_TASKS", (team) => team.wipTasks, (wip) => wip.p50);
    expect(p50.map((row) => [row.value, row.hidden, row.count, row.trend])).toEqual([[4, false, 6, null], [2, false, 3, null], [null, true, 1, null]]);
    const epics = wipRows(HOME.teams, "WIP_EPICS", (team) => team.wipEpics, (wip) => wip.p95);
    expect(epics.map((row) => [row.value, row.hidden, row.count])).toEqual([[null, true, 2], [null, false, 0], [null, false, 0]]);
  });
});

describe("sprintRows", () => {
  test("a row per team with a closed sprint, in the overview's order, with the capacity and the frozen figures of a drifted sprint", () => {
    const rows = sprintRows(HOME.teams);
    expect(rows.map((row) => [row.id, row.team, row.sprint, row.capacityMd, row.drift])).toEqual([
      ["1:12", "Alpha", "Alpha 2", 22, true],
      ["2:21", "Beta", "Beta 1", null, false],
    ]);
    expect(rows[0].frozen).toEqual({ committedMd: 20, finalMd: 22, deliveredMd: 16.5 });
    expect(rows[1].frozen).toBeNull();
  });

  test("the items behind a sprint bar; a bucket Home draws no bar for has none", () => {
    const row = sprintRows(HOME.teams)[0];
    expect(sprintBucketItems(row, "COMMITTED")).toBe(8);
    expect(sprintBucketItems(row, "FINAL")).toBe(10);
    expect(sprintBucketItems(row, "DELIVERED")).toBe(7);
    expect(sprintBucketItems(row, "DROPPED")).toBeUndefined();
  });
});

describe("the drill", () => {
  test.each(HOME_DRILL_VIEWS)("%s has a list shape and a count the overview can compare the list with", (view) => {
    const spec = HOME_VIEWS[view];
    expect(spec.unit).toBeTruthy();
    expect(Number.isInteger(spec.count(alpha))).toBe(true);
  });

  test("the count is the figure the list reconciles with: a PRED's items within, a median's sample, a WIP's count", () => {
    expect(HOME_VIEWS.TASK_PRED10.count(alpha)).toBe(3);
    expect(HOME_VIEWS.EPIC_PRED25.count(beta)).toBe(3);
    expect(HOME_VIEWS.TASK_CYCLE.count(alpha)).toBe(11);
    expect(HOME_VIEWS.WIP_EPICS.count(alpha)).toBe(2);
    expect(HOME_VIEWS.QUALITY_TASKS_NO_ESTIMATE.count(alpha)).toBe(3);
    expect(HOME_VIEWS.TASK_COST.count(gamma)).toBe(2);
  });

  test("focus returns to the card of the section a bar belongs to", () => {
    expect(drillAnchorOf({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "FINAL" })).toBe("home-sprints");
    expect(drillAnchorOf({ kind: "week", teamId: 1, bucketStart: "2026-07-06" })).toBe("home-throughput");
    expect(drillAnchorOf({ kind: "home", view: "TASK_COST", teamId: 1 })).toBe("home-granularity");
    expect(drillAnchorOf({ kind: "home", view: "EPIC_PRED50", teamId: 1 })).toBe("home-accuracy");
    expect(drillAnchorOf({ kind: "home", view: "WIP_EPICS", teamId: 1 })).toBe("home-wipEpics");
    expect(drillAnchorOf({ kind: "home", view: "WIP_TASKS", teamId: 1 })).toBe("home-wipTasks");
    expect(drillAnchorOf({ kind: "home", view: "QUALITY_EPICS_NO_DATES", teamId: 1 })).toBe("home-quality");
    expect(drillAnchorOf({ kind: "bucket", bucketStart: "2026-07-06" })).toBe("home-sprints");
  });

  test("a target is drawn only when the overview has its team (and sprint, and week); other kinds are never drawn", () => {
    expect(drillDrawn({ kind: "home", view: "TASK_CYCLE", teamId: 3 }, HOME)).toBe(true);
    expect(drillDrawn({ kind: "home", view: "TASK_CYCLE", teamId: 99 }, HOME)).toBe(false);
    expect(drillDrawn({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "FINAL" }, HOME)).toBe(true);
    expect(drillDrawn({ kind: "sprint", teamId: 1, sprintId: 11, bucket: "FINAL" }, HOME)).toBe(false);
    expect(drillDrawn({ kind: "sprint", teamId: 3, sprintId: 12, bucket: "FINAL" }, HOME)).toBe(false);
    expect(drillDrawn({ kind: "week", teamId: 1, bucketStart: "2026-07-06" }, HOME)).toBe(true);
    expect(drillDrawn({ kind: "week", teamId: 1, bucketStart: "2026-07-07" }, HOME)).toBe(false);
    expect(drillDrawn({ kind: "week", teamId: 9, bucketStart: "2026-07-06" }, HOME)).toBe(false);
    expect(drillDrawn({ kind: "hist", view: "WORKING_DAYS", bucketIndex: 1 }, HOME)).toBe(false);
  });
});

describe("state and links", () => {
  test("nothing derived is the server's null derivedAt", () => {
    expect(nothingDerived(HOME)).toBe(false);
    expect(nothingDerived(HOME_NOT_DERIVED)).toBe(true);
  });

  test("the window is the period every link carries; extra page params ride along; a drill never follows a link", () => {
    expect(windowFilter(HOME)).toEqual({ from: "2026-07-02", to: "2026-09-29" });
    expect(overviewLink("/reports/aging-wip", windowFilter(HOME), { kind: "epics" })).toBe(
      "/reports/aging-wip?from=2026-07-02&to=2026-09-29&kind=epics",
    );
    expect(overviewLink("/reports/commitments", { lastSprints: 1 })).toBe("/reports/commitments?lastSprints=1");
    // A page-local param belongs to its own page only.
    expect(overviewLink("/reports/data-quality", windowFilter(HOME), { kind: "epics" })).toBe("/reports/data-quality?from=2026-07-02&to=2026-09-29");
  });
});

describe("copy helpers", () => {
  test("the window length is the server's own inclusive span", () => {
    expect(windowDays(HOME.window)).toBe(90);
    expect(windowDays({ from: "2026-03-01", to: "2026-03-01" })).toBe(1);
    expect(windowDays({ from: "2026-02-01", to: "2026-03-01" })).toBe(29);
  });

  test("epic views count epics, every other view tasks", () => {
    expect(["EPIC_PRED25", "WIP_EPICS", "QUALITY_EPICS_NO_DATES"].map((view) => isEpicView(view as never))).toEqual([true, true, true]);
    expect(["TASK_PRED25", "WIP_TASKS", "QUALITY_TASKS_UNASSIGNED"].map((view) => isEpicView(view as never))).toEqual([false, false, false]);
  });

  test("a chart is as tall as its teams need, never shorter than the minimum", () => {
    expect(teamBarsHeight(1)).toBe(110);
    expect(teamBarsHeight(10)).toBe(356);
  });
});
