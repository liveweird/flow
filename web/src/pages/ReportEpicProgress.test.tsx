import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportEpicProgress from "./ReportEpicProgress";
import { jsonResponse } from "../test/http";
import {
  EPIC_PROGRESS_DOMAIN,
  EPIC_PROGRESS_DOMAIN_PERIOD,
  EPIC_PROGRESS_EPIC,
  EPIC_PROGRESS_EPIC_PERIOD,
  EPIC_PROGRESS_NO_CURVE,
  EPIC_PROGRESS_NO_PLAN,
  EPIC_PROGRESS_NOT_DERIVED,
  EPIC_PROGRESS_TEAM,
  EPIC_PROGRESS_TEAM_NO_COST,
  EPIC_PROGRESS_TEAM_PERIOD,
  EPIC_PROGRESS_UNIT,
  EPIC_PROGRESS_UNIT_PERIOD,
  FILTERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: { date?: string; day?: string }[];
    series: { name: string; strokeDasharray?: string }[];
    withLegend?: boolean;
    referenceLines?: { y?: number }[];
  }) => (
    <div
      data-testid="line-chart"
      data-days={props.data.map((r) => r.date ?? r.day).join("|")}
      data-rows={JSON.stringify(props.data)}
      data-reference={(props.referenceLines ?? []).map((line) => line.y).join(",")}
      data-series={props.series.map((s) => s.name).join(",")}
      data-dashed={props.series.filter((s) => s.strokeDasharray).map((s) => s.name).join(",")}
      data-legend={String(props.withLegend ?? false)}
    />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/epic-progress?";

function serve(mockFetch: FetchMock, response: unknown = EPIC_PROGRESS_UNIT, status = 200, filters: unknown = FILTERS) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, filters));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

/** Serves the level the URL asks for, so a drill click gets its own answer without a race. */
function serveByScope(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      const params = new URLSearchParams(url.slice(URL_PREFIX.length));
      const response = params.has("epicId")
        ? EPIC_PROGRESS_EPIC
        : params.has("domain")
          ? EPIC_PROGRESS_DOMAIN
          : params.has("teamId")
            ? EPIC_PROGRESS_TEAM
            : EPIC_PROGRESS_UNIT;
      return Promise.resolve(jsonResponse(200, response));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/epic-progress", state?: unknown) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/epic-progress" element={<ReportEpicProgress />} />
      </Routes>
      <LocationProbe />
    </>,
    { route, state },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");
const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });
const bodyRows = (table: HTMLElement) =>
  within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));

