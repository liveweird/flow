import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportBacklog from "./ReportBacklog";
import { jsonResponse } from "../test/http";
import {
  BACKLOG,
  BACKLOG_NO_VELOCITY,
  BACKLOG_NOT_DERIVED,
  BACKLOG_PARTIAL_WINDOW,
  BACKLOG_ZERO_VELOCITY,
  FILTERS,
} from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  AreaChart: (props: { data: { day: string; md: number }[]; series: { name: string }[]; withLegend?: boolean }) => (
    <div
      data-testid="area-chart"
      data-days={props.data.map((r) => r.day).join("|")}
      data-md={props.data.map((r) => r.md).join(",")}
      data-series={props.series.map((s) => s.name).join(",")}
      data-legend={String(props.withLegend ?? false)}
    />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/backlog?";

function serve(mockFetch: FetchMock, response: unknown = BACKLOG, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/backlog") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/backlog" element={<ReportBacklog />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });

describe("ReportBacklog page", () => {
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

  test("the current backlog as tiles — man-days, items and sprints ahead — then the trend in man-days", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Estimated backlog" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Backlog on 2026-09-29" })).toBeInTheDocument();
    expect(within(tile("Backlog (MD)")).getByText("25")).toBeInTheDocument();
    expect(within(tile("Items")).getByText("12")).toBeInTheDocument();
    expect(within(tile("Backlog in sprints")).getByText("≈ 2.5 sprints ahead")).toBeInTheDocument();
    expect(within(tile("Backlog in sprints")).getByText(/Recent pace: the sum of each team's own mean delivery per sprint, 10 MD, from at least 3 closed sprints per team \(window: 3\)\./)).toBeInTheDocument();

    const chart = await screen.findByTestId("area-chart");
    expect(chart.getAttribute("data-days")).toBe("2026-09-27|2026-09-28|2026-09-29");
    expect(chart.getAttribute("data-md")).toBe("21,23.5,25");
    // One series, so no legend.
    expect(chart.getAttribute("data-series")).toBe("md");
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
  });

  test("at unit level the tile explains that an unowned backlog inflates the figure", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(within(tile("Backlog in sprints")).getByText(/a large unowned backlog inflates the figure/)).toBeInTheDocument();
  });

  test("at unit level fewer sprints means SOME team has fewer than the window, and the pace is a sum of team means", async () => {
    serve(mockFetch, { ...BACKLOG, current: { ...BACKLOG.current, sprintsUsed: 1 } });
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText(/from at least 1 closed sprint per team \(window: 3\)/)).toBeInTheDocument();
    expect(within(sprints).getByText(/Some team has fewer closed sprints than the window/)).toBeInTheDocument();
    expect(within(sprints).queryByText(/^Fewer closed sprints/)).not.toBeInTheDocument();
  });

  test("a positive figure that rounds to zero reads '< 0.1', never '≈ 0'", async () => {
    serve(mockFetch, { ...BACKLOG, current: { ...BACKLOG.current, md: 0.2, meanDeliveredMd: 10, backlogInSprints: 0.02 } });
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText("< 0.1 sprints ahead")).toBeInTheDocument();
    expect(within(sprints).queryByText(/≈ 0/)).not.toBeInTheDocument();
  });

  test("a link with a team AND a domain is read with the team winning — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?teamId=1&domain=FLO");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("domain")).toBe(false));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
  });

  test("fewer closed sprints than the window: the figure is shown, with the caveat and the singular", async () => {
    serve(mockFetch, BACKLOG_PARTIAL_WINDOW);
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText("≈ 3.1 sprints ahead")).toBeInTheDocument();
    expect(within(sprints).getByText(/the mean of 1 closed sprint \(window: 3\)\./)).toBeInTheDocument();
    expect(within(sprints).getByText(/Fewer closed sprints than the window/)).toBeInTheDocument();
    expect(within(sprints).queryByText(/unowned backlog/)).not.toBeInTheDocument();
  });

  test("a whole number of sprints reads in the singular at exactly one", async () => {
    serve(mockFetch, { ...BACKLOG, current: { ...BACKLOG.current, meanDeliveredMd: 25, backlogInSprints: 1 } });
    renderPage();
    expect(await screen.findByText("≈ 1 sprint ahead")).toBeInTheDocument();
  });

  test("no velocity (no closed sprint, or an unowned/domain scope): a dash and the reason", async () => {
    serve(mockFetch, BACKLOG_NO_VELOCITY);
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText("—")).toBeInTheDocument();
    expect(within(sprints).getByText(/No closed sprint yet, or this selection has no velocity of its own/)).toBeInTheDocument();
    // The rest of the report is unaffected.
    expect(within(tile("Backlog (MD)")).getByText("25")).toBeInTheDocument();
    expect(await screen.findByTestId("area-chart")).toBeInTheDocument();
  });

  test("a mean of zero: a dash, and it says the sprints delivered nothing", async () => {
    serve(mockFetch, BACKLOG_ZERO_VELOCITY);
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText("—")).toBeInTheDocument();
    expect(within(sprints).getByText(/The recent sprints delivered nothing/)).toBeInTheDocument();
  });

  test("the trend's numbers are in a table behind a disclosure, newest day first", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.queryByRole("table", { name: "Backlog by day, as a table" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show daily figures" }));
    const table = screen.getByRole("table", { name: "Backlog by day, as a table" });
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["2026-09-29", "25", "12"],
      ["2026-09-28", "23.5", "11"],
      ["2026-09-27", "21", "10"],
    ]);
  });

  test("not derived yet: the empty state, the note and no tiles or trend", async () => {
    serve(mockFetch, BACKLOG_NOT_DERIVED);
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    // Said once, in the viewer's language: the meta line, not also the server's English note.
    expect(screen.getByText("No data has been derived yet.")).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Backlog in sprints" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  test("the filters round-trip into the query string; a period control refetches", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/backlog?teamId=1&to=2026-09-29&from=2026-09-01");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}from=2026-09-01&to=2026-09-29&teamId=1`]);
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 3 sprints" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=3&teamId=1`));
  });

  test("a bare link with a remembered team is already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await waitFor(() => expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
  });

  test("offers only the domain: no activity type, work category, domain view or bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
    for (const name of ["Activity type", "Work category"]) expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
  });

  test("Estimated backlog is the second tab of the Flow group", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("tab", { name: "Estimated backlog" })).toHaveAttribute("aria-selected", "true");
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});
