import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportEstimateAdjustments from "./ReportEstimateAdjustments";
import { jsonResponse } from "../test/http";
import { ADJUSTMENTS, ADJUSTMENTS_USERS, FILTERS, histogramItems } from "../test/reportFixtures";
import { showItems } from "../test/drill";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom: each histogram is a probe with one "double-click" button per series, firing what
// recharts forwards to a rectangle — the plotted row (row 1) as payload.
vi.mock("@mantine/charts", () => {
  type BarProps = (series: { name: string }) => {
    onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void;
  };
  return {
    BarChart: (props: { data: { label: string }[]; series: { name: string }[]; barProps?: BarProps }) => (
      <div>
        <div data-testid="bar-chart" />
        {typeof props.barProps === "function" &&
          props.series.map((series) => (
            <button
              key={series.name}
              data-testid={`dbl-${series.name}`}
              onClick={() => props.barProps?.(series).onDoubleClick?.({ payload: props.data[1] }, 1, {})}
            />
          ))}
      </div>
    ),
  };
});

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/estimate-adjustments?";
const ITEMS = "/api/v1/reports/estimate-adjustments/items?";
const TASKS_NAME = "Tasks — Change from start to done";
const EPICS_NAME = "Epics — Change from start to done";
const TASKS_GROUP = `Histogram: number of items per range — ${TASKS_NAME}`;
const TASKS_TABLE = `Histogram, as a table — ${TASKS_NAME}`;

/** Epics that DID change: the tasks' figures stand in, so both blocks draw a histogram. */
const WITH_EPICS = { ...ADJUSTMENTS, epics: ADJUSTMENTS.tasks };
/** Every task kept its estimate: the tasks block shows the "no estimate changed" notice instead of a histogram. */
const NONE_CHANGED = {
  ...ADJUSTMENTS,
  tasks: {
    ...ADJUSTMENTS.tasks,
    changeDistribution: { n: 0, hidden: true, histogram: [], byUser: [] },
    changeExcluded: { ...ADJUSTMENTS.tasks.changeExcluded, unchanged: 4 },
  },
};

function serve(mockFetch: FetchMock, report: (url: string) => unknown = () => ADJUSTMENTS, items?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, histogramItems(4, 0, 0.25, 0.1)));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, report(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/estimate-adjustments"
        element={
          <>
            <ReportEstimateAdjustments />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const search = () => screen.getByTestId("where").textContent;

describe("ReportEstimateAdjustments page — drilling into a change-histogram bar", () => {
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

  test("a range's count lists the tasks whose estimate moved by that much, the figure a signed change; Back restores the page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/estimate-adjustments?teamId=1");
    const table = await screen.findByRole("table", { name: TASKS_TABLE });
    const button = within(table).getByRole("button", { name: showItems(`${TASKS_NAME}: 0% – +25%`) });
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Tasks — Change from start to done · 0% – +25%" });
    expect(await within(drawer).findByText("4 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=TASKS&bucketIndex=1`]);
    expect(search()).toBe("?teamId=1&drill=hist%3ATASKS%3A1");
    const items = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((h) => h.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Change in estimate"]);
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1Ann Author2026-09-09+10%");
    // The bar and the list agree (4 charted items in that range): no "data changed" note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe("?teamId=1");
    expect(button).toHaveFocus();
  });

  test("a double-click on the tasks histogram, and on the epics one, each asks for ITS view", async () => {
    serve(mockFetch, () => WITH_EPICS, () => jsonResponse(200, histogramItems(4, 0, 0.25, 0.1, "EPIC")));
    const user = userEvent.setup();
    renderPage("/reports/estimate-adjustments?teamId=1");
    // The two blocks draw the same visible title, but each histogram is named after its block: no two share a name.
    const tasksGroup = await screen.findByRole("group", { name: TASKS_GROUP });
    const epicsGroup = screen.getByRole("group", { name: `Histogram: number of items per range — ${EPICS_NAME}` });
    expect(screen.getByRole("table", { name: `Histogram, as a table — ${EPICS_NAME}` })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: showItems(`${EPICS_NAME}: 0% – +25%`) })).toBeInTheDocument();
    await user.click(within(tasksGroup).getByTestId("dbl-count"));
    await screen.findByRole("dialog", { name: "Tasks — Change from start to done · 0% – +25%" });
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(within(epicsGroup).getByTestId("dbl-count"));
    const drawer = await screen.findByRole("dialog", { name: "Epics — Change from start to done · 0% – +25%" });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}teamId=1&view=TASKS&bucketIndex=1`, `${ITEMS}teamId=1&view=EPICS&bucketIndex=1`]);
    // An epic has no assignee: a dash, not "Unassigned".
    const items = await within(drawer).findByRole("table", { name: "Items behind the bar" });
    expect(within(items).getAllByRole("row")[1].textContent).toBe("FLO-1Item 1—2026-09-09+10%");
  });

  test("with people picked a segment double-click carries the person and the list is checked against their share of the bar", async () => {
    serve(mockFetch, () => ADJUSTMENTS_USERS, () => jsonResponse(200, histogramItems(2, 0, 0.25, 0.1)));
    const user = userEvent.setup();
    renderPage("/reports/estimate-adjustments?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const group = await screen.findByRole("group", {
      name: `Histogram: number of items per range, stacked by person — ${TASKS_NAME}`,
    });
    await user.click(within(group).getByTestId("dbl-p1"));
    const drawer = await screen.findByRole("dialog", { name: "Tasks — Change from start to done · 0% – +25%" });
    expect(calls(mockFetch, ITEMS)).toEqual([
      `${ITEMS}teamId=1&accountId=acc-ann&accountId=acc-bob&view=TASKS&bucketIndex=1&segmentAccountId=acc-bob`,
    ]);
    expect(search()).toContain("drill=hist%3ATASKS%3A1%3Bs%3Dacc-bob");
    expect(await within(drawer).findByText("2 items")).toBeInTheDocument();
    // Bob's share of this range is 1 in the chart; the list says 2, so the data changed.
    expect(within(drawer).getByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("2. Bob Builder");
    // The epics block is not drawn per person at USER level: no epics histogram, no epics drill.
    expect(screen.getAllByRole("group", { name: /^Histogram: number of items per range/ })).toHaveLength(1);
  });

  test("when no estimate changed the notice stands alone: no drill figures, no hint, and a drill= link opens nothing", async () => {
    serve(mockFetch, () => NONE_CHANGED);
    renderPage("/reports/estimate-adjustments?teamId=1&drill=hist:TASKS:1");
    expect(await screen.findByText(/^No estimate changed: all 4 measurable items/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /show the items of/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Double-click a bar/)).not.toBeInTheDocument();
    // The hidden epics histogram has no bar either.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a drill= naming a hidden histogram or a view this page does not draw opens nothing and is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/estimate-adjustments?teamId=1&drill=hist:EPICS:0");
    await screen.findByRole("group", { name: TASKS_GROUP });
    await waitFor(() => expect(search()).toBe("?teamId=1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a link carrying drill= for the tasks opens the bar once the report is there", async () => {
    serve(mockFetch);
    renderPage("/reports/estimate-adjustments?teamId=1&drill=hist:TASKS:0");
    await screen.findByRole("dialog", { name: "Tasks — Change from start to done · −25% – 0%" });
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}teamId=1`]);
  });
});
