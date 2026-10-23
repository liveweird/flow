import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ThroughputUserTables from "./ThroughputUserTables";
import { renderWithProviders, screen, within } from "../test/render";
import { FILTERS, THROUGHPUT_USER, THROUGHPUT_USERS } from "../test/reportFixtures";
import { showItems } from "../test/drill";
import { throughputBuckets, throughputSprints } from "../utils/throughputReport";

const buckets = throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK");
const sprints = throughputSprints(THROUGHPUT_USERS.bySprint, FILTERS);
const columnTexts = (table: HTMLElement) => within(table).getAllByRole("columnheader").map((header) => header.textContent);
const cellTexts = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));

function renderTables(over: Partial<Parameters<typeof ThroughputUserTables>[0]> = {}) {
  return renderWithProviders(
    <ThroughputUserTables
      groups={THROUGHPUT_USERS.groups}
      buckets={buckets}
      sprints={sprints}
      bucket="WEEK"
      cumulative={false}
      {...over}
    />,
  );
}

describe("ThroughputUserTables", () => {
  test("with two people: the numbered period totals, then each person's share of every week and of every sprint", () => {
    renderTables();
    const totals = screen.getByRole("table", { name: "Totals per person" });
    expect(cellTexts(totals)).toEqual([
      ["1. Ann Author", "10.5", "5"],
      ["2. Bob Builder", "6", "2"],
    ]);
    const periods = screen.getByRole("table", { name: "Each person's delivered scope per period" });
    expect(columnTexts(periods)[0]).toBe("Week starting");
    // a week with nothing delivered stays visible: one row per person per slot, slots in time order
    expect(cellTexts(periods).map((cells) => cells.slice(0, 2))).toEqual([
      ["2026-09-07", "1. Ann Author"],
      ["2026-09-07", "2. Bob Builder"],
      ["2026-09-14", "1. Ann Author"],
      ["2026-09-14", "2. Bob Builder"],
      ["2026-09-21", "1. Ann Author"],
      ["2026-09-21", "2. Bob Builder"],
    ]);
    const sprintTable = screen.getByRole("table", { name: "Each person's delivered scope per sprint" });
    expect(columnTexts(sprintTable)[0]).toBe("Sprint");
    expect(cellTexts(sprintTable)).toHaveLength(4);
    expect(columnTexts(periods)).not.toContain("Cumulative MD");
  });

  test("the month bucket names its slot column, and the cumulative view adds the running totals", () => {
    renderTables({ bucket: "MONTH", cumulative: true });
    const periods = screen.getByRole("table", { name: "Each person's delivered scope per period" });
    expect(columnTexts(periods)[0]).toBe("Month");
    expect(columnTexts(periods).some((header) => /cumulative/i.test(header ?? ""))).toBe(true);
  });

  test("with one person the share tables stay out (the period and sprint tables already are that person's rows)", () => {
    const one = throughputBuckets(THROUGHPUT_USER.byBucket, "WEEK");
    renderTables({ groups: THROUGHPUT_USER.groups, buckets: one, sprints: throughputSprints(THROUGHPUT_USER.bySprint, FILTERS) });
    expect(screen.getAllByRole("table")).toHaveLength(1);
    expect(screen.getByRole("table", { name: "Totals per person" })).toBeInTheDocument();
  });

  test("no groups and no sprints leave only the period shares; an empty sprint series drops the sprint table", () => {
    renderTables({ groups: [], sprints: throughputSprints([], FILTERS) });
    expect(screen.queryByRole("table", { name: "Totals per person" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("table")).toHaveLength(1);
  });

  test("a sprint not yet started has no period people: the numbering falls back to the sprint series; an unlisted account stays bare and none is Unassigned", () => {
    const emptyPeriod = throughputBuckets([], "WEEK");
    renderTables({
      buckets: emptyPeriod,
      groups: [
        { teamId: 1, accountId: "acc-bob", label: "Bob Builder", deliveredMd: 1, deliveredItems: 1 },
        { teamId: 1, accountId: "acc-zed", label: "Zed Zero", deliveredMd: 2, deliveredItems: 2 },
        { teamId: 1, accountId: null, label: null, deliveredMd: 3, deliveredItems: 3 },
      ],
    });
    const totals = screen.getByRole("table", { name: "Totals per person" });
    expect(cellTexts(totals).map((cells) => cells[0])).toEqual(["2. Bob Builder", "Zed Zero", "Unassigned"]);
  });

  test("the share figures open the drills when handlers are given, and are plain text while the data is stale", async () => {
    const onDrillPeriodShare = vi.fn();
    const onDrillSprintShare = vi.fn();
    const { unmount } = renderTables({ onDrillPeriodShare, onDrillSprintShare });
    const periods = screen.getByRole("table", { name: "Each person's delivered scope per period" });
    await userEvent.click(within(periods).getAllByRole("button", { name: showItems("2026-09-07 · 1. Ann Author", "Delivered (MD)") })[0]);
    expect(onDrillPeriodShare).toHaveBeenCalledTimes(1);
    expect(onDrillPeriodShare.mock.calls[0][0]).toMatchObject({ label: "2026-09-07", person: { accountId: "acc-ann" } });
    const sprintTable = screen.getByRole("table", { name: "Each person's delivered scope per sprint" });
    await userEvent.click(within(sprintTable).getAllByRole("button", { name: showItems("Alpha 1 · 2. Bob Builder", "Delivered (items)") })[0]);
    expect(onDrillSprintShare).toHaveBeenCalledTimes(1);
    unmount();

    renderTables({ onDrillPeriodShare, onDrillSprintShare, drillDisabled: true });
    expect(screen.queryAllByRole("button", { name: /show the items/ })).toHaveLength(0);
  });
});
