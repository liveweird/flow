import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import type { DeepDiveReport } from "../api/reports";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import i18n from "../i18n";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import ReportDeepDive from "./ReportDeepDive";

// recharts draws nothing under happy-dom; the burn-up chart is asserted through its props.
vi.mock("@mantine/charts", () => ({
  LineChart: (props: { data: unknown; series: unknown }) => (
    <div data-testid="line-chart" data-rows={JSON.stringify(props.data)} data-series={JSON.stringify(props.series)} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PATH = "/api/v1/reports/deep-dive";
const LAYERS_KEY = "flow.viewSettings.reports.deepDive.layers";
const BUDGET_KEY = "flow.viewSettings.reports.deepDive.burnupBudget";
const FOLDED_KEY = "flow.viewSettings.reports.deepDive.selectionFolded";
/** The viewer's stored choice to keep the Selection form open (it folds by default over a shown selection). */
const keepFormOpen = () => localStorage.setItem(FOLDED_KEY, "false");

/** One epic with one task: plan, approximate EV, a done day and cost, over the month-crossing range. */
function sampleReport(partial: Partial<DeepDiveReport> = {}): DeepDiveReport {
  return deepDiveReport({
    ...MONTH_CROSSING,
    mode: "SPRINTS",
    sprints: [{ id: 41, name: "Sprint 4", startDay: 0, endDay: 12 }],
    authors: [{ accountId: "a1", displayName: "Ann Lee" }],
    epics: [deepDiveEpic("FLO-1", { summary: "Onboarding" })],
    tasks: [
      deepDiveTask("FLO-11", {
        summary: "Sign-up form",
        epicKey: "FLO-1",
        planBasisMd: 2,
        planSource: "EARLIEST",
        pv: [{ d: 0, md: 2 }],
        approxEv: [{ d: 4, md: 2 }],
        inProgress: [4],
        evState: "EARNED",
        cost: [{ d: 5, a: 0, md: 1.5 }],
        done: { d: 6, evMd: 2 },
        totals: { pvMd: 2, evMd: 2, costMd: 1.5 },
      }),
    ],
    ...partial,
  });
}

function serve(mockFetch: FetchMock, report: unknown = sampleReport(), status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(`${REPORT_PATH}?`)) return Promise.resolve(jsonResponse(status, report));
    if (url.startsWith(`${REPORT_PATH}/epics?`)) {
      const items = [{ id: 1, connectionId: 1, key: "FLO-1", summary: "Onboarding", domain: "FLO" }];
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: 1 }));
    }
    if (url.startsWith(`${REPORT_PATH}/`)) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  const navigate = useNavigate();
  return (
    <>
      <output data-testid="search">{useLocation().search}</output>
      <button type="button" onClick={() => void navigate(-1)}>
        go back
      </button>
    </>
  );
}

function renderPage(route = "/reports/deep-dive") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/deep-dive" element={<ReportDeepDive />} />
      </Routes>
      <Link to={LINK_A}>go to A</Link>
      <Link to={LINK_B}>go to B</Link>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => screen.getByTestId("search").textContent ?? "";
const reportCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(`${REPORT_PATH}?`));
const SPRINT_LINK = "/reports/deep-dive?sprintId=42&domain=FLO&sprintId=41";
const LINK_A = SPRINT_LINK;
const LINK_B = "/reports/deep-dive?epicId=FLO-1";

