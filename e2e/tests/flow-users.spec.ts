import { BASE_URL } from "../playwright.config";
import { createUserViaUi, deleteUserRow, expect, login, loginViaForm, openFilters, signOut, test, uniqueText } from "./helpers";

// The whole account lifecycle on a throwaway Flow user (its email carries the e2e marker):
// create via the one-time reveal → the new user's limited view → self password change →
// promotion → deletion → the dead login.
test("admin creates a user who signs in, changes their password, and is finally deleted", async ({
  page,
}) => {
  await login(page);

  // The old address (a bookmark, an emailed link) lands on the renamed page.
  await page.goto("/users");
  await expect(page).toHaveURL(/\/flow-users$/);
  // The admin's own row shows the You badge and offers Edit but no Delete/Reset.
  await openFilters(page);
  await page.getByLabel("Email", { exact: true }).fill("admin@flow.local");
  // Row-scoped asserts (the filter is debounced, and residue rows may share the page).
  const adminRow = page.getByRole("row").filter({ hasText: "admin@flow.local" });
  await expect(adminRow.getByText("You")).toBeVisible();
  // The row actions sit under the kebab (v1.19.0): open it and read the menu.
  const adminMenu = adminRow.getByRole("button", { name: /^Operations for / });
  await adminMenu.click();
  const adminMenuId = await adminMenu.getAttribute("aria-controls");
  const adminItems = page.locator(`[id="${adminMenuId}"]`);
  await expect(adminItems.getByRole("menuitem", { name: /^Edit / })).toBeVisible();
  await expect(adminItems.getByRole("menuitem", { name: /^Delete / })).toHaveCount(0);
  await expect(adminItems.getByRole("menuitem", { name: /^Reset password/ })).toHaveCount(0);
  await page.keyboard.press("Escape");

  const throwaway = await createUserViaUi(page, "E2E Person");
  expect(throwaway.password).toMatch(/^[A-Za-z0-9_-]{16}$/);

  // The new user signs in through the real form (the revealed one-time password IS the subject): no Flow users nav, /flow-users bounces home, Change password works.
  await loginViaForm(page, throwaway.email, throwaway.password);
  await expect(page.getByRole("link", { name: "Flow users" })).toHaveCount(0);
  await page.goto("/flow-users");
  await expect(page.getByRole("heading", { name: "Flow" })).toBeVisible();
  const newPassword = `${uniqueText("self-pw")}`;
  await page.goto("/change-password");
  await page.getByLabel(/^Current password/).fill(throwaway.password);
  await page.getByLabel(/^New password/).fill(newPassword);
  await page.getByLabel(/^Confirm new password/).fill(newPassword);
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/password") && r.request().method() === "PUT" && r.ok()),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  // The change ended every token of the account, this device's included: the SPA signs out by itself and says why.
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText("Your password was changed. Sign in with your new password.")).toBeVisible();

  // Back as admin: promote, then delete; both against the name-filtered list.
  await login(page);
  await page.goto(`/flow-users/${throwaway.id}/edit`);
  await page.getByLabel("Administrator").check();
  await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith(`/api/v1/flow-users/${throwaway.id}`) && r.request().method() === "PUT" && r.ok(),
    ),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  // Row-scoped: the seed admin's row, the role-filter options, etc. also say "Admin".
  await expect(
    page.getByRole("row").filter({ hasText: throwaway.name }).getByText("Admin", { exact: true }),
  ).toBeVisible();

  await deleteUserRow(page, throwaway.name);
  // Verify against a fresh load — the in-place list can lose a refetch race.
  await page.goto("/flow-users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(throwaway.name);
  await expect(page.getByText("No Flow users")).toBeVisible();

  // The deleted account can no longer sign in (their CHANGED password included).
  await signOut(page);
  // Fresh load: the logout notification can remount the login form and drop typed values.
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await page.getByRole("textbox", { name: "Email" }).fill(throwaway.email);
  await page.getByRole("textbox", { name: "Password" }).fill(newPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(/invalid email or password/i)).toBeVisible();
});

// The per-request account check (security.md "JWT model"): a role change or a deletion reaches a user who is already
// signed in on their very next request, not at their access token's expiry. Two browser contexts: the admin's and the user's.
test("an admin's demotion and deletion reach a signed-in user on their next request", async ({ page, browser }) => {
  await login(page);
  const throwaway = await createUserViaUi(page, "E2E Session");
  const saveRoles = async (administrator: boolean) => {
    await page.goto(`/flow-users/${throwaway.id}/edit`);
    const box = page.getByLabel("Administrator");
    await (administrator ? box.check() : box.uncheck());
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/v1/flow-users/${throwaway.id}`) && r.request().method() === "PUT" && r.ok(),
      ),
      page.getByRole("button", { name: "Save" }).click(),
    ]);
  };
  await saveRoles(true);

  const userContext = await browser.newContext({ baseURL: BASE_URL });
  let deleted = false;
  try {
    const userPage = await userContext.newPage();
    await login(userPage, throwaway.email, throwaway.password);
    await expect(userPage.getByRole("link", { name: "Flow users" })).toBeVisible();

    // Demoted: the user's token is refused once, the silent refresh brings the new roles, and they stay signed in.
    await saveRoles(false);
    await userPage.goto("/");
    await expect(userPage.getByRole("link", { name: "Flow users" })).toHaveCount(0);
    await userPage.goto("/flow-users");
    await expect(userPage.getByRole("heading", { name: "Flow" })).toBeVisible();
    await expect(userPage.getByRole("button", { name: "Account menu" })).toBeVisible();

    // Deleted: the refresh is rejected too, so their next navigation lands on the sign-in page with the notice.
    await deleteUserRow(page, throwaway.name);
    deleted = true;
    await userPage.goto("/");
    await expect(userPage).toHaveURL(/\/login$/);
    await expect(userPage.getByText("You've been signed out.")).toBeVisible();
  } finally {
    await userContext.close();
    // A failure before the in-test deletion must not strand an active (possibly Administrator) account with a known password.
    if (!deleted) await deleteUserRow(page, throwaway.name);
  }
  await signOut(page);
});
