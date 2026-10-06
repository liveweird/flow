// The v0.3.0 metrics CONFIGURATION surfaces (the report pages are `reports.spec.ts`'s): the
// global `metrics.settings` singleton, a data source's per-connection metrics configuration
// (status→stage/board→team mapping over a real Jira-stub sync), and D1's dated
// Jira-user team membership. Owns: its own edits to the GLOBAL settings singleton — captured via
// the API before the first test touches the page and restored via the API at the end (see that
// test's own comment); a throwaway data source + throwaway team per test that needs one (unique
// `e2e-metrics-*` names), all deleted by the end of their own test (and by the afterEach below when a step fails first); a throwaway regular user in
// the third test.
import {
  apiAsAdmin,
  createUserViaUi,
  deleteUserRow,
  deleteViaApi,
  expect,
  login,
  openFilters,
  rowOperation,
  signOut,
  test,
  uniqueText,
} from "./helpers";

const JIRA_SITE_URL = "https://flow-e2e.atlassian.net";
const JIRA_EMAIL = "svc-account@flow-e2e.example.com";
const JIRA_TOKEN = "e2e-fake-api-token";
const PROJECT_KEYS = ["FLO", "PLT", "GTM", "OPS"];
/** `sample-data/jira/expected.json` `teams.roster[0]` — deterministic across every stub run. */
const SAMPLE_PERSON = { displayName: "Sample User 1", accountId: "5f8a1b2c3d4e5f6a7b8c9d01" };

// Server-side rows the running test created, removed LAST-CREATED FIRST (membership, then team, then the connection it
// was mapped to) by the afterEach below whatever the test's outcome — so a failing step leaves nothing behind (404 = already
// gone, e.g. the in-body UI delete of the data source or user). Test 1 registers nothing (it restores the settings itself).
const teardown: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(teardown.splice(0).reverse());
});

test("admin adjusts metrics settings", async ({ page }) => {
  await login(page);

  // The settings singleton is GLOBAL and shared with every other admin — capture the current
  // values through the API first so the very last step can put them back, whatever they were.
  const { api } = await apiAsAdmin();
  const originalRes = await api.get("/api/v1/metrics-settings");
  expect(originalRes.ok(), await originalRes.text()).toBeTruthy();
  const original = await originalRes.json();

  await page.goto("/metrics-settings");
  await expect(page.getByRole("heading", { name: "Metrics settings" })).toBeVisible();

  const agingWindowInput = page.getByLabel("Aging-WIP window (items)");
  await expect(agingWindowInput).toHaveValue(String(original.agingWindowItems));
  const newAgingWindow = original.agingWindowItems + 7;
  await agingWindowInput.fill(String(newAgingWindow));

  const newHoliday = "2031-06-15";
  const holidaysInput = page.getByRole("combobox", { name: "Holidays" });
  await holidaysInput.click();
  await holidaysInput.pressSequentially(newHoliday);
  await holidaysInput.press("Enter");

  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/metrics-settings") && r.request().method() === "PUT" && r.ok()),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByText("Saved — reports re-derive shortly")).toBeVisible();

  // Persisted: a fresh load shows both changes.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Metrics settings" })).toBeVisible();
  await expect(page.getByLabel("Aging-WIP window (items)")).toHaveValue(String(newAgingWindow));
  await expect(page.getByText(newHoliday)).toBeVisible();

  // Invalid: marking every day of the week as weekend is refused inline, client-side — no request
  // is ever sent for it. Mantine's Chip input is moved off-screen (not just `opacity: 0`), so
  // even a forced click on it fails an "outside the viewport" check — click the chip's own
  // visible label text instead, which forwards the click to its linked input natively.
  for (const day of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) {
    const chip = page.getByRole("checkbox", { name: day });
    if (!(await chip.isChecked())) await page.getByText(day, { exact: true }).click();
  }
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Weekend days must not mark every day of the week as non-working")).toBeVisible();

  // Restore the shared global settings to their pre-test values.
  const restore = await api.put("/api/v1/metrics-settings", {
    data: {
      hoursPerDay: original.hoursPerDay,
      timeZone: original.timeZone,
      weekendDays: original.weekendDays,
      holidays: original.holidays,
      commitmentGraceMinutes: original.commitmentGraceMinutes,
      minSampleSize: original.minSampleSize,
      agingWindowItems: original.agingWindowItems,
      agingPercentiles: original.agingPercentiles,
      backlogWindowSprints: original.backlogWindowSprints,
      epicDriftDays: original.epicDriftDays,
    },
  });
  expect(restore.ok(), await restore.text()).toBeTruthy();
  await api.dispose();
});

