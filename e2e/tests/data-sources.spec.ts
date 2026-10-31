// The v0.2.0 Data sources admin surface: an admin connects the Jira stub (the compose stack
// reroutes every Jira call to the jira-stub service via JIRA_STUB_BASE_URL, so no real Jira
// tenant is ever touched), test-connects it, syncs it end to end, and reads back what landed —
// the sync-jobs history, the raw-store counts, the data profile and the raw issue inspector.
// The 1.34.0 GitLab kind does the same against the gitlab-stub service (GITLAB_STUB_BASE_URL; the
// only accepted base URL is the stub's own origin, http://gitlab-stub:8080) and reads back its
// projects and users. A regular user sees no trace of the surface at all. Owns: its throwaway data
// sources (unique `e2e-jira-*` / `e2e-gitlab-*` names) — deleted at the end of their test (and by
// the afterEach below when a step fails first) — and, in the last test, a throwaway user. The synced connection's raw/normalized rows are NOT purged by the delete (see
// scenarios/data-sources.md "Not covered here").
import { createUserViaUi, deleteUserRow, deleteViaApi, expect, login, openFilters, REPORTS_EXCLUSIVE_LOCKS, rowOperation, signOut, test, uniqueText } from "./helpers";

// Its first test syncs and derives a live connection: never beside `reports.spec.ts`, whose team-level figures a foreign DERIVE would
// move mid-test (helpers.ts, `REPORTS_EXCLUSIVE_LOCKS`).
test.describe.configure({ lock: REPORTS_EXCLUSIVE_LOCKS.dataSources });

// Server-side rows the running test created, removed (children first) by the afterEach below whatever the test's outcome — the
// in-body UI delete is a scenario step, this is the safety net for a step that fails before reaching it (404 = already gone).
const teardown: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(teardown.splice(0).reverse());
});

