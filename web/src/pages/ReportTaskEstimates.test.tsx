import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskEstimates from "./ReportTaskEstimates";
import { jsonResponse } from "../test/http";
import {
  FILTERS,
  TASK_ESTIMATES,
  TASK_ESTIMATES_EMPTY,
  TASK_ESTIMATES_HIDDEN,
  TASK_ESTIMATES_TAIL,
  TASK_ESTIMATES_USER,
  TASK_ESTIMATES_USERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each histogram is a probe.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: ({ label: string; count?: number; tail?: number } & Record<string, unknown>)[];
    series: { name: string; label: string }[];
    type?: string;
    xAxisLabel?: string;
  }) => (
    <div
      data-testid="bar-chart"
      data-counts={props.data.map((r) => r.count ?? `tail ${r.tail}`).join(",")}
      data-labels={props.data.map((r) => r.label).join(",")}
      data-x-label={props.xAxisLabel}
      data-type={props.type ?? ""}
      data-series={props.series.map((s) => s.name).join(",")}
      data-series-labels={props.series.map((s) => s.label).join("|")}
      data-stacks={JSON.stringify(props.data.map((r) => props.series.map((s) => r[s.name])))}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/task-estimates?";
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

/** The fixture for the request: several `accountId`s = the stacked split, one = that person, none = the team read. */
function reportFor(url: string) {
  const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
  if (accounts > 1) return TASK_ESTIMATES_USERS;
  return accounts === 1 ? TASK_ESTIMATES_USER : TASK_ESTIMATES;
}

function serve(mockFetch: FetchMock, report?: (url: string) => Response, filters?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.(url) ?? jsonResponse(200, reportFor(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/task-estimates") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/task-estimates"
        element={
          <>
            <ReportTaskEstimates />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));

describe("ReportTaskEstimates page", () => {
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

  test("shows both views side by side — at done first — with strips, histogram tables and the counted exclusions", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Task estimates" })).toBeInTheDocument();
    const done = await screen.findByRole("heading", { name: "Estimate at done" });
    const start = screen.getByRole("heading", { name: "Estimate at start" });
    // The estimate at done is the primary view: first in document order, and named so.
    expect(done.compareDocumentPosition(start) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("Primary view — the estimate as it stood when the task finished.")).toBeInTheDocument();
    expect(screen.getByText(/Each bar counts the tasks estimated above the figure of the bar before it/)).toBeInTheDocument();

    const charts = await screen.findAllByTestId("bar-chart");
    expect(charts.map((c) => c.getAttribute("data-counts"))).toEqual(["2,4,6,3,1", "1,3,5,2"]);
    // The server's fixed 0.5-MD bars are labelled by their upper bound (the response carries `bucketWidth`), the axis says what they measure.
    expect(charts.map((c) => c.getAttribute("data-labels"))).toEqual(["0.5,1,1.5,2,2.5", "0.5,1,1.5,2"]);
    expect(charts[0].getAttribute("data-x-label")).toBe("estimate (MD)");

    // The full strip (the estimates have a spread worth reading) and no accuracy measures.
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("p90")).toBeInTheDocument();
    expect(within(strips[0]).getByText("2.5")).toBeInTheDocument();
    expect(within(strips[1]).getByText("1.5")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /^Accuracy measures/ })).not.toBeInTheDocument();

    // The chart's numbers: the histogram as a table, a range cell per bar.
    const table = screen.getByRole("table", { name: "Histogram, as a table — Estimate at done" });
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([["0.5", "2"], ["1", "4"], ["1.5", "6"], ["2", "3"], ["2.5", "1"]]);

    // Each view carries ITS OWN accounting, and each one reconciles: n + Σ reasons = population (no "no time logged" bucket).
    const lists = screen.getAllByRole("list").map((list) => within(list).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim()));
    expect(lists).toEqual([
      ["16 in the distribution", "4 No estimate at done"],
      ["11 in the distribution", "3 Never started, so no estimate at start", "6 No estimate at start (including estimated late)"],
    ]);
    expect(screen.getAllByText("Of 20 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("16 + 4 = 20")).toBeInTheDocument();
    expect(screen.getByText("11 + 3 + 6 = 20")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the long tail control sits with the histograms: a pick writes the URL and the request, and the open bucket reads '≥ X'", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ESTIMATES_TAIL));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")[0].getAttribute("data-counts")).toBe("2,4,6,3,tail 1"));
    expect(screen.getAllByTestId("bar-chart")[0].getAttribute("data-labels")).toBe("0.5,1,1.5,2,≥ 2");
    const table = screen.getByRole("table", { name: "Histogram, as a table — Estimate at done" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[rows.length - 1].firstElementChild).toHaveTextContent("≥ 2 (long tail)");
    expect(calls(mockFetch)).toEqual([TEAM]);

    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    await user.click(screen.getByRole("option", { name: "0%" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&tailPercent=0`));
    expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1&tailPercent=0");
  });

  test("groups: a team below the minimum sample shows counts and a dash, not a median; teams link in", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    // Beta: 7 finished, 6 at done (median 1.5), 4 at start (hidden → "—").
    const beta = within(groups).getByRole("row", { name: /Beta/ });
    expect(within(beta).getAllByRole("cell").map((c) => c.textContent)).toEqual(["Beta", "7", "6", "1.5", "4", "—"]);
    const alpha = within(groups).getByRole("row", { name: /Alpha/ });
    expect(within(alpha).getAllByRole("cell").map((c) => c.textContent)).toEqual(["Alpha", "13", "10", "1", "7", "1.5"]);
    expect(within(groups).getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(screen.getByText("Groups below the minimum sample show counts only.")).toBeInTheDocument();
  });

  test("a whole report below the minimum sample shows the notice instead of the charts", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ESTIMATES_HIDDEN));
    renderPage();
    const notes = await screen.findAllByRole("note");
    expect(notes[0]).toHaveTextContent("Only 3 items in this selection — at least 5 are needed");
    expect(notes[1]).toHaveTextContent("Only 2 items in this selection");
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Percentiles" })).not.toBeInTheDocument();
    // The accounting is still there.
    expect(screen.getAllByText("Of 9 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("3 + 6 = 9")).toBeInTheDocument();
    expect(screen.getByText("2 + 2 + 5 = 9")).toBeInTheDocument();
  });

  test("a period with no finished tasks is the empty state", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ESTIMATES_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  test("a failure is an inline alert; the filter bar stays usable", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("headings nest: the card title is h3, each distribution and its accounting h5", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toContain("Task estimates");
    expect(screen.getAllByRole("heading", { level: 5 }).map((h) => h.textContent)).toEqual([
      "Estimate at done",
      "Left out of this distribution",
      "Estimate at start",
      "Left out of this distribution",
    ]);
  });

  test("offers the domain view, domain, activity type and work category — and no bucket", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&domainView=EPIC`));
  });

  test("changing the period keeps the previous distributions on screen, busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("lastSprints=6")) return deferred;
      return Promise.resolve(jsonResponse(200, TASK_ESTIMATES));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=6&teamId=1`));
    expect(screen.getAllByTestId("bar-chart")[0].closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");

    release?.(jsonResponse(200, TASK_ESTIMATES_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });

  describe("a team is required", () => {
    test("the team control has no 'All teams' option and no clear button", async () => {
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
      renderPage("/reports/task-estimates?teamId=1");
      await screen.findAllByTestId("bar-chart");
      await openReportFilters(user);

      await user.click(screen.getByRole("combobox", { name: "Member" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann`));
      await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann&accountId=acc-bob`));

      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      const charts = screen.getAllByTestId("bar-chart");
      expect(charts.map((c) => c.getAttribute("data-type"))).toEqual(["stacked", "stacked"]);
      expect(charts.map((c) => c.getAttribute("data-series"))).toEqual(["p0,p1", "p0,p1"]);
      expect(charts[0].getAttribute("data-series-labels")).toBe("1. Ann Author|2. Bob Builder");
      // One segment per person in every bar: Ann then Bob (at done, then at start); the bars keep their upper-bound labels.
      expect(JSON.parse(charts[0].getAttribute("data-stacks") ?? "[]")).toEqual([[1, 1], [3, 1], [4, 2], [2, 1], [0, 1]]);
      expect(JSON.parse(charts[1].getAttribute("data-stacks") ?? "[]")).toEqual([[1, 0], [2, 1], [3, 2], [1, 1]]);
      expect(charts[0].getAttribute("data-labels")).toBe("0.5,1,1.5,2,2.5");
      expect(screen.getAllByRole("list", { name: "Chart legend" })).toHaveLength(2);
    });

    test("every chart number is in a table with a column per person; the totals table replaces the by-member drill table", async () => {
      serve(mockFetch);
      renderPage("/reports/task-estimates?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));

      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Estimate at done" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann Author", "2. Bob Builder", "Items"]);
      expect(within(histogram).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))[0]).toEqual([
        "0.5",
        "1",
        "1",
        "2",
      ]);

      const totals = screen.getByRole("table", { name: "Totals per person" });
      const rows = within(totals).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
      expect(rows).toEqual([
        ["1. Ann Author", "13", "10", "1", "7", "1.5"],
        ["2. Bob Builder", "7", "6", "1.5", "4", "—"],
      ]);
      expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    });

    test("one person is the same view with a single stack and a '1.' column", async () => {
      serve(mockFetch);
      renderPage("/reports/task-estimates?teamId=1&accountId=acc-ann");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(screen.getAllByTestId("bar-chart").map((c) => c.getAttribute("data-series"))).toEqual(["p0", "p0"]);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-ann`]);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/task-estimates?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=2`));
      expect(screen.getByTestId("where").textContent).not.toContain("accountId");
    });
  });
});
