import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCodeActivity from "./ReportCodeActivity";
import { jsonResponse } from "../test/http";
import {
  CODE_ACTIVITY,
  CODE_ACTIVITY_PENDING,
  CODE_ACTIVITY_UNASSIGNED,
  CODE_ACTIVITY_USERS,
  codeCommitItems,
  codeMrItems,
} from "../test/codeActivityFixtures";
import { showItems } from "../test/drill";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import type { CodeActivityReport } from "../api/reports";

// recharts renders nothing under happy-dom (no layout): the charts are probes. The weekly chart carries one button per week firing the
// `onDoubleClick` recharts would forward to that bar's rectangle.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: { id?: string }[];
    dataKey: string;
    barProps?: (series: { name: string }) => { onDoubleClick?: (entry: { payload: unknown }) => void };
  }) => (
    <div data-testid={props.dataKey === "week" ? "weekly-chart" : "bar-chart"}>
      {props.dataKey === "week" &&
        props.data.map((row, index) => (
          <button key={row.id} data-testid={`week-${index}`} onClick={() => props.barProps?.({ name: "merged" }).onDoubleClick?.({ payload: row })} />
        ))}
    </div>
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PREFIX = "/api/v1/reports/code-activity?";
const ITEMS_PREFIX = "/api/v1/reports/code-activity/items?";

function serve(mockFetch: FetchMock, report: CodeActivityReport = CODE_ACTIVITY, items: (url: string) => Response = () => jsonResponse(200, codeMrItems(40))) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS_PREFIX)) return Promise.resolve(items(url));
    if (url.startsWith(REPORT_PREFIX)) return Promise.resolve(jsonResponse(200, report));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/code-activity") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/code-activity"
        element={
          <>
            <ReportCodeActivity />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(ITEMS_PREFIX));
/** A request's path and query parameters (repeated keys kept as lists), so a test does not depend on the parameter order. */
const parse = (url: string) => {
  const params = new URL(url, "http://flow.test").searchParams;
  return Object.fromEntries([...new Set(params.keys())].map((key) => [key, params.getAll(key).length === 1 ? params.get(key) : params.getAll(key)]));
};
const membersTable = () => screen.findByRole("table", { name: "Activity by member" });

