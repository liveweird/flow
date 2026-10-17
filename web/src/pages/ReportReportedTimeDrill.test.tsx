import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportReportedTime from "./ReportReportedTime";
import { jsonResponse } from "../test/http";
import { FILTERS, REPORTED_TIME, REPORTED_TIME_USERS, histogramItems } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

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
const REPORT = "/api/v1/reports/reported-time-ratio?";
const ITEMS = "/api/v1/reports/reported-time-ratio/items?";

function serve(mockFetch: FetchMock, items?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, histogramItems(4, 0.5, 1, 0.75)));
    if (url.startsWith(REPORT)) {
      const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
      return Promise.resolve(jsonResponse(200, accounts > 1 ? REPORTED_TIME_USERS : REPORTED_TIME));
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
        path="/reports/reported-time-ratio"
        element={
          <>
            <ReportReportedTime />
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

describe("ReportReportedTime page — drilling into a histogram bar", () => {
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

  test("a ratio row's Items button lists the tasks in that range, the figure read as a ratio; Back restores the page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/reported-time-ratio?teamId=1");
    const table = await screen.findByRole("table", {
      name: "Histogram, as a table — Reported time ÷ cycle time",
    });
    const button = within(table).getByRole("button", {
      name: "Show the items of Reported time ÷ cycle time: 0.5 – 1",
    });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", {
      name: "0.5 – 1 · Reported time ÷ cycle time",
    });
    expect(await within(drawer).findByText("4 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=RATIO&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3ARATIO%3A1");
    const items = within(drawer).getByRole("table", {
      name: "Items behind the bar",
    });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Reported ÷ cycle"]);
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1Ann Author2026-09-090.75");

    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a double-click on the flow-efficiency chart opens ITS distribution, the fraction shown as a percentage", async () => {
    serve(mockFetch, () => jsonResponse(200, histogramItems(6, 0.2, 0.4, 0.25)));
    const user = userEvent.setup();
    renderPage("/reports/reported-time-ratio?teamId=1");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range — Flow efficiency",
    });
    await user.click(within(group).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", {
      name: "20% – 40% · Flow efficiency",
    });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=FLOW_EFFICIENCY&bucketIndex=1`]);
    expect(within(drawer).getByRole("columnheader", { name: "Flow efficiency" })).toBeInTheDocument();
    expect(within(within(drawer).getByRole("table", { name: "Items behind the bar" })).getAllByRole("row")[1].textContent).toContain("25%");
  });

  test("with people picked a segment double-click carries the person, and a link with drill= reopens it", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range, stacked by person — Reported time ÷ cycle time",
    });
    await user.click(within(group).getByTestId("dbl-p0"));
    await screen.findByRole("dialog", {
      name: "0.5 – 1 · Reported time ÷ cycle time",
    });
    expect(calls(mockFetch, ITEMS)).toEqual([
      `${ITEMS}teamId=1&accountId=acc-ann&accountId=acc-bob&view=RATIO&bucketIndex=1&segmentAccountId=acc-ann`,
    ]);
    expect(search()).toContain("drill=hist%3ARATIO%3A1%3Bs%3Dacc-ann");
  });

  test("a link carrying drill= opens the bar once the report is there; a view the page did not draw opens nothing", async () => {
    serve(mockFetch);
    renderPage("/reports/reported-time-ratio?teamId=1&drill=hist:FLOW_EFFICIENCY:0");
    await screen.findByRole("dialog", { name: "0% – 20% · Flow efficiency" });
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}teamId=1`]);
  });

  test("a drill= naming another report's view opens nothing, fetches no items and is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/reported-time-ratio?teamId=1&drill=hist:WORKING_DAYS:1");
    await screen.findByRole("group", {
      name: "Histogram: number of items per range — Flow efficiency",
    });
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });
});
