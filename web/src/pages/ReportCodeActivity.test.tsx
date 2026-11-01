import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCodeActivity from "./ReportCodeActivity";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import {
  CODE_ACTIVITY,
  CODE_ACTIVITY_NO_SOURCES,
  CODE_ACTIVITY_PENDING,
  CODE_ACTIVITY_SMALL,
  CODE_ACTIVITY_UNASSIGNED,
  CODE_ACTIVITY_USERS,
} from "../test/codeActivityFixtures";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histograms and the weekly chart are probes carrying the props the page decides.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: Record<string, unknown>[]; dataKey: string; series: { name: string }[] }) => (
    <div
      data-testid={props.dataKey === "week" ? "weekly-chart" : "bar-chart"}
      data-values={props.data.map((row) => props.series.map((s) => String(row[s.name] ?? "")).join("/")).join(",")}
      data-series={props.series.map((s) => s.name).join(",")}
    />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/code-activity?";
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

function serve(mockFetch: FetchMock, report: (url: string) => Response = () => jsonResponse(200, CODE_ACTIVITY)) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report(url));
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

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });
const cellsOf = (row: HTMLElement) => within(row).getAllByRole("cell").map((cell) => cell.textContent);

describe("ReportCodeActivity page", () => {
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

  test("a heading outline of one page title and five cards, and one request for the first team", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Activity by member" })).toBeInTheDocument();
    expect(headingOutline().filter(([level]) => level <= 3)).toEqual([
      [2, "Code activity"],
      [3, "Merge request flow"],
      [3, "Merged per week"],
      [3, "Open merge requests now"],
      [3, "How review work is spread"],
      [3, "Activity by member"],
    ]);
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the flow: the merged count, the two review counts with their rule and share, and five distributions with their own numbers", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Merge request flow" });
    expect(within(tile("Merged")).getByText("40")).toBeInTheDocument();
    expect(within(tile("Merged")).getByText("Closed without merging: 3")).toBeInTheDocument();
    const noReview = tile("Merged without review");
    expect(within(noReview).getByText("4")).toBeInTheDocument();
    expect(noReview).toHaveTextContent("No comment, approval or review by another person up to the merge. Lower is better. That is 10% of the merged merge requests whose details were read.");
    expect(tile("Merged without approval")).toHaveTextContent("No approval by another person before the merge. Lower is better. That is 22.5% of the merged");

    for (const title of ["Cycle time, working days", "Cycle time, elapsed days", "Time to first review, working days", "Merge request size, lines", "Merge request size, files"]) {
      expect(await screen.findByRole("heading", { level: 5, name: title })).toBeInTheDocument();
    }
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")).toHaveLength(5));
    expect(screen.getAllByTestId("bar-chart").map((chart) => chart.getAttribute("data-values"))).toEqual([
      "10,14,9,4,3",
      "8,12,10,6,4",
      "20,10,4,2,1",
      "15,12,8,3,2",
      "18,11,7,3,1",
    ]);
    // Every histogram has its numbers as a table, named after it.
    expect(screen.getByRole("table", { name: "Histogram, as a table — Merge request size, lines" })).toBeInTheDocument();
  });

  test("the weekly merged bars: zero weeks kept, the numbers as a table, one series and no verdict", async () => {
    serve(mockFetch);
    renderPage();
    const chart = await screen.findByTestId("weekly-chart");
    expect(chart.getAttribute("data-values")).toBe("12,0,28");
    expect(chart.getAttribute("data-series")).toBe("merged");
    const table = screen.getByRole("table", { name: "Merged per week, as a table" });
    expect(within(table).getAllByRole("row").slice(1).map((row) => cellsOf(row))).toEqual([
      ["2026-09-07", "12"],
      ["2026-09-14", "0"],
      ["2026-09-21", "28"],
    ]);
  });

  test("open now: the waiting count as the headline, the drafts apart, the age median and p85", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Open merge requests now" });
    expect(screen.getByText("Waiting for review").nextElementSibling).toHaveTextContent("6");
    expect(screen.getByText("Drafts").nextElementSibling).toHaveTextContent("2");
    const age = screen.getByRole("group", { name: "Age of the waiting merge requests" });
    expect(age).toHaveTextContent("Age, median (working days)2.5");
    expect(age).toHaveTextContent("Age, p85 (working days)6");
    expect(screen.getByText(/a draft is not waiting for review/)).toBeInTheDocument();
  });

  test("review spread: the median reviewers per merge request and the busiest reviewer's share, absent when the response has none", async () => {
    serve(mockFetch);
    const { unmount } = renderPage();
    await screen.findByRole("heading", { level: 3, name: "How review work is spread" });
    expect(screen.getByText("Reviewers per merged merge request (median)").nextElementSibling).toHaveTextContent("2");
    expect(screen.getByText("Share of the reviews held by the busiest reviewer").nextElementSibling).toHaveTextContent("35%");
    expect(screen.getByText("Over the merged merge requests whose details were read: 38.")).toBeInTheDocument();
    unmount();

    serve(mockFetch, () => jsonResponse(200, { ...CODE_ACTIVITY, review: undefined }));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    expect(screen.queryByRole("heading", { name: "How review work is spread" })).not.toBeInTheDocument();
  });

  test("the members' table is NEUTRAL: the server's order, a roster row of zeros kept, no sort control, no icon, the caption says it is context", async () => {
    serve(mockFetch);
    renderPage();
    const table = await screen.findByRole("table", { name: "Activity by member" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Member",
      "Commits",
      "Lines added",
      "Lines deleted",
      "Merged",
      "Opened",
      "Open now",
      "Reviews",
      "Approvals",
      "Comments",
    ]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((row) => cellsOf(row))).toEqual([
      ["Ann Author", "84", "4159 (commits not counted: 2)", "1682", "18", "20", "3", "11", "9", "37"],
      ["Bob Builder", "0", "0", "0", "0", "0", "0", "0", "0", "0"],
    ]);
    // No sort control, no icon, no colour cue anywhere in the table: a column header is plain text and a cell is its number.
    expect(within(table).queryAllByRole("button")).toHaveLength(0);
    expect(table.querySelector("svg")).toBeNull();
    expect(table.querySelector("[data-variant], [data-color]")).toBeNull();
    expect(screen.getByText(/Context, not performance: these are volumes of work, in no order of merit/)).toBeInTheDocument();
    // The name is the way into that member's own view.
    expect(within(rows[0]).getByRole("link", { name: "Show Ann Author" }).getAttribute("href")).toContain("accountId=acc-ann");
  });

  test("with members picked the request repeats accountId, every histogram splits per person and the rows are the picks, not links", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_USERS));
    renderPage("/reports/code-activity?teamId=1&accountId=acc-ann&accountId=acc-bob");
    const table = await screen.findByRole("table", { name: "Activity by member" });
    expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-ann&accountId=acc-bob`]);
    expect(within(table).queryAllByRole("link")).toHaveLength(0);
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    const working = await screen.findByRole("table", { name: "Histogram, as a table — Cycle time, working days" });
    expect(within(working).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Range", "1. Ann Author", "2. Bob Builder", "Items"]);
  });

  test("the sources say when they were synced and what is still to be read, and the note says what no figure holds yet", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_PENDING));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    expect(screen.getByText(/Stub GitLab: last synced .* Files of 12 commits are still to be read.* Details of 5 merge requests are still to be read/)).toBeInTheDocument();
    expect(screen.getByText("Second GitLab: not synced yet.")).toBeInTheDocument();
    expect(screen.getByText(/The derived date and configuration revision above belong to the Jira data/)).toBeInTheDocument();
  });

  test("the unlinked and unknown counts appear in one gray note; an administrator gets a link to each source's identities", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_PENDING));
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
    renderPage();
    const note = await screen.findByText(/GitLab identities with activity in this period that no one has linked to a person yet: 3\./);
    const alert = note.closest('[role="note"]') as HTMLElement;
    expect(alert).toHaveTextContent("Their commits (21), merge requests (4) and review acts (30) are in no member's figures until an administrator decides.");
    expect(within(alert).getByText("Commits with unknown lines, left out of the lines sums: 2.")).toBeInTheDocument();
    expect(within(alert).getByText("Merged merge requests with unknown size, left out of the size figures: 1.")).toBeInTheDocument();
    expect(within(alert).getByText("Merged merge requests whose details are not read yet, left out of the review figures: 5.")).toBeInTheDocument();
    expect(within(alert).getByText("Review acts with no date, in no period: 1.")).toBeInTheDocument();
    expect(within(alert).getByRole("link", { name: "Open the identities of Stub GitLab" })).toHaveAttribute("href", "/data-sources/7/identities");
    expect(within(alert).getByRole("link", { name: "Open the identities of Second GitLab" })).toHaveAttribute("href", "/data-sources/9/identities");
  });

  test("a regular user reads the same note without the admin links", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_PENDING));
    renderPage();
    const note = await screen.findByText(/GitLab identities with activity in this period that no one has linked to a person yet: 3\./);
    expect(within(note.closest('[role="note"]') as HTMLElement).queryAllByRole("link")).toHaveLength(0);
  });

  test("a complete picture raises no note at all", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    expect(screen.queryByText("Still to attribute, or not yet known")).not.toBeInTheDocument();
  });

  test("few merged merge requests: every distribution says it is withheld, the counts stay, the review and age figures are dashes or notes", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_SMALL));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Merge request flow" });
    expect(screen.getAllByText(/at least 5 are needed to show percentiles and a histogram/)).toHaveLength(5);
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(within(tile("Merged without review")).getByText("1")).toBeInTheDocument();
    expect(tile("Merged without review")).toHaveTextContent("No merged merge request has its details read yet.");
    expect(screen.getByText("Waiting for review").nextElementSibling).toHaveTextContent("1");
    expect(screen.queryByRole("group", { name: "Age of the waiting merge requests" })).not.toBeInTheDocument();
    expect(screen.getByText("Fewer than 5 merge requests are waiting, so no ages are shown.")).toBeInTheDocument();
    expect(screen.getByText("Reviewers per merged merge request (median)").nextElementSibling).toHaveTextContent("—");
    expect(screen.getByText(/A figure shown as a dash is withheld under 5 such merge requests\./)).toBeInTheDocument();
  });

  test("no active GitLab source: one empty state in place of the cards", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_NO_SOURCES));
    renderPage();
    expect(await screen.findByText("No GitLab data source is active.")).toBeInTheDocument();
    expect(screen.getByText(/An administrator connects one under Data sources/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
  });

  test("the work of unlinked identities (teamId=0) keeps its own labels and links nowhere", async () => {
    serve(mockFetch, () => jsonResponse(200, CODE_ACTIVITY_UNASSIGNED));
    renderPage();
    const table = await screen.findByRole("table", { name: "Activity by member" });
    expect(within(table).getAllByRole("row").slice(1).map((row) => cellsOf(row).slice(0, 2))).toEqual([
      ["sample.stranger", "5"],
      ["Not linked to a person", "1"],
    ]);
    expect(within(table).queryAllByRole("link")).toHaveLength(0);
  });

  test("a pasted link with another report's slices or a Jira connection is read without them — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/code-activity?teamId=1&domain=FLO&activityType=Bug&bucket=MONTH&connectionId=1");
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the filter bar offers the team, the members and the period with sprints, and nothing of the Jira slices", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    await screen.findByRole("group", { name: "Report filters" });
    for (const name of ["Team", "Member"]) expect(screen.getAllByText(name).length).toBeGreaterThan(0);
    // The period is dates AND sprints (the window of a sprint is "the merge requests merged in it").
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    expect(screen.getByRole("option", { name: "Last 3 sprints" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Last 90 days" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    for (const name of ["Domain", "Activity type", "Work category"]) expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
  });

  test("the long tail: the default 5 is in neither the URL nor the request; a pick writes both", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const control = await screen.findByRole("combobox", { name: "Long tail" });
    expect(control).toHaveValue("5%");
    expect(calls(mockFetch)).toEqual([TEAM]);
    await user.click(control);
    await user.click(screen.getByRole("option", { name: "10%" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&tailPercent=10`));
    expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1&tailPercent=10");
  });

  test("a failed load is one red alert, not a card of zeros", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
  });

  test("with no team at all there is nothing to read, and nothing is requested", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/reports/filters" ? jsonResponse(200, { ...FILTERS, teams: [] }) : jsonResponse(404, { title: "Not Found", status: 404 })),
    );
    renderPage();
    expect(await screen.findByText("There are no teams yet, so there is nothing to read.")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
  });
});
