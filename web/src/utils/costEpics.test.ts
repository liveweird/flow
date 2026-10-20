import { describe, expect, test } from "vitest";
import { epicColumnName, epicDrillKey, epicHeading, epicRowName } from "./costEpics";
import { COST_MATRIX_UNIT } from "../test/reportFixtures";
import i18n from "../i18n";

const t = i18n.t.bind(i18n);
const { rows, columns } = COST_MATRIX_UNIT.byEpic;

describe("the epic table's words", () => {
  test("a row is named by its key and summary, the fold by its size, the epic-less work by its own words", () => {
    expect(rows.map((row) => epicRowName(t, row))).toEqual(["FLO-10 Checkout", "FLO-11 Search", "Other epics (2)", "No epic"]);
    expect(epicRowName(t, { ...rows[0], issueKey: null, summary: null })).toBe("10");
  });

  test("a row drills with its epic id or the fixed word of its kind", () => {
    expect(rows.map(epicDrillKey)).toEqual(["10", "11", "OTHER_EPICS", "NO_EPIC"]);
  });

  test("a column is a team (UNIT, the unassigned one by its shared label) or an author (the no-author bucket by its own words)", () => {
    expect(columns.map((column) => epicColumnName(t, column, "UNIT"))).toEqual(["Alpha", "Old team", "Unassigned"]);
    expect(epicColumnName(t, { ...columns[0], accountId: "acc-ann", label: null }, "TEAM")).toBe("acc-ann");
    expect(epicColumnName(t, { ...columns[0], label: null }, "TEAM")).toBe("No known author");
  });
});

describe("epicHeading", () => {
  const heading = (target: Parameters<typeof epicHeading>[1], level: "UNIT" | "TEAM" | "USER" = "UNIT") => epicHeading(t, target, COST_MATRIX_UNIT.byEpic, level);

  test("the row is the title and the column the subtitle; a Total has none", () => {
    expect(heading({ kind: "epic", keys: ["10"], teamId: 1 })).toEqual({ title: "FLO-10 Checkout", subtitle: "Alpha" });
    expect(heading({ kind: "epic", keys: ["10"] })).toEqual({ title: "FLO-10 Checkout", subtitle: undefined });
    expect(heading({ kind: "epic", keys: ["OTHER_EPICS"], teamId: 0 })).toEqual({ title: "Other epics (2)", subtitle: "Unassigned" });
  });

  test("a link naming a row or column the report does not list falls back to what the URL spells", () => {
    expect(heading({ kind: "epic", keys: ["99", "NO_EPIC"], teamId: 9 })).toEqual({ title: "99, No epic", subtitle: undefined });
    expect(heading({ kind: "epic", keys: ["10"], teamId: 1, segmentAccountId: "acc-x" }, "TEAM")).toEqual({ title: "FLO-10 Checkout", subtitle: "acc-x" });
  });
});