test("admin connects the Jira stub, syncs it, and inspects the result", async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  const name = uniqueText("e2e-jira");

  // Create through the modal, testing the connection against the Jira stub first.
  await page.goto("/data-sources");
  await expect(page.getByRole("heading", { name: "Data sources" })).toBeVisible();
  await page.getByRole("button", { name: "New data source" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(name);
  await dialog.getByLabel("Site URL").fill("https://flow-e2e.atlassian.net");
  await dialog.getByLabel("Service-account email").fill("svc-account@flow-e2e.example.com");
  await dialog.getByLabel("API token").fill("e2e-fake-api-token");
  const projectKeys = dialog.getByRole("combobox", { name: "Project keys" });
  for (const key of ["FLO", "PLT", "GTM", "OPS"]) {
    await projectKeys.click();
    await projectKeys.pressSequentially(key);
    await projectKeys.press("Enter");
  }

  await dialog.getByRole("button", { name: "Test connection" }).click();
  // Every REQUIRED probe row must be OK against the sample stub (server's own
  // DataSourceTestConnectionTest pins this) — only the optional field_search, bulkfetch and project_fields rows may vary.
  const requiredRows = dialog.locator("table tbody tr", { hasText: "Required" });
  await expect(requiredRows.first()).toBeVisible({ timeout: 35_000 });
  const requiredCount = await requiredRows.count();
  for (let i = 0; i < requiredCount; i++) {
    await expect(requiredRows.nth(i)).toContainText("OK");
  }
  await expect(dialog.getByText(/Cloud ID: /)).toBeVisible();

  const [createResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/data-sources") && r.request().method() === "POST" && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  teardown.push(`/api/v1/data-sources/${(await createResponse.json()).id as number}`);
  await expect(dialog).toHaveCount(0);

  // Filter the list down to the new row and open its details page.
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const openLink = page.getByRole("link", { name: `Open ${name}` });
  await expect(openLink).toBeVisible();
  await openLink.click();
  await expect(page).toHaveURL(/\/data-sources\/\d+$/);
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();

  // Sync now — a scheduler tick may already have enqueued the same job (coalesced); either way
  // exactly one SYNC job runs to completion for this fresh connection.
  await page.getByRole("button", { name: "Sync now" }).click();

  // Poll the details page (reloading each attempt, since the sync-jobs history table itself has
  // no auto-refresh — only the connection/counts/current-job summary does) until the SYNC job is
  // SUCCEEDED and the raw-store counts show the full in-scope dataset (sample-data/README.md /
  // jira/expected.json: 1,201 in-scope issues across FLO/PLT/GTM/OPS). The scheduler may also
  // enqueue its own RECONCILE pass alongside our manual SYNC (its "Sync now" click either starts
  // a fresh job or coalesces into one already scheduled) — scope the row lookup to the "Sync"
  // kind so a co-occurring Reconcile row's own "Succeeded" badge is never what we matched.
  const syncJobRow = page.getByRole("row").filter({ hasText: "Sync" }).filter({ hasText: "Succeeded" });
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
    await expect(syncJobRow.first()).toBeVisible();
    await expect(page.getByRole("row", { name: /Raw issues/ })).toContainText("1201");
  }).toPass({ timeout: 280_000, intervals: [10_000] });

  // The data profile — a couple of stable section headings/values.
  await expect(page.getByRole("link", { name: "Data profile" })).toBeVisible();
  await page.getByRole("link", { name: "Data profile" }).click();
  await expect(page).toHaveURL(/\/data-sources\/\d+\/profile$/);
  await expect(page.getByRole("heading", { name: "Data profile" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Projects" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Workflows" })).toBeVisible();
  await expect(page.getByText("FLO").first()).toBeVisible();

  // The raw issue inspector: a known key renders; a malformed one errors inline, form still usable.
  await page.getByRole("link", { name: "Back to data source" }).click();
  await expect(page).toHaveURL(/\/data-sources\/\d+$/);
  await expect(page.getByRole("link", { name: "Raw issue inspector" })).toBeVisible();
  await page.getByRole("link", { name: "Raw issue inspector" }).click();
  await expect(page).toHaveURL(/\/data-sources\/\d+\/inspect$/);
  const keyInput = page.getByLabel("Issue key or id");
  await keyInput.fill("FLO-1");
  await page.getByRole("button", { name: "Look up" }).click();
  await expect(page.getByRole("heading", { name: "FLO-1" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Raw payload" })).toBeVisible();

  await keyInput.fill("not a valid key!!");
  await page.getByRole("button", { name: "Look up" }).click();
  await expect(page.getByText("Enter a valid issue key (e.g. ENG-123) or numeric id")).toBeVisible();
  await expect(keyInput).toBeEditable();
  await expect(page.getByRole("button", { name: "Look up" })).toBeEnabled();

  // Delete from the list's row menu.
  await page.goto("/data-sources");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/data-sources\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open ${name}` })).toHaveCount(0);
});

test("admin connects the GitLab stub, syncs it, and reads back its projects, users, commits and merge requests", async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  const name = uniqueText("e2e-gitlab");

  // Create through the modal: pick the GitLab kind, then test the connection against the GitLab stub first.
  await page.goto("/data-sources");
  await page.getByRole("button", { name: "New data source" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(name);
  await dialog.getByRole("radiogroup", { name: "Kind" }).getByText("GitLab", { exact: true }).click();
  // The Excluded paths field is prefilled with the server's built-in defaults (the `/kinds` read).
  await expect(dialog.getByRole("combobox", { name: "Excluded paths" })).toBeVisible();
  await expect(dialog.getByText("package-lock.json", { exact: true })).toBeVisible();
  // The compose stack's GITLAB_STUB_BASE_URL makes the stub's exact origin the only accepted base URL.
  await dialog.getByLabel("Base URL").fill("http://gitlab-stub:8080");
  await dialog.getByLabel("Access token").fill("e2e-fake-token");
  const groupPaths = dialog.getByRole("combobox", { name: "Group paths" });
  for (const path of ["platform", "apps"]) {
    await groupPaths.click();
    await groupPaths.pressSequentially(path);
    await groupPaths.press("Enter");
  }

  await dialog.getByRole("button", { name: "Test connection" }).click();
  // Every REQUIRED probe row (version, user, token scopes, each group and its projects, users) is OK against the stub
  // (the server's GitLabConnectorTest pins it); the optional user_admin / user_emails rows may vary. A GitLab result has no Cloud ID.
  const requiredRows = dialog.locator("table tbody tr", { hasText: "Required" });
  await expect(requiredRows.first()).toBeVisible({ timeout: 35_000 });
  const requiredCount = await requiredRows.count();
  for (let i = 0; i < requiredCount; i++) {
    await expect(requiredRows.nth(i)).toContainText("OK");
  }
  await expect(dialog.getByText(/Cloud ID/)).toHaveCount(0);

  const [createResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/data-sources") && r.request().method() === "POST" && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  teardown.push(`/api/v1/data-sources/${(await createResponse.json()).id as number}`);
  await expect(dialog).toHaveCount(0);

  // Filter the list down to the new row and open its details page.
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const openLink = page.getByRole("link", { name: `Open ${name}` });
  await expect(openLink).toBeVisible();
  await openLink.click();
  await expect(page).toHaveURL(/\/data-sources\/\d+$/);
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();

  // The connection table names the kind and the groups; the Jira-only pages and actions are not offered for GitLab.
  const connection = page.getByRole("table", { name: "Connection", exact: true });
  await expect(connection).toContainText("GitLab");
  await expect(connection).toContainText("platform, apps");
  await expect(connection.getByRole("row", { name: /^Excluded paths/ })).toContainText("package-lock.json");
  for (const link of ["Data profile", "Raw issue inspector", "Metrics configuration"]) {
    await expect(page.getByRole("link", { name: link })).toHaveCount(0);
  }
  await expect(page.getByRole("button", { name: "Reprocess" })).toHaveCount(0);

  // Sync now, then poll (reloading each attempt) until the SYNC job succeeded and the counts show the stub's in-scope world:
  // 4 projects (sample-data/gitlab/expected.json `projects.inScopeIds`), 8 users (`users.list`) and its commits: 300 live, 19 of them
  // merges (`commits.inScopeTotals`), and none still awaiting file details (one SYNC drains the 281 diffs under the default budget); then
  // its merge requests: 60 (`mergeRequests.total`), 34 merged (`byState.merged`), 698 review events (`events.noteTotal` 582 +
  // `approvalsEndpointTotal` 40 + `reviewersTotal` 76), none awaiting details (one SYNC drains ~190 calls under the default budget) and the one
  // GraphQL gap without size statistics (`mergeRequests.nullDiffStatsGap`). Scope the job row to the "Sync"
  // kind so a co-occurring Reconcile row's own "Succeeded" is never what we matched.
  await page.getByRole("button", { name: "Sync now" }).click();
  const syncJobRow = page.getByRole("row").filter({ hasText: "Sync" }).filter({ hasText: "Succeeded" });
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
    await expect(syncJobRow.first()).toBeVisible();
    await expect(page.getByRole("row", { name: /^Projects \d+$/ })).toContainText("4");
    await expect(page.getByRole("row", { name: /^Users \d+$/ })).toContainText("8");
    await expect(page.getByRole("row", { name: /^Commits \d+$/ })).toContainText("300");
    await expect(page.getByRole("row", { name: /^Merge commits \d+$/ })).toContainText("19");
    await expect(page.getByRole("row", { name: /^Commits awaiting file details \d+$/ })).toContainText("0");
    await expect(page.getByRole("row", { name: /^Merge requests \d+$/ })).toContainText("60");
    await expect(page.getByRole("row", { name: /^Merged merge requests \d+$/ })).toContainText("34");
    await expect(page.getByRole("row", { name: /^MR events .* \d+$/ })).toContainText("698");
    await expect(page.getByRole("row", { name: /^Merge requests awaiting details \d+$/ })).toContainText("0");
    await expect(page.getByRole("row", { name: /^Merge requests without size statistics \d+$/ })).toContainText("1");
  }).toPass({ timeout: 280_000, intervals: [10_000] });

  // Delete from the list's row menu.
  await page.goto("/data-sources");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/data-sources\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open ${name}` })).toHaveCount(0);
});

