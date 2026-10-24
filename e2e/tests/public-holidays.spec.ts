// The Public holidays dictionary (Administration > Public holidays) and what a holiday does to the Capacity grid. A holiday
// is always a non-working day: an administrator adds one on a weekday inside a stub sprint's calendar window, the Capacity
// grid then shows that column locked off and named (and the live total drops it), and deleting the holiday frees the day
// again; a regular user only reads the list. The grid half is API-seeded exactly like capacity.spec.ts (a synced stub
// connection, a team with the FLO board mapped to it and one stub person on its roster) but reads the OLDEST non-golden sprint of
// that team, so the holiday never lands inside the window capacity.spec.ts asserts on. The holiday exists only for the
// duration of the one test (it is global: it re-derives every connection, then again when deleted) and is swept by name.
// Owns: its own Jira-stub data source (`e2e-holidays-ds-*`), team (`e2e-holidays-team-*`) with one stub person, the holiday
// rows it adds (named `E2E Holiday-*`, deleted by the test, again in afterAll, and swept if an aborted run stranded some)
// and one throwaway regular user.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Page } from "@playwright/test";
import {
  addStubMemberViaApi,
  apiAsAdmin,
  awaitDerivedSprint,
  axeViolations,
  configureMetricsViaApi,
  deleteViaApi,
  expect,
  login,
  REPORTS_EXCLUSIVE_LOCKS,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

// The holiday dictionary is global: the holiday this spec adds re-derives every connection, `reports`'s included (and this spec's own
// connection is live): never beside `reports.spec.ts` (helpers.ts, `REPORTS_EXCLUSIVE_LOCKS`).
test.describe.configure({ lock: REPORTS_EXCLUSIVE_LOCKS.holidays });

const GOLDEN = (
  JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
    golden: { sprint: { sprintId: number; name: string } };
  }
).golden.sprint;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);

const HOLIDAY_PREFIX = "E2E Holiday";

type Holiday = { id: number; date: string; name: string };

async function listHolidays(admin: APIRequestContext): Promise<Holiday[]> {
  const res = await admin.get("/api/v1/public-holidays");
  expect(res.ok(), await res.text()).toBeTruthy();
  return ((await res.json()) as { items: Holiday[] }).items;
}

/** Remove every holiday this spec (or an aborted earlier run of it) left behind: 204 and 404 both pass. */
async function sweepHolidays(): Promise<void> {
  const { api } = await apiAsAdmin();
  try {
    const stranded = (await listHolidays(api)).filter((h) => h.name.startsWith(HOLIDAY_PREFIX));
    await deleteViaApi(stranded.map((h) => `/api/v1/public-holidays/${h.id}`));
  } finally {
    await api.dispose();
  }
}

/** Monday to Friday — the stack's default working days (Saturday and Sunday are the default weekend). */
const isWeekday = (day: string) => ![0, 6].includes(new Date(`${day}T00:00:00Z`).getUTCDay());

/** The calendar's days (`YYYY-MM-DD`, first to last) from the caption "name · from → to" under the Sprint calendar title. */
async function windowDays(page: Page, sprintName: string): Promise<string[]> {
  const caption = page.locator("p:visible").filter({ hasText: new RegExp(`^${sprintName} · \\d{4}-\\d{2}-\\d{2} → \\d{4}-\\d{2}-\\d{2}$`) });
  const match = /(\d{4}-\d{2}-\d{2}) → (\d{4}-\d{2}-\d{2})$/.exec(await caption.first().innerText());
  expect(match, "the calendar names its window").not.toBeNull();
  const days: string[] = [];
  for (let at = Date.parse(match?.[1] ?? ""); at <= Date.parse(match?.[2] ?? ""); at += 86_400_000) days.push(new Date(at).toISOString().slice(0, 10));
  return days;
}

