import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { ReportDrillItems } from "../api/reportDrill";
import ReportCosts from "./ReportCosts";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { showItems } from "../test/drill";
import {
  COSTS_NO_SPRINTS,
  COSTS_TOP_EPIC_IDS,
  COSTS_UNIT,
  COSTS_WITH_SPRINTS,
  COSTS_WITH_SPRINTS_USER,
  costsSprintItems,
} from "../test/costsSprintsFixtures";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props; every series gets a button that
// fires the `onDoubleClick` recharts would forward to its rectangles, with the LAST row (the latest sprint) as the payload.
vi.mock("@mantine/charts", () => {
  type BarProps = { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  type Series = { name: string; type?: string; label?: string; color?: string; stackId?: string; strokeDasharray?: string };
  const probe = (kind: string) => (props: { data: { label: string }[]; series: Series[]; barProps?: unknown }) => (
    <div>
      <div
        data-testid="chart"
        data-kind={kind}
        data-series={JSON.stringify(props.series.map(({ name, type, label, color, stackId, strokeDasharray }) => ({ name, type, label, color, stackId, strokeDasharray })))}
        data-rows={JSON.stringify(props.data)}
      />
      {typeof props.barProps === "function" &&
        props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`dbl-${series.name}`}
            onClick={() => (props.barProps as (s: unknown) => BarProps)(series).onDoubleClick?.({ payload: props.data.at(-1) }, 0, {})}
          />
        ))}
    </div>
  );
  return { BarChart: probe("bar"), CompositeChart: probe("composite"), DonutChart: () => null, ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;
type Plotted = { name: string; type?: string; label: string; color: string; stackId?: string; strokeDasharray?: string };
const REPORT = "/api/v1/reports/costs?";
const ITEMS = "/api/v1/reports/costs/items?";

function serve(mockFetch: FetchMock, report: unknown = COSTS_WITH_SPRINTS, items: ReportDrillItems = costsSprintItems(13.5)) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(jsonResponse(200, items));
    if (url.startsWith(REPORT)) {
      const picked = new URLSearchParams(url.slice(REPORT.length)).has("accountId");
      return Promise.resolve(jsonResponse(200, picked && report === COSTS_WITH_SPRINTS ? COSTS_WITH_SPRINTS_USER : report));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <output data-testid="where">{useLocation().search}</output>;
}

function renderPage(route = "/reports/costs?teamId=1&lastSprints=6") {
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

const EPIC_CHART = "Chart: actual cost by sprint, stacked by epic";
const EPIC_TABLE = "Actual cost per sprint and epic";
const CAPACITY_CHART = "Chart: actual cost and capacity by sprint";
const CAPACITY_TABLE = "Actual cost and capacity per sprint";
const itemsCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(ITEMS));
const itemParams = (mockFetch: FetchMock) => new URL(itemsCalls(mockFetch).at(-1) ?? "", "http://x").searchParams;
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });
const probeOf = async (name: string) => within(await screen.findByRole("group", { name })).getByTestId("chart");
const seriesOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-series") ?? "[]") as Plotted[];
const rowsOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-rows") ?? "[]") as Record<string, number | string | null>[];
const cellsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent ?? ""));

