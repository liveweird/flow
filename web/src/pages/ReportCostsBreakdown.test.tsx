import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { ReportDrillItems } from "../api/reportDrill";
import ReportCosts from "./ReportCosts";
import { jsonResponse } from "../test/http";
import { COSTS_EMPTY, COSTS_NO_SHARE, COSTS_TEAM, FILTERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts draws nothing under happy-dom: each donut is a probe with one button per slice that fires what recharts forwards to a
// pie sector's `onClick` (the sector and its index).
vi.mock("@mantine/charts", () => ({
  DonutChart: (props: {
    data: { name: string; value: number; color: string }[];
    pieProps: { onClick: (sector: unknown, index: number) => void };
  }) => (
    <div data-testid="donut" data-cells={JSON.stringify(props.data)}>
      {props.data.map((cell, index) => (
        <button key={cell.name} data-testid={`slice-${cell.name}`} onClick={() => props.pieProps.onClick(cell, index)} />
      ))}
    </div>
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/costs?";
const ITEMS = "/api/v1/reports/costs/items?";

/** Two tasks (one of them an epic logged on directly) with the cost drill's shape: the authors joined into `assignee`. */
const COST_ITEMS: ReportDrillItems = {
  ...histogramItems(2, 0, null, 0),
  bar: { count: 2, sumMd: 2.25, from: null, to: null },
  items: [
    { issueKey: "FLO-1", summary: "Build it", itemKind: "TASK", accountId: null, assignee: "Ann Author, Bob", teamId: null, anchorAt: Date.UTC(2026, 2, 2, 10), figure: 1.75 },
    { issueKey: "FLO-E1", summary: "The epic", itemKind: "EPIC", accountId: "acc-bob", assignee: "Bob", teamId: null, anchorAt: Date.UTC(2026, 1, 20, 10), figure: 0.5 },
  ],
};

function serve(mockFetch: FetchMock, report: unknown = COSTS_TEAM, items: ReportDrillItems = COST_ITEMS) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(jsonResponse(200, items));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, report));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <output data-testid="where">{useLocation().search}</output>;
}

function renderPage(route = "/reports/costs") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/costs" element={<ReportCosts />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const search = () => screen.getByTestId("where").textContent;
const ACTIVITY = "Man-days by activity type";
const CATEGORY = "Man-days by work category";
const rowsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));

