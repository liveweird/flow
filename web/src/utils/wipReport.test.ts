import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import {
  DEFAULT_HIDDEN_BANDS,
  paintBands,
  paintPeople,
  wipBands,
  wipBandSummary,
  wipChartRows,
  wipPeople,
  wipPeopleChartRows,
  wipPersonSummary,
  wipPersonTotal,
} from "./wipReport";
import { WIP_COLUMN, WIP_STAGE, WIP_STATUS, WIP_USER } from "../test/reportFixtures";

const t = i18n.t.bind(i18n);

describe("wipBands and paintBands", () => {
  test("stages are translated and keep the app's colours whatever their position", () => {
    const bands = wipBands("STAGE", WIP_STAGE.keys, t);
    expect(paintBands("STAGE", bands).map((b) => [b.name, b.label, b.color])).toEqual([
      ["b0", "Not started", "gray.6"],
      ["b1", "In progress", "flow.6"],
      ["b2", "Waiting", "grape.6"],
      ["b3", "Done", "teal.8"],
      ["b4", "Unmapped status", "orange.8"],
    ]);
  });

  test("an unknown stage falls back to the server's label and the neutral cycle", () => {
    const bands = wipBands("STAGE", [{ key: "MYSTERY", label: "Mystery" }], t);
    expect(paintBands("STAGE", bands)[0]).toMatchObject({ label: "Mystery", color: "flow.6" });
  });

  test("statuses and columns walk the neutral blue/gray cycle by position among the bands SHOWN, so neighbours never share a hue", () => {
    const keys = Array.from({ length: 6 }, (_, i) => ({ key: `s${i}`, label: `Status ${i}` }));
    const bands = wipBands("STATUS", keys, t);
    const colors = paintBands("STATUS", bands).map((b) => b.color);
    expect(colors).toEqual(["flow.6", "gray.6", "flow.7", "gray.6", "flow.6", "gray.6"]);
    // Hiding the first two re-colours the rest by their new position — still alternating hues.
    const remaining = paintBands("STATUS", bands.slice(2)).map((b) => b.color);
    expect(remaining).toEqual(["flow.6", "gray.6", "flow.7", "gray.6"]);
    for (const list of [colors, remaining]) {
      for (let i = 1; i < list.length; i++) expect(list[i].split(".")[0]).not.toBe(list[i - 1].split(".")[0]);
    }
    // No semantic hue is ever lent to an arbitrary status name.
    expect(colors.join()).not.toMatch(/teal|orange|red/);
    expect(wipBands("STATUS", WIP_STATUS.keys, t).map((b) => b.label)).toEqual(["To Do", "In Progress", "Done"]);
  });

  test("the no-column key is translated, a real column keeps its own name, and data names never carry the key", () => {
    const bands = wipBands("COLUMN", [...WIP_COLUMN.keys, { key: "a.b c", label: "a.b c" }], t);
    expect(bands.map((b) => b.label)).toEqual(["Backlog", "Doing", "(no column)", "a.b c"]);
    expect(bands.map((b) => b.name)).toEqual(["b0", "b1", "b2", "b3"]);
  });
});

describe("wipChartRows and wipBandSummary", () => {
  const bands = wipBands("STAGE", WIP_STAGE.keys, t).filter((b) => b.key === "IN_PROGRESS");

  test("rows carry the day and the ticked bands' counts under their data names", () => {
    expect(wipChartRows(WIP_STAGE.series.slice(3), bands)).toEqual([
      { day: "2026-09-28", b1: 8 },
      { day: "2026-09-29", b1: 9 },
    ]);
  });

  test("a key a point lacks reads as zero", () => {
    expect(wipChartRows([{ day: "2026-09-29", isWorkingDay: true, counts: {} }], bands)).toEqual([{ day: "2026-09-29", b1: 0 }]);
  });

  test("the summary is the latest day, the mean to one decimal and the peak", () => {
    expect(wipBandSummary(WIP_STAGE.series, "NOT_STARTED")).toEqual({ latest: 38, average: "39.4", peak: 40 });
    expect(wipBandSummary(WIP_STAGE.series, "IN_PROGRESS")).toEqual({ latest: 9, average: "7", peak: 9 });
    expect(wipBandSummary([], "IN_PROGRESS")).toEqual({ latest: 0, average: "0", peak: 0 });
  });

  test("WAITING is a visible band by default: only Not started and Done start hidden", () => {
    expect(DEFAULT_HIDDEN_BANDS.STAGE).not.toContain("WAITING");
  });

  test("only the stage keying starts with bands hidden", () => {
    expect(DEFAULT_HIDDEN_BANDS).toEqual({ STAGE: ["NOT_STARTED", "DONE"], STATUS: [], COLUMN: [] });
  });
});

describe("the per-person view of a USER-level series", () => {
  const WORK = ["IN_PROGRESS", "WAITING"];

  test("the people are the first point's byUser, numbered in request order; none below USER level", () => {
    expect(wipPeople(WIP_USER.series)).toEqual([
      { accountId: "acc-ann", label: "Ann Author", position: 1, name: "p0" },
      { accountId: "acc-bob", label: "Bob Builder", position: 2, name: "p1" },
    ]);
    expect(wipPeople(WIP_STAGE.series)).toEqual([]);
    expect(wipPeople([])).toEqual([]);
  });

  test("they are painted on the neutral cycle by position and named with their number", () => {
    const painted = paintPeople(wipPeople(WIP_USER.series));
    expect(painted.map((p) => [p.name, p.label, p.color])).toEqual([
      ["p0", "1. Ann Author", "flow.6"],
      ["p1", "2. Bob Builder", "gray.6"],
    ]);
  });

  test("a person's total is the sum of the ticked bands' counts; the rows and the summary follow", () => {
    const [monday, tuesday] = WIP_USER.series;
    expect(wipPersonTotal(monday, "acc-ann", WORK)).toBe(2);
    expect(wipPersonTotal(tuesday, "acc-bob", WORK)).toBe(2);
    expect(wipPersonTotal(tuesday, "acc-nobody", WORK)).toBe(0);
    expect(wipPersonTotal(WIP_STAGE.series[0], "acc-ann", WORK)).toBe(0);
    expect(wipPeopleChartRows(WIP_USER.series, wipPeople(WIP_USER.series), WORK)).toEqual([
      { day: "2026-09-28", p0: 2, p1: 2 },
      { day: "2026-09-29", p0: 4, p1: 2 },
    ]);
    expect(wipPersonSummary(WIP_USER.series, "acc-ann", WORK)).toEqual({ latest: 4, average: "3", peak: 4 });
    expect(wipPersonSummary([], "acc-ann", WORK)).toEqual({ latest: 0, average: "0", peak: 0 });
  });
});
