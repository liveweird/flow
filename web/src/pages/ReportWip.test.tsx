import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportWip from "./ReportWip";
import { jsonResponse } from "../test/http";
import { FILTERS, WIP_COLUMN, WIP_EPICS, WIP_NOT_DERIVED, WIP_STAGE, WIP_STATUS, WIP_USER } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  AreaChart: (props: {
    data: Record<string, string | number>[];
    series: { name: string; label: string; color: string }[];
    type: string;
    withLegend: boolean;
  }) => (
    <div
      data-testid="area-chart"
      data-type={props.type}
      data-legend={String(props.withLegend)}
      data-days={props.data.map((r) => r.day).join("|")}
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/wip?";

function report(url: string) {
  const params = new URL(url, "http://x").searchParams;
  if (params.getAll("accountId").length > 0) return WIP_USER;
  if (params.get("itemKind") === "EPIC") return WIP_EPICS;
  if (params.get("by") === "STATUS") return WIP_STATUS;
  if (params.get("by") === "COLUMN") return WIP_COLUMN;
  return WIP_STAGE;
}

function serve(mockFetch: FetchMock, respond?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(respond?.(url) ?? jsonResponse(200, report(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/wip") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/wip" element={<ReportWip />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const chartSeries = () => JSON.parse(screen.getByTestId("area-chart").getAttribute("data-series")!) as { name: string; label: string; color: string }[];

describe("ReportWip page", () => {
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

  test("asks for tasks by stage explicitly and stacks the in-progress, waiting and blocked work, hiding Done and Not started at first", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "WIP" })).toBeInTheDocument();
    const chart = await screen.findByTestId("area-chart");
    // A bare link is never read for the whole unit: it starts on the first team by name.
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);

    expect(chart.getAttribute("data-type")).toBe("stacked");
    // Stage colours are the app's vocabulary: blue in progress, grape waiting and red blocked (both shown by default), orange unmapped.
    expect(chartSeries()).toEqual([
      { name: "b1", label: "In progress", color: "flow.6" },
      { name: "b2", label: "Waiting", color: "grape.6" },
      { name: "b3", label: "Blocked", color: "red.7" },
      { name: "b5", label: "Unmapped status", color: "orange.8" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(JSON.parse(chart.getAttribute("data-rows")!)[4]).toEqual({ day: "2026-09-29", b1: 9, b2: 4, b3: 3, b5: 1 });
    expect(screen.getByRole("group", { name: "Show in chart" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "In progress" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Waiting" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Blocked" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Done" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Not started" })).not.toBeChecked();
    expect(screen.getByText(/Not started and Done are hidden at first/)).toBeInTheDocument();
  });

  test("ticking a band adds it to the chart; ticking none says so instead of drawing an empty frame", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await user.click(screen.getByRole("checkbox", { name: "Done" }));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["In progress", "Waiting", "Blocked", "Done", "Unmapped status"]));
    expect(chartSeries()[3].color).toBe("teal.8");

    await user.click(screen.getByRole("checkbox", { name: "In progress" }));
    await user.click(screen.getByRole("checkbox", { name: "Waiting" }));
    await user.click(screen.getByRole("checkbox", { name: "Blocked" }));
    await user.click(screen.getByRole("checkbox", { name: "Done" }));
    await user.click(screen.getByRole("checkbox", { name: "Unmapped status" }));
    expect(await screen.findByText("Tick at least one band to draw the chart.")).toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
    // The numbers stay in the tables, ticked or not.
    expect(screen.getByRole("table", { name: "Latest day, average and peak per band, as a table" })).toBeInTheDocument();
  });

  test("a single ticked band has no legend", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await user.click(screen.getByRole("checkbox", { name: "Waiting" }));
    await user.click(screen.getByRole("checkbox", { name: "Blocked" }));
    await user.click(screen.getByRole("checkbox", { name: "Unmapped status" }));
    await waitFor(() => expect(screen.getByTestId("area-chart").getAttribute("data-legend")).toBe("false"));
  });

  test("working days only drops the weekend points from the chart, not from the tables", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const chart = await screen.findByTestId("area-chart");
    expect(chart.getAttribute("data-days")).toBe("2026-09-25|2026-09-26|2026-09-27|2026-09-28|2026-09-29");
    await user.click(screen.getByRole("switch", { name: "Working days only" }));
    await waitFor(() => expect(screen.getByTestId("area-chart").getAttribute("data-days")).toBe("2026-09-25|2026-09-28|2026-09-29"));
    await user.click(screen.getByRole("button", { name: "Show daily figures" }));
    expect(within(screen.getByRole("table", { name: "Daily figures, as a table" })).getAllByRole("row")).toHaveLength(6);
  });

  test("the on-screen-only view controls stay off paper, and the print header states the working-days switch", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    const printed = () => document.querySelector('[data-print="only"]');
    expect(printed()).toHaveTextContent("Working days only: Off");
    const hidden = document.querySelectorAll('[data-print="hide"]');
    expect(Array.from(hidden).some((el) => el.contains(screen.getByRole("checkbox", { name: "Blocked" })))).toBe(true);
    expect(Array.from(hidden).some((el) => el.contains(screen.getByRole("switch", { name: "Working days only" })))).toBe(true);
    await user.click(screen.getByRole("switch", { name: "Working days only" }));
    expect(printed()).toHaveTextContent("Working days only: On");
  });

  test("the summary table gives every band's latest day, average and peak — hidden bands too", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    const table = screen.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(table).getByRole("columnheader", { name: "Latest day (2026-09-29)" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Not started", "38", "39.4", "40"],
      ["In progress", "9", "7", "9"],
      ["Waiting", "4", "2.6", "4"],
      ["Blocked", "3", "1.6", "3"],
      ["Done", "102", "100.6", "102"],
      ["Unmapped status", "1", "1", "1"],
    ]);
  });

  test("the daily table sits behind a disclosure and lists the newest day first", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.queryByRole("table", { name: "Daily figures, as a table" })).not.toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Show daily figures" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    expect(screen.getByRole("button", { name: "Hide daily figures" })).toHaveAttribute("aria-expanded", "true");
    const table = screen.getByRole("table", { name: "Daily figures, as a table" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows[0]).toEqual(["2026-09-29", "38", "9", "4", "3", "102", "1"]);
    expect(rows).toHaveLength(5);
  });

  test("the by toggle refetches with the new key and shows the statuses, all ticked", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
    await user.click(screen.getByText("Status"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&by=STATUS&itemKind=TASK`));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["To Do", "In Progress", "Done"]));
    // Arbitrary status names wear the categorical palette by position — never teal/orange/red/grape.
    expect(chartSeries().map((s) => s.color)).toEqual(["flow.6", "violet.5", "cyan.7"]);
    // Colours follow the bands SHOWN: hide the first and the rest move up, still all different hues.
    await user.click(screen.getByRole("checkbox", { name: "To Do" }));
    await waitFor(() => expect(chartSeries().map((s) => [s.label, s.color])).toEqual([["In Progress", "flow.6"], ["Done", "violet.5"]]));
    expect(screen.getByRole("radio", { name: "Status" })).toBeChecked();
  });

  test("the board column key needs one team: disabled while a domain is read with the reason, enabled for a team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?domain=FLO");
    await screen.findByTestId("area-chart");
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Board column" })).toBeDisabled();
    expect(screen.getByText(/Board columns need one team/)).toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Board column" })).toBeEnabled());
    expect(screen.queryByText(/Board columns need one team/)).not.toBeInTheDocument();
    await user.click(screen.getByText("Board column"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&by=COLUMN&itemKind=TASK`));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["Backlog", "Doing", "(no column)"]));
  });

  test("a link asking for columns without a team is asked as stage, never a doomed 400", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?domain=FLO&by=COLUMN");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&by=STAGE&itemKind=TASK`]);
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
  });

  test("a link with a team AND a domain is read with the team winning — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?teamId=1&domain=FLO");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("domain")).toBe(false));
    expect(search().get("teamId")).toBe("1");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
    await openReportFilters();
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("");
  });

  test("a pasted link with an activity type or work category is read without them — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?teamId=1&activityType=Bug&workCategory=Run");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("activityType")).toBe(false));
    expect(search().has("workCategory")).toBe(false);
    expect(search().get("teamId")).toBe("1");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
  });

  test("a column keying the filter cannot honour leaves the URL too, so a later team pick does not revive it", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?domain=FLO&by=COLUMN");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("by")).toBe(false));
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`));
    expect(calls(mockFetch).some((url) => url.includes("by=COLUMN"))).toBe(false);
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
  });

  test("a 400 that is not a column request is a plain failure, even when the link once asked for columns", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "unknown sprint" }));
    renderPage("/reports/wip?domain=FLO&sprintId=999&by=COLUMN");
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (400)");
    expect(screen.queryByText(/This team has no board mapped/)).not.toBeInTheDocument();
  });

  test("the ticked bands of a board are that team's own: another team's columns start afresh", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?teamId=1&by=COLUMN");
    await waitFor(() => expect(chartSeries()).toHaveLength(3));
    await user.click(screen.getByRole("checkbox", { name: "Doing" }));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["Backlog", "(no column)"]));
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["Backlog", "Doing", "(no column)"]));
  });

  test("the summary says it covers every day, whatever the working-days switch shows", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByText("These figures cover every calendar day of the period, whatever the chart shows.")).toBeInTheDocument();
  });

  test("a team without a mapped board gets the reason, not a failure", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "no board" }));
    renderPage("/reports/wip?teamId=2&by=COLUMN");
    expect(await screen.findByText(/This team has no board mapped/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  test("the item kind defaults to tasks and switches to epics; there is no combined option", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.queryByRole("radio", { name: "Tasks and epics" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("radio", { name: /^(Tasks|Epics)$/ })).toHaveLength(2);
    await user.click(screen.getByText("Epics"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&by=STAGE&itemKind=EPIC`));
  });

  test("a link or stored filter with itemKind=BOTH falls back to tasks, in the request and the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?teamId=1&itemKind=BOTH");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.queryByRole("radio", { name: "Tasks and epics" })).not.toBeInTheDocument();
    await waitFor(() => expect(search().get("itemKind")).toBeNull());
  });

  test("the filters round-trip into the query string, in canonical order", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?itemKind=EPIC&teamId=1&to=2026-09-29&from=2026-09-01&by=STATUS");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}from=2026-09-01&to=2026-09-29&teamId=1&by=STATUS&itemKind=EPIC`]);
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Status" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
  });

  test("a bare link with a remembered team is already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    await waitFor(() => expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
  });

  test("offers the domain, but no domain view, activity type, work category or bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Domain" })).toBeInTheDocument();
    for (const name of ["Activity type", "Work category"]) expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
  });

  test("not derived yet: the empty state, the server's note under a title, and the meta line", async () => {
    serve(mockFetch, () => jsonResponse(200, WIP_NOT_DERIVED));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    // Said once, in the viewer's language: the meta line — not also the server's English note.
    expect(screen.getByText("No data has been derived yet.")).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.queryByText(/Not derived yet/)).not.toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  test("any other server note is shown verbatim under a translated title", async () => {
    const note = "The sprint-relative period resolved no sprint, so there is nothing to read";
    serve(mockFetch, () => jsonResponse(200, { ...WIP_NOT_DERIVED, meta: WIP_STAGE.meta, note }));
    renderPage("/reports/wip?lastSprints=3");
    const box = await screen.findByRole("note");
    expect(within(box).getByText("About this data")).toBeInTheDocument();
    expect(within(box).getByText(note)).toBeInTheDocument();
  });

  test("Flow is a tab group: WIP is selected beside the other four", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("tab", { name: "WIP" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["WIP", "Estimated backlog", "Aging WIP", "Blocked time", "Epic progress"]);
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});

/** The scope rule (`teamRequired` + `teamOrDomain`): exactly one of a specific team or a specific domain, never neither. */
describe("ReportWip page — a team or a domain", () => {
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

  test("a link naming neither a team nor a domain gets the default team, URL included, and never asks for the whole unit", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?lastSprints=3");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).every((url) => url.includes("teamId=1"))).toBe(true);
    // The unassigned bucket (teamId=0) is no specific team either.
    expect(calls(mockFetch).some((url) => url.includes("teamId=0"))).toBe(false);
  });

  test("an unassigned-bucket link (teamId=0) is read for the default team instead", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?teamId=0");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).some((url) => url.includes("teamId=0"))).toBe(false);
  });

  test("the team control offers no All teams and cannot be cleared", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: /^Team/ });
    expect((team as HTMLInputElement).value).toBe("Alpha");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect(team).not.toHaveAttribute("placeholder");
    await user.click(team);
    expect((await screen.findAllByRole("option")).map((option) => option.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
  });

  test("a domain alone is a valid scope: the request names no team, and the team control shows none", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?domain=FLO");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&by=STAGE&itemKind=TASK`]);
    expect(search().has("teamId")).toBe(false);
    await openReportFilters();
    const team = screen.getByRole("combobox", { name: "Team" });
    expect((team as HTMLInputElement).value).toBe("");
    expect(team).toHaveAttribute("placeholder", "No team: a domain is picked");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("Flow");
  });

  test("picking a team replaces the domain, and clearing the domain falls back to the default team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?domain=FLO&lastSprints=3");
    await screen.findByTestId("area-chart");
    await openReportFilters();
    // Clearing the domain returns to a team — the first by name, as nothing is remembered.
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(search().has("domain")).toBe(false);
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&teamId=1&by=STAGE&itemKind=TASK`));
    // ... and a domain picked again drops the team (no team x domain split).
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}lastSprints=3&domain=FLO&by=STAGE&itemKind=TASK`));
    expect(search().has("teamId")).toBe(false);
  });

  test("a domain stands in for the remembered team without erasing it: clearing the domain returns to that team", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().get("teamId")).toBe("2"));
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(search().get("domain")).toBe("FLO"));
    expect(search().has("teamId")).toBe(false);
    expect(localStorage.getItem("flow.viewSettings.reports.teamId")).toBe("2");
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(search().get("teamId")).toBe("2"));
    expect(search().has("domain")).toBe(false);
  });

  test("with no team and no domain at all there is nothing to read: the empty state, no request", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [], domains: [] })
          : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    renderPage();
    expect(await screen.findByText(/WIP is read for one team or one domain/)).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("button", { name: /^Filters/ })).not.toBeInTheDocument();
  });

  test("with domains but no team the bar stays up and asks for a pick instead of a request", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters"
          ? jsonResponse(200, { ...FILTERS, teams: [] })
          : url.startsWith(URL_PREFIX)
            ? jsonResponse(200, WIP_STAGE)
            : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText("Pick a team or a domain to read WIP for.")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    expect(await screen.findByTestId("area-chart")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&by=STAGE&itemKind=TASK`]);
  });

  test("picking a domain under a column keying drops the team and the keying, and picking a team again stays on stage", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?teamId=1&by=COLUMN");
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["Backlog", "Doing", "(no column)"]));
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(search().has("by")).toBe(false));
    expect(search().has("teamId")).toBe(false);
    expect(search().get("domain")).toBe("FLO");
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=2&by=STAGE&itemKind=TASK`));
    expect(search().has("domain")).toBe(false);
  });
});

