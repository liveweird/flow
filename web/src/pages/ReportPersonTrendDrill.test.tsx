import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ReactElement } from "react";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportPersonTrend from "./ReportPersonTrend";
import { compareItems } from "../test/comparePeopleFixtures";
import { showItems } from "../test/drill";
import { jsonResponse } from "../test/http";
import { PERSON_TREND } from "../test/personTrendFixtures";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { todayIsoDate } from "../utils/isoDate";
import { presetRange } from "../utils/reportFilter";

type Row = Record<string, string | number | null>;
type DotProps = { cx?: number; cy?: number; index: number };
type LineProps = { dot: (props: DotProps) => ReactElement | null; activeDot?: { onClick: (dot: { payload: Row }, event: unknown) => void } };

// recharts renders nothing under happy-dom: each line chart is a probe that draws what the page hands the library — a dot per plotted point
// (the page's own dot renderer, so its click is the real one; a gap has no coordinates and draws nothing) and a button per point that fires the
// ACTIVE dot's click (what recharts forwards, `(dotProps, event)`, `payload` the plotted row).
vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { name: string }[]; data: Row[]; lineProps?: LineProps | ((series: { name: string }) => LineProps) }) => (
    <div data-testid="line-chart">
      {props.series.map((series) => {
        const line = typeof props.lineProps === "function" ? props.lineProps(series) : props.lineProps;
        return props.data.map((row, index) => {
          const plotted = row[series.name] !== null;
          const Dot = line?.dot;
          return (
            <span key={`${series.name}-${row.label}`} data-testid={`point-${series.name}-${row.label}`}>
              {Dot && (
                <svg>
                  <Dot cx={plotted ? 1 : undefined} cy={plotted ? 1 : undefined} index={index} />
                </svg>
              )}
              {plotted && line?.activeDot && <button data-testid={`active-${series.name}-${row.label}`} onClick={() => line.activeDot?.onClick({ payload: row }, {})} />}
            </span>
          );
        });
      })}
    </div>
  ),
  AreaChart: () => <div data-testid="area-chart" />,
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/person-trend?";
const ITEMS = "/api/v1/reports/person-trend/items?";
const YEAR = presetRange("last365", todayIsoDate(FILTERS.timeZone));
const QUERY = `from=${YEAR.from}&to=${YEAR.to}&accountId=acc-ann`;
const ANN = `/reports/person-trend?${QUERY}`;

function serve(mockFetch: FetchMock, items: (url: string) => Response = () => jsonResponse(200, compareItems(7, 21, 3))) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items(url));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, PERSON_TREND));
    if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/person-trend" element={<ReportPersonTrend />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const search = () => screen.getByTestId("where").textContent;
const card = (title: string) => screen.getByRole("heading", { level: 3, name: title }).closest(".mantine-Paper-root") as HTMLElement;
const dotOf = (scope: HTMLElement, series: string, end: string) => within(scope).getByTestId(`point-${series}-${end}`).querySelector("circle");

