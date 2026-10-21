import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCommitments from "./ReportCommitments";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { showItems } from "../test/drill";
import { COMMITMENTS_EPICS, COMMITMENTS_EPICS_USERS, epicSegmentItems } from "../test/epicFixtures";
import { COMMITMENTS_TEAM, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props; every series gets a button that
// fires the `onDoubleClick` recharts would forward to its rectangles, with the LAST row (the latest sprint) as the payload.
vi.mock("@mantine/charts", () => {
  type BarProps = { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  type Series = { name: string; label?: string; color?: string; stackId?: string };
  const probe = (testId: string) => (props: { data: { label: string }[]; series: Series[]; barProps?: unknown }) => (
    <div>
      <div
        data-testid={testId}
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
  return { CompositeChart: probe("composite-chart"), BarChart: probe("bar-chart"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;
type Plotted = { name: string; label: string; color: string; stackId: string };

function serve(mockFetch: FetchMock, report = COMMITMENTS_EPICS, items: (url: string) => Response = () => jsonResponse(200, epicSegmentItems(3, 8))) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/sprint-consistency/items?")) return Promise.resolve(items(url));
    if (url.startsWith("/api/v1/reports/commitments?")) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? COMMITMENTS_EPICS_USERS : report));
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
        path="/reports/commitments"
        element={
          <>
            <ReportCommitments />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const EPIC_CHART = "Chart: initial and final scope by sprint, stacked by epic";
const EPIC_TABLE = "Initial and final scope per sprint and epic";
const itemsCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/sprint-consistency/items?"));
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });
/** The probe inside the epic chart (waits for the lazy chunk). */
async function epicProbe(): Promise<HTMLElement> {
  return within(await screen.findByRole("group", { name: EPIC_CHART })).getByTestId("bar-chart");
}
const seriesOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-series") ?? "[]") as Plotted[];
const rowsOf = (probe: HTMLElement) => JSON.parse(probe.getAttribute("data-rows") ?? "[]") as Record<string, number | string>[];
/** A table's body rows as their cells' text. */
const cellsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent ?? ""));

describe("ReportCommitments page — the by-epic section", () => {
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

  test("a card under the main chart: its title, what it is for, the legend naming every segment, the chart's two stacks and the rule", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&lastSprints=6");
    expect(await screen.findByRole("heading", { level: 3, name: "Initial and final scope per sprint, by epic" })).toBeInTheDocument();
    expect(screen.getByText("How many epics each sprint's scope spreads over — at its start (outlined column) and at its end (solid column).")).toBeInTheDocument();
    const group = await screen.findByRole("group", { name: EPIC_CHART });
    // The legend names every segment (colour is never the only carrier); the special keys are localised, not the server's fallback text.
    expect(within(within(group).getByRole("list", { name: "Epics in the chart" })).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "FLO-10 Checkout",
      "FLO-11 Search",
      "Other epics",
      "No epic",
      "Initial (left column, outlined)",
      "Final (right column, solid)",
    ]);
    const probe = await epicProbe();
    expect(rowsOf(probe).map((row) => row.label)).toEqual(["Alpha 1", "Alpha 2"]);
    expect(seriesOf(probe).map((series) => `${series.stackId}:${series.name}:${series.label}`)).toEqual([
      "i:i0:FLO-10 Checkout · initial",
      "i:i1:FLO-11 Search · initial",
      "i:i2:Other epics · initial",
      "i:i3:No epic · initial",
      "f:f0:FLO-10 Checkout · final",
      "f:f1:FLO-11 Search · final",
      "f:f2:Other epics · final",
      "f:f3:No epic · final",
    ]);
    expect(screen.getByText(/The six epics with the most work in the period have a colour of their own/)).toBeInTheDocument();
    // the hint is the card's own: once for the main chart, once for this one
    expect(screen.getAllByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toHaveLength(2);
  });

  test("an epic keeps its colour in both stacks and every sprint; the top epics wear the palette, 'Other epics' the gray and 'No epic' its own neutral", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1");
    const series = seriesOf(await epicProbe());
    const colours = (stack: string) => series.filter((s) => s.stackId === stack).map((s) => s.color);
    expect(colours("i")).toEqual(["flow.6", "violet.5", "gray.6", "gray.8"]);
    // initial and final are the same segments in the same colours: one series list serves every sprint, so a colour cannot move between sprints
    expect(colours("f")).toEqual(colours("i"));
    // Alpha 1 lacks the second epic: its row holds 0 there rather than shifting the others
    expect(rowsOf(await epicProbe())[0]).toMatchObject({ label: "Alpha 1", i0: 9, i1: 0, i2: 0, i3: 6 });
  });

  test("in Polish the special segments are 'Pozostałe epiki' and 'Bez epiku'", async () => {
    await i18n.changeLanguage("pl");
    try {
      serve(mockFetch);
      renderPage("/reports/commitments?teamId=1");
      const group = await screen.findByRole("group", { name: "Wykres: zakres początkowy i końcowy według sprintów, ułożony według epików" });
      const entries = within(within(group).getByRole("list", { name: "Epiki na wykresie" })).getAllByRole("listitem").map((item) => item.textContent);
      expect(entries.slice(0, 4)).toEqual(["FLO-10 Checkout", "FLO-11 Search", "Pozostałe epiki", "Bez epiku"]);
      expect(screen.getByRole("heading", { level: 3, name: "Zakres początkowy i końcowy w sprintach, według epików" })).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("the table lists every sprint × segment, the epic as its key and summary, and its sums are the sprint rows' totals", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&lastSprints=6");
    const table = await screen.findByRole("table", { name: EPIC_TABLE });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Sprint",
      "Epic",
      "Initial (MD)",
      "Initial (items)",
      "Final (MD)",
      "Final (items)",
    ]);
    const rows = cellsOf(table);
    expect(rows).toEqual([
      ["Alpha 1", "FLO-10 Checkout", "9", "3", "10", "3"],
      ["Alpha 1", "No epic", "6", "3", "6", "3"],
      ["Alpha 2", "FLO-10 Checkout", "8", "3", "10", "3"],
      ["Alpha 2", "FLO-11 Search", "6", "2", "7.5", "3"],
      ["Alpha 2", "Other epics", "4", "2", "4", "2"],
      ["Alpha 2", "No epic", "2", "1", "3", "1"],
    ]);
    // Σ segments == the sprint row of the table above, in all four figures
    const sprints = cellsOf(screen.getByRole("table", { name: "Sprints and their figures" }));
    for (const sprint of ["Alpha 1", "Alpha 2"]) {
      const total = (column: number) => rows.filter((row) => row[0] === sprint).reduce((sum, row) => sum + Number(row[column]), 0);
      const own = sprints.find((row) => row[0] === sprint)!;
      // sprint, team, completed, initial MD, items, final MD, items, …
      expect([total(2), total(3), total(4), total(5)]).toEqual([3, 4, 5, 6].map((column) => Number(own[column])));
    }
  });

  test("a report with no segment anywhere draws no by-epic section, and the rest of the page is untouched", async () => {
    serve(mockFetch, COMMITMENTS_TEAM);
    renderPage("/reports/commitments?teamId=1");
    expect(await screen.findByTestId("composite-chart")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /by epic/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Epics in the chart" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: EPIC_TABLE })).not.toBeInTheDocument();
  });

  test("a figure of the table drills the sprint's bucket narrowed to the epic: the request, the title, the URL and the focus", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1&lastSprints=6");
    const button = await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-10 Checkout · Committed", "Initial (MD)") });
    expect(button).toHaveAccessibleName("8 — Initial (MD): show the items of Alpha 2 · FLO-10 Checkout · Committed");
    await user.click(button);

    const drawer = await dialog("Committed — FLO-10 Checkout · Alpha 2");
    // the server's bar of the segment reconciles with the chart's figure (3 items): no note
    expect(await within(drawer).findByText("3 items · 8 MD")).toBeInTheDocument();
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=COMMITTED&epicSegment=101"]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=sprint%3A1%3A12%3ACOMMITTED%3Ae%3D101");

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1&lastSprints=6");
    expect(button).toHaveFocus();
  });

  test("'Other epics' sends the chart's own top epics as topEpicId; 'No epic' just the fixed key", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · Other epics · Final", "Final (items)") }));
    await dialog("Final — Other epics · Alpha 2");
    expect(itemsCalls(mockFetch).at(-1)).toBe(
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=FINAL&epicSegment=OTHER_EPICS&topEpicId=101&topEpicId=102",
    );
    expect(search()).toContain("drill=sprint%3A1%3A12%3AFINAL%3Ae%3DOTHER_EPICS%2C101%2C102");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: showItems("Alpha 1 · No epic · Committed", "Initial (MD)") }));
    await dialog("Committed — No epic · Alpha 1");
    expect(itemsCalls(mockFetch).at(-1)).toBe("/api/v1/reports/sprint-consistency/items?sprintId=11&teamId=1&bucket=COMMITTED&epicSegment=NO_EPIC");
  });

  test("a double-click on a segment of the chart drills it: the final stack's second epic of the latest sprint", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await epicProbe();
    await user.click(screen.getByTestId("dbl-f1"));
    await dialog("Final — FLO-11 Search · Alpha 2");
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=FINAL&epicSegment=102"]);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByTestId("dbl-i2"));
    await dialog("Committed — Other epics · Alpha 2");
    expect(itemsCalls(mockFetch).at(-1)).toContain("&bucket=COMMITTED&epicSegment=OTHER_EPICS&topEpicId=101&topEpicId=102");
  });

  test("a stale count shows the changed-data note", async () => {
    serve(mockFetch, COMMITMENTS_EPICS, () => jsonResponse(200, epicSegmentItems(5, 8)));
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1");
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-10 Checkout · Committed", "Initial (items)") }));
    expect(await screen.findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a link carrying an 'Other epics' drill opens the drawer, names the segment and sends the top epics", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&drill=sprint:1:12:COMMITTED:e=OTHER_EPICS,101,102");
    const drawer = await dialog("Committed — Other epics · Alpha 2");
    expect(await within(drawer).findByText("3 items · 8 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=COMMITTED&epicSegment=OTHER_EPICS&topEpicId=101&topEpicId=102",
    ]);
    // closing a drill nobody clicked open returns focus to the by-epic card, not the main chart's
    await userEvent.setup().keyboard("{Escape}");
    await waitFor(() => expect(document.activeElement).toHaveAttribute("data-drill-anchor", "commitments-epics"));
  });

  test("a garbled epic part of a drill link opens nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/commitments?teamId=1&drill=sprint:1:12:COMMITTED:e=OTHER_EPICS");
    await epicProbe();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
  });

  test("with people picked the section shows their summed figures and a by-epic drill has no person select", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/commitments?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const probe = await epicProbe();
    expect(seriesOf(probe)).toHaveLength(8);
    await user.click(await screen.findByRole("button", { name: showItems("Alpha 2 · FLO-10 Checkout · Committed", "Initial (items)") }));
    const drawer = await dialog("Committed — FLO-10 Checkout · Alpha 2");
    expect(await within(drawer).findByText("3 items · 8 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&accountId=acc-ann&accountId=acc-bob&bucket=COMMITTED&epicSegment=101",
    ]);
    expect(within(drawer).queryByRole("combobox", { name: "Show the items of" })).not.toBeInTheDocument();
  });
});
