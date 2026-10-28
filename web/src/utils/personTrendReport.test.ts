import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { PERSON_TREND, PERSON_TREND_EMPTY, PERSON_TREND_ROWS, PERSON_TREND_TEAM } from "../test/personTrendFixtures";
import {
  chartPointsOf,
  figureText,
  hasFigures,
  hasReference,
  mixRowsOf,
  normalizePersonTrendFilter,
  pointCountOf,
  pointDrillCount,
  pointNote,
  pointTitle,
  referenceNote,
  reportHasReference,
  PERSON_TREND_CONTROLS,
  TREND_CHARTS,
  trendAnchor,
  trendAnchorOfRow,
  trendOfCue,
} from "./personTrendReport";
import { requiredPeople } from "./reportFilter";

const t = i18n.t.bind(i18n);
const rowOf = (key: string) => PERSON_TREND_ROWS.find((row) => row.key === key)!;

describe("normalizePersonTrendFilter", () => {
  test("drops a team, a single member, a sprint-relative period and another report's keys; keeps ONE person, the dates and the slices", () => {
    expect(
      normalizePersonTrendFilter({
        teamId: 2,
        accountId: "acc-x",
        lastSprints: 3,
        sprintId: 9,
        from: "2026-01-01",
        to: "2026-02-01",
        accountIds: ["acc-ann", "acc-cy"],
        domainView: "EPIC",
        domain: "FLO",
        activityType: "Bug",
        workCategory: "X",
        tailPercent: 10,
        bucket: "MONTH",
        itemKind: "BOTH",
      }),
    ).toEqual({ from: "2026-01-01", to: "2026-02-01", accountIds: ["acc-ann"], domainView: "EPIC", domain: "FLO", activityType: "Bug", workCategory: "X" });
  });

  test("a filter without people stays without", () => {
    expect(normalizePersonTrendFilter({ from: "2026-01-01" })).toEqual({ from: "2026-01-01" });
  });

  test("the page is requested with exactly one person, on a year", () => {
    expect(requiredPeople(PERSON_TREND_CONTROLS)).toBe(1);
    expect(PERSON_TREND_CONTROLS.defaultPeriod).toBe("last365");
  });
});

describe("TREND_CHARTS", () => {
  test("covers every row the server sends exactly once, in the server's order, the two cycle percentiles on one chart", () => {
    expect(TREND_CHARTS.flatMap((chart) => chart.keys)).toEqual(PERSON_TREND_ROWS.map((row) => row.key));
    expect(TREND_CHARTS[0].keys).toEqual(["cycleWorkingDaysP50", "cycleWorkingDaysP90"]);
    expect(TREND_CHARTS.filter((chart) => chart.keys.length === 2)).toHaveLength(1);
    expect(TREND_CHARTS.map((chart) => chart.id)).toEqual(TREND_CHARTS.map((chart) => chart.keys[0]));
  });
});

describe("chartPointsOf", () => {
  test("a window with nothing derived is a GAP whatever the server's figure says (a zero count would be a lie), and a hidden figure is one too", () => {
    const items = chartPointsOf(PERSON_TREND, rowOf("deliveredItems"));
    expect(items.map((point) => point.value)).toEqual([null, 4, 6, 7, 9]);
    expect(items.map((point) => point.coverage)).toEqual(["NONE", "PARTIAL", "FULL", "FULL", "FULL"]);
    expect(chartPointsOf(PERSON_TREND, rowOf("cycleWorkingDaysP90")).map((point) => point.value)).toEqual([null, 11, 10, 9, null]);
    expect(chartPointsOf(PERSON_TREND, rowOf("cycleWorkingDaysP90"))[4]).toMatchObject({ hidden: true, n: 3 });
  });

  test("a figure the server left out (no denominator) is a gap, never a zero", () => {
    expect(chartPointsOf(PERSON_TREND, rowOf("flowEfficiencyP50"))[0].value).toBeNull();
  });

  test("hasFigures is false when no window has anything to draw", () => {
    expect(hasFigures(chartPointsOf(PERSON_TREND, rowOf("deliveredMd")))).toBe(true);
    expect(PERSON_TREND_EMPTY.rows.some((row) => hasFigures(chartPointsOf(PERSON_TREND_EMPTY, row)))).toBe(false);
  });

  test("a window the response lists no coverage for reads as complete", () => {
    expect(chartPointsOf({ ...PERSON_TREND, windows: [] }, rowOf("deliveredMd")).every((point) => point.coverage === "FULL")).toBe(true);
  });
});

