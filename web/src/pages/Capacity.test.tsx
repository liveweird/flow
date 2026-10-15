import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { headingOutline } from "../test/headings";
import {
  calendarGets,
  person,
  putBody,
  putCalls,
  renderPage,
  serve,
  sprintSummary,
  DAYS,
  SPRINTS,
  TEAMS,
  type FetchMock,
} from "../test/capacityKit";

describe("Capacity page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["USER"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("with nothing picked it offers the team picker and says what to do", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText("Pick a team and a sprint to see its capacity calendar.")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Capacity"]]);
    expect(screen.getByRole("combobox", { name: "Sprint" })).toBeDisabled();
    // No team-scoped request until a team is picked.
    expect(mockFetch.mock.calls.some(([url]) => String(url).includes("/capacity"))).toBe(false);
  });

  test("picking a team puts it in the URL and loads its default and its sprints, newest first", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Payments" }));
    await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent("/capacity?teamId=3"));
    expect(await screen.findByRole("textbox", { name: "Default capacity (MD)" })).toHaveValue("40");
    expect(mockFetch.mock.calls.some(([url]) => url === "/api/v1/teams/3/sprint-calendars?page=1&pageSize=100&sort=-startAt")).toBe(true);

    await user.click(screen.getByRole("combobox", { name: "Sprint" }));
    const option = await screen.findByRole("option", { name: /Sprint 11/ });
    expect(option).toHaveTextContent("Sprint 11 · 2026-03-06 → 2026-03-09");
    expect(within(option).getByText("calendar")).toBeInTheDocument();
    expect(within(screen.getByRole("option", { name: /Sprint 12/ })).queryByText("calendar")).not.toBeInTheDocument();
    await user.click(option);
    await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent("/capacity?teamId=3&sprintId=11"));
    expect(await screen.findByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" })).toBeInTheDocument();
  });

  test("a deep link opens the calendar: the outline, the live total and the derived figure", async () => {
    serve(mockFetch);
    renderPage("/capacity?teamId=3&sprintId=11");
    expect(await screen.findByText("This calendar: 3.6 MD")).toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Capacity"],
      [3, "Team default capacity"],
      [3, "Sprint calendar"],
    ]);
    expect(screen.getByText("Capacity in reports: 20 MD (the team default)")).toBeInTheDocument();
    // Once in the (closed) sprint picker's options, once as the calendar's own caption.
    expect(screen.getAllByText("Sprint 11 · 2026-03-06 → 2026-03-09")).toHaveLength(2);
    expect(screen.queryByText(/Showing computed defaults/)).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1");
    expect(screen.getByText("not a member")).toBeInTheDocument();
  });

  test("the live total follows the edits against a hand computation", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3&sprintId=11");
    await screen.findByText("This calendar: 3.6 MD");
    // Team coefficient 1.2, Saturday on, Bob's Friday at 0.5, Ada at 0.9:
    // Ada 0.9 x (1 + 1 + 1) = 2.7; Bob 0.8 x (0.5 + 1 + 1) = 2; 1.2 x 4.7 = 5.64.
    const teamCoefficient = screen.getByRole("textbox", { name: "Team coefficient" });
    await user.clear(teamCoefficient);
    await user.type(teamCoefficient, "1.2");
    await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
    const bobFriday = screen.getByRole("textbox", { name: "Availability of Bob Builder on 2026-03-06" });
    await user.clear(bobFriday);
    await user.type(bobFriday, "0.5");
    const ada = screen.getByRole("textbox", { name: "Coefficient of Ada Lovelace" });
    await user.clear(ada);
    await user.type(ada, "0.9");
    expect(screen.getByText("This calendar: 5.64 MD")).toBeInTheDocument();
  });

  test("Save sends the whole document without the grid-only fields; Reset goes back to the loaded one", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3&sprintId=11");
    await screen.findByText("This calendar: 3.6 MD");
    expect(screen.getByRole("button", { name: "Reset" })).toBeDisabled();

    const cell = screen.getByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" });
    await user.clear(cell);
    await user.type(cell, "0.5");
    expect(screen.getByText("This calendar: 3.1 MD")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reset" }));
    expect(screen.getByText("This calendar: 3.6 MD")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" })).toHaveValue("1");

    await user.clear(screen.getByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" }));
    await user.type(screen.getByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" }), "0.5");
    await user.click(screen.getByRole("checkbox", { name: "Count Bob Builder" }));
    await user.click(screen.getByRole("button", { name: "Save calendar" }));

    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
    const adaCells = DAYS.map((day) => ({ day, availability: day === DAYS[0] ? 0.5 : 1 }));
    const bobCells = DAYS.map((day) => ({ day, availability: 1 }));
    expect(putBody(mockFetch, "/api/v1/teams/3/sprint-calendars/11")).toEqual({
      teamCoefficient: 1,
      days: DAYS.map((day, i) => ({ day, enabled: i === 0 || i === 3 })),
      people: [
        { accountId: "ada", enabled: true, coefficient: 1, cells: adaCells },
        { accountId: "bob", enabled: false, coefficient: 0.8, cells: bobCells },
      ],
    });
    // The saved calendar is reloaded afterwards.
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => url === "/api/v1/teams/3/sprint-calendars/11").length).toBeGreaterThan(1));
  });

  test("an unstored sprint says the grid shows computed defaults", async () => {
    serve(mockFetch, { calendar: { hasCalendar: false, updatedAt: null, derivedCapacityMd: null, derivedCapacitySource: null } });
    renderPage("/capacity?teamId=3&sprintId=12");
    expect(await screen.findByText("Showing computed defaults. Save to store this calendar.")).toBeInTheDocument();
    expect(screen.getByText("Capacity in reports: none for this sprint")).toBeInTheDocument();
    // The grid's total is the proposal, never the figure reports use today.
    expect(screen.getByText("This calendar: 3.6 MD")).toBeInTheDocument();
    expect(screen.getByText("Saving it makes reports use it.")).toBeInTheDocument();
  });

  test("an unstored sprint shows the effective figure reports use beside the calendar's own total", async () => {
    serve(mockFetch, { calendar: { hasCalendar: false, updatedAt: null, derivedCapacityMd: 4.5, derivedCapacitySource: "DEFAULT" } });
    renderPage("/capacity?teamId=3&sprintId=12");
    expect(await screen.findByText("Capacity in reports: 4.5 MD (members × working days)")).toBeInTheDocument();
    expect(screen.getByText("This calendar: 3.6 MD")).toBeInTheDocument();
  });

  test("a stored calendar offers the hint only once it has unsaved edits", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3&sprintId=11");
    await screen.findByText("This calendar: 3.6 MD");
    expect(screen.queryByText("Saving it makes reports use it.")).not.toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
    expect(screen.getByText("Saving it makes reports use it.")).toBeInTheDocument();
  });

  test("a sprint without a window has no grid", async () => {
    serve(mockFetch, { calendar: { window: null, note: "x", days: [], people: [], capacityMd: null } });
    renderPage("/capacity?teamId=3&sprintId=11");
    expect(await screen.findByText("This sprint has no start date or lasts over 60 days, so it has no calendar.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save calendar" })).not.toBeInTheDocument();
  });

  test("a non-manager reads: every input and Save are disabled and the note says why", async () => {
    // The server withholds the per-person coefficients from a caller who cannot edit.
    const people = [person("ada", "Ada Lovelace", 1), person("bob", "Bob Builder", 0.8, false)].map((p) => ({ ...p, coefficient: null }));
    serve(mockFetch, { calendar: { canEdit: false, people, capacityMd: 7.77 }, capacity: { canEdit: false } });
    renderPage("/capacity?teamId=3&sprintId=11");
    // The total is the server's figure (the formula needs the coefficients, which are null), and the coefficient column is gone.
    await screen.findByText("This calendar: 7.77 MD");
    expect(screen.queryByRole("columnheader", { name: "Coefficient" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Coefficient of/ })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1");
    expect(screen.queryByText("Saving it makes reports use it.")).not.toBeInTheDocument();
    expect(screen.getAllByText("Only the team's manager or an administrator can edit the team's capacity.")).toHaveLength(2);
    for (const input of screen.getAllByRole("textbox", { name: /Availability|Coefficient|Team coefficient|Default capacity/ })) expect(input).toBeDisabled();
    for (const box of screen.getAllByRole("checkbox")) expect(box).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save calendar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save default" })).toBeDisabled();
  });

  test.each([
    [403, "You can no longer edit this team's capacity."],
    [409, "This sprint has no start date, so it has no calendar."],
    [404, "This team or sprint no longer exists."],
    [400, "The calendar no longer fits the sprint (its days or people changed). Press Reset to load the current one, then redo your edits."],
    [500, "Could not save the capacity."],
  ])("a %i on Save shows the fixed message, never the server's text", async (status, message) => {
    serve(mockFetch, {
      puts: { "PUT /api/v1/teams/3/sprint-calendars/11": { status, body: { title: "x", status, detail: "SERVER-SIDE DETAIL", instance: "/x", type: "about:blank" } } },
    });
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3&sprintId=11");
    await screen.findByText("This calendar: 3.6 MD");
    await user.click(screen.getByRole("button", { name: "Save calendar" }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText(/SERVER-SIDE DETAIL/)).not.toBeInTheDocument();
    // A rejected, vanished or conflicting document means the loaded one is stale: those three refetch, 403 and 500 do not.
    if ([400, 404, 409].includes(status)) await waitFor(() => expect(calendarGets(mockFetch)).toBeGreaterThan(1));
    else expect(calendarGets(mockFetch)).toBe(1);
  });

  test("the team default saves its figure, and clearing it saves null", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3");
    const field = await screen.findByRole("textbox", { name: "Default capacity (MD)" });
    expect(field).toHaveValue("40");
    await user.clear(field);
    await user.type(field, "42.5");
    await user.click(screen.getByRole("button", { name: "Save default" }));
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
    expect(putBody(mockFetch, "/api/v1/teams/3/capacity")).toEqual({ defaultCapacityMd: 42.5 });

    await user.clear(screen.getByRole("textbox", { name: "Default capacity (MD)" }));
    await user.click(screen.getByRole("button", { name: "Save default" }));
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(2));
    expect(JSON.parse((putCalls(mockFetch)[1][1] as RequestInit).body as string)).toEqual({ defaultCapacityMd: null });
  });

  test("the team default takes a comma, refuses a negative sign and flags a non-number instead of sending null", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3");
    const field = await screen.findByRole("textbox", { name: "Default capacity (MD)" });
    await user.clear(field);
    await user.type(field, "-7,5");
    expect(field).toHaveValue("7.5");
    await user.clear(field);
    await user.type(field, ".");
    expect(screen.getByText("Enter a figure from 0 to 999999.99, or leave it blank for none.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save default" })).toBeDisabled();
    await user.clear(field);
    await user.type(field, "12,25");
    expect(screen.queryByText(/Enter a figure from 0/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save default" }));
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
    expect(putBody(mockFetch, "/api/v1/teams/3/capacity")).toEqual({ defaultCapacityMd: 12.25 });
  });

  test("a rejected team default shows the fixed message", async () => {
    serve(mockFetch, { puts: { "PUT /api/v1/teams/3/capacity": { status: 403, body: { title: "Forbidden", status: 403, detail: "SERVER-SIDE DETAIL" } } } });
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3");
    await user.click(await screen.findByRole("button", { name: "Save default" }));
    expect(await screen.findByText("You can no longer edit this team's capacity.")).toBeInTheDocument();
  });

  test("a team without sprints says so and still offers its default", async () => {
    serve(mockFetch, { sprints: { items: [], page: 1, pageSize: 100, total: 0 } });
    renderPage("/capacity?teamId=3");
    expect(await screen.findByText("This team has no sprints yet. They appear after the next sync.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Default capacity (MD)" })).toBeInTheDocument();
  });

  test("a long sprint list says it is cut", async () => {
    serve(mockFetch, { sprints: { ...SPRINTS, total: 250 } });
    renderPage("/capacity?teamId=3");
    expect(await screen.findByText("Showing the first 2 of 250 sprints.")).toBeInTheDocument();
  });

  test("no teams yet says so", async () => {
    serve(mockFetch, { teams: { items: [], page: 1, pageSize: 100, total: 0 } });
    renderPage();
    expect(await screen.findByText("There is no team yet. An administrator adds teams on the Teams page.")).toBeInTheDocument();
  });

  test("picking another team drops the sprint from the URL", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3&sprintId=11");
    await screen.findByText("This calendar: 3.6 MD");
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Identity" }));
    await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent(/^\/capacity\?teamId=4$/));
  });

  test("a long team list says it is cut, like the sprint list", async () => {
    serve(mockFetch, { teams: { ...TEAMS, total: 130 } });
    renderPage();
    expect(await screen.findByText("Showing the first 2 of 130 teams.")).toBeInTheDocument();
  });

  test("the sprint picker ends a sprint on its last day, as the grid does", async () => {
    // The sprint ends at Warsaw midnight (exclusive): its last day is the 9th, not the 10th.
    const midnight = Date.UTC(2026, 2, 9, 23);
    serve(mockFetch, { sprints: { ...SPRINTS, items: [{ ...sprintSummary(13, "Sprint 13", false), endAt: midnight, completeAt: midnight }], total: 1 } });
    const user = userEvent.setup();
    renderPage("/capacity?teamId=3");
    await user.click(await screen.findByRole("combobox", { name: "Sprint" }));
    expect(await screen.findByRole("option", { name: /Sprint 13/ })).toHaveTextContent("Sprint 13 · 2026-03-06 → 2026-03-09");
  });

  test("the total is not a live region (it changes on every keystroke)", async () => {
    serve(mockFetch);
    renderPage("/capacity?teamId=3&sprintId=11");
    const total = await screen.findByText("This calendar: 3.6 MD");
    expect(total.closest("[aria-live]")).toBeNull();
  });

  test("a sprint whose team has nobody to plan cannot be saved or given a coefficient", async () => {
    serve(mockFetch, { calendar: { people: [], capacityMd: 0, hasCalendar: false, updatedAt: null } });
    renderPage("/capacity?teamId=3&sprintId=12");
    expect(await screen.findByText("No team member overlaps this sprint, so there is nobody to plan.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    // A save would store a zero calendar that overrides the team default.
    expect(screen.getByRole("button", { name: "Save calendar" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Team coefficient" })).toBeDisabled();
    expect(putCalls(mockFetch)).toHaveLength(0);
  });
});
