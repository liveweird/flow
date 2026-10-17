import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportSprintConsistency from "./ReportSprintConsistency";
import { jsonResponse } from "../test/http";
import { CONSISTENCY_SLICED, CONSISTENCY_TEAM, FILTERS, SPRINT_ITEMS } from "../test/reportFixtures";
import { showItems } from "../test/drill";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe; every series gets a button that fires the
// `onDoubleClick` recharts would forward to its rectangles, with the first row as the payload.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: { label: string }[];
    series: { name: string }[];
    barProps: (series: unknown) => { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  }) => (
    <div>
      <div data-testid="bar-chart" data-series={props.series.map((s) => s.name).join(",")} />
      {props.series.map((series) => (
        <button
          key={series.name}
          data-testid={`dbl-${series.name}`}
          // A bar is an svg path: pressing it focuses nothing (a button would take focus and become the focus-return target).
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => props.barProps(series).onDoubleClick?.({ payload: props.data[0] }, 0, {})}
        />
      ))}
    </div>
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;

/** The items of a bucket: `count` of the fixture's tasks, the bar the server recomputes over them. */
const bucketItems = (count: number, sumMd: number) => ({
  ...SPRINT_ITEMS,
  bar: { count, sumMd, from: null, to: null },
  items: SPRINT_ITEMS.items.slice(0, count),
  total: count,
});

