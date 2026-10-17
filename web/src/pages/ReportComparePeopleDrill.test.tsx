import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportComparePeople from "./ReportComparePeople";
import { COMPARE_PEOPLE, COMPARE_PEOPLE_CAPACITY, compareItems } from "../test/comparePeopleFixtures";
import { showItems } from "../test/drill";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom: each histogram is a probe with one "double-click" button per series, firing what recharts
// forwards to a rectangle — the plotted row (index 1) as payload.
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
const REPORT = "/api/v1/reports/compare-people?";
const ITEMS = "/api/v1/reports/compare-people/items?";
const PEOPLE = "accountId=acc-ann&accountId=acc-cy";
const TWO = `/reports/compare-people?${PEOPLE}`;

interface Serve {
  report?: unknown;
  items?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, { report = COMPARE_PEOPLE, items }: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, compareItems(14, 31.5, 2.25)));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, report));
    if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/compare-people" element={<ReportComparePeople />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const search = () => screen.getByTestId("where").textContent;
const table = () => screen.getByRole("table", { name: "Comparison of the two people, metric by metric" });
const rowOf = (name: string) => within(table()).getByRole("rowheader", { name }).closest("tr") as HTMLElement;

describe("ReportComparePeople page — drilling to the items behind a figure", () => {
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

  test("a row's figure lists that person's items: the same filter plus the row's view and the person; the server's bar line; Back restores the page and the focus", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    const button = within(rowOf("Delivered (MD)")).getByRole("button", { name: showItems("Delivered (MD)", "A · Ann Author") });
    expect(button).toHaveAccessibleName("31.5 — A · Ann Author: show the items of Delivered (MD)");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Delivered tasks · A · Ann Author" });
    expect(await within(drawer).findByText("14 items · 31.5 MD")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${PEOPLE}&view=DELIVERED&segmentAccountId=acc-ann`]);
    expect(search()).toBe(`?${PEOPLE}&drill=cell%3ADELIVERED%3Bs%3Dacc-ann`);
    const items = within(drawer).getByRole("table", { name: "Items behind the bar" });
    expect(
      within(items)
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual(["Issue", "Summary", "Assignee", "Done", "Delivered (MD)"]);
    // The table said 14 for Ann, the list says 14: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(search()).toBe(`?${PEOPLE}`);
    expect(button).toHaveFocus();
    // The drill is no filter: the report was requested once.
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}${PEOPLE}`]);
  });

  test("B's figure lists B's items, and the drawer's person select moves between the two — with no 'Everyone'", async () => {
    serve(mockFetch, { items: (url) => jsonResponse(200, compareItems(url.includes("segmentAccountId=acc-cy") ? 10 : 14, 24)) });
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    await user.click(within(rowOf("Delivered (items)")).getByRole("button", { name: showItems("Delivered (items)", "B · Cy Coder") }));
    const drawer = await screen.findByRole("dialog", { name: "Delivered tasks · B · Cy Coder" });
    expect(await within(drawer).findByText("10 items · 24 MD")).toBeInTheDocument();
    const select = within(drawer).getByRole("combobox", { name: "Show the items of" });
    expect(select).toHaveValue("2. Cy Coder");
    await user.click(select);
    expect(screen.queryByRole("option", { name: "Everyone" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "1. Ann Author" }));
    expect(await within(drawer).findByText("14 items · 24 MD")).toBeInTheDocument();
    expect(search()).toBe(`?${PEOPLE}&drill=cell%3ADELIVERED%3Bs%3Dacc-ann`);
  });

  test("each view reads in its own unit and date: a worklog list, the tasks held in progress, the re-estimated tasks", async () => {
    serve(mockFetch, { report: COMPARE_PEOPLE_CAPACITY, items: () => jsonResponse(200, compareItems(3, null, 4)) });
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    const headers = (drawer: HTMLElement) =>
      within(within(drawer).getByRole("table", { name: "Items behind the bar" }))
        .getAllByRole("columnheader")
        .map((header) => header.textContent);
    const open = async (row: string, column: string, title: string) => {
      await user.click(within(rowOf(row)).getByRole("button", { name: showItems(row, column) }));
      const drawer = await screen.findByRole("dialog", { name: title });
      await within(drawer).findByRole("table", { name: "Items behind the bar" });
      return drawer;
    };

    let drawer = await open("Logged (MD)", "A · Ann Author", "Logged time · A · Ann Author");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Authors", "Logged on", "Logged (MD)"]);
    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    drawer = await open("Average WIP per working day", "A · Ann Author", "Tasks in progress · A · Ann Author");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Assignee", "Days in progress"]);
    // The average's sample is working days, not the items listed: no comparison, no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: "Back to the chart" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    drawer = await open("Tasks re-estimated after start", "B · Cy Coder", "Tasks re-estimated after start · B · Cy Coder");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Assignee", "Started", "Estimate changes"]);
    expect(calls(mockFetch, ITEMS).at(-1)).toBe(`${ITEMS}${PEOPLE}&view=ADJUSTED&segmentAccountId=acc-cy`);
  });

  test("a list that disagrees with the table's count says the data changed", async () => {
    serve(mockFetch, { items: () => jsonResponse(200, compareItems(13, 30)) });
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    await user.click(within(rowOf("Delivered (MD)")).getByRole("button", { name: showItems("Delivered (MD)", "A · Ann Author") }));
    const drawer = await screen.findByRole("dialog", { name: "Delivered tasks · A · Ann Author" });
    expect(await within(drawer).findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
  });

  test("a histogram bar double-click lists that person's share of the range; the open tail drills by its index", async () => {
    serve(mockFetch, { items: () => jsonResponse(200, compareItems(4, null, 3.5)) });
    const user = userEvent.setup();
    renderPage(TWO);
    const group = await screen.findByRole("group", { name: "Histogram: number of items per range, one bar per person — Working days" });
    // Ann's bar of the second range holds 4 (the union layout's byUser), Cy's 2.
    await user.click(within(group).getByTestId("dbl-p0"));
    const drawer = await screen.findByRole("dialog", { name: "2 – 4 · Working days" });
    expect(await within(drawer).findByText("4 items")).toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${PEOPLE}&view=CYCLE_WORKING_DAYS&bucketIndex=1&segmentAccountId=acc-ann`]);
    expect(search()).toBe(`?${PEOPLE}&drill=hist%3ACYCLE_WORKING_DAYS%3A1%3Bs%3Dacc-ann`);
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(within(drawer).getByRole("combobox", { name: "Show the items of" })).toHaveValue("1. Ann Author");
    await user.click(within(drawer).getByRole("combobox", { name: "Show the items of" }));
    expect(screen.queryByRole("option", { name: "Everyone" })).not.toBeInTheDocument();
  });

  test("a person's count in a histogram table opens that person's bar; the total count is plain text", async () => {
    serve(mockFetch, { items: () => jsonResponse(200, compareItems(2, null, 3.5)) });
    const user = userEvent.setup();
    renderPage(TWO);
    const histogram = await screen.findByRole("table", { name: "Histogram, as a table — Elapsed days" });
    const second = within(histogram).getAllByRole("row")[2];
    expect(within(second).getAllByRole("button")).toHaveLength(2);
    await user.click(within(second).getByRole("button", { name: showItems("Elapsed days: 3 – 6 · 2. Cy Coder", "2. Cy Coder") }));
    await screen.findByRole("dialog", { name: "3 – 6 · Elapsed days" });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${PEOPLE}&view=CYCLE_ELAPSED_DAYS&bucketIndex=1&segmentAccountId=acc-cy`]);
  });

  test("a work-mix share lists that person's tasks of the slice: the page's filter plus the key as the slice", async () => {
    serve(mockFetch, { items: () => jsonResponse(200, compareItems(9, 21, 2)) });
    const user = userEvent.setup();
    renderPage(TWO);
    const byType = await screen.findByRole("table", { name: "Work mix by activity type" });
    await user.click(within(byType).getByRole("button", { name: showItems("Work mix by activity type: Story", "A · Ann Author") }));
    const drawer = await screen.findByRole("dialog", { name: "Work mix by activity type: Story · A · Ann Author" });
    expect(await within(drawer).findByText("9 items · 21 MD")).toBeInTheDocument();
    // The key narrows through the slice param (sorted into the canonical order), the view is delivered tasks.
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${PEOPLE}&activityType=Story&view=DELIVERED&segmentAccountId=acc-ann`]);
    expect(search()).toBe(`?${PEOPLE}&drill=mix%3AactivityType%3AStory%3Bs%3Dacc-ann`);
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
  });

  test("the uncategorized key is sent as the report filters' own UNCATEGORIZED value", async () => {
    serve(mockFetch, { items: () => jsonResponse(200, compareItems(10, 24, 2)) });
    const user = userEvent.setup();
    renderPage(TWO);
    const byCategory = await screen.findByRole("table", { name: "Work mix by work category" });
    await user.click(within(byCategory).getByRole("button", { name: showItems("Work mix by work category: (uncategorized)", "B · Cy Coder") }));
    await screen.findByRole("dialog", { name: "Work mix by work category: (uncategorized) · B · Cy Coder" });
    expect(calls(mockFetch, ITEMS)).toEqual([`${ITEMS}${PEOPLE}&workCategory=UNCATEGORIZED&view=DELIVERED&segmentAccountId=acc-cy`]);
  });

  test("a link carrying drill= opens the cell once the report is there; the report request never carries it", async () => {
    serve(mockFetch);
    renderPage(`${TWO}&drill=cell:DELIVERED;s=acc-cy`);
    const drawer = await screen.findByRole("dialog", { name: "Delivered tasks · B · Cy Coder" });
    expect(await within(drawer).findByText("14 items · 31.5 MD")).toBeInTheDocument();
    expect(calls(mockFetch, REPORT)).toEqual([`${REPORT}${PEOPLE}`]);
  });

  test.each([
    ["a view the report has no row for (the capacity rows are an editor's, this is another)", "cell:NO_SUCH_VIEW;s=acc-ann"],
    ["a person outside the two picks", "cell:DELIVERED;s=acc-bob"],
    ["a work-mix key the report does not list", "mix:activityType:Nope;s=acc-ann"],
    ["a histogram bar past the last range", "hist:CYCLE_WORKING_DAYS:99;s=acc-ann"],
    ["a histogram bar with no person (every compare list is one person's)", "hist:CYCLE_WORKING_DAYS:1"],
    ["a histogram bar of a person outside the two picks", "hist:CYCLE_WORKING_DAYS:1;s=acc-bob"],
    ["a view of another report", "hist:WORKING_DAYS:1;s=acc-ann"],
  ])("drill= %s opens nothing, fetches no items and is dropped from the URL", async (_name, drill) => {
    serve(mockFetch);
    renderPage(`${TWO}&drill=${drill}`);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    await waitFor(() => expect(search()).toBe(`?${PEOPLE}`));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a drill= that spells no target (a cell with no person) is ignored: nothing opens, nothing is fetched", async () => {
    serve(mockFetch);
    renderPage(`${TWO}&drill=cell:DELIVERED`);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS)).toEqual([]);
  });

  test("a figure while the rows are kept data of the previous pair is plain text, not a button", async () => {
    let release: (response: Response) => void = () => {};
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    expect(within(rowOf("Delivered (MD)")).getAllByRole("button")).toHaveLength(2);
    // Swapping refetches; the answer is held back, so the old rows stay on screen as kept data.
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith(REPORT)) return new Promise<Response>((resolve) => (release = resolve));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    await user.click(screen.getByRole("button", { name: "Swap A and B" }));
    await waitFor(() => expect(within(rowOf("Delivered (MD)")).queryAllByRole("button")).toHaveLength(0));
    expect(within(rowOf("Delivered (MD)")).getAllByRole("cell")[0].textContent).toBe("31.5n = 14");
    release(jsonResponse(200, COMPARE_PEOPLE));
    await waitFor(() => expect(within(rowOf("Delivered (MD)")).getAllByRole("button")).toHaveLength(2));
  });
});