describe("ReportDeepDive page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve(mockFetch);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("without a selection it explains the two ways in and requests no report", async () => {
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Deep dive" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 3, name: "Pick something to dive into" })).toBeInTheDocument();
    const explainer = screen.getByRole("heading", { name: "Pick something to dive into" }).closest("section") as HTMLElement;
    expect(within(explainer).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Sprints of a domain: the tasks of one domain that were in scope at the close of the sprints you pick (up to 52 sprints).",
      "Epics: every task under the epics you pick (up to 50 epics, 500 tasks in all); pick a domain first, it narrows the list to its epics.",
    ]);
    // The selection panel is there to make one.
    expect(await screen.findByRole("button", { name: "Show" })).toHaveAttribute("aria-disabled", "true");
    expect(reportCalls(mockFetch)).toEqual([]);
  });

  test("an incomplete selection in the link (sprints without a domain) reads as no selection", async () => {
    renderPage("/reports/deep-dive?sprintId=41");
    expect(await screen.findByRole("heading", { name: "Pick something to dive into" })).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([]);
  });

  test("a selection in the URL is requested in its canonical form and drawn as a matrix", async () => {
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("grid", { name: "Plan, earned value and cost by epic and time" })).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?domain=FLO&sprintId=41&sprintId=42`]);
    expect(screen.getByText("Showing 2026-08-27 to 2026-09-08.")).toBeInTheDocument();
    // One outline: the title, then the selection, the matrix (its own summary block one level down) and the limits.
    expect(headingOutline()).toEqual([
      [2, "Deep dive"],
      [3, "Selection"],
      [3, "Plan, earned value and cost"],
      [4, "Totals per epic and task"],
      [3, "Data limits"],
    ]);
  });

  test("over a selection in the link the panel is folded to a one-line summary that names its sprints from the answered report (an unknown one reads by id)", async () => {
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(await screen.findByText("Flow · Sprints: Sprint 4, 42")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();
  });

  test("Edit selection opens the form on the link's selection, and the same toggle folds it again", async () => {
    const user = userEvent.setup();
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    await user.click(await screen.findByRole("button", { name: "Edit selection" }));
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "true");
    expect(await screen.findByText("Sprint 4")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Sprints of a domain" })).toBeChecked();
    expect(screen.getByText("2 of 52 selected.")).toBeInTheDocument();
    expect(screen.queryByText("Flow · Sprints: Sprint 4, 42")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();
  });

  test("while the report loads there is a loader, never a stale grid", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/reports/filters" ? Promise.resolve(jsonResponse(200, FILTERS)) : new Promise(() => {}),
    );
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
  });

  test("nothing derived yet is a note in the viewer's language and no grid", async () => {
    serve(
      mockFetch,
      deepDiveReport({
        range: { from: "2026-09-01", to: "2026-09-01", asOfDay: null },
        meta: { ...sampleReport().meta, derivedAt: null, configRevision: null },
        note: "Not derived yet: the connection has not completed a DERIVE.",
      }),
    );
    renderPage(SPRINT_LINK);
    expect(await screen.findByText(/Nothing in this selection has been derived yet/)).toBeInTheDocument();
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    expect(screen.queryByText(/Not derived yet:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Data limits" })).not.toBeInTheDocument();
  });

  test("a clamped range says so, in words, above the matrix", async () => {
    serve(mockFetch, sampleReport({ note: "RANGE_CLAMPED: the implied range spans 2000 days; the last 1100 are shown." }));
    renderPage(SPRINT_LINK);
    const note = await screen.findByText(/only its last 1100 days are drawn/);
    expect(note.closest('[role="note"]')).toHaveClass("mantine-Alert-root");
    expect(screen.getByRole("grid")).toBeInTheDocument();
    expect(screen.queryByText(/RANGE_CLAMPED/)).not.toBeInTheDocument();
  });

  const HORIZON_NOTE =
    "HISTORY_HORIZON: the selection lies before 2026-04-30 (history horizon: 6 months), so only that day is shown; totals are whole-life";
  const HORIZON_META = { ...deepDiveReport().meta, historyHorizon: { months: 6, from: "2026-04-30" } };

  test("a history-horizon note is said in words with the horizon's first day, not as the server wrote it", async () => {
    serve(mockFetch, sampleReport({ note: HORIZON_NOTE, meta: HORIZON_META }));
    renderPage(SPRINT_LINK);
    const note = await screen.findByText(/The selection lies before 2026-04-30 \(history horizon: 6 months\)/);
    expect(note.closest('[role="note"]')).toHaveClass("mantine-Alert-root");
    expect(screen.queryByText(/HISTORY_HORIZON/)).not.toBeInTheDocument();
  });

  test("a span clamp and the history horizon are both said, one note each", async () => {
    const note = `RANGE_CLAMPED: the implied range spans 2000 days; the last 1100 are shown. ${HORIZON_NOTE}`;
    serve(mockFetch, sampleReport({ note, meta: HORIZON_META }));
    renderPage(SPRINT_LINK);
    expect(await screen.findByText(/only its last 1100 days are drawn/)).toBeInTheDocument();
    expect(screen.getByText(/The selection lies before 2026-04-30/)).toBeInTheDocument();
    expect(screen.queryByText(/RANGE_CLAMPED|HISTORY_HORIZON/)).not.toBeInTheDocument();
  });

  test("in Polish the history-horizon note is Polish too", async () => {
    await i18n.changeLanguage("pl");
    try {
      serve(mockFetch, sampleReport({ note: HORIZON_NOTE, meta: HORIZON_META }));
      renderPage(SPRINT_LINK);
      expect(await screen.findByText(/Wybór leży przed 2026-04-30 \(horyzont historii: 6 miesięcy\)/)).toBeInTheDocument();
      expect(screen.queryByText(/HISTORY_HORIZON/)).not.toBeInTheDocument();
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("any other server note is shown as written", async () => {
    serve(mockFetch, sampleReport({ note: "Something the server wants to say." }));
    renderPage(SPRINT_LINK);
    const note = await screen.findByText("Something the server wants to say.");
    expect(note.closest('[role="note"]')).not.toBeNull();
    expect(screen.getByRole("grid")).toBeInTheDocument();
  });

  test("the data limits list every counter, the fixed rules and how fresh the data is", async () => {
    serve(
      mockFetch,
      sampleReport({
        quality: {
          neverInSprint: 3,
          noEstimate: 2,
          noWorkingDay: 1,
          epicsWithoutWindow: 4,
          laterFallback: 5,
          openInProgress: 6,
          doneUnestimated: 7,
          epicOwnCostMd: 1.25,
        },
      }),
    );
    renderPage(SPRINT_LINK);
    const limits = (await screen.findByRole("heading", { name: "Data limits" })).closest("section") as HTMLElement;
    const counters = within(limits).getByRole("list", { name: "What this selection could not draw" });
    expect(within(counters).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Tasks never in a sprint: 3 They have no plan (PV); their approximate EV and cost are still drawn.",
      "Tasks with no estimate at commitment: 2 They have no plan (PV) either.",
      "Tasks whose sprint has no working day: 1 Their estimate has no working day to sit on, so there is no plan (PV) to draw.",
      "Epics without a planned window: 4 They get no dashed outline.",
      "Tasks planned from a later sprint: 5 Their plan (PV) uses the estimate from the first later sprint that has one, not the earliest sprint's.",
      "Tasks in progress, not yet done: 6 They earn nothing until they are done; their in-progress days carry the hollow mark while the Approximate EV layer is on. Once a task is done, the earlier periods gain its share.",
      "Tasks done without an estimate: 7 There is no estimate to earn, so no approximate EV; their in-progress days carry the hollow mark while the Approximate EV layer is on.",
      "Man-days logged on the epics themselves: 1.25 Shown on each epic's own line, never spread over its tasks.",
    ]);
    const rules = within(within(limits).getByRole("list", { name: "Rules that always apply" })).getAllByRole("listitem");
    expect(rules).toHaveLength(4);
    // the precise copy: a flagged day in an in-progress status counts, waiting and blocked days earn nothing
    expect(rules[2]).toHaveTextContent("Approximate EV spreads a finished task's estimate at done evenly over the working days it spent in an in-progress status");
    expect(rules[2]).toHaveTextContent("Days in a waiting or blocked status earn nothing.");
    expect(rules[2]).toHaveTextContent("the flag is not subtracted");
    expect(within(limits).getByText("Data as of 2026-09-08, the day of the last derive.")).toBeInTheDocument();
  });

  test("the layer switches start on, redraw the matrix and are remembered for the viewer", async () => {
    const user = userEvent.setup();
    const { unmount } = renderPage(SPRINT_LINK);
    const grid = await screen.findByRole("grid");
    const layers = () => screen.getByRole("group", { name: "Layers shown" });
    for (const name of ["Plan (PV)", "Approximate EV", "Cost (AC)"]) expect(within(layers()).getByRole("switch", { name })).toBeChecked();
    expect(grid.querySelector('[data-layer="cost"]')).not.toBeNull();

    await user.click(within(layers()).getByRole("switch", { name: "Cost (AC)" }));
    expect(within(layers()).getByRole("switch", { name: "Cost (AC)" })).not.toBeChecked();
    expect(screen.getByRole("grid").querySelector('[data-layer="cost"]')).toBeNull();
    expect(screen.getByRole("grid").querySelector('[data-layer="pv"]')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem(LAYERS_KEY) ?? "null")).toEqual({ pv: true, aev: true, cost: false });
    unmount();

    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(within(layers()).getByRole("switch", { name: "Cost (AC)" })).not.toBeChecked();
    expect(within(layers()).getByRole("switch", { name: "Plan (PV)" })).toBeChecked();
  });

  test("a corrupt remembered layer choice falls back to all three on", async () => {
    localStorage.setItem(LAYERS_KEY, JSON.stringify({ pv: "yes" }));
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    for (const name of ["Plan (PV)", "Approximate EV", "Cost (AC)"]) expect(screen.getByRole("switch", { name })).toBeChecked();
  });

  test("a choice remembered before Approximate EV (the old exec key) falls back to all on once", async () => {
    localStorage.setItem(LAYERS_KEY, JSON.stringify({ pv: true, exec: false, cost: true }));
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    for (const name of ["Plan (PV)", "Approximate EV", "Cost (AC)"]) expect(screen.getByRole("switch", { name })).toBeChecked();
  });

  test("Show writes the canonical selection to the URL and requests it once", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("radio", { name: "Epics" }));
    // The epic picker waits for a domain, which then only narrows its list: the URL stays the epics'.
    expect(screen.getByRole("combobox", { name: "Epics" })).toHaveAccessibleDescription(/Pick a domain first\./);
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await user.click(screen.getByRole("combobox", { name: "Epics" }));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    // Picking is not asking: nothing is requested and the address is unchanged until Show.
    expect(reportCalls(mockFetch)).toEqual([]);
    expect(search()).toBe("");

    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(search()).toBe("?epicId=FLO-1");
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
  });

  test("another selection starts a fresh matrix, even one whose answer is already cached", async () => {
    const user = userEvent.setup();
    renderPage(LINK_A);
    await screen.findByRole("grid");
    await user.click(screen.getByRole("link", { name: "go to B" }));
    await waitFor(() => expect(reportCalls(mockFetch)).toHaveLength(2));
    await screen.findByRole("grid");
    await user.click(screen.getAllByRole("button", { name: /^Expand .* into weeks$/ })[0]);
    expect(screen.getAllByRole("button", { name: /^Collapse /i }).length).toBeGreaterThan(0);

    // A's answer is cached, so the grid swaps with no loading in between — still without B's open month.
    await user.click(screen.getByRole("link", { name: "go to A" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Collapse (?!all)/i })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Expand .* into weeks$/ }).length).toBeGreaterThan(0);
  });

  test("a link with epics and no domain still shows the report; only adding epics needs a domain", async () => {
    keepFormOpen();
    renderPage(LINK_B);
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
    expect(screen.getByRole("combobox", { name: "Epics" })).toHaveAccessibleDescription(/Pick a domain first\./);
    expect(screen.getByText("1 of 50 selected. Pick a domain first.")).toBeInTheDocument();
    expect(mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(`${REPORT_PATH}/epics?`))).toEqual([]);
  });

  test("a link with issueId shows the whole epic and says the tasks view is gone", async () => {
    const user = userEvent.setup();
    keepFormOpen();
    serve(mockFetch, sampleReport({ mode: "EPICS" }));
    renderPage("/reports/deep-dive?epicId=FLO-1&issueId=FLO-11");
    await screen.findByRole("grid");
    // The retired key is never sent: the whole epic is requested, and the page says why.
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
    expect(await screen.findByRole("note")).toHaveTextContent(
      "This link picked single tasks of an epic, a view that no longer exists. The whole epic is shown instead.",
    );
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();

    // Showing the same epic writes the URL without the retired key, which takes the note with it.
    await user.click(screen.getByRole("button", { name: "Show" }));
    await waitFor(() => expect(search()).toBe("?epicId=FLO-1"));
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument();
  });

  test("a link with only issueId, or one beside a sprint selection, reads as it would without it and says nothing about tasks", async () => {
    const first = renderPage("/reports/deep-dive?issueId=FLO-11");
    expect(await screen.findByRole("heading", { name: "Pick something to dive into" })).toBeInTheDocument();
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([]);
    first.unmount();

    renderPage(`${SPRINT_LINK}&issueId=FLO-11`);
    await screen.findByRole("grid");
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?domain=FLO&sprintId=41&sprintId=42`]);
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument();
  });

  test("Back and a followed link reset the panel to the URL", async () => {
    const user = userEvent.setup();
    keepFormOpen();
    renderPage(LINK_B);
    await screen.findByRole("grid");
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
    expect(screen.getByText(/^1 of 50 selected\./)).toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: "go to A" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Sprints of a domain" })).toBeChecked());
    expect(screen.getByText("2 of 52 selected.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "go back" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked());
    expect(screen.getByText(/^1 of 50 selected\./)).toBeInTheDocument();
  });

  test("Show folds the panel without resetting its draft: Edit selection brings the same picks and mode back", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("radio", { name: "Epics" }));
    // The epic picker waits for a domain, which then only narrows its list: the URL stays the epics'.
    expect(screen.getByRole("combobox", { name: "Epics" })).toHaveAccessibleDescription(/Pick a domain first\./);
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await user.click(screen.getByRole("combobox", { name: "Epics" }));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(search()).toBe("?epicId=FLO-1");
    await screen.findByRole("grid");
    // Folded over the selection just shown: its summary, no form.
    expect(await screen.findByText("Epics: FLO-1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveFocus();
    expect(screen.queryByRole("radio", { name: "Epics" })).not.toBeInTheDocument();
    // A rebuilt panel would have lost the draft; this one still holds its mode and its pick.
    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
    expect(screen.getByText(/^1 of 50 selected\./)).toBeInTheDocument();
  });

  test("Show for the selection already on screen adds no history entry", async () => {
    const user = userEvent.setup();
    keepFormOpen();
    renderPage(LINK_A);
    await screen.findByRole("grid");
    await user.click(screen.getByRole("link", { name: "go to B" }));
    await screen.findByText(/^1 of 50 selected\./);
    await user.click(screen.getByRole("button", { name: "Show" }));
    await user.click(screen.getByRole("button", { name: "go back" }));
    await waitFor(() => expect(search()).toContain("domain=FLO"));
  });

  test("a failed report is an inline alert, never a toast", async () => {
    serve(mockFetch, { title: "Boom", status: 500 }, 500);
    renderPage(SPRINT_LINK);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/500/);
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    // The panel stays (folded over the link's selection), so the selection can be changed.
    expect(screen.getByRole("button", { name: "Edit selection" })).toBeInTheDocument();
  });

  test("a 400 shows the server's own reason for the selection", async () => {
    serve(mockFetch, { title: "Bad Request", status: 400, detail: "The selection resolves to more than 500 tasks." }, 400);
    renderPage(SPRINT_LINK);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("The selection resolves to more than 500 tasks."));
    expect(screen.getByRole("alert")).toHaveTextContent("This selection cannot be shown");
    // The form is open (not folded) so the selection can be fixed right there, whatever the stored choice.
    expect(screen.getByRole("button", { name: "Show" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "true");
  });
});

