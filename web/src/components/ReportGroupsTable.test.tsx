import { describe, expect, test } from "vitest";
import { useLocation } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import ReportGroupsTable, { type GroupColumn, type GroupIdentity } from "./ReportGroupsTable";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

type Row = GroupIdentity & { md: number };
const COLUMNS: GroupColumn<Row>[] = [{ key: "md", header: "MD", render: (g) => g.md }];

function Table(props: { level: "UNIT" | "TEAM" | "USER"; groups: Row[] }) {
  const { search } = useLocation();
  return (
    <>
      <ReportGroupsTable {...props} filters={FILTERS} columns={COLUMNS} />
      <output data-testid="search">{search}</output>
    </>
  );
}

describe("ReportGroupsTable", () => {
  test("a team row's name narrows teamId and keeps the rest of the filter", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Table level="UNIT" groups={[{ teamId: 2, label: "Beta", md: 7 }]} />, {
      route: "/reports/velocity?lastSprints=3&domain=FLO",
    });
    expect(screen.getByRole("heading", { name: "By team" })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Show Beta" });
    expect(link).toHaveAttribute("href", "/reports/velocity?lastSprints=3&teamId=2&domain=FLO");
    await user.click(link);
    expect(screen.getByTestId("search").textContent).toContain("teamId=2");
  });

  test("a team name falls back to the reference data when the row has no label", () => {
    renderWithProviders(<Table level="UNIT" groups={[{ teamId: 1, md: 1 }]} />);
    expect(screen.getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
  });

  test("a member row narrows accountId within the team; the unassigned bucket is not a link", () => {
    renderWithProviders(
      <Table
        level="TEAM"
        groups={[
          { accountId: "acc-ann", label: "Ann Author", md: 3 },
          { accountId: "acc-bob", md: 2 },
          { accountId: null, label: null, md: 1 },
        ]}
      />,
      { route: "/reports/velocity?teamId=1" },
    );
    expect(screen.getByRole("heading", { name: "By member" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Show Ann Author" })).toHaveAttribute(
      "href",
      "/reports/velocity?teamId=1&accountId=acc-ann",
    );
    // A missing label resolves through the team rosters.
    expect(screen.getByRole("link", { name: "Show Bob Builder" })).toBeInTheDocument();
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  test("a team row without any identity is the unassigned bucket", () => {
    renderWithProviders(<Table level="UNIT" groups={[{ teamId: null, md: 4 }]} />);
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  test("a report can word its UNIT table for other owners than credit teams", () => {
    renderWithProviders(
      <>
        <ReportGroupsTable
          level="UNIT"
          filters={FILTERS}
          groups={[{ teamId: 1, md: 1 }, { teamId: null, md: 2 }]}
          columns={COLUMNS}
          wording={{ title: "Epics by owner team", teamHeader: "Owner team", unassigned: "No owner team" }}
        />
      </>,
    );
    const table = screen.getByRole("table", { name: "Epics by owner team" });
    expect(screen.getByRole("heading", { name: "Epics by owner team" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Owner team" })).toBeInTheDocument();
    expect(table).toHaveTextContent("No owner team");
    expect(screen.queryByText("Unassigned")).not.toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  test("USER level and an empty list render nothing", () => {
    const { container, rerender } = renderWithProviders(<Table level="USER" groups={[{ teamId: 1, md: 1 }]} />, {
      route: "/reports/velocity?teamId=1&accountId=a",
    });
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    rerender(<Table level="UNIT" groups={[]} />);
    expect(container.querySelector("table")).toBeNull();
  });
});
