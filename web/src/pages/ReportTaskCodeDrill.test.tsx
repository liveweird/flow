import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskCode from "./ReportTaskCode";
import { jsonResponse } from "../test/http";
import { showItems } from "../test/drill";
import { FILTERS } from "../test/reportFixtures";
import { TASK_CODE, TASK_CODE_NO_MERGES, taskCodeItems } from "../test/taskCodeFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import type { TaskCodeReport } from "../api/reports";

// recharts renders nothing under happy-dom (no layout): the histograms are probes.
vi.mock("@mantine/charts", () => ({
  BarChart: () => <div data-testid="bar-chart" />,
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PREFIX = "/api/v1/reports/task-code?";
const ITEMS_PREFIX = "/api/v1/reports/task-code/items?";

function serve(mockFetch: FetchMock, report: TaskCodeReport = TASK_CODE, items: (url: string) => Response = () => jsonResponse(200, taskCodeItems(24))) {
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

function renderPage(route = "/reports/task-code") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/task-code"
        element={
          <>
            <ReportTaskCode />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const itemsCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(ITEMS_PREFIX));
/** A request's path and query parameters, so a test does not depend on the parameter order. */
const parse = (url: string) => Object.fromEntries(new URL(url, "http://flow.test").searchParams.entries());
const TASKS_CARD = "Done tasks and their merge requests";

describe("ReportTaskCode page - the drill to the tasks behind a figure", () => {
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

  test("the with-a-merge-request count lists the tasks, each Jira key with the GitLab merge requests the count held for it, and focus returns on close", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const button = await screen.findByRole("button", { name: showItems(TASKS_CARD, "With a merged merge request") });
    expect(button).toHaveTextContent("24");
    await user.click(button);

    const drawer = await screen.findByRole("dialog", { name: "Tasks with a merged merge request" });
    expect(await within(drawer).findByText("24 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "TASKS_WITH_MR" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=taskcode%3ATASKS_WITH_MR");
    // The count equals the list: no "data changed" note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(within(drawer).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Issue", "Summary", "Assignee", "Done", "Working days"]);
    // A task: its Jira key (plain text here, the fixture has no site URL), then its merge requests as GitLab links.
    const task = within(drawer).getByRole("row", { name: /FLO-12/ });
    expect(within(task).getByText("FLO-12")).toBeInTheDocument();
    const first = within(task).getByRole("link", { name: "grp/app!12 (opens in GitLab)" });
    expect(first).toHaveAttribute("href", "http://gitlab-stub:8080/grp/app/-/merge_requests/12");
    expect(first).toHaveAttribute("target", "_blank");
    expect(first).toHaveAttribute("rel", "noopener noreferrer");
    expect(within(task).getByRole("link", { name: "grp/sub/api!7 (opens in GitLab)" })).toHaveAttribute(
      "href",
      "http://gitlab-stub:8080/grp/sub/api/-/merge_requests/7",
    );
    // Tab order is the reading order: the task's links, in the row, in key order.
    expect(within(task).getAllByRole("link").map((link) => link.textContent)).toEqual(["grp/app!12 (opens in GitLab)", "grp/sub/api!7 (opens in GitLab)"]);
    // A task nothing names, and a missing summary and assignee, are dashes and no links.
    const bare = within(drawer).getByRole("row", { name: /FLO-31/ });
    expect(within(bare).queryAllByRole("link")).toHaveLength(0);
    expect(within(drawer).getByText("Showing the 3 largest of 24 items.")).toBeInTheDocument();

    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByTestId("where").textContent).not.toContain("drill=");
    expect(button).toHaveFocus();
  });

  test("the without count lists the tasks with no merged merge request: no figure column, the unmerged merge requests as links", async () => {
    serve(mockFetch, TASK_CODE, () => jsonResponse(200, taskCodeItems(16)));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems(TASKS_CARD, "Without a merged merge request") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks without a merged merge request" });
    expect(await within(drawer).findByText("16 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "TASKS_WITHOUT_MR" }]);
    expect(within(drawer).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Issue", "Summary", "Assignee", "Done"]);
    expect(within(drawer).getByRole("link", { name: "grp/app!15 (opens in GitLab)" })).toBeInTheDocument();
  });

  test("the Done total opens nothing: only the counts that have tasks behind them are buttons, and a zero is text", async () => {
    serve(mockFetch);
    const { unmount } = renderPage();
    await screen.findByRole("heading", { level: 3, name: TASKS_CARD });
    expect(within(screen.getByRole("group", { name: "Done tasks" })).queryAllByRole("button")).toHaveLength(0);
    expect(screen.getAllByRole("button", { name: /show the items of/ }).map((b) => b.textContent)).toContain("24");
    unmount();

    serve(mockFetch, TASK_CODE_NO_MERGES);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: TASKS_CARD });
    expect(screen.queryByRole("button", { name: showItems(TASKS_CARD, "With a merged merge request") })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: showItems("Code merged after the task was done", "Merged after done") })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: showItems(TASKS_CARD, "Without a merged merge request") })).toHaveTextContent("12");
  });

  test("the merged-after-done count lists its tasks with the lag in working days", async () => {
    serve(mockFetch, TASK_CODE, () => jsonResponse(200, taskCodeItems(6)));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems("Code merged after the task was done", "Merged after done") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks merged after done" });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "MERGED_AFTER_DONE" }]);
    expect(within(drawer).getByRole("columnheader", { name: "Working days" })).toBeInTheDocument();
  });

  test("a histogram bar's figure lists that bar with its index, in the distribution's own wording", async () => {
    serve(mockFetch, TASK_CODE, () => jsonResponse(200, { ...taskCodeItems(6), bar: { count: 6, sumMd: null, from: 0, to: 2 } }));
    const user = userEvent.setup();
    renderPage();
    const first = (await screen.findAllByRole("button", { name: /show the items of Task start to last merge, working days: / }))[0];
    expect(first).toHaveTextContent("6");
    await user.click(first);
    const drawer = await screen.findByRole("dialog", { name: /^Code lead time · / });
    expect(await within(drawer).findByText("6 items")).toBeInTheDocument();
    expect(itemsCalls(mockFetch).map(parse)).toEqual([{ teamId: "1", view: "CODE_LEAD_TIME", bucketIndex: "0" }]);
    expect(screen.getByTestId("where").textContent).toContain("drill=hist%3ACODE_LEAD_TIME%3A0");
    expect(within(drawer).getByRole("link", { name: "grp/app!12 (opens in GitLab)" })).toBeInTheDocument();
    // The lag histogram's bar is the other view.
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const lag = (await screen.findAllByRole("button", { name: /show the items of Done to last merge, working days: / }))[0];
    await user.click(lag);
    await screen.findByRole("dialog", { name: /^Done to last merge, working days · / });
    await waitFor(() => expect(itemsCalls(mockFetch).map((url) => parse(url).view)).toEqual(["CODE_LEAD_TIME", "MERGED_AFTER_DONE_LAG"]));
  });

  test("a deep link to a drawn figure opens it on load; one the report does not draw opens nothing and is dropped", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/task-code?teamId=1&drill=taskcode%3AMERGED_AFTER_DONE");
    expect(await screen.findByRole("dialog", { name: "Tasks merged after done" })).toBeInTheDocument();
    await waitFor(() => expect(itemsCalls(mockFetch)).toHaveLength(1));
    unmount();
    mockFetch.mockReset();

    // A bar past the last bucket is stale: it opens nothing and is dropped from the URL. A link this page cannot read (a person's segment,
    // a view that is no count) is no target at all: nothing opens and no request is made.
    for (const [drill, dropped] of [
      ["hist%3ACODE_LEAD_TIME%3A9", true],
      ["taskcode%3ATASKS_WITH_MR%3Bs%3Dacc-ann", false],
      ["taskcode%3ACODE_LEAD_TIME", false],
    ] as const) {
      serve(mockFetch);
      const again = renderPage(`/reports/task-code?teamId=1&drill=${drill}`);
      await screen.findByRole("heading", { level: 3, name: TASKS_CARD });
      if (dropped) await waitFor(() => expect(screen.getByTestId("where").textContent).not.toContain("drill="));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(itemsCalls(mockFetch)).toEqual([]);
      again.unmount();
      mockFetch.mockReset();
    }
  });

  test("with two different GitLab hosts a merge request is plain text rather than a link to the wrong GitLab", async () => {
    const two: TaskCodeReport = {
      ...TASK_CODE,
      sources: [...TASK_CODE.sources, { ...TASK_CODE.sources[0], connectionId: 8, name: "Other GitLab", baseUrl: "http://other-gitlab:8080" }],
    };
    serve(mockFetch, two);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems(TASKS_CARD, "With a merged merge request") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks with a merged merge request" });
    expect(await within(drawer).findByText("grp/app!12")).toBeInTheDocument();
    expect(within(drawer).queryByRole("link")).not.toBeInTheDocument();
  });

  test("a failing list says so inside the drawer", async () => {
    serve(mockFetch, TASK_CODE, () => jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: showItems(TASKS_CARD, "With a merged merge request") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks with a merged merge request" });
    expect(await within(drawer).findByRole("alert")).toBeInTheDocument();
  });
});
