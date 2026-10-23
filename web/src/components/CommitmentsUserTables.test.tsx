import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import CommitmentsUserTables from "./CommitmentsUserTables";
import { renderWithProviders, screen, within } from "../test/render";
import { COMMITMENTS_USER, COMMITMENTS_USERS, FILTERS } from "../test/reportFixtures";
import { showItems } from "../test/drill";

const cellTexts = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));
const headerTexts = (table: HTMLElement) => within(table).getAllByRole("columnheader").map((header) => header.textContent);

function renderTables(over: Partial<Parameters<typeof CommitmentsUserTables>[0]> = {}) {
  return renderWithProviders(
    <CommitmentsUserTables sprints={COMMITMENTS_USERS.sprints} groups={COMMITMENTS_USERS.groups} filters={FILTERS} {...over} />,
  );
}

describe("CommitmentsUserTables", () => {
  test("with two people: the numbered period totals and each person's share of the sprint", () => {
    renderTables();
    const totals = screen.getByRole("table", { name: "Totals per person" });
    expect(cellTexts(totals).map((cells) => cells[0])).toEqual(["1. Ann Author", "2. Bob Builder"]);
    const shares = screen.getByRole("table", { name: "Each person's initial and final scope per sprint" });
    expect(headerTexts(shares).slice(0, 2)).toEqual(["Sprint", "Person"]);
    expect(cellTexts(shares).map((cells) => cells.slice(0, 2))).toEqual([
      ["Alpha 2", "1. Ann Author"],
      ["Alpha 2", "2. Bob Builder"],
    ]);
  });

  test("with one person the share table stays out (the sprints table beside the chart already is that person's row)", () => {
    renderTables({ sprints: COMMITMENTS_USER.sprints, groups: COMMITMENTS_USER.groups });
    expect(screen.getAllByRole("table")).toHaveLength(1);
    expect(screen.getByRole("table", { name: "Totals per person" })).toBeInTheDocument();
  });

  test("without groups the totals table is gone but the shares remain; a person the sprints never list stays bare, none is Unassigned", () => {
    const { unmount } = renderTables({ groups: [] });
    expect(screen.queryByRole("table", { name: "Totals per person" })).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Each person's initial and final scope per sprint" })).toBeInTheDocument();
    unmount();

    renderTables({
      groups: [
        { accountId: "acc-bob", label: "Bob Builder", initialMd: 1, initialItems: 1, finalMd: 1, finalItems: 1 },
        { accountId: "acc-zed", label: "Zed Zero", initialMd: 1, initialItems: 1, finalMd: 1, finalItems: 1 },
        { accountId: null, label: null, initialMd: 1, initialItems: 1, finalMd: 1, finalItems: 1 },
      ],
    });
    const totals = screen.getByRole("table", { name: "Totals per person" });
    expect(cellTexts(totals).map((cells) => cells[0])).toEqual(["2. Bob Builder", "Zed Zero", "Unassigned"]);
  });

  test("a share figure opens the drill of its person and bucket; kept data of a previous filter is plain text", async () => {
    const onDrill = vi.fn();
    const { unmount } = renderTables({ onDrill });
    const shares = screen.getByRole("table", { name: "Each person's initial and final scope per sprint" });
    const initial = within(shares).getAllByRole("button", { name: showItems("Alpha 2 · 2. Bob Builder · Committed") });
    await userEvent.click(initial[0]);
    expect(onDrill).toHaveBeenCalledTimes(1);
    const [sprint, bucket, accountId, trigger] = onDrill.mock.calls[0];
    expect(sprint).toMatchObject({ sprintId: 12 });
    expect(bucket).toBe("COMMITTED");
    expect(accountId).toBe("acc-bob");
    expect(trigger).toBeInstanceOf(HTMLElement);
    unmount();

    renderTables({ onDrill, drillDisabled: true });
    expect(screen.queryAllByRole("button", { name: /show the items/ })).toHaveLength(0);
  });

  test("without a handler the figures are plain text", () => {
    renderTables();
    expect(screen.queryAllByRole("button", { name: /show the items/ })).toHaveLength(0);
  });
});
