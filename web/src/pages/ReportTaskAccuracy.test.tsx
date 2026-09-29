import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportTaskAccuracy from "./ReportTaskAccuracy";
import { jsonResponse } from "../test/http";
import { FILTERS, TASK_ACCURACY, TASK_ACCURACY_EMPTY, TASK_ACCURACY_HIDDEN } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each histogram is a probe.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string; count: number }[]; xAxisLabel?: string }) => (
    <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} data-x-label={props.xAxisLabel} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/task-estimation-accuracy?";

function serve(mockFetch: FetchMock, report?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, TASK_ACCURACY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/task-estimation-accuracy") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/task-estimation-accuracy" element={<ReportTaskAccuracy />} />
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
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
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
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("headings nest: the card title is h3, each distribution and its accounting h5", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toContain("Task estimation accuracy");
    expect(screen.getAllByRole("heading", { level: 5 }).map((h) => h.textContent)).toEqual([
      "Against the estimate at start",
      "Left out of this distribution",
      "Against the estimate at done",
      "Left out of this distribution",
    ]);
  });

  test("offers the domain view, domain, activity type and work category — and no bucket", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}domainView=EPIC`));
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
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=6`));
    expect(screen.getAllByTestId("bar-chart")[0].closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");

    release?.(jsonResponse(200, TASK_ACCURACY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });
});
