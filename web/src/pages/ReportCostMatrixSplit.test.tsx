import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCostMatrix from "./ReportCostMatrix";
import { jsonResponse } from "../test/http";
import {
  COST_MATRIX_SPRINT_SPLIT,
  COST_MATRIX_TEAM,
  COST_MATRIX_TEAM_CAPACITY,
  COST_MATRIX_TEAM_NO_WORKLOGS_WEEK,
  COST_MATRIX_UNIT,
  COST_MATRIX_WEEKS,
  FILTERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// The cost matrix's capacity column (rendered iff the response carries the block) and its Total | Week | Sprint split.
// `ReportCostMatrix.test.tsx` holds the rest of the page.

// recharts draws nothing under happy-dom and these tests are about the matrix: the breakdown donuts are a stub.
vi.mock("@mantine/charts", () => ({ DonutChart: () => null }));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/cost-matrix?";

function serve(mockFetch: FetchMock, response: unknown = COST_MATRIX_UNIT) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(200, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

/** Serves the split the URL asks for (a week split, a sprint split, or none), so a click gets its own answer. */
function serveBySplit(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      const params = new URLSearchParams(url.slice(URL_PREFIX.length));
      let response = params.has("teamId") ? COST_MATRIX_TEAM : COST_MATRIX_UNIT;
      if (params.get("split") === "WEEK") response = COST_MATRIX_WEEKS;
      if (params.get("split") === "SPRINT") response = COST_MATRIX_SPRINT_SPLIT;
      return Promise.resolve(jsonResponse(200, response));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/cost-matrix") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/cost-matrix" element={<ReportCostMatrix />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");
const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
// The matrix's caption — the page also holds the two breakdown tables ("Man-days by activity type" / "… by work category").
const MATRIX_TABLE = /^Man-days logged in the period/;
const matrix = () => screen.getByRole("table", { name: MATRIX_TABLE });
/** Every row after the (single) header row: the row header first, then its cells. */
const rowsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((r) => [...within(r).getAllByRole("rowheader"), ...within(r).getAllByRole("cell")].map((c) => c.textContent));
const href = (name: string) => screen.getByRole("link", { name }).getAttribute("href");
/** The split table's body: after its TWO header rows, each row's row header then its cells (the totals row last). */
const splitRowsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(2)
    .map((r) => [...within(r).getAllByRole("rowheader"), ...within(r).getAllByRole("cell")].map((c) => c.textContent));
/** The header cells of one header row, by their scope: the column groups in row 1, the columns in row 2. */
const headerTexts = (table: HTMLElement, row: number, scope: string) =>
  [...(table.querySelectorAll("thead tr")[row]?.querySelectorAll(`th[scope="${scope}"]`) ?? [])].map((th) => th.textContent);

describe("ReportCostMatrix page — capacity and the time split", () => {
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

  test("the capacity column: when the server sends it — header after Total, each author's figure, a dash for the no-author bucket, the totals row and the calendar-days hint", async () => {
    serve(mockFetch, COST_MATRIX_TEAM_CAPACITY);
    renderPage("/reports/cost-matrix?teamId=1");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(within(matrix()).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Author",
      "Flow",
      "(no domain)",
      "Total",
      "Capacity (MD)",
      "Foreign work (MD)",
      "Foreign share",
    ]);
    expect(rowsOf(matrix())).toEqual([
      ["Ann Author", "4", "0", "4", "20.5", "1", "25%"],
      ["Bob Builder", "2", "0", "2", "16", "0", "0%"],
      ["No known author", "0", "1", "1", "—", "0", "0%"],
      ["Total", "6", "1", "7", "36.5", "1", "14.3%"],
    ]);
    // The rule and how much of it is calendar truth, in one line under the table.
    expect(screen.getByText(/Capacity is each person's man-days available in the period, read from the team's sprint calendars/)).toHaveTextContent(
      "10 of 29 days read a saved calendar.",
    );
    // The page says who sees the column.
    expect(screen.getByText(/the team's manager and administrators also see each person's capacity/)).toBeInTheDocument();
  });

  test("the capacity count reads in the singular for one day", async () => {
    serve(mockFetch, { ...COST_MATRIX_TEAM_CAPACITY, capacity: { totalMd: 36.5, days: 1, calendarDays: 1 } });
    renderPage("/reports/cost-matrix?teamId=1");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(screen.getByText(/Capacity is each person's man-days/)).toHaveTextContent("1 of 1 day reads a saved calendar.");
  });

  test("no capacity block: no column, no hint — the column is the server's call, never inferred", async () => {
    serve(mockFetch, COST_MATRIX_TEAM);
    renderPage("/reports/cost-matrix?teamId=1");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(screen.queryByRole("columnheader", { name: "Capacity (MD)" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Capacity is each person/)).not.toBeInTheDocument();
    expect(rowsOf(matrix())[0]).toEqual(["Ann Author", "4", "0", "4", "1", "25%"]);
  });

  test("the split control: Total by default with nothing in the URL, Week refetches with split=WEEK and the URL says so, Sprint is disabled without a team and enabled with one", async () => {
    serveBySplit(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table", { name: MATRIX_TABLE });
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Total" })).toBeChecked();
    expect(search().has("split")).toBe(false);
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
    // Sprints are per team: with no team the option is off and says why.
    expect(screen.getByRole("radio", { name: "Sprint" })).toBeDisabled();
    expect(screen.getByText("Sprints are per team: pick a team.")).toBeInTheDocument();

    await user.click(screen.getByText("Week"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}split=WEEK`));
    expect(search().get("split")).toBe("WEEK");
    await waitFor(() => expect(headerTexts(matrix(), 0, "colgroup")).toEqual(["2026-09-21", "2026-09-28", "Period"]));

    // Back to Total: the key goes away rather than being written as NONE.
    await user.click(within(screen.getByRole("radiogroup", { name: "Split" })).getByText("Total"));
    await waitFor(() => expect(search().has("split")).toBe(false));
    await waitFor(() => expect(headerTexts(matrix(), 0, "colgroup")).toEqual([]));
    expect(calls(mockFetch).at(-1)).toBe(URL_PREFIX);
  });

  test("with a team the Sprint option is on, asks for split=SPRINT and carries the split into the drill", async () => {
    serveBySplit(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?teamId=1");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Sprint" })).toBeEnabled();
    expect(screen.queryByText("Sprints are per team: pick a team.")).not.toBeInTheDocument();
    await user.click(screen.getByText("Sprint"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&split=SPRINT`));
    expect(search().get("split")).toBe("SPRINT");
    expect(await screen.findByText("Outside sprints")).toBeInTheDocument();
  });

  test("a week split: a column group per week with the domains and the week's total, the period's figures last, the totals row per bucket, the heat scale over the bucket cells, and the week note", async () => {
    serve(mockFetch, COST_MATRIX_WEEKS);
    renderPage("/reports/cost-matrix?split=WEEK");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(matrix().querySelector("caption")).toHaveTextContent(/split by week: each week's domains and total, then the period's figures/);
    // Two header rows: the groups (weeks by their Monday, then the period), then the columns of each.
    const groups = [...matrix().querySelectorAll('thead th[scope="colgroup"]')];
    expect(groups.map((g) => g.textContent)).toEqual(["2026-09-21", "2026-09-28", "Period"]);
    expect(groups.map((g) => g.getAttribute("colspan"))).toEqual(["3", "3", "3"]);
    expect(matrix().querySelector("thead tr th[rowspan]")).toHaveTextContent("Author team");
    expect(headerTexts(matrix(), 1, "col")).toEqual([
      "Flow",
      "Operations",
      "Total",
      "Flow",
      "Operations",
      "Total",
      "Total",
      "Foreign work (MD)",
      "Foreign share",
    ]);
    // The period's domain cells are not repeated: the groups hold them, the period group only the totals.
    expect(splitRowsOf(matrix())).toEqual([
      ["Alpha", "6", "1", "7", "4", "1.5", "5.5", "12.5", "3.75", "30%"],
      ["Old team Deleted team", "1", "0", "1", "0", "0", "0", "1", "0", "0%"],
      ["Unassigned", "0.5", "0", "0.5", "0", "0.5", "0.5", "1", "0", "0%"],
      ["Total", "7.5", "1", "8.5", "4", "2", "6", "14.5", "3.75", "25.9%"],
    ]);
    // The scale runs against the largest bucket cell (6), not the period's 10.
    expect(screen.getByRole("group", { name: "Colour scale" })).toHaveTextContent("up to 6 MD in one cell");
    expect(within(within(matrix()).getAllByRole("row")[2]).getAllByRole("cell")[0]).toHaveAttribute("data-heat", "5");
    expect(screen.getByText("Weeks start on Monday in the configured time zone; the first and last week are cut to the period.")).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Capacity (MD)" })).not.toBeInTheDocument();
    // The split travels with a drill into a team.
    expect(href("Show Alpha")).toBe("/reports/cost-matrix?teamId=1&split=WEEK");
  });

  test("a week split of members who logged nothing: a group per week with just a Total, zero rows, dashes, no crash", async () => {
    serve(mockFetch, COST_MATRIX_TEAM_NO_WORKLOGS_WEEK);
    renderPage("/reports/cost-matrix?teamId=1&split=WEEK");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    const groups = [...matrix().querySelectorAll('thead th[scope="colgroup"]')];
    expect(groups.map((g) => g.textContent)).toEqual(["2026-09-28", "Period"]);
    expect(groups.map((g) => g.getAttribute("colspan"))).toEqual(["1", "3"]);
    expect(headerTexts(matrix(), 1, "col")).toEqual(["Total", "Total", "Foreign work (MD)", "Foreign share"]);
    expect(splitRowsOf(matrix())).toEqual([
      ["Ann Author", "0", "0", "0", "—"],
      ["Zed Member", "0", "0", "0", "—"],
      ["Total", "0", "0", "0", "—"],
    ]);
  });

  test("a long week split (13 weeks): every week has its own named group, the header rows add up to the body's columns, and none is blank", async () => {
    const weeks = Array.from({ length: 13 }, (_, i) => new Date(Date.UTC(2026, 5, 29 + 7 * i)).toISOString().slice(0, 10));
    const [first, second] = [COST_MATRIX_WEEKS.rows[0].byBucket![0], COST_MATRIX_WEEKS.rows[0].byBucket![1]];
    const response = {
      ...COST_MATRIX_WEEKS,
      split: { kind: "WEEK" as const, buckets: weeks.map((key) => ({ key, label: null, from: key, to: key, sprintId: null })) },
      rows: COST_MATRIX_WEEKS.rows.map((row) => ({ ...row, byBucket: weeks.map((_, i) => (i % 2 === 0 ? first : second)) })),
      bucketTotals: weeks.map((_, i) => COST_MATRIX_WEEKS.bucketTotals![i % 2]),
    };
    serve(mockFetch, response);
    renderPage("/reports/cost-matrix?split=WEEK");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    const groups = [...matrix().querySelectorAll('thead th[scope="colgroup"]')];
    expect(groups.map((g) => g.textContent)).toEqual([...weeks, "Period"]);
    expect(groups.every((g) => (g.textContent ?? "").trim() !== "")).toBe(true);
    // The group spans add up to the second header row's cells and to a body row's cells (the row header apart).
    const spans = groups.map((g) => Number(g.getAttribute("colspan")));
    expect(spans).toEqual([...weeks.map(() => 3), 3]);
    const columns = headerTexts(matrix(), 1, "col").length;
    expect(columns).toBe(spans.reduce((a, b) => a + b, 0));
    for (const row of within(matrix()).getAllByRole("row").slice(2)) expect(within(row).getAllByRole("cell")).toHaveLength(columns);
  });

  test("a sprint split: the team's sprints in order, Outside sprints last, capacity per sprint beside each sprint's total", async () => {
    serve(mockFetch, COST_MATRIX_SPRINT_SPLIT);
    renderPage("/reports/cost-matrix?teamId=1&split=SPRINT");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(matrix().querySelector("caption")).toHaveTextContent(/split by sprint: each sprint's domains and total, then the period's figures/);
    const groups = [...matrix().querySelectorAll('thead th[scope="colgroup"]')];
    expect(groups.map((g) => g.textContent)).toEqual(["Alpha 1", "Alpha 2", "Outside sprints", "Period"]);
    expect(groups.map((g) => g.getAttribute("colspan"))).toEqual(["4", "4", "4", "4"]);
    const perSprint = ["Flow", "(no domain)", "Total", "Capacity (MD)"];
    expect(headerTexts(matrix(), 1, "col")).toEqual([...perSprint, ...perSprint, ...perSprint, "Total", "Capacity (MD)", "Foreign work (MD)", "Foreign share"]);
    expect(splitRowsOf(matrix())).toEqual([
      ["Ann Author", "3", "0", "3", "10", "1", "0", "1", "9", "0", "0", "0", "1.5", "4", "20.5", "1", "25%"],
      ["Bob Builder", "1.5", "0", "1.5", "8", "0.5", "0", "0.5", "7", "0", "0", "0", "1", "2", "16", "0", "0%"],
      ["No known author", "0", "1", "1", "—", "0", "0", "0", "—", "0", "0", "0", "—", "1", "—", "0", "0%"],
      ["Total", "4.5", "1", "5.5", "18", "1.5", "0", "1.5", "16", "0", "0", "0", "2.5", "7", "36.5", "1", "14.3%"],
    ]);
    expect(screen.getByText(/Capacity is each person's man-days/)).toHaveTextContent("10 of 29 days read a saved calendar.");
    expect(screen.getByText(/Each sprint of the team is a group of columns; time logged on days outside every sprint is under "Outside sprints", last\./)).toBeInTheDocument();
  });

  test("a pasted split=SPRINT no team backs reads as Total, off the request and the URL; split=WEEK stays", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/cost-matrix?split=SPRINT");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    await waitFor(() => expect(search().toString()).toBe(""));
    expect(calls(mockFetch).some((url) => url.includes("split"))).toBe(false);
    unmount();

    const second = renderPage("/reports/cost-matrix?teamId=0&split=SPRINT");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    await waitFor(() => expect(search().toString()).toBe("teamId=0"));
    expect(calls(mockFetch).some((url) => url.includes("split"))).toBe(false);
    second.unmount();

    renderPage("/reports/cost-matrix?split=WEEK");
    await screen.findByRole("table", { name: MATRIX_TABLE });
    expect(search().get("split")).toBe("WEEK");
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}split=WEEK`);
  });
});