describe("the team reference", () => {
  const teamRow = PERSON_TREND_TEAM.rows.find((row) => row.key === "deliveredItems")!;
  const points = chartPointsOf(PERSON_TREND_TEAM, teamRow);

  test("each point carries the team of its window and the team median with its peers; too few peers is a null value, never a zero", () => {
    expect(points.map((point) => point.team?.name)).toEqual(["Alpha", "Alpha", "Alpha", "Beta", "Beta"]);
    expect(points.map((point) => point.reference?.value)).toEqual([undefined, 5, 7, null, 10]);
    expect(points.map((point) => point.reference?.peers)).toEqual([undefined, 4, 5, 2, 3]);
  });

  test("a window with nothing derived drops the reference (it would be the peers' empty-span zero) but keeps its team", () => {
    expect(points[0].coverage).toBe("NONE");
    expect(points[0].reference).toBeUndefined();
    expect(points[0].team).toEqual({ id: 1, name: "Alpha" });
  });

  test("a person with no team at any window has neither a team nor a reference on any point", () => {
    const plain = chartPointsOf(PERSON_TREND, PERSON_TREND_ROWS[0]);
    expect(plain.every((point) => point.team === undefined && point.reference === undefined)).toBe(true);
    expect(hasReference(plain)).toBe(false);
  });

  test("hasReference needs a VALUE to draw: a team that never had enough peers draws nothing", () => {
    expect(hasReference(points)).toBe(true);
    expect(hasReference(points.map((point) => ({ ...point, reference: { value: null, peers: 2 } })))).toBe(false);
  });

  test("reportHasReference: some row has a median to draw; a team without one, or none at all, has not", () => {
    expect(reportHasReference(PERSON_TREND_TEAM)).toBe(true);
    expect(reportHasReference(PERSON_TREND)).toBe(false);
    const tooFew = { ...PERSON_TREND_TEAM, rows: PERSON_TREND_TEAM.rows.map((row) => ({ ...row, points: row.points.map((point) => ({ ...point, reference: { peers: 1 } })) })) };
    expect(reportHasReference(tooFew)).toBe(false);
  });

  test("the tooltip line names the value, the team and the peers; without a value it says why; without a team there is none", () => {
    const format = (value: number) => String(value);
    expect(referenceNote(points[2], format, t)).toBe("Team median 7 (Alpha, 5 peers)");
    expect(referenceNote({ ...points[2], reference: { value: 7, peers: 1 } }, format, t)).toBe("Team median 7 (Alpha, 1 peer)");
    expect(referenceNote(points[3], format, t)).toBe("Team median: too few peers (2) in Beta");
    expect(referenceNote(points[0], format, t)).toBeUndefined();
    expect(referenceNote({ ...points[2], team: undefined }, format, t)).toBeUndefined();
  });
});

describe("pointNote, pointTitle and figureText", () => {
  const points = (key: string) => chartPointsOf(PERSON_TREND, rowOf(key));

  test("a full point has nothing to add; a partial one says so; a NONE window says no data; a hidden one says why", () => {
    expect(pointNote(points("deliveredItems")[3], 5, t)).toBeUndefined();
    expect(pointNote(points("deliveredItems")[1], 5, t)).toBe("partial");
    expect(pointNote(points("deliveredItems")[0], 5, t)).toBe("no data derived");
    expect(pointNote(points("cycleWorkingDaysP90")[4], 5, t)).toBe("below the minimum of 5");
    expect(pointNote(points("flowEfficiencyP50")[1], 5, t)).toBe("partial");
  });

  test("a figure with no denominator in a full window says so", () => {
    const [, , full] = points("taskSizeP50");
    expect(pointNote({ ...full, value: null }, 5, t)).toBe("no figure");
  });

  test("the title is the window end, the sample and the note; a window with nothing derived names no sample", () => {
    const p90 = points("cycleWorkingDaysP90");
    expect(pointTitle(p90[2], 5, t)).toBe("2026-07-28 · n = 9");
    expect(pointTitle(p90[1], 5, t)).toBe("2026-07-14 · n = 8, partial");
    expect(pointTitle(p90[4], 5, t)).toBe("2026-08-25 · n = 3, below the minimum of 5");
    expect(pointTitle(p90[0], 5, t)).toBe("2026-06-30 · no data derived");
  });

  test("the figure is written in the row's unit; a gap is a dash", () => {
    expect(figureText(rowOf("flowEfficiencyP50"), points("flowEfficiencyP50")[3])).toBe("33%");
    expect(figureText(rowOf("cycleWorkingDaysP50"), points("cycleWorkingDaysP50")[3])).toBe("5");
    expect(figureText(rowOf("deliveredItems"), points("deliveredItems")[0])).toBe("—");
  });
});