/** USER level: the member control picks up to eight people; the server answers their tasks, split per person in `byUser`. */
describe("ReportWip page — several people", () => {
  let mockFetch: FetchMock;
  const chartRows = () => JSON.parse(screen.getByTestId("area-chart").getAttribute("data-rows")!) as Record<string, number | string>[];

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

  test("the member control picks several people: repeated accountId in the request, in pick order", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?teamId=1");
    await screen.findByTestId("area-chart");
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Member" }));
    await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&accountId=acc-bob&by=STAGE&itemKind=TASK`));
    await user.click(await screen.findByRole("option", { name: "Ann Author" }));
    await waitFor(() => expect(search().getAll("accountId")).toEqual(["acc-bob", "acc-ann"]));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=1&accountId=acc-bob&accountId=acc-ann&by=STAGE&itemKind=TASK`));
  });

  test("two people: the union by band first, then one stacked segment per person over the ticked bands, numbered", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?teamId=1&accountId=acc-ann&accountId=acc-bob");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&accountId=acc-ann&accountId=acc-bob&by=STAGE&itemKind=TASK`]);
    // By band (the default): the union, exactly as at the other levels (In progress + Waiting + Blocked + Unmapped ticked).
    expect(chartSeries().map((s) => s.label)).toEqual(["In progress", "Waiting", "Blocked", "Unmapped status"]);
    expect(chartRows()[1]).toEqual({ day: "2026-09-29", b1: 4, b2: 2, b3: 0, b5: 0 });
    expect(screen.getByText(/Counts the tasks the picked people hold at the end of each day/)).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "By person" }));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["1. Ann Author", "2. Bob Builder"]));
    expect(chartSeries().map((s) => s.color)).toEqual(["flow.6", "violet.5"]);
    // Each person's items over the ticked bands (In progress + Waiting): Ann 2 then 4, Bob 2 then 2.
    expect(chartRows()).toEqual([
      { day: "2026-09-28", p0: 2, p1: 2 },
      { day: "2026-09-29", p0: 4, p1: 2 },
    ]);
    expect(screen.getByRole("group", { name: /stacked by person/ })).toBeInTheDocument();
    expect(screen.getByText(/One segment per person/)).toBeInTheDocument();

    // The text alternatives gain the person dimension and follow the ticked bands.
    const summary = screen.getByRole("table", { name: "Latest day, average and peak per person, as a table" });
    const rows = within(summary).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["1. Ann Author", "4", "3", "4"],
      ["2. Bob Builder", "2", "2", "2"],
    ]);
    await user.click(screen.getByRole("button", { name: "Show daily figures" }));
    const daily = screen.getByRole("table", { name: "Daily figures per person, as a table" });
    const dailyRows = within(daily).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(dailyRows).toEqual([["2026-09-29", "4", "2"], ["2026-09-28", "2", "2"]]);

    // Ticking Done adds it to every person's total.
    await user.click(screen.getByRole("checkbox", { name: "Done" }));
    await waitFor(() => expect(chartRows()[1]).toEqual({ day: "2026-09-29", p0: 7, p1: 5 }));
    // Back to the bands.
    await user.click(screen.getByRole("radio", { name: "By band" }));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["In progress", "Waiting", "Blocked", "Done", "Unmapped status"]));
  });

  test("one person is the union itself: no view switch, the page reads as at TEAM level", async () => {
    const one = { ...WIP_USER, series: WIP_USER.series.map((point) => ({ ...point, byUser: point.byUser?.slice(0, 1) })) };
    serve(mockFetch, () => jsonResponse(200, one));
    renderPage("/reports/wip?teamId=1&accountId=acc-ann");
    await screen.findByTestId("area-chart");
    expect(screen.queryByRole("radio", { name: "By person" })).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Latest day, average and peak per band, as a table" })).toBeInTheDocument();
  });

  test("a domain read offers no member control, and a link naming people without a team is asked without them (the server needs a team)", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?domain=FLO&accountId=acc-ann");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO&by=STAGE&itemKind=TASK`]);
    await openReportFilters();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
  });

  test("an EPIC read at USER level shows the server's note about the epics left out", async () => {
    const note = "USER level counts the picked people's tasks only: an epic has no assignee, so epics are left out";
    serve(mockFetch, () => jsonResponse(200, { ...WIP_USER, itemKind: "EPIC", note }));
    renderPage("/reports/wip?teamId=1&accountId=acc-ann&accountId=acc-bob&itemKind=EPIC");
    const box = await screen.findByRole("note");
    expect(within(box).getByText(note)).toBeInTheDocument();
  });
});
