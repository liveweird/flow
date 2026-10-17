import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import { AGING_UNIT, CONSISTENCY_UNIT, CYCLE_TIME, DATA_QUALITY, FILTERS, SPRINT_ITEMS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

// recharts renders nothing under happy-dom (no layout): the bar chart is a probe whose series carry a button firing the
// `onDoubleClick` recharts would forward to the series' rectangles (the first row as the payload); the line chart is inert.
vi.mock("@mantine/charts", () => {
  type BarProps = { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };
  return {
    BarChart: (props: { data: unknown[]; series: { name: string }[]; barProps?: (series: unknown) => BarProps }) => (
      <div data-testid="bar-chart">
        {props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`dbl-${series.name}`}
            onClick={() => props.barProps?.(series).onDoubleClick?.({ payload: props.data[0] }, 0, {})}
          />
        ))}
      </div>
    ),
    LineChart: () => <div data-testid="line-chart" />,
    ChartTooltip: () => null,
  };
});

type FetchMock = ReturnType<typeof vi.fn>;

function serve(mockFetch: FetchMock, items: (url: string) => Response = () => jsonResponse(200, SPRINT_ITEMS)) {
  mockFetch.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/reports/sprint-consistency/items?")) return Promise.resolve(items(url));
    const answers: Record<string, unknown> = {
      "/api/v1/reports/filters": FILTERS,
      "/api/v1/reports/sprint-consistency?lastSprints=1": CONSISTENCY_UNIT,
      "/api/v1/reports/cycle-time?": CYCLE_TIME,
      "/api/v1/reports/aging-wip?": AGING_UNIT,
      "/api/v1/reports/data-quality?": DATA_QUALITY,
    };
    return Promise.resolve(url in answers ? jsonResponse(200, answers[url]) : jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderHome(route = "/") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <>
            <Home />
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
const dialog = (name: string) => screen.findByRole("dialog", { name });

describe("Home page — drilling into the commitments chart", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("a team row's Initial, Final and Delivered figures are buttons; pressing one lists that sprint's items, with no filter but the sprint and its team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    const tile = await screen.findByRole("region", { name: "Commitments and velocity" });
    await within(tile).findByTestId("bar-chart");
    expect(within(tile).getByText("Double-click a bar to list the items behind it — or click a figure in the table.")).toBeInTheDocument();
    // No Items column and no row menu any more: the figures are the way in.
    expect(within(tile).queryByRole("columnheader", { name: "Items" })).not.toBeInTheDocument();
    expect(within(tile).queryByRole("button", { name: "Operations for Alpha · Alpha 2" })).not.toBeInTheDocument();
    const initial = within(tile).getByRole("button", { name: showItems("Alpha · Alpha 2 · Committed") });
    expect(initial).toHaveTextContent("20");
    expect(within(tile).getByRole("button", { name: showItems("Alpha · Alpha 2 · Final") })).toHaveTextContent("24.5");
    const delivered = within(tile).getByRole("button", { name: showItems("Alpha · Alpha 2 · Delivered") });
    expect(delivered).toHaveTextContent("18");
    await user.click(delivered);

    const drawer = await dialog("Alpha · Alpha 2 · Delivered");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=DELIVERED"]);
    expect(screen.getByTestId("where").textContent).toBe("?drill=sprint%3A1%3A12%3ADELIVERED");
    // Alpha 2 delivered 7 items on the tile, the list says 8: the data changed since the chart was drawn.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByTestId("where").textContent).toBe("");
    // Focus is back on the figure that opened the drawer.
    expect(delivered).toHaveFocus();
  });

  test.each([
    ["initialMd", "COMMITTED", "Committed", 8],
    ["finalMd", "FINAL", "Final", 10],
    ["deliveredMd", "DELIVERED", "Delivered", 7],
  ])("a double-click on the %s bar lists the %s items of that team's sprint", async (series, bucket, label, chartItems) => {
    serve(mockFetch, () => jsonResponse(200, { ...SPRINT_ITEMS, bar: { ...SPRINT_ITEMS.bar, count: chartItems } }));
    const user = userEvent.setup();
    renderHome();
    await user.click(await screen.findByTestId(`dbl-${series}`));
    const drawer = await dialog(`Alpha · Alpha 2 · ${label}`);
    expect(itemsCalls(mockFetch)).toEqual([`/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=${bucket}`]);
    expect(await within(drawer).findByText(`${chartItems} items · 20 MD`)).toBeInTheDocument();
    // The tile's own item count for this bar equals the list's: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
  });

  test("each row's drill pins ITS team: another team's connection may carry the same sprint id", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    const tile = await screen.findByRole("region", { name: "Commitments and velocity" });
    await within(tile).findByTestId("bar-chart");
    await user.click(within(tile).getByRole("button", { name: showItems("Beta · Beta 1 · Final") }));
    await dialog("Beta · Beta 1 · Final");
    expect(itemsCalls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency/items?sprintId=21&teamId=2&bucket=FINAL"]);
    expect(screen.getByTestId("where").textContent).toBe("?drill=sprint%3A2%3A21%3AFINAL");
  });

  test("a link carrying drill= opens the drawer once the tile has loaded", async () => {
    serve(mockFetch);
    renderHome("/?drill=sprint:1:12:COMMITTED");
    const drawer = await dialog("Alpha · Alpha 2 · Committed");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
  });
});
