import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import { jsonResponse } from "../test/http";
import { FILTERS, THROUGHPUT_MONTHS, THROUGHPUT_NOTHING, THROUGHPUT_TEAM, THROUGHPUT_USER, THROUGHPUT_USERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props.
vi.mock("@mantine/charts", () => {
  const probe = (kind: string) => (props: { data: { label: string }[]; series: { name: string }[]; type?: string }) => (
    <div
      data-testid="chart"
      data-kind={kind}
      data-type={props.type ?? ""}
      data-labels={props.data.map((r) => r.label).join("|")}
      data-series={props.series.map((s) => s.name).join(",")}
      data-values={JSON.stringify(props.data.map((r) => (r as unknown as { md: number }).md))}
    />
  );
  return { BarChart: probe("bar"), AreaChart: probe("area"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;

const TEAM_MONTHS = { ...THROUGHPUT_TEAM, byBucket: THROUGHPUT_MONTHS.byBucket };

function reportFor(url: string) {
  const params = new URL(url, "http://x").searchParams;
  const accounts = params.getAll("accountId").length;
  if (accounts > 1) return THROUGHPUT_USERS;
  if (accounts === 1) return THROUGHPUT_USER;
  return params.get("bucket") === "MONTH" ? TEAM_MONTHS : THROUGHPUT_TEAM;
}

interface Serve {
  filters?: () => Response;
  report?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, overrides: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(overrides.filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/throughput?")) return Promise.resolve(overrides.report?.(url) ?? jsonResponse(200, reportFor(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/throughput") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/throughput"
        element={
          <>
            <ReportThroughput />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput"));

const NAMES = {
  period: "Chart: delivered scope by period",
  sprint: "Chart: delivered scope by sprint",
};
/** The probe inside a named chart group (waits for the lazy chunk). */
async function chartIn(name: string): Promise<HTMLElement> {
  return within(await screen.findByRole("group", { name })).getByTestId("chart");
}
const sprintCard = () => screen.getByRole("heading", { name: /^Delivered per sprint/ }).closest("div[class*=Paper]") as HTMLElement;
const search = () => screen.getByTestId("where").textContent;

describe("ReportThroughput page", () => {
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

  test("renders the team's delivered-per-week chart, its totals, the sprint chart and table with drift, and the member groups", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Throughput" })).toBeInTheDocument();
    const period = await chartIn(NAMES.period);
    expect(period.getAttribute("data-kind")).toBe("bar");
    expect(period.getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21");
    expect(period.getAttribute("data-series")).toBe("md");
    expect(screen.getByText("Delivered in the period: 28 MD (11 items)")).toBeInTheDocument();
    expect(screen.getByText("Domain view: delivered in — a task counts in its own domain.")).toBeInTheDocument();

    // The sprint view: its own chart and table, drift only on the drifted sprint, naming the frozen figures.
    expect((await chartIn(NAMES.sprint)).getAttribute("data-labels")).toBe("Alpha 1|Alpha 2");
    const row = within(sprintCard()).getByRole("row", { name: /^Alpha 2 / });
    expect(within(row).getByText("2026-09-10")).toBeInTheDocument();
    expect(within(row).getByText("18")).toBeInTheDocument();
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: delivered 16 MD, 6 items")).toBeInTheDocument();
    expect(screen.getAllByText("Drift")).toHaveLength(1);

    // TEAM level: the groups are the members, and they link in; the unassigned bucket does not.
    expect(screen.getByRole("link", { name: "Show Ann Author" })).toBeInTheDocument();
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Totals per person" })).not.toBeInTheDocument();
    // The request names the team the page started on.
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1"]);
  });

  describe("a team is required", () => {
    test("the team control has no 'All teams' option, no clear button, and its picked option cannot be clicked off", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await chartIn(NAMES.period);
      await openReportFilters(user);

      const team = screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement;
      expect(team.value).toBe("Alpha");
      expect(screen.queryByRole("button", { name: "Clear Team" })).not.toBeInTheDocument();
      await user.click(team);
      expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
      expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
      await user.click(screen.getByRole("option", { name: "Alpha" }));
      expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1"]);
    });

    test("a bare link starts on the remembered team, else the first team by name when the remembered one is gone", async () => {
      localStorage.setItem("flow.viewSettings.reports.teamId", "2");
      serve(mockFetch);
      const first = renderPage();
      await chartIn(NAMES.period);
      await waitFor(() => expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=2"]));
      first.unmount();

      mockFetch.mockReset();
      localStorage.setItem("flow.viewSettings.reports.teamId", "99");
      serve(mockFetch);
      renderPage();
      await chartIn(NAMES.period);
      await waitFor(() => expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1"]));
    });

    test("a link with only a period gets a team too, once, and keeps the period and the chart view", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?lastSprints=3&view=cumulative");
      await chartIn("Chart: delivered scope by period, cumulative");
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=3&teamId=1"]);
      expect(search()).toContain("view=cumulative");
    });

    test.each([
      ["a deleted team", "99"],
      ["the unassigned bucket", "0"],
    ])("a link naming %s is rewritten to the first team by name, once, and drops the picked people", async (_name, teamId) => {
      serve(mockFetch);
      renderPage(`/reports/throughput?teamId=${teamId}&accountId=acc-ann&lastSprints=3`);
      await chartIn(NAMES.period);
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=3&teamId=1"]);
    });

    test("with no team at all the page says so and requests nothing", async () => {
      serve(mockFetch, { filters: () => jsonResponse(200, { ...FILTERS, teams: [] }) });
      renderPage();
      expect(await screen.findByText(/there is no team yet/)).toBeInTheDocument();
      expect(calls(mockFetch)).toEqual([]);
      expect(screen.queryByTestId("chart")).not.toBeInTheDocument();
    });
  });

  describe("several people", () => {
    test("the member control picks several people: repeated accountId in the request, then stacked segments per person", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await chartIn(NAMES.period);
      await openReportFilters(user);

      await user.click(screen.getByRole("combobox", { name: "Member" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&accountId=acc-ann"));
      await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob"));

      const period = await chartIn("Chart: delivered scope by period, stacked by person");
      expect(period.getAttribute("data-kind")).toBe("bar");
      expect(period.getAttribute("data-type")).toBe("stacked");
      expect(period.getAttribute("data-series")).toBe("p0,p1");
      const sprint = await chartIn("Chart: delivered scope by sprint, stacked by person");
      expect(sprint.getAttribute("data-labels")).toBe("Alpha 1|Alpha 2");
      expect(sprint.getAttribute("data-series")).toBe("p0,p1");
      expect(screen.getAllByRole("list", { name: "Chart legend" })).toHaveLength(2);
      expect(screen.getAllByRole("list", { name: "Chart legend" })[0]).toHaveTextContent("1. Ann Author2. Bob Builder");
      // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    });

    test("a link with repeated accountId selects those people and requests them in that order", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
      await chartIn("Chart: delivered scope by period, stacked by person");
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1&accountId=acc-bob&accountId=acc-ann"]);
      await openReportFilters();
      const pills = screen.getByRole("group", { name: "Report filters" });
      expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
      expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
    });

    test("every chart number is in a table: the period and sprint tables, the totals per person and each person's share", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await chartIn("Chart: delivered scope by period, stacked by person");

      const period = screen.getByRole("table", { name: "Delivered per period, as a table" });
      expect(within(period).getAllByRole("row").slice(1).map((r) => r.textContent)).toEqual(["2026-09-0794", "2026-09-1400", "2026-09-217.53"]);
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getByRole("row", { name: /1\. Ann Author/ })).toHaveTextContent("10.5");
      expect(within(totals).getByRole("row", { name: /2\. Bob Builder/ })).toHaveTextContent("6");
      // The team level's drill table is gone: nothing is below a person.
      expect(screen.queryByRole("link", { name: "Show Ann Author" })).not.toBeInTheDocument();

      const periodShares = screen.getByRole("table", { name: "Each person's delivered scope per period" });
      expect(within(periodShares).getByRole("row", { name: /2026-09-21 1\. Ann Author/ })).toHaveTextContent("4.52");
      expect(within(periodShares).getByRole("row", { name: /2026-09-21 2\. Bob Builder/ })).toHaveTextContent("31");
      const sprintShares = screen.getByRole("table", { name: "Each person's delivered scope per sprint" });
      expect(within(sprintShares).getByRole("row", { name: /Alpha 2 2\. Bob Builder/ })).toHaveTextContent("2.51");
      // The sprint table keeps the drift badge and the frozen figures.
      expect(within(sprintCard()).getByText("Frozen at completion: delivered 6 MD, 3 items")).toBeInTheDocument();
    });

    test("with no period buckets (a sprint not yet started) the people still come from the sprint view", async () => {
      serve(mockFetch, { report: () => jsonResponse(200, { ...THROUGHPUT_USERS, byBucket: [] }) });
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");

      const totals = await screen.findByRole("table", { name: "Totals per person" });
      expect(within(totals).getByRole("row", { name: /1\. Ann Author/ })).toBeInTheDocument();
      expect(within(totals).getByRole("row", { name: /2\. Bob Builder/ })).toBeInTheDocument();
      expect(screen.getByRole("table", { name: "Each person's delivered scope per sprint" })).toBeInTheDocument();
    });

    test("one person is the same view with a single stack, their group, and no per-person share tables", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann");
      const period = await chartIn("Chart: delivered scope by period, stacked by person");
      expect(period.getAttribute("data-series")).toBe("p0");
      const row = within(sprintCard()).getByRole("row", { name: /^Alpha 2 / });
      expect(within(row).getByText("7.5")).toBeInTheDocument();
      expect(within(row).getByText("Drift")).toBeInTheDocument();
      expect(within(row).getByText("Frozen at completion: delivered 6 MD, 3 items")).toBeInTheDocument();
      expect(within(screen.getByRole("table", { name: "Totals per person" })).getByRole("row", { name: /1\. Ann Author/ })).toHaveTextContent("28");
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      expect(screen.queryByRole("table", { name: "Each person's delivered scope per period" })).not.toBeInTheDocument();
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1&accountId=acc-ann"]);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await chartIn("Chart: delivered scope by period, stacked by person");
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=2"));
    });

    test("drilling into a member from the team table narrows to that person", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      await user.click(await screen.findByRole("link", { name: "Show Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&accountId=acc-ann"));
      expect(await chartIn("Chart: delivered scope by period, stacked by person")).toBeInTheDocument();
    });
  });

  describe("per period or cumulative", () => {
    test("the switch flips both charts to stacked areas of running totals and writes the URL, without a new request", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/throughput?teamId=1");
      const bars = await chartIn(NAMES.period);
      expect(bars.getAttribute("data-values")).toBe("[12.5,0,15.5]");
      const view = screen.getByRole("radiogroup", { name: "Chart view" });
      expect(within(view).getByRole("radio", { name: "Per period" })).toBeChecked();
      expect(search()).not.toContain("view");

      await user.click(within(view).getByText("Cumulative"));

      const area = await chartIn("Chart: delivered scope by period, cumulative");
      expect(area.getAttribute("data-kind")).toBe("area");
      expect(area.getAttribute("data-values")).toBe("[12.5,12.5,28]");
      const sprints = await chartIn("Chart: delivered scope by sprint, cumulative");
      expect(sprints.getAttribute("data-kind")).toBe("area");
      expect(sprints.getAttribute("data-values")).toBe("[10,28]");
      expect(within(view).getByRole("radio", { name: "Cumulative" })).toBeChecked();
      expect(search()).toBe("?teamId=1&view=cumulative");
      expect(screen.getByRole("heading", { name: "Delivered per period, cumulative" })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Delivered per sprint, cumulative" })).toBeInTheDocument();
      // The view is not a filter: nothing is refetched, and the request never carries it.
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1"]);

      await user.click(within(view).getByText("Per period"));
      expect((await chartIn(NAMES.period)).getAttribute("data-kind")).toBe("bar");
      expect(search()).toBe("?teamId=1");
    });

    test("a link with view=cumulative opens on it; any other value is the per-period default", async () => {
      serve(mockFetch);
      const first = renderPage("/reports/throughput?teamId=1&view=cumulative");
      expect((await chartIn("Chart: delivered scope by period, cumulative")).getAttribute("data-kind")).toBe("area");
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?teamId=1"]);
      first.unmount();
      renderPage("/reports/throughput?teamId=1&view=weird");
      expect((await chartIn(NAMES.period)).getAttribute("data-kind")).toBe("bar");
    });

    test("the cumulative tables add the running totals, so the areas' numbers are all in a table", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&view=cumulative");
      await chartIn("Chart: delivered scope by period, cumulative");
      const period = screen.getByRole("table", { name: "Delivered per period, as a table" });
      expect(within(period).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Week starting",
        "Delivered (MD)",
        "Delivered (items)",
        "Cumulative (MD)",
        "Cumulative (items)",
      ]);
      expect(within(period).getAllByRole("row").slice(1).map((r) => r.textContent)).toEqual([
        "2026-09-0712.5512.55",
        "2026-09-140012.55",
        "2026-09-2115.562811",
      ]);
      const sprints = within(sprintCard()).getByRole("table", { name: "Sprints and their figures" });
      expect(within(sprints).getByRole("columnheader", { name: "Cumulative (MD)" })).toBeInTheDocument();
      expect(within(within(sprints).getByRole("row", { name: /^Alpha 2 / })).getAllByRole("cell").map((c) => c.textContent)).toContain("28");
    });

    test("with people picked the cumulative chart stacks them and the share tables carry their running totals", async () => {
      serve(mockFetch);
      renderPage("/reports/throughput?teamId=1&accountId=acc-ann&accountId=acc-bob&view=cumulative");
      const period = await chartIn("Chart: delivered scope by period, cumulative, stacked by person");
      expect(period.getAttribute("data-kind")).toBe("area");
      expect(period.getAttribute("data-series")).toBe("p0,p1");
      expect(period.getAttribute("data-values")).toBe("[9,9,16.5]");
      const shares = screen.getByRole("table", { name: "Each person's delivered scope per period" });
      expect(within(shares).getByRole("columnheader", { name: "Cumulative (MD)" })).toBeInTheDocument();
      expect(within(shares).getByRole("row", { name: /2026-09-21 1\. Ann Author/ })).toHaveTextContent("4.5210.55");
    });
  });

  test("the bucket control writes the URL, refetches and relabels the buckets", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await chartIn(NAMES.period);
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Week" })).toBeChecked();

    await user.click(screen.getByText("Month"));

    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&bucket=MONTH"));
    await waitFor(async () => expect((await chartIn(NAMES.period)).getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(screen.getByRole("radio", { name: "Month" })).toBeChecked();
  });

  test("a bucket in the link is honoured and part of the request", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?bucket=MONTH&lastSprints=3&teamId=1");
    await waitFor(async () => expect((await chartIn(NAMES.period)).getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=3&teamId=1&bucket=MONTH"]);
  });

  test("offers the domain view, domain, activity type and work category controls, and they filter the request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await chartIn(NAMES.period);
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&domainView=EPIC"));
    expect(screen.getByText("Domain view: earned in — a task counts in its epic's domain.")).toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&domainView=EPIC&activityType=Bug"));
  });

  test("a period with nothing delivered shows the empty state for the chart; an empty sprint view is its own state", async () => {
    serve(mockFetch, { report: () => jsonResponse(200, { ...THROUGHPUT_NOTHING, meta: THROUGHPUT_TEAM.meta }) });
    renderPage();
    expect(await screen.findAllByText("No data in this period")).toHaveLength(2);
    expect(screen.queryByTestId("chart")).not.toBeInTheDocument();
  });

  test("a sprint-less period still charts the period view", async () => {
    serve(mockFetch, { report: () => jsonResponse(200, { ...THROUGHPUT_TEAM, bySprint: [] }) });
    renderPage();
    expect(await chartIn(NAMES.period)).toBeInTheDocument();
    expect(screen.getAllByText("No data in this period")).toHaveLength(1);
    expect(screen.queryByRole("group", { name: NAMES.sprint })).not.toBeInTheDocument();
  });

  test("a report failure is ONE inline alert (the sprint card is not repeated); the filter bar stays usable", async () => {
    serve(mockFetch, { report: () => jsonResponse(500, { title: "boom", status: 500 }) });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Delivered per sprint" })).not.toBeInTheDocument();
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("the chart's numbers are also a table — one row per bucket, the bucket's first day, MD and items", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const table = await screen.findByRole("table", { name: "Delivered per period, as a table" });
    expect(within(table).getByRole("columnheader", { name: "Week starting" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.textContent)).toEqual(["2026-09-0712.55", "2026-09-1400", "2026-09-2115.56"]);

    await openReportFilters();
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Delivered per period, as a table" })).getByRole("columnheader", { name: "Month" })).toBeInTheDocument());
    expect(within(screen.getByRole("table", { name: "Delivered per period, as a table" })).getByText("2026-08")).toBeInTheDocument();
  });

  test("says the sprint view ignores the domain, activity type and work category filters", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText(/It ignores the domain, activity type and work category filters\./)).toBeInTheDocument();
  });

  test("changing the bucket keeps the previous chart on screen, busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("bucket=MONTH")) return deferred;
      return Promise.resolve(jsonResponse(200, THROUGHPUT_TEAM));
    });
    const user = userEvent.setup();
    renderPage();
    await chartIn(NAMES.period);
    const busy = async () => (await chartIn(NAMES.period)).closest("[aria-busy]");
    expect(await busy()).toHaveAttribute("aria-busy", "false");

    await openReportFilters();
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?teamId=1&bucket=MONTH"));
    expect(await busy()).toHaveAttribute("aria-busy", "true");
    expect((await chartIn(NAMES.period)).getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21");

    release?.(jsonResponse(200, TEAM_MONTHS));
    await waitFor(async () => expect((await chartIn(NAMES.period)).getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(await busy()).toHaveAttribute("aria-busy", "false");
  });
});
