import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportDataQuality from "./ReportDataQuality";
import { jsonResponse } from "../test/http";
import {
  DATA_QUALITY,
  DATA_QUALITY_CAPPED,
  DATA_QUALITY_CLEAN,
  DATA_QUALITY_TEAM,
  DATA_QUALITY_USER,
  FILTERS,
} from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histogram is a probe.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { count: number }[] }) => (
    <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/data-quality?";
const ROLES_KEY = "flow.auth.roles";

function serve(mockFetch: FetchMock, respond?: (url: string) => Response, filters: typeof FILTERS = FILTERS) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, filters));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(respond?.(url) ?? jsonResponse(200, DATA_QUALITY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Probe() {
  const { search } = useLocation();
  return <output data-testid="search">{search}</output>;
}

function renderPage(route = "/reports/data-quality") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/data-quality"
        element={
          <>
            <ReportDataQuality />
            <Probe />
          </>
        }
      />
      <Route path="/data-sources/:id/metrics-config" element={<div>metrics configuration page</div>} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const rowsOf = (table: HTMLElement) =>
  within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
const card = (name: string) => screen.getByRole("group", { name });
const table = (name: string) => screen.getByRole("table", { name: `${name}, as a table` });

/** The report has loaded once its overview names the populations. */
const ready = () => screen.findByRole("group", { name: "What was checked" });

const CARD_TITLES = [
  "Done tasks without worklogs",
  "Logged hours per member and working day",
  "Late logging",
  "Authors without a team",
  "Tasks without an estimate",
  "Tasks without an epic",
  "Tasks without a work category",
  "Done tasks without an assignee",
  "Epics without an estimate of their own",
  "Epics without dates",
  "Epics with dates far from today",
  "Epics that disagree with their children",
  "Work done outside any sprint",
  "Tasks in another domain than their epic",
  "Sprint figures that moved since the freeze",
  "Domains without an owner team",
  "Statuses without a stage",
  "Boards without a team",
  "Derive warnings",
];

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

describe("ReportDataQuality page — request, overview and the findings", () => {
  test("sends only the params the page owns; a link's foreign ones are dropped from the request and the URL", async () => {
    serve(mockFetch);
    renderPage(
      "/reports/data-quality?connectionId=1&domain=FLO&domainView=EPIC&teamId=1&activityType=Bug&workCategory=X&bucket=WEEK&by=STATUS&itemKind=EPIC&epicId=FLO-1&utm=x",
    );
    expect(await screen.findByRole("heading", { level: 2, name: "Data quality" })).toBeInTheDocument();
    await ready();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&domainView=EPIC&domain=FLO&connectionId=1`]);
    await waitFor(() =>
      expect(new URLSearchParams(screen.getByTestId("search").textContent ?? "").toString()).toBe(
        "utm=x&teamId=1&domainView=EPIC&domain=FLO&connectionId=1",
      ),
    );
  });

  test("a bare link asks for the server's own defaults; the bar offers domain, its view and the team — no activity type or work category", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
    for (const name of ["Period", "Team", "Domain"]) expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    expect(screen.queryByRole("combobox", { name: "Activity type" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Work category" })).not.toBeInTheDocument();
    // One connection in the reference data: nothing to choose.
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  test("the domain view is a request param when chosen", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await ready();
    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}domainView=EPIC`));
  });

  test("the overview states the populations and every headline count, each tile a link to its card", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const overview = await screen.findByRole("group", { name: "Headline counts, each linking to its card" });
    const populations = screen.getByRole("group", { name: "What was checked" });
    expect(within(populations).getByText("Done tasks").nextSibling).toHaveTextContent("20");
    expect(within(populations).getByText("Open started tasks").nextSibling).toHaveTextContent("3");
    expect(within(populations).getByText("Epics").nextSibling).toHaveTextContent("4");
    expect(within(populations).getByText("Worklogs").nextSibling).toHaveTextContent("30");

    const tile = (label: string) => within(overview).getByRole("link", { name: label }).parentElement?.textContent;
    expect(tile("Done tasks without worklogs")).toBe("Done tasks without worklogs80%16 of 20 done tasks have worklogs");
    expect(tile("Over 1 day late")).toBe("Over 1 day late16of 28 worklogs with a known logging time");
    expect(tile("Over 7 days late")).toBe("Over 7 days late3of 28 worklogs with a known logging time");
    expect(tile("Authors without a team")).toBe("Authors without a team2");
    expect(tile("Tasks without an estimate")).toBe("Tasks without an estimate43 done · 1 open");
    expect(tile("Tasks without an epic")).toBe("Tasks without an epic75 done · 2 open");
    expect(tile("Tasks without a work category")).toBe("Tasks without a work category32 done · 1 open");
    expect(tile("Done tasks without an assignee")).toBe("Done tasks without an assignee22 done");
    expect(tile("Epics without an estimate of their own")).toBe("Epics without an estimate of their own2epics");
    expect(tile("Epics without dates")).toBe("Epics without dates2epics");
    expect(tile("Epics with dates far from today")).toBe("Epics with dates far from today1epics");
    expect(tile("Work done outside any sprint")).toBe("Work done outside any sprint33 done");
    expect(tile("Tasks in another domain than their epic")).toBe("Tasks in another domain than their epic11 done");
    expect(tile("Sprint figures that moved since the freeze")).toBe(
      "Sprint figures that moved since the freeze3sprint figures moved since the freeze",
    );
    expect(tile("Epics that disagree with their children")).toBe("Epics that disagree with their children2epics");
    expect(tile("Statuses without a stage")).toBe("Statuses without a stage1statuses without a stage");
    expect(tile("Boards without a team")).toBe("Boards without a team1boards without a team · unattributed done tasks: 2");
    expect(tile("Domains without an owner team")).toBe("Domains without an owner team1domains without an owner team");
    expect(tile("Derive warnings")).toBe("Derive warnings1connections with a warning");

    // Following a tile lands on its card (keyboard focus moves there) without touching the report's URL.
    const before = screen.getByTestId("search").textContent;
    await user.click(within(overview).getByRole("link", { name: "Boards without a team" }));
    expect(document.activeElement).toBe(card("Boards without a team"));
    expect(screen.getByTestId("search").textContent).toBe(before);
  });

  test("every finding has its card, in order, each with a plain-language line", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const titles = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    // The overview and the groups table sit around the nineteen findings.
    expect(titles.slice(1, 1 + CARD_TITLES.length)).toEqual(CARD_TITLES);
    expect(within(card("Done tasks without worklogs")).getByText(/no time logged at all/)).toBeInTheDocument();
    expect(within(card("Statuses without a stage")).getByText(/An administrator maps them to a stage/)).toBeInTheDocument();
  });

  test("coverage: the fraction, the done/without split and the list of tasks with a dash for what is unknown", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const c = card("Done tasks without worklogs");
    expect(within(c).getByText("Found: 4")).toBeInTheDocument();
    expect(within(c).getByText("Coverage").nextSibling).toHaveTextContent("80%");
    expect(within(c).getByText("With worklogs").nextSibling).toHaveTextContent("16");
    expect(rowsOf(table("Done tasks without worklogs"))).toEqual([
      ["FLO-1Summary of FLO-1", "Alpha", "Ann Author", "2026-09-20", "2026-09-10", "3"],
      ["FLO-2", "Alpha", "Unassigned", "2026-09-20", "2026-09-10", "—"],
    ]);
    // Only 2 of the 4 rows were sent (a fixture, not the server's 50 cap), so the card says so.
    expect(within(c).getByText("and 2 more (the list shows the first 2)")).toBeInTheDocument();
  });

  test("logged hours: a figure read against the configured man-day, not a finding", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const c = card("Logged hours per member and working day");
    expect(within(c).queryByText(/Found|None found/)).not.toBeInTheDocument();
    expect(within(c).getByText("Member-days").nextSibling).toHaveTextContent("25");
    expect(within(c).getByText("Hours logged").nextSibling).toHaveTextContent("130");
    expect(within(c).getByText("Hours per member-day").nextSibling).toHaveTextContent("5.2 h");
    expect(within(c).getByText("Configured man-day").nextSibling).toHaveTextContent("8 h");
  });

  test("late logging: counts, the lateness distribution and the latest-logged worklogs", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const c = card("Late logging");
    expect(within(c).getByText("Found: 16")).toBeInTheDocument();
    expect(within(c).getByText("Over 1 day late").nextSibling).toHaveTextContent("16");
    expect(within(c).getByText("Over 7 days late").nextSibling).toHaveTextContent("3");
    expect(within(c).getByText("With known logging time").nextSibling).toHaveTextContent("28");
    await waitFor(() => expect(within(c).getByTestId("bar-chart")).toHaveAttribute("data-counts", "12,9,4,3"));
    expect(rowsOf(table("Latest-logged worklogs"))).toEqual([
      ["FLO-9Late one", "Bob Builder", "Alpha", "2026-09-01", "11.5"],
      ["FLO-10", "Unknown author", "Unassigned", "2026-09-03", "7.2"],
    ]);
  });

  test("missing data: done vs open, the estimate the tasks carry and each list", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const noEstimate = card("Tasks without an estimate");
    expect(within(noEstimate).getByText("Found: 4")).toBeInTheDocument();
    expect(within(noEstimate).getByText("Done", { selector: "p" }).nextSibling).toHaveTextContent("3");
    expect(within(noEstimate).getByText("Open", { selector: "p" }).nextSibling).toHaveTextContent("1");
    expect(within(noEstimate).queryByText("Estimate (MD)", { selector: "p" })).not.toBeInTheDocument();
    // An open task has no done date.
    expect(rowsOf(table("Tasks without an estimate")).map((r) => r[3])).toEqual(["2026-09-20", "Open"]);

    const noEpic = card("Tasks without an epic");
    expect(within(noEpic).getByText("Found: 7")).toBeInTheDocument();
    expect(within(noEpic).getByText("Estimate (MD)", { selector: "p" }).nextSibling).toHaveTextContent("12");

    const unassigned = card("Done tasks without an assignee");
    expect(within(unassigned).queryByText("Open", { selector: "p" })).not.toBeInTheDocument();
    expect(rowsOf(table("Done tasks without an assignee"))[0].slice(1, 3)).toEqual(["Unassigned", "Unassigned"]);

    expect(within(card("Tasks without a work category")).getByText("Found: 3")).toBeInTheDocument();
    expect(within(card("Work done outside any sprint")).getByText("Found: 3")).toBeInTheDocument();
    expect(within(card("Tasks in another domain than their epic")).getByText("Found: 1")).toBeInTheDocument();
  });

  test("epics: owner, domain and dates, the drift naming how each epic disagrees", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    expect(rowsOf(table("Epics without an estimate of their own"))).toEqual([
      ["FLO-E1Epic FLO-E1", "Alpha", "FLO", "2026-08-01", "2026-11-01", "Open"],
      ["FLO-E2Epic FLO-E2", "Beta", "FLO", "2026-08-01", "2026-11-01", "Open"],
    ]);
    expect(rowsOf(table("Epics without dates"))[0]).toEqual(["FLO-E3Epic FLO-E3", "No owner team", "FLO", "—", "—", "Open"]);
    expect(rowsOf(table("Epics with dates far from today"))[0][4]).toBe("2050-01-01");
    expect(rowsOf(table("Epics that disagree with their children")).map((r) => r[6])).toEqual([
      "Not started, children active",
      "Open, all children done, Done, children open",
    ]);
  });

  test("snapshot drift: sprint, figure, live, frozen and the signed difference; a reconstructed baseline is marked and explained", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const c = card("Sprint figures that moved since the freeze");
    expect(within(c).getByText("Found: 3")).toBeInTheDocument();
    expect(rowsOf(table("Sprint figures that moved since the freeze"))).toEqual([
      ["Alpha 2Alpha", "2026-09-10", "Capacity (MD)", "40", "32", "+8", "Frozen at close"],
      ["Alpha 2Alpha", "2026-09-10", "Load", "0.875", "1.1", "−0.225", "Frozen at close"],
      ["Beta 1Beta", "—", "Committed (items)", "9", "8", "+1", "Reconstructed"],
    ]);
    expect(within(c).getByText(/Reconstructed: the snapshot was written by a derive that ran after the sprint closed/)).toBeInTheDocument();
  });

  test("the reconstructed note is absent when every baseline is a true freeze", async () => {
    const drift = DATA_QUALITY.snapshotDrift.items.map((row) => ({ ...row, reconstructed: false }));
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, snapshotDrift: { total: 3, items: drift } }));
    renderPage();
    await ready();
    expect(screen.queryByText(/Reconstructed: the snapshot/)).not.toBeInTheDocument();
    expect(screen.queryByText("Reconstructed")).not.toBeInTheDocument();
  });

  test("configuration findings: domains, statuses, boards (with the residual) and derive warnings", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    expect(rowsOf(table("Domains without an owner team"))).toEqual([["Stub", "OperationsOPS", "OPS, OPS2", "3"]]);
    expect(rowsOf(table("Statuses without a stage"))).toEqual([["Stub", "Waiting for vendor", "In Progress", "14", "2"]]);
    expect(rowsOf(table("Boards without a team"))).toEqual([["Stub", "OPS board", "OPS", "4", "3"]]);
    expect(
      within(card("Boards without a team")).getByText(/2 done tasks were finished in a team-less sprint that belongs to none of the boards above/),
    ).toBeInTheDocument();
    expect(rowsOf(table("Derive warnings"))).toEqual([
      ["Stub", "2026-05-28", "The sprint field could not be resolved, so the sprint step was skipped"],
    ]);
    expect(within(card("Authors without a team")).getByText("Found: 2")).toBeInTheDocument();
    expect(rowsOf(table("Authors without a team"))).toEqual([
      ["Zed Zero", "2", "1.5"],
      ["Unknown author", "1", "0.5"],
    ]);
  });

  test("a list the server capped says 'and N more' — a task finding and a configuration list alike", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_CAPPED));
    renderPage();
    await ready();
    const noEpic = card("Tasks without an epic");
    expect(within(noEpic).getByText("Found: 75")).toBeInTheDocument();
    expect(rowsOf(table("Tasks without an epic"))).toHaveLength(50);
    expect(within(noEpic).getByText("and 25 more (the list shows the first 50)")).toBeInTheDocument();
    const statuses = card("Statuses without a stage");
    expect(within(statuses).getByText("Found: 53")).toBeInTheDocument();
    expect(within(statuses).getByText("and 3 more (the list shows the first 50)")).toBeInTheDocument();
    // The overview counts the finding, not the rows the list carries.
    const overview = screen.getByRole("group", { name: "Headline counts, each linking to its card" });
    expect(within(overview).getByRole("link", { name: "Tasks without an epic" }).parentElement?.textContent).toBe(
      "Tasks without an epic7570 done · 5 open",
    );
  });

  test("a finding that was checked and found nothing still has its card, saying so — no lists, no hidden cards", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_CLEAN));
    renderPage();
    await ready();
    for (const title of CARD_TITLES) expect(card(title)).toBeInTheDocument();
    // Every finding but the logged-hours figure states it is clean.
    expect(screen.getAllByText("None found")).toHaveLength(CARD_TITLES.length - 1);
    expect(screen.queryByText(/^Found: /)).not.toBeInTheDocument();
    expect(screen.queryAllByRole("table", { name: /, as a table$/ })).toEqual([]);
    expect(screen.queryByText(/and \d+ more/)).not.toBeInTheDocument();
    // Coverage still states its figures: 100%, nothing without a worklog.
    expect(within(card("Done tasks without worklogs")).getByText("Coverage").nextSibling).toHaveTextContent("100%");
    // No groups, so no groups table.
    expect(screen.queryByRole("heading", { name: "By team" })).not.toBeInTheDocument();
  });

  test("a lateness distribution below the minimum sample is the counts-only note, not percentiles", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_TEAM));
    renderPage("/reports/data-quality?teamId=1");
    await ready();
    const c = card("Late logging");
    expect(within(c).getByText(/Only 3 items in this selection — at least 5 are needed/)).toBeInTheDocument();
    expect(within(c).queryByTestId("bar-chart")).not.toBeInTheDocument();
  });

});

