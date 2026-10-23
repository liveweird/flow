import { describe, expect, test } from "vitest";
import ReportPersonTotals from "./ReportPersonTotals";
import type { GroupColumn } from "./ReportGroupsTable";
import { renderWithProviders, screen, within } from "../test/render";
import type { DistributionPerson } from "../utils/distributionPeople";

interface Group {
  accountId?: string | null;
  label?: string | null;
  items: number;
}
const columns: GroupColumn<Group>[] = [{ key: "items", header: "Items", render: (group) => group.items }];
const people: DistributionPerson[] = [
  { accountId: "acc-ann", label: "Ann Author", position: 1 },
  { accountId: "acc-bob", label: "Bob Builder", position: 2 },
];
const rows = () => within(screen.getByRole("table", { name: "Totals per person" })).getAllByRole("row").slice(1);
const cells = (row: HTMLElement) => within(row).getAllByRole("cell").map((cell) => cell.textContent);

describe("ReportPersonTotals", () => {
  test("one plain-text row per person, numbered like the histogram's legend, with the page's own columns right-aligned", () => {
    renderWithProviders(
      <ReportPersonTotals
        people={people}
        columns={columns}
        groups={[
          { accountId: "acc-bob", label: "Bob Builder", items: 4 },
          { accountId: "acc-ann", label: "Ann Author", items: 7 },
        ]}
      />,
    );
    expect(screen.getByRole("heading", { name: "Totals per person" })).toBeInTheDocument();
    expect(within(screen.getByRole("table")).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Member", "Items"]);
    expect(rows().map(cells)).toEqual([
      ["2. Bob Builder", "4"],
      ["1. Ann Author", "7"],
    ]);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("a person the chart does not number keeps the bare name; the label falls back to the account id, then to Unassigned", () => {
    renderWithProviders(
      <ReportPersonTotals
        people={people}
        columns={columns}
        groups={[
          { accountId: "acc-zed", label: "Zed Zero", items: 1 },
          { accountId: "acc-raw", label: null, items: 2 },
          { accountId: null, label: null, items: 3 },
        ]}
      />,
    );
    expect(rows().map((row) => cells(row)[0])).toEqual(["Zed Zero", "acc-raw", "Unassigned"]);
  });

  test("no people at all (nothing to number) still lists the groups by name", () => {
    renderWithProviders(<ReportPersonTotals people={[]} columns={columns} groups={[{ accountId: "acc-ann", label: "Ann Author", items: 7 }]} />);
    expect(rows().map((row) => cells(row)[0])).toEqual(["Ann Author"]);
  });

  test("no groups render nothing", () => {
    renderWithProviders(<ReportPersonTotals people={people} columns={columns} groups={[]} />);
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