test("admin configures a data source's metrics", async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  const name = uniqueText("e2e-metrics-ds");

  // Create through the modal (the data-sources spec's own creation steps, minus the Test
  // connection detour it already covers — the sync itself proves reachability here).
  await page.goto("/data-sources");
  await page.getByRole("button", { name: "New data source" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(name);
  await dialog.getByLabel("Site URL").fill(JIRA_SITE_URL);
  await dialog.getByLabel("Service-account email").fill(JIRA_EMAIL);
  await dialog.getByLabel("API token").fill(JIRA_TOKEN);
  const projectKeys = dialog.getByRole("combobox", { name: "Project keys" });
  for (const key of PROJECT_KEYS) {
    await projectKeys.click();
    await projectKeys.pressSequentially(key);
    await projectKeys.press("Enter");
  }
  const [createResponse] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/data-sources") && r.request().method() === "POST" && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  teardown.push(`/api/v1/data-sources/${(await createResponse.json()).id as number}`);
  await expect(dialog).toHaveCount(0);

  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const openLink = page.getByRole("link", { name: `Open ${name}` });
  await expect(openLink).toBeVisible();
  await openLink.click();
  await expect(page).toHaveURL(/\/data-sources\/\d+$/);
  const dataSourceId = Number(/\/data-sources\/(\d+)$/.exec(page.url())?.[1]);

  // Sync now, then wait it out (the data-sources spec's own generous poll — a scheduler tick may
  // also enqueue its own Reconcile alongside this manual Sync, hence scoping to the Sync row).
  await page.getByRole("button", { name: "Sync now" }).click();
  const syncJobRow = page.getByRole("row").filter({ hasText: "Sync" }).filter({ hasText: "Succeeded" });
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
    await expect(syncJobRow.first()).toBeVisible();
  }).toPass({ timeout: 280_000, intervals: [10_000] });

  // A throwaway team for the board mapping below — team CRUD itself is the teams spec's job.
  const { api } = await apiAsAdmin();
  const teamName = uniqueText("e2e-metrics-team");
  const createdTeam = await api.post("/api/v1/teams", { data: { name: teamName } });
  expect(createdTeam.ok(), await createdTeam.text()).toBeTruthy();
  const teamId = (await createdTeam.json()).id as number;
  teardown.push(`/api/v1/teams/${teamId}`);

  await page.goto(`/data-sources/${dataSourceId}/metrics-config`);
  await expect(page.getByRole("heading", { name: "Metrics configuration" })).toBeVisible();
  await expect(page.getByText("Showing computed defaults — save to confirm them")).toBeVisible();

  // Stages are preselected from the Jira status category (`To Do` -> `new` -> Not started).
  await expect(page.getByRole("combobox", { name: "Stage for To Do", exact: true })).toHaveValue("Not started");

  // A per-domain override under the stage table: in domain PLT only, `To Do` reads as Done.
  await expect(page.getByRole("heading", { name: "Per-domain overrides" })).toBeVisible();
  await page.getByRole("combobox", { name: "Domain", exact: true }).click();
  await page.getByRole("option", { name: "PLT", exact: true }).click();
  const pltToDo = page.getByRole("combobox", { name: "Stage for To Do in domain PLT", exact: true });
  await expect(pltToDo).toHaveValue("");
  await pltToDo.click();
  await page.getByRole("option", { name: "Done", exact: true }).click();
  await expect(pltToDo).toHaveValue("Done");
  await expect(page.getByText("Overrides in total: 1")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Stage for To Do", exact: true })).toHaveValue("Not started");

  // Map one board to the throwaway team and save.
  await page.getByRole("tab", { name: "Boards" }).click();
  const flowBoardTeam = page.getByRole("combobox", { name: "Team for Flow Core board" });
  await flowBoardTeam.click();
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/data-sources\/\d+\/metrics-config$/.test(r.url()) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByText("Saved — reports re-derive shortly")).toBeVisible();

  await page.reload();
  await expect(page.getByRole("heading", { name: "Metrics configuration" })).toBeVisible();
  await expect(page.getByText("Showing computed defaults — save to confirm them")).toHaveCount(0);

  // The override was saved with the rest of the configuration: still there after the reload.
  await expect(page.getByText("Overrides in total: 1")).toBeVisible();
  await page.getByRole("combobox", { name: "Domain", exact: true }).click();
  await page.getByRole("option", { name: "PLT (1 overridden)", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Stage for To Do in domain PLT", exact: true })).toHaveValue("Done");

  // Map a second board to the SAME team — a 409, marked on the row that actually changed.
  await page.getByRole("tab", { name: "Boards" }).click();
  const platformBoardTeam = page.getByRole("combobox", { name: "Team for Platform board" });
  await platformBoardTeam.click();
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await Promise.all([
    page.waitForResponse((r) => /\/api\/v1\/data-sources\/\d+\/metrics-config$/.test(r.url()) && r.request().method() === "PUT"),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByText("This team is already mapped to another board").first()).toBeVisible();

  // Cleanup: the team goes in the afterEach; the connection is deleted through the UI (a scenario step).
  await page.goto("/data-sources");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/data-sources\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open ${name}` })).toHaveCount(0);
  await api.dispose();
});

test("admin adds a dated Jira member to a team; a regular user sees it read-only", async ({ page }) => {
  // A generous ceiling: this test's own sync poll can run close to its 280s cap under load (the
  // same stub sync journey 2 also runs, in the same or a concurrent worker), and every step after
  // the sync (team + user creation, two membership-modal round trips, the regular-user checks,
  // cleanup) still needs its own headroom on top of that.
  test.setTimeout(420_000);
  await login(page);

  // A synced connection through the API (the UI creation flow is the previous test's job — this
  // one only needs `norm.people` populated so the person picker has someone to find).
  const { api } = await apiAsAdmin();
  const dsName = uniqueText("e2e-metrics-ds");
  const created = await api.post("/api/v1/data-sources", {
    data: {
      name: dsName,
      syncIntervalMinutes: 60,
      jira: { siteUrl: JIRA_SITE_URL, email: JIRA_EMAIL, apiToken: JIRA_TOKEN, projectKeys: PROJECT_KEYS },
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const dataSourceId = (await created.json()).id as number;
  teardown.push(`/api/v1/data-sources/${dataSourceId}`);
  const enqueued = await api.post(`/api/v1/data-sources/${dataSourceId}/sync-jobs`, { data: { kind: "SYNC" } });
  expect(enqueued.ok(), await enqueued.text()).toBeTruthy();
  await expect(async () => {
    const status = await (await api.get(`/api/v1/data-sources/${dataSourceId}/status`)).json();
    expect(status.currentJob).toBeNull();
    expect(status.lastJobs?.SYNC?.status).toBe("SUCCEEDED");
  }).toPass({ timeout: 340_000, intervals: [5_000] });

  // A throwaway team, and its regular-user reader created up front (while still admin).
  const teamName = uniqueText("e2e-metrics-team");
  await page.goto("/teams");
  await page.getByRole("button", { name: "New team" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(teamName);
  const [createdTeam] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith("/api/v1/teams") && r.request().method() === "POST" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  const teamId = (await createdTeam.json()).id as number;
  teardown.push(`/api/v1/teams/${teamId}`);
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  const reader = await createUserViaUi(page, "E2E Metrics Reader");
  teardown.push(`/api/v1/users/${reader.id}`);

  await page.goto(`/teams/${teamId}`);
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();

  // Add a Jira member: a closed, past interval (never "current", so the same person stays
  // pickable for the overlap attempt below). D1's exclusion constraint scopes uniqueness to the
  // ACCOUNT alone, globally across every team a person has EVER belonged to — not just this
  // throwaway team — and a deleted team's own membership rows are kept "for the record" (the
  // `team_members` roster idiom), never cleared by the team's own soft-delete. A random,
  // per-run date range (rather than a fixed literal) keeps this run independent of any earlier
  // run's own history for this same shared, deterministic stub person.
  const runAnchor = new Date(Date.UTC(2000, 0, 1) + (Date.now() % (20 * 365 * 24 * 60 * 60 * 1000)));
  const isoDatePlusDays = (days: number) => {
    const d = new Date(runAnchor);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
  };
  const firstFrom = isoDatePlusDays(0);
  const firstTo = isoDatePlusDays(150);
  const secondFrom = isoDatePlusDays(60);
  const secondTo = isoDatePlusDays(200);

  await page.getByRole("button", { name: "Add Jira member" }).click();
  let modal = page.getByRole("dialog");
  const personPicker = modal.getByRole("combobox", { name: "Person" });
  await personPicker.click();
  await personPicker.fill(SAMPLE_PERSON.displayName);
  // The option list renders in a portal, a sibling of the dialog rather than a descendant of
  // it — scope to `page`, not `modal`.
  await page.getByRole("option", { name: `${SAMPLE_PERSON.displayName} (${SAMPLE_PERSON.accountId})`, exact: true }).click();
  await modal.getByLabel("Valid from").fill(firstFrom);
  await modal.getByLabel("Valid to").fill(firstTo);
  const [createResponse] = await Promise.all([
    page.waitForResponse((r) => /\/jira-memberships$/.test(r.url()) && r.request().method() === "POST" && r.ok()),
    modal.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  const membershipId = (await createResponse.json()).id as number;
  teardown.push(`/api/v1/teams/${teamId}/jira-memberships/${membershipId}`);
  await expect(modal).toHaveCount(0);
  const membershipRow = page.getByRole("row", { name: new RegExp(SAMPLE_PERSON.displayName) });
  await expect(membershipRow).toContainText(firstFrom);
  await expect(membershipRow).toContainText(firstTo);

  // An overlapping second membership for the SAME person is refused inline (never a toast).
  await page.getByRole("button", { name: "Add Jira member" }).click();
  modal = page.getByRole("dialog");
  const secondPicker = modal.getByRole("combobox", { name: "Person" });
  await secondPicker.click();
  await secondPicker.fill(SAMPLE_PERSON.displayName);
  await page.getByRole("option", { name: `${SAMPLE_PERSON.displayName} (${SAMPLE_PERSON.accountId})`, exact: true }).click();
  await modal.getByLabel("Valid from").fill(secondFrom);
  await modal.getByLabel("Valid to").fill(secondTo);
  await Promise.all([
    page.waitForResponse((r) => /\/jira-memberships$/.test(r.url()) && r.request().method() === "POST"),
    modal.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await expect(
    modal.getByText("This person already has a membership covering that period — end or edit the existing one first."),
  ).toBeVisible();
  await modal.getByRole("button", { name: "Cancel" }).click();
  await expect(modal).toHaveCount(0);

  // A regular user reads the same membership, with no edit controls and no Metrics settings nav.
  await signOut(page);
  await login(page, reader.email, reader.password);
  await expect(page.getByRole("link", { name: "Metrics settings" })).toHaveCount(0);
  await page.goto("/metrics-settings");
  await expect(page).toHaveURL("/");

  await page.goto(`/teams/${teamId}`);
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByRole("row", { name: new RegExp(SAMPLE_PERSON.displayName) })).toContainText(firstFrom);
  await expect(page.getByRole("button", { name: "Add Jira member" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Operations for ${SAMPLE_PERSON.displayName}` })).toHaveCount(0);
  await signOut(page);

  // Cleanup as the admin: the reader through the UI (a scenario step); the membership row (BEFORE the team — a team's own
  // soft-delete does not clear it, see the comment above, and leaving it in place would permanently occupy this shared stub
  // person's one global membership slot for this date range), the team and the data source in the afterEach.
  await login(page);
  await deleteUserRow(page, reader.name);
  await api.dispose();
});