describe("ReportDataQuality page — levels, roles and the empty states", () => {
  test("the groups table at UNIT level: counts per team, unassigned unlinked, the epic columns present", async () => {
    serve(mockFetch);
    renderPage();
    await ready();
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    expect(within(groups).getByRole("columnheader", { name: "Epics" })).toBeInTheDocument();
    expect(rowsOf(groups)).toEqual([
      ["Alpha", "12", "2", "3", "3", "4", "0", "2", "9", "2", "6", "2", "3"],
      ["Beta", "6", "1", "1", "1", "3", "1", "1", "6", "1", "4", "1", "2"],
      ["Unassigned", "2", "0", "0", "0", "0", "1", "0", "1", "0", "—", "1", "2"],
    ]);
    expect(within(groups).getAllByRole("link").map((a) => a.getAttribute("href"))).toEqual([
      "/reports/data-quality?teamId=1",
      "/reports/data-quality?teamId=2",
    ]);
  });

  test("one team: members are the groups (no epic columns), the team-less findings say a team's view lists none, the connection ones say the team does not narrow them", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_TEAM));
    renderPage("/reports/data-quality?teamId=1");
    await ready();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
    const groups = screen.getByRole("heading", { name: "By member" }).closest("div") as HTMLElement;
    expect(within(groups).queryByRole("columnheader", { name: "Epics" })).not.toBeInTheDocument();
    expect(rowsOf(groups).map((r) => r[0])).toEqual(["Ann Author", "Bob Builder"]);
    expect(within(groups).getAllByRole("link").map((a) => a.getAttribute("href"))).toEqual([
      "/reports/data-quality?teamId=1&accountId=acc-ann",
      "/reports/data-quality?teamId=1&accountId=acc-bob",
    ]);

    // What the API returns is rendered: none for what belongs to no team…
    for (const title of ["Domains without an owner team", "Authors without a team"]) {
      const c = card(title);
      expect(within(c).getByText("None found")).toBeInTheDocument();
      expect(within(c).getByText(/This belongs to no team, so a single team's view lists none/)).toBeInTheDocument();
    }
    // Sprint drift follows the sprint's own team: it is not a connection-level finding, so it carries no such note.
    const drift = card("Sprint figures that moved since the freeze");
    expect(within(drift).getByText("Found: 3")).toBeInTheDocument();
    expect(within(drift).queryByText(/does not narrow it/)).not.toBeInTheDocument();
    // Only the board LIST is not narrowed (its done-task counts and the residual follow the filter).
    expect(
      within(card("Boards without a team")).getByText(/Only the list of boards is not narrowed by the team/),
    ).toBeInTheDocument();
    // …and the connection-level findings are still all there.
    for (const title of ["Statuses without a stage", "Derive warnings"]) {
      const c = card(title);
      expect(within(c).getByText("Found: 1")).toBeInTheDocument();
      expect(within(c).getByText("This is a property of the connection, so the team filter does not narrow it.")).toBeInTheDocument();
    }
  });

  test("the whole unit and the unassigned bucket carry no 'team-less' note; a work-category field nobody configured is not measured, not clean", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_TEAM));
    renderPage("/reports/data-quality?teamId=0");
    await ready();
    expect(screen.queryByText(/This belongs to no team/)).not.toBeInTheDocument();
    expect(screen.queryByText(/does not narrow it/)).not.toBeInTheDocument();
    const c = card("Tasks without a work category");
    expect(within(c).getByText("Not measured")).toBeInTheDocument();
    expect(within(c).getByText(/No connection in scope has a work-category field configured/)).toBeInTheDocument();
    expect(within(c).queryByText("None found")).not.toBeInTheDocument();
    const overview = screen.getByRole("group", { name: "Headline counts, each linking to its card" });
    expect(within(overview).getByRole("link", { name: "Tasks without a work category" }).parentElement?.textContent).toBe(
      "Tasks without a work category—no work-category field configured",
    );
  });

  test("a member's read has no epic or sprint findings and no groups — it says why instead of 'none found'", async () => {
    serve(mockFetch, () => jsonResponse(200, DATA_QUALITY_USER));
    renderPage("/reports/data-quality?teamId=1&accountId=acc-ann");
    await ready();
    for (const title of [
      "Epics without an estimate of their own",
      "Epics without dates",
      "Epics with dates far from today",
      "Epics that disagree with their children",
    ]) {
      expect(within(card(title)).getByText("Not measured")).toBeInTheDocument();
      expect(within(card(title)).getByText(/Epics aren't attributed to individual people/)).toBeInTheDocument();
    }
    const drift = card("Sprint figures that moved since the freeze");
    expect(within(drift).getByText("Not measured")).toBeInTheDocument();
    expect(within(drift).getByText(/not read for a single person/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^By (team|member)$/ })).not.toBeInTheDocument();
  });

  test("an administrator gets each configuration finding's connection as a link into its metrics configuration", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await ready();
    const links = screen.getAllByRole("link", { name: "Open the metrics configuration of Stub" });
    expect(links).toHaveLength(4);
    expect(links.every((a) => a.getAttribute("href") === "/data-sources/1/metrics-config")).toBe(true);
    for (const title of ["Domains without an owner team", "Statuses without a stage", "Boards without a team", "Derive warnings"]) {
      expect(within(card(title)).getAllByRole("link", { name: "Open the metrics configuration of Stub" })).toHaveLength(1);
    }
    await user.click(links[0]);
    expect(await screen.findByText("metrics configuration page")).toBeInTheDocument();
  });

  test("a regular user reads the same findings — the connection is plain text, no link into the configuration", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["USER"]));
    serve(mockFetch);
    renderPage();
    await ready();
    expect(screen.queryByRole("link", { name: /metrics configuration/i })).not.toBeInTheDocument();
    const statuses = card("Statuses without a stage");
    expect(within(statuses).getByText("Stub")).toBeInTheDocument();
    expect(within(statuses).queryByRole("link")).not.toBeInTheDocument();
    // The admin-actionable text is still there for everyone.
    expect(within(statuses).getByText(/An administrator maps them to a stage/)).toBeInTheDocument();
  });

  test("boards: the badge counts boards only; a residual with no board listed keeps the card orange with its own note and tile figure", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, unmappedBoards: { total: 0, items: [], unattributedDoneTasks: 4 } }));
    renderPage();
    await ready();
    const c = card("Boards without a team");
    expect(c).toHaveAttribute("data-state", "found");
    expect(within(c).getByText("Found: 0")).toBeInTheDocument();
    expect(within(c).getByText(/4 done tasks were finished in a team-less sprint/)).toBeInTheDocument();
    const overview = screen.getByRole("group", { name: "Headline counts, each linking to its card" });
    expect(within(overview).getByRole("link", { name: "Boards without a team" }).parentElement?.textContent).toBe(
      "Boards without a team0boards without a team · unattributed done tasks: 4",
    );
  });

  test("no done task in the period: coverage is not measured — not a teal 'none found'", async () => {
    serve(mockFetch, () =>
      jsonResponse(200, {
        ...DATA_QUALITY,
        worklogCoverage: { doneTasks: 0, withWorklogs: 0, coverage: null, without: { done: 0, open: 0, total: 0, md: 0, items: [] } },
      }),
    );
    renderPage();
    await ready();
    const c = card("Done tasks without worklogs");
    expect(within(c).getByText("Not measured")).toBeInTheDocument();
    expect(within(c).getByText("No done task in this period, so there is no coverage to measure.")).toBeInTheDocument();
    expect(within(c).queryByText("None found")).not.toBeInTheDocument();
  });

  test("worklogs without a known logging time: late logging is not measured, and so are none at all", async () => {
    const late = { ...DATA_QUALITY.lateLogging, measurable: 0, over1Day: 0, over7Days: 0, worst: [] };
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, lateLogging: late }));
    const { unmount } = renderPage();
    await ready();
    let c = card("Late logging");
    expect(within(c).getByText("Not measured")).toBeInTheDocument();
    expect(within(c).getByText(/No worklog in this period has a known logging time/)).toBeInTheDocument();
    expect(within(c).queryByText("None found")).not.toBeInTheDocument();
    unmount();
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, lateLogging: { ...late, worklogs: 0 } }));
    renderPage();
    await ready();
    c = card("Late logging");
    expect(within(c).getByText("No worklogs in this period.")).toBeInTheDocument();
  });

  test("epic start and due are UTC calendar dates, read without the zone; the done date keeps it", async () => {
    // In Los Angeles UTC midnight is the evening before: a zoned read would show 2026-07-31.
    const zoned = { ...FILTERS, timeZone: "America/Los_Angeles" };
    const epic = { ...DATA_QUALITY.epicDrift.items[0], startAt: Date.UTC(2026, 7, 1), dueAt: Date.UTC(2026, 10, 1), doneAt: Date.UTC(2026, 8, 20, 3) };
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, epicDrift: { total: 1, items: [epic] } }), zoned);
    renderPage();
    await ready();
    const [row] = rowsOf(table("Epics that disagree with their children"));
    expect(row.slice(3, 6)).toEqual(["2026-08-01", "2026-11-01", "2026-09-19"]);
  });

  test("a derive-warning row names the connection as the server sent it", async () => {
    const warning = { ...DATA_QUALITY.deriveWarnings[0], connectionName: "Server's own name" };
    serve(mockFetch, () => jsonResponse(200, { ...DATA_QUALITY, deriveWarnings: [warning] }));
    renderPage();
    await ready();
    expect(rowsOf(table("Derive warnings"))[0][0]).toBe("Server's own name");
  });

  test("a failure is an inline alert, and no findings render", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.queryByRole("heading", { level: 3, name: "Late logging" })).not.toBeInTheDocument();
  });
});
