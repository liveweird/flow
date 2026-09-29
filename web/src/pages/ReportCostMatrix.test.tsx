import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCostMatrix from "./ReportCostMatrix";
import { jsonResponse } from "../test/http";
import {
  COST_MATRIX_EMPTY,
  COST_MATRIX_NO_SHARE,
  COST_MATRIX_SPRINTS,
  COST_MATRIX_TASK_VIEW,
  COST_MATRIX_TEAM,
  COST_MATRIX_THIRDS,
  COST_MATRIX_UNIT,
  COST_MATRIX_USER,
  FILTERS,
} from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/cost-matrix?";

function serve(mockFetch: FetchMock, response: unknown = COST_MATRIX_UNIT, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

/** Serves the level and the domain view the URL asks for, so a click gets its own answer without a race. */
function serveByScope(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      const params = new URLSearchParams(url.slice(URL_PREFIX.length));
      let response = params.get("domainView") === "TASK" ? COST_MATRIX_TASK_VIEW : COST_MATRIX_UNIT;
      if (params.has("accountId")) response = COST_MATRIX_USER;
      else if (params.has("teamId")) response = COST_MATRIX_TEAM;
      return Promise.resolve(jsonResponse(200, response));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/cost-matrix") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/cost-matrix" element={<ReportCostMatrix />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");
const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });
const matrix = () => screen.getByRole("table");
/** Every row after the header: the row header first, then its cells. */
const rowsOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((r) => [...within(r).getAllByRole("rowheader"), ...within(r).getAllByRole("cell")].map((c) => c.textContent));
const heatOf = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1, -1)
    .map((r) => within(r).getAllByRole("cell").slice(0, -3).map((c) => c.getAttribute("data-heat")));
const href = (name: string) => screen.getByRole("link", { name }).getAttribute("href");