describe("ReportPersonTrend page — drilling to the items behind a point", () => {
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

  test("a figure of the numbers table lists the point's items — the page's filter plus the row's view and the window's end; Escape returns focus to the figure", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(ANN);
    await screen.findByRole("heading", { level: 3, name: "Delivered (items)" });
    const delivered = card("Delivered (items)");
    expect(within(delivered).getByText("Click a dot — or a figure in the numbers table — to list the items behind that point.")).toBeInTheDocument();
    // The window the heading names is the WHOLE 90 days of the point, not just its last day.
    await user.click(within(delivered).getByRole("button", { name: "Show the numbers" }));
    const button = within(delivered).getByRole("button", { name: showItems("Delivered (items)", "2026-08-11") });
    expect(button).toHaveAccessibleName("7 — 2026-08-11: show the items of Delivered (items)");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Delivered (items) · 2026-05-14 – 2026-08-11" });
    expect(await within(drawer).findByText("7 items · 21 MD")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${QUERY}&view=DELIVERED&pointEnd=2026-08-11`]);
    expect(search()).toBe(`?${QUERY}&drill=point%3AdeliveredItems%3A2026-08-11`);
    // The table said 7 for that window, the list says 7: no note. Every list is the one person's, so there is no person select.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(within(drawer).queryByRole("combobox")).not.toBeInTheDocument();
    expect(
      within(within(drawer).getByRole("table", { name: "Items behind the bar" }))
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Delivered (MD)"]);

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe(`?${QUERY}`);
    expect(button).toHaveFocus();
    // The drill is no filter: the report was requested once.
    expect(calls(mockFetch, REPORT)).toHaveLength(1);
  });

  test("a gap, a withheld figure and a window with nothing derived are plain text in the table, never a button", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(ANN);
    await screen.findByRole("heading", { level: 3, name: "Delivered (items)" });
    const delivered = card("Delivered (items)");
    await user.click(within(delivered).getByRole("button", { name: "Show the numbers" }));
    // The NONE window carries the server's 0 but is drawn as a gap and has no items to open; the four others do.
    expect(within(delivered).getAllByRole("button", { name: /: show the items of Delivered \(items\)$/ })).toHaveLength(4);
    const cycle = card("Cycle time, working days");
    await user.click(within(cycle).getByRole("button", { name: "Show the numbers" }));
    // p90's 2026-08-25 figure is withheld under the minimum sample: text, not a button.
    expect(within(cycle).queryByRole("button", { name: /^— .*2026-08-25/ })).not.toBeInTheDocument();
    expect(within(cycle).getAllByRole("button", { name: /show the items of Cycle time, working days — p90$/ })).toHaveLength(3);
  });

  test("a dot opens the items of ITS row's point: the p90 line of the shared cycle chart asks the p90 row, with its own title", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(ANN);
    const cycle = await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
    const scope = card("Cycle time, working days");
    await waitFor(() => expect(dotOf(scope, "p90", "2026-07-14")).not.toBeNull());
    await user.click(dotOf(scope, "p90", "2026-07-14")!);

    const drawer = await screen.findByRole("dialog", { name: "Cycle time, working days — p90 · 2026-04-16 – 2026-07-14" });
    expect(await within(drawer).findByText("7 items · 21 MD")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${QUERY}&view=CYCLE_WORKING_DAYS&pointEnd=2026-07-14`]);
    expect(search()).toBe(`?${QUERY}&drill=point%3AcycleWorkingDaysP90%3A2026-07-14`);

    // No pressed element for a pointer: Escape returns focus to the card the dot is in.
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(cycle.closest("[data-drill-anchor]")).toHaveFocus();
    expect(cycle.closest("[data-drill-anchor]")).toHaveAttribute("data-drill-anchor", "trend-cycleWorkingDaysP50");
  });

  test("the hovered (active) dot opens the same list; a gap draws no dot and has no active one", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(ANN);
    await screen.findByRole("heading", { level: 3, name: "Delivered (items)" });
    await waitFor(() => expect(screen.getAllByTestId("line-chart")).toHaveLength(13));
    // Delivered (items): the first window has nothing derived, so it is a null point — no dot, no active dot.
    expect(dotOf(card("Delivered (items)"), "value", "2026-06-30")).toBeNull();
    const delivered = within(card("Delivered (items)"));
    expect(delivered.queryByTestId("active-value-2026-06-30")).not.toBeInTheDocument();
    await user.click(delivered.getByTestId("active-value-2026-07-28"));
    expect(await screen.findByRole("dialog", { name: "Delivered (items) · 2026-04-30 – 2026-07-28" })).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${QUERY}&view=DELIVERED&pointEnd=2026-07-28`]);
  });

  test("a drill link opens its drawer on load; a link to a day that is no window, or a row the report lacks, is closed and dropped (never a 400)", async () => {
    serve(mockFetch);
    const first = renderPage(`${ANN}&drill=point%3AdeliveredMd%3A2026-07-14`);
    expect(await screen.findByRole("dialog", { name: "Delivered (MD) · 2026-04-16 – 2026-07-14" })).toBeInTheDocument();
    first.unmount();
    mockFetch.mockClear();
    serve(mockFetch);
    renderPage(`${ANN}&drill=point%3AdeliveredItems%3A2020-01-01`);
    await screen.findByRole("heading", { level: 3, name: "Delivered (items)" });
    await waitFor(() => expect(search()).toBe(`?${QUERY}`));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a list whose size is not the point's n says the data changed since the chart was drawn", async () => {
    serve(mockFetch, () => jsonResponse(200, compareItems(5, 15, 3)));
    renderPage(`${ANN}&drill=point%3AdeliveredItems%3A2026-08-11`);
    const drawer = await screen.findByRole("dialog", { name: "Delivered (items) · 2026-05-14 – 2026-08-11" });
    expect(await within(drawer).findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a share's point has no sample to compare: the list's own size is not flagged", async () => {
    serve(mockFetch, () => jsonResponse(200, compareItems(2, null, 1)));
    renderPage(`${ANN}&drill=point%3AreopenedShare%3A2026-08-11`);
    const drawer = await screen.findByRole("dialog", { name: "Finished tasks that were reopened · 2026-05-14 – 2026-08-11" });
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
  });
});