test("admin cannot move a backfill date before the history horizon", async ({ page }) => {
  await login(page);
  await page.goto("/data-sources");
  await page.getByRole("button", { name: "New data source" }).click();
  const dialog = page.getByRole("dialog");
  // The hint names the horizon's first day (24 months back on a fresh stack) once the settings have loaded.
  await expect(dialog.getByText(/The history horizon allows dates from \d{4}-\d{2}-\d{2} on/)).toBeVisible();

  // Three years back is before the 24-month horizon: refused in the form, nothing is sent.
  const threeYearsBack = new Date();
  threeYearsBack.setUTCFullYear(threeYearsBack.getUTCFullYear() - 3);
  let posted = false;
  page.on("request", (r) => {
    if (r.method() === "POST" && r.url().endsWith("/api/v1/data-sources")) posted = true;
  });
  await dialog.getByLabel("Name").fill(uniqueText("e2e-horizon"));
  await dialog.getByLabel("Site URL").fill("https://flow-e2e.atlassian.net");
  await dialog.getByLabel("Service-account email").fill("svc-account@flow-e2e.example.com");
  await dialog.getByLabel("API token").fill("e2e-fake-api-token");
  const projectKeys = dialog.getByRole("combobox", { name: "Project keys" });
  await projectKeys.click();
  await projectKeys.pressSequentially("HZN");
  await projectKeys.press("Enter");
  await dialog.getByLabel("Backfill from").fill(threeYearsBack.toISOString().slice(0, 10));
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  await expect(dialog.getByText(/Backfill date must not be before \d{4}-\d{2}-\d{2} \(the history horizon\)/)).toBeVisible();
  expect(posted).toBe(false);
});

test("a regular user sees no Data sources surface", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E DataSource Reader");
  teardown.push(`/api/v1/flow-users/${user.id}`);
  await signOut(page);

  await login(page, user.email, user.password);
  await expect(page.getByRole("link", { name: "Data sources" })).toHaveCount(0);
  await page.goto("/data-sources");
  await expect(page).toHaveURL("/");
  await signOut(page);

  await login(page);
  await deleteUserRow(page, user.name);
});
