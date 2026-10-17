import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportComparePeople from "./ReportComparePeople";
import { COMPARE_PEOPLE, COMPARE_PEOPLE_CAPACITY, COMPARE_PEOPLE_THIN } from "../test/comparePeopleFixtures";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom: each histogram is a probe carrying the props the page decides (the layout, the series).
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { type?: string; series: { name: string; label?: string }[]; data: { label: string }[] }) => (
    <div data-testid="bar-chart" data-type={props.type} data-series={props.series.map((series) => series.label ?? series.name).join("|")} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/compare-people?";
const PEOPLE = [
  { accountId: "acc-ann", displayName: "Ann Author" },
  { accountId: "acc-bob", displayName: "Bob Builder" },
  { accountId: "acc-cy", displayName: "Cy Coder" },
];

function serve(mockFetch: FetchMock, report: unknown = COMPARE_PEOPLE, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(status, report));
    if (url.startsWith("/api/v1/jira-users?")) {
      const q = new URL(url, "http://x").searchParams.get("q")?.toLowerCase();
      const items = PEOPLE.filter((person) => q === undefined || person.displayName.toLowerCase().includes(q));
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 20, total: items.length }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().pathname + useLocation().search}</div>;
}

function renderPage(route = "/reports/compare-people") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/compare-people" element={<ReportComparePeople />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const where = () => screen.getByTestId("where").textContent;
const reportCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(REPORT));
const TWO = "/reports/compare-people?accountId=acc-ann&accountId=acc-cy";
const table = () => screen.getByRole("table", { name: "Comparison of the two people, metric by metric" });
/** The table row whose metric is `name`. */
const rowOf = (name: string) => {
  const header = within(table()).getByRole("rowheader", { name });
  return header.closest("tr") as HTMLElement;
};
const cellsOf = (name: string) => within(rowOf(name)).getAllByRole("cell").map((cell) => cell.textContent);