describe("trendOfCue", () => {
  test("the verdict is the tone and the sign of the change is the arrow", () => {
    const cueOf = (key: string) => trendOfCue(rowOf(key).trend, rowOf(key).tolerance);
    expect(cueOf("cycleWorkingDaysP50")).toEqual({ tone: "better", direction: "down" });
    expect(cueOf("reopenedShare")).toEqual({ tone: "worse", direction: "up" });
  });

  test("no verdict and a change within the tolerance is a TIE ('No change')", () => {
    expect(trendOfCue(rowOf("flowEfficiencyP50").trend, rowOf("flowEfficiencyP50").tolerance)).toEqual({ tone: "same", direction: "up" });
    expect(trendOfCue(rowOf("lateLoggingShare").trend, rowOf("lateLoggingShare").tolerance)).toEqual({ tone: "same", direction: "flat" });
    // Exactly the tolerance is still a tie (the server's own rule).
    expect(trendOfCue({ fromEnd: "2026-01-01", toEnd: "2026-02-01", difference: -0.5 }, 0.5)).toEqual({ tone: "same", direction: "down" });
  });

  test("no verdict and a change BEYOND the tolerance is not a tie: a side is under the minimum sample, so the mark is neutral and says it cannot call", () => {
    expect(trendOfCue(rowOf("blockedItemsShare").trend, rowOf("blockedItemsShare").tolerance)).toEqual({ tone: "none", direction: "down" });
    expect(trendOfCue({ fromEnd: "2026-01-01", toEnd: "2026-02-01", difference: 14 }, 0)).toEqual({ tone: "none", direction: "up" });
  });

  test("no cue, no mark; a cue with neither verdict nor change says nothing", () => {
    expect(trendOfCue(undefined, 0.5)).toBeNull();
    expect(trendOfCue({ fromEnd: "2026-01-01", toEnd: "2026-02-01" }, 0.5)).toEqual({ tone: "none", direction: null });
  });
});

describe("mixRowsOf", () => {
  test("one row per window with s0, s1… the shares; a window that delivered nothing or has no data derived is a gap", () => {
    const rows = mixRowsOf(PERSON_TREND.workMix.activityType, PERSON_TREND.windows);
    expect(rows.map((row) => row.label)).toEqual(PERSON_TREND.windows.map((window) => window.end));
    expect(rows[0]).toEqual({ label: "2026-06-30", s0: null, s1: null });
    expect(rows[3]).toEqual({ label: "2026-08-11", s0: 0.7, s1: 0.3 });
  });

  test("a NONE window is a gap even where the server has a share", () => {
    const series = [{ key: "Story", points: PERSON_TREND.workMix.activityType[0].points.map((point) => ({ ...point, share: 0.5 })) }];
    expect(mixRowsOf(series, PERSON_TREND.windows)[0].s0).toBeNull();
    expect(mixRowsOf(series, PERSON_TREND.windows)[1].s0).toBe(0.5);
  });
});

describe("the drill of a point", () => {
  test("a card's focus-return anchor is its chart's id; the two cycle-time rows share one card, an unknown row falls back to its own key", () => {
    expect(trendAnchor("deliveredItems")).toBe("trend-deliveredItems");
    expect(trendAnchorOfRow("cycleWorkingDaysP90")).toBe("trend-cycleWorkingDaysP50");
    expect(trendAnchorOfRow("reopenedShare")).toBe("trend-reopenedShare");
    expect(trendAnchorOfRow("somethingNew")).toBe("trend-somethingNew");
  });

  test("the drawer's own count is compared with a point only where its n IS the number of listed items", () => {
    expect(pointCountOf(rowOf("deliveredItems"), 7)).toBe(7);
    expect(pointCountOf(rowOf("cycleWorkingDaysP90"), 9)).toBe(9);
    expect(pointCountOf(rowOf("taskSizeP50"), 4)).toBe(4);
    // A share counts its numerator, the coverage its gap, the average WIP its working days, the age the aged tasks of those held.
    for (const key of ["reopenedShare", "blockedItemsShare", "worklogCoverage", "avgWip", "wipAtEndMedianAgeWd"]) {
      expect(pointCountOf(rowOf(key), 9)).toBeUndefined();
    }
    expect(pointCountOf({ drill: undefined }, 9)).toBeUndefined();
  });

  test("a table figure is a button only when items stand behind it: Compare people's rule on the chart point, so a gap, a withheld figure and a zero share are plain text", () => {
    const [none, partial, full] = chartPointsOf(PERSON_TREND, rowOf("deliveredItems"));
    expect(pointDrillCount(rowOf("deliveredItems"), none)).toBe(0);
    expect(pointDrillCount(rowOf("deliveredItems"), partial)).toBe(4);
    expect(pointDrillCount(rowOf("deliveredItems"), full)).toBe(6);
    const p90 = chartPointsOf(PERSON_TREND, rowOf("cycleWorkingDaysP90"));
    expect(pointDrillCount(rowOf("cycleWorkingDaysP90"), p90[4])).toBe(0);
    expect(pointDrillCount(rowOf("reopenedShare"), { label: "x", value: 0, n: 9, hidden: false, coverage: "FULL" })).toBe(0);
    expect(pointDrillCount(rowOf("reopenedShare"), { label: "x", value: 0.1, n: 9, hidden: false, coverage: "FULL" })).toBe(9);
  });
});
