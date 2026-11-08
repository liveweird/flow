// The Administration > Service tokens registry: an administrator issues a read-only token for another application, the token
// reads Flow's teams through the token-only /api/v1/integration namespace, and revoking it ends that access on the very next
// request. The secret is typed nowhere and printed nowhere: it is read from the one-time reveal panel into a variable that only
// feeds the Authorization header, and every assertion about it is on a boolean (a failed `toBe` on the value would echo it). A failure
// trace's DOM snapshot can still hold the revealed value, but `afterAll` has revoked that token before anyone opens the trace.
// Owns: its `e2e-token-*` rows. A token is never deleted (revoke is terminal, the registry has no DELETE), so each run uses a
// unique name, which also keeps re-runs clear of the live-name unique index; the test revokes its token, and `afterAll` revokes any
// `e2e-token-*` row an aborted run left live.
import { request as playwrightRequest, type APIRequestContext } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsAdmin, expect, login, test, uniqueText } from "./helpers";

const NAME_PREFIX = "e2e-token";

type Row = { id: number; name: string; revoked: boolean };

/** Revoke every still-live `e2e-token-*` row (the test's own and any an aborted run stranded): 204 and 409 both pass. */
async function revokeStrandedTokens(): Promise<void> {
  const { api } = await apiAsAdmin();
  try {
    const listed = await api.get("/api/v1/service-tokens");
    expect(listed.ok(), await listed.text()).toBeTruthy();
    const live = ((await listed.json()) as { items: Row[] }).items.filter((token) => token.name.startsWith(`${NAME_PREFIX}-`) && !token.revoked);
    for (const token of live) {
      const status = (await api.post(`/api/v1/service-tokens/${token.id}/revoke`)).status();
      expect([204, 404, 409], `revoking token ${token.id}`).toContain(status);
    }
  } finally {
    await api.dispose();
  }
}

test.afterAll(async () => {
  await revokeStrandedTokens();
});

test("admin creates a service token, reads Flow's teams with it, and revokes it", async ({ page }) => {
  const name = uniqueText(NAME_PREFIX);
  let consumer: APIRequestContext | undefined;
  try {
    await login(page);
    await page.getByRole("group", { name: "Administration" }).getByRole("link", { name: "Service tokens", exact: true }).click();
    await expect(page).toHaveURL(/\/service-tokens$/);
    await expect(page.getByRole("heading", { level: 2, name: "Service tokens", exact: true })).toBeVisible();
    await expect(page.getByText(/The secret is shown once\./)).toBeVisible();

    // Create it with no expiry (the default choice).
    await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
    await expect(page.getByRole("combobox", { name: "Expires", exact: true })).toHaveValue("Never");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/service-tokens") && r.status() === 201),
      page.getByRole("button", { name: "Create token", exact: true }).click(),
    ]);

    // The reveal panel: masked until the eye toggle, then the one moment the secret is visible.
    const panel = page.getByRole("alert").filter({ hasText: `Token created: ${name}` });
    await expect(panel).toBeVisible();
    await expect(panel.getByText(/Copy it now, it is never shown again\./)).toBeVisible();
    await panel.getByRole("button", { name: "Show password", exact: true }).click();
    const secret = (await panel.locator("code").innerText()).trim();
    expect(secret.startsWith("flow_int_"), "the revealed value is a flow_int_ token").toBe(true);

    // The registry row: active, never expires, never used.
    const row = page.getByRole("row").filter({ hasText: name });
    await expect(row.getByText("Active", { exact: true })).toBeVisible();
    await expect(row.getByText("Never", { exact: true })).toHaveCount(2);

    // Another application reads Flow with it: its own identity, then the teams document.
    consumer = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${secret}` } });
    const me = await consumer.get("/api/v1/integration/me");
    expect(me.status(), "GET /integration/me with the live token").toBe(200);
    expect(((await me.json()) as { name: string }).name).toBe(name);
    const teams = await consumer.get("/api/v1/integration/teams");
    expect(teams.status(), "GET /integration/teams with the live token").toBe(200);
    expect(Array.isArray(((await teams.json()) as { items: unknown[] }).items)).toBe(true);

    // Revoke it behind its confirmation: the one-time panel goes with it and the row has no action left.
    await page.getByRole("button", { name: `Revoke the token ${name}`, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Revoke this token?" });
    await expect(dialog.getByText(`"${name}" stops working immediately`, { exact: false })).toBeVisible();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "POST" && /\/api\/v1\/service-tokens\/\d+\/revoke$/.test(r.url()) && r.status() === 204),
      dialog.getByRole("button", { name: "Revoke", exact: true }).click(),
    ]);
    await expect(page.getByText("Service token revoked")).toBeVisible();
    await expect(row.getByText("Revoked", { exact: true })).toBeVisible();
    await expect(row.getByRole("button")).toHaveCount(0);
    await expect(panel).toHaveCount(0);

    // The very next request with the same token is refused, with the uniform problem answer.
    const after = await consumer.get("/api/v1/integration/me");
    expect(after.status(), "GET /integration/me with the revoked token").toBe(401);
    expect(after.headers()["content-type"]).toContain("application/problem+json");
  } finally {
    await consumer?.dispose();
  }
});
