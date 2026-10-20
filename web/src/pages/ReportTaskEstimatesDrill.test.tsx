import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskEstimates from "./ReportTaskEstimates";
import { jsonResponse } from "../test/http";
import { FILTERS, TASK_ESTIMATES, TASK_ESTIMATES_USERS, histogramItems } from "../test/reportFixtures";
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
const REPORT = "/api/v1/reports/task-estimates?";
const ITEMS = "/api/v1/reports/task-estimates/items?";

function serve(mockFetch: FetchMock, items?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, histogramItems(4, 1, 1, 1)));
    if (url.startsWith(REPORT)) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? TASK_ESTIMATES_USERS : TASK_ESTIMATES));
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
        path="/reports/task-estimates"
        element={
          <>
            <ReportTaskEstimates />
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

describe("ReportTaskEstimates page — drilling into a histogram bar", () => {
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

  test("a bar's count lists the tasks estimated up to that figure, the figure read in MD; Back restores the page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-estimates?teamId=1");
    const table = await screen.findByRole("table", { name: "Histogram, as a table — Estimate at done" });
    // The bar ending at 1 MD is the second row: its range reads as the value, "1".
    const button = within(table).getByRole("button", { name: showItems("Estimate at done: 1") });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Estimate at done · 1" });
    expect(await within(drawer).findByText("4 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=AT_DONE&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3AAT_DONE%3A1");
    const items = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(within(items).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Issue", "Summary", "Assignee", "Done", "Estimate (MD)"]);
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1Ann Author2026-09-091");

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a double-click on the at-start chart opens ITS distribution, not the at-done one", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-estimates?teamId=1");
    const group = await screen.findByRole("group", { name: "Histogram: number of items per range — Estimate at start" });
    await user.click(within(group).getByTestId("dbl-count"));
    await screen.findByRole("dialog", { name: "Estimate at start · 1" });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=AT_START&bucketIndex=1`]);
  });

  test("with people picked a segment double-click carries the person, and the chart's share is what the list is checked against", async () => {
    serve(mockFetch, () => jsonResponse(200, histogramItems(2, 1, 1, 1)));
    const user = userEvent.setup();
    renderPage("/reports/task-estimates?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range, stacked by person — Estimate at done",
    });
    await user.click(within(group).getByTestId("dbl-p1"));
    const drawer = await screen.findByRole("dialog", { name: "Estimate at done · 1" });
    expect(calls(mockFetch, ITEMS)).toEqual([
      `${ITEMS}teamId=1&accountId=acc-ann&accountId=acc-bob&view=AT_DONE&bucketIndex=1&segmentAccountId=acc-bob`,
    ]);
    expect(search()).toContain("drill=hist%3AAT_DONE%3A1%3Bs%3Dacc-bob");
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    // Bob's share of this bar in the at-done chart is 1; the list says 2, so the data changed.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("2. Bob Builder");
  });

  test("a link carrying drill= opens the bar once the report is there", async () => {
    serve(mockFetch);
    renderPage("/reports/task-estimates?teamId=1&drill=hist:AT_START:0");
    await screen.findByRole("dialog", { name: "Estimate at start · 0.5" });
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}teamId=1`]);
  });

  test("a drill= naming a view this page does not draw opens nothing, fetches no items and is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/task-estimates?teamId=1&drill=hist:WORKING_DAYS:1");
    await screen.findByRole("group", { name: "Histogram: number of items per range — Estimate at done" });
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });
});