describe("ReportCodeActivity page - the drill to the commits and merge requests behind a figure", () => {
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

  test("a member's commits figure lists their commits: the member is the only pick, every key links to the GitLab commit, and focus returns on close", async () => {
    serve(mockFetch, CODE_ACTIVITY, () => jsonResponse(200, codeCommitItems()));
    const user = userEvent.setup();
    renderPage();
    const button = within(await membersTable()).getByRole("button", { name: showItems("Ann Author", "Commits") });
    expect(button).toHaveTextContent("84");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Commits · Ann Author" });
    expect(await within(drawer).findByText("84 items")).toBeInTheDocument();
    // At TEAM level a member's cell is the request with that member as the only pick - no segment, the same rows.
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", accountId: "acc-ann", view: "COMMITS" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=code%3ACOMMITS%3Bs%3Dacc-ann");
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(within(drawer).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Issue", "Summary", "Person", "Committed", "Lines changed"]);
    const link = within(drawer).getByRole("link", { name: "grp/app@3f9a1c2b4d5e (opens in GitLab)" });
    expect(link).toHaveAttribute("href", "http://gitlab-stub:8080/grp/app/-/commit/3f9a1c2b4d5e");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    // The truncation note and the unknown lines.
    expect(within(drawer).getByText("Showing the 3 largest of 84 items.")).toBeInTheDocument();
    const rows = within(drawer).getAllByRole("row").slice(1);
    expect(rows.map((row) => within(row).getAllByRole("cell").at(-1)?.textContent)).toEqual(["240", "12", "—"]);

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByTestId("where").textContent).not.toContain("drill=");
    expect(button).toHaveFocus();
  });

  test("a zero is text: only the counts that have commits or merge requests behind them are buttons", async () => {
    serve(mockFetch);
    renderPage();
    const table = await membersTable();
    const rowOf = (name: string) => within(table).getAllByRole("row").find((row) => row.textContent?.startsWith(name)) as HTMLElement;
    expect(within(rowOf("Bob Builder")).queryAllByRole("button")).toHaveLength(0);
    expect(within(rowOf("Ann Author")).getAllByRole("button").map((b) => b.textContent)).toEqual(["84", "18", "20", "3", "11", "9", "37"]);
  });

  test("an unlinked identity's row (teamId=0) has no button at all", async () => {
    serve(mockFetch, CODE_ACTIVITY_UNASSIGNED);
    renderPage("/reports/code-activity?teamId=0");
    const table = await membersTable();
    expect(within(table).queryAllByRole("button")).toHaveLength(0);
  });

  test("the merged tile lists the team's merge requests: no person, the key links to the GitLab merge request, the title is the heading", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged") }));
    const drawer = await screen.findByRole("dialog", { name: "Merge requests merged" });
    expect(await within(drawer).findByText("40 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "MRS_MERGED" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=code%3AMRS_MERGED");
    expect(within(drawer).getByRole("link", { name: "grp/app!12 (opens in GitLab)" })).toHaveAttribute("href", "http://gitlab-stub:8080/grp/app/-/merge_requests/12");
    expect(within(drawer).getByRole("link", { name: "grp/sub/api!7 (opens in GitLab)" })).toHaveAttribute(
      "href",
      "http://gitlab-stub:8080/grp/sub/api/-/merge_requests/7",
    );
    expect(within(drawer).getByRole("columnheader", { name: "Merged" })).toBeInTheDocument();
    // An unlinked author has no name, a dash in the Person column.
    const unnamed = within(drawer).getByRole("row", { name: /grp\/app!3/ });
    expect(within(unnamed).getAllByRole("cell")[2]).toHaveTextContent("—");
  });

  test("the two review-rule tiles and the waiting count of the open tile open their own views", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged without review") }));
    expect(await screen.findByRole("dialog", { name: "Merged without review" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: showItems("Merge request flow", "Merged without approval") }));
    expect(await screen.findByRole("dialog", { name: "Merged without approval" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: showItems("Open merge requests now", "Waiting for review") }));
    expect(await screen.findByRole("dialog", { name: "Merge requests waiting for review" })).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map((url) => parse(url).view)).toEqual(["MERGED_WITHOUT_REVIEW", "MERGED_WITHOUT_APPROVAL", "OPEN_MRS_WAITING"]);
  });

  test("a week's figure in the weekly table lists the merge requests merged in it", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Week starting 2026-09-21", "Merged") }));
    const drawer = await screen.findByRole("dialog", { name: "Merged in the week · Week starting 2026-09-21" });
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "WEEK", bucketStart: "2026-09-21" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=code%3AWEEK%3A2026-09-21");
    // The list is 40 merge requests behind a week that shows 28: the page says the data changed.
    expect(await within(drawer).findByText("The data changed since the chart was drawn.")).toBeInTheDocument();
    // A week with nothing merged is text.
    expect(screen.queryByRole("button", { name: showItems("Week starting 2026-09-14", "Merged") })).not.toBeInTheDocument();
  });

  test("a double-click on a weekly bar lists that week's merge requests, and a week with nothing merged opens nothing", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId("week-2"));
    expect(await screen.findByRole("dialog", { name: "Merged in the week · Week starting 2026-09-21" })).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "WEEK", bucketStart: "2026-09-21" }]);
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByTestId("week-1"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(itemsCalls(mockFetch)).toHaveLength(1);
  });

  test("a histogram bar's figure lists that bar of the distribution with the bar's index and the cycle's own wording", async () => {
    serve(mockFetch, CODE_ACTIVITY, () => jsonResponse(200, { ...codeMrItems(10), bar: { count: 10, sumMd: null, from: 0, to: 2 } }));
    const user = userEvent.setup();
    renderPage();
    const first = (await screen.findAllByRole("button", { name: /show the items of Cycle time, working days: / }))[0];
    expect(first).toHaveTextContent("10");
    await user.click(first);
    const drawer = await screen.findByRole("dialog", { name: /^Cycle time, working days · / });
    expect(await within(drawer).findByText("10 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "MR_CYCLE_WORKING_DAYS", bucketIndex: "0" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=hist%3AMR_CYCLE_WORKING_DAYS%3A0");
    expect(within(drawer).getByRole("link", { name: "grp/app!12 (opens in GitLab)" })).toBeInTheDocument();
    expect(within(drawer).getByRole("columnheader", { name: "Working days" })).toBeInTheDocument();
  });

  test("with members picked, a member's cell narrows the picks' list to them (segmentAccountId)", async () => {
    serve(mockFetch, CODE_ACTIVITY_USERS, () => jsonResponse(200, codeCommitItems()));
    const user = userEvent.setup();
    renderPage("/reports/code-activity?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const table = await membersTable();
    await user.click(within(table).getByRole("button", { name: showItems("Ann Author", "Commits") }));
    await screen.findByRole("dialog", { name: "Commits · Ann Author" });
    await waitFor(() => expect(itemsCalls(mockFetch)).toHaveLength(1));
    expect(parse(itemsCalls(mockFetch)[0])).toEqual({ teamId: "1", accountId: ["acc-ann", "acc-bob"], view: "COMMITS", segmentAccountId: "acc-ann" });
  });

  test("a deep link to a figure the report does not show - an unknown person, a week it did not draw, a view that is no tile - opens nothing and is dropped", async () => {
    for (const drill of ["code%3ACOMMITS%3Bs%3Dacc-zed", "code%3AWEEK%3A2026-01-05", "code%3ACOMMITS"]) {
      serve(mockFetch);
      const { unmount } = renderPage(`/reports/code-activity?teamId=1&drill=${drill}`);
      await membersTable();
      await waitFor(() => expect(screen.getByTestId("where").textContent).not.toContain("drill="));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(itemsCalls(mockFetch)).toEqual([]);
      unmount();
      mockFetch.mockReset();
    }
  });

  test("a deep link to a drawn figure opens it on load", async () => {
    serve(mockFetch);
    renderPage("/reports/code-activity?teamId=1&drill=code%3AMRS_MERGED");
    expect(await screen.findByRole("dialog", { name: "Merge requests merged" })).toBeInTheDocument();
    await waitFor(() => expect(itemsCalls(mockFetch)).toHaveLength(1));
  });

  test("with two different GitLab hosts a key is plain text rather than a link to the wrong GitLab", async () => {
    serve(mockFetch, CODE_ACTIVITY_PENDING);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged") }));
    const drawer = await screen.findByRole("dialog", { name: "Merge requests merged" });
    expect(await within(drawer).findByText("grp/app!12")).toBeInTheDocument();
    expect(within(drawer).queryByRole("link")).not.toBeInTheDocument();
  });

  test("a failing list says so inside the drawer", async () => {
    serve(mockFetch, CODE_ACTIVITY, () => jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged") }));
    const drawer = await screen.findByRole("dialog", { name: "Merge requests merged" });
    expect(await within(drawer).findByRole("alert")).toBeInTheDocument();
  });
});
