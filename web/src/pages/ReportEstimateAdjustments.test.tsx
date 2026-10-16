import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import ReportEstimateAdjustments from "./ReportEstimateAdjustments";
import { jsonResponse } from "../test/http";
import { ADJUSTMENTS, ADJUSTMENTS_EMPTY, ADJUSTMENTS_USER, ADJUSTMENTS_USERS, FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histograms are probes.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: ({ label: string; count?: number; tail?: number } & Record<string, unknown>)[];
    series: { name: string; label: string }[];
    type?: string;
  }) => (
    <div
      data-testid="bar-chart"
      data-labels={props.data.map((r) => r.label).join("|")}
      data-counts={props.data.map((r) => r.count ?? `tail ${r.tail}`).join(",")}
      data-type={props.type ?? ""}
      data-series={props.series.map((s) => s.name).join(",")}
      data-series-labels={props.series.map((s) => s.label).join("|")}
      data-stacks={JSON.stringify(props.data.map((r) => props.series.map((s) => r[s.name])))}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/estimate-adjustments?";
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

/** The fixture for the request: several `accountId`s = the stacked split, one = that person, none = the team read. */
function reportFor(url: string) {
  const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
  if (accounts > 1) return ADJUSTMENTS_USERS;
  if (accounts === 1) return ADJUSTMENTS_USER;
  return ADJUSTMENTS;
}

function serve(mockFetch: FetchMock, report?: () => Response, filters?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, reportFor(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/estimate-adjustments") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/estimate-adjustments"
        element={
          <>
            <ReportEstimateAdjustments />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));

/** The four figures of one block, by label. */
function figures(block: HTMLElement) {
  const group = within(block).getAllByRole("group")[0];
  return Object.fromEntries(
    ["Started", "Changed after start", "Estimated late", "Share changed"].map((label) => [
      label,
      within(group).getByText(label).nextElementSibling?.textContent,
    ]),
  );
}

describe("ReportEstimateAdjustments page", () => {
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

  test("tasks block: started, changed, share as a percentage, estimated late, and the change distribution as percentages", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Estimate adjustments" })).toBeInTheDocument();
    const tasks = (await screen.findByRole("heading", { name: "Tasks" })).parentElement as HTMLElement;
    expect(figures(tasks)).toEqual({ Started: "30", "Changed after start": "6", "Estimated late": "2", "Share changed": "20%" });

    // Fractions are shown as signed percentages: the distribution table and the strip.
    const strip = within(tasks).getByRole("group", { name: "Percentiles" });
    expect(within(strip).getByText("+10%")).toBeInTheDocument();
    expect(within(strip).getByText("+60%")).toBeInTheDocument();
    const table = within(tasks).getByRole("table", { name: "Histogram, as a table — Change from start to done" });
    expect(within(table).getAllByRole("row").slice(1).map((r) => r.firstElementChild?.textContent)).toEqual([
      "−25% – 0%",
      "0% – +25%",
      "+25% – +50%",
      "+50% – +75%",
    ]);
    expect((await screen.findAllByTestId("bar-chart")).map((c) => c.getAttribute("data-counts"))).toEqual(["1,4,3,2"]);

    // Estimated late is counted separately, never as an infinite change.
    expect(within(tasks).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim())).toEqual([
      "10 in the distribution",
      "2 Estimated late — no estimate at start",
      "3 No estimate at start or at done (or never started)",
    ]);
    // … and the accounting reconciles: n + late + unestimated = population.
    expect(within(tasks).getByText("10 + 2 + 3 = 15")).toBeInTheDocument();
    // An estimated-late item is also a change after start — said where the counts are.
    expect(within(tasks).getByText("Estimated late is included in Changed after start.")).toBeInTheDocument();
  });

  test("epics block below the minimum: the share is withheld behind the notice, the distribution too — counts stay", async () => {
    serve(mockFetch);
    renderPage();
    const epics = (await screen.findByRole("heading", { name: "Epics" })).parentElement as HTMLElement;
    const group = within(epics).getAllByRole("group")[0];
    expect(within(group).getByText("Started").nextElementSibling).toHaveTextContent("3");
    expect(within(group).getByText("Changed after start").nextElementSibling).toHaveTextContent("1");
    const notes = within(epics).getAllByRole("note");
    expect(notes[0]).toHaveTextContent("Only 3 items started in this selection — at least 5 are needed to show a share.");
    expect(notes[1]).toHaveTextContent("Only 2 items in this selection — at least 5 are needed to show percentiles and a histogram.");
    expect(within(epics).queryByRole("table", { name: /Histogram/ })).not.toBeInTheDocument();
    expect(within(epics).getByText("Of 4 finished in this period:")).toBeInTheDocument();
  });

  test("groups: tasks and epics side by side; a hidden share and an absent kind are dashes", async () => {
    serve(mockFetch);
    renderPage();
    const groups = (await screen.findByRole("heading", { name: "By team" })).closest("div") as HTMLElement;
    const rows = within(groups).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "20", "25%", "3", "—"],
      ["Beta", "10", "10%", "0", "—"],
    ]);
  });

  test("says the domain view applies to tasks only", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText("The domain view applies to tasks; epics always read their own space.")).toBeInTheDocument();
  });

  test("at USER level the Epics block is one line, not zeros and three 'nothing to measure' notes", async () => {
    serve(mockFetch);
    renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const epics = (await screen.findByRole("heading", { name: "Epics" })).parentElement as HTMLElement;
    expect(within(epics).getByRole("note")).toHaveTextContent("Epics aren't attributed to individual people");
    expect(within(epics).queryByText("Started")).not.toBeInTheDocument();
    expect(within(epics).queryByText("Nothing to measure in this selection.")).not.toBeInTheDocument();
    // Tasks still render.
    expect(screen.getByRole("heading", { name: "Tasks" })).toBeInTheDocument();
  });

  test("block titles are h4, distributions and accountings h5", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { name: "Tasks" });
    expect(screen.getAllByRole("heading", { level: 4 }).map((h) => h.textContent)).toEqual(["Tasks", "Epics"]);
    expect(screen.getAllByRole("heading", { level: 5 }).length).toBeGreaterThanOrEqual(4);
  });

  test("offers the domain view, domain, activity type and work category", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { name: "Tasks" });
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
  });

  test("nothing started or finished is the empty state, not two blocks of zeros", async () => {
    serve(mockFetch, () => jsonResponse(200, ADJUSTMENTS_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Tasks" })).not.toBeInTheDocument();
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });

  test("calls the report for the first team by name, never the whole unit", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(calls(mockFetch)).toEqual([TEAM]);
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));
  });

  describe("a team is required", () => {
    test("the team control has no 'All teams' option, no clear button, and its picked option cannot be clicked off", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await screen.findAllByTestId("bar-chart");
      await openReportFilters(user);

      const team = screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement;
      expect(team.value).toBe("Alpha");
      expect(screen.queryByRole("button", { name: "Clear Team" })).not.toBeInTheDocument();
      await user.click(team);
      expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
      expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
      await user.click(screen.getByRole("option", { name: "Alpha" }));
      expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
      expect(calls(mockFetch)).toEqual([TEAM]);
    });

    test("with no team at all the page says so and requests nothing", async () => {
      serve(mockFetch, undefined, () => jsonResponse(200, { ...FILTERS, teams: [] }));
      renderPage();
      expect(await screen.findByText(/there is no team yet/)).toBeInTheDocument();
      expect(calls(mockFetch)).toEqual([]);
      expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    });
  });

  describe("several people", () => {
    test("the member control picks several people: repeated accountId in the request, then stacked segments per person", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/estimate-adjustments?teamId=1");
      await screen.findAllByTestId("bar-chart");
      await openReportFilters(user);

      await user.click(screen.getByRole("combobox", { name: "Member" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann`));
      await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann&accountId=acc-bob`));

      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(1));
      const chart = screen.getByTestId("bar-chart");
      expect(chart.getAttribute("data-type")).toBe("stacked");
      expect(chart.getAttribute("data-series")).toBe("p0,p1");
      expect(chart.getAttribute("data-series-labels")).toBe("1. Ann Author|2. Bob Builder");
      // One segment per person in every bucket: Ann then Bob.
      expect(JSON.parse(chart.getAttribute("data-stacks") ?? "[]")).toEqual([[1, 0], [2, 1], [1, 1], [1, 0]]);
      expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("1. Ann Author2. Bob Builder");
      // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    });

    test("every chart number is in a table: a column per person beside the total, and the totals per person", async () => {
      serve(mockFetch);
      renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(1));

      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Change from start to done" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Range",
        "1. Ann Author",
        "2. Bob Builder",
        "Items",
      ]);
      expect(within(histogram).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))[1]).toEqual([
        "0% – +25%",
        "2",
        "1",
        "3",
      ]);

      const totals = screen.getByRole("table", { name: "Totals per person" });
      const rows = within(totals).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
      expect(rows).toEqual([
        ["1. Ann Author", "8", "37.5%", "—", "—"],
        ["2. Bob Builder", "4", "—", "—", "—"],
      ]);
      expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    });

    test("one person is the same view with a single stack, a '1.' column and their totals row", async () => {
      serve(mockFetch);
      renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(1));
      expect(screen.getByTestId("bar-chart").getAttribute("data-series")).toBe("p0");
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Change from start to done" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann Author", "Items"]);
      expect(within(screen.getByRole("table", { name: "Totals per person" })).getAllByRole("row")).toHaveLength(2);
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-ann`]);
    });

    test("a link with repeated accountId selects those people and requests them in that order", async () => {
      serve(mockFetch);
      renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(1));
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-bob&accountId=acc-ann`]);
      await openReportFilters();
      const pills = screen.getByRole("group", { name: "Report filters" });
      expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
      expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
    });

    test("a hidden distribution shows no stack, and the totals keep the bare names when no chart numbers them", async () => {
      serve(mockFetch, () =>
        jsonResponse(200, {
          ...ADJUSTMENTS_USERS,
          tasks: { ...ADJUSTMENTS_USERS.tasks, changeDistribution: { n: 3, hidden: true, histogram: [] } },
        }),
      );
      renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await screen.findAllByRole("note");
      expect(screen.queryByRole("group", { name: /stacked by person/ })).not.toBeInTheDocument();
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getAllByRole("row")[1]).toHaveTextContent(/^Ann Author/);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(1));
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=2`));
      expect(screen.getByTestId("where").textContent).not.toContain("accountId");
    });
  });
});
