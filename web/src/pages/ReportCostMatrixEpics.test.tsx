import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { ReportDrillItems } from "../api/reportDrill";
import type { CostMatrixReport } from "../api/reports";
import ReportCostMatrix from "./ReportCostMatrix";
import { jsonResponse } from "../test/http";
import { COST_MATRIX_EMPTY, COST_MATRIX_TEAM, COST_MATRIX_UNIT, COST_MATRIX_USER, FILTERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts draws nothing under happy-dom: the donuts are a probe with one button per slice.
vi.mock("@mantine/charts", () => ({
  DonutChart: (props: { data: { name: string }[]; pieProps: { onClick: (sector: unknown, index: number) => void } }) => (
    <div data-testid="donut">
      {props.data.map((cell, index) => (
        <button key={cell.name} data-testid={`slice-${cell.name}`} onClick={() => props.pieProps.onClick(cell, index)} />
      ))}
    </div>
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/cost-matrix?";
const ITEMS = "/api/v1/reports/cost-matrix/items?";

const EPIC_ITEMS: ReportDrillItems = {
  ...histogramItems(1, 0, null, 0),
  bar: { count: 1, sumMd: 3, from: null, to: null },
  items: [{ issueKey: "FLO-1", summary: "Build it", itemKind: "TASK", accountId: null, assignee: "Ann Author", teamId: null, anchorAt: Date.UTC(2026, 2, 2, 10), figure: 3 }],
};

/** A team's authors (Ann 4, Bob 2, no known author 1) split over one epic, the fold and the epic-less work; the rows add up to 7. */
const TEAM_EPICS: CostMatrixReport = {
  ...COST_MATRIX_TEAM,
  byEpic: {
    columns: [
      { teamId: 1, accountId: "acc-ann", label: "Ann Author", totalMd: 4, active: null },
      { teamId: 1, accountId: "acc-bob", label: "Bob Builder", totalMd: 2, active: null },
      { teamId: 1, accountId: null, label: null, totalMd: 1, active: null },
    ],
    rows: [
      { kind: "EPIC", epicId: 10, issueKey: "FLO-10", summary: "Checkout", domain: "FLO", count: null, cells: [3, 2, 0], totalMd: 5 },
      { kind: "OTHER", epicId: null, issueKey: null, summary: null, domain: null, count: 3, cells: [1, 0, 0.5], totalMd: 1.5 },
      { kind: "NO_EPIC", epicId: null, issueKey: null, summary: null, domain: null, count: null, cells: [0, 0, 0.5], totalMd: 0.5 },
    ],
  },
};

/** One author (USER level): the same rows, one column — which the page does not draw, Total is the figure. */
const USER_EPICS: CostMatrixReport = {
  ...COST_MATRIX_USER,
  byEpic: {
    columns: [{ teamId: 1, accountId: "acc-ann", label: "Ann Author", totalMd: 4, active: null }],
    rows: [
      { kind: "EPIC", epicId: 10, issueKey: "FLO-10", summary: "Checkout", domain: "FLO", count: null, cells: [3], totalMd: 3 },
      { kind: "OTHER", epicId: null, issueKey: null, summary: null, domain: null, count: 2, cells: [1], totalMd: 1 },
    ],
  },
};

function serve(mockFetch: FetchMock, report: unknown = COST_MATRIX_UNIT) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(jsonResponse(200, EPIC_ITEMS));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, report));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <output data-testid="where">{useLocation().search}</output>;
}

function renderPage(route = "/reports/cost-matrix") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/cost-matrix" element={<ReportCostMatrix />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const itemCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(ITEMS));
const itemParams = (mockFetch: FetchMock) => new URL(itemCalls(mockFetch)[0], "http://x").searchParams;
const search = () => screen.getByTestId("where").textContent;
const UNIT_TABLE = "Man-days by epic and author team";
const TEAM_TABLE = "Man-days by epic and author";
const rowsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => Array.from(row.querySelectorAll("th, td"), (cell) => cell.textContent));

describe("ReportCostMatrix page — man-days by epic", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("the unit: a row per epic, the fold and no epic, a column per author team, a Total column and a footer that is the server's own totals", async () => {
    serve(mockFetch);
    renderPage();
    const table = await screen.findByRole("table", { name: UNIT_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Epic", "Alpha", "Old team Deleted team", "Unassigned", "Total"]);
    expect(rowsOf(table)).toEqual([
      ["FLO-10 CheckoutDomain: Flow", "7", "1", "0.5", "8.5"],
      ["FLO-11 SearchDomain: Flow", "3", "—", "—", "3"],
      ["Other epics (2)", "1.5", "—", "0.25", "1.75"],
      ["No epic", "1", "—", "0.25", "1.25"],
      ["Total", "12.5", "1", "1", "14.5"],
    ]);
    // A real table: a hidden caption names it, the headers carry their scope, the region is named.
    expect(within(table).getByText(UNIT_TABLE, { selector: "caption" })).toBeInTheDocument();
    expect(within(table).getAllByRole("columnheader")[0]).toHaveAttribute("scope", "col");
    expect(within(table).getAllByRole("rowheader")[0]).toHaveAttribute("scope", "row");
    expect(screen.getByRole("region", { name: UNIT_TABLE })).toHaveAttribute("tabindex", "0");
    // The attribution rule and the folding rule are printed with it; the level-aware heading is the card's.
    expect(screen.getByText(/a sub-task through its parent task/)).toBeInTheDocument();
    expect(screen.getByText(/The 50 epics with the most man-days are listed/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: UNIT_TABLE })).toBeInTheDocument();
  });

  test("a team: a column per author, the no-author bucket named; a person: only Total", async () => {
    serve(mockFetch, TEAM_EPICS);
    const { unmount } = renderPage("/reports/cost-matrix?teamId=1");
    const table = await screen.findByRole("table", { name: TEAM_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Epic", "Ann Author", "Bob Builder", "No known author", "Total"]);
    expect(rowsOf(table).at(-1)).toEqual(["Total", "4", "2", "1", "7"]);
    unmount();

    serve(mockFetch, USER_EPICS);
    renderPage("/reports/cost-matrix?teamId=1&accountId=acc-ann");
    const person = await screen.findByRole("table", { name: "Man-days by epic" });
    expect(within(person).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Epic", "Total"]);
    expect(rowsOf(person)).toEqual([["FLO-10 CheckoutDomain: Flow", "3"], ["Other epics (2)", "1"], ["Total", "4"]]);
  });

  test("no table when nothing was logged", async () => {
    serve(mockFetch, COST_MATRIX_EMPTY);
    renderPage();
    await screen.findByText("No data in this period");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("a team cell is a button named by the figure first; it asks for the epic, narrowed to the author, and the URL says which", async () => {
    serve(mockFetch, TEAM_EPICS);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?teamId=1&domain=FLO&split=WEEK");
    const table = await screen.findByRole("table", { name: TEAM_TABLE });
    const button = within(table).getByRole("button", { name: "3 MD — Ann Author: show the tasks of FLO-10 Checkout" });
    expect(button).toHaveTextContent("3");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "FLO-10 Checkout · Ann Author" });
    expect(await within(drawer).findByText("1 item · 3 MD")).toBeInTheDocument();
    const params = itemParams(mockFetch);
    expect([params.get("by"), params.getAll("key"), params.get("teamId"), params.get("accountId"), params.get("domain"), params.has("split")]).toEqual([
      "EPIC",
      ["10"],
      "1",
      "acc-ann",
      "FLO",
      false,
    ]);
    expect(search()).toContain("drill=epic%3A1%3A10%3Bs%3Dacc-ann");
    const items = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(within(items).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Issue", "Summary", "Kind", "Authors", "Last logged", "Logged (MD)"]);

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).not.toContain("drill");
    expect(button).toHaveFocus();
  });

  test("the no-author column, a zero and the footer are plain; a row's Total keeps the page's own scope (no team, no person of its own)", async () => {
    serve(mockFetch, TEAM_EPICS);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?teamId=1");
    const table = await screen.findByRole("table", { name: TEAM_TABLE });
    // The no-author bucket is nobody's drill (like its matrix row), a zero has nothing to list, the footer is the totals.
    expect(within(table).queryByRole("button", { name: /No known author/ })).not.toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /^0 MD/ })).not.toBeInTheDocument();
    expect(within(table).getAllByRole("button").filter((b) => b.closest("tfoot"))).toEqual([]);

    await user.click(within(table).getByRole("button", { name: "5 MD — total: show the tasks of FLO-10 Checkout" }));
    await screen.findByRole("dialog", { name: "FLO-10 Checkout" });
    const params = itemParams(mockFetch);
    expect([params.get("teamId"), params.has("accountId"), params.getAll("key")]).toEqual(["1", false, ["10"]]);
    expect(search()).toContain("drill=epic%3A-%3A10");
  });

  test("No epic asks for NO_EPIC, in a cell and in the Total", async () => {
    serve(mockFetch, TEAM_EPICS);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?teamId=1");
    const table = await screen.findByRole("table", { name: TEAM_TABLE });
    await user.click(within(table).getByRole("button", { name: "0.5 MD — total: show the tasks of No epic" }));
    const drawer = await screen.findByRole("dialog", { name: "No epic" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(itemParams(mockFetch).getAll("key")).toEqual(["NO_EPIC"]);
    expect(search()).toContain("drill=epic%3A-%3ANO_EPIC");
  });

  test("Other epics: its cells are plain, its Total asks for OTHER_EPICS at a team's level", async () => {
    serve(mockFetch, TEAM_EPICS);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?teamId=1");
    const table = await screen.findByRole("table", { name: TEAM_TABLE });
    expect(within(table).queryByRole("button", { name: /Ann Author: show the tasks of Other epics/ })).not.toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /1 MD — Ann Author/ })).not.toBeInTheDocument();
    await user.click(within(table).getByRole("button", { name: "1.5 MD — total: show the tasks of Other epics (3)" }));
    await screen.findByRole("dialog", { name: "Other epics (3)" });
    await waitFor(() => expect(itemCalls(mockFetch)).toHaveLength(1));
    expect(itemParams(mockFetch).getAll("key")).toEqual(["OTHER_EPICS"]);
  });

  test("Other epics' Total is plain at a person's level (the fold would rank over the person's slice); an epic's Total opens", async () => {
    serve(mockFetch, USER_EPICS);
    renderPage("/reports/cost-matrix?teamId=1&accountId=acc-ann");
    const table = await screen.findByRole("table", { name: "Man-days by epic" });
    expect(within(table).queryByRole("button", { name: /Other epics/ })).not.toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "3 MD — total: show the tasks of FLO-10 Checkout" })).toBeInTheDocument();
  });

  test("the unit: a team's cell is narrowed to the team; a soft-deleted team's cells are plain; the Total keeps the page's scope", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?domain=FLO&teamId=2");
    const table = await screen.findByRole("table", { name: UNIT_TABLE });
    expect(within(table).queryByRole("button", { name: /Old team/ })).not.toBeInTheDocument();
    await user.click(within(table).getByRole("button", { name: "7 MD — Alpha: show the tasks of FLO-10 Checkout" }));
    const drawer = await screen.findByRole("dialog", { name: "FLO-10 Checkout · Alpha" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    const params = itemParams(mockFetch);
    expect([params.get("teamId"), params.has("accountId"), params.get("domain")]).toEqual(["1", false, "FLO"]);
    expect(search()).toContain("drill=epic%3A1%3A10");

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(within(table).getByRole("button", { name: "8.5 MD — total: show the tasks of FLO-10 Checkout" }));
    await screen.findByRole("dialog", { name: "FLO-10 Checkout" });
    await waitFor(() => expect(itemCalls(mockFetch)).toHaveLength(2));
    // The Total is the page's own scope (team 2), not the cell's row (team 1).
    expect(new URL(itemCalls(mockFetch)[1], "http://x").searchParams.get("teamId")).toBe("2");
  });

  test("under a sprint-relative period the unit's cells are plain (the team rows read the union of every team's sprints) but Totals still open", async () => {
    serve(mockFetch, { ...COST_MATRIX_UNIT, meta: { ...COST_MATRIX_UNIT.meta, from: null, to: null } });
    renderPage("/reports/cost-matrix?lastSprints=3");
    const table = await screen.findByRole("table", { name: UNIT_TABLE });
    expect(within(table).queryByRole("button", { name: /Alpha: show the tasks/ })).not.toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "8.5 MD — total: show the tasks of FLO-10 Checkout" })).toBeInTheDocument();
  });

  test("a drill= link opens its figure once the report is in hand, naming the row and the column; a malformed one is ignored", async () => {
    serve(mockFetch, TEAM_EPICS);
    const { unmount } = renderPage("/reports/cost-matrix?teamId=1&drill=epic%3A1%3A10%3Bs%3Dacc-bob");
    const drawer = await screen.findByRole("dialog", { name: "FLO-10 Checkout · Bob Builder" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    const params = itemParams(mockFetch);
    expect([params.get("accountId"), params.getAll("key")]).toEqual(["acc-bob", ["10"]]);
    unmount();

    mockFetch.mockClear();
    renderPage("/reports/cost-matrix?teamId=1&drill=epic%3A1%3AFLO-10");
    await screen.findByRole("table", { name: TEAM_TABLE });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemCalls(mockFetch)).toEqual([]);
  });

  test("the donuts still open their drill from the shared hook, and each drawer closes back to its own figure", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const activity = await screen.findByRole("table", { name: "Man-days by activity type" });
    const slice = within(activity).getByRole("button", { name: "8.5 MD — show the tasks of Development" });
    await user.click(slice);
    const drawer = await screen.findByRole("dialog", { name: "Development · Activity type" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(itemParams(mockFetch).get("by")).toBe("ACTIVITY_TYPE");
    // Only the drawer of the open target is mounted: the epic drawer stays shut for a donut's drill.
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(slice).toHaveFocus();
  });
});
