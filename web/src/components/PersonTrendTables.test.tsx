import { describe, expect, test } from "vitest";
import { PersonTrendFigureTable } from "./PersonTrendTables";
import { PERSON_TREND, PERSON_TREND_TEAM } from "../test/personTrendFixtures";
import { renderWithProviders, screen, within } from "../test/render";
import { chartPointsOf } from "../utils/personTrendReport";
import type { PersonTrendReport } from "../api/reports";

const cells = (row: HTMLElement) => Array.from(row.querySelectorAll("th, td")).map((cell) => cell.textContent);

function table(report: PersonTrendReport, keys: string[], labels: string[]) {
  const rows = keys.map((key) => report.rows.find((row) => row.key === key)!);
  renderWithProviders(
    <PersonTrendFigureTable rows={rows} seriesLabels={labels} pointsOf={(row) => chartPointsOf(report, row)} minSampleSize={5} label="Items: figures by window" />,
  );
  return screen.getByRole("table", { name: "Items: figures by window" });
}

describe("PersonTrendFigureTable", () => {
  test("without a team at any window the table is the figures, the sample and the coverage - no Team and no reference column", () => {
    const el = table(PERSON_TREND, ["deliveredItems"], ["Delivered items"]);
    expect(cells(within(el).getAllByRole("row")[0])).toEqual(["Window ending", "Delivered items", "n", "Window"]);
  });

  test("with a team: a Team column names the team of EACH window (a mover changes team) and the team median sits beside its peers' count; too few peers is a dash with the count, a window with nothing derived has none", () => {
    const el = table(PERSON_TREND_TEAM, ["deliveredItems"], ["Delivered items"]);
    const rows = within(el).getAllByRole("row");
    expect(cells(rows[0])).toEqual(["Window ending", "Team", "Delivered items", "Team median (peers)", "n", "Window"]);
    expect(cells(rows[1])).toEqual(["2026-06-30", "Alpha", "—", "—", "0", "no data derived"]);
    expect(cells(rows[2])).toEqual(["2026-07-14", "Alpha", "4", "5 (4)", "4", "partial"]);
    expect(cells(rows[4])).toEqual(["2026-08-11", "Beta", "7", "— (2)", "7", "complete"]);
    expect(cells(rows[5])).toEqual(["2026-08-25", "Beta", "9", "10 (3)", "9", "complete"]);
  });

  test("the shared cycle table has a team-median column per row, each named by its row, in the row's unit", () => {
    const el = table(PERSON_TREND_TEAM, ["cycleWorkingDaysP50", "cycleWorkingDaysP90"], ["Median", "p90"]);
    const rows = within(el).getAllByRole("row");
    expect(cells(rows[0])).toEqual(["Window ending", "Team", "Median", "p90", "Median: team median (peers)", "p90: team median (peers)", "n", "Window"]);
    expect(cells(rows[3])).toEqual(["2026-07-28", "Alpha", "5.5", "10", "6.5 (5)", "11 (5)", "9", "complete"]);
  });
});