describe("ReportDeepDive page — the Matrix | Burn-up tabs", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve(mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });
  const BURNUP_LINK = `${SPRINT_LINK}&view=burnup`;
  const layers = () => screen.queryByRole("group", { name: "Layers shown" });

  test("the matrix is the default tab: nothing in the URL, no burn-up drawn", async () => {
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Matrix" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tablist", { name: "View" })).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Burn-up" })).not.toBeInTheDocument();
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
  });

  test("choosing Burn-up writes view=burnup next to the selection and draws the chart from the answer already fetched", async () => {
    const user = userEvent.setup();
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(layers()).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Burn-up" }));
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41&view=burnup");
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "true");
    const chart = await screen.findByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!).map((s: { name: string }) => s.name)).toEqual(["pv", "ev", "aev", "ac"]);
    expect(JSON.parse(chart.getAttribute("data-rows")!).at(-1)).toEqual({ date: "2026-09-08", pv: 2, ev: 2, aev: 2, ac: 1.5 });
    // The layer switches belong to the matrix; the burn-up has its own legend.
    expect(layers()).not.toBeInTheDocument();
    // One answer feeds both views: no second request.
    expect(reportCalls(mockFetch)).toHaveLength(1);

    await user.click(screen.getByRole("tab", { name: "Matrix" }));
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
    expect(await screen.findByRole("group", { name: "Layers shown" })).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(reportCalls(mockFetch)).toHaveLength(1);
  });

  test("a link with view=burnup opens the burn-up, with the one outline", async () => {
    renderPage(BURNUP_LINK);
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "true");
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?domain=FLO&sprintId=41&sprintId=42`]);
    expect(headingOutline()).toEqual([
      [2, "Deep dive"],
      [3, "Selection"],
      [3, "Burn-up"],
      [3, "Data limits"],
    ]);
  });

  test("Show for another selection keeps the burn-up open and requests the new answer once", async () => {
    const user = userEvent.setup();
    keepFormOpen();
    renderPage("/reports/deep-dive?epicId=FLO-1&view=burnup");
    await screen.findByTestId("line-chart");
    await user.type(await screen.findByRole("textbox", { name: "From (optional)" }), "2026-08-30");
    await user.click(screen.getByRole("button", { name: "Show" }));
    await waitFor(() => expect(reportCalls(mockFetch)).toHaveLength(2));
    const params = new URLSearchParams(search());
    expect(params.get("view")).toBe("burnup");
    expect(params.get("from")).toBe("2026-08-30");
    expect(reportCalls(mockFetch)[1]).toBe(`${REPORT_PATH}?epicId=FLO-1&from=2026-08-30`);
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
  });

  test("the budget switch is remembered per viewer and survives a hop to the matrix and back", async () => {
    const user = userEvent.setup();
    const epics = [deepDiveEpic("FLO-1", { summary: "Onboarding", plannedStart: 0, plannedDue: 4, budgetMd: 10 })];
    serve(mockFetch, sampleReport({ epics }));
    const { unmount } = renderPage(BURNUP_LINK);
    const toggle = await screen.findByRole("switch", { name: /Epic budget plan/ });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(JSON.parse(localStorage.getItem(BUDGET_KEY) ?? "null")).toBe(true);

    await user.click(screen.getByRole("tab", { name: "Matrix" }));
    await user.click(screen.getByRole("tab", { name: "Burn-up" }));
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).toBeChecked();
    const series = JSON.parse((await screen.findByTestId("line-chart")).getAttribute("data-series")!) as Array<{ name: string }>;
    expect(series.map((s) => s.name)).toContain("budget");
    unmount();

    renderPage(BURNUP_LINK);
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).toBeChecked();
  });

  test("a corrupt remembered budget choice falls back to off", async () => {
    localStorage.setItem(BUDGET_KEY, JSON.stringify("yes"));
    serve(mockFetch, sampleReport({ epics: [deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 4, budgetMd: 10 })] }));
    renderPage(BURNUP_LINK);
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).not.toBeChecked();
  });

  test("a followed link without view opens the matrix again", async () => {
    const user = userEvent.setup();
    renderPage("/reports/deep-dive?epicId=FLO-1&view=burnup");
    await screen.findByTestId("line-chart");
    await user.click(screen.getByRole("link", { name: "go to A" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Matrix" })).toHaveAttribute("aria-selected", "true");
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
  });

  test("the tabs are reachable by keyboard: the arrow key moves to the other tab", async () => {
    const user = userEvent.setup();
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    screen.getByRole("tab", { name: "Matrix" }).focus();
    await user.keyboard("{ArrowRight}");
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveFocus();
    expect(search()).toContain("view=burnup");
  });

  test("nothing derived yet shows its one note, with no tabs, even for a burn-up link", async () => {
    serve(
      mockFetch,
      deepDiveReport({
        range: { from: "2026-09-01", to: "2026-09-01", asOfDay: null },
        meta: { ...sampleReport().meta, derivedAt: null, configRevision: null },
        note: "Not derived yet: the connection has not completed a DERIVE.",
      }),
    );
    renderPage(BURNUP_LINK);
    expect(await screen.findByText(/Nothing in this selection has been derived yet/)).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("a selection with no figures says so on the burn-up too", async () => {
    serve(mockFetch, sampleReport({ tasks: [], epics: [] }));
    renderPage(BURNUP_LINK);
    expect(await screen.findByText("There is nothing to plot for this selection.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });
});