test.describe("a public holiday is a locked, named day in the Capacity grid", () => {
  let api: APIRequestContext | undefined;
  // Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
  let dataSourceId: number | undefined;
  let teamId = 0;
  let membershipId: number | undefined;
  let sprint = { sprintId: 0, name: "" };

  test.beforeAll(async () => {
    // A full stub sync plus the derivation that follows it: each wait is bounded (340s / 240s).
    test.setTimeout(700_000);
    await sweepHolidays();
    ({ api } = await apiAsAdmin());
    const admin = api;
    ({ id: dataSourceId } = await syncStubDataSourceViaApi(admin, "e2e-holidays-ds"));
    ({ teamId } = await configureMetricsViaApi(admin, dataSourceId, uniqueText("e2e-holidays-team")));
    ({ membershipId } = await addStubMemberViaApi(admin, teamId));
    await awaitDerivedSprint(admin, teamId, GOLDEN.sprintId);
    // Any other sprint of the team: capacity.spec.ts reads the golden one concurrently and asserts every weekday of it counted.
    const sprints = await admin.get(`/api/v1/teams/${teamId}/sprint-calendars?page=1&pageSize=100&sort=-startAt`);
    expect(sprints.ok(), await sprints.text()).toBeTruthy();
    const others = ((await sprints.json()) as { items: { sprintId: number; name: string; startAt: number | null; endAt: number | null }[] }).items.filter(
      (s) => s.sprintId !== GOLDEN.sprintId && s.startAt !== null && s.endAt !== null && s.endAt - s.startAt <= 30 * 86_400_000,
    );
    expect(others.length, "the team has a second sprint with a calendar window").toBeGreaterThan(0);
    // The OLDEST such sprint: a holiday dated that early falls before most open-item and epic windows, so the global re-derive it
    // triggers (twice, with the delete) moves the fewest other figures.
    sprint = others.reduce((oldest, s) => ((s.startAt ?? 0) < (oldest.startAt ?? 0) ? s : oldest));
  });

  test.afterAll(async () => {
    try {
      // A FRESH admin session: the access token beforeAll minted may have expired across the bounded waits.
      // Children first: the connection (soft delete), the roster membership (before the team: a person belongs to one team
      // at a time, globally), then the team.
      await deleteViaApi([
        ...(dataSourceId === undefined ? [] : [`/api/v1/data-sources/${dataSourceId}`]),
        ...(membershipId === undefined || teamId === 0 ? [] : [`/api/v1/teams/${teamId}/jira-memberships/${membershipId}`]),
        ...(teamId === 0 ? [] : [`/api/v1/teams/${teamId}`]),
      ]);
    } finally {
      // The holiday is global and re-derives every connection: it is swept even when the deletes above failed.
      try {
        await sweepHolidays();
      } finally {
        await api?.dispose();
      }
    }
  });

  test("an administrator adds a public holiday, the Capacity grid locks that day and deleting it frees the day", async ({ page }) => {
    test.setTimeout(180_000);
    const calendarUrl = `/capacity?teamId=${teamId}&sprintId=${sprint.sprintId}`;
    const name = uniqueText(HOLIDAY_PREFIX);

    await login(page);
    // The calendar before any holiday: read its window, then choose a weekday of it that is not already a holiday.
    await page.goto(calendarUrl);
    await expect(page.getByRole("heading", { level: 3, name: "Sprint calendar", exact: true })).toBeVisible();
    const days = await windowDays(page, sprint.name);
    const { api: reader } = await apiAsAdmin();
    let taken: Set<string>;
    try {
      taken = new Set((await listHolidays(reader)).map((h) => h.date));
    } finally {
      await reader.dispose();
    }
    const workingDays = days.filter((day) => isWeekday(day) && !taken.has(day));
    expect(workingDays.length, "the sprint has working days").toBeGreaterThan(2);
    // The earliest working day of the (oldest) sprint's window.
    const date = workingDays[0];
    const year = date.slice(0, 4);
    await expect(page.getByText(`This calendar: ${md(workingDays.length)} MD`)).toBeVisible();
    await expect(page.getByRole("checkbox", { name: `Count ${date}`, exact: true })).toBeChecked();

    // Add it on the Public holidays page.
    await page.goto("/public-holidays");
    await expect(page.getByRole("heading", { level: 2, name: "Public holidays", exact: true })).toBeVisible();
    await page.getByRole("textbox", { name: "Date", exact: true }).fill(date);
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/public-holidays") && r.status() === 201),
      page.getByRole("button", { name: "Add holiday", exact: true }).click(),
    ]);
    await expect(page.getByText("Public holiday added — reports re-derive shortly")).toBeVisible();
    // The table follows the new row to its year.
    await expect(page.getByRole("combobox", { name: "Year", exact: true })).toHaveValue(year);
    const table = page.getByRole("table", { name: `Public holidays of ${year}`, exact: true });
    await expect(table.getByRole("row", { name: new RegExp(`^${date} .* ${name}`) })).toBeVisible();
    // The same date a second time is refused inline (409), with no toast.
    await page.getByRole("textbox", { name: "Date", exact: true }).fill(date);
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(`${name} again`);
    await page.getByRole("button", { name: "Add holiday", exact: true }).click();
    await expect(page.getByText("A holiday already exists on this date.")).toBeVisible();

    // The Capacity grid: the column is locked off, named, and the live total dropped the day.
    await page.goto(calendarUrl);
    const locked = page.getByRole("checkbox", { name: `${date} is a public holiday: ${name}`, exact: true });
    await expect(locked).not.toBeChecked();
    await expect(locked).toBeDisabled();
    await expect(page.locator(`th[title="Public holiday: ${name}"]`)).toBeVisible();
    await expect(page.getByText("Public holiday (always not counted)")).toBeVisible();
    await expect(page.getByText(`This calendar: ${md(workingDays.length - 1)} MD`)).toBeVisible();
    await expect(page.getByRole("textbox", { name: new RegExp(`^Availability of .* on ${date}$`) })).toBeDisabled();

    // Delete it again behind its confirmation.
    await page.goto("/public-holidays");
    await page.getByRole("combobox", { name: "Year", exact: true }).click();
    await page.getByRole("option", { name: year, exact: true }).click();
    await page.getByRole("button", { name: `Delete the holiday ${name}`, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Delete this holiday?" });
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && r.url().includes("/api/v1/public-holidays/") && r.status() === 204),
      dialog.getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    await expect(page.getByText("Public holiday deleted — reports re-derive shortly")).toBeVisible();
    await expect(page.getByText(name, { exact: true })).toHaveCount(0);

    // The day is an ordinary working day again.
    await page.goto(calendarUrl);
    await expect(page.getByRole("checkbox", { name: `Count ${date}`, exact: true })).toBeChecked();
    await expect(page.getByText(`This calendar: ${md(workingDays.length)} MD`)).toBeVisible();
  });
});