describe("ReportCosts page — the activity-type and work-category donuts", () => {
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

  test("two donuts, each with a legend table that carries the figures; slices under 3% fold into Other, which lists them", async () => {
    serve(mockFetch);
    renderPage();
    const activity = await screen.findByRole("table", { name: ACTIVITY });
    expect(within(activity).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Activity type", "Man-days (MD)", "Share"]);
    expect(rowsOf(activity)).toEqual([
      ["Development", "8.5", "58.6%"],
      ["Testing", "3", "20.7%"],
      ["Support", "2", "13.8%"],
      ["Admin", "0.5", "3.4%"],
      ["Other (2)Includes: Planning, Ops", "0.5", "3.4%"],
    ]);
    const category = screen.getByRole("table", { name: CATEGORY });
    expect(rowsOf(category)).toEqual([
      ["Run", "9", "62.1%"],
      ["Build", "4.5", "31%"],
      ["(uncategorized)", "1", "6.9%"],
    ]);
    // The charts draw the same slices, Other in the neutral gray; the group names say which breakdown each is.
    // (The chart is a lazy chunk: await it.)
    const cells = async (name: string) =>
      JSON.parse((await within(await screen.findByRole("group", { name })).findByTestId("donut")).getAttribute("data-cells")!) as {
        name: string;
        value: number;
        color: string;
      }[];
    const drawn = await cells("Chart: " + ACTIVITY);
    expect(drawn.map((cell) => [cell.name, cell.value])).toEqual([
      ["Development", 8.5],
      ["Testing", 3],
      ["Support", 2],
      ["Admin", 0.5],
      ["Other (2)", 0.5],
    ]);
    expect(drawn.at(-1)?.color).toBe("gray.6");
    expect((await cells("Chart: " + CATEGORY)).map((cell) => cell.name)).toEqual(["Run", "Build", "(uncategorized)"]);
    expect(screen.getAllByText("Click a slice — or a figure in the table — to list the tasks behind it.")).toHaveLength(2);
  });

  test("a breakdown whose own filter is set is not drawn; both set draws none; an empty or all-zero report draws none", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/costs?activityType=Development");
    await screen.findByRole("table", { name: CATEGORY });
    expect(screen.queryByRole("table", { name: ACTIVITY })).not.toBeInTheDocument();
    unmount();

    const second = renderPage("/reports/costs?workCategory=Run");
    await screen.findByRole("table", { name: ACTIVITY });
    expect(screen.queryByRole("table", { name: CATEGORY })).not.toBeInTheDocument();
    second.unmount();

    const third = renderPage("/reports/costs?activityType=Development&workCategory=Run");
    await screen.findByRole("group", { name: "Man-days logged" });
    expect(screen.queryByRole("table", { name: ACTIVITY })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: CATEGORY })).not.toBeInTheDocument();
    third.unmount();

    serve(mockFetch, COSTS_EMPTY);
    const empty = renderPage();
    await screen.findByText("No data in this period");
    expect(screen.queryByTestId("donut")).not.toBeInTheDocument();
    empty.unmount();

    serve(mockFetch, COSTS_NO_SHARE);
    renderPage();
    await screen.findByRole("group", { name: "Man-days logged" });
    expect(screen.queryByRole("table", { name: ACTIVITY })).not.toBeInTheDocument();
  });

  test("a legend figure is a button named by the figure first; it opens the slice's tasks, the URL says which, Back returns focus", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/costs?domain=FLO");
    const activity = await screen.findByRole("table", { name: ACTIVITY });
    const button = within(activity).getByRole("button", { name: "8.5 MD — show the tasks of Development" });
    expect(button).toHaveTextContent("8.5");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Development · Activity type" });
    expect(await within(drawer).findByText("2 items · 2.25 MD")).toBeInTheDocument();
    // The page's own filter travels with the slice; the key is repeated per folded key.
    const asked = calls(mockFetch, ITEMS);
    expect(asked).toHaveLength(1);
    const params = new URL(asked[0], "http://x").searchParams;
    expect([params.get("by"), params.getAll("key"), params.get("domain")]).toEqual(["ACTIVITY_TYPE", ["Development"], "FLO"]);
    expect(search()).toContain("drill=cost%3AACTIVITY_TYPE%3ADevelopment");
    const items = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(within(items).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Issue", "Summary", "Kind", "Authors", "Last logged", "Logged (MD)"]);
    expect(within(items).getAllByRole("row").slice(1).map((row) => within(row).getAllByRole("cell").map((c) => c.textContent))).toEqual([
      ["FLO-1", "Build it", "Task", "Ann Author, Bob", "2026-03-02", "1.75"],
      ["FLO-E1", "The epic", "Epic", "Bob", "2026-02-20", "0.5"],
    ]);

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).not.toContain("drill");
    expect(button).toHaveFocus();
  });

  test("Other opens the drill with every folded key, repeated; the drawer says how many and lists them", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const activity = await screen.findByRole("table", { name: ACTIVITY });
    await user.click(within(activity).getByRole("button", { name: "0.5 MD — show the tasks of Other (2)" }));
    const drawer = await screen.findByRole("dialog", { name: "Other (2) · Activity type" });
    expect(within(drawer).getByText("Includes: Planning, Ops")).toBeInTheDocument();
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(new URL(calls(mockFetch, ITEMS)[0], "http://x").searchParams.getAll("key")).toEqual(["Planning", "Ops"]);
    expect(search()).toContain("drill=cost%3AACTIVITY_TYPE%3APlanning%2COps");
  });

  test("a click on a donut slice opens the same drill as its legend figure; (uncategorized) asks for UNCATEGORIZED", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/costs?activityType=Development");
    const chart = await screen.findByRole("group", { name: "Chart: " + CATEGORY });
    await user.click(within(chart).getByTestId("slice-(uncategorized)"));
    const drawer = await screen.findByRole("dialog", { name: "(uncategorized) · Work category" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    const params = new URL(calls(mockFetch, ITEMS)[0], "http://x").searchParams;
    expect([params.get("by"), params.getAll("key"), params.get("activityType")]).toEqual(["WORK_CATEGORY", ["UNCATEGORIZED"], "Development"]);
  });

  test("a drill= link opens its slice once the report is in hand, and a malformed one is ignored", async () => {
    serve(mockFetch);
    // With its team, as every drill link the page writes: a team-less link is normalised to the default team, and that filter change
    // can close the drill before it opens (order-dependent — BACKLOG "A drill link without a team loses its drill").
    const { unmount } = renderPage("/reports/costs?teamId=1&drill=cost%3AWORK_CATEGORY%3ARun");
    const drawer = await screen.findByRole("dialog", { name: "Run · Work category" });
    await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(new URL(calls(mockFetch, ITEMS)[0], "http://x").searchParams.getAll("key")).toEqual(["Run"]);
    unmount();

    mockFetch.mockClear();
    renderPage("/reports/costs?teamId=1&drill=cost%3AWORK_CATEGORY%3A100%25");
    await screen.findByRole("table", { name: CATEGORY });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("Other's legend figure is the drill's bar: two slices of 0.125 MD show 0.13 each, fold to 0.25 (not 0.26) and the drawer says 0.25", async () => {
    const tiny = {
      ...COSTS_TEAM,
      byActivityType: [
        { key: "Big", md: 100, exactMd: 100 },
        { key: "x", md: 0.13, exactMd: 0.125 },
        { key: "y", md: 0.13, exactMd: 0.125 },
      ],
    };
    serve(mockFetch, tiny, { ...COST_ITEMS, bar: { count: 2, sumMd: 0.25, from: null, to: null } });
    const user = userEvent.setup();
    renderPage();
    const activity = await screen.findByRole("table", { name: ACTIVITY });
    expect(rowsOf(activity).map((row) => row[1])).toEqual(["100", "0.25"]);
    await user.click(within(activity).getByRole("button", { name: "0.25 MD — show the tasks of Other (2)" }));
    const drawer = await screen.findByRole("dialog", { name: "Other (2) · Activity type" });
    expect(await within(drawer).findByText("2 items · 0.25 MD")).toBeInTheDocument();
    expect(new URL(calls(mockFetch, ITEMS)[0], "http://x").searchParams.getAll("key")).toEqual(["x", "y"]);
  });
});