function serve(mockFetch: FetchMock, items: (url: string) => Response = () => jsonResponse(200, SPRINT_ITEMS)) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/sprint-consistency/items?")) return Promise.resolve(items(url));
    if (url.startsWith("/api/v1/reports/sprint-consistency?")) {
      const params = new URL(url, "http://x").searchParams;
      const sliced = params.has("activityType") || params.has("workCategory");
      return Promise.resolve(jsonResponse(200, sliced ? CONSISTENCY_SLICED : CONSISTENCY_TEAM));
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
        path="/reports/sprint-consistency"
        element={
          <>
            <ReportSprintConsistency />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/sprint-consistency/items?"));
const search = () => screen.getByTestId("where").textContent;
const dialog = (name: string) => screen.findByRole("dialog", { name });
const chartGroup = (name: string) => screen.findByRole("group", { name });
const SCOPE = "Chart: committed and delivered scope by sprint";
const PARTITION = "Chart: final scope split into carried over, delivered and dropped, by sprint";
const CHANGES = "Chart: scope added and removed during each sprint";

describe("ReportSprintConsistency page — drilling into a sprint's bucket", () => {
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

  test("each of a sprint row's seven figures is a button for its bucket; Final (drawn by no chart) opens the sprint's drawer with the URL, and Back returns focus to the figure", async () => {
    serve(mockFetch, () => jsonResponse(200, bucketItems(8, 24.5)));
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1&lastSprints=6");
    expect(await screen.findAllByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toHaveLength(3);
    expect(screen.getByText("Click a figure in the table to list the items behind it.")).toBeInTheDocument();
    // No Items column and no row menu: every bucket's figure is the way in.
    expect(screen.queryByRole("columnheader", { name: "Items" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Operations for Alpha 2" })).not.toBeInTheDocument();
    const buttons = await screen.findAllByRole("button", { name: /show the items of Alpha 2 · / });
    // Each name starts with the visible figure (WCAG 2.5.3), then the column, then the row and bucket.
    expect(buttons.map((button) => button.getAttribute("aria-label"))).toEqual([
      "20 (8) — Committed: show the items of Alpha 2 · Committed",
      "4.5 (2) — Added: show the items of Alpha 2 · Added",
      "1 (1) — Removed: show the items of Alpha 2 · Removed",
      "24.5 (10) — Final: show the items of Alpha 2 · Final",
      "18 (7) — Delivered: show the items of Alpha 2 · Delivered",
      "4 (2) — Carried over: show the items of Alpha 2 · Carried over",
      "2.5 (1) — Dropped: show the items of Alpha 2 · Dropped",
    ]);
    const final = buttons[3];
    expect(final).toHaveTextContent("24.5 (10)");
    await user.click(final);

    const drawer = await dialog("Alpha 2 · Final");
    expect(await within(drawer).findByText("8 items · 24.5 MD")).toBeInTheDocument();
    // The same filter, its period narrowed to the ONE sprint, plus the bucket.
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=FINAL"]);
    expect(search()).toBe("?teamId=1&lastSprints=6&drill=sprint%3A1%3A12%3AFINAL");
    // The table says 10 final items, the list 8: the data moved since the chart was drawn.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    // No members on this page, so no person select and no live-figures caption without a slice.
    expect(within(drawer).queryByRole("combobox")).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/Sliced by activity type/)).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1&lastSprints=6");
    expect(final).toHaveFocus();
  });

  test.each([
    { chart: SCOPE, series: "committedMd", title: "Alpha 2 · Committed", bucket: "COMMITTED", count: 8, md: 20 },
    { chart: SCOPE, series: "deliveredMd", title: "Alpha 2 · Delivered", bucket: "DELIVERED", count: 7, md: 18 },
    { chart: PARTITION, series: "carriedOverMd", title: "Alpha 2 · Carried over", bucket: "CARRIED_OVER", count: 2, md: 4 },
    { chart: PARTITION, series: "deliveredMd", title: "Alpha 2 · Delivered", bucket: "DELIVERED", count: 7, md: 18 },
    { chart: PARTITION, series: "droppedMd", title: "Alpha 2 · Dropped", bucket: "DROPPED", count: 1, md: 2.5 },
    { chart: CHANGES, series: "addedMd", title: "Alpha 2 · Added", bucket: "ADDED", count: 2, md: 4.5 },
    { chart: CHANGES, series: "removedMd", title: "Alpha 2 · Removed", bucket: "REMOVED", count: 1, md: 1 },
  ])("a double-click on the $series bar of '$chart' lists the sprint's $bucket items, equal to the table's count", async ({ chart, series, title, bucket, count, md }) => {
    serve(mockFetch, () => jsonResponse(200, bucketItems(count, md)));
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1");
    await user.click(within(await chartGroup(chart)).getByTestId(`dbl-${series}`));

    const drawer = await dialog(title);
    expect(await within(drawer).findByText(`${count} ${count === 1 ? "item" : "items"} · ${md} MD`)).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([`/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=${bucket}`]);
    expect(search()).toBe(`?teamId=1&drill=sprint%3A1%3A12%3A${bucket}`);
    // The chart's figure and the list agree: no stale-data note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
  });

  test("closing a pointer-opened drill puts focus back on the chart's card", async () => {
    serve(mockFetch, () => jsonResponse(200, bucketItems(2, 4)));
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1");
    await user.click(within(await chartGroup(PARTITION)).getByTestId("dbl-carriedOverMd"));
    await dialog("Alpha 2 · Carried over");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // The partition card (the one that was double-clicked), not the first card on the page.
    const card = screen.getByRole("heading", { name: "Where the final scope ended up" }).closest("[data-drill-anchor]");
    await waitFor(() => expect(card).toHaveFocus());
    expect(card).toHaveAttribute("data-drill-anchor", "consistency-partition");
  });

  test("with a slice active the drawer repeats the live-figures note and the request carries the slice", async () => {
    serve(mockFetch, () => jsonResponse(200, bucketItems(3, 5)));
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1&activityType=Bug");
    await user.click(within(await chartGroup(SCOPE)).getByTestId("dbl-committedMd"));
    const drawer = await dialog("Alpha 2 · Committed");
    expect(await within(drawer).findByText(/Sliced by activity type or work category: these figures are read live/)).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&activityType=Bug&bucket=COMMITTED"]);
  });

  test("a link carrying drill= opens the drawer; a garbled one opens nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/sprint-consistency?teamId=1&drill=sprint:1:12:CARRIED_OVER");
    const drawer = await dialog("Alpha 2 · Carried over");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
  });

  test("a garbled drill= opens nothing and fetches nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/sprint-consistency?teamId=1&drill=sprint:1:12:EVERYTHING");
    await screen.findAllByTestId("bar-chart");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);
  });

  test("a failed drill request is told inside the drawer, and the charts stay", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1");
    await user.click(within(await chartGroup(CHANGES)).getByTestId("dbl-removedMd"));
    const drawer = await dialog("Alpha 2 · Removed");
    expect(await within(drawer).findByRole("alert")).toBeInTheDocument();
    expect(screen.getAllByTestId("bar-chart")).toHaveLength(3);
  });

  test("a bucket with no items has nothing to list: its figure is plain text, not a button", async () => {
    const [sprint] = CONSISTENCY_TEAM.sprints;
    const noRemovals = { ...CONSISTENCY_TEAM, sprints: [{ ...sprint, removedMd: 0, removedItems: 0 }] };
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/reports/filters" ? jsonResponse(200, FILTERS) : jsonResponse(200, noRemovals)),
    );
    renderPage("/reports/sprint-consistency?teamId=1");
    const committed = await screen.findByRole("button", { name: showItems("Alpha 2 · Committed") });
    const row = committed.closest("tr") as HTMLElement;
    // Six of the seven figures are buttons; Removed (0 items) is the cell's text.
    expect(within(row).getAllByRole("button")).toHaveLength(6);
    expect(within(row).queryByRole("button", { name: showItems("Alpha 2 · Removed") })).not.toBeInTheDocument();
    expect(within(row).getByText("0 (0)")).toBeInTheDocument();
  });

  test("while a filter change shows the previous data the figures are plain text, not buttons, and bars open nothing", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("lastSprints=6")) return deferred;
      return Promise.resolve(jsonResponse(200, CONSISTENCY_TEAM));
    });
    const user = userEvent.setup();
    renderPage("/reports/sprint-consistency?teamId=1");
    const committed = await screen.findByRole("button", { name: showItems("Alpha 2 · Committed") });
    // The sprint row: sprint, team, completed, the seven figures, drift.
    const row = committed.closest("tr") as HTMLElement;
    expect(within(row).getAllByRole("cell")).toHaveLength(11);
    expect(within(row).getAllByRole("button")).toHaveLength(7);
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: /show the items of Alpha 2 · / })).not.toBeInTheDocument());
    expect(screen.getByText("20 (8)")).toBeInTheDocument();
    await user.click(screen.getAllByTestId("dbl-committedMd")[0]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual([]);

    release?.(jsonResponse(200, CONSISTENCY_TEAM));
    await waitFor(() => expect(screen.getAllByRole("button", { name: /show the items of Alpha 2 · / })).toHaveLength(7));
  });
});
