import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReportEpicAccuracy from "./ReportEpicAccuracy";
import { jsonResponse } from "../test/http";
import { EPIC_ACCURACY, EPIC_ACCURACY_EMPTY, EPIC_ACCURACY_TRUNCATED, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { count: number }[] }) => <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} />,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/epic-estimation-accuracy?";

function serve(mockFetch: FetchMock, report?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.() ?? jsonResponse(200, EPIC_ACCURACY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/epic-estimation-accuracy") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/epic-estimation-accuracy" element={<ReportEpicAccuracy />} />
    </Routes>,
    { route },
  );
}

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
    expect((await screen.findAllByTestId("bar-chart")).map((c) => c.getAttribute("data-counts"))).toEqual(["0,2,3,1", "1,3,3,1"]);
    const lists = screen.getAllByRole("list").map((list) => within(list).getAllByRole("listitem").map((i) => i.textContent?.replace(/\s+/g, " ").trim()));
    expect(lists).toEqual([
      [
        "6 in the distribution",
        "1 No time logged on the epic or its children",
        "1 Never started, so no estimate at start",
        "2 No own estimate at start (the child sum is never used)",
      ],
      ["8 in the distribution", "1 No time logged on the epic or its children", "1 No own estimate at done"],
    ]);
    expect(screen.getAllByText("Of 10 finished in this period:")).toHaveLength(2);
    expect(screen.getByText("6 + 1 + 1 + 2 = 10")).toBeInTheDocument();
    expect(screen.getByText("8 + 1 + 1 = 10")).toBeInTheDocument();
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

  test("groups are per owner team; the unowned bucket is counts-only and not a link", async () => {
    serve(mockFetch);
    renderPage();
    const groups = (await screen.findByRole("heading", { name: "By team" })).closest("div") as HTMLElement;
    const rows = within(groups).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "7", "5", "1.2", "5", "1.1"],
      ["Unassigned", "3", "1", "—", "3", "—"],
    ]);
    expect(within(groups).getAllByRole("link")).toHaveLength(1);
  });

  test("at USER level it says epics are not attributed to people — not a generic empty state", async () => {
    serve(mockFetch, () =>
      jsonResponse(200, { ...EPIC_ACCURACY_EMPTY, meta: { ...EPIC_ACCURACY_EMPTY.meta, level: "USER" } }),
    );
    renderPage();
    expect(await screen.findByRole("note")).toHaveTextContent(
      "Epics aren't attributed to individual people, so there are no epic figures for a single person.",
    );
    expect(screen.queryByText("No data in this period")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Epic estimation accuracy", level: 3 })).not.toBeInTheDocument();
  });

  test("a stale domain view or activity type in the URL is not shown — this page has no such controls", async () => {
    serve(mockFetch);
    renderPage("/reports/epic-estimation-accuracy?domainView=EPIC&activityType=Bug&domain=FLO");
    await screen.findAllByTestId("bar-chart");
    expect(screen.queryByRole("radio", { name: "Earned in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Activity type" })).not.toBeInTheDocument();
    // The control that does exist reflects the URL.
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("Flow");
  });

  test("offers only the controls that change an epic read: domain and work category", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Work category" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Activity type" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
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