test.describe("the public holidays list for a regular user", () => {
  let userId: number | undefined;
  let credentials = { email: "", password: "e2e-only-password" };

  test.beforeAll(async () => {
    const { api } = await apiAsAdmin();
    try {
      const name = uniqueText("e2e-holidays-reader");
      credentials = { email: `${name.toLowerCase()}@flow.local`, password: credentials.password };
      const created = await api.post("/api/v1/flow-users", { data: { name, email: credentials.email, password: credentials.password, roles: [] } });
      expect(created.status(), await created.text()).toBe(201);
      userId = (await created.json()).id as number;
    } finally {
      await api.dispose();
    }
  });

  test.afterAll(async () => {
    await deleteViaApi(userId === undefined ? [] : [`/api/v1/flow-users/${userId}`]);
  });

  test("a regular user reads the seeded public holidays but cannot change them", async ({ page }) => {
    await login(page, credentials.email, credentials.password);
    // Any signed-in user finds the page in the Administration section.
    await page.getByRole("group", { name: "Administration" }).getByRole("link", { name: "Public holidays", exact: true }).click();
    await expect(page).toHaveURL(/\/public-holidays$/);
    await expect(page.getByRole("heading", { level: 2, name: "Public holidays", exact: true })).toBeVisible();
    await expect(page.getByText(/The list is maintained by administrators\./)).toBeVisible();

    // The migration seeds the Polish statutory holidays 2017-2030: pick a year and read one.
    await page.getByRole("combobox", { name: "Year", exact: true }).click();
    await page.getByRole("option", { name: "2026", exact: true }).click();
    const table = page.getByRole("table", { name: "Public holidays of 2026", exact: true });
    await expect(table.getByRole("row", { name: /^2026-11-01 .* Wszystkich Świętych$/ })).toBeVisible();

    // Read-only: no add strip, no delete.
    await expect(page.getByRole("button", { name: "Add holiday", exact: true })).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: "Date", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Delete the holiday / })).toHaveCount(0);
    expect(await axeViolations(page)).toEqual([]);
  });
});
