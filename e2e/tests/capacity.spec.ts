// The Capacity page (A32): how many man-days a team has in a sprint. An administrator plans the golden
// FLO sprint's person x day calendar (the computed defaults, a team default, a day off, a half cell, a
// coefficient) and the Commitments report then shows the calendar's figure; a team manager edits, a regular
// user only reads. The admin side is API-seeded (a synced stub connection, a team with the FLO board mapped
// to it and one stub person on its roster) so the journeys stay fast; the sync + DERIVE wait is a bounded
// poll of the report API, never a sleep. The connection is deliberately NOT paused: a calendar save bumps the
// shared config revision and the CALENDAR figure only reaches the report once the connection re-derives.
// Owns: its own Jira-stub data source (`e2e-capacity-ds-*`), team (`e2e-capacity-team-*`) with one stub
// person on its roster, and two throwaway users (a manager and a reader), all created through the API
// before the block and deleted after it.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Page } from "@playwright/test";
import {
  addStubMemberViaApi,
  apiAsAdmin,
  awaitDerivedSprint,
  axeViolations,
  configureMetricsViaApi,
  expect,
  login,
  openFilters,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

const GOLDEN = (
  JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
    golden: { sprint: { sprintId: number; name: string } };
  }
).golden.sprint;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let teamId = 0;
let teamName = "";
let membershipId: number | undefined;
let managerId: number | undefined;
let readerId: number | undefined;
let manager = { name: "", email: "", password: "" };
let reader = { email: "", password: "" };

const calendarUrl = () => `/capacity?teamId=${teamId}&sprintId=${GOLDEN.sprintId}`;

async function createUserViaApi(admin: APIRequestContext, prefix: string): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(prefix);
  const email = `${name.toLowerCase()}@flow.local`;
  const password = "e2e-only-password";
  const created = await admin.post("/api/v1/users", { data: { name, email, password, roles: [] } });
  expect(created.status(), await created.text()).toBe(201);
  return { id: (await created.json()).id as number, name, email, password };
}

/** The sprint's calendar days (`YYYY-MM-DD`, first to last) from the caption "name · from → to" under the Sprint calendar title. */
async function windowDays(page: Page): Promise<string[]> {
  const caption = page.locator("p:visible").filter({ hasText: new RegExp(`^${GOLDEN.name} · \\d{4}-\\d{2}-\\d{2} → \\d{4}-\\d{2}-\\d{2}$`) });
  const match = /(\d{4}-\d{2}-\d{2}) → (\d{4}-\d{2}-\d{2})$/.exec(await caption.first().innerText());
  expect(match, "the calendar names its window").not.toBeNull();
  const days: string[] = [];
  for (let at = Date.parse(match?.[1] ?? ""); at <= Date.parse(match?.[2] ?? ""); at += 86_400_000) days.push(new Date(at).toISOString().slice(0, 10));
  return days;
}

/** Monday to Friday — the stack's default working days (Saturday and Sunday are the default weekend). */
const isWeekday = (day: string) => ![0, 6].includes(new Date(`${day}T00:00:00Z`).getUTCDay());

