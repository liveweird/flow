import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import { chartRenders } from "../test/homeChartsMock";
import { HOME } from "../test/homeFixtures";
import { FILTERS, histogramItems, SPRINT_ITEMS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { showItems } from "../test/drill";

vi.mock("@mantine/charts", async () => (await import("../test/homeChartsMock")).homeChartsMock);

type FetchMock = ReturnType<typeof vi.fn>;

const HOME_ITEMS_PREFIX = "/api/v1/reports/home/items?";

function serve(mockFetch: FetchMock, homeItems: (url: string) => Response = () => jsonResponse(200, histogramItems(11, 0, null, 5.5))) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/home") return Promise.resolve(jsonResponse(200, HOME));
    if (url === "/api/v1/reports/filters") {
      return Promise.resolve(jsonResponse(200, { ...FILTERS, connections: [{ id: 1, name: "Stub", siteUrl: "https://jira.example" }] }));
    }
    if (url.startsWith(HOME_ITEMS_PREFIX)) return Promise.resolve(homeItems(url));
    if (url.startsWith("/api/v1/reports/sprint-consistency/items?") || url.startsWith("/api/v1/reports/throughput/items?")) {
      return Promise.resolve(jsonResponse(200, SPRINT_ITEMS));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderHome(route = "/") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <>
            <Home />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock, prefix: string) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const dialog = (name: string) => screen.findByRole("dialog", { name });
const ready = () => screen.findByRole("region", { name: "What is missing from the data" });
const section = (name: string) => screen.getByRole("region", { name });
const chartOf = async (region: string, title: string) =>
  within(await within(section(region)).findByRole("group", { name: `Chart: ${title}` })).findByTestId("bar-chart");
/** A request's path and query parameters, so a test does not depend on the parameter order. */
const parse = (url: string) => {
  const parsed = new URL(url, "http://flow.test");
  return { path: parsed.pathname, query: Object.fromEntries(parsed.searchParams) };
};

describe("Home page — drilling into the items behind a figure", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("a figure of a hidden table is a button: pressing it lists the items behind the team's bar, and focus returns to it", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const tableRegion = section("How big the work is");
    const cycle = within(tableRegion).getByRole("table", { name: "Median cycle time (working days), as a table" });
    const button = within(cycle).getByRole("button", { name: showItems("Alpha · Median cycle time (working days)", "Items") });
    expect(button).toHaveTextContent("11");
    await user.click(button);

    const drawer = await dialog("Tasks done · cycle time · Alpha");
    expect(await within(drawer).findByText("11 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([`${HOME_ITEMS_PREFIX}view=TASK_CYCLE&teamId=1`]);
    expect(screen.getByTestId("where").textContent).toBe("?drill=home%3ATASK_CYCLE%3A1");
    // The list equals the figure's sample (11): no "data changed" note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    // The columns of a cycle list: the figure is working days.
    expect(within(drawer).getByRole("columnheader", { name: "Working days" })).toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByTestId("where").textContent).toBe("");
    expect(button).toHaveFocus();
  });

  test("a figure of ZERO items is plain text — there is nothing to list", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const noEstimate = within(section("What is missing from the data")).getByRole("table", { name: "Tasks with no estimate, as a table" });
    expect(within(noEstimate).queryByRole("button", { name: showItems("Beta · Tasks with no estimate") })).not.toBeInTheDocument();
    expect(within(noEstimate).getByRole("button", { name: showItems("Alpha · Tasks with no estimate") })).toHaveTextContent("3");
  });

  test("a double-click on a team's bar lists the same items; the list is compared with the figure", async () => {
    serve(mockFetch, () => jsonResponse(200, histogramItems(9, 0, null, 2)));
    const user = userEvent.setup();
    renderHome();
    await ready();
    const chart = await chartOf("How big the work is", "Median estimate (MD)");
    await user.click(within(chart).getByTestId("dbl-value-1"));
    const drawer = await dialog("Tasks done · estimates · Beta");
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([`${HOME_ITEMS_PREFIX}view=TASK_ESTIMATE&teamId=2`]);
    expect(await within(drawer).findByText("9 items")).toBeInTheDocument();
    // Beta's median estimate stands on 8 tasks, the list says 9: the data changed since the chart was drawn.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a hidden figure still lists its items, and a bar with nothing behind it opens nothing", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const chart = await chartOf("Tasks in progress", "Tasks in progress");
    // Gamma has one task in progress (a count is never hidden); the p50 age is hidden, yet its table lists the task.
    await user.click(within(chart).getByTestId("dbl-value-2"));
    await dialog("Tasks in progress now · Gamma");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const epics = await chartOf("Epics in progress", "Epics in progress");
    mockFetch.mockClear();
    await user.click(within(epics).getByTestId("dbl-value-1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([]);
  });

  test("PRED: a double-click on a point and a table figure open the tasks or the epics within that share, compared with the items within", async () => {
    serve(mockFetch, () => jsonResponse(200, histogramItems(6, 0, null, 1.1)));
    const user = userEvent.setup();
    renderHome();
    await ready();
    const accuracy = section("How well estimates hold");
    const alpha = await within(await within(accuracy).findByRole("group", { name: /^Chart: Alpha, share/ })).findByTestId("line-chart");
    await user.click(within(alpha).getByTestId("dot-tasks-1"));
    const drawer = await dialog("Tasks within ±25% of their estimate · Alpha");
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([`${HOME_ITEMS_PREFIX}view=TASK_PRED25&teamId=1`]);
    // Alpha's 25% share: 6 items within, the list holds 6.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    const beta = within(accuracy).getByRole("table", { name: "Beta, accuracy of estimates, as a table" });
    await user.click(within(beta).getByRole("button", { name: showItems("Beta · Epics ±25%", "Items within") }));
    await dialog("Epics within ±25% of their estimate · Beta");
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX).at(-1)).toBe(`${HOME_ITEMS_PREFIX}view=EPIC_PRED25&teamId=2`);
  });

  test("the active dot of a PRED point opens the list too (whichever argument carries the point), and a point with nothing within it opens nothing", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const accuracy = section("How well estimates hold");
    const alpha = await within(await within(accuracy).findByRole("group", { name: /^Chart: Alpha, share/ })).findByTestId("line-chart");
    await user.click(within(alpha).getByTestId("active-tasks-2"));
    await dialog("Tasks within ±50% of their estimate · Alpha");
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([`${HOME_ITEMS_PREFIX}view=TASK_PRED50&teamId=1`]);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Gamma's sample is under the minimum and nothing is within any share: nothing to list.
    mockFetch.mockClear();
    const gamma = await within(await within(accuracy).findByRole("group", { name: /^Chart: Gamma, share/ })).findByTestId("line-chart");
    await user.click(within(gamma).getByTestId("dot-tasks-0"));
    await user.click(within(gamma).getByTestId("active-epics-1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([]);
  });

  test("issue keys in the list link to Jira (the reference data is asked for when the first list opens, not before)", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    expect(mockFetch.mock.calls.map((c) => c[0])).toEqual(["/api/v1/reports/home"]);
    const cycle = within(section("How big the work is")).getByRole("table", { name: "Median cycle time (working days), as a table" });
    await user.click(within(cycle).getByRole("button", { name: showItems("Alpha · Median cycle time (working days)", "Items") }));
    const drawer = await dialog("Tasks done · cycle time · Alpha");
    const link = await within(drawer).findByRole("link", { name: /^FLO-1 / });
    expect(link).toHaveAttribute("href", "https://jira.example/browse/FLO-1");
  });

  test("opening and closing a list does not redraw the charts", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    await chartOf("How big the work is", "Median estimate (MD)");
    const before = chartRenders.count;
    const cycle = within(section("How big the work is")).getByRole("table", { name: "Median cycle time (working days), as a table" });
    await user.click(within(cycle).getByRole("button", { name: showItems("Alpha · Median cycle time (working days)", "Items") }));
    const drawer = await dialog("Tasks done · cycle time · Alpha");
    await within(drawer).findByText("11 items");
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(chartRenders.count).toBe(before);
  });

  test("a week's bar lists the tasks the team delivered that week — the Throughput items for the team and the window", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const chart = await chartOf("How much is delivered", "Alpha, man-days delivered per week");
    // The first week delivered nothing: no list. The second delivered one task.
    await user.click(within(chart).getByTestId("dbl-deliveredMd-0"));
    expect(itemsCalls(mockFetch, "/api/v1/reports/throughput/items?")).toEqual([]);
    await user.click(within(chart).getByTestId("dbl-deliveredMd-1"));
    const drawer = await dialog("Delivered · Alpha · Week of 2026-07-06");
    await within(drawer).findByText("8 items · 20 MD");
    const [call] = itemsCalls(mockFetch, "/api/v1/reports/throughput/items?");
    expect(parse(call)).toEqual({
      path: "/api/v1/reports/throughput/items",
      query: { teamId: "1", from: "2026-07-02", to: "2026-09-29", bucket: "WEEK", bucketStart: "2026-07-06" },
    });
    expect(screen.getByTestId("where").textContent).toBe("?drill=week%3A1%3A2026-07-06");
    // The week delivered 1 item on the chart, the list says 8.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("the week table's figures are buttons too", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const weeks = within(section("How much is delivered")).getByRole("table", { name: "Alpha, delivery per week, as a table" });
    await user.click(within(weeks).getByRole("button", { name: showItems("Alpha · Week of 2026-07-13", "Items") }));
    await dialog("Delivered · Alpha · Week of 2026-07-13");
    expect(parse(itemsCalls(mockFetch, "/api/v1/reports/throughput/items?")[0]).query.bucketStart).toBe("2026-07-13");
  });

  test("a sprint bar lists the sprint's scope with the sprint and its team alone; the capacity bar is not a set of items", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const chart = await chartOf("Last sprint", "capacity, initial scope, final scope and delivered scope of each team's last closed sprint");
    await user.click(within(chart).getByTestId("dbl-capacityMd-0"));
    expect(itemsCalls(mockFetch, "/api/v1/reports/sprint-consistency/items?")).toEqual([]);
    await user.click(within(chart).getByTestId("dbl-deliveredMd-0"));
    const drawer = await dialog("Delivered · Alpha · Alpha 2");
    expect(await within(drawer).findByText("8 items · 20 MD")).toBeInTheDocument();
    expect(itemsCalls(mockFetch, "/api/v1/reports/sprint-consistency/items?")).toEqual([
      "/api/v1/reports/sprint-consistency/items?sprintId=12&teamId=1&bucket=DELIVERED",
    ]);
    expect(screen.getByTestId("where").textContent).toBe("?drill=sprint%3A1%3A12%3ADELIVERED");
    // Alpha delivered 7 items on the chart, the list says 8.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a sprint table figure opens that team's sprint — Initial is COMMITTED — and pins ITS team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderHome();
    await ready();
    const sprints = within(section("Last sprint")).getByRole("table", { name: "Each team's last closed sprint, as a table" });
    await user.click(within(sprints).getByRole("button", { name: showItems("Beta · Beta 1 · Committed", "Initial (MD)") }));
    await dialog("Committed · Beta · Beta 1");
    expect(itemsCalls(mockFetch, "/api/v1/reports/sprint-consistency/items?")).toEqual([
      "/api/v1/reports/sprint-consistency/items?sprintId=21&teamId=2&bucket=COMMITTED",
    ]);
  });

  test.each([
    ["home:WIP_TASKS:2", "Tasks in progress now · Beta", `${HOME_ITEMS_PREFIX}view=WIP_TASKS&teamId=2`],
    ["home:QUALITY_EPICS_ESTIMATE_GAP:1", "Epics whose estimate differs from their tasks · Alpha", `${HOME_ITEMS_PREFIX}view=QUALITY_EPICS_ESTIMATE_GAP&teamId=1`],
  ])("a link carrying drill=%s opens the drawer once the overview has loaded", async (drill, name, request) => {
    serve(mockFetch);
    renderHome(`/?drill=${drill}`);
    await dialog(name);
    expect(itemsCalls(mockFetch, HOME_ITEMS_PREFIX)).toEqual([request]);
  });

  test("deep links to a week and a sprint open too", async () => {
    serve(mockFetch);
    const week = renderHome("/?drill=week:1:2026-07-06");
    expect(await dialog("Delivered · Alpha · Week of 2026-07-06")).toBeInTheDocument();
    week.unmount();
    renderHome("/?drill=sprint:1:12:COMMITTED");
    expect(await dialog("Committed · Alpha · Alpha 2")).toBeInTheDocument();
  });

  test.each([
    ["a team the overview does not list", "home:TASK_CYCLE:99"],
    ["a sprint that is not the team's last", "sprint:1:99:FINAL"],
    ["a week outside the window", "week:1:2020-01-06"],
    ["a kind of bar Home does not draw", "bucket:2026-07-06"],
  ])("%s is dropped from the URL, never a drawer that answers a 400", async (_name, drill) => {
    serve(mockFetch);
    renderHome(`/?drill=${drill}`);
    await ready();
    await waitFor(() => expect(screen.getByTestId("where").textContent).toBe(""));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockFetch.mock.calls.every(([url]) => !(url as string).includes("/items?"))).toBe(true);
  });
});
