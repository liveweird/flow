import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import {
  AGING_HIDDEN,
  AGING_TRUNCATED,
  AGING_UNIT,
  CONSISTENCY_EMPTY,
  CONSISTENCY_UNIT,
  CYCLE_TIME,
  CYCLE_TIME_ALL_HIDDEN,
  CYCLE_TIME_EMPTY,
  DATA_QUALITY,
  DATA_QUALITY_CLEAN,
  FILTERS,
} from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { todayIsoDate } from "../utils/isoDate";
import { presetRange } from "../utils/reportFilter";

const ROLES_KEY = "flow.auth.roles";

// recharts renders nothing under happy-dom (no layout): both charts are probes of their props.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { team: string; initialMd: number; finalMd: number; deliveredMd: number }[]; series: { name: string }[] }) => (
    <div
      data-testid="bar-chart"
      data-teams={props.data.map((r) => r.team).join("|")}
      data-delivered={props.data.map((r) => String(r.deliveredMd)).join(",")}
      data-series={props.series.map((s) => s.name).join(",")}
    />
  ),
  LineChart: (props: { data: { label: string; p50: number | null }[]; h: number; connectNulls: boolean }) => (
    <div
      data-testid="line-chart"
      data-p50={props.data.map((r) => String(r.p50)).join(",")}
      data-height={String(props.h)}
      data-connect-nulls={String(props.connectNulls)}
    />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
type Answers = Partial<Record<"filters" | "sprint-consistency" | "cycle-time" | "aging-wip" | "data-quality", () => Response>>;

/** The overview endpoints (the shared filters and the four reports), each answering its fixture unless the test overrides it. */
function serve(mockFetch: FetchMock, answers: Answers = {}) {
  const defaults: Required<Answers> = {
    filters: () => jsonResponse(200, FILTERS),
    "sprint-consistency": () => jsonResponse(200, CONSISTENCY_UNIT),
    "cycle-time": () => jsonResponse(200, CYCLE_TIME),
    "aging-wip": () => jsonResponse(200, AGING_UNIT),
    "data-quality": () => jsonResponse(200, DATA_QUALITY),
  };
  mockFetch.mockImplementation((url: string) => {
    const match = /^\/api\/v1\/reports\/([a-z-]+)\??/.exec(url);
    const answer = match ? { ...defaults, ...answers }[match[1] as keyof Answers] : undefined;
    return Promise.resolve(answer ? answer() : jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

const never = () => new Promise<Response>(() => undefined) as unknown as Response;
const failing = () => jsonResponse(500, { title: "Boom", status: 500 });
const notDerived = <T extends { meta: object }>(report: T): T => ({ ...report, meta: { ...report.meta, derivedAt: null } });
const requested = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string);

function renderHome() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Home />} />
    </Routes>,
  );
}

const tile = (name: string) => screen.getByRole("region", { name });

describe("Home page — the unit overview", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem(ROLES_KEY, "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("asks for the shared reference data and the four unit-level reports, nothing else, however many tiles it draws", async () => {
    // A remembered team must not leak into the unit-wide requests either.
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderHome();
    await screen.findByRole("region", { name: "Data quality" });
    await waitFor(() => expect(screen.getByTestId("bar-chart")).toBeInTheDocument());
    expect(requested(mockFetch).sort()).toEqual([
      "/api/v1/reports/aging-wip?",
      "/api/v1/reports/cycle-time?",
      "/api/v1/reports/data-quality?",
      "/api/v1/reports/filters",
      "/api/v1/reports/sprint-consistency?lastSprints=1",
    ]);
    expect(requested(mockFetch).length).toBeLessThanOrEqual(5);
  });

  test("velocity: initial, final and delivered per team from one report, as a chart and a table with the drift marker", async () => {
    serve(mockFetch);
    renderHome();
    const velocity = await screen.findByRole("region", { name: "Velocity and throughput" });
    const chart = await within(velocity).findByTestId("bar-chart");
    expect(chart.getAttribute("data-teams")).toBe("Alpha|Beta");
    expect(chart.getAttribute("data-delivered")).toBe("18,10");
    expect(chart.getAttribute("data-series")).toBe("initialMd,finalMd,deliveredMd");
    expect(within(velocity).getByRole("group", { name: /Chart: initial scope, final scope and delivered scope/ })).toBeInTheDocument();

    const table = within(velocity).getByRole("table", { name: "Each team's last closed sprint, as a table" });
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "Alpha 2", "2026-09-10", "20", "24.5", "18", "DriftFrozen at completion: committed 20 · final 22 · delivered 16.5 MD"],
      ["Beta", "Beta 1", "2026-09-01", "10", "10", "10", ""],
    ]);
    expect(within(velocity).getByText(/Each team's last closed sprint, in man-days/)).toBeInTheDocument();
  });

  test("cycle time: median, p90 and finished count, the smaller trend chart with its gaps, and the trend as a table", async () => {
    serve(mockFetch);
    renderHome();
    const cycle = await screen.findByRole("region", { name: "Cycle time" });
    const stats = await within(cycle).findByRole("group", { name: "Cycle time in working days" });
    expect(within(stats).getByText("3.5")).toBeInTheDocument();
    expect(within(stats).getByText("7.5")).toBeInTheDocument();
    expect(within(stats).getByText("18")).toBeInTheDocument();
    expect(within(cycle).getByText("Tasks finished in the last 90 days (2026-07-02 – 2026-09-29), in working days.")).toBeInTheDocument();

    const chart = await within(cycle).findByTestId("line-chart");
    expect(chart.getAttribute("data-p50")).toBe("3,null,2.5,null");
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(chart.getAttribute("data-height")).toBe("200");
    const table = within(cycle).getByRole("table", { name: "Trend, as a table" });
    expect(within(table).getAllByRole("row")).toHaveLength(5);
  });

  test("cycle time: no finished task, a hidden median and a trend with no plottable week each say so", async () => {
    serve(mockFetch, { "cycle-time": () => jsonResponse(200, CYCLE_TIME_EMPTY) });
    const { unmount } = renderHome();
    expect(await within(await screen.findByRole("region", { name: "Cycle time" })).findByText("No task finished in this period.")).toBeInTheDocument();
    unmount();

    serve(mockFetch, { "cycle-time": () => jsonResponse(200, CYCLE_TIME_ALL_HIDDEN) });
    renderHome();
    const cycle = await screen.findByRole("region", { name: "Cycle time" });
    expect(await within(cycle).findByText("No period has enough finished items to show a median and p90.")).toBeInTheDocument();
    expect(within(cycle).queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("work in progress: the count, past p85 (orange badge) and past p95 (red badge), tasks only", async () => {
    serve(mockFetch);
    renderHome();
    const wip = await screen.findByRole("region", { name: "Work in progress" });
    const stats = await within(wip).findByRole("group", { name: "Work in progress and aging" });
    const cells = ["In progress", "Past p85", "Past p95"].map((label) => within(stats).getByRole("group", { name: label }).textContent);
    // 4 tasks (the epic is not counted); 2 past p85 (FLO-1 above p95, FLO-2); 1 past p95.
    expect(cells).toEqual(["In progress4", "Past p852", "Past p951"]);
    expect(within(stats).getByText("Past p85").className).toMatch(/Badge/);
    expect(within(wip).queryByText(/at least/)).not.toBeInTheDocument();
  });

  test("work in progress: hidden thresholds show dashes and say why; a truncated list says the figures are at least", async () => {
    serve(mockFetch, { "aging-wip": () => jsonResponse(200, AGING_HIDDEN) });
    const { unmount } = renderHome();
    const hidden = await screen.findByRole("region", { name: "Work in progress" });
    expect(await within(hidden).findByText("Not enough finished tasks for aging thresholds (3 of the 5 needed).")).toBeInTheDocument();
    expect(within(hidden).getByRole("group", { name: "Past p85" })).toHaveTextContent("Past p85—");
    unmount();

    serve(mockFetch, { "aging-wip": () => jsonResponse(200, AGING_TRUNCATED) });
    renderHome();
    const truncated = await screen.findByRole("region", { name: "Work in progress" });
    expect(await within(truncated).findByText("The list holds the oldest 500 items, so these figures are at least.")).toBeInTheDocument();
    expect(within(truncated).getByRole("group", { name: "In progress" })).toHaveTextContent("In progress4+");
  });

  test("data quality: configuration kinds first, then the largest volumes; the rest counted; a link to all findings", async () => {
    serve(mockFetch);
    renderHome();
    const quality = await screen.findByRole("region", { name: "Data quality" });
    const list = await within(quality).findByRole("list", { name: "Data-quality findings, largest first" });
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Domains without an owner team1",
      "Statuses without a stage1",
      "Boards without a team1",
      "Derive warnings1",
      "Tasks without an epic7",
    ]);
    expect(within(quality).getByText("and 12 more kinds of findings")).toBeInTheDocument();
    expect(within(quality).getByText(/Period-bound findings cover the last 90 days; configuration findings are current/)).toBeInTheDocument();
    expect(within(quality).getByRole("link", { name: "All findings" })).toHaveAttribute(
      "href",
      "/reports/data-quality?from=2026-07-02&to=2026-09-29",
    );
  });

  test("data quality: exactly one more kind reads in the singular", async () => {
    const one = (total: number) => ({ total, items: [] });
    const six = {
      ...DATA_QUALITY_CLEAN,
      authorsWithoutTeam: one(1),
      epicDrift: one(1),
      snapshotDrift: one(1),
      unmappedBoards: { ...one(1), unattributedDoneTasks: 0 },
      outsideSprint: { ...DATA_QUALITY_CLEAN.outsideSprint, total: 1, done: 1 },
      crossDomain: { ...DATA_QUALITY_CLEAN.crossDomain, total: 1, done: 1 },
    };
    serve(mockFetch, { "data-quality": () => jsonResponse(200, six) });
    renderHome();
    const quality = await screen.findByRole("region", { name: "Data quality" });
    expect(await within(quality).findByText("and 1 more kind of finding")).toBeInTheDocument();
  });

  test("data quality: a clean report says none found instead of an empty list", async () => {
    serve(mockFetch, { "data-quality": () => jsonResponse(200, DATA_QUALITY_CLEAN) });
    renderHome();
    const quality = await screen.findByRole("region", { name: "Data quality" });
    expect(await within(quality).findByText("None found")).toBeInTheDocument();
    expect(within(quality).queryByRole("list")).not.toBeInTheDocument();
  });

  test("every tile title links into its full report, carrying the period the tile was computed over", async () => {
    serve(mockFetch);
    renderHome();
    await screen.findByRole("group", { name: "Cycle time in working days" });
    const period = "from=2026-07-02&to=2026-09-29";
    await waitFor(() =>
      expect(within(tile("Cycle time")).getByRole("link", { name: "Cycle time" })).toHaveAttribute("href", `/reports/cycle-time?${period}`),
    );
    const velocity = await screen.findByRole("region", { name: "Velocity and throughput" });
    expect(await within(velocity).findByRole("link", { name: "Velocity and throughput" })).toHaveAttribute("href", "/reports/velocity?lastSprints=1");
    expect(within(velocity).getByRole("link", { name: "Throughput report" })).toHaveAttribute("href", "/reports/throughput?lastSprints=1");
    expect(within(velocity).getByRole("link", { name: "Sprint consistency report" })).toHaveAttribute(
      "href",
      "/reports/sprint-consistency?lastSprints=1",
    );
    expect(within(tile("Work in progress")).getByRole("link", { name: "Work in progress" })).toHaveAttribute("href", `/reports/aging-wip?${period}`);
    expect(within(tile("Data quality")).getByRole("link", { name: "Data quality" })).toHaveAttribute("href", `/reports/data-quality?${period}`);
  });

  test.each([
    ["still loading", never],
    ["failed", failing],
  ])("no link is ever bare while both period-bearing reports are %s: it carries the trailing 90 days in the configured zone", async (_state, answer) => {
    // A bare report URL would start on the remembered team; the overview is unit-wide.
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch, { "cycle-time": answer, "data-quality": answer });
    renderHome();
    const { from, to } = presetRange("last90", todayIsoDate(FILTERS.timeZone));
    const velocity = await screen.findByRole("region", { name: "Velocity and throughput" });
    await within(velocity).findByRole("link", { name: "Sprint consistency report" });
    const reportLinks = screen.getAllByRole("link").map((link) => link.getAttribute("href") ?? "").filter((href) => href.startsWith("/reports/"));
    expect(reportLinks.length).toBeGreaterThanOrEqual(6);
    expect(reportLinks.every((href) => href.includes("?"))).toBe(true);
    // The configured zone replaces the provisional UTC period once the reference data arrives.
    await waitFor(() =>
      expect(within(tile("Cycle time")).getByRole("link", { name: "Cycle time" })).toHaveAttribute("href", `/reports/cycle-time?from=${from}&to=${to}`),
    );
    expect(within(tile("Work in progress")).getByRole("link", { name: "Work in progress" })).toHaveAttribute("href", `/reports/aging-wip?from=${from}&to=${to}`);
    expect(within(tile("Data quality")).getByRole("link", { name: "Data quality" })).toHaveAttribute("href", `/reports/data-quality?from=${from}&to=${to}`);
  });

  test.each([
    ["still loading", never],
    ["failed", failing],
  ])("with the reference data %s too, the links still carry the local UTC trailing 90 days", async (_state, answer) => {
    serve(mockFetch, { filters: answer, "cycle-time": answer, "data-quality": answer });
    renderHome();
    const { from, to } = presetRange("last90", todayIsoDate());
    const velocity = await screen.findByRole("region", { name: "Velocity and throughput" });
    await within(velocity).findByRole("link", { name: "Sprint consistency report" });
    expect(within(tile("Cycle time")).getByRole("link", { name: "Cycle time" })).toHaveAttribute("href", `/reports/cycle-time?from=${from}&to=${to}`);
    expect(within(tile("Data quality")).getByRole("link", { name: "Data quality" })).toHaveAttribute("href", `/reports/data-quality?from=${from}&to=${to}`);
  });

  test("the Closed date is the day in the configured zone, not in UTC; without the reference data it falls back to UTC", async () => {
    // 22:30 UTC on the 10th is already the 11th in Warsaw (UTC+2 in September).
    const lateClose = {
      ...CONSISTENCY_UNIT,
      sprints: [{ ...CONSISTENCY_UNIT.sprints[0], completedAt: Date.UTC(2026, 8, 10, 22, 30) }],
    };
    serve(mockFetch, { "sprint-consistency": () => jsonResponse(200, lateClose) });
    const { unmount } = renderHome();
    const table = await within(await screen.findByRole("region", { name: "Velocity and throughput" })).findByRole("table");
    await waitFor(() => expect(within(table).getByText("2026-09-11")).toBeInTheDocument());
    unmount();

    serve(mockFetch, { filters: failing, "sprint-consistency": () => jsonResponse(200, lateClose) });
    renderHome();
    const utcTable = await within(await screen.findByRole("region", { name: "Velocity and throughput" })).findByRole("table");
    expect(within(utcTable).getByText("2026-09-10")).toBeInTheDocument();
  });

  test("a failing report shows its own error in its own tile; the other tiles still render", async () => {
    serve(mockFetch, { "cycle-time": failing });
    renderHome();
    const cycle = await screen.findByRole("region", { name: "Cycle time" });
    expect(await within(cycle).findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(within(await screen.findByRole("region", { name: "Data quality" })).getByRole("list")).toBeInTheDocument();
    expect(within(tile("Work in progress")).getByRole("group", { name: "Work in progress and aging" })).toBeInTheDocument();
    expect(await within(tile("Velocity and throughput")).findByRole("table")).toBeInTheDocument();
    // The failed report cannot date the links, the surviving from/to report (data quality) does.
    await waitFor(() =>
      expect(within(tile("Data quality")).getByRole("link", { name: "Data quality" })).toHaveAttribute("href", expect.stringContaining("from=2026-07-02")),
    );
  });

  test("the velocity tile shows its report's failure and no table", async () => {
    serve(mockFetch, { "sprint-consistency": () => jsonResponse(503, { title: "Down", status: 503 }) });
    renderHome();
    const velocity = await screen.findByRole("region", { name: "Velocity and throughput" });
    expect(await within(velocity).findByRole("alert")).toHaveTextContent("Load failed (503)");
    expect(within(velocity).queryByRole("table")).not.toBeInTheDocument();
    expect(within(velocity).queryByRole("link", { name: "Throughput report" })).not.toBeInTheDocument();
  });

  test("a report that has not answered yet leaves that tile busy while the others render; one polite live region says so", async () => {
    serve(mockFetch, { "data-quality": never });
    renderHome();
    expect(await within(await screen.findByRole("region", { name: "Cycle time" })).findByRole("group", { name: "Cycle time in working days" })).toBeInTheDocument();
    expect(tile("Data quality")).toHaveAttribute("aria-busy", "true");
    expect(within(tile("Data quality")).queryByRole("list")).not.toBeInTheDocument();
    expect(tile("Cycle time")).toHaveAttribute("aria-busy", "false");
    // One status region for the whole page, not one per loading tile.
    expect(screen.getAllByRole("status")).toHaveLength(1);
    expect(screen.getByRole("status")).toHaveTextContent("Loading…");
  });

  test("the live region falls silent once everything has loaded", async () => {
    serve(mockFetch);
    renderHome();
    await screen.findByRole("group", { name: "Cycle time in working days" });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(""));
  });

  test("a closed-sprint-free unit says so in the velocity tile", async () => {
    serve(mockFetch, { "sprint-consistency": () => jsonResponse(200, CONSISTENCY_EMPTY) });
    renderHome();
    expect(await within(await screen.findByRole("region", { name: "Velocity and throughput" })).findByText("No closed sprint yet.")).toBeInTheDocument();
  });

  describe("when nothing has been derived yet", () => {
    const allNotDerived: Answers = {
      "sprint-consistency": () => jsonResponse(200, notDerived(CONSISTENCY_UNIT)),
      "cycle-time": () => jsonResponse(200, notDerived(CYCLE_TIME)),
      "aging-wip": () => jsonResponse(200, notDerived(AGING_UNIT)),
      "data-quality": () => jsonResponse(200, notDerived(DATA_QUALITY)),
    };

    test("a regular user sees the plain empty state and no admin links", async () => {
      serve(mockFetch, allNotDerived);
      renderHome();
      expect(await screen.findByText(/^No data has been derived yet\. The overview appears/)).toBeInTheDocument();
      expect(screen.queryByRole("region")).not.toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "Add a data source" })).not.toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "Metrics settings" })).not.toBeInTheDocument();
    });

    test("an admin gets links to the data sources and the metrics settings", async () => {
      localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
      serve(mockFetch, allNotDerived);
      renderHome();
      expect(await screen.findByText(/^No data has been derived yet\. Connect a data source/)).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Add a data source" })).toHaveAttribute("href", "/data-sources");
      expect(screen.getByRole("link", { name: "Metrics settings" })).toHaveAttribute("href", "/metrics-settings");
    });

    test("a failed report keeps the grid and its error: the empty state never hides a failure", async () => {
      serve(mockFetch, { ...allNotDerived, "aging-wip": failing });
      renderHome();
      const wip = await screen.findByRole("region", { name: "Work in progress" });
      expect(await within(wip).findByRole("alert")).toHaveTextContent("Load failed (500)");
      expect(screen.queryByText(/No data has been derived yet/)).not.toBeInTheDocument();
    });
  });

  test("when every report fails no empty state claims nothing was derived; each tile shows its error", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(failing()));
    renderHome();
    await waitFor(() => expect(screen.getAllByRole("alert")).toHaveLength(4));
    expect(screen.queryByText(/No data has been derived yet/)).not.toBeInTheDocument();
  });

  test("the page says it is unit-wide and states each tile's period or scope", async () => {
    serve(mockFetch);
    renderHome();
    expect(await screen.findByText(/The whole unit at a glance/)).toBeInTheDocument();
    expect(screen.getByText(/not your remembered team/)).toBeInTheDocument();
    await screen.findByRole("group", { name: "Cycle time in working days" });
    expect(screen.getByText(/Tasks in progress right now/)).toBeInTheDocument();
  });
});
