// The ADMIN telemetry panel (web/src/components/TelemetryPanel.tsx): opened from the account menu, it shows Flow's own memory (a share
// of the container limit, the compose app runs in a 768 MB cgroup), the storage figures and the per-data-source sync health, remembers
// that it is open across a reload, and never appears for a regular user. The sampler's first tick comes seconds after the app starts, so
// every test first waits (through the API, bounded) until a sample and a storage measurement exist. Owns: one throwaway DISABLED Jira
// stub data source in the second test (never synced, so no DERIVE can disturb another spec) and a throwaway user in the fourth; both
// are removed by the test and, if a step fails first, by the afterEach below.
import type { APIRequestContext, Page } from "@playwright/test";
import { accountMenu, apiAsAdmin, axeViolations, createUserViaUi, deleteUserRow, deleteViaApi, expect, login, signOut, stubDataSourceBody, test, uniqueText } from "./helpers";

const teardown: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(teardown.splice(0).reverse());
});

/**
 * Waits until exactly ONE live (non-stale) process has a sample and the storage has been measured (the panel would otherwise say "no
 * sample yet" for up to a minute), and returns that process's instance name. A stack rebuilt within the last 15 minutes still lists the
 * previous container's process as stale, so the panel can hold two Memory tables: the locators are scoped to the live one by name.
 */
async function awaitFirstSamples(api: APIRequestContext): Promise<string> {
  let instance = "";
  await expect
    .poll(
      async () => {
        const res = await api.get("/api/v1/telemetry/current");
        if (!res.ok()) return `status ${res.status()}`;
        const body = (await res.json()) as { processes: { instance: string; stale: boolean; sampledAt: number | null }[]; storage: unknown };
        const live = body.processes.filter((p) => !p.stale);
        const ready = live.length === 1 && live[0]?.sampledAt !== null && body.storage !== null;
        if (ready) instance = live[0]?.instance ?? "";
        return ready ? "ready" : "waiting";
      },
      { message: "the sampler's first sample and storage measurement of one live process", timeout: 90_000, intervals: [1_000, 2_000, 5_000] },
    )
    .toBe("ready");
  return instance;
}

async function openPanelFromMenu(page: Page) {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Telemetry panel" }).click();
  const panel = page.getByRole("complementary", { name: "Telemetry" });
  await expect(panel).toBeVisible();
  return panel;
}

test("an admin opens the telemetry panel from the account menu and reads the Memory section", async ({ page }) => {
  const { api } = await apiAsAdmin();
  let instance: string;
  try {
    instance = await awaitFirstSamples(api);
  } finally {
    await api.dispose();
  }
  await login(page);
  const panel = await openPanelFromMenu(page);
  await expect(panel.getByRole("heading", { level: 2, name: "Telemetry" })).toBeVisible();
  // Memory is open by default; the compose stack gives the app a cgroup with a 768 MB limit.
  await expect(panel.getByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
  const table = panel.getByRole("table", { name: `Memory of ${instance}`, exact: true });
  await expect(table).toBeVisible();
  await expect(table.getByRole("row", { name: /Container limit/ })).toContainText("768 MB");
  await expect(table.getByRole("row", { name: /Share of the limit/ })).toContainText(/\d%/);
  // The account menu item shows the toggle is on.
  await accountMenu(page).click();
  await expect(page.getByRole("menuitem", { name: "Telemetry panel" })).toHaveAttribute("aria-current", "true");
});

test("the Storage and Sync sections show the database and the data sources", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const name = uniqueText("e2e-telemetry-ds");
  try {
    await awaitFirstSamples(api);
    const created = await api.post("/api/v1/data-sources", { data: { ...stubDataSourceBody(name), enabled: false } });
    expect(created.ok(), await created.text()).toBeTruthy();
    teardown.push(`/api/v1/data-sources/${((await created.json()) as { id: number }).id}`);
  } finally {
    await api.dispose();
  }
  await login(page);
  const panel = await openPanelFromMenu(page);

  await panel.getByRole("button", { name: "Storage" }).click();
  const sizes = panel.getByRole("table", { name: "Database size per schema" });
  await expect(sizes.getByRole("row", { name: /^Database/ })).toContainText(/\d (MB|GB)/);
  await expect(sizes.getByRole("row", { name: /Schema raw/ })).toBeVisible();
  const estimates = panel.getByRole("table", { name: "Estimated size per data source" });
  await expect(estimates.getByRole("row", { name })).toBeVisible();

  await panel.getByRole("button", { name: "Sync" }).click();
  const sync = panel.getByRole("table", { name: "Sync health per data source" });
  const row = sync.getByRole("row", { name });
  await expect(row).toContainText("Never");
  await expect(row).toContainText("Disabled");
});

test("the open telemetry panel survives a reload and closes with its own button", async ({ page }) => {
  await login(page);
  const panel = await openPanelFromMenu(page);
  await panel.getByRole("button", { name: "Storage" }).click();
  await page.reload();
  const reopened = page.getByRole("complementary", { name: "Telemetry" });
  await expect(reopened).toBeVisible();
  // The expanded sections are remembered too.
  await expect(reopened.getByRole("button", { name: "Storage" })).toHaveAttribute("aria-expanded", "true");

  await reopened.getByRole("button", { name: "Close" }).click();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
  await page.reload();
  await expect(accountMenu(page)).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
});

test("a regular user has no telemetry item in the account menu", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E Telemetry Reader");
  teardown.push(`/api/v1/flow-users/${user.id}`);
  await signOut(page);

  await login(page, user.email, user.password);
  await accountMenu(page).click();
  await expect(page.getByRole("menuitem", { name: "Change password" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Telemetry panel" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  // Even a stored "open" flag shows a regular user nothing.
  await page.evaluate(() => localStorage.setItem("flow.viewSettings.telemetry.panelOpen", "true"));
  await page.reload();
  await expect(accountMenu(page)).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Telemetry" })).toHaveCount(0);
  await signOut(page);

  await login(page);
  await deleteUserRow(page, user.name);
});

test("the open telemetry panel has no WCAG A/AA violations", async ({ page }) => {
  const { api } = await apiAsAdmin();
  let instance: string;
  try {
    instance = await awaitFirstSamples(api);
  } finally {
    await api.dispose();
  }
  await login(page);
  const panel = await openPanelFromMenu(page);
  for (const section of ["Storage", "Sync", "DERIVE", "Requests", "JVM"]) {
    await panel.getByRole("button", { name: section }).click();
  }
  await expect(panel.getByRole("table", { name: `Memory of ${instance}`, exact: true })).toBeVisible();
  await expect(panel.getByRole("group", { name: "Trend: Memory (UTC)" })).toBeVisible();
  await expect(panel.getByRole("table", { name: "Database size per schema" })).toBeVisible();
  expect(await axeViolations(page)).toEqual([]);
});