async function pick(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string) {
  await user.click(screen.getByRole("combobox", { name: combobox }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("ReportComparePeople page", () => {
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

  describe("the pickers", () => {
    test("a bare visit is the pickers and an empty state: person B waits for A, and nothing is requested", async () => {
      serve(mockFetch);
      renderPage();
      expect(await screen.findByText("Pick two people to compare them.")).toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "Person A" })).toBeEnabled();
      expect(screen.getByRole("combobox", { name: "Person B" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Swap A and B" })).toBeDisabled();
      expect(headingOutline()).toEqual([[2, "Compare people"]]);
      expect(reportCalls(mockFetch)).toEqual([]);
    });

    test("picking A then B writes the repeated accountId to the URL and requests the report once both are there", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await screen.findByText("Pick two people to compare them.");
      await pick(user, "Person A", "Ann Author");
      await waitFor(() => expect(where()).toBe("/reports/compare-people?accountId=acc-ann"));
      expect(reportCalls(mockFetch)).toEqual([]);
      expect(screen.getByText("Pick two people to compare them.")).toBeInTheDocument();

      // Ann is not offered as her own opponent.
      await user.click(screen.getByRole("combobox", { name: "Person B" }));
      expect(await screen.findByRole("option", { name: "Cy Coder" })).toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "Ann Author" })).not.toBeInTheDocument();
      await user.click(screen.getByRole("option", { name: "Cy Coder" }));

      await waitFor(() => expect(where()).toBe(TWO));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}accountId=acc-ann&accountId=acc-cy`]));
      expect(await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" })).toBeInTheDocument();
    });

    test("the typed text goes to the server as q, debounced, and the directory is the unit's", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await screen.findByText("Pick two people to compare them.");
      await user.type(screen.getByRole("combobox", { name: "Person A" }), "cy");
      await waitFor(() =>
        expect(mockFetch.mock.calls.map((c) => c[0] as string)).toContain("/api/v1/jira-users?page=1&pageSize=20&sort=displayName&q=cy&scope=UNIT"),
      );
      expect(await screen.findByRole("option", { name: "Cy Coder" })).toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "Ann Author" })).not.toBeInTheDocument();
    });

    test("a directory longer than one page says so, and a search with no match says that; a failed search is the field's error", async () => {
      serve(mockFetch);
      const base = mockFetch.getMockImplementation() as (url: string) => Promise<Response>;
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith("/api/v1/jira-users?")) {
          const q = new URL(url, "http://x").searchParams.get("q");
          if (q === "zzz") return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
          if (q === "boom") return Promise.resolve(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
          return Promise.resolve(jsonResponse(200, { items: PEOPLE, page: 1, pageSize: 20, total: 340 }));
        }
        return base(url);
      });
      const user = userEvent.setup();
      renderPage();
      expect(await screen.findByText("Showing 3 of 340 — type to narrow it down.")).toBeInTheDocument();
      await user.type(screen.getByRole("combobox", { name: "Person A" }), "zzz");
      expect(await screen.findByText("No matching people")).toBeInTheDocument();
      await user.clear(screen.getByRole("combobox", { name: "Person A" }));
      await user.type(screen.getByRole("combobox", { name: "Person A" }), "boom");
      expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    });

    test("swapping turns the comparison around; clearing B leaves A, and A can be cleared only while B is empty", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(screen.getByRole("combobox", { name: "Person A" })).toHaveValue("Ann Author");
      expect(screen.getByRole("combobox", { name: "Person B" })).toHaveValue("Cy Coder");
      expect(screen.queryByLabelText("Clear Person A")).not.toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Swap A and B" }));
      await waitFor(() => expect(where()).toBe("/reports/compare-people?accountId=acc-cy&accountId=acc-ann"));
      await waitFor(() => expect(reportCalls(mockFetch).at(-1)).toBe(`${REPORT}accountId=acc-cy&accountId=acc-ann`));

      await user.click(await screen.findByLabelText("Clear Person B"));
      await waitFor(() => expect(where()).toBe("/reports/compare-people?accountId=acc-cy"));
      expect(await screen.findByText("Pick two people to compare them.")).toBeInTheDocument();
      expect(screen.getByLabelText("Clear Person A")).toBeInTheDocument();
    });
  });

  describe("the URL", () => {
    test("a team, another report's keys and a third person are dropped and the URL rewritten; the dates and slices stay", async () => {
      serve(mockFetch);
      renderPage(
        "/reports/compare-people?teamId=2&accountId=acc-ann&accountId=acc-cy&accountId=acc-bob&from=2026-05-01&to=2026-06-01&bucket=MONTH&by=STATUS&domain=FLO",
      );
      await waitFor(() => expect(where()).toBe("/reports/compare-people?from=2026-05-01&to=2026-06-01&accountId=acc-ann&accountId=acc-cy&domain=FLO"));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}from=2026-05-01&to=2026-06-01&accountId=acc-ann&accountId=acc-cy&domain=FLO`]));
    });

    test.each(["lastSprints=3&teamId=1", "sprintId=11"])("a sprint-relative period (%s) is dropped for the default dates", async (period) => {
      serve(mockFetch);
      renderPage(`/reports/compare-people?${period}&accountId=acc-ann&accountId=acc-cy`);
      await waitFor(() => expect(where()).toBe(TWO));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}accountId=acc-ann&accountId=acc-cy`]));
    });

    test("the filter bar has the date presets only: no team, no member, no sprint period", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      await openReportFilters(user);
      expect(screen.queryByRole("combobox", { name: "Team" })).not.toBeInTheDocument();
      expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
      await user.click(screen.getByRole("combobox", { name: "Period" }));
      expect(await screen.findByRole("option", { name: "Last 30 days" })).toBeInTheDocument();
      expect(screen.getByRole("option", { name: "Custom range" })).toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "One sprint" })).not.toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "Last 3 sprints" })).not.toBeInTheDocument();
    });

    test("a failed report is the inline error, not a table", async () => {
      serve(mockFetch, { title: "Bad Request", status: 400 }, 400);
      renderPage(TWO);
      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(screen.queryByRole("table", { name: "Comparison of the two people, metric by metric" })).not.toBeInTheDocument();
    });
  });

  describe("the table", () => {
    test("is Metric · A · B · Difference, in sections, each cell its figure with its sample", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(
        within(table())
          .getAllByRole("columnheader")
          .map((header) => header.textContent),
      ).toEqual(["Metric", "A · Ann Author", "B · Cy Coder", "Difference (B − A)"]);
      expect(
        within(table())
          .getAllByRole("rowheader")
          .filter((header) => header.getAttribute("scope") === "rowgroup")
          .map((header) => header.textContent),
      ).toEqual(["Delivery", "Cycle time", "Estimation", "Work in progress", "Logged time", "Data quality"]);
      expect(cellsOf("Delivered (MD)")).toEqual(["31.5n = 14", "24n = 10", "−7.5"]);
      expect(cellsOf("Delivered (items)")).toEqual(["14n = 14", "10n = 10", "−4"]);
      expect(cellsOf("Cycle time, working days — median")).toEqual(["3.5n = 12", "4.9n = 7", "+1.4"]);
      expect(cellsOf("Flow efficiency — median")).toEqual(["42%n = 12", "30%n = 7", "−12 pp"]);
      expect(cellsOf("Reported time ÷ cycle — median")).toEqual(["0.8n = 11", "1.3n = 6", "+0.5"]);
      expect(cellsOf("Median change of the estimate")).toEqual(["+25%n = 6", "—n = 2, below the minimum of 5", "—"]);
      expect(cellsOf("Average WIP per working day")).toEqual(["2.4n = 60", "3.1n = 60", "+0.7"]);
    });

    test("the better side is cued with an arrow towards its column and a teal badge named in words — and only there", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      const cue = (name: string) => within(rowOf(name)).queryByRole("img");
      expect(cue("Cycle time, working days — median")).toHaveAccessibleName("A better by 1.4 days");
      expect(cue("Cycle time, elapsed days — median")).toHaveAccessibleName("B better by 0.8 days");
      expect(cue("Delivered (items)")).toHaveAccessibleName("A better by 4 items");
      expect(cue("Flow efficiency — median")).toHaveAccessibleName("A better by 12 pp");
      expect(cue("Reported time ÷ cycle — median")).toHaveAccessibleName("A closer to 1.0 by 0.1");
      expect(cue("Finished tasks with time logged")).toHaveAccessibleName("B better by 10 pp");
      // The arrow points at the better side's column (left = A, right = B); the badge is teal, never red.
      const a = cue("Cycle time, working days — median") as HTMLElement;
      const b = cue("Cycle time, elapsed days — median") as HTMLElement;
      expect(a.querySelector(".tabler-icon-arrow-left")).not.toBeNull();
      expect(a.querySelector(".tabler-icon-arrow-right")).toBeNull();
      expect(b.querySelector(".tabler-icon-arrow-right")).not.toBeNull();
      for (const badge of [a, b]) {
        expect(badge.getAttribute("data-variant")).toBe("light");
        expect(badge.style.getPropertyValue("--badge-bg")).toContain("teal");
        expect(badge.className).not.toMatch(/red/);
      }
      // …and every cue on the page is one of those: a name that starts with a side and says "better" or "closer".
      for (const image of within(table()).getAllByRole("img")) {
        expect(image.getAttribute("aria-label")).toMatch(/^[AB] (better|closer to 1\.0) by /);
      }
    });

    test("a tie, a neutral row, a withheld side and a zero gap get no cue, just the gap in plain text", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      for (const name of [
        "Cycle time, working days — p90", // 7.5 vs 7.52: within the tolerance
        "Estimate accuracy at start — within ±25%", // equal
        "Tasks re-estimated after start", // neutral
        "Median change of the estimate", // one side withheld
        "Logged (MD)", // neutral
        "Foreign work share of logged time", // neutral
        "Finished tasks that were blocked", // equal
        "Finished tasks without an estimate", // zero both
      ]) {
        expect(within(rowOf(name)).queryByRole("img"), name).toBeNull();
      }
      expect(cellsOf("Tasks re-estimated after start")).toEqual(["20%n = 10", "35%n = 8", "+15 pp"]);
      expect(cellsOf("Logged (MD)")).toEqual(["20n = 15", "18.5n = 12", "−1.5"]);
      expect(cellsOf("Estimate accuracy at start — within ±25%")).toEqual(["50%n = 10", "50%n = 8", "0 pp"]);
    });

    test("a side under the minimum sample shows its count; a statistic gets no verdict, a pure item count keeps its cue", async () => {
      serve(mockFetch, COMPARE_PEOPLE_THIN);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(within(table()).getAllByRole("img").map((image) => image.getAttribute("aria-label"))).toEqual(["A better by 11 items"]);
      expect(cellsOf("Delivered (items)")).toEqual(["14n = 14", "3n = 3", "−11"]);
      expect(cellsOf("Cycle time, working days — median")).toEqual(["3.5n = 12", "—n = 3, below the minimum of 5", "—"]);
      // The server's note about the WIP rows rides along as written.
      expect(screen.getByText("No day has been derived yet for the work in progress.")).toBeInTheDocument();
    });

    test("the capacity rows and hint are there exactly when the response carries them — never from the role", async () => {
      serve(mockFetch);
      const { unmount } = renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      for (const name of ["Available (MD)", "Logged ÷ available MD", "Delivered MD per available MD", "Delivered items per available MD"]) {
        expect(within(table()).queryByRole("rowheader", { name }), name).not.toBeInTheDocument();
      }
      expect(screen.queryByText(/Available man-days follow/)).not.toBeInTheDocument();
      unmount();

      localStorage.clear();
      localStorage.setItem("flow.auth.token", "fake-token");
      serve(mockFetch, COMPARE_PEOPLE_CAPACITY);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(cellsOf("Available (MD)")).toEqual(["30n = 90", "20n = 90", "−10"]);
      expect(within(rowOf("Logged ÷ available MD")).getByRole("img")).toHaveAccessibleName("A closer to 1.0 by 0.12");
      expect(cellsOf("Delivered MD per available MD")[2]).toBe("+0.05");
      expect(screen.getByText(/Available man-days follow the cost matrix's rule/)).toBeInTheDocument();
      expect(screen.getByText(/Counted over 90 days, up to today\./)).toBeInTheDocument();
      // The available man-days have no items behind them: plain text, no button.
      expect(within(rowOf("Available (MD)")).queryByRole("button")).not.toBeInTheDocument();
    });

    test("a figure with items behind it is a button named with the figure first; a zero share and a withheld figure are plain text", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(within(rowOf("Delivered (MD)")).getByRole("button", { name: "31.5 — A · Ann Author: show the items of Delivered (MD)" })).toBeInTheDocument();
      expect(within(rowOf("Delivered (MD)")).getByRole("button", { name: "24 — B · Cy Coder: show the items of Delivered (MD)" })).toBeInTheDocument();
      expect(within(rowOf("Finished tasks without an estimate")).queryByRole("button")).not.toBeInTheDocument();
      expect(within(rowOf("Median change of the estimate")).getAllByRole("button")).toHaveLength(1);
      expect(within(rowOf("Foreign work share of logged time")).getAllByRole("button")).toHaveLength(1);
    });
  });

  describe("the histograms and the work mix", () => {
    test("each cycle-time histogram draws the two people side by side, numbered, with a table column per person", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      const charts = await screen.findAllByTestId("bar-chart");
      expect(charts).toHaveLength(2);
      for (const chart of charts) {
        expect(chart).toHaveAttribute("data-type", "default");
        expect(chart).toHaveAttribute("data-series", "1. Ann Author|2. Cy Coder");
      }
      const working = screen.getByRole("table", { name: "Histogram, as a table — Working days" });
      expect(
        within(working)
          .getAllByRole("columnheader")
          .map((header) => header.textContent),
      ).toEqual(["Range", "1. Ann Author", "2. Cy Coder", "Items"]);
      expect(screen.getAllByText("Ann Author", { exact: false }).length).toBeGreaterThan(0);
      // Every figure of the grouped histogram is one person's: the total column is plain text, each person's count a button.
      const firstRow = within(working).getAllByRole("row")[1];
      expect(within(firstRow).getAllByRole("button")).toHaveLength(2);
    });

    test("withheld distributions are the minimum-sample notice, not an empty chart", async () => {
      serve(mockFetch, COMPARE_PEOPLE_THIN);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      expect(screen.queryAllByTestId("bar-chart")).toEqual([]);
      expect(screen.getAllByRole("note").length).toBeGreaterThanOrEqual(2);
    });

    test("the work mix is three tables of shares with no cue, and a key with no value is not a button", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      const byType = screen.getByRole("table", { name: "Work mix by activity type" });
      const story = within(byType).getByRole("rowheader", { name: "Story" }).closest("tr") as HTMLElement;
      expect(within(story).getAllByRole("cell").map((cell) => cell.textContent)).toEqual(["66.7%21 MD · 9 items", "50%12 MD · 5 items"]);
      expect(within(byType).getByRole("button", { name: "66.7% — A · Ann Author: show the items of Work mix by activity type: Story" })).toBeInTheDocument();
      const none = within(byType).getByRole("rowheader", { name: "(no activity type)" }).closest("tr") as HTMLElement;
      expect(within(none).queryByRole("button")).not.toBeInTheDocument();
      expect(within(screen.getByRole("table", { name: "Work mix by work category" })).getByRole("rowheader", { name: "(uncategorized)" })).toBeInTheDocument();
      const byDomain = screen.getByRole("table", { name: "Work mix by domain" });
      expect(within(byDomain).getByRole("rowheader", { name: "Flow" })).toBeInTheDocument();
      expect(within(byDomain).getByRole("rowheader", { name: "(no domain)" })).toBeInTheDocument();
      expect(within(byType).queryByRole("img")).not.toBeInTheDocument();
    });

    test("the page outline: the title, then one card heading per block (and the tables' own)", async () => {
      serve(mockFetch);
      renderPage(TWO);
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      await screen.findAllByTestId("bar-chart");
      const outline = headingOutline();
      expect(outline[0]).toEqual([2, "Compare people"]);
      expect(outline.filter(([level]) => level === 3).map(([, text]) => text)).toEqual(["Head to head", "Cycle time", "Work mix"]);
      expect(outline.every(([level]) => level >= 2 && level <= 5)).toBe(true);
    });
  });

  describe("remembered filters", () => {
    test("the two people and the period are remembered and come back on a bare visit, without a request for a stale pair", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      const { unmount } = renderPage("/reports/compare-people?accountId=acc-ann&accountId=acc-cy&from=2026-05-01&to=2026-06-01");
      await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
      // A deep link is taken as written and stores nothing; the user's own change does.
      expect(localStorage.getItem("flow.viewSettings.reports.compare-people.filter")).toBeNull();
      await user.click(screen.getByRole("button", { name: "Swap A and B" }));
      await waitFor(() =>
        expect(JSON.parse(localStorage.getItem("flow.viewSettings.reports.compare-people.filter") ?? "null")).toBe(
          "from=2026-05-01&to=2026-06-01&accountId=acc-cy&accountId=acc-ann",
        ),
      );
      unmount();

      mockFetch.mockClear();
      renderPage();
      await waitFor(() => expect(where()).toBe("/reports/compare-people?from=2026-05-01&to=2026-06-01&accountId=acc-cy&accountId=acc-ann"));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}from=2026-05-01&to=2026-06-01&accountId=acc-cy&accountId=acc-ann`]));
    });

    test("the shared remembered team is not applied to a page with no team", async () => {
      localStorage.setItem("flow.viewSettings.reports.teamId", "2");
      serve(mockFetch);
      renderPage();
      await screen.findByText("Pick two people to compare them.");
      expect(where()).toBe("/reports/compare-people");
    });

    test("a remembered pair is kept as written even when a person is in no team's roster, and a remembered team or sprint is dropped", async () => {
      localStorage.setItem(
        "flow.viewSettings.reports.compare-people.filter",
        JSON.stringify("lastSprints=3&teamId=2&accountId=acc-ann&accountId=acc-unknown&domain=GONE"),
      );
      serve(mockFetch);
      renderPage();
      await waitFor(() => expect(where()).toBe("/reports/compare-people?accountId=acc-ann&accountId=acc-unknown"));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}accountId=acc-ann&accountId=acc-unknown`]));
    });
  });
});