describe("ReportEpicProgress page", () => {
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

  test("the unit: headline tiles as of the day, signed variances, two-decimal indices — and no chart", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Epic progress" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 3, name: "Whole unit" })).toBeInTheDocument();
    expect(screen.getByText("As of 2026-09-29")).toBeInTheDocument();
    expect(within(tile("PV (MD)")).getByText("12")).toBeInTheDocument();
    expect(within(tile("EV (MD)")).getByText("9")).toBeInTheDocument();
    expect(within(tile("AC (MD)")).getByText("10")).toBeInTheDocument();
    expect(within(tile("SV (MD)")).getByText("−3")).toBeInTheDocument();
    expect(within(tile("CV (MD)")).getByText("−1")).toBeInTheDocument();
    expect(within(tile("SPI")).getByText("0.75")).toBeInTheDocument();
    expect(within(tile("SPI")).getByText("Behind plan")).toBeInTheDocument();
    expect(within(tile("CPI")).getByText("0.90")).toBeInTheDocument();
    expect(within(tile("CPI")).getByText("Over budget")).toBeInTheDocument();
    expect(screen.getByText(/The domains below add up to these figures; the teams do not/)).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Foreign work share" })).not.toBeInTheDocument();
    // No default period is invented; a bare link is read for the default team (a team or a domain is required).
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
  });

  test("the unit drill: domains (the headline's basis), then the teams as the sprint view that does not add up", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-progress?lastSprints=3");
    const domains = await screen.findByRole("table", { name: "Domains" });
    expect(bodyRows(domains)).toEqual([["Flow", "12", "9", "10", "−3", "0.75", "−1", "0.90"]]);
    expect(screen.getByText(/these rows add up to the unit's figures above/)).toBeInTheDocument();
    const teams = screen.getByRole("table", { name: "Teams (sprint view)" });
    expect(screen.getByText(/these rows do not add up to the unit's figures/)).toBeInTheDocument();
    expect(bodyRows(teams).map((r) => [r[0], r[5], r[7]])).toEqual([
      ["Alpha", "0.75", "0.83"],
      ["Old team Deleted team", "1.00", "1.25"],
      ["Unassigned", "—", "0.00"],
    ]);
    // Names are the way in, carrying the period; the domain and the team narrow ONE scope each.
    const href = (name: string) => screen.getByRole("link", { name }).getAttribute("href");
    expect(href("Show Flow")).toBe("/reports/epic-progress?lastSprints=3&domain=FLO");
    expect(href("Show Alpha")).toBe("/reports/epic-progress?lastSprints=3&teamId=1");
    // UNASSIGNED (team 0) is a legal scope and is linked.
    expect(href("Show Unassigned")).toBe("/reports/epic-progress?lastSprints=3&teamId=0");
  });

  test("a soft-deleted team keeps its figures, is marked, and has no link (its drill would be a 400)", async () => {
    serve(mockFetch);
    renderPage();
    const teams = await screen.findByRole("table", { name: "Teams (sprint view)" });
    expect(within(teams).getByText("Old team")).toBeInTheDocument();
    expect(within(teams).getByText("Deleted team")).toBeInTheDocument();
    expect(within(teams).queryByRole("link", { name: /Old team/ })).not.toBeInTheDocument();
  });

  test("the domain level sends only that scope and lists its epics, each a way in; with nothing above it there is no breadcrumb", async () => {
    serveByScope(mockFetch);
    renderPage("/reports/epic-progress?lastSprints=3&domain=FLO");
    expect(await screen.findByRole("heading", { level: 3, name: "Domain: Flow" })).toBeInTheDocument();
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&domain=FLO`);
    // Chart: PV, EV, AC only — no original plan outside an epic — with a legend.
    const chart = await screen.findByTestId("line-chart");
    expect(chart.getAttribute("data-series")).toBe("pv,ev,ac");
    expect(chart.getAttribute("data-dashed")).toBe("");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-days")).toBe("2026-09-27|2026-09-28|2026-09-29");
    expect(screen.queryByRole("group", { name: "Foreign work share" })).not.toBeInTheDocument();
    // Its epics are the drill.
    const epics = screen.getByRole("table", { name: "Epics" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(epics.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(bodyRows(epics).map((r) => r[0])).toEqual(["FLO-33 Reporting epic", "FLO-40"]);
    expect(screen.getByRole("link", { name: "Show FLO-33" }).getAttribute("href")).toBe(
      "/reports/epic-progress?lastSprints=3&epicId=FLO-33",
    );
    // The page is never read for the whole unit, so there is no unit crumb to lead back to.
    expect(screen.queryByRole("navigation", { name: "Where you are in the drill-down" })).not.toBeInTheDocument();
  });

  test("with the Jira site known an epic row keeps its in-app drill link and gains a Jira link beside it", async () => {
    const filters = { ...FILTERS, connections: [{ id: 1, name: "Stub", siteUrl: "https://stub.atlassian.net" }] };
    serve(mockFetch, EPIC_PROGRESS_DOMAIN, 200, filters);
    renderPage("/reports/epic-progress?lastSprints=3&domain=FLO");
    const epics = await screen.findByRole("table", { name: "Epics" });
    expect(within(epics).getByRole("link", { name: "Show FLO-33" }).getAttribute("href")).toBe(
      "/reports/epic-progress?lastSprints=3&epicId=FLO-33",
    );
    const external = await within(epics).findByRole("link", { name: "FLO-33 (opens in Jira)" });
    expect(external).toHaveAttribute("href", "https://stub.atlassian.net/browse/FLO-33");
    expect(external).toHaveAttribute("target", "_blank");
    expect(external).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("the epic level: the original plan is a dashed series, the plan panel shows budget, dates, drift and every baseline", async () => {
    serve(mockFetch, EPIC_PROGRESS_EPIC);
    renderPage("/reports/epic-progress?epicId=FLO-33");
    expect(await screen.findByRole("heading", { level: 3, name: "Epic: FLO-33 · Reporting epic" })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}epicId=FLO-33`]);
    const chart = await screen.findByTestId("line-chart");
    expect(chart.getAttribute("data-series")).toBe("pv,pvOriginal,ev,ac");
    expect(chart.getAttribute("data-dashed")).toBe("pvOriginal");
    // Plan panel.
    const plan = screen.getByRole("heading", { level: 3, name: "Plan" }).closest(".mantine-Paper-root") as HTMLElement;
    expect(within(plan).getByText("20 MD · the sum of its tasks' estimates")).toBeInTheDocument();
    // The planned dates read as calendar dates (UTC midnight), whatever zone the report is configured in.
    expect(within(plan).getAllByText("2026-09-01")).toHaveLength(3);
    expect(within(plan).getAllByText("2026-10-31")).toHaveLength(2);
    expect(within(plan).getByText("Dates moved since the original plan")).toBeInTheDocument();
    expect(within(plan).getByText("Budget changed since the original plan")).toBeInTheDocument();
    const baselines = within(plan).getByRole("table", { name: "Plan baselines, oldest first" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(baselines.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(bodyRows(baselines)).toEqual([
      ["2026-04-28", "2026-05-18", "2026-09-01", "2026-10-15", "16"],
      ["2026-05-18", "current", "2026-09-01", "2026-10-31", "20"],
    ]);
    expect(within(plan).queryByRole("note")).not.toBeInTheDocument();
    // The text alternative, behind a disclosure, carries the original plan.
    expect(screen.queryByRole("table", { name: /Cumulative PV, EV and AC by day/ })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Show daily figures" }));
    const daily = screen.getByRole("table", { name: "Cumulative PV, EV and AC by day, as a table" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(daily.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(daily).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Day", "PV", "Original plan (PV)", "EV", "AC"]);
    expect(bodyRows(daily)[0]).toEqual(["2026-09-29", "12", "14", "9", "10"]);
  });

  test("an epic reached through a domain offers the way back to it", async () => {
    serve(mockFetch, EPIC_PROGRESS_EPIC);
    renderPage("/reports/epic-progress?epicId=FLO-33&lastSprints=3", { domain: { key: "FLO", name: "Flow" } });
    const crumbs = await screen.findByRole("navigation", { name: "Where you are in the drill-down" });
    expect(within(crumbs).getByRole("link", { name: "Flow" }).getAttribute("href")).toBe("/reports/epic-progress?lastSprints=3&domain=FLO");
    expect(within(crumbs).getByText("FLO-33 · Reporting epic")).toHaveAttribute("aria-current", "page");
  });

  test("an epic opened directly has nothing above it (no unit crumb); a malformed drill state is ignored", async () => {
    serve(mockFetch, EPIC_PROGRESS_EPIC);
    renderPage("/reports/epic-progress?epicId=FLO-33", { domain: { key: 7 } });
    expect(await screen.findByRole("heading", { level: 3, name: "Epic: FLO-33 · Reporting epic" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Where you are in the drill-down" })).not.toBeInTheDocument();
  });

  test("no plan yet: SPI is a dash with the reason, and the missing plan is explained without hiding EV and AC", async () => {
    serve(mockFetch, EPIC_PROGRESS_NO_PLAN);
    renderPage("/reports/epic-progress?epicId=FLO-33");
    const spi = await screen.findByRole("group", { name: "SPI" });
    expect(within(spi).getByText("—")).toBeInTheDocument();
    expect(within(spi).getByText("No plan yet: planned value is 0, so there is no ratio.")).toBeInTheDocument();
    expect(within(tile("EV (MD)")).getByText("9")).toBeInTheDocument();
    expect(within(tile("SV (MD)")).getByText("+9")).toBeInTheDocument();
    expect(screen.getByText(/No plan curve: the plan is missing a date, or a date lies more than ten years from today/)).toBeInTheDocument();
    // No baseline, no drift, no original plan drawn.
    expect(screen.queryByRole("table", { name: "Plan baselines, oldest first" })).not.toBeInTheDocument();
    expect(screen.queryByText(/since the original plan/)).not.toBeInTheDocument();
    expect((await screen.findByTestId("line-chart")).getAttribute("data-series")).toBe("pv,ev,ac");
  });

  test("in the horizon but with no working day: its own note, not the horizon one", async () => {
    serve(mockFetch, EPIC_PROGRESS_NO_CURVE);
    renderPage("/reports/epic-progress?epicId=FLO-33");
    expect(await screen.findByText(/No plan curve: the planned window holds no working day/)).toBeInTheDocument();
    expect(screen.queryByText(/more than ten years from today/)).not.toBeInTheDocument();
  });

  test("the team: the sprint view with the foreign-work share beside CPI and a one-line explanation", async () => {
    serve(mockFetch, EPIC_PROGRESS_TEAM);
    renderPage("/reports/epic-progress?teamId=1");
    expect(await screen.findByRole("heading", { level: 3, name: "Team: Alpha" })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
    const foreign = tile("Foreign work share");
    expect(within(foreign).getByText("12.5%")).toBeInTheDocument();
    expect(within(foreign).getByText(/Read the team's CPI together with it/)).toBeInTheDocument();
    expect(screen.getByText(/Sprint view: planned is the committed scope of the team's sprints/)).toBeInTheDocument();
    expect((await screen.findByTestId("line-chart")).getAttribute("data-series")).toBe("pv,ev,ac");
    // No user level: the bar has a team but no member control.
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Team" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
  });

  test("a team that logged nothing: CPI and the foreign-work share are dashes with their reasons", async () => {
    serve(mockFetch, EPIC_PROGRESS_TEAM_NO_COST);
    renderPage("/reports/epic-progress?teamId=1");
    const cpi = await screen.findByRole("group", { name: "CPI" });
    expect(within(cpi).getByText("—")).toBeInTheDocument();
    expect(within(cpi).getByText("Nothing logged yet: actual cost is 0, so there is no ratio.")).toBeInTheDocument();
    const foreign = tile("Foreign work share");
    expect(within(foreign).getByText("—")).toBeInTheDocument();
    expect(within(foreign).getByText("Nothing logged yet, so there is no share.")).toBeInTheDocument();
  });

  test("a link with several scopes and params the report cannot answer is normalised — off the request and off the URL", async () => {
    serve(mockFetch, EPIC_PROGRESS_EPIC);
    renderPage(
      "/reports/epic-progress?epicId=FLO-33&domain=FLO&teamId=1&accountId=acc-ann&activityType=Bug&workCategory=Maintenance&domainView=TASK&lastSprints=3",
    );
    await screen.findByTestId("line-chart");
    await waitFor(() => expect(search().toString()).toBe("lastSprints=3&epicId=FLO-33"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&epicId=FLO-33`]);
  });

  test("a domain link with a team keeps the domain; a blank epic key is no scope at all", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN);
    renderPage("/reports/epic-progress?domain=FLO&teamId=1&epicId=%20");
    await screen.findByTestId("line-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
  });

  test("a bare link starts on the remembered team", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    serveByScope(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Team: Alpha" })).toBeInTheDocument();
    await waitFor(() => expect(search().get("teamId")).toBe("2"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=2`]);
  });

  test.each(["domain=FLO", "epicId=FLO-33"])("a pasted ?%s link is read as written even with a remembered team: no team is added", async (query) => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serveByScope(mockFetch);
    renderPage(`/reports/epic-progress?${query}`);
    await screen.findByTestId("line-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}${query}`]);
    expect(search().toString()).toBe(query);
  });

  test("planned dates are calendar dates read in UTC; a baseline's instants are read in the configured zone (Europe/Warsaw)", async () => {
    // effectiveFrom 22:30Z on the 17th is already the 18th in Warsaw; a UTC-midnight date stays put.
    serve(mockFetch, {
      ...EPIC_PROGRESS_EPIC,
      epic: {
        ...EPIC_PROGRESS_EPIC.epic!,
        baselines: [
          { effectiveFrom: Date.UTC(2026, 4, 17, 22, 30), supersededAt: Date.UTC(2026, 5, 16, 22, 30), startAt: Date.UTC(2026, 8, 1), dueAt: Date.UTC(2026, 9, 15), budgetMd: 16 },
          { effectiveFrom: Date.UTC(2026, 5, 16, 22, 30), supersededAt: null, startAt: Date.UTC(2026, 8, 1), dueAt: Date.UTC(2026, 9, 31), budgetMd: 20 },
        ],
      },
    });
    renderPage("/reports/epic-progress?epicId=FLO-33");
    const baselines = await screen.findByRole("table", { name: "Plan baselines, oldest first" });
    expect(bodyRows(baselines)).toEqual([
      ["2026-05-18", "2026-06-17", "2026-09-01", "2026-10-15", "16"],
      ["2026-06-17", "current", "2026-09-01", "2026-10-31", "20"],
    ]);
  });

  test("in a negative-offset zone (America/New_York) the instants move back a day but the planned dates do not", async () => {
    // 02:00Z on the 18th is the evening of the 17th in New York; UTC midnight on 1 Sep is 31 Aug there — and must NOT read so.
    serve(
      mockFetch,
      {
        ...EPIC_PROGRESS_EPIC,
        epic: {
          ...EPIC_PROGRESS_EPIC.epic!,
          startAt: Date.UTC(2026, 8, 1),
          dueAt: Date.UTC(2026, 9, 31),
          baselines: [
            { effectiveFrom: Date.UTC(2026, 4, 18, 2, 0), supersededAt: null, startAt: Date.UTC(2026, 8, 1), dueAt: Date.UTC(2026, 9, 31), budgetMd: 20 },
          ],
        },
      },
      200,
      { ...FILTERS, timeZone: "America/New_York" },
    );
    renderPage("/reports/epic-progress?epicId=FLO-33");
    const baselines = await screen.findByRole("table", { name: "Plan baselines, oldest first" });
    expect(bodyRows(baselines)).toEqual([["2026-05-17", "current", "2026-09-01", "2026-10-31", "20"]]);
    const plan = screen.getByRole("heading", { level: 3, name: "Plan" }).closest(".mantine-Paper-root") as HTMLElement;
    // The Planned start / due facts (outside the table) read the same calendar days.
    expect(within(plan).getAllByText("Planned start")[0].nextElementSibling).toHaveTextContent("2026-09-01");
    expect(within(plan).getAllByText("Planned due")[0].nextElementSibling).toHaveTextContent("2026-10-31");
  });

  test("the domain crumb survives a period change and the normalising rewrite of the link", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    // activityType is dropped by the normalising rewrite (a replace navigation) — the hand-off must outlive it.
    renderPage("/reports/epic-progress?epicId=FLO-33&activityType=Bug", { domain: { key: "FLO", name: "Flow" } });
    await waitFor(() => expect(search().has("activityType")).toBe(false));
    let crumbs = await screen.findByRole("navigation", { name: "Where you are in the drill-down" });
    expect(within(crumbs).getByRole("link", { name: "Flow" })).toBeInTheDocument();
    // …and a period change made through the bar (a push navigation).
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 30 days" }));
    await waitFor(() => expect(search().has("from")).toBe(true));
    crumbs = screen.getByRole("navigation", { name: "Where you are in the drill-down" });
    expect(within(crumbs).getByRole("link", { name: "Flow" }).getAttribute("href")).toMatch(/^\/reports\/epic-progress\?from=.*&domain=FLO$/);
  });

  test("a team drill drops a one-sprint period the team does not list, and keeps one it does", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-progress?sprintId=11");
    await screen.findByRole("table", { name: "Teams (sprint view)" });
    const href = (name: string) => screen.getByRole("link", { name }).getAttribute("href");
    // Sprint 11 is Alpha's; UNASSIGNED lists none. Domains do not take a team, so they keep the period.
    expect(href("Show Alpha")).toBe("/reports/epic-progress?sprintId=11&teamId=1");
    expect(href("Show Unassigned")).toBe("/reports/epic-progress?teamId=0");
    expect(href("Show Flow")).toBe("/reports/epic-progress?sprintId=11&domain=FLO");
  });

  test("picking a team replaces the epic; the bar offers only a team and a domain, no activity type or work category", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?epicId=FLO-33");
    await screen.findByTestId("line-chart");
    await openReportFilters();
    for (const name of ["Activity type", "Work category"]) expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(search().has("epicId")).toBe(false);
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=1`);
  });

  test("picking a domain replaces the team", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?teamId=1");
    await screen.findByTestId("line-chart");
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(search().get("domain")).toBe("FLO"));
    expect(search().has("teamId")).toBe(false);
  });

  test("nothing derived yet: the meta line says so, the empty state replaces the tiles, and the note is not repeated in English", async () => {
    serve(mockFetch, EPIC_PROGRESS_NOT_DERIVED);
    renderPage();
    expect(await screen.findByText("No data has been derived yet.")).toBeInTheDocument();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByText(/Not derived yet/)).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "PV (MD)" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Domains" })).not.toBeInTheDocument();
  });

  test("any other server note is shown as written, and a period after the as-of day still shows the running figures", async () => {
    serve(mockFetch, {
      ...EPIC_PROGRESS_DOMAIN,
      series: [],
      note: "The period starts after the as-of day 2026-09-29, so there is nothing to draw.",
    });
    renderPage("/reports/epic-progress?domain=FLO");
    expect(await screen.findByRole("note")).toHaveTextContent("The period starts after the as-of day 2026-09-29");
    expect(within(tile("PV (MD)")).getByText("12")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("an unknown scope is the normal failure alert", async () => {
    serve(mockFetch, { title: "unknown domain", status: 400 }, 400);
    renderPage("/reports/epic-progress?domain=NOPE");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "PV (MD)" })).not.toBeInTheDocument();
  });

  test("Epic progress is the fifth tab of the Flow group", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("group", { name: "PV (MD)" });
    expect(screen.getByRole("tab", { name: "Epic progress" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("tab")).toHaveLength(5);
  });
});

describe("ReportEpicProgress page: the basis, this period or since the start (A37)", () => {
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
  /** Answers with the report of the basis the request names; a request without `basis` gets the level's default (the `fallback`). */
  function serveByBasis(fallback: unknown, byBasis: { PERIOD: unknown; CUMULATIVE: unknown }) {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.startsWith(URL_PREFIX)) {
        const basis = new URLSearchParams(url.slice(URL_PREFIX.length)).get("basis");
        return Promise.resolve(jsonResponse(200, basis === "PERIOD" ? byBasis.PERIOD : basis === "CUMULATIVE" ? byBasis.CUMULATIVE : fallback));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
  }

  const trendChart = () => screen.getAllByTestId("line-chart").find((chart) => chart.getAttribute("data-series") === "spi,cpi")!;
  // The chart is a lazy chunk: it may not be mounted yet when the page's own controls are, so every read awaits it
  // (a synchronous `getAllByTestId` right after a control appears fails whenever the chunk loads second).
  const mdChartRows = () =>
    waitFor(() => {
      const chart = screen.getAllByTestId("line-chart").find((c) => c.getAttribute("data-series")!.startsWith("pv"));
      expect(chart).toBeDefined();
      return JSON.parse(chart!.getAttribute("data-rows")!) as { pv: number; ev: number; ac: number; pvOriginal?: number }[];
    });

  test("a domain opens on the period the server picked: the control says so, the URL stays bare and the request names no basis", async () => {
    serveByBasis(EPIC_PROGRESS_DOMAIN_PERIOD, { PERIOD: EPIC_PROGRESS_DOMAIN_PERIOD, CUMULATIVE: EPIC_PROGRESS_DOMAIN });
    renderPage("/reports/epic-progress?domain=FLO");
    const control = await screen.findByRole("radiogroup", { name: "Measured over" });
    expect(within(control).getByRole("radio", { name: "This period" })).toBeChecked();
    expect(within(control).getByRole("radio", { name: "Since start" })).not.toBeChecked();
    expect(search().has("basis")).toBe(false);
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
    expect(screen.getByText("2026-09-27 to 2026-09-29")).toBeInTheDocument();
  });

  test("touching the toggle makes the basis explicit: it lands in the URL and the request, and the figures follow", async () => {
    serveByBasis(EPIC_PROGRESS_DOMAIN_PERIOD, { PERIOD: EPIC_PROGRESS_DOMAIN_PERIOD, CUMULATIVE: EPIC_PROGRESS_DOMAIN });
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?domain=FLO");
    await user.click(await screen.findByRole("radio", { name: "Since start" }));
    await waitFor(() => expect(search().get("basis")).toBe("CUMULATIVE"));
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}domain=FLO&basis=CUMULATIVE`);
    await waitFor(() => expect(within(tile("PV (MD)")).getByText("12")).toBeInTheDocument());
    expect(screen.getByRole("radio", { name: "Since start" })).toBeChecked();
    expect(await screen.findByRole("heading", { level: 3, name: "Cumulative PV, EV and AC" })).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "This period" }));
    await waitFor(() => expect(search().get("basis")).toBe("PERIOD"));
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}domain=FLO&basis=PERIOD`);
  });

  test("a pasted ?basis=CUMULATIVE is explicit from the first request; an unknown basis is dropped", async () => {
    serveByBasis(EPIC_PROGRESS_DOMAIN_PERIOD, { PERIOD: EPIC_PROGRESS_DOMAIN_PERIOD, CUMULATIVE: EPIC_PROGRESS_DOMAIN });
    renderPage("/reports/epic-progress?domain=FLO&basis=CUMULATIVE");
    expect(await screen.findByRole("radio", { name: "Since start" })).toBeChecked();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&basis=CUMULATIVE`]);
  });

  test("an unknown basis never reaches the request", async () => {
    serveByBasis(EPIC_PROGRESS_DOMAIN_PERIOD, { PERIOD: EPIC_PROGRESS_DOMAIN_PERIOD, CUMULATIVE: EPIC_PROGRESS_DOMAIN });
    renderPage("/reports/epic-progress?domain=FLO&basis=WEEKLY");
    await screen.findByRole("radiogroup", { name: "Measured over" });
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
  });

  test("one epic opens since its start, and asking for the period rebases the curves and the original plan", async () => {
    serveByBasis(EPIC_PROGRESS_EPIC, { PERIOD: EPIC_PROGRESS_EPIC_PERIOD, CUMULATIVE: EPIC_PROGRESS_EPIC });
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?epicId=FLO-33");
    expect(await screen.findByRole("radio", { name: "Since start" })).toBeChecked();
    expect((await mdChartRows())[0]).toMatchObject({ pv: 8, pvOriginal: 10 });
    await user.click(screen.getByRole("radio", { name: "This period" }));
    await waitFor(async () => expect((await mdChartRows())[0]).toMatchObject({ pv: 2, ev: 2, ac: 2, pvOriginal: 4 }));
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}epicId=FLO-33&basis=PERIOD`);
  });

  test("PERIOD: the curves start near 0 and carry the period's title; the numbers are in the table", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN_PERIOD);
    renderPage("/reports/epic-progress?domain=FLO");
    expect(await screen.findByRole("heading", { level: 3, name: "PV, EV and AC in the period" })).toBeInTheDocument();
    const rowsOf = await mdChartRows();
    expect(rowsOf.map((r) => [r.pv, r.ev, r.ac])).toEqual([
      [2, 2, 2],
      [4, 4, 4],
      [6, 7, 6],
    ]);
    await userEvent.setup().click(screen.getByRole("button", { name: "Show daily figures" }));
    const daily = screen.getByRole("table", { name: "PV, EV and AC in the period by day, as a table" });
    expect(bodyRows(daily)[0]).toEqual(["2026-09-29", "6", "7", "6"]);
  });

  test("PERIOD tiles: the period's figures are primary, the cumulative ones a small line under each", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN_PERIOD);
    renderPage("/reports/epic-progress?domain=FLO");
    await screen.findByRole("group", { name: "PV (MD)" });
    const pv = tile("PV (MD)");
    expect(within(pv).getByText("6")).toBeInTheDocument();
    expect(within(pv).getByText("Since start: 12")).toBeInTheDocument();
    expect(within(tile("EV (MD)")).getByText("7")).toBeInTheDocument();
    expect(within(tile("SV (MD)")).getByText("+1")).toBeInTheDocument();
    expect(within(tile("SV (MD)")).getByText("Since start: −3")).toBeInTheDocument();
    const spi = tile("SPI");
    expect(within(spi).getByText("1.17")).toBeInTheDocument();
    expect(within(spi).getByText("Ahead of plan")).toBeInTheDocument();
    expect(within(spi).getByText("Since start: 0.75")).toBeInTheDocument();
    expect(within(tile("CPI")).getByText("Since start: 0.90")).toBeInTheDocument();
  });

  test("CUMULATIVE tiles: the other basis is the small line (\"This period: …\")", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN);
    renderPage("/reports/epic-progress?domain=FLO&basis=CUMULATIVE");
    await screen.findByRole("group", { name: "PV (MD)" });
    expect(within(tile("PV (MD)")).getByText("12")).toBeInTheDocument();
    expect(within(tile("PV (MD)")).getByText("This period: 6")).toBeInTheDocument();
    expect(within(tile("CPI")).getByText("0.90")).toBeInTheDocument();
    expect(within(tile("CPI")).getByText("This period: 1.17")).toBeInTheDocument();
  });

  test("a hidden period index is a dash with its reason, and the cumulative index stays beside it", async () => {
    serve(mockFetch, {
      ...EPIC_PROGRESS_DOMAIN_PERIOD,
      period: { firstDay: "2026-09-27", pv: 0.5, ev: 3, ac: 0.25, sv: 2.5, spi: null, cv: 2.75, cpi: null },
    });
    renderPage("/reports/epic-progress?domain=FLO");
    const spi = await screen.findByRole("group", { name: "SPI" });
    expect(within(spi).getByText("—")).toBeInTheDocument();
    expect(within(spi).getByText(/Too little planned in this period/)).toBeInTheDocument();
    expect(within(spi).getByText("Since start: 0.75")).toBeInTheDocument();
    const cpi = tile("CPI");
    expect(within(cpi).getByText("—")).toBeInTheDocument();
    expect(within(cpi).getByText(/Too little spent in this period/)).toBeInTheDocument();
  });

  test("the weekly trend: SPI and CPI on one index axis with a 1.0 reference line, and its numbers in a table beside it", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN_PERIOD);
    renderPage("/reports/epic-progress?domain=FLO");
    expect(await screen.findByRole("heading", { level: 3, name: "SPI and CPI trend" })).toBeInTheDocument();
    expect(screen.getByText(/over the trailing 28 days/)).toBeInTheDocument();
    const chart = await waitFor(() => {
      const found = trendChart();
      expect(found).toBeDefined();
      return found;
    });
    expect(chart.getAttribute("data-days")).toBe("2026-09-27|2026-09-29");
    expect(chart.getAttribute("data-reference")).toBe("1");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-dashed")).toBe("cpi");
    const table = screen.getByRole("table", { name: "SPI and CPI per week, as a table" });
    expect(bodyRows(table)).toEqual([
      ["2026-09-29", "1.10", "0.95"],
      ["2026-09-27", "—", "0.80"],
    ]);
  });

  test("the unit on the period has the trend but no MD chart; a basis without a trend shows no trend card", async () => {
    serve(mockFetch, EPIC_PROGRESS_UNIT_PERIOD);
    renderPage("/reports/epic-progress");
    expect(await screen.findByRole("heading", { level: 3, name: "SPI and CPI trend" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3, name: "PV, EV and AC in the period" })).not.toBeInTheDocument();
  });

  test("no trend card when the answer has no trend", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN);
    renderPage("/reports/epic-progress?domain=FLO&basis=CUMULATIVE");
    await screen.findByRole("group", { name: "PV (MD)" });
    expect(screen.queryByRole("heading", { level: 3, name: "SPI and CPI trend" })).not.toBeInTheDocument();
  });

  test("the drill table follows the basis: the period's columns, a hidden index as a dash; cumulative otherwise", async () => {
    serve(mockFetch, { ...EPIC_PROGRESS_UNIT_PERIOD, rows: EPIC_PROGRESS_UNIT.rows });
    renderPage("/reports/epic-progress");
    const domains = await screen.findByRole("table", { name: "Domains" });
    expect(bodyRows(domains)).toEqual([["Flow", "6", "7", "6", "+1", "1.17", "+1", "1.17"]]);
    const teams = screen.getByRole("table", { name: "Teams (sprint view)" });
    expect(bodyRows(teams).map((r) => [r[0], r[1], r[5], r[7]])).toEqual([
      ["Alpha", "10", "0.90", "1.00"],
      ["Old team Deleted team", "0", "—", "0.00"],
      ["Unassigned", "0", "—", "0.00"],
    ]);
  });

  test("the drill table of a domain shows its epics' period figures on the period basis", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN_PERIOD);
    renderPage("/reports/epic-progress?domain=FLO");
    const epics = await screen.findByRole("table", { name: "Epics" });
    expect(bodyRows(epics).map((r) => r.slice(1))).toEqual([
      ["4", "5", "4", "+1", "1.25", "+1", "1.25"],
      ["2", "2", "2", "0", "1.00", "0", "1.00"],
    ]);
  });

  test("a team on the period shows the period's foreign-work share, matching the shown CPI, and the cumulative share beside it", async () => {
    serve(mockFetch, EPIC_PROGRESS_TEAM_PERIOD);
    renderPage("/reports/epic-progress?teamId=1");
    const foreign = await screen.findByRole("group", { name: "Foreign work share" });
    expect(within(foreign).getByText("40%")).toBeInTheDocument();
    expect(within(foreign).getByText(/in this period that went into other teams' work/)).toBeInTheDocument();
    expect(within(foreign).getByText("Since start: 12.5%")).toBeInTheDocument();
  });

  test("a team since the start keeps the cumulative share, the period's beside it", async () => {
    serve(mockFetch, EPIC_PROGRESS_TEAM);
    renderPage("/reports/epic-progress?teamId=1&basis=CUMULATIVE");
    const foreign = await screen.findByRole("group", { name: "Foreign work share" });
    expect(within(foreign).getByText("12.5%")).toBeInTheDocument();
    expect(within(foreign).getByText("This period: 40%")).toBeInTheDocument();
  });

  test("a drill keeps an explicit basis", async () => {
    serve(mockFetch, EPIC_PROGRESS_DOMAIN);
    renderPage("/reports/epic-progress?lastSprints=3&domain=FLO&basis=CUMULATIVE");
    const href = (await screen.findByRole("link", { name: "Show FLO-33" })).getAttribute("href");
    expect(href).toBe("/reports/epic-progress?lastSprints=3&epicId=FLO-33&basis=CUMULATIVE");
  });
});

/** The scope rule (`teamRequired` + `teamOrDomain` + `epicScope`): one of a specific team, a specific domain or a single epic, never the whole unit. */
describe("ReportEpicProgress page — a team or a domain", () => {
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

  test("a link naming no scope (or the unassigned bucket) gets the default team, URL included, and never asks for the whole unit", async () => {
    serveByScope(mockFetch);
    const { unmount } = renderPage("/reports/epic-progress?lastSprints=3");
    await screen.findByTestId("line-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).every((url) => url.includes("teamId=1"))).toBe(true);
    unmount();
    mockFetch.mockClear();
    renderPage("/reports/epic-progress?teamId=0");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).some((url) => url.includes("teamId=0"))).toBe(false);
  });

  test("the team control offers no All teams and cannot be cleared", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("line-chart");
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
    serveByScope(mockFetch);
    renderPage("/reports/epic-progress?domain=FLO");
    await screen.findByTestId("line-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
    expect(search().has("teamId")).toBe(false);
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: "Team" });
    expect((team as HTMLInputElement).value).toBe("");
    expect(team).toHaveAttribute("placeholder", "No team: a domain is picked");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
  });

  test("a single epic needs neither a team nor a domain: the page fetches it, and the team control shows none", async () => {
    serveByScope(mockFetch);
    renderPage("/reports/epic-progress?epicId=FLO-33&lastSprints=3");
    expect(await screen.findByRole("heading", { level: 3, name: "Epic: FLO-33 · Reporting epic" })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&epicId=FLO-33`]);
    expect(search().has("teamId")).toBe(false);
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: "Team" });
    expect((team as HTMLInputElement).value).toBe("");
    expect(team).toHaveAttribute("placeholder", "No team: a single epic is shown");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect(screen.queryByText("Pick a team or a domain to read epic progress for.")).not.toBeInTheDocument();
  });

  test("a single epic with a stale team or domain beside it is still just the epic", async () => {
    serveByScope(mockFetch);
    renderPage("/reports/epic-progress?epicId=FLO-33&teamId=99&domain=FLO");
    await screen.findByTestId("line-chart");
    await waitFor(() => expect(search().toString()).toBe("epicId=FLO-33"));
    expect(calls(mockFetch).every((url) => url === `${URL_PREFIX}epicId=FLO-33`)).toBe(true);
  });

  test("picking a team from a single epic replaces the epic; clearing the domain falls back to the default team", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?epicId=FLO-33");
    await screen.findByTestId("line-chart");
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=2`));
    expect(search().has("epicId")).toBe(false);

    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}domain=FLO`));
    expect(search().has("teamId")).toBe(false);
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(search().get("teamId")).toBe("2"));
    expect(search().has("domain")).toBe(false);
  });

  test("a remembered filter holding only a domain is read for that domain, one without a scope for the default team", async () => {
    localStorage.setItem("flow.viewSettings.reports.epic-progress.filter", JSON.stringify("domain=FLO"));
    serveByScope(mockFetch);
    const first = renderPage();
    await screen.findByTestId("line-chart");
    await waitFor(() => expect(search().get("domain")).toBe("FLO"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
    first.unmount();

    mockFetch.mockClear();
    localStorage.setItem("flow.viewSettings.reports.epic-progress.filter", JSON.stringify("lastSprints=3"));
    renderPage();
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    await waitFor(() => expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&teamId=1`]));
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
    expect(await screen.findByText(/Epic progress is read for one team or one domain/)).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("button", { name: /^Filters/ })).not.toBeInTheDocument();
  });

  test("with domains but no team the bar stays up and asks for a pick instead of a request", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [] })
          : url.startsWith(URL_PREFIX)
            ? jsonResponse(200, EPIC_PROGRESS_DOMAIN)
            : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText("Pick a team or a domain to read epic progress for.")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("heading", { level: 3, name: "Epic progress" })).not.toBeInTheDocument();
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
  });

  test("a single epic is read even when no team and no domain exists", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [], domains: [] })
          : url.startsWith(URL_PREFIX)
            ? jsonResponse(200, EPIC_PROGRESS_EPIC)
            : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    renderPage("/reports/epic-progress?epicId=FLO-33");
    expect(await screen.findByRole("heading", { level: 3, name: "Epic: FLO-33 · Reporting epic" })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}epicId=FLO-33`]);
  });
});
