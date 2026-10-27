import AxeBuilder from "@axe-core/playwright";
import { type APIRequestContext, expect, type Page, request as playwrightRequest, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { BASE_URL } from "../playwright.config";
import { type SharedFixture, sharedFixtureFromEnv } from "../shared-fixture";

export { expect, test };

/**
 * The Playwright locks that keep `reports.spec.ts` apart from every spec that has a LIVE synced connection or changes a global derive
 * input. `reports` reads team-level figures (a team's tasks are credited by its roster, across every connection that was derived since the
 * roster existed) and drills them against the figure it just read, so a DERIVE of another spec's connection that finishes in the middle of
 * a test, or a holiday or a settings edit that moves the inputs, fails it ("the data changed since the chart was drawn"). The old
 * alphabetical order only avoided the overlap by luck (`reports` ran last); build-times.md WHY 37 reordered it and the overlap failed
 * `reports` twice in nine runs. Playwright never runs two tests that share a lock at the same time, so `reports` holds all four locks
 * and each of the four specs holds its own: they stay free to overlap each other.
 */
export const REPORTS_EXCLUSIVE_LOCKS = {
  capacity: "reports-apart-from-capacity",
  dataSources: "reports-apart-from-data-sources",
  holidays: "reports-apart-from-public-holidays",
  metricsConfig: "reports-apart-from-metrics-config",
} as const;

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@flow.local";
const PASSWORD = "changeme";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (page.url().startsWith("http")) {
    await page.evaluate(() => localStorage.clear());
    await page.goto("/login");
  } else {
    // A fresh page (about:blank) is a fresh browser context: there is no session to clear, so ONE
    // load of the SPA is enough — the clear-and-reload branch above cost a second one in every
    // login (~125 per run, ~95 of them on the axe sweeps; build-times.md WHY 7).
    await page.goto("/login");
  }
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/**
 * Sign in through the real login form. Use it where the credential or the form IS the subject (the first sign-in with a
 * generated password, a reset password, the language riding the login response); everything else takes `login`.
 */
export async function loginViaForm(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The `/login` response fields the SPA persists (`storeSession` in `web/src/api/session.ts`). */
interface LoginBody {
  token: string;
  refreshToken: string;
  roles: string[];
  userId: number;
  disabledFeatures?: string[];
}

/**
 * The `localStorage` of a signed-in SPA: the keys `storeSession` writes, from the tokens the server just issued. The session identity
 * is left out on purpose: the SPA gives a session without one a fresh identity on first use, exactly as for an older session.
 */
function sessionEntries(body: LoginBody): [string, string][] {
  return [
    ["flow.auth.token", body.token],
    ["flow.auth.refreshToken", body.refreshToken],
    ["flow.auth.roles", JSON.stringify(body.roles)],
    ["flow.auth.userId", String(body.userId)],
    ["flow.auth.disabledFeatures", JSON.stringify(body.disabledFeatures ?? [])],
  ];
}

/**
 * Sign in WITHOUT the form: the real `POST /api/v1/login` through the page's request context (so the tokens, the revocation
 * blocklist and any credential-revision check are the server's own), then the session written to `localStorage` exactly as the SPA
 * would persist it, then ONE load of `/`. The form costs a full SPA boot on `/login`, two fills, a click and a client-side redirect
 * per sign-in, and the suite signs in ~190 times (build-times.md WHY 37). The session is minted per call: `signOut` revokes the pair
 * server-side, so one reused across tests would be a revoked one. A refused login (wrong password, an MFA challenge, a rate limit) or a
 * session the shell does not accept falls back to the real form, which then reports what is wrong. Pass credentials as a regular
 * user to read as one; the specs whose subject is a credential call `loginViaForm`.
 */
export async function login(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  const minted = await page.request.post("/api/v1/login", { data: { email, password } });
  const body = minted.ok() ? ((await minted.json()) as Partial<LoginBody>) : undefined;
  if (body?.token === undefined || body.refreshToken === undefined || body.roles === undefined || body.userId === undefined) {
    return loginViaForm(page, email, password);
  }
  // `localStorage` needs a document on the app's origin; a fresh page is about:blank, and a static asset is the cheapest document
  // the origin serves (the SPA is not booted for it).
  if (!page.url().startsWith("http")) await page.goto("/favicon.svg");
  await page.evaluate((entries) => {
    localStorage.clear();
    for (const [key, value] of entries) localStorage.setItem(key, value);
  }, sessionEntries(body as LoginBody));
  await page.goto("/");
  try {
    await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  } catch {
    // Drift in the persisted keys costs one slow sign-in here, never a red spec that is not about signing in.
    await loginViaForm(page, email, password);
  }
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
 * Delete a Flow user from the Flow users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/flow-users");
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
  await page.goto("/flow-users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/flow-users") && r.request().method() === "POST" && r.ok(),
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
  await expect(page).toHaveURL(/\/flow-users$/);
  return { id, name, email, password };
}

/**
 * Makes Home list exactly these teams: writes the remembered pick (`flow.viewSettings.home.teams`, the same key the team picker
 * stores) and reloads Home. Home's default is the viewer's own teams, then the first three by name, so a flow that reads a
 * particular team's rows pins it here instead of depending on how many other teams the shared stack holds. Call it after `login`.
 */
export async function showTeamsOnHome(page: Page, teamIds: readonly number[]): Promise<void> {
  await page.evaluate((ids) => localStorage.setItem("flow.viewSettings.home.teams", JSON.stringify(ids)), [...teamIds]);
  await page.goto("/");
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
 * Drive a list row's action through its "Operations for <name>" kebab menu (the Flow users rows
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
 * Teardown through the API for the rows a test created, for a spec's `test.afterEach` — so a failing step leaves nothing
 * behind (an old e2e connection would keep syncing on every boot of the dev volume). `paths` are deleted IN THE GIVEN ORDER
 * (children first) through a FRESH admin session (the test's own token may have expired across a long sync wait). EVERY path is
 * attempted; 204 and 404 pass, since the test body may already have deleted a row through the UI — so callers must register
 * only paths built from a create response's id (a mistyped path would also answer 404 and go unnoticed). Any other status is
 * collected and thrown once at the end, listing all of them.
 */
export async function deleteViaApi(paths: string[]): Promise<void> {
  if (paths.length === 0) return;
  const { api } = await apiAsAdmin();
  const failures: string[] = [];
  try {
    for (const path of paths) {
      const status = (await api.delete(path)).status();
      if (status !== 204 && status !== 404) failures.push(`${path} -> ${status}`);
    }
  } finally {
    await api.dispose();
  }
  expect(failures, `teardown DELETEs that answered neither 204 nor 404`).toEqual([]);
}

const STUB_EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  referenceDate: string;
  golden: { sprint: { sprintId: number } };
};

/**
 * The stub dataset's "now" (`sample-data/jira/expected.json`'s `referenceDate`): its data ends there,
 * so a period written as an offset from it never slides off the data the way "the last 90 days" does.
 */
const STUB_REFERENCE_MS = Date.parse(STUB_EXPECTED.referenceDate);

/** The calendar day (`YYYY-MM-DD`) `days` days from the stub's reference date. */
export function stubDayOffset(days: number): string {
  return new Date(STUB_REFERENCE_MS + days * 86_400_000).toISOString().slice(0, 10);
}

/** The Jira-stub connection settings every spec that syncs it uses (the compose `jira-stub`, `sample-data/jira/expected.json`'s `connection`). */
const STUB_SITE_URL = "https://flow-e2e.atlassian.net";
const STUB_EMAIL = "svc-account@flow-e2e.example.com";
const STUB_TOKEN = "e2e-fake-api-token";
const STUB_PROJECT_KEYS = ["FLO", "PLT", "GTM", "OPS"];

/** The create body of a Jira-stub data source (unique `name`) — `syncStubDataSourceViaApi` and the shared fixture's global setup both post it. */
export function stubDataSourceBody(name: string): object {
  return {
    name,
    syncIntervalMinutes: 60,
    jira: { siteUrl: STUB_SITE_URL, email: STUB_EMAIL, apiToken: STUB_TOKEN, projectKeys: STUB_PROJECT_KEYS },
  };
}

/**
 * Wait (bounded, ~340s) until the SYNC job `jobId` — or a newer one — of the data source SUCCEEDED, polling its status every 2s.
 * Keyed on the job, not on `currentJob === null`: the DERIVEs a membership/holiday/settings edit fans out to every enabled
 * connection keep `currentJob` set for as long as they queue, and a SYNC that already finished would be waited on for nothing.
 */
async function awaitSyncSucceeded(api: APIRequestContext, dataSourceId: number, jobId: number): Promise<void> {
  const deadline = Date.now() + 340_000;
  for (;;) {
    const res = await api.get(`/api/v1/data-sources/${dataSourceId}/status`);
    // A gone source (404) or a lost session (401) has no `lastJobs`: fail now, not after the whole wait.
    if (!res.ok()) throw new Error(`status of data source ${dataSourceId} answered ${res.status()}: ${await res.text()}`);
    const status = await res.json();
    const last = status.lastJobs?.SYNC as { id?: number; status?: string; errorCode?: string | null } | undefined;
    if (last?.status === "FAILED" || last?.status === "CANCELLED") {
      throw new Error(`SYNC of data source ${dataSourceId} ended ${last.status} (${last.errorCode ?? "no error code"})`);
    }
    if (last?.status === "SUCCEEDED" && (last.id ?? 0) >= jobId) return;
    if (Date.now() > deadline) throw new Error(`SYNC of data source ${dataSourceId} did not finish in time (last status: ${last?.status ?? "none"})`);
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

/**
 * A Jira-stub data source created and fully SYNCED through the API — for specs whose subject is
 * something DOWNSTREAM of ingestion (the reports), not the ingestion journey the data-sources spec
 * already drives through the UI. Waits for the SYNC job to finish (bounded, ~340s); the caller owns
 * the connection and deletes it. `name` is unique per call.
 */
export async function syncStubDataSourceViaApi(api: APIRequestContext, namePrefix: string): Promise<{ id: number; name: string }> {
  const name = uniqueText(namePrefix);
  const created = await api.post("/api/v1/data-sources", { data: stubDataSourceBody(name) });
  expect(created.ok(), await created.text()).toBeTruthy();
  const id = (await created.json()).id as number;
  try {
    const enqueued = await api.post(`/api/v1/data-sources/${id}/sync-jobs`, { data: { kind: "SYNC" } });
    expect(enqueued.ok(), await enqueued.text()).toBeTruthy();
    await awaitSyncSucceeded(api, id, (await enqueued.json()).job.id as number);
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
 * Team capacity and the Jira-user roster are deliberately not seeded — the commitments,
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
    await mapFloBoardViaApi(api, dataSourceId, teamId, false);
  } catch (failure) {
    // The caller never receives the id of a team whose setup failed — remove it here.
    await api.delete(`/api/v1/teams/${teamId}`);
    throw failure;
  }
  return { teamId };
}

/**
 * Map the stub's FLO board to `teamId`, writing back the current effective config with only `boards` changed. With `onlyIfAbsent`
 * the PUT is skipped when the mapping is already there — the shared fixture's race-safe path: several workers reach it at once,
 * and every PUT bumps the configuration revision (a further DERIVE), so only the first ones should write.
 */
async function mapFloBoardViaApi(api: APIRequestContext, dataSourceId: number, teamId: number, onlyIfAbsent: boolean): Promise<void> {
  const optionsRes = await api.get(`/api/v1/data-sources/${dataSourceId}/metrics-config/options`);
  expect(optionsRes.ok(), await optionsRes.text()).toBeTruthy();
  const floBoard = ((await optionsRes.json()).boards as { boardId: number; projectKey?: string | null }[]).find(
    (board) => board.projectKey === "FLO",
  );
  expect(floBoard, "the stub exposes a board on the FLO project").toBeDefined();

  const currentRes = await api.get(`/api/v1/data-sources/${dataSourceId}/metrics-config`);
  expect(currentRes.ok(), await currentRes.text()).toBeTruthy();
  const current = await currentRes.json();
  if (onlyIfAbsent && (current.boards as { boardId: number; teamId: number }[]).some((board) => board.boardId === floBoard?.boardId && board.teamId === teamId)) return;
  const saved = await api.put(`/api/v1/data-sources/${dataSourceId}/metrics-config`, {
    data: {
      statusStages: current.statusStages,
      fields: current.fields,
      domains: current.domains,
      boards: [{ boardId: floBoard?.boardId, teamId }],
      activityTypes: current.activityTypes,
      workCategories: current.workCategories,
    },
  });
  if (!saved.ok() && onlyIfAbsent) {
    // Two workers that both read the config before either PUT committed race on the same insert, and the loser answers 409:
    // benign when the winner's mapping is now stored.
    const after = await (await api.get(`/api/v1/data-sources/${dataSourceId}/metrics-config`)).json();
    if ((after.boards as { boardId: number; teamId: number }[]).some((board) => board.boardId === floBoard?.boardId && board.teamId === teamId)) return;
  }
  expect(saved.ok(), await saved.text()).toBeTruthy();
}

/**
 * Wait until a DERIVE has produced `sprintId` for `teamId` — the chained DERIVE (after the sync)
 * and the one the board mapping enqueues may both run; the sprint only appears in the team's
 * commitments once a derivation ran under the mapping. Polls the report API itself (bounded,
 * ~240s), never a fixed sleep. A `400`/empty answer before that is expected and retried.
 */
export async function awaitDerivedSprint(api: APIRequestContext, teamId: number, sprintId: number): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await api.get(`/api/v1/reports/commitments?teamId=${teamId}&sprintId=${sprintId}`);
    const text = await res.text();
    if (res.ok() && ((JSON.parse(text) as { sprints: { sprintId: number }[] }).sprints ?? []).some((sprint) => sprint.sprintId === sprintId)) return;
    if (Date.now() > deadline) {
      throw new Error(`sprint ${sprintId} never appeared in team ${teamId}'s commitments within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

/**
 * Stub people who logged work in the sample data's window (`sample-data/jira/expected.json`'s
 * `teams.roster`), in the order `addStubMemberViaApi` tries them.
 */
export const STUB_MEMBER_CANDIDATES = [
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d16", displayName: "Sample User 16" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d22", displayName: "Sample User 22" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d24", displayName: "Sample User 24" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d30", displayName: "Sample User 30" },
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d04", displayName: "Sample User 4" },
  // LAST on purpose: this is the one candidate with no worklog inside the golden sprint (FLO Sprint 4), so the golden sprint's Costs
  // figures (the sprint widgets' e2e) only hold for a roster person tried before it. A run only reaches it when every earlier
  // candidate already belongs to another team.
  { accountId: "5f8a1b2c3d4e5f6a7b8c9d12", displayName: "Sample User 12" },
];

/**
 * Put one stub Jira person on `teamId` from the start of 2025, open-ended, through the API — so a
 * report that attributes authors to teams (Costs) has a real team's man-days to read. D1's exclusion
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
 * Wait until a DERIVE has run under the team's board mapping AND its member: the Costs report
 * of the team counts its members' man-days only once the roster was read. Polls the report API
 * itself (bounded, ~240s), never a fixed sleep; `window` is the `from`/`to` query the caller reads with.
 */
export async function awaitDerivedTeamCost(
  api: APIRequestContext,
  dataSourceId: number,
  teamId: number,
  window: string,
): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await api.get(`/api/v1/reports/costs?connectionId=${dataSourceId}&teamId=${teamId}&${window}`);
    const text = await res.text();
    if (res.ok() && ((JSON.parse(text) as { totalMd?: number }).totalMd ?? 0) > 0) return;
    if (Date.now() > deadline) {
      throw new Error(`team ${teamId} never logged any man-days in the Costs report within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

/**
 * Wait until the team's Costs report lists `sprintId` with an actual cost above zero (the roster person logged work inside the sprint's own
 * dates, read after the derive under the team's mapping and member). Polls the report API itself (bounded, ~240s), never a fixed sleep.
 */
export async function awaitDerivedSprintCost(api: APIRequestContext, dataSourceId: number, teamId: number, sprintId: number): Promise<void> {
  const deadline = Date.now() + 240_000;
  for (;;) {
    const res = await api.get(`/api/v1/reports/costs?connectionId=${dataSourceId}&teamId=${teamId}&sprintId=${sprintId}`);
    const text = await res.text();
    if (res.ok() && ((JSON.parse(text) as { sprints?: { sprintId: number; acMd: number }[] }).sprints ?? []).some((sprint) => sprint.sprintId === sprintId && sprint.acMd > 0)) return;
    if (Date.now() > deadline) {
      throw new Error(`sprint ${sprintId} never cost any man-days in team ${teamId}'s Costs report within 240s (last answer: ${res.status()} ${text.slice(0, 300)})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
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
  { path: "/reports/commitments", heading: "Commitments" },
  { path: "/reports/throughput", heading: "Throughput" },
  { path: "/reports/sprint-consistency", heading: "Sprint consistency" },
  { path: "/reports/cycle-time", heading: "Cycle time" },
  { path: "/reports/task-estimation-accuracy", heading: "Task estimation accuracy" },
  { path: "/reports/task-estimates", heading: "Task estimates" },
  { path: "/reports/epic-estimation-accuracy", heading: "Epic estimation accuracy" },
  { path: "/reports/estimate-adjustments", heading: "Estimate adjustments" },
  { path: "/reports/reported-time-ratio", heading: "Reported time" },
  { path: "/reports/wip", heading: "WIP" },
  { path: "/reports/backlog", heading: "Estimated backlog" },
  { path: "/reports/aging-wip", heading: "Aging WIP" },
  { path: "/reports/blocked-time", heading: "Blocked time" },
  { path: "/reports/epic-progress", heading: "Epic progress" },
  { path: "/reports/data-quality", heading: "Data quality" },
  { path: "/reports/costs", heading: "Costs" },
  // Without people picked: the pickers and their empty state.
  { path: "/reports/compare-people", heading: "Compare people" },
  // Likewise without a person: the picker and its empty state.
  { path: "/reports/person-trend", heading: "Person trend" },
];

/** The people reports (Compare people, Person trend) read people across every team: they are never scoped to a team. */
const PEOPLE_REPORT_PATHS: ReadonlySet<string> = new Set(["/reports/compare-people", "/reports/person-trend"]);

/** The reports that are never read for the whole unit: a link without a scope is rewritten (replace) to name a team, a domain or an epic before any request. */
const SCOPED_REPORT_PATHS: ReadonlySet<string> = new Set(
  REPORT_PAGES.map(({ path }) => path).filter((path) => !PEOPLE_REPORT_PATHS.has(path)),
);

/**
 * A report has settled once its page title is up, no spinner is left (filters, data, lazy charts) and nothing failed — what a scan waits for.
 * A report that needs a team (or a domain) first renders, then rewrites its URL with the default team and only then fetches, so the title and an
 * idle page alone can pass between the two: the scope in the address (or the "no team yet" empty state) is waited for first.
 */
export const reportSettled = (heading: string) => async (page: Page) => {
  await expect(page.getByRole("heading", { level: 2, name: heading, exact: true })).toBeVisible();
  if (SCOPED_REPORT_PATHS.has(new URL(page.url()).pathname)) {
    await expect
      .poll(async () => /[?&](teamId|domain|epicId)=/.test(page.url()) || (await page.getByText(/there is (no team|neither) yet/).count()) > 0, {
        message: "the report is scoped to a team, a domain or an epic",
      })
      .toBe(true);
  }
  await expect(page.getByRole("status", { name: "Loading…" })).toHaveCount(0);
  await expect(page.getByRole("alert")).toHaveCount(0);
};

/**
 * Pause a data source's syncing through the API (a full replace of its settings with `enabled:
 * false`; a blank token keeps the stored one). A paused connection keeps everything it derived and
 * stays readable by `connectionId`, but no config bump elsewhere in the suite re-derives it — the
 * shared fixture connection is paused once its derive is done. Idempotent: an already paused source is left alone.
 */
async function pauseDataSourceViaApi(api: APIRequestContext, id: number): Promise<void> {
  const current = await api.get(`/api/v1/data-sources/${id}`);
  expect(current.ok(), await current.text()).toBeTruthy();
  const source = await current.json();
  if (source.enabled === false) return;
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

/**
 * The shared read-only fixture's connection, once its SYNC (enqueued by global setup) has SUCCEEDED — for the specs that need only the
 * synced raw/norm data (the Jira-user roster, the Atlassian Teams), not a derivation. Never mutate the connection or its team.
 */
export async function sharedFixtureSynced(api: APIRequestContext): Promise<SharedFixture> {
  const fixture = sharedFixtureFromEnv();
  await awaitSyncSucceeded(api, fixture.dataSourceId, fixture.syncJobId);
  return fixture;
}

/**
 * The shared read-only fixture, synced, derived and paused — what `reports-drill`, `reports-compare` and `accessibility-data` read
 * instead of syncing a connection each. Global setup created the connection and its team and enqueued the SYNC without waiting; the
 * first consumer to get here finishes the preparation, and the rest find it done. Idempotent and race-safe across the parallel workers:
 * the SYNC is awaited by job id, the FLO board mapping is written only while absent (two first writers can race on the same insert; the 409 loser re-reads and accepts
 * the winner's mapping), the golden sprint appearing under the mapping proves the DERIVE, and pausing skips an already paused
 * source. Paused, the connection leaves every DERIVE fan-out, so no other spec's edit rewrites what the consumers read.
 */
export async function sharedFixtureReady(api: APIRequestContext): Promise<SharedFixture> {
  const fixture = await sharedFixtureSynced(api);
  await mapFloBoardViaApi(api, fixture.dataSourceId, fixture.teamId, true);
  await awaitDerivedSprint(api, fixture.teamId, STUB_EXPECTED.golden.sprint.sprintId);
  await pauseDataSourceViaApi(api, fixture.dataSourceId);
  return fixture;
}
