import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCodeActivity from "./ReportCodeActivity";
import { jsonResponse } from "../test/http";
import { CODE_ACTIVITY, CODE_ACTIVITY_UNASSIGNED_MIXED, codeCommitItems, codeMrItems } from "../test/codeActivityFixtures";
import { showItems } from "../test/drill";
import { FILTERS } from "../test/reportFixtures";
import { expectNeutralVolumes } from "../test/neutralVolumes";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the charts are inert probes.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { dataKey: string }) => <div data-testid={props.dataKey === "week" ? "weekly-chart" : "bar-chart"} />,
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PREFIX = "/api/v1/reports/code-activity?";
const ITEMS_PREFIX = "/api/v1/reports/code-activity/items?";
const SHARED_TEAM_KEY = "flow.viewSettings.reports.teamId";

/** Team 1 answers the ordinary report, `teamId=0` the Unassigned bucket; `refuse` overrides a request (a budget 400, a permit 429). */
function serve(mockFetch: FetchMock, refuse?: (url: string) => Response | undefined) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    const refused = refuse?.(url);
    if (refused) return Promise.resolve(refused);
    if (url.startsWith(ITEMS_PREFIX)) return Promise.resolve(jsonResponse(200, url.includes("view=COMMITS") ? codeCommitItems(3) : codeMrItems(40)));
    if (url.startsWith(REPORT_PREFIX)) return Promise.resolve(jsonResponse(200, url.includes("teamId=0") ? CODE_ACTIVITY_UNASSIGNED_MIXED : CODE_ACTIVITY));
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

const urls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const parse = (url: string) => Object.fromEntries(new URL(url, "http://flow.test").searchParams.entries());
const where = () => screen.getByTestId("where");
const membersTable = () => screen.findByRole("table", { name: "Activity by member" });

async function pickUnassigned(user: ReturnType<typeof userEvent.setup>) {
  await openReportFilters(user);
  await user.click(screen.getByRole("combobox", { name: "Team" }));
  await user.click(await screen.findByRole("option", { name: "Unassigned" }));
}

