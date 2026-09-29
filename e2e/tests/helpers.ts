import { type APIRequestContext, expect, type Page, request as playwrightRequest, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
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
