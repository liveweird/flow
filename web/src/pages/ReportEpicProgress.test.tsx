import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportEpicProgress from "./ReportEpicProgress";
import { jsonResponse } from "../test/http";
import {
  EPIC_PROGRESS_DOMAIN,
  EPIC_PROGRESS_EPIC,
  EPIC_PROGRESS_NO_CURVE,
  EPIC_PROGRESS_NO_PLAN,
  EPIC_PROGRESS_NOT_DERIVED,
  EPIC_PROGRESS_TEAM,
  EPIC_PROGRESS_TEAM_NO_COST,
  EPIC_PROGRESS_UNIT,
  FILTERS,
} from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  LineChart: (props: { data: { date: string }[]; series: { name: string; strokeDasharray?: string }[]; withLegend?: boolean }) => (
    <div
      data-testid="line-chart"
      data-days={props.data.map((r) => r.date).join("|")}
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
    // No default period is invented: a bare request.
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
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

  test("clicking a domain narrows to it; the domain level sends only that scope", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/epic-progress?lastSprints=3");
    await user.click(await screen.findByRole("link", { name: "Show Flow" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Domain: Flow" })).toBeInTheDocument();
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&domain=FLO`);
    // Chart: PV, EV, AC only — no original plan outside an epic — with a legend.
    const chart = await screen.findByTestId("line-chart");
    expect(chart.getAttribute("data-series")).toBe("pv,ev,ac");
    expect(chart.getAttribute("data-dashed")).toBe("");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-days")).toBe("2026-09-27|2026-09-28|2026-09-29");
    expect(screen.queryByRole("group", { name: "Foreign work share" })).not.toBeInTheDocument();
    // Its epics are the drill; the breadcrumb leads back to the unit.
    const epics = screen.getByRole("table", { name: "Epics" });
    expect(bodyRows(epics).map((r) => r[0])).toEqual(["FLO-33 Reporting epic", "FLO-40"]);
    expect(screen.getByRole("link", { name: "Show FLO-33" }).getAttribute("href")).toBe(
      "/reports/epic-progress?lastSprints=3&epicId=FLO-33",
    );
    const crumbs = screen.getByRole("navigation", { name: "Where you are in the drill-down" });
    expect(within(crumbs).getByRole("link", { name: "Whole unit" }).getAttribute("href")).toBe("/reports/epic-progress?lastSprints=3");
    expect(within(crumbs).getByText("Flow")).toHaveAttribute("aria-current", "page");
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
    expect(bodyRows(baselines)).toEqual([
      ["2026-04-28", "2026-05-18", "2026-09-01", "2026-10-15", "16"],
      ["2026-05-18", "current", "2026-09-01", "2026-10-31", "20"],
    ]);
    expect(within(plan).queryByRole("note")).not.toBeInTheDocument();
    // The text alternative, behind a disclosure, carries the original plan.
    expect(screen.queryByRole("table", { name: /Cumulative PV, EV and AC by day/ })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Show daily figures" }));
    const daily = screen.getByRole("table", { name: "Cumulative PV, EV and AC by day, as a table" });
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

  test("an epic opened directly has only the unit above it; a malformed drill state is ignored", async () => {
    serve(mockFetch, EPIC_PROGRESS_EPIC);
    renderPage("/reports/epic-progress?epicId=FLO-33", { domain: { key: 7 } });
    const crumbs = await screen.findByRole("navigation", { name: "Where you are in the drill-down" });
    expect(within(crumbs).getAllByRole("link").map((a) => a.textContent)).toEqual(["Whole unit"]);
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

  test("the unit crumb lands on the whole unit even with a remembered team and a bare (default-period) link", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serveByScope(mockFetch);
    const user = userEvent.setup();
    // A bare link starts on the remembered team: TEAM level.
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Team: Alpha" })).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "Where you are in the drill-down" });
    await user.click(within(crumbs).getByRole("link", { name: "Whole unit" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Whole unit" })).toBeInTheDocument();
    await waitFor(() => expect(search().toString()).toBe(""));
    expect(calls(mockFetch).at(-1)).toBe(URL_PREFIX);
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("");
    // The memory is gone too, so the next bare visit stays at the unit.
    expect(localStorage.getItem("flow.viewSettings.reports.teamId")).toBe("null");
  });

  test.each(["domain=FLO", "epicId=FLO-33"])("the unit crumb of a pasted ?%s link (remembered team) lands on the unit", async (query) => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage(`/reports/epic-progress?${query}`);
    const crumbs = await screen.findByRole("navigation", { name: "Where you are in the drill-down" });
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}${query}`]);
    await user.click(within(crumbs).getByRole("link", { name: "Whole unit" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Whole unit" })).toBeInTheDocument();
    expect(search().toString()).toBe("");
    expect(calls(mockFetch).at(-1)).toBe(URL_PREFIX);
    expect(screen.queryByRole("navigation", { name: "Where you are in the drill-down" })).not.toBeInTheDocument();
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
