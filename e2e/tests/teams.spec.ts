// The flat-teams registry: an admin creates a team, manages its roster, renames and deletes it;
// a regular user sees the read-only list. Owns: its throwaway team(s) and user (unique names,
// deleted by the end of the file).
import { createUserViaUi, deleteUserRow, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

test("admin creates a team, manages its roster, renames it, and deletes it", async ({ page }) => {
  await login(page);
  const member = await createUserViaUi(page, "E2E Team Member");
  const teamName = uniqueText("e2e-team");

  // Create through the modal — a new team lands on its roster page.
  await page.goto("/teams");
  await expect(page.getByRole("heading", { name: "Teams" })).toBeVisible();
  await page.getByRole("button", { name: "New team" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(teamName);
  await dialog.getByLabel("Description").fill("Playwright's own team");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByText("No members yet")).toBeVisible();

  // Add the throwaway user from the searchable picker.
  const picker = page.getByRole("combobox", { name: "Add a member" });
  await picker.click();
  await picker.fill(member.name);
  await page.getByRole("option", { name: `${member.name} (${member.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && /\/members\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("button", { name: "Add", exact: true }).click(),
  ]);
  const roster = page.getByRole("table", { name: `Members of ${teamName}` });
  await expect(roster.getByText(member.email)).toBeVisible();

  // Rename from the details page.
  const renamed = `${teamName}-renamed`;
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(renamed);
  await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: renamed })).toBeVisible();

  // Remove the member through the row menu.
  await rowOperation(page, member.name, `Remove ${member.name} from the team`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/members\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByText("No members yet")).toBeVisible();

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
  await page.getByRole("link", { name: `Open team ${teamName}` }).click();
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Add a member" })).toHaveCount(0);
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
