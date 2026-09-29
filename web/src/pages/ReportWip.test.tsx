import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportWip from "./ReportWip";
import { jsonResponse } from "../test/http";
import { FILTERS, WIP_BOTH, WIP_COLUMN, WIP_EPICS, WIP_NOT_DERIVED, WIP_STAGE, WIP_STATUS } from "../test/reportFixtures";
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
  if (params.get("itemKind") === "EPIC") return WIP_EPICS;
  if (params.get("itemKind") === "BOTH") return WIP_BOTH;
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

  test("asks for tasks by stage explicitly and stacks the in-progress work, hiding Done and Not started at first", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "WIP" })).toBeInTheDocument();
    const chart = await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}by=STAGE&itemKind=TASK`]);

    expect(chart.getAttribute("data-type")).toBe("stacked");
    // Stage colours are the app's vocabulary: blue in progress, orange unmapped.
    expect(chartSeries()).toEqual([
      { name: "b1", label: "In progress", color: "flow.6" },
      { name: "b3", label: "Unmapped status", color: "orange.8" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(JSON.parse(chart.getAttribute("data-rows")!)[4]).toEqual({ day: "2026-09-29", b1: 9, b3: 1 });
    expect(screen.getByRole("group", { name: "Show in chart" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "In progress" })).toBeChecked();
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
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["In progress", "Done", "Unmapped status"]));
    expect(chartSeries()[1].color).toBe("teal.8");

    await user.click(screen.getByRole("checkbox", { name: "In progress" }));
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

  test("the summary table gives every band's latest day, average and peak — hidden bands too", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    const table = screen.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    expect(within(table).getByRole("columnheader", { name: "Latest day (2026-09-29)" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Not started", "38", "39.4", "40"],
      ["In progress", "9", "7", "9"],
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
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows[0]).toEqual(["2026-09-29", "38", "9", "102", "1"]);
    expect(rows).toHaveLength(5);
  });

  test("the by toggle refetches with the new key and shows the statuses, all ticked", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
    await user.click(screen.getByText("Status"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}by=STATUS&itemKind=TASK`));
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["To Do", "In Progress", "Done"]));
    // Arbitrary status names wear neutral blue/gray, alternating by position — never teal/orange/red.
    expect(chartSeries().map((s) => s.color)).toEqual(["flow.6", "gray.6", "flow.7"]);
    // Colours follow the bands SHOWN: hide the first and the rest move up, still alternating hues.
    await user.click(screen.getByRole("checkbox", { name: "To Do" }));
    await waitFor(() => expect(chartSeries().map((s) => [s.label, s.color])).toEqual([["In Progress", "flow.6"], ["Done", "gray.6"]]));
    expect(screen.getByRole("radio", { name: "Status" })).toBeChecked();
  });

  test("the board column key needs one team: disabled at unit level with the reason, enabled for a team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
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
    renderPage("/reports/wip?lastSprints=3&by=COLUMN");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&by=STAGE&itemKind=TASK`]);
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
  });

  test("a link with a team AND a domain is read with the team winning — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?teamId=1&domain=FLO");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("domain")).toBe(false));
    expect(search().get("teamId")).toBe("1");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
    expect((screen.getByRole("combobox", { name: "Domain" }) as HTMLInputElement).value).toBe("");
  });

  test("a column keying the filter cannot honour leaves the URL too, so a later team pick does not revive it", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?lastSprints=3&by=COLUMN");
    await screen.findByTestId("area-chart");
    await waitFor(() => expect(search().has("by")).toBe(false));
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}lastSprints=3&teamId=1&by=STAGE&itemKind=TASK`));
    expect(calls(mockFetch).some((url) => url.includes("by=COLUMN"))).toBe(false);
    expect(screen.getByRole("radio", { name: "Stage" })).toBeChecked();
  });

  test("clearing the team under a column keying drops the keying from the URL, and picking one again stays on stage", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/wip?teamId=1&by=COLUMN");
    await waitFor(() => expect(chartSeries().map((s) => s.label)).toEqual(["Backlog", "Doing", "(no column)"]));
    await user.click(screen.getByLabelText("Clear Team"));
    await waitFor(() => expect(search().has("by")).toBe(false));
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=2&by=STAGE&itemKind=TASK`));
  });

  test("a 400 that is not a column request is a plain failure, even when the link once asked for columns", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "unknown sprint" }));
    renderPage("/reports/wip?sprintId=999&by=COLUMN");
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

  test("the item kind defaults to tasks and switches to epics and to both, which adds the grain warning", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.queryByText(/different grains/)).not.toBeInTheDocument();
    await user.click(screen.getByText("Epics"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}by=STAGE&itemKind=EPIC`));
    await user.click(screen.getByText("Tasks and epics"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}by=STAGE&itemKind=BOTH`));
    expect(await screen.findByText(/different grains/)).toBeInTheDocument();
  });

  test("the filters round-trip into the query string, in canonical order", async () => {
    serve(mockFetch);
    renderPage("/reports/wip?itemKind=EPIC&teamId=1&to=2026-09-29&from=2026-09-01&by=STATUS");
    await screen.findByTestId("area-chart");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}from=2026-09-01&to=2026-09-29&teamId=1&by=STATUS&itemKind=EPIC`]);
    expect(screen.getByRole("radio", { name: "Status" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
  });

  test("a bare link with a remembered team is already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    await waitFor(() => expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1&by=STAGE&itemKind=TASK`]);
  });

  test("offers the domain, but no domain view, activity type, work category or bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
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

  test("Flow is a tab group: WIP is selected beside the other three", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("area-chart");
    expect(screen.getByRole("tab", { name: "WIP" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["WIP", "Estimated backlog", "Aging WIP", "Blocked time"]);
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});
