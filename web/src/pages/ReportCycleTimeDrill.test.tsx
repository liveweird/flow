import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCycleTime from "./ReportCycleTime";
import { jsonResponse } from "../test/http";
import { CYCLE_TIME, CYCLE_TIME_TAIL, CYCLE_TIME_USERS, FILTERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

// recharts renders nothing under happy-dom: each histogram is a probe with one "double-click" button per series, firing what
// recharts forwards to a rectangle — the plotted row as payload (the open tail series on the last row, every other on row 1).
vi.mock("@mantine/charts", () => {
  type BarProps = (series: { name: string }) => {
    onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void;
  };
  return {
    BarChart: (props: { data: { label: string }[]; series: { name: string }[]; barProps?: BarProps }) => (
      <div>
        <div data-testid="bar-chart" />
        {typeof props.barProps === "function" &&
          props.series.map((series) => (
            <button
              key={series.name}
              data-testid={`dbl-${series.name}`}
              onClick={() => {
                const row = series.name === "tail" ? props.data.length - 1 : 1;
                props.barProps?.(series).onDoubleClick?.({ payload: props.data[row] }, row, {});
              }}
            />
          ))}
      </div>
    ),
    LineChart: () => <div data-testid="line-chart" />,
    ChartTooltip: () => null,
  };
});

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/cycle-time?";
const ITEMS = "/api/v1/reports/cycle-time/items?";

interface Serve {
  report?: unknown;
  items?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, { report = CYCLE_TIME, items }: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, histogramItems(6, 2, 4, 3.5)));
    if (url.startsWith(REPORT)) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? CYCLE_TIME_USERS : report));
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
        path="/reports/cycle-time"
        element={
          <>
            <ReportCycleTime />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const search = () => screen.getByTestId("where").textContent;
const histogramGroup = (title: string) => `Histogram: number of items per range — ${title}`;

describe("ReportCycleTime page — drilling into a histogram bar", () => {
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

  test("a range's count lists the tasks in that range: the same filter plus the view and the bar, the server's bar line, Back restores the page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/cycle-time?teamId=1");
    await screen.findByRole("group", { name: histogramGroup("Working days") });
    expect(screen.getAllByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toHaveLength(4);
    const table = screen.getByRole("table", {
      name: "Histogram, as a table — Working days",
    });
    expect(
      within(table)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Range", "Items"]);
    const button = within(table).getByRole("button", {
      name: showItems("Working days: 2 – 4"),
    });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", {
      name: "Working days · 2 – 4",
    });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=WORKING_DAYS&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3AWORKING_DAYS%3A1");
    const items = within(drawer).getByRole("table", {
      name: "Items behind the bar",
    });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Working days"]);
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1Ann Author2026-09-093.5");
    // The chart counts 6 for this range, the list says 6: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}teamId=1`]);
  });

  test("a double-click on the elapsed-days chart opens ITS distribution, in elapsed days", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/cycle-time?teamId=1");
    const group = await screen.findByRole("group", {
      name: histogramGroup("Elapsed days"),
    });
    await user.click(within(group).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", {
      name: "Elapsed days · 3 – 6",
    });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=ELAPSED_DAYS&bucketIndex=1`]);
    expect(within(drawer).getByRole("columnheader", { name: "Elapsed days" })).toBeInTheDocument();
  });

  test("the open tail bar drills like any other, by its index", async () => {
    serve(mockFetch, {
      report: CYCLE_TIME_TAIL,
      items: () => jsonResponse(200, histogramItems(2, 8, null, 9)),
    });
    const user = userEvent.setup();
    renderPage("/reports/cycle-time?teamId=1");
    const group = await screen.findByRole("group", {
      name: histogramGroup("Working days"),
    });
    await user.click(within(group).getByTestId("dbl-tail"));
    const drawer = await screen.findByRole("dialog", {
      name: "Working days · ≥ 8",
    });
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=WORKING_DAYS&bucketIndex=4`]);
  });

  test("an epic histogram drills to epics, and the stale-count note appears when the list disagrees with the bar", async () => {
    serve(mockFetch, {
      items: () => jsonResponse(200, histogramItems(3, 5, 10, 8, "EPIC")),
    });
    const user = userEvent.setup();
    renderPage("/reports/cycle-time?teamId=1");
    const table = await screen.findByRole("table", {
      name: "Histogram, as a table — Epics: working days",
    });
    await user.click(
      within(table).getByRole("button", {
        name: showItems("Epics: working days: 5 – 10"),
      }),
    );
    const drawer = await screen.findByRole("dialog", {
      name: "Epics: working days · 5 – 10",
    });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=EPIC_WORKING_DAYS&bucketIndex=1`]);
    expect(await within(drawer).findByText("3 items")).toBeInTheDocument();
    // The epics chart drew 2 here, the list says 3: the data changed.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("with people picked a segment double-click lists that person's share, and the drawer's select moves between people", async () => {
    const bob = {
      ...histogramItems(2, 2, 4, 3.5),
      items: histogramItems(2, 2, 4, 3.5).items.map((item) => ({
        ...item,
        accountId: "acc-bob",
      })),
    };
    serve(mockFetch, {
      items: (url) => jsonResponse(200, url.includes("segmentAccountId=acc-bob") ? bob : histogramItems(6, 2, 4, 3.5)),
    });
    const user = userEvent.setup();
    renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range, stacked by person — Working days",
    });
    await user.click(within(group).getByTestId("dbl-p1"));
    const drawer = await screen.findByRole("dialog", {
      name: "Working days · 2 – 4",
    });
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([
      `${ITEMS}teamId=1&accountId=acc-ann&accountId=acc-bob&view=WORKING_DAYS&bucketIndex=1&segmentAccountId=acc-bob`,
    ]);
    // Bob's share of this range in the chart is 2, the list says 2: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(search()).toContain("drill=hist%3AWORKING_DAYS%3A1%3Bs%3Dacc-bob");
    expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("2. Bob Builder");

    await user.click(within(drawer).getByRole("combobox", { name: "Show the items of" }));
    await user.click(await screen.findByRole("option", { name: "Everyone" }));
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(search()).toMatch(/drill=hist%3AWORKING_DAYS%3A1$/);
  });

  test("a link carrying drill= opens the bar once the report is there; a view the page did not draw, a bucket link or garbage opens nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/cycle-time?teamId=1&drill=hist:WORKING_DAYS:1");
    const drawer = await screen.findByRole("dialog", {
      name: "Working days · 2 – 4",
    });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    // The drill is no filter: the report request never carries it.
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}teamId=1`]);
  });

  test.each(["hist:NOPE:1", "hist:WORKING_DAYS:99", "hist:RATIO:0"])(
    "drill=%s names a bar the page did not draw: it opens nothing, fetches no items and is dropped from the URL",
    async (drill) => {
      serve(mockFetch);
      renderPage(`/reports/cycle-time?teamId=1&drill=${drill}`);
      await screen.findByRole("group", { name: histogramGroup("Working days") });
      await waitFor(() => expect(search()).toBe("?teamId=1"));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(calls(mockFetch, ITEMS)).toEqual([]);
    },
  );

  test.each(["bucket:2026-09-07", "nonsense"])("drill=%s is another page's or garbage: it opens nothing and fetches no items", async (drill) => {
    serve(mockFetch);
    renderPage(`/reports/cycle-time?teamId=1&drill=${drill}`);
    await screen.findByRole("group", { name: histogramGroup("Working days") });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a link to an epic bar at USER level (the epics block is empty there) opens nothing and is dropped", async () => {
    serve(mockFetch);
    renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob&drill=hist:EPIC_WORKING_DAYS:0");
    await screen.findByRole("group", { name: "Histogram: number of items per range, stacked by person — Working days" });
    await waitFor(() => expect(search()).toBe("?teamId=1&accountId=acc-ann&accountId=acc-bob"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("the epics block at USER level has no histogram, so nothing there to drill", async () => {
    serve(mockFetch);
    renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
    await screen.findByRole("group", {
      name: "Histogram: number of items per range, stacked by person — Working days",
    });
    expect(
      screen.queryByRole("table", {
        name: "Histogram, as a table — Epics: working days",
      }),
    ).not.toBeInTheDocument();
  });
});