describe("ReportCostMatrix page", () => {
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

  test("the unit: author teams by domain with row totals, foreign work, and the totals row — a real table with a caption and scoped headers", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Cost matrix" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 3, name: "Man-days by author team and domain" })).toBeInTheDocument();
    await screen.findByRole("table");
    expect(matrix().querySelector("caption")).toHaveTextContent(/author teams in rows, domains in columns/);
    expect(rowsOf(matrix())).toEqual([
      ["Alpha", "10", "2.5", "12.5", "3.75", "30%"],
      ["Old team Deleted team", "1", "0", "1", "0", "0%"],
      ["Unassigned", "0.5", "0.5", "1", "0", "0%"],
      ["Total", "11.5", "3", "14.5", "3.75", "25.9%"],
    ]);
    const headers = within(matrix()).getAllByRole("columnheader");
    expect(headers.map((h) => h.textContent)).toEqual(["Author team", "Flow", "Operations", "Total", "Foreign work (MD)", "Foreign share"]);
    for (const header of headers) expect(header).toHaveAttribute("scope", "col");
    for (const header of within(matrix()).getAllByRole("rowheader")) expect(header).toHaveAttribute("scope", "row");
    // A bare request: the server's own defaults (EPIC view, its default period).
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
  });

  test("tiles: the man-days, the foreign man-days and the unit's overall share, with what foreign work means", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table");
    expect(within(tile("Man-days logged")).getByText("14.5")).toBeInTheDocument();
    expect(within(tile("Foreign work (MD)")).getByText("3.75")).toBeInTheDocument();
    const share = tile("Foreign work share");
    expect(within(share).getByText("25.9%")).toBeInTheDocument();
    expect(within(share).getByText("Foreign man-days as a share of everything logged in the period.")).toBeInTheDocument();
    // What counts as foreign work is one plain line under the table.
    expect(screen.getByText(/tasks of another team's sprint, or, when a task has no sprint team, tasks assigned to someone in another team/)).toBeInTheDocument();
  });

  test("the heat scale: intensity follows the figure against the largest cell; nothing logged has no fill and is quiet", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table");
    // The largest cell (10) is the strongest step; 2.5 of 10 is the second of five; any positive figure shows a step.
    expect(heatOf(matrix())).toEqual([
      ["5", "2"],
      ["1", "0"],
      ["1", "1"],
    ]);
    const cells = within(matrix()).getAllByRole("row")[1];
    const [strong, weak] = within(cells).getAllByRole("cell");
    expect(strong.style.backgroundColor).not.toBe("");
    expect(strong.style.backgroundColor).not.toBe(weak.style.backgroundColor);
    const zero = within(within(matrix()).getAllByRole("row")[2]).getAllByRole("cell")[1];
    expect(zero.style.backgroundColor).toBe("");
    // The scale is explained, and the number stays the carrier.
    expect(screen.getByRole("group", { name: "Colour scale" })).toHaveTextContent("up to 10 MD in one cell");
  });

  test("the domain view: EPIC by default (says whose domain), a toggle refetches in the TASK view and back", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table");
    expect(screen.getByRole("radio", { name: "Earned in" })).toBeChecked();
    expect(screen.getByText("Columns show the epic's domain — a task with no epic counts in its own domain.")).toBeInTheDocument();
    expect(rowsOf(matrix())[0]).toEqual(["Alpha", "10", "2.5", "12.5", "3.75", "30%"]);

    await user.click(screen.getByText("Delivered in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}domainView=TASK`));
    expect(await screen.findByText("Columns show the task's own domain, even when its epic belongs to another.")).toBeInTheDocument();
    await waitFor(() => expect(rowsOf(matrix())[0]).toEqual(["Alpha", "8", "4.5", "12.5", "3.75", "30%"]));
    expect(search().get("domainView")).toBe("TASK");

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}domainView=EPIC`));
    await waitFor(() => expect(rowsOf(matrix())[0]).toEqual(["Alpha", "10", "2.5", "12.5", "3.75", "30%"]));
  });

  test("the bar offers period, team, domain, activity type and work category (and a connection only when there is a choice); slices are request params", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table");
    for (const name of ["Period", "Team", "Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}domain=FLO&activityType=Bug`));
  });

  test("a pasted link keeps what the report reads and loses what only other reports own — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage(
      "/reports/cost-matrix?teamId=1&domainView=TASK&domain=FLO&activityType=Bug&workCategory=UNCATEGORIZED&connectionId=1&epicId=FLO-33&breakdown=DOMAIN&bucket=WEEK&by=STATUS&itemKind=EPIC",
    );
    await screen.findByRole("table");
    await waitFor(() =>
      expect(search().toString()).toBe("teamId=1&domainView=TASK&domain=FLO&activityType=Bug&workCategory=UNCATEGORIZED&connectionId=1"),
    );
    expect(calls(mockFetch).at(-1)).toBe(
      `${URL_PREFIX}teamId=1&domainView=TASK&domain=FLO&activityType=Bug&workCategory=UNCATEGORIZED&connectionId=1`,
    );
    expect(calls(mockFetch).some((url) => /epicId|breakdown|bucket|by=|itemKind/.test(url))).toBe(false);
  });

  test("a team row opens its authors, carrying the period; a deleted team is marked and not linked; the unassigned row is linked to the team-less authors", async () => {
    serve(mockFetch);
    renderPage("/reports/cost-matrix?from=2026-09-01&to=2026-09-29&domainView=TASK");
    await screen.findByRole("table");
    expect(href("Show Alpha")).toBe("/reports/cost-matrix?from=2026-09-01&to=2026-09-29&teamId=1&domainView=TASK");
    expect(href("Show Unassigned")).toBe("/reports/cost-matrix?from=2026-09-01&to=2026-09-29&teamId=0&domainView=TASK");
    const deleted = within(matrix()).getByText("Old team").closest("th") as HTMLElement;
    expect(within(deleted).getByText("Deleted team")).toBeInTheDocument();
    expect(within(deleted).queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Show Old team" })).not.toBeInTheDocument();
  });

  test("a sprint-relative period says the team rows read the unit's window; a one-sprint period follows only into a team that lists the sprint; the unassigned row cannot be opened", async () => {
    serve(mockFetch, COST_MATRIX_SPRINTS);
    const { unmount } = renderPage("/reports/cost-matrix?sprintId=11");
    await screen.findByRole("table");
    expect(screen.getByText(/Each team row reads the whole unit's sprint window/)).toBeInTheDocument();
    expect(href("Show Alpha")).toBe("/reports/cost-matrix?sprintId=11&teamId=1");
    // A team-less drill resolves no sprint and would answer empty: shown, not linked.
    expect(screen.queryByRole("link", { name: "Show Unassigned" })).not.toBeInTheDocument();
    expect(within(matrix()).getByText("Unassigned")).toBeInTheDocument();
    unmount();

    // Sprint 21 is Beta's: Alpha does not list it, so the drill falls back to the default period.
    renderPage("/reports/cost-matrix?sprintId=21");
    await screen.findByRole("table");
    expect(href("Show Alpha")).toBe("/reports/cost-matrix?teamId=1");
  });

  test("a last-N-sprints period travels into a team (its own last N)", async () => {
    serve(mockFetch, COST_MATRIX_SPRINTS);
    renderPage("/reports/cost-matrix?lastSprints=3");
    await screen.findByRole("table");
    expect(href("Show Alpha")).toBe("/reports/cost-matrix?lastSprints=3&teamId=1");
  });

  test("a team read of a sprint-relative period says the sprints are the team's own", async () => {
    serve(mockFetch, { ...COST_MATRIX_TEAM, meta: { ...COST_MATRIX_TEAM.meta, from: null, to: null } });
    renderPage("/reports/cost-matrix?lastSprints=3&teamId=1");
    await screen.findByRole("table");
    expect(screen.getByText("The period follows sprints: this team's own sprints.")).toBeInTheDocument();
  });

  test("the authors of the team-less bucket are not linked under a sprint-relative period, and are under a date period", async () => {
    const noTeam = (from: string | null) => ({
      ...COST_MATRIX_TEAM,
      meta: { ...COST_MATRIX_TEAM.meta, from, to: from },
      rows: COST_MATRIX_TEAM.rows.map((row) => ({ ...row, teamId: 0 })),
    });
    serve(mockFetch, noTeam(null));
    const { unmount } = renderPage("/reports/cost-matrix?lastSprints=3&teamId=0");
    await screen.findByRole("table");
    expect(screen.queryByRole("link", { name: "Show Ann Author" })).not.toBeInTheDocument();
    expect(within(matrix()).getByText("Ann Author")).toBeInTheDocument();
    unmount();

    serve(mockFetch, noTeam("2026-09-01"));
    renderPage("/reports/cost-matrix?from=2026-09-01&to=2026-09-29&teamId=0");
    await screen.findByRole("table");
    expect(href("Show Ann Author")).toBe("/reports/cost-matrix?from=2026-09-01&to=2026-09-29&teamId=0&accountId=acc-ann");
  });

  test("the table sits in a focusable, named region so the keyboard can scroll it", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table");
    const region = screen.getByRole("region", { name: /author teams in rows, domains in columns/ });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(region).toContainElement(matrix());
  });

  test("a date period does not say anything about sprints", async () => {
    serve(mockFetch);
    renderPage("/reports/cost-matrix?from=2026-09-01&to=2026-09-29");
    await screen.findByRole("table");
    expect(screen.queryByText(/sprint window/)).not.toBeInTheDocument();
  });

  test("drilling: a team is its authors (the unknown author unlinked), an author is one row with nothing further to open", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/cost-matrix?lastSprints=3");
    await screen.findByRole("table");
    await user.click(screen.getByRole("link", { name: "Show Alpha" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Man-days by author and domain" })).toBeInTheDocument();
    await waitFor(() => expect(within(matrix()).getAllByRole("columnheader")[0]).toHaveTextContent("Author"));
    expect(within(matrix()).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Author",
      "Flow",
      "(no domain)",
      "Total",
      "Foreign work (MD)",
      "Foreign share",
    ]);
    expect(rowsOf(matrix())).toEqual([
      ["Ann Author", "4", "0", "4", "1", "25%"],
      ["Bob Builder", "2", "0", "2", "0", "0%"],
      ["No known author", "0", "1", "1", "0", "0%"],
      ["Total", "6", "1", "7", "1", "14.3%"],
    ]);
    // A date period says nothing about sprints.
    expect(screen.queryByText(/The period follows sprints/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Show No known author" })).not.toBeInTheDocument();
    expect(search().get("teamId")).toBe("1");
    expect(href("Show Ann Author")).toBe("/reports/cost-matrix?lastSprints=3&teamId=1&accountId=acc-ann");

    await user.click(screen.getByRole("link", { name: "Show Ann Author" }));
    expect(await screen.findByRole("heading", { level: 3, name: "Man-days by domain" })).toBeInTheDocument();
    await waitFor(() => expect(rowsOf(matrix())).toEqual([["Ann Author", "4", "4", "1", "25%"], ["Total", "4", "4", "1", "25%"]]));
    expect(within(matrix()).queryByRole("link")).not.toBeInTheDocument();
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&teamId=1&accountId=acc-ann`);
  });

  test("totals are the server's own rounded sums, never re-added from the cells shown, and the footnote says why", async () => {
    serve(mockFetch, COST_MATRIX_THIRDS);
    renderPage();
    await screen.findByRole("table");
    // Three cells of 0.33 and a row total of 1 (not 0.99) — every column reads its own sum too.
    expect(rowsOf(matrix())).toEqual([
      ["Alpha", "0.33", "0.33", "0.33", "1", "0", "0%"],
      ["Total", "0.33", "0.33", "0.33", "1", "0", "0%"],
    ]);
    // A domain no row names falls back to its key.
    expect(within(matrix()).getAllByRole("columnheader").map((h) => h.textContent).slice(1, 4)).toEqual(["Alfa", "Bravo", "C"]);
    expect(screen.getByText(/Every figure is its own exact sum, rounded once to two decimals/)).toHaveTextContent("up to 0.005 for each of them");
  });

  test("worklogs that add up to nothing: rows exist, the share is a dash with its reason — never 0%", async () => {
    serve(mockFetch, COST_MATRIX_NO_SHARE);
    renderPage();
    await screen.findByRole("table");
    expect(rowsOf(matrix())).toEqual([
      ["Alpha", "0", "0", "0", "—"],
      ["Total", "0", "0", "0", "—"],
    ]);
    const share = tile("Foreign work share");
    expect(within(share).getByText("—")).toBeInTheDocument();
    expect(within(share).getByText("Nothing was logged, so there is no share.")).toBeInTheDocument();
    expect(heatOf(matrix())).toEqual([["0"]]);
    // With every cell 0 there is no scale to explain.
    expect(screen.queryByRole("group", { name: "Colour scale" })).not.toBeInTheDocument();
  });

  test("nothing logged in the period is the empty state, with no table", async () => {
    serve(mockFetch, COST_MATRIX_EMPTY);
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Man-days logged" })).not.toBeInTheDocument();
  });

  test("a rejected slice (an inactive team, an unknown sprint) is the normal failure alert", async () => {
    serve(mockFetch, { title: "unknown team", status: 400 }, 400);
    renderPage("/reports/cost-matrix?teamId=99");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("the cost matrix is a group of one: no report tabs", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table");
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });
});
