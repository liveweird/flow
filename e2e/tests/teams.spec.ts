// The flat-teams registry: an admin creates a team, renames it, assigns its manager and deletes it;
// a regular user sees the read-only list; an admin records a team's work-category targets. Owns: its
// throwaway team(s) and user (unique names, deleted by the end of the file).
import { apiAsAdmin, createUserViaUi, deleteUserRow, deleteViaApi, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

test("admin creates a team, renames it, assigns its manager, and deletes it", async ({ page }) => {
  await login(page);
  const member = await createUserViaUi(page, "E2E Team Member");
  const teamName = uniqueText("e2e-team");

  // Create through the modal — a new team lands on its own page.
  await page.goto("/teams");
  await expect(page.getByRole("heading", { name: "Teams" })).toBeVisible();
  await page.getByRole("button", { name: "New team" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(teamName);
  await dialog.getByLabel("Description").fill("Playwright's own team");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByText("No manager")).toBeVisible();

  // Rename from the details page.
  const renamed = `${teamName}-renamed`;
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(renamed);
  await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: renamed })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Assign the throwaway user as the team's manager from the editor's searchable picker.
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const editor = page.getByRole("dialog");
  const managerPicker = editor.getByRole("combobox", { name: "Manager" });
  await managerPicker.click();
  await managerPicker.fill(member.name);
  await page.getByRole("option", { name: `${member.name} (${member.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    editor.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByText(`Manager: ${member.name}`)).toBeVisible();

  // The list shows it (filtered by name); delete from the row menu.
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(renamed);
  await expect(page.getByRole("link", { name: `Open team ${renamed}` })).toBeVisible();
  await rowOperation(page, renamed, `Delete ${renamed}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open team ${renamed}` })).toHaveCount(0);

  await deleteUserRow(page, member.name);
});

test("a regular user sees the read-only teams list", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E Team Reader");
  const teamName = uniqueText("e2e-ro-team");
  await page.goto("/teams");
  await page.getByRole("button", { name: "New team" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(teamName);
  await page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  await signOut(page);

  await login(page, user.email, user.password);
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(teamName);
  await expect(page.getByRole("link", { name: `Open team ${teamName}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "New team" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Operations for ${teamName}` })).toHaveCount(0);
  await expect(page.getByRole("link", { name: `Work-category targets of ${teamName}` })).toBeVisible();
  await page.getByRole("link", { name: `Open team ${teamName}` }).click();
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
  await signOut(page);

  // Cleanup as the admin.
  await login(page);
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(teamName);
  await rowOperation(page, teamName, `Delete ${teamName}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await deleteUserRow(page, user.name);
});

test("admin sets a team's work-category targets, saves and sees them after a reload", async ({ page }) => {
  const teamName = uniqueText("e2e-team-targets");
  const { api } = await apiAsAdmin();
  const created = await api.post("/api/v1/teams", { data: { name: teamName } });
  expect(created.status(), await created.text()).toBe(201);
  const teamId = ((await created.json()) as { id: number }).id;
  await api.dispose();
  try {
    await login(page);
    await page.goto("/teams");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(teamName);
    await rowOperation(page, teamName, `Work-category targets of ${teamName}`);
    await expect(page).toHaveURL(new RegExp(`/teams/${teamId}/work-category-targets$`));
    await expect(page.getByRole("heading", { name: `Work-category targets: ${teamName}` })).toBeVisible();

    // A team with no mapped categories can still target the always-offered "No category" row.
    const noCategory = page.getByRole("textbox", { name: "Target % for No category" });
    await noCategory.fill("60");
    await expect(page.getByText(/The targets add up to 60 %, not 100 %/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
    await noCategory.fill("100");
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && r.url().endsWith(`/api/v1/teams/${teamId}/work-category-targets`) && r.status() === 204),
      page.getByRole("button", { name: "Save", exact: true }).click(),
    ]);

    await page.reload();
    await expect(page.getByRole("textbox", { name: "Target % for No category" })).toHaveValue("100");
    await expect(page.getByRole("button", { name: "Clear targets" })).toBeEnabled();
  } finally {
    await deleteViaApi([`/api/v1/teams/${teamId}`]);
  }
});
