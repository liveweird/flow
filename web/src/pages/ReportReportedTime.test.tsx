import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReportReportedTime from "./ReportReportedTime";
import { jsonResponse } from "../test/http";
import { FILTERS, REPORTED_TIME, REPORTED_TIME_EMPTY, REPORTED_TIME_HIDDEN } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string; count: number }[]; xAxisLabel?: string }) => (
    <div
      data-testid="bar-chart"
      data-counts={props.data.map((r) => r.count).join(",")}
      data-labels={props.data.map((r) => r.label).join("|")}
      data-x-label={props.xAxisLabel}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/reported-time-ratio?";

function serve(mockFetch: FetchMock, report?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, REPORTED_TIME));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/reported-time-ratio" element={<ReportReportedTime />} />
    </Routes>,
    { route: "/reports/reported-time-ratio" },
  );
}

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
});
