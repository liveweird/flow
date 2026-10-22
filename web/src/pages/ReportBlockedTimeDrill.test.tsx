import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportBlockedTime from "./ReportBlockedTime";
import { jsonResponse } from "../test/http";
import { BLOCKED_TEAM_BOTH, BLOCKED_TIME, FILTERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

// recharts renders nothing under happy-dom: each histogram is a probe with one "double-click" button per series, firing what
// recharts forwards to a rectangle — the plotted row (row 1) as payload.
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
              onClick={() => props.barProps?.(series).onDoubleClick?.({ payload: props.data[1] }, 1, {})}
            />
          ))}
      </div>
    ),
  };
});

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/blocked-time?";
const ITEMS = "/api/v1/reports/blocked-time/items?";

function serve(mockFetch: FetchMock, items?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, histogramItems(4, 1, 2, 1.5)));
    if (url.startsWith(REPORT)) {
      const both = new URL(url, "http://x").searchParams.get("itemKind") === "BOTH";
      return Promise.resolve(jsonResponse(200, both ? BLOCKED_TEAM_BOTH : BLOCKED_TIME));
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
        path="/reports/blocked-time"
        element={
          <>
            <ReportBlockedTime />
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

describe("ReportBlockedTime page — drilling into a histogram bar", () => {
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

  test("a blocked-days range's count lists the items in that range, the item kind sent explicitly; Back restores the page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/blocked-time?teamId=1");
    const table = await screen.findByRole("table", {
      name: "Histogram, as a table — Blocked working days",
    });
    const button = within(table).getByRole("button", {
      name: showItems("Blocked working days: 2 – 3"),
    });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", {
      name: "Blocked working days · 2 – 3",
    });
    expect(await within(drawer).findByText("4 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&itemKind=TASK&view=BLOCKED_WORKING_DAYS&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3ABLOCKED_WORKING_DAYS%3A1");
    const items = within(drawer).getByRole("table", {
      name: "Items behind the bar",
    });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Blocked working days"]);

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a double-click on the share chart opens ITS distribution, the fraction shown as a percentage", async () => {
    serve(mockFetch, () => jsonResponse(200, histogramItems(3, 0.2, 0.4, 0.25)));
    const user = userEvent.setup();
    renderPage("/reports/blocked-time");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range — Blocked share of cycle",
    });
    await user.click(within(group).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", {
      name: "Blocked share of cycle · 20% – 40%",
    });
    expect(await within(drawer).findByText("3 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&itemKind=TASK&view=SHARE_OF_CYCLE&bucketIndex=1`]);
    expect(
      within(drawer).getByRole("columnheader", {
        name: "Blocked share of cycle",
      }),
    ).toBeInTheDocument();
    expect(within(within(drawer).getByRole("table", { name: "Items behind the bar" })).getAllByRole("row")[1].textContent).toContain("25%");
  });

  test("with tasks and epics in the population the request carries the kind and a mixed list says which row is which", async () => {
    const epic = histogramItems(1, 1, 2, 3, "EPIC").items[0];
    const mixed = histogramItems(2, 1, 2, 1.5);
    serve(mockFetch, () =>
      jsonResponse(200, {
        ...mixed,
        bar: { ...mixed.bar, count: 3 },
        total: 3,
        items: [epic, ...mixed.items],
      }),
    );
    const user = userEvent.setup();
    renderPage("/reports/blocked-time?itemKind=BOTH");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range — Blocked working days",
    });
    await user.click(within(group).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", {
      name: "Blocked working days · 2 – 3",
    });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&itemKind=BOTH&view=BLOCKED_WORKING_DAYS&bucketIndex=1`]);
    const items = await within(drawer).findByRole("table", {
      name: "Items behind the bar",
    });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Kind", "Assignee", "Done", "Blocked working days"]);
    const rows = within(items).getAllByRole("row");
    expect(rows[1].textContent).toBe("FLO-1Item 1Epic—2026-09-093");
    expect(rows[2].textContent).toContain("Task");
  });

  test("with tasks and epics requested the Kind column stays on a bucket that holds only one kind", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/blocked-time?itemKind=BOTH");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range — Blocked working days",
    });
    await user.click(within(group).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", { name: "Blocked working days · 2 – 3" });
    const items = await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Kind", "Assignee", "Done", "Blocked working days"]);
    expect(within(items).getAllByRole("row")[1].textContent).toContain("Task");
  });

  test("a drill= naming a view this page does not draw opens nothing, fetches no items and is dropped from the URL", async () => {
    serve(mockFetch);
    // With its team, as every drill link the page writes: a team-less link is normalised to the default team, which races the drop
    // of the drill (order-dependent — BACKLOG "A drill link without a team loses its drill").
    renderPage("/reports/blocked-time?teamId=1&drill=hist:RATIO:1");
    await screen.findByRole("group", {
      name: "Histogram: number of items per range — Blocked working days",
    });
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });
});
