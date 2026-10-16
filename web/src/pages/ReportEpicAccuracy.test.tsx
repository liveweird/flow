import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportEpicAccuracy from "./ReportEpicAccuracy";
import { jsonResponse } from "../test/http";
import { EPIC_ACCURACY, EPIC_ACCURACY_EMPTY, EPIC_ACCURACY_HIDDEN, EPIC_ACCURACY_TRUNCATED, FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { count: number }[] }) => <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} />,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/epic-estimation-accuracy?";
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

function serve(mockFetch: FetchMock, report?: () => Response, filters?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, EPIC_ACCURACY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/epic-estimation-accuracy") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/epic-estimation-accuracy"
        element={
          <>
            <ReportEpicAccuracy />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));

describe("ReportEpicAccuracy page", () => {
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

  test("renders both distributions against the OWN estimate and the epic exclusions", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Epic estimation accuracy" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Against the own estimate at start" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Against the own estimate at done" })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getAllByTestId("bar-chart").map((c) => c.getAttribute("data-counts"))).toEqual(["0,2,3,1", "1,2,1,1"]),
    );
    const lists = screen.getAllByRole("list").map((list) => within(list).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim()));
    expect(lists).toEqual([
      [
        "6 in the distribution",
        "1 No time logged on the epic or its children",
        "1 Never started, so no estimate at start",
        "2 No own estimate at start (the child sum is never used)",
      ],
      ["5 in the distribution", "1 No time logged on the epic or its children", "4 No own estimate at done"],
    ]);
    expect(screen.getAllByText("Of 10 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("6 + 1 + 1 + 2 = 10")).toBeInTheDocument();
    expect(screen.getByText("5 + 1 + 4 = 10")).toBeInTheDocument();
  });

  test("the epics table lists every finished epic: key, summary, estimates, child sum, actual, ratios — a dash where there is none", async () => {
    serve(mockFetch);
    renderPage();
    const table = (await screen.findByRole("heading", { name: "Finished epics" })).closest("div[class*=Paper]") as HTMLElement;
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(10);
    // A measured epic (with a summary): own estimates 20 / 22, child sum 12, actual 24, ratios 1.2 / 1.09.
    const measured = within(table).getByRole("row", { name: /FLO-105/ });
    expect(within(measured).getAllByRole("cell").map((c) => c.textContent)).toEqual([
      "FLO-105Checkout revamp",
      "2026-09-15",
      "20",
      "22",
      "12",
      "24",
      "1.2",
      "1.09",
    ]);
    // No own estimate at start (only the child sum exists): a dash, and no ratio at start.
    const late = within(table).getByRole("row", { name: /FLO-102/ });
    expect(within(late).getAllByRole("cell").map((c) => c.textContent)).toEqual(["FLO-102", "2026-09-18", "—", "22", "12", "24", "—", "1.09"]);
    // No time logged: no ratio in either view.
    const idle = within(table).getByRole("row", { name: /FLO-100/ });
    expect(within(idle).getAllByRole("cell").slice(-3).map((c) => c.textContent)).toEqual(["0", "—", "—"]);
    expect(screen.queryByText(/Showing the newest/)).not.toBeInTheDocument();
  });

  test("a truncated list says the distributions still count every epic", async () => {
    serve(mockFetch, () => jsonResponse(200, EPIC_ACCURACY_TRUNCATED));
    renderPage();
    expect(await screen.findByText("Showing the newest 10 epics; the distributions above count all of them.")).toBeInTheDocument();
  });

  test("a stale domain view or activity type in the URL is not shown — this page has no such controls", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-estimation-accuracy?domainView=EPIC&activityType=Bug&domain=FLO");
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.queryByRole("radio", { name: "Earned in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Activity type" })).not.toBeInTheDocument();
    // The control that does exist reflects the URL.
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("Flow");
  });

  test("offers only the controls that change an epic read: team, domain and work category", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Team" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Work category" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Activity type" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
  });

  test("offers no member control and a pasted accountId is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-estimation-accuracy?teamId=1&accountId=acc-ann&accountId=acc-bob");
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    // The address bar says what the page shows: no member, and the request never carried one.
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the strip shows median, mean and items and no p90/p95 while the accuracy measures table lists the nine measures", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(strips).toHaveLength(2);
    for (const strip of strips) {
      expect(within(strip).getByText("Median (p50)")).toBeInTheDocument();
      expect(within(strip).getByText("Mean")).toBeInTheDocument();
      expect(within(strip).getByText("Items")).toBeInTheDocument();
      expect(within(strip).queryByText("p90")).not.toBeInTheDocument();
      expect(within(strip).queryByText("p95")).not.toBeInTheDocument();
    }
    expect(screen.queryByText("p90")).not.toBeInTheDocument();

    const table = screen.getByRole("table", { name: "Accuracy measures — Against the own estimate at start" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Measure", "Value"]);
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows.map((r) => r.slice(0, 2))).toEqual([
      ["PRED(10)", "25%"],
      ["PRED(25)", "50%"],
      ["PRED(50)", "75%"],
      ["MdMRE", "18%"],
      ["sMAPE", "21.5%"],
      ["IQR", "0.45"],
      ["IDR", "1.2"],
      ["MAD", "0.25"],
      ["Tail share (beyond ±2σ)", "16.7%"],
    ]);
    // The one-line meanings are not row text: each is on demand behind an info icon named after its measure.
    expect(rows.every((r) => r.length === 2)).toBe(true);
    expect(screen.queryByText(/relative to the actual/)).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.hover(within(table).getByRole("button", { name: "About PRED(10)" }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("relative to the actual");
    // The at-done view has its own table; with five epics (under six) its tail share is undefined and reads a dash.
    const done = screen.getByRole("table", { name: "Accuracy measures — Against the own estimate at done" });
    expect(within(within(done).getAllByRole("row")[9]).getAllByRole("cell")[1]).toHaveTextContent("—");
    // The table sits between the strip and the histogram.
    const histogram = screen.getAllByTestId("bar-chart")[0];
    expect(strips[0].compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(table.compareDocumentPosition(histogram) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  test("a view below the minimum sample shows the notice and no accuracy table", async () => {
    serve(mockFetch, () => jsonResponse(200, EPIC_ACCURACY_HIDDEN));
    renderPage();
    const notes = await screen.findAllByRole("note");
    expect(notes[0]).toHaveTextContent("Only 3 items in this selection");
    expect(screen.queryByRole("table", { name: /^Accuracy measures/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Percentiles" })).not.toBeInTheDocument();
    expect(screen.getAllByText("Of 9 finished in this period:")).toHaveLength(2);
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

  test("a period with no finished epics is the empty state and lists nothing", async () => {
    serve(mockFetch, () => jsonResponse(200, EPIC_ACCURACY_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Finished epics" })).not.toBeInTheDocument();
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});