describe("ReportCodeActivity page - the Unassigned bucket (teamId=0)", () => {
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

  test("the team control closes its list with Unassigned: picking it writes teamId=0, drops the members and a last-N-sprints period, and keeps the shared remembered team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/code-activity?teamId=1&lastSprints=3&accountId=acc-ann");
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    expect((await screen.findAllByRole("option")).map((option) => option.textContent)).toEqual(["Alpha", "Beta", "Gamma", "Unassigned"]);
    await user.click(screen.getByRole("option", { name: "Unassigned" }));

    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    await waitFor(() => expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}teamId=0`));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Unassigned");
    // No roster, so no member to pick; the shared team a bare visit of another report starts on is untouched.
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
  });

  test("Unassigned survives a reload: the address as written, and a bare visit that restores the page's own memory", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderPage();
    await screen.findByRole("heading", { level: 3, name: "Activity by member" });
    await pickUnassigned(user);
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    await membersTable();
    first.unmount();

    // A reload of the address: not rewritten to a team, and asked for as written.
    mockFetch.mockClear();
    serve(mockFetch);
    const reloaded = renderPage("/reports/code-activity?teamId=0");
    expect(await screen.findByText("sample.stranger")).toBeInTheDocument();
    expect(where()).toHaveTextContent(/^\?teamId=0$/);
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
    reloaded.unmount();

    // A bare visit (a nav click) restores the page's own filter - Unassigned, not the first team.
    mockFetch.mockClear();
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText("sample.stranger")).toBeInTheDocument();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
  });

  test("a link naming members under teamId=0 is read for the whole bucket - off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/code-activity?teamId=0&accountId=acc-ann&accountId=acc-bob");
    await membersTable();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
  });

  test("a pasted or remembered sprint-relative period under teamId=0 is rewritten to the default dates, and the Period control offers no sprints", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const { unmount } = renderPage("/reports/code-activity?teamId=0&lastSprints=3");
    await membersTable();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    expect(screen.queryByRole("option", { name: "Last 3 sprints" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "One sprint" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Last 90 days" })).toBeInTheDocument();
    unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    renderPage("/reports/code-activity?teamId=0&sprintId=11");
    await membersTable();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
  });

  test("the rows are identities: unlinked ones by their GitLab name with no link, a linked person with no team by name as text, a hint says what they are", async () => {
    serve(mockFetch);
    renderPage("/reports/code-activity?teamId=0");
    const table = await membersTable();
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((row) => within(row).getAllByRole("cell").slice(0, 2).map((cell) => cell.textContent))).toEqual([
      ["Dee Drifter", "3"],
      ["sample.stranger", "5"],
      ["sample.visitor", "1"],
    ]);
    // No name narrows to anything (there is no team to narrow within), and the volumes stay neutral.
    expect(within(table).queryAllByRole("link")).toHaveLength(0);
    expectNeutralVolumes(table);
    expect(screen.getByText(/^Unassigned: the work of GitLab identities that no administrator has linked to a person yet/)).toBeInTheDocument();
    // The "still to attribute" note stays, and an administrator keeps the way to the identities.
    expect(screen.getByText(/GitLab identities with activity in this period that no one has linked to a person yet: 2\./)).toBeInTheDocument();
  });

  test("the administrator's Identities link stays in the note under Unassigned", async () => {
    serve(mockFetch);
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
    renderPage("/reports/code-activity?teamId=0");
    expect(await screen.findByRole("link", { name: "Open the identities of Stub GitLab" })).toHaveAttribute("href", "/data-sources/7/identities");
  });

  test("the flow cards drill with teamId=0, and so does a linked person's cell (that person as the only pick); an unlinked row has no figure to open", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/code-activity?teamId=0");
    const table = await membersTable();
    expect(within(within(table).getAllByRole("row")[2]).queryAllByRole("button")).toHaveLength(0);

    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged") }));
    const merged = await screen.findByRole("dialog", { name: "Merge requests merged" });
    expect(await within(merged).findByText("40 items")).toBeInTheDocument();
    expect(parse(urls(mockFetch, ITEMS_PREFIX)[0])).toEqual({ teamId: "0", view: "MRS_MERGED" });
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(within(table).getByRole("button", { name: showItems("Dee Drifter", "Commits") }));
    const commits = await screen.findByRole("dialog", { name: "Commits · Dee Drifter" });
    expect(await within(commits).findByText("3 items")).toBeInTheDocument();
    expect(parse(urls(mockFetch, ITEMS_PREFIX).at(-1) ?? "")).toEqual({ teamId: "0", accountId: "acc-dee", view: "COMMITS" });
  });
});

describe("ReportCodeActivity page - the server's refusals are told in the page's own words", () => {
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

  const budget = () =>
    jsonResponse(400, {
      title: "Bad Request",
      status: 400,
      detail: "The selection reads more than 100000 code rows (commits, merge requests and review events) - narrow the period or pick fewer people",
    });
  const busy = () =>
    jsonResponse(429, { title: "Too Many Requests", status: 429, detail: "Too many code activity reports are being computed at once - retry in a moment" });

  test("a selection over the row budget (400) says to narrow the period or pick fewer people, not a bare status, and draws no card", async () => {
    serve(mockFetch, (url) => (url.startsWith(REPORT_PREFIX) ? budget() : undefined));
    renderPage("/reports/code-activity?teamId=0");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("This selection is too large to read. Narrow the period or pick fewer people.");
    expect(alert).not.toHaveTextContent("Load failed");
    expect(alert).not.toHaveTextContent("code rows");
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
  });

  test("a busy server (429 of the computation permits) says to retry in a moment; the per-user rate limit keeps its own wording", async () => {
    serve(mockFetch, (url) => (url.startsWith(REPORT_PREFIX) ? busy() : undefined));
    const { unmount } = renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("The server is busy computing other reports. Retry in a moment.");
    unmount();

    serve(mockFetch, (url) => (url.startsWith(REPORT_PREFIX) ? jsonResponse(429, { title: "Too Many Requests", status: 429 }) : undefined));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests. Wait a minute and try again.");
  });

  test("the drill drawer says the same when its list is refused", async () => {
    serve(mockFetch, (url) => (url.startsWith(ITEMS_PREFIX) ? budget() : undefined));
    const user = userEvent.setup();
    renderPage("/reports/code-activity?teamId=0");
    await user.click(await screen.findByRole("button", { name: showItems("Merge request flow", "Merged") }));
    const drawer = await screen.findByRole("dialog", { name: "Merge requests merged" });
    expect(await within(drawer).findByRole("alert")).toHaveTextContent("This selection is too large to read. Narrow the period or pick fewer people.");

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    serve(mockFetch, (url) => (url.startsWith(ITEMS_PREFIX) ? busy() : undefined));
    await user.click(screen.getByRole("button", { name: showItems("Merge request flow", "Merged without review") }));
    const again = await screen.findByRole("dialog", { name: /^Merged without review/ });
    expect(await within(again).findByRole("alert")).toHaveTextContent("The server is busy computing other reports. Retry in a moment.");
  });
});
