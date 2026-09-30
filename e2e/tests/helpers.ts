import AxeBuilder from "@axe-core/playwright";
import { type APIRequestContext, expect, type Page, request as playwrightRequest, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { BASE_URL } from "../playwright.config";

export { expect, test };

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@flow.local";
const PASSWORD = "changeme";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (!page.url().startsWith("http")) await page.goto("/login");
  await page.evaluate(() => localStorage.clear());
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/** Sign in through the real login form. */
export async function login(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The header account-menu trigger — visible only inside the authenticated shell (v1.19.0). */
export function accountMenu(page: Page) {
  return page.getByRole("button", { name: "Account menu" });
}

/** Sign out through the account menu (the former header Logout button). */
export async function signOut(page: Page): Promise<void> {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

/**
 * Delete a user from the Users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

/**
 * Create a throwaway user through the real UI (an admin must be signed in) and capture the
 * generated password from the one-time reveal modal. Never mutate seeded accounts — use this.
 * The id comes from the POST response; the password from the dialog after "Show password".
 */
export async function createUserViaUi(
  page: Page,
  namePrefix = "E2E User",
): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@flow.local`;
  await page.goto("/users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/users") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const id: number = (await created.json()).id;
  const dialog = page.getByRole("dialog");
  // Masked as "*" until revealed — click the eye toggle first.
  await dialog.getByRole("button", { name: "Show password" }).click();
  await expect(dialog.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
  const password = (await dialog.locator("code").textContent()) ?? "";
  // Keep the credential out of assertion output: validate only the captured value's shape.
  expect(password.length > 0 && !/^\*+$/.test(password), "generated password is revealed").toBe(true);
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

/**
 * Ensure a list view's filter panel is expanded. Idempotent on purpose: the open/collapsed
 * state persists per view in localStorage (flow.viewSettings.*), so within one test a
 * revisited page restores the panel open — a blind toggle click would close it again.
 */
export async function openFilters(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "Filters" });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Drive a list row's action through its "Operations for <name>" kebab menu (the Users rows
 * today; list rows carry interpolated item names, hence the open `string` union member).
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation: "Edit" | "Delete" | (string & {}),
): Promise<void> {
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  // A missing row fails HERE with its name, not as an anonymous click timeout at the end of the test budget.
  await expect(trigger).toBeVisible();
  // Ensure THIS row's menu actually opened: a previous row's still-fading dropdown treats
  // the first click as its outside-click and swallows it, leaving the WRONG menu mounted —
  // an unscoped menuitem click would then drive the other row's operation. Bounded: an
  // unbounded toPass() retries until the TEST budget is gone and reports the wrong step.
  await expect(async () => {
    if ((await trigger.getAttribute("aria-expanded")) !== "true") await trigger.click();
    expect(await trigger.getAttribute("aria-expanded")).toBe("true");
  }).toPass({ timeout: 10_000 });
  const dropdownId = await trigger.getAttribute("aria-controls");
  await page.locator(`[id="${dropdownId}"]`).getByRole("menuitem", { name: operation }).click();
}

/**
 * API-side seeding for specs whose subject is a PAGE, not the journey that creates its data (the
 * accessibility sweep): one admin-authenticated request context against the same stack. Every id
 * it creates is the caller's to delete directly through the same API — the Owns rule applies unchanged.
 */
export async function apiAsAdmin(): Promise<{ api: APIRequestContext; userId: number }> {
  const anonymous = await playwrightRequest.newContext({ baseURL: BASE_URL });
  const login = await anonymous.post("/api/v1/login", { data: { email: ADMIN, password: PASSWORD } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const { token, userId } = (await login.json()) as { token: string; userId: number };
  await anonymous.dispose();
  const api = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  return { api, userId };
}

/**
 * The stub dataset's "now" (`sample-data/jira/expected.json`'s `referenceDate`): its data ends there,
 * so a period written as an offset from it never slides off the data the way "the last 90 days" does.
 */
const STUB_REFERENCE_MS = Date.parse(
  (JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as { referenceDate: string }).referenceDate,
);

/** The calendar day (`YYYY-MM-DD`) `days` days from the stub's reference date. */
export function stubDayOffset(days: number): string {
  return new Date(STUB_REFERENCE_MS + days * 86_400_000).toISOString().slice(0, 10);
}

/** The Jira-stub connection settings every spec that syncs it uses (the compose `jira-stub`, `sample-data/jira/expected.json`'s `connection`). */
const STUB_SITE_URL = "https://flow-e2e.atlassian.net";
const STUB_EMAIL = "svc-account@flow-e2e.example.com";
const STUB_TOKEN = "e2e-fake-api-token";
const STUB_PROJECT_KEYS = ["FLO", "PLT", "GTM", "OPS"];

/**
 * A Jira-stub data source created and fully SYNCED through the API — for specs whose subject is
 * something DOWNSTREAM of ingestion (the reports), not the ingestion journey the data-sources spec
 * already drives through the UI. Waits for the SYNC job to finish (bounded, ~340s); the caller owns
 * the connection and deletes it. `name` is unique per call.
 */
export async function syncStubDataSourceViaApi(api: APIRequestContext, namePrefix: string): Promise<{ id: number; name: string }> {
  const name = uniqueText(namePrefix);
  const created = await api.post("/api/v1/data-sources", {
    data: {
      name,
      syncIntervalMinutes: 60,
      jira: { siteUrl: STUB_SITE_URL, email: STUB_EMAIL, apiToken: STUB_TOKEN, projectKeys: STUB_PROJECT_KEYS },
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const id = (await created.json()).id as number;
  try {
    const enqueued = await api.post(`/api/v1/data-sources/${id}/sync-jobs`, { data: { kind: "SYNC" } });
    expect(enqueued.ok(), await enqueued.text()).toBeTruthy();
    const deadline = Date.now() + 340_000;
    for (;;) {
      const status = await (await api.get(`/api/v1/data-sources/${id}/status`)).json();
      const last = status.lastJobs?.SYNC as { status?: string; errorCode?: string | null } | undefined;
      if (last?.status === "FAILED" || last?.status === "CANCELLED") {
        throw new Error(`SYNC of data source ${id} ended ${last.status} (${last.errorCode ?? "no error code"})`);
      }
      if (status.currentJob === null && last?.status === "SUCCEEDED") break;
      if (Date.now() > deadline) throw new Error(`SYNC of data source ${id} did not finish in time (last status: ${last?.status ?? "none"})`);
      await new Promise((resolve) => setTimeout(resolve, 5_000));
    }
  } catch (failure) {
    // The caller never receives the id of a connection that failed to sync — remove it here.
    await api.delete(`/api/v1/data-sources/${id}`);
    throw failure;
  }
  return { id, name };
}

/**
 * The minimal metrics configuration reports need, through the API: a fresh team and the stub's FLO
 * board mapped to it (D10 — a sprint is a team's sprint only through its board). Every other
 * setting keeps the COMPUTED defaults (the config PUT is a full replace, so the current effective
 * config is read first and written back with only `boards` changed). The caller owns the team.
 * Sprint capacities and the Jira-user roster are deliberately not seeded — the velocity,
 * throughput, sprint-consistency, estimation and cycle-time figures do not read them.
 */
export async function configureMetricsViaApi(
  api: APIRequestContext,
  dataSourceId: number,
  teamName: string,
): Promise<{ teamId: number }> {
  const createdTeam = await api.post("/api/v1/teams", { data: { name: teamName } });
  expect(createdTeam.ok(), await createdTeam.text()).toBeTruthy();
  const teamId = (await createdTeam.json()).id as number;
  try {
    const optionsRes = await api.get(`/api/v1/data-sources/${dataSourceId}/metrics-config/options`);
    expect(optionsRes.ok(), await optionsRes.text()).toBeTruthy();
    const floBoard = ((await optionsRes.json()).boards as { boardId: number; projectKey?: string | null }[]).find(
      (board) => board.projectKey === "FLO",
    );
    expect(floBoard, "the stub exposes a board on the FLO project").toBeDefined();

    const currentRes = await api.get(`/api/v1/data-sources/${dataSourceId}/metrics-config`);
    expect(currentRes.ok(), await currentRes.text()).toBeTruthy();
    const current = await currentRes.json();
    const saved = await api.put(`/api/v1/data-sources/${dataSourceId}/metrics-config`, {
      data: {
        statusStages: current.statusStages,
        fields: current.fields,
        domains: current.domains,
        boards: [{ boardId: floBoard?.boardId, teamId }],
        activityTypes: current.activityTypes,
        workCategories: current.workCategories,
        blockedStatuses: current.blockedStatuses,
        sprintCapacities: current.sprintCapacities,
      },
    });
    expect(saved.ok(), await saved.text()).toBeTruthy();
  } catch (failure) {
    // The caller never receives the id of a team whose setup failed — remove it here.
    await api.delete(`/api/v1/teams/${teamId}`);
    throw failure;
  }
  return { teamId };
}

/**
 * Wait until a DERIVE has produced `sprintId` for `teamId` — the chained DERIVE (after the sync)
 * and the one the board mapping enqueues may both run; the sprint only appears in the team's
 * velocity once a derivation ran under the mapping. Polls the report API itself (bounded,
 * ~240s), never a fixed sleep. A `400`/empty answer before that is expected and retried.
 */
export async function awaitDerivedSprint(api: APIRequestContext, teamId: number, sprintId: number): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await api.get(`/api/v1/reports/velocity?teamId=${teamId}&sprintId=${sprintId}`);
    const text = await res.text();
    if (res.ok() && ((JSON.parse(text) as { sprints: { sprintId: number }[] }).sprints ?? []).some((sprint) => sprint.sprintId === sprintId)) return;
    if (Date.now() > deadline) {
      throw new Error(`sprint ${sprintId} never appeared in team ${teamId}'s velocity within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
}

/**
 * Stub people who logged work in the sample data's window (`sample-data/jira/expected.json`'s
 * `teams.roster`), in the order `addStubMemberViaApi` tries them.
 */
const STUB_MEMBER_CANDIDATES = [
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d16", displayName: "Sample User 16" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d22", displayName: "Sample User 22" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d24", displayName: "Sample User 24" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d12", displayName: "Sample User 12" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d30", displayName: "Sample User 30" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d04", displayName: "Sample User 4" },
];

/**
 * Put one stub Jira person on `teamId` from the start of 2025, open-ended, through the API — so a
 * report that attributes authors to teams (the cost matrix) has a real team row. D1's exclusion
 * constraint scopes a person to ONE team at any instant GLOBALLY, and a deleted team keeps its
 * membership rows, so a person left over from an aborted run answers `409`: the next candidate is
 * tried instead of failing the run. The caller deletes the membership BEFORE the team.
 */
export async function addStubMemberViaApi(
  api: APIRequestContext,
  teamId: number,
): Promise<{ membershipId: number; accountId: string; displayName: string }> {
  const validFrom = Date.UTC(2025, 0, 1);
  for (const candidate of STUB_MEMBER_CANDIDATES) {
    const created = await api.post(`/api/v1/teams/${teamId}/jira-memberships`, {
      data: { accountId: candidate.accountId, validFrom, validTo: null },
    });
    if (created.status() === 409) continue;
    expect(created.status(), await created.text()).toBe(201);
    return { membershipId: (await created.json()).id as number, ...candidate };
  }
  throw new Error("every stub member candidate already belongs to another team — clear the leftover memberships");
}

/**
 * Wait until a DERIVE has run under the team's board mapping AND its member: the cost matrix
 * names the team as an author team only once the roster was read. Polls the report API itself
 * (bounded, ~240s), never a fixed sleep; `window` is the `from`/`to` query the caller reads with.
 */
export async function awaitDerivedTeamCost(
  api: APIRequestContext,
  dataSourceId: number,
  teamId: number,
  window: string,
): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await api.get(`/api/v1/reports/cost-matrix?connectionId=${dataSourceId}&${window}`);
    const text = await res.text();
    if (res.ok() && ((JSON.parse(text) as { rows: { teamId: number | null }[] }).rows ?? []).some((row) => row.teamId === teamId)) return;
    if (Date.now() > deadline) {
      throw new Error(`team ${teamId} never appeared in the cost matrix within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
}

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

/**
 * An axe WCAG 2.0/2.1 A+AA scan of the open page (or of `include`, a selector), as one readable
 * line per violation with the offending nodes — `[]` when clean. No waivers, `color-contrast`
 * included: the theme's tokens are AA-tested in `web/src/theme.test.ts`, so a finding is fixed at
 * the token level, never by patching single elements.
 */
export async function axeViolations(page: Page, include?: string): Promise<{ id: string; impact: string | null | undefined; help: string; nodes: string[] }[]> {
  const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
  const results = await (include ? builder.include(include) : builder).analyze();
  return results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
}

export type Scheme = "light" | "dark";

/** The scheme the page must actually render in — Mantine stamps it on <html> (the `auto` default follows Playwright's emulated `colorScheme`). */
export async function expectScheme(page: Page, scheme: Scheme): Promise<void> {
  await expect(page.locator("html")).toHaveAttribute("data-mantine-color-scheme", scheme);
}

/** Every report route (nav model: `web/src/utils/reportLinks.ts`) with its page title. */
export const REPORT_PAGES: { path: string; heading: string }[] = [
  { path: "/reports/velocity", heading: "Velocity" },
  { path: "/reports/throughput", heading: "Throughput" },
  { path: "/reports/sprint-consistency", heading: "Sprint consistency" },
  { path: "/reports/cycle-time", heading: "Cycle time" },
  { path: "/reports/task-estimation-accuracy", heading: "Task estimation accuracy" },
  { path: "/reports/epic-estimation-accuracy", heading: "Epic estimation accuracy" },
  { path: "/reports/estimate-adjustments", heading: "Estimate adjustments" },
  { path: "/reports/reported-time-ratio", heading: "Reported time" },
  { path: "/reports/wip", heading: "WIP" },
  { path: "/reports/backlog", heading: "Estimated backlog" },
  { path: "/reports/aging-wip", heading: "Aging WIP" },
  { path: "/reports/blocked-time", heading: "Blocked time" },
  { path: "/reports/epic-progress", heading: "Epic progress" },
  { path: "/reports/data-quality", heading: "Data quality" },
  { path: "/reports/cost-matrix", heading: "Cost matrix" },
];

/** A report has settled once its page title is up, no spinner is left (filters, data, lazy charts) and nothing failed — what a scan waits for. */
export const reportSettled = (heading: string) => async (page: Page) => {
  await expect(page.getByRole("heading", { level: 2, name: heading, exact: true })).toBeVisible();
  await expect(page.getByRole("status", { name: "Loading…" })).toHaveCount(0);
  await expect(page.getByRole("alert")).toHaveCount(0);
};

/**
 * Pause a data source's syncing through the API (a full replace of its settings with `enabled:
 * false`; a blank token keeps the stored one). A paused connection keeps everything it derived and
 * stays readable by `connectionId`, but no config bump elsewhere in the suite re-derives it — the
 * fixture connections of page-scanning specs are paused once their derive is done.
 */
export async function pauseDataSourceViaApi(api: APIRequestContext, id: number): Promise<void> {
  const current = await api.get(`/api/v1/data-sources/${id}`);
  expect(current.ok(), await current.text()).toBeTruthy();
  const source = await current.json();
  const saved = await api.put(`/api/v1/data-sources/${id}`, {
    data: {
      name: source.name,
      enabled: false,
      syncIntervalMinutes: source.syncIntervalMinutes,
      backfillFrom: source.backfillFrom,
      reconcileHourUtc: source.reconcileHourUtc,
      // Spelled out: the response's read-only fields (`hasApiToken`, `cloudId`) are not accepted back.
      jira: {
        siteUrl: source.jira.siteUrl,
        email: source.jira.email,
        apiToken: "",
        projectKeys: source.jira.projectKeys,
        authScheme: source.jira.authScheme,
      },
    },
  });
  expect(saved.ok(), await saved.text()).toBeTruthy();
}
