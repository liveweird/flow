import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportBacklog from "./ReportBacklog";
import { jsonResponse } from "../test/http";
import {
  BACKLOG,
  BACKLOG_NO_LIMIT,
  BACKLOG_NO_PACE,
  BACKLOG_NOT_DERIVED,
  BACKLOG_PARTIAL_WINDOW,
  BACKLOG_ZERO_PACE,
  FILTERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
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
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
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
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
  });

  test("a pasted link with an activity type or work category is read without them — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?teamId=1&activityType=Bug&workCategory=Run");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("activityType")).toBe(false));
    expect(search().has("workCategory")).toBe(false);
    expect(search().get("teamId")).toBe("1");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
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

  test("no pace (no closed sprint, or an unowned/domain scope): a dash and the reason", async () => {
    serve(mockFetch, BACKLOG_NO_PACE);
    renderPage();
    const sprints = await screen.findByRole("group", { name: "Backlog in sprints" });
    expect(within(sprints).getByText("—")).toBeInTheDocument();
    expect(within(sprints).getByText(/No closed sprint yet, or this selection has no velocity of its own/)).toBeInTheDocument();
    // The rest of the report is unaffected.
    expect(within(tile("Backlog (MD)")).getByText("25")).toBeInTheDocument();
    expect(await screen.findByTestId("area-chart")).toBeInTheDocument();
  });

  test("a mean of zero: a dash, and it says the sprints delivered nothing", async () => {
    serve(mockFetch, BACKLOG_ZERO_PACE);
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
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
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
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}from=2026-09-01&to=2026-09-29&teamId=1&touchedWithinDays=90`]);
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 3 sprints" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=3&teamId=1&touchedWithinDays=90`));
  });

  test("a bare link with a remembered team is already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    await waitFor(() => expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
  });

  test("offers only the domain: no member, activity type, work category, domain view or bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
    for (const name of ["Member", "Activity type", "Work category"]) expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
  });

  test("a pasted link with a member is read without it — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?teamId=1&accountId=acc-ann");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("accountId")).toBe(false));
    expect(search().get("teamId")).toBe("1");
    expect(calls(mockFetch).every((url) => !url.includes("accountId"))).toBe(true);
    expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&touchedWithinDays=90`);
  });

  test("the slider defaults to 90 days and the request sends it; the URL carries no window", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    const slider = screen.getByRole("slider", { name: "Last change within" });
    expect(slider).toHaveAttribute("aria-valuetext", "90 days (default)");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
    expect(search().has("touchedWithinDays")).toBe(false);
  });

  test("moving the slider writes touchedWithinDays and refetches; No limit sends 0 and the default leaves the URL bare again", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      return Promise.resolve(jsonResponse(200, url.endsWith("touchedWithinDays=0") ? BACKLOG_NO_LIMIT : BACKLOG));
    });
    const user = userEvent.setup();
    renderPage("/reports/backlog?teamId=1");
    await screen.findByTestId("area-chart");
    const slider = screen.getByRole("slider", { name: "Last change within" });
    slider.focus();
    await user.keyboard("{ArrowLeft}");
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&touchedWithinDays=60`));
    expect(search().get("touchedWithinDays")).toBe("60");
    await user.keyboard("{End}");
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&touchedWithinDays=0`));
    expect(search().get("touchedWithinDays")).toBe("0");
    expect(await within(tile("Items")).findByText("15")).toBeInTheDocument();
    expect(slider).toHaveAttribute("aria-valuetext", "No limit");
    // Back on the default stop: sent as 90, gone from the URL.
    await user.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}");
    await waitFor(() => expect(search().has("touchedWithinDays")).toBe(false));
    expect(slider).toHaveAttribute("aria-valuetext", "90 days (default)");
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=1&touchedWithinDays=90`);
  });

  test("the Items tile says how many items are hidden; nothing hidden says so; the window off says nothing", async () => {
    serve(mockFetch);
    const first = renderPage();
    await screen.findByTestId("area-chart");
    expect(await within(tile("Items")).findByText("3 items (6 MD) hidden: no change in the last 90 days.")).toBeInTheDocument();
    first.unmount();

    serve(mockFetch, { ...BACKLOG, current: { ...BACKLOG.current, hiddenItems: 1, hiddenMd: 2.5 }, touchedWithinDays: 30 });
    const second = renderPage("/reports/backlog?touchedWithinDays=30");
    await screen.findByTestId("area-chart");
    expect(await within(tile("Items")).findByText("1 item (2.5 MD) hidden: no change in the last 30 days.")).toBeInTheDocument();
    second.unmount();

    serve(mockFetch, { ...BACKLOG, current: { ...BACKLOG.current, hiddenItems: 0, hiddenMd: 0 } });
    const third = renderPage();
    await screen.findByTestId("area-chart");
    expect(await within(tile("Items")).findByText("Nothing hidden: every item changed within the last 90 days.")).toBeInTheDocument();
    third.unmount();

    serve(mockFetch, BACKLOG_NO_LIMIT);
    renderPage("/reports/backlog?touchedWithinDays=0");
    await screen.findByTestId("area-chart");
    expect(await within(tile("Items")).findByText("15")).toBeInTheDocument();
    expect(within(tile("Items")).queryByText(/hidden/)).not.toBeInTheDocument();
  });

  test("a hand-edited value off the steps snaps to the nearest step and the URL is rewritten", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?touchedWithinDays=45");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("touchedWithinDays")).toBe("60"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=60`]);
    expect(screen.getByRole("slider", { name: "Last change within" })).toHaveAttribute("aria-valuetext", "60 days");
  });

  test("a pasted 90 is the default: it is dropped from the URL, and 0 (no limit) is kept", async () => {
    serve(mockFetch);
    const first = renderPage("/reports/backlog?touchedWithinDays=90");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("touchedWithinDays")).toBe(false));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=90`]);
    first.unmount();
    mockFetch.mockClear();
    renderPage("/reports/backlog?touchedWithinDays=0");
    await screen.findByTestId("area-chart");
    expect(search().get("touchedWithinDays")).toBe("0");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&touchedWithinDays=0`]);
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

