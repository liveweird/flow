import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReportEstimateAdjustments from "./ReportEstimateAdjustments";
import { jsonResponse } from "../test/http";
import { ADJUSTMENTS, ADJUSTMENTS_EMPTY, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string; count: number }[] }) => (
    <div data-testid="bar-chart" data-labels={props.data.map((r) => r.label).join("|")} data-counts={props.data.map((r) => r.count).join(",")} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/estimate-adjustments?";

function serve(mockFetch: FetchMock, report?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, ADJUSTMENTS));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/estimate-adjustments" element={<ReportEstimateAdjustments />} />
    </Routes>,
    { route: "/reports/estimate-adjustments" },
  );
}

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
    serve(mockFetch, () =>
      jsonResponse(200, {
        ...ADJUSTMENTS,
        meta: { ...ADJUSTMENTS.meta, level: "USER" },
        epics: ADJUSTMENTS_EMPTY.epics,
        groups: [],
      }),
    );
    renderPage();
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
});