/** Wait until Commitments reports the sprint's capacity from the calendar, at the expected figure (bounded, ~240s). */
async function awaitCalendarCapacity(admin: APIRequestContext, expected: number): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await admin.get(`/api/v1/reports/commitments?teamId=${teamId}&sprintId=${GOLDEN.sprintId}`);
    const text = await res.text();
    const sprint = res.ok()
      ? (JSON.parse(text) as { sprints: { sprintId: number; capacityMd: number | null; capacitySource: string | null }[] }).sprints.find((s) => s.sprintId === GOLDEN.sprintId)
      : undefined;
    if (sprint?.capacitySource === "CALENDAR" && sprint.capacityMd !== null && Math.abs(sprint.capacityMd - expected) < 0.005) return;
    if (Date.now() > deadline) throw new Error(`Commitments never reported CALENDAR ${expected} MD within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
}

/** Open the page for the team and sprint through the pickers. */
async function pickTeamAndSprint(page: Page): Promise<void> {
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const sprint = page.getByRole("combobox", { name: "Sprint", exact: true });
  await expect(sprint).toBeEnabled();
  await sprint.click();
  await page.getByRole("option", { name: new RegExp(`^${GOLDEN.name} · `) }).click();
  await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));
}

test.describe("capacity, planned by an admin and a manager and read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync plus the derivation that follows it: each wait is bounded (340s / 240s).
    test.setTimeout(700_000);
    ({ api } = await apiAsAdmin());
    const admin = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(admin, "e2e-capacity-ds"));
    teamName = uniqueText("e2e-capacity-team");
    ({ teamId } = await configureMetricsViaApi(admin, dataSourceId, teamName));
    // One stub person on the roster: the calendar's only row.
    ({ membershipId } = await addStubMemberViaApi(admin, teamId));
    const managerUser = await createUserViaApi(admin, "e2e-capacity-manager");
    managerId = managerUser.id;
    manager = managerUser;
    const readerUser = await createUserViaApi(admin, "e2e-capacity-reader");
    readerId = readerUser.id;
    reader = readerUser;
    await awaitDerivedSprint(admin, teamId, GOLDEN.sprintId);
  });

  test.afterAll(async () => {
    // A FRESH admin session: the access token beforeAll minted may have expired across the bounded waits.
    // Children first: the connection (soft delete), the roster membership (a person belongs to one team at
    // a time, globally, so it goes BEFORE the team), the team, then the users.
    const { api: cleanup } = await apiAsAdmin();
    const paths = [
      dataSourceId === undefined ? null : `/api/v1/data-sources/${dataSourceId}`,
      membershipId === undefined || teamId === 0 ? null : `/api/v1/teams/${teamId}/jira-memberships/${membershipId}`,
      teamId === 0 ? null : `/api/v1/teams/${teamId}`,
      managerId === undefined ? null : `/api/v1/users/${managerId}`,
      readerId === undefined ? null : `/api/v1/users/${readerId}`,
    ];
    for (const path of paths) {
      if (path === null) continue;
      const removed = await cleanup.delete(path);
      expect([204, 404], `${path} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  });

  test("an administrator plans a sprint calendar and Commitments shows the calendar capacity", async ({ page }) => {
    // Includes the bounded wait for the re-derive that makes the calendar the report's figure.
    test.setTimeout(360_000);
    const saved = page.getByText("Saved — reports re-derive shortly");
    await login(page);
    await page.goto("/capacity");
    await expect(page.getByRole("heading", { level: 2, name: "Capacity", exact: true })).toBeVisible();
    await expect(page.getByText("Pick a team and a sprint to see its capacity calendar.")).toBeVisible();
    await pickTeamAndSprint(page);

    // The team default: a figure for every sprint of the team without a calendar.
    const teamDefault = page.getByRole("textbox", { name: "Default capacity (MD)" });
    await expect(teamDefault).toBeEditable();
    await teamDefault.fill("40");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/api/v1/teams/${teamId}/capacity`) && r.ok()),
      page.getByRole("button", { name: "Save default", exact: true }).click(),
    ]);
    await expect(saved).toBeVisible();
    // The toast is fixed-vocabulary and short-lived: let it go so the calendar's own toast is the only one asserted below.
    await expect(saved).toHaveCount(0);

    // The computed defaults: nothing is stored yet, the weekend columns are off, every working day is counted.
    await expect(page.getByRole("heading", { level: 3, name: "Sprint calendar", exact: true })).toBeVisible();
    await expect(page.getByText("Showing computed defaults. Save to store this calendar.")).toBeVisible();
    await expect(page.getByText(/^Capacity in reports: /)).toBeVisible();
    const days = await windowDays(page);
    const weekdays = days.filter(isWeekday);
    expect(weekdays.length, "the golden sprint spans at least a working week").toBeGreaterThan(4);
    for (const day of days) {
      const counted = page.getByRole("checkbox", { name: `Count ${day}`, exact: true });
      if (isWeekday(day)) await expect(counted).toBeChecked();
      else await expect(counted).not.toBeChecked();
    }
    const cell = (day: string) => page.getByRole("textbox", { name: new RegExp(`^Availability of .* on ${day}$`) });
    await expect(cell(weekdays[0])).toHaveValue("1");
    await expect(page.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1");
    await expect(page.getByText(`This calendar: ${md(weekdays.length)} MD`)).toBeVisible();

    // Turn the first working day off, give the second a half day and the person a 0.8 coefficient.
    await page.getByRole("checkbox", { name: `Count ${weekdays[0]}`, exact: true }).uncheck();
    await expect(cell(weekdays[0])).toBeDisabled();
    await cell(weekdays[1]).fill("0.5");
    await page.getByRole("textbox", { name: /^Coefficient of / }).fill("0.8");
    // 0.8 x ((N - 2) full days + one half day), N working days minus the one turned off.
    const expected = Math.round(0.8 * (weekdays.length - 2 + 0.5) * 100) / 100;
    await expect(page.getByText(`This calendar: ${md(expected)} MD`)).toBeVisible();
    await expect(page.getByText("Saving it makes reports use it.")).toBeVisible();

    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/sprint-calendars/${GOLDEN.sprintId}`) && r.ok()),
      page.getByRole("button", { name: "Save calendar", exact: true }).click(),
    ]);
    await expect(saved).toBeVisible();

    // The calendar is stored: a reload keeps every edit and the defaults note is gone. The populated grid is scanned once.
    await page.reload();
    await expect(page.getByRole("heading", { level: 3, name: "Sprint calendar", exact: true })).toBeVisible();
    await expect(page.getByText("Showing computed defaults. Save to store this calendar.")).toHaveCount(0);
    await expect(page.getByRole("checkbox", { name: `Count ${weekdays[0]}`, exact: true })).not.toBeChecked();
    await expect(cell(weekdays[1])).toHaveValue("0.5");
    await expect(page.getByRole("textbox", { name: /^Coefficient of / })).toHaveValue("0.8");
    await expect(page.getByRole("textbox", { name: "Default capacity (MD)" })).toHaveValue("40");
    await expect(page.getByText(`This calendar: ${md(expected)} MD`)).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);

    // The next DERIVE takes the figure from the calendar, ahead of the team default.
    const { api: poller } = await apiAsAdmin();
    try {
      await awaitCalendarCapacity(poller, expected);
    } finally {
      await poller.dispose();
    }

    // Commitments shows that figure for the golden sprint — a calendar capacity carries no "default" badge.
    await page.goto("/reports/commitments");
    await openFilters(page);
    const team = page.getByRole("combobox", { name: "Team", exact: true });
    await team.click();
    await team.fill(teamName);
    await page.getByRole("option", { name: teamName, exact: true }).click();
    await page.getByRole("combobox", { name: "Period", exact: true }).click();
    await page.getByRole("option", { name: "One sprint", exact: true }).click();
    await page.getByRole("combobox", { name: "Sprint", exact: true }).click();
    await page.getByRole("option", { name: new RegExp(`^${GOLDEN.name} · `) }).click();
    const sprintTable = page.getByRole("table", { name: "Sprints and their figures" });
    const headers = (await sprintTable.getByRole("columnheader").allInnerTexts()).map((text) => text.trim());
    const capacityColumn = headers.findIndex((text) => text.startsWith("Capacity (MD)"));
    expect(capacityColumn, "the sprints table has a Capacity (MD) column").toBeGreaterThan(-1);
    await expect(sprintTable.getByRole("row", { name: new RegExp(GOLDEN.name) }).getByRole("cell").nth(capacityColumn)).toHaveText(md(expected));
  });

  test("a team manager edits the calendar, a regular user only reads it", async ({ page }) => {
    // A regular user who manages nothing reads: every control is disabled, the note says why, no coefficients are shown.
    await login(page, reader.email, reader.password);
    await page.goto(calendarUrl());
    await expect(page.getByRole("heading", { level: 3, name: "Sprint calendar", exact: true })).toBeVisible();
    await expect(page.getByText("Only the team's manager or an administrator can edit the team's capacity.")).toHaveCount(2);
    await expect(page.getByRole("button", { name: "Save calendar", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save default", exact: true })).toBeDisabled();
    await expect(page.getByRole("textbox", { name: "Default capacity (MD)" })).toBeDisabled();
    await expect(page.getByRole("textbox", { name: "Team coefficient" })).toBeDisabled();
    await expect(page.getByRole("columnheader", { name: "Coefficient", exact: true })).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: /^Coefficient of / })).toHaveCount(0);
    const cells = page.getByRole("textbox", { name: /^Availability of / });
    expect(await cells.count(), "the roster person's day cells").toBeGreaterThan(0);
    for (const input of await cells.all()) await expect(input).toBeDisabled();
    for (const box of await page.getByRole("checkbox").all()) await expect(box).toBeDisabled();

    // The administrator assigns the manager from the team's editor.
    await login(page);
    await page.goto(`/teams/${teamId}`);
    await page.getByRole("button", { name: "Edit", exact: true }).click();
    const editor = page.getByRole("dialog");
    const managerPicker = editor.getByRole("combobox", { name: "Manager" });
    await managerPicker.click();
    await managerPicker.fill(manager.name);
    await page.getByRole("option", { name: `${manager.name} (${manager.email})` }).click();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
      editor.getByRole("button", { name: "Save", exact: true }).click(),
    ]);
    await expect(page.getByText(`Manager: ${manager.name}`)).toBeVisible();

    // The manager edits: a team default and a cell, both stored, both still there after a reload.
    await login(page, manager.email, manager.password);
    await page.goto(calendarUrl());
    await expect(page.getByText("Only the team's manager or an administrator can edit the team's capacity.")).toHaveCount(0);
    await expect(page.getByRole("columnheader", { name: "Coefficient", exact: true })).toBeVisible();
    const teamDefault = page.getByRole("textbox", { name: "Default capacity (MD)" });
    await teamDefault.fill("55");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/api/v1/teams/${teamId}/capacity`) && r.ok()),
      page.getByRole("button", { name: "Save default", exact: true }).click(),
    ]);
    const firstOpenCell = page.locator('input[aria-label^="Availability of "]:enabled').first();
    const openCellName = (await firstOpenCell.getAttribute("aria-label")) ?? "";
    expect(openCellName, "the roster person has a counted day").not.toBe("");
    await firstOpenCell.fill("0.25");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/sprint-calendars/${GOLDEN.sprintId}`) && r.ok()),
      page.getByRole("button", { name: "Save calendar", exact: true }).click(),
    ]);
    await page.reload();
    await expect(page.getByRole("textbox", { name: "Default capacity (MD)" })).toHaveValue("55");
    await expect(page.getByRole("textbox", { name: openCellName, exact: true })).toHaveValue("0.25");
  });
});