/** The scope rule (`teamRequired` + `teamOrDomain`): exactly one of a specific team or a specific domain, never neither. */
describe("ReportBacklog page — a team or a domain", () => {
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

  test("a link naming neither a team nor a domain gets the default team, URL included, and never asks for the whole unit", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?lastSprints=3");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).every((url) => url.includes("teamId=1"))).toBe(true);
  });

  test("an unassigned-bucket link (teamId=0) is read for the default team instead", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?teamId=0");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).some((url) => url.includes("teamId=0"))).toBe(false);
  });

  test("the team control offers no All teams and cannot be cleared", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: /^Team/ });
    expect((team as HTMLInputElement).value).toBe("Alpha");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect(team).not.toHaveAttribute("placeholder");
    await user.click(team);
    expect((await screen.findAllByRole("option")).map((option) => option.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
  });

  test("a domain alone is a valid scope: the request names no team, and the team control shows none", async () => {
    serve(mockFetch);
    renderPage("/reports/backlog?domain=FLO");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&touchedWithinDays=90`]);
    expect(search().has("teamId")).toBe(false);
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: "Team" });
    expect((team as HTMLInputElement).value).toBe("");
    expect(team).toHaveAttribute("placeholder", "No team: a domain is picked");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("Flow");
  });

  test("clearing the domain falls back to the default team, and picking a domain again drops the team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/backlog?domain=FLO&lastSprints=3");
    await screen.findByTestId("area-chart");
    await openReportFilters();
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(search().has("domain")).toBe(false);
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&teamId=1&touchedWithinDays=90`));
    // ... and a domain picked again drops the team (no team x domain split).
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&domain=FLO&touchedWithinDays=90`));
    expect(search().has("teamId")).toBe(false);
  });

  test("a remembered filter holding only a domain is read for that domain, never the whole unit", async () => {
    localStorage.setItem("flow.viewSettings.reports.backlog.filter", JSON.stringify("domain=FLO"));
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("domain")).toBe("FLO"));
    expect(search().has("teamId")).toBe(false);
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&touchedWithinDays=90`]);
  });

  test("a remembered filter without a team or a domain is read for the default team", async () => {
    localStorage.setItem("flow.viewSettings.reports.backlog.filter", JSON.stringify("lastSprints=3"));
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&teamId=1&touchedWithinDays=90`]);
  });

  test("with no team and no domain at all there is nothing to read: the empty state, no request", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [], domains: [] })
          : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    renderPage();
    expect(await screen.findByText(/The estimated backlog is read for one team or one domain/)).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("button", { name: /^Filters/ })).not.toBeInTheDocument();
  });

  test("with domains but no team the bar stays up and asks for a pick instead of a request", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [] })
          : url.startsWith(URL_PREFIX)
            ? jsonResponse(200, BACKLOG)
            : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText("Pick a team or a domain to read the estimated backlog for.")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    expect(await screen.findByTestId("area-chart")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&touchedWithinDays=90`]);
  });
});
