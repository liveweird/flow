import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { showItems } from "../test/drill";
import { THROUGHPUT_EPICS, THROUGHPUT_EPICS_USERS, epicSegmentItems } from "../test/epicFixtures";
import { FILTERS, THROUGHPUT_TEAM } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props; every series gets a button that
// fires the `onDoubleClick` recharts would forward to its rectangles, with the LAST row (the latest sprint) as the payload.
vi.mock("@mantine/charts", () => {
  type BarProps = { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  type Series = { name: string; label?: string; color?: string; stackId?: string };
  const probe = (kind: string) => (props: { data: { label: string }[]; series: Series[]; barProps?: unknown }) => (
    <div>
      <div
        data-testid="chart"
        data-kind={kind}
        data-series={JSON.stringify(props.series.map(({ name, label, color, stackId }) => ({ name, label, color, stackId })))}
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
  return { BarChart: probe("bar"), AreaChart: probe("area"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;
type Plotted = { name: string; label: string; color: string; stackId: string };

function serve(mockFetch: FetchMock, report = THROUGHPUT_EPICS, items: (url: string) => Response = () => jsonResponse(200, epicSegmentItems(2, 6))) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/throughput/items?")) return Promise.resolve(items(url));
    if (url.startsWith("/api/v1/reports/throughput?")) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? THROUGHPUT_EPICS_USERS : report));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/throughput"
        element={
          <>
            <ReportThroughput />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const EPIC_CHART = "Chart: delivered scope by sprint, stacked by epic";
const EPIC_TABLE = "Delivered scope per sprint and epic";
const itemsCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput/items?"));
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });
const epicProbe = async () => within(await screen.findByRole("group", { name: EPIC_CHART })).getByTestId("chart");
const seriesOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-series") ?? "[]") as Plotted[];
const rowsOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-rows") ?? "[]") as Record<string, number | string>[];
const cellsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent ?? ""));

describe("ReportThroughput page — the by-epic section", () => {
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

  test("a card after the sprint card: its title, what it is for, the legend naming every segment and one delivered stack in stable colours", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?teamId=1&lastSprints=6");
    expect(await screen.findByRole("heading", { level: 3, name: "Delivered per sprint, by epic" })).toBeInTheDocument();
    expect(screen.getByText("How many epics each sprint's delivered work spreads over.")).toBeInTheDocument();
    const group = await screen.findByRole("group", { name: EPIC_CHART });
    expect(within(within(group).getByRole("list", { name: "Epics in the chart" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "FLO-10 Checkout",
      "FLO-11 Search",
      "Other epics",
      "No epic",
    ]);
    const probe = await epicProbe();
    expect(rowsOf(probe).map((row) => row.label)).toEqual(["Alpha 1", "Alpha 2"]);
    // one stack, the special keys localised, the colours the chart's own order gives (an epic wears the same one in every sprint)
    expect(seriesOf(probe).map((series) => `${series.stackId}:${series.name}:${series.label}:${series.color}`)).toEqual([
      "d:d0:FLO-10 Checkout · delivered:flow.6",
      "d:d1:FLO-11 Search · delivered:violet.5",
      "d:d2:Other epics · delivered:gray.6",
      "d:d3:No epic · delivered:gray.8",
    ]);
    expect(rowsOf(probe)[0]).toMatchObject({ d0: 7, d1: 0, d2: 0, d3: 3 });
    expect(rowsOf(probe)[1]).toMatchObject({ d0: 6, d1: 5, d2: 4, d3: 3 });
  });

  test("in Polish the special segments are 'Pozostałe epiki' and 'Bez epiku'", async () => {
    await i18n.changeLanguage("pl");
    try {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1");
      const group = await screen.findByRole("group", { name: "Wykres: dostarczony zakres według sprintów, ułożony według epików" });
      expect(within(within(group).getByRole("list", { name: "Epiki na wykresie" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
        "FLO-10 Checkout",
        "FLO-11 Search",
        "Pozostałe epiki",
        "Bez epiku",
      ]);
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("the table lists every sprint × segment and its sums are the sprint rows' delivered totals", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?teamId=1&lastSprints=6");
    const table = await screen.findByRole("table", { name: EPIC_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Sprint", "Epic", "Delivered (MD)", "Delivered (items)"]);
    const rows = cellsOf(table);
    expect(rows).toEqual([
      ["Alpha 1", "FLO-10 Checkout", "7", "3"],
      ["Alpha 1", "No epic", "3", "1"],
      ["Alpha 2", "FLO-10 Checkout", "6", "2"],
      ["Alpha 2", "FLO-11 Search", "5", "2"],
      ["Alpha 2", "Other epics", "4", "2"],
      ["Alpha 2", "No epic", "3", "1"],
    ]);
    const sprints = cellsOf(screen.getByRole("table", { name: "Sprints and their figures" }));
    for (const sprint of ["Alpha 1", "Alpha 2"]) {
      const total = (column: number) => rows.filter((row) => row[0] === sprint).reduce((sum, row) => sum + Number(row[column]), 0);
      const own = sprints.find((row) => row[0] === sprint)!;
      // sprint, team, completed, delivered MD, delivered items, …
      expect([total(2), total(3)]).toEqual([Number(own[3]), Number(own[4])]);
    }
  });

  test("only the sprint view is split: the period card has no by-epic chart or table, and the switch to cumulative leaves the section as it is", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1&lastSprints=6");
    await epicProbe();
    expect(screen.getAllByRole("heading", { level: 3, name: /by epic/ })).toHaveLength(1);
    const periodCard = screen.getByRole("heading", { level: 3, name: "Delivered per period" }).closest("[data-drill-anchor]") as HTMLElement;
    expect(periodCard).toHaveAttribute("data-drill-anchor", "throughput-period");
    expect(within(periodCard).queryByRole("group", { name: EPIC_CHART })).not.toBeInTheDocument();
    expect(within(periodCard).queryByRole("table", { name: EPIC_TABLE })).not.toBeInTheDocument();
    // the section belongs to the sprint view: it sits after the sprint card
    const sprintCard = screen.getByRole("heading", { level: 3, name: "Delivered per sprint" }).closest("[data-drill-anchor]") as HTMLElement;
    const epicCard = screen.getByRole("heading", { level: 3, name: "Delivered per sprint, by epic" }).closest("[data-drill-anchor]") as HTMLElement;
    expect(sprintCard.compareDocumentPosition(epicCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(epicCard).toHaveAttribute("data-drill-anchor", "throughput-epics");

    await user.click(screen.getByRole("radio", { name: "Cumulative" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Delivered per sprint, cumulative" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Delivered per sprint, by epic" })).toBeInTheDocument();
    expect(await epicProbe()).toBeInTheDocument();
  });

  test("a report with no segment anywhere draws no by-epic section", async () => {
    serve(mockFetch, THROUGHPUT_TEAM);
    renderPage("/reports/throughput?teamId=1");
    await screen.findByRole("group", { name: "Chart: delivered scope by sprint" });
    expect(screen.queryByRole("heading", { name: /by epic/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Epics in the chart" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: EPIC_TABLE })).not.toBeInTheDocument();
  });

  test("a figure of the table lists what the sprint delivered in that epic: the request, the title, the URL and the focus", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1&lastSprints=6");
    const button = await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-11 Search · Delivered", "Delivered (items)") });
    expect(button).toHaveAccessibleName("2 — Delivered (items): show the items of Alpha 2 · FLO-11 Search · Delivered");
    await user.click(button);

    const drawer = await dialog("Delivered — FLO-11 Search · Alpha 2");
    expect(await within(drawer).findByText("2 items · 6 MD")).toBeInTheDocument();
    // the sprint-view chart count for the segment is 2 items: no note
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=12&teamId=1&epicSegment=102"]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=sprint%3A1%3A12%3ADELIVERED%3Ae%3D102");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });

  test("'Other epics' sends the chart's top epics as topEpicId, a double-click on a segment drills it too", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · Other epics · Delivered", "Delivered (MD)") }));
    await dialog("Delivered — Other epics · Alpha 2");
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=12&teamId=1&epicSegment=OTHER_EPICS&topEpicId=101&topEpicId=102"]);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByTestId("dbl-d3"));
    await dialog("Delivered — No epic · Alpha 2");
    expect(itemsCalls(mockFetch).at(-1)).toBe("/api/v1/reports/throughput/items?sprintId=12&teamId=1&epicSegment=NO_EPIC");
  });

  test("a stale count shows the changed-data note", async () => {
    serve(mockFetch, THROUGHPUT_EPICS, () => jsonResponse(200, epicSegmentItems(5, 6)));
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-11 Search · Delivered", "Delivered (items)") }));
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a link carrying a by-epic drill opens the drawer and, closed, returns focus to the by-epic card", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?teamId=1&drill=sprint:1:12:DELIVERED:e=NO_EPIC");
    const drawer = await dialog("Delivered — No epic · Alpha 2");
    expect(await within(drawer).findByText("2 items · 6 MD")).toBeInTheDocument();
    await userEvent.setup().keyboard("{Escape}");
    await waitFor(() => expect(document.activeElement).toHaveAttribute("data-drill-anchor", "throughput-epics"));
  });

  test("with people picked the section shows their summed figures and its drill has no person select", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const probe = await epicProbe();
    expect(seriesOf(probe)).toHaveLength(4);
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-10 Checkout · Delivered", "Delivered (MD)") }));
    const drawer = await dialog("Delivered — FLO-10 Checkout · Alpha 2");
    expect(await within(drawer).findByText("2 items · 6 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/throughput/items?sprintId=12&teamId=1&accountId=acc-ann&accountId=acc-bob&epicSegment=101"]);
    expect(within(drawer).queryByRole("combobox", { name: "Show the items of" })).not.toBeInTheDocument();
  });
});
