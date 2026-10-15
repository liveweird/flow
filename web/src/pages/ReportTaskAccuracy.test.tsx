import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskAccuracy from "./ReportTaskAccuracy";
import { jsonResponse } from "../test/http";
import {
  FILTERS,
  TASK_ACCURACY,
  TASK_ACCURACY_EMPTY,
  TASK_ACCURACY_HIDDEN,
  TASK_ACCURACY_TAIL,
  TASK_ACCURACY_USER,
  TASK_ACCURACY_USERS,
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
      data-x-label={props.xAxisLabel}
      data-type={props.type ?? ""}
      data-series={props.series.map((s) => s.name).join(",")}
      data-series-labels={props.series.map((s) => s.label).join("|")}
      data-stacks={JSON.stringify(props.data.map((r) => props.series.map((s) => r[s.name])))}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/task-estimation-accuracy?";
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

/** The fixture for the request: several `accountId`s = the stacked split, one = that person, none = the team read. */
function reportFor(url: string) {
  const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
  if (accounts > 1) return TASK_ACCURACY_USERS;
  return accounts === 1 ? TASK_ACCURACY_USER : TASK_ACCURACY;
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

function renderPage(route = "/reports/task-estimation-accuracy") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/task-estimation-accuracy"
        element={
          <>
            <ReportTaskAccuracy />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));

describe("ReportTaskAccuracy page", () => {
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

  test("the long tail control sits with the histograms: a pick writes the URL and the request, and the open bucket shows", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ACCURACY_TAIL));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")[0].getAttribute("data-counts")).toBe("1,3,5,2,tail 1"));
    const table = screen.getByRole("table", { name: "Histogram, as a table — Against the estimate at start" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[rows.length - 1].firstElementChild).toHaveTextContent("≥ 2.5 (long tail)");
    expect(calls(mockFetch)).toEqual([TEAM]);

    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    await user.click(screen.getByRole("option", { name: "0%" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&tailPercent=0`));
    expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1&tailPercent=0");
  });

  test("shows both views side by side — at start first — with strips, histogram tables and the counted exclusions", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Task estimation accuracy" })).toBeInTheDocument();
    const start = await screen.findByRole("heading", { name: "Against the estimate at start" });
    const done = screen.getByRole("heading", { name: "Against the estimate at done" });
    // D15: at start is the primary view — first in document order, and named so.
    expect(start.compareDocumentPosition(done) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("Primary view — the estimate the team committed to when work began.")).toBeInTheDocument();
    expect(screen.getByText("A ratio of 1 means the actual cost equals the estimate; above 1 it cost more.")).toBeInTheDocument();

    expect((await screen.findAllByTestId("bar-chart")).map((c) => c.getAttribute("data-counts"))).toEqual(["1,3,5,2,1", "0,4,6,3,1"]);
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("1.1")).toBeInTheDocument();
    expect(within(strips[1]).getByText("1.05")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Histogram, as a table — Against the estimate at done" })).toBeInTheDocument();

    // Each view carries ITS OWN accounting, under itself, and each one reconciles: n + Σ reasons = population.
    const lists = screen.getAllByRole("list").map((list) => within(list).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim()));
    expect(lists).toEqual([
      ["12 in the distribution", "3 No time logged", "2 Never started, so no estimate at start", "3 No estimate at start (including estimated late)"],
      ["14 in the distribution", "3 No time logged", "3 No estimate at done"],
    ]);
    expect(screen.getAllByText("Of 20 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("12 + 3 + 2 + 3 = 20")).toBeInTheDocument();
    expect(screen.getByText("14 + 3 + 3 = 20")).toBeInTheDocument();
    // Each accounting sits with its own distribution: the at-start list follows the at-start heading.
    const [startList, doneList] = screen.getAllByRole("list");
    expect(start.compareDocumentPosition(startList) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(startList.compareDocumentPosition(done) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(done.compareDocumentPosition(doneList) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The histogram is titled with what it measures.
    expect((await screen.findAllByTestId("bar-chart"))[0].getAttribute("data-x-label")).toBe("actual ÷ estimate");
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("groups: a team below the minimum sample shows counts and a dash, not a median; teams link in", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    const beta = within(groups).getByRole("row", { name: /Beta/ });
    // Beta: 8 finished, 4 at start (hidden → "—"), 5 at done (median 1.3).
    expect(within(beta).getAllByRole("cell").map((c) => c.textContent)).toEqual(["Beta", "8", "4", "—", "5", "1.3"]);
    const alpha = within(groups).getByRole("row", { name: /Alpha/ });
    expect(within(alpha).getAllByRole("cell").map((c) => c.textContent)).toEqual(["Alpha", "12", "8", "1.1", "9", "1"]);
    expect(within(groups).getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(screen.getByText("Groups below the minimum sample show counts only.")).toBeInTheDocument();
  });

  test("a whole report below the minimum sample shows the notice instead of the charts", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ACCURACY_HIDDEN));
    renderPage();
    const notes = await screen.findAllByRole("note");
    expect(notes[0]).toHaveTextContent("Only 3 items in this selection — at least 5 are needed");
    expect(notes[1]).toHaveTextContent("Only 4 items in this selection");
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Percentiles" })).not.toBeInTheDocument();
    // The accounting is still there.
    expect(screen.getAllByText("Of 9 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("3 + 1 + 1 + 4 = 9")).toBeInTheDocument();
    expect(screen.getByText("4 + 1 + 4 = 9")).toBeInTheDocument();
  });

  test("a period with no finished tasks is the empty state", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ACCURACY_EMPTY));
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

  test("headings nest: the card title is h3, each distribution, its accuracy measures and its accounting h5", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toContain("Task estimation accuracy");
    expect(screen.getAllByRole("heading", { level: 5 }).map((h) => h.textContent)).toEqual([
      "Against the estimate at start",
      "Accuracy measures",
      "Left out of this distribution",
      "Against the estimate at done",
      "Accuracy measures",
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
      return Promise.resolve(jsonResponse(200, TASK_ACCURACY));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=6&teamId=1`));
    expect(screen.getAllByTestId("bar-chart")[0].closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");

    release?.(jsonResponse(200, TASK_ACCURACY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });

  test("the strip shows median, mean and items and no p90/p95 while the accuracy measures table lists the nine measures", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    for (const strip of screen.getAllByRole("group", { name: "Percentiles" })) {
      expect(within(strip).getByText("Median (p50)")).toBeInTheDocument();
      expect(within(strip).getByText("Mean")).toBeInTheDocument();
      expect(within(strip).getByText("Items")).toBeInTheDocument();
      expect(within(strip).queryByText("p90")).not.toBeInTheDocument();
      expect(within(strip).queryByText("p95")).not.toBeInTheDocument();
    }
    expect(screen.queryByText("p90")).not.toBeInTheDocument();

    const table = screen.getByRole("table", { name: "Accuracy measures — Against the estimate at start" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Measure", "Value", "What it says"]);
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    // Fractions read as percentages, spreads as ratios; every row carries its one-line meaning.
    expect(rows.map((r) => r.slice(0, 2))).toEqual([
      ["PRED(10)", "25%"],
      ["PRED(25)", "50%"],
      ["PRED(50)", "75%"],
      ["MdMRE", "18%"],
      ["sMAPE", "21.5%"],
      ["IQR", "0.45"],
      ["IDR", "1.2"],
      ["MAD", "0.25"],
      ["Tail share (beyond ±2σ)", "8.3%"],
    ]);
    expect(rows.every((r) => (r[2] ?? "").length > 20)).toBe(true);
    expect(rows[0][2]).toContain("relative to the actual");
    expect(rows[3][2]).toContain("|actual − estimate| ÷ actual");
    expect(rows[8][2]).toContain("about 4.6% beyond ±2σ");
    // The at-done view has its own table; its tail share is undefined for this sample and reads a dash.
    const done = screen.getByRole("table", { name: "Accuracy measures — Against the estimate at done" });
    expect(within(within(done).getAllByRole("row")[9]).getAllByRole("cell")[1]).toHaveTextContent("—");
    // The table sits between the strip and the histogram.
    const [strip] = screen.getAllByRole("group", { name: "Percentiles" });
    const histogram = screen.getAllByTestId("bar-chart")[0];
    expect(strip.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(table.compareDocumentPosition(histogram) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  test("a hidden view shows the notice and no accuracy table", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_ACCURACY_HIDDEN));
    renderPage();
    await screen.findAllByRole("note");
    expect(screen.queryByRole("table", { name: /^Accuracy measures/ })).not.toBeInTheDocument();
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
      renderPage("/reports/task-estimation-accuracy?teamId=1");
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
      // One segment per person in every bucket: Ann then Bob (at start, then at done).
      expect(JSON.parse(charts[0].getAttribute("data-stacks") ?? "[]")).toEqual([[1, 0], [2, 1], [3, 2], [1, 1], [0, 1]]);
      expect(JSON.parse(charts[1].getAttribute("data-stacks") ?? "[]")).toEqual([[0, 0], [3, 1], [4, 2], [2, 1], [0, 1]]);
      const legends = screen.getAllByRole("list", { name: "Chart legend" });
      expect(legends).toHaveLength(2);
      expect(legends[0]).toHaveTextContent("1. Ann Author2. Bob Builder");
      // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    });

    test("the strip and the accuracy measures stay the union; every chart number is in a table with a column per person", async () => {
      serve(mockFetch);
      renderPage("/reports/task-estimation-accuracy?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));

      const [strip] = screen.getAllByRole("group", { name: "Percentiles" });
      expect(within(strip).getByText("1.1")).toBeInTheDocument();
      expect(within(strip).getByText("12")).toBeInTheDocument();
      const measures = screen.getByRole("table", { name: "Accuracy measures — Against the estimate at start" });
      expect(within(measures).getAllByRole("row")).toHaveLength(10);
      expect(within(within(measures).getAllByRole("row")[1]).getAllByRole("cell")[1]).toHaveTextContent("25%");

      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Against the estimate at start" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Range",
        "1. Ann Author",
        "2. Bob Builder",
        "Items",
      ]);
      expect(within(histogram).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))[0]).toEqual([
        "0.5 – 1",
        "1",
        "0",
        "1",
      ]);

      const totals = screen.getByRole("table", { name: "Totals per person" });
      const rows = within(totals).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
      expect(rows).toEqual([
        ["1. Ann Author", "12", "7", "1.1", "9", "1"],
        ["2. Bob Builder", "8", "5", "1.3", "5", "1.3"],
      ]);
      expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    });

    test("one person is the same view with a single stack, a '1.' column and their totals row", async () => {
      serve(mockFetch);
      renderPage("/reports/task-estimation-accuracy?teamId=1&accountId=acc-ann");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(screen.getAllByTestId("bar-chart").map((c) => c.getAttribute("data-series"))).toEqual(["p0", "p0"]);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Against the estimate at start" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann Author", "Items"]);
      expect(within(screen.getByRole("table", { name: "Totals per person" })).getAllByRole("row")).toHaveLength(2);
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-ann`]);
    });

    test("a link with repeated accountId selects those people and requests them in that order", async () => {
      serve(mockFetch);
      renderPage("/reports/task-estimation-accuracy?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-bob&accountId=acc-ann`]);
      await openReportFilters();
      const pills = screen.getByRole("group", { name: "Report filters" });
      expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
      expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
    });

    test("a hidden distribution shows no stack; the other still does, and the totals keep the bare names when no chart numbers them", async () => {
      serve(mockFetch, () =>
        jsonResponse(200, {
          ...TASK_ACCURACY_USERS,
          atStart: { n: 3, hidden: true, histogram: [] },
          accuracyAtStart: null,
          atDone: { ...TASK_ACCURACY_USERS.atDone, byUser: undefined },
        }),
      );
      renderPage("/reports/task-estimation-accuracy?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await screen.findAllByRole("note");
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getAllByRole("row")[1]).toHaveTextContent(/^Ann Author/);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/task-estimation-accuracy?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=2`));
      expect(screen.getByTestId("where").textContent).not.toContain("accountId");
    });
  });
});