describe("ReportCosts page — the sprint widgets", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    // A bare session: no ADMIN role, no manager — the widgets (capacity included) are everyone's.
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("the by-epic card: its title, the legend naming every segment and one cost stack in stable colours, the server's segment order", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Actual cost per sprint, by epic" })).toBeInTheDocument();
    const group = await screen.findByRole("group", { name: EPIC_CHART });
    expect(within(within(group).getByRole("list", { name: "Epics in the chart" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "FLO-10 Checkout",
      "FLO-11 Search",
      "Other epics",
      "No epic",
    ]);
    const probe = await probeOf(EPIC_CHART);
    expect(rowsOf(probe).map((row) => row.label)).toEqual(["Alpha 1", "Alpha 2"]);
    expect(seriesOf(probe).map((series) => `${series.stackId}:${series.name}:${series.label}:${series.color}`)).toEqual([
      "a:a0:FLO-10 Checkout:flow.6",
      "a:a1:FLO-11 Search:violet.5",
      "a:a2:Other epics:gray.6",
      "a:a3:No epic:gray.8",
    ]);
    expect(rowsOf(probe)[0]).toMatchObject({ a0: 6, a1: 0, a2: 0, a3: 4 });
    expect(rowsOf(probe)[1]).toMatchObject({ a0: 7, a1: 3, a2: 2.5, a3: 1 });
    expect(screen.getByText(/A worklog counts toward the epic its task belongs to now/)).toBeInTheDocument();
  });

  test("the by-epic table lists every sprint × segment and its sums are the sprints' actual cost", async () => {
    serve(mockFetch);
    renderPage();
    const table = await screen.findByRole("table", { name: EPIC_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Sprint", "Epic", "Actual cost (MD)"]);
    const rows = cellsOf(table);
    expect(rows).toEqual([
      ["Alpha 1", "FLO-10 Checkout", "6"],
      ["Alpha 1", "No epic", "4"],
      ["Alpha 2", "FLO-10 Checkout", "7"],
      ["Alpha 2", "FLO-11 Search", "3"],
      ["Alpha 2", "Other epics", "2.5"],
      ["Alpha 2", "No epic", "1"],
    ]);
    const capacity = cellsOf(screen.getByRole("table", { name: CAPACITY_TABLE }));
    for (const sprint of ["Alpha 1", "Alpha 2"]) {
      const total = rows.filter((row) => row[0] === sprint).reduce((sum, row) => sum + Number(row[2]), 0);
      expect(total).toBe(Number(capacity.find((row) => row[0] === sprint)?.[1]));
    }
  });

  test("the capacity card: a bar per sprint with the capacity as a dashed line, a dash and no marker for a sprint without one", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Actual cost against capacity, per sprint" })).toBeInTheDocument();
    const probe = await probeOf(CAPACITY_CHART);
    expect(seriesOf(probe)).toEqual([
      { name: "acMd", type: "bar", label: "Actual cost", color: "gray.6" },
      { name: "capacityMd", type: "line", label: "Capacity", color: "gray.6", strokeDasharray: "6 4" },
    ]);
    expect(rowsOf(probe).map((row) => [row.label, row.acMd, row.capacityMd])).toEqual([
      ["Alpha 1", 10, 12],
      ["Alpha 2", 13.5, null],
    ]);
    // The page's own legend tells the gray bar from the gray dashed line by glyph (the chart's default legend would show two identical swatches).
    const legend = within(screen.getByRole("group", { name: CAPACITY_CHART })).getByRole("list", { name: "Series in the chart" });
    expect(within(legend).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["Actual cost", "Capacity"]);
    const table = screen.getByRole("table", { name: CAPACITY_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Sprint",
      "Actual cost (MD)",
      "Capacity (MD)",
      "Actual ÷ capacity",
      "Capacity source",
    ]);
    expect(cellsOf(table)).toEqual([
      ["Alpha 1", "10", "12", "83.3%", "Sprint calendar"],
      ["Alpha 2", "13.5", "—", "—", "—"],
    ]);
  });

  test("no sprint with a capacity: the line stays out of the chart and every capacity cell is a dash", async () => {
    serve(mockFetch);
    renderPage("/reports/costs?teamId=1&accountId=acc-ann");
    const probe = await probeOf(CAPACITY_CHART);
    expect(seriesOf(probe).map((series) => series.name)).toEqual(["acMd"]);
    expect(within(screen.getByRole("list", { name: "Series in the chart" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["Actual cost"]);
    expect(cellsOf(screen.getByRole("table", { name: CAPACITY_TABLE }))).toEqual([
      ["Alpha 1", "10", "—", "—", "—"],
      ["Alpha 2", "13.5", "—", "—", "—"],
    ]);
    expect(screen.getByText(/With one author picked there is no capacity/)).toBeInTheDocument();
  });

  test("the time logged in the period outside the listed sprints is one line under the widgets, and only when there is some", async () => {
    serve(mockFetch);
    const { unmount } = renderPage();
    expect(await screen.findByText("2.5 MD logged in the period outside the listed sprints")).toBeInTheDocument();
    unmount();

    serve(mockFetch, { ...COSTS_WITH_SPRINTS, outsideSprintsMd: 0 });
    renderPage();
    await probeOf(CAPACITY_CHART);
    expect(screen.queryByText(/outside the listed sprints/)).not.toBeInTheDocument();
  });

  test("a team without a closed sprint in the period gets one card that says so, and no outside line or table", async () => {
    serve(mockFetch, COSTS_NO_SPRINTS);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Actual cost per sprint" })).toBeInTheDocument();
    expect(screen.getByText("This team completed no sprint in the period, so there is no sprint to show.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: EPIC_TABLE })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: CAPACITY_TABLE })).not.toBeInTheDocument();
    expect(screen.queryByText(/outside the listed sprints/)).not.toBeInTheDocument();
  });

  test("the unit level says sprints need a team", async () => {
    serve(mockFetch, COSTS_UNIT);
    renderPage();
    expect(await screen.findByText("Sprints belong to a team. Pick a team to see the actual cost of its sprints.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: CAPACITY_TABLE })).not.toBeInTheDocument();
  });

  test("sprints that cost nothing draw no by-epic card but keep the capacity card", async () => {
    const idle = { ...COSTS_WITH_SPRINTS, sprints: COSTS_WITH_SPRINTS.sprints.map((sprint) => ({ ...sprint, acMd: 0, byEpic: [] })) };
    serve(mockFetch, idle);
    renderPage();
    await probeOf(CAPACITY_CHART);
    expect(screen.queryByRole("heading", { name: /by epic/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /show the items of/ })).not.toBeInTheDocument();
  });

  test("a segment figure of the by-epic table lists the worklogs of that epic in that sprint: the request, the title, the URL and the focus", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(3));
    const user = userEvent.setup();
    renderPage();
    const button = await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-11 Search", "Actual cost (MD)") });
    expect(button).toHaveAccessibleName("3 — Actual cost (MD): show the items of Alpha 2 · FLO-11 Search");
    await user.click(button);

    const drawer = await dialog("Actual cost — FLO-11 Search · Alpha 2");
    expect(await within(drawer).findByText("2 items · 3 MD")).toBeInTheDocument();
    // the bar equals the chart's 3 MD for the segment: no note
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    const params = itemParams(mockFetch);
    expect([params.get("by"), params.get("epicSegment"), params.get("sprintId"), params.get("teamId"), params.get("connectionId"), params.has("lastSprints"), params.has("key")]).toEqual([
      "EPIC",
      "102",
      "12",
      "1",
      "1",
      false,
      false,
    ]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=cost-sprint%3A1%3A1%3A12%3Ae%3D102");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });

  test("'Other epics' sends the chart's top epics as topEpicId; a double-click on a segment drills it too", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(2.5));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · Other epics", "Actual cost (MD)") }));
    await dialog("Actual cost — Other epics · Alpha 2");
    const params = itemParams(mockFetch);
    expect([params.get("by"), params.get("epicSegment"), params.getAll("topEpicId")]).toEqual(["EPIC", "OTHER_EPICS", COSTS_TOP_EPIC_IDS.map(String)]);
    expect(search()).toContain("drill=cost-sprint%3A1%3A1%3A12%3Ae%3DOTHER_EPICS%2C101%2C102");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByTestId("dbl-a3"));
    await dialog("Actual cost — No epic · Alpha 2");
    expect(itemParams(mockFetch).get("epicSegment")).toBe("NO_EPIC");
    expect(itemParams(mockFetch).has("topEpicId")).toBe(false);
  });

  test("a sprint's figure in the capacity table lists the sprint's WHOLE bar: no breakdown, no epic, the period narrowed to the sprint", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(10));
    const user = userEvent.setup();
    renderPage();
    const table = await screen.findByRole("table", { name: CAPACITY_TABLE });
    const button = within(table).getByRole("button", { name: showItems("Alpha 1 · Actual cost", "Actual cost (MD)") });
    await user.click(button);

    const drawer = await dialog("Actual cost · Alpha 1");
    expect(await within(drawer).findByText("2 items · 10 MD")).toBeInTheDocument();
    const params = itemParams(mockFetch);
    expect([params.has("by"), params.has("epicSegment"), params.has("key"), params.get("sprintId"), params.get("teamId")]).toEqual([false, false, false, "11", "1"]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=cost-sprint%3A1%3A1%3A11");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });

  test("one sprint id on two connections is two bars, each drilling its own connection and held against its own figure", async () => {
    const twin = { ...COSTS_WITH_SPRINTS.sprints[0], connectionId: 7, acMd: 4, capacityMd: null, capacitySource: null, byEpic: [{ ...COSTS_WITH_SPRINTS.sprints[0].byEpic[0], acMd: 4 }] };
    serve(mockFetch, { ...COSTS_WITH_SPRINTS, sprints: [COSTS_WITH_SPRINTS.sprints[0], twin] }, costsSprintItems(4));
    const user = userEvent.setup();
    renderPage();
    const probe = await probeOf(CAPACITY_CHART);
    expect(rowsOf(probe).map((row) => [row.id, row.acMd])).toEqual([
      ["1:1:11", 10],
      ["1:7:11", 4],
    ]);
    const table = screen.getByRole("table", { name: CAPACITY_TABLE });
    const buttons = within(table).getAllByRole("button", { name: showItems("Alpha 1 · Actual cost", "Actual cost (MD)") });
    expect(buttons).toHaveLength(2);

    await user.click(buttons[1]);
    const drawer = await dialog("Actual cost · Alpha 1");
    expect(itemParams(mockFetch).get("connectionId")).toBe("7");
    expect(search()).toContain("drill=cost-sprint%3A1%3A7%3A11");
    // held against THIS bar's 4 MD, not the first connection's 10
    expect(await within(drawer).findByText("2 items · 4 MD")).toBeInTheDocument();
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(buttons[0]);
    await dialog("Actual cost · Alpha 1");
    expect(itemParams(mockFetch).get("connectionId")).toBe("1");
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a double-click on a sprint's actual-cost bar opens the same whole-bar drill; a hit on the capacity line changes nothing", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(13.5));
    const user = userEvent.setup();
    renderPage();
    await probeOf(CAPACITY_CHART);
    // The capacity series is a reference line, not a drillable bar: a hit on it opens nothing and asks for nothing.
    await user.click(screen.getByTestId("dbl-capacityMd"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
    await user.click(screen.getByTestId("dbl-acMd"));
    const drawer = await dialog("Actual cost · Alpha 2");
    expect(await within(drawer).findByText("2 items · 13.5 MD")).toBeInTheDocument();
    expect(itemParams(mockFetch).get("sprintId")).toBe("12");
  });

  test("a drawer total that differs from the chart's figure says the data changed", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(11));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 1 · Actual cost", "Actual cost (MD)") }));
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a link carrying a sprint drill opens the drawer once the report is in hand and, closed, returns focus to its card", async () => {
    serve(mockFetch, COSTS_WITH_SPRINTS, costsSprintItems(1));
    const { unmount } = renderPage("/reports/costs?teamId=1&lastSprints=6&drill=cost-sprint:1:1:12:e=NO_EPIC");
    const drawer = await dialog("Actual cost — No epic · Alpha 2");
    expect(await within(drawer).findByText("2 items · 1 MD")).toBeInTheDocument();
    await userEvent.setup().keyboard("{Escape}");
    await waitFor(() => expect(document.activeElement).toHaveAttribute("data-drill-anchor", "costs-epics"));
    unmount();

    renderPage("/reports/costs?teamId=1&lastSprints=6&drill=cost-sprint:1:1:11");
    await dialog("Actual cost · Alpha 1");
    await userEvent.setup().keyboard("{Escape}");
    await waitFor(() => expect(document.activeElement).toHaveAttribute("data-drill-anchor", "costs-capacity"));
  });

  test("a malformed sprint drill link is ignored", async () => {
    serve(mockFetch);
    renderPage("/reports/costs?teamId=1&lastSprints=6&drill=cost-sprint:1:x");
    await probeOf(CAPACITY_CHART);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
  });

  test("in Polish the outside line, the card titles and the special segments are translated", async () => {
    await i18n.changeLanguage("pl");
    try {
      serve(mockFetch);
      renderPage();
      expect(await screen.findByText("2.5 MD zalogowano w okresie poza wymienionymi sprintami")).toBeInTheDocument();
      expect(screen.getByRole("heading", { level: 3, name: "Koszt rzeczywisty sprintów wg epików" })).toBeInTheDocument();
      const group = await screen.findByRole("group", { name: "Wykres: koszt rzeczywisty wg sprintów, ułożony według epików" });
      expect(within(within(group).getByRole("list", { name: "Epiki na wykresie" })).getAllByRole("listitem").map((item) => item.textContent)).toContain("Pozostałe epiki");
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});
