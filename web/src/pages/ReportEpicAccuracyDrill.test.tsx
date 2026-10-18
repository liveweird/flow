import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportEpicAccuracy from "./ReportEpicAccuracy";
import { jsonResponse } from "../test/http";
import { EPIC_ACCURACY, FILTERS, histogramItems } from "../test/reportFixtures";
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
const REPORT = "/api/v1/reports/epic-estimation-accuracy?";
const ITEMS = "/api/v1/reports/epic-estimation-accuracy/items?";

function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(jsonResponse(200, histogramItems(2, 1, 1.5, 1.2, "EPIC")));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, EPIC_ACCURACY));
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
        path="/reports/epic-estimation-accuracy"
        element={
          <>
            <ReportEpicAccuracy />
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

describe("ReportEpicAccuracy page — drilling into a histogram bar", () => {
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

  test("a range's count lists the epics in that ratio range, with a dash for the assignee an epic does not have; Back restores", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-estimation-accuracy?teamId=1");
    const table = await screen.findByRole("table", {
      name: "Histogram, as a table — Against the own estimate at start",
    });
    const button = within(table).getByRole("button", {
      name: showItems("Against the own estimate at start: 1 – 1.5"),
    });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", {
      name: "Against the own estimate at start · 1 – 1.5",
    });
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=AT_START&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3AAT_START%3A1");
    const items = within(drawer).getByRole("table", {
      name: "Items behind the bar",
    });
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1—2026-09-091.2");
    // Epics carry no people: no segment select, and the list is the whole bar.
    expect(within(drawer).queryByRole("combobox")).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a double-click on the at-done chart opens ITS distribution, and a link with drill= reopens a bar", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-estimation-accuracy?teamId=1");
    const group = await screen.findByRole("group", {
      name: "Histogram: number of items per range — Against the own estimate at done",
    });
    await user.click(within(group).getByTestId("dbl-count"));
    await screen.findByRole("dialog", {
      name: "Against the own estimate at done · 1 – 1.5",
    });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=AT_DONE&bucketIndex=1`]);
  });

  test("a drill= naming a bar the page did not draw opens nothing, fetches no items and is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-estimation-accuracy?teamId=1&drill=hist:AT_START:99");
    await screen.findByRole("group", {
      name: "Histogram: number of items per range — Against the own estimate at done",
    });
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });
});
