import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportReportedTime from "./ReportReportedTime";
import { jsonResponse } from "../test/http";
import {
  FILTERS,
  REPORTED_TIME,
  REPORTED_TIME_EMPTY,
  REPORTED_TIME_HIDDEN,
  REPORTED_TIME_USER,
  REPORTED_TIME_USERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: ({ label: string; count?: number } & Record<string, unknown>)[];
    series: { name: string; label: string }[];
    type?: string;
    xAxisLabel?: string;
  }) => (
    <div
      data-testid="bar-chart"
      data-counts={props.data.map((r) => r.count).join(",")}
      data-labels={props.data.map((r) => r.label).join("|")}
      data-x-label={props.xAxisLabel}
      data-type={props.type ?? ""}
      data-series={props.series.map((s) => s.name).join(",")}
      data-series-labels={props.series.map((s) => s.label).join("|")}
      data-stacks={JSON.stringify(props.data.map((r) => props.series.map((s) => r[s.name])))}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/reported-time-ratio?";

/** The fixture for the number of `accountId`s in the request: none = the team, one = that person, several = the stacked split. */
function reportFor(url: string) {
  const accounts = new URL(url, "http://x").searchParams.getAll("accountId").length;
  if (accounts > 1) return REPORTED_TIME_USERS;
  return accounts === 1 ? REPORTED_TIME_USER : REPORTED_TIME;
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

function renderPage(route = "/reports/reported-time-ratio") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/reported-time-ratio"
        element={
          <>
            <ReportReportedTime />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/reported-time-ratio"));

describe("ReportReportedTime page", () => {
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

  test("explains that it is how much of the elapsed working time was logged — and not flow efficiency", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Reported time" })).toBeInTheDocument();
    expect(
      screen.getByText(/How much of the elapsed working time was logged: man-days logged ÷ working days of cycle time\. It is not flow efficiency/),
    ).toBeInTheDocument();
  });

  test("the ratio and flow efficiency panels, each with its own accounting — the flow one has no 'no time logged' bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { name: "Reported time ÷ cycle time" });
    const flow = screen.getByRole("heading", { name: "Flow efficiency" });
    expect(flow).toBeInTheDocument();
    expect(screen.getByText(/Time in a waiting status counts as waiting, not as active/)).toBeInTheDocument();

    await waitFor(() => expect(screen.getAllByTestId("bar-chart")).toHaveLength(2));
    const charts = screen.getAllByTestId("bar-chart");
    expect(charts.map((c) => c.getAttribute("data-counts"))).toEqual(["6,4,2,1,1", "4,6,4,2,1"]);
    expect(charts.map((c) => c.getAttribute("data-x-label"))).toEqual(["actual MD ÷ cycle working days", "active ÷ cycle time"]);
    // Flow efficiency is a share: ranges and the strip read as percentages.
    expect(charts[1].getAttribute("data-labels")).toBe("0% – 20%|20% – 40%|40% – 60%|60% – 80%|80% – 100%");
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("0.6")).toBeInTheDocument();
    expect(within(strips[1]).getByText("40%")).toBeInTheDocument();

    const lists = screen.getAllByRole("list").map((l) => within(l).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim()));
    expect(lists).toEqual([
      ["14 in the distribution", "3 No time logged", "2 Never started, so no cycle", "1 Cycle of zero working days"],
      ["17 in the distribution", "2 Never started, so no cycle", "1 Cycle of zero elapsed time"],
    ]);
    expect(screen.getByText("14 + 3 + 2 + 1 = 20")).toBeInTheDocument();
    expect(screen.getByText("17 + 2 + 1 = 20")).toBeInTheDocument();
  });

  test("reads the median first: the outlier note appears with a visible ratio distribution", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText(/Very short cycles give very large ratios\. They are real and never dropped, so read the median \(p50\) first/)).toBeInTheDocument();
  });

  test("below the minimum sample the ratio shows the notice (and no outlier note); the accounting remains", async () => {
    serve(mockFetch, () => jsonResponse(200, REPORTED_TIME_HIDDEN));
    renderPage();
    const notes = await screen.findAllByRole("note");
    expect(notes[0]).toHaveTextContent("Only 3 items in this selection — at least 5 are needed");
    expect(notes[1]).toHaveTextContent("Only 4 items in this selection");
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByText(/Very short cycles/)).not.toBeInTheDocument();
    expect(screen.getByText("3 + 4 + 1 + 1 = 9")).toBeInTheDocument();
    expect(screen.getByText("4 + 1 + 4 = 9")).toBeInTheDocument();
  });

  test("groups carry both measures; a team below the minimum shows counts and a dash", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    const rows = within(groups).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "12", "10", "0.7", "10", "40%"],
      ["Beta", "8", "4", "—", "7", "35%"],
    ]);
  });

  test("Reported time is a tab of the Estimation group and offers the shared slices", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getByRole("tab", { name: "Reported time" })).toHaveAttribute("aria-selected", "true");
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
  });

  test("a period with no finished tasks is the empty state", async () => {
    serve(mockFetch, () => jsonResponse(200, REPORTED_TIME_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
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
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/reported-time-ratio?teamId=1"]);
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
      renderPage("/reports/reported-time-ratio?teamId=1");
      await screen.findAllByTestId("bar-chart");
      await openReportFilters(user);

      await user.click(screen.getByRole("combobox", { name: "Member" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/reported-time-ratio?teamId=1&accountId=acc-ann"));
      await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
      await waitFor(() =>
        expect(calls(mockFetch)).toContain("/api/v1/reports/reported-time-ratio?teamId=1&accountId=acc-ann&accountId=acc-bob"),
      );

      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      const charts = screen.getAllByTestId("bar-chart");
      expect(charts.map((c) => c.getAttribute("data-type"))).toEqual(["stacked", "stacked"]);
      expect(charts.map((c) => c.getAttribute("data-series"))).toEqual(["p0,p1", "p0,p1"]);
      expect(charts[0].getAttribute("data-series-labels")).toBe("1. Ann Author|2. Bob Builder");
      // One segment per person in every bucket: Ann then Bob (the ratio's, then flow efficiency's).
      expect(JSON.parse(charts[0].getAttribute("data-stacks") ?? "[]")).toEqual([[4, 2], [3, 1], [1, 1], [1, 0], [0, 1]]);
      expect(JSON.parse(charts[1].getAttribute("data-stacks") ?? "[]")).toEqual([[3, 1], [3, 3], [2, 2], [2, 0], [0, 1]]);
      const legends = screen.getAllByRole("list", { name: "Chart legend" });
      expect(legends).toHaveLength(2);
      expect(legends[0]).toHaveTextContent("1. Ann Author2. Bob Builder");
      // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    });

    test("every chart number is in a table: a column per person beside the total, and the totals per person", async () => {
      serve(mockFetch);
      renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));

      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Reported time ÷ cycle time" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Range",
        "1. Ann Author",
        "2. Bob Builder",
        "Items",
      ]);
      expect(within(histogram).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))[0]).toEqual([
        "0 – 0.5",
        "4",
        "2",
        "6",
      ]);

      const totals = screen.getByRole("table", { name: "Totals per person" });
      const rows = within(totals).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
      expect(rows).toEqual([
        ["1. Ann Author", "11", "9", "0.5", "10", "35%"],
        ["2. Bob Builder", "9", "5", "0.7", "7", "45%"],
      ]);
      expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    });

    test("one person is the same view with a single stack, a '1.' column and their totals row", async () => {
      serve(mockFetch);
      renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-ann");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      const charts = screen.getAllByTestId("bar-chart");
      expect(charts.map((c) => c.getAttribute("data-series"))).toEqual(["p0", "p0"]);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Reported time ÷ cycle time" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann Author", "Items"]);
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getAllByRole("row")).toHaveLength(2);
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/reported-time-ratio?teamId=1&accountId=acc-ann"]);
    });

    test("a link with repeated accountId selects those people and requests them in that order", async () => {
      serve(mockFetch);
      renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(calls(mockFetch)).toEqual(["/api/v1/reports/reported-time-ratio?teamId=1&accountId=acc-bob&accountId=acc-ann"]);
      await openReportFilters();
      const pills = screen.getByRole("group", { name: "Report filters" });
      expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
      expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
    });

    test("a hidden ratio shows no stack; the flow efficiency still does, and the totals keep the bare names when no chart numbers them", async () => {
      serve(mockFetch, () =>
        jsonResponse(200, {
          ...REPORTED_TIME_USERS,
          ratio: { n: 3, hidden: true, histogram: [] },
          flowEfficiency: { ...REPORTED_TIME_USERS.flowEfficiency, byUser: undefined },
        }),
      );
      renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await screen.findAllByRole("note");
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getAllByRole("row")[1]).toHaveTextContent(/^Ann Author/);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/reported-time-ratio?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/reported-time-ratio?teamId=2"));
      expect(screen.getByTestId("where").textContent).not.toContain("accountId");
    });
  });
});
