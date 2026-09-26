import type { Route } from "@playwright/test";
import { accountMenu, ADMIN, expect, login, signOut, test } from "./helpers";

test("signing out from a protected route starts the next login at home", async ({ page }) => {
  await login(page);
  await expect(page.getByRole("heading", { name: "Flow" })).toBeVisible();

  await page.goto("/teams");
  await expect(page.getByRole("heading", { name: "Teams", exact: true })).toBeVisible();

  let releaseLogout!: () => void;
  let logoutIntercepted = false;
  let markLogoutHeld!: () => void;
  let markLogoutSettled!: () => void;
  const logoutGate = new Promise<void>((resolve) => { releaseLogout = resolve; });
  const logoutHeld = new Promise<void>((resolve) => { markLogoutHeld = resolve; });
  const logoutSettled = new Promise<void>((resolve) => { markLogoutSettled = resolve; });
  const logoutHandler = async (route: Route) => {
    logoutIntercepted = true;
    try {
      const response = await route.fetch();
      markLogoutHeld();
      await logoutGate;
      await route.fulfill({ response });
    } finally {
      markLogoutSettled();
    }
  };
  await page.route("**/api/v1/logout", logoutHandler);

  try {
    await signOut(page);
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await expect(page.getByText("You've been signed out.")).toBeVisible();
    await logoutHeld;

    // Stay on the router-produced login page: a helper reload/clear here would erase the stale
    // `from` state that this regression is meant to catch.
    await page.getByRole("textbox", { name: "Email" }).fill(ADMIN);
    await page.getByRole("textbox", { name: "Password" }).fill("changeme");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole("heading", { name: "Flow" })).toBeVisible();
  } finally {
    releaseLogout();
    if (logoutIntercepted) await logoutSettled;
    await page.unroute("**/api/v1/logout", logoutHandler);
  }

  await expect(accountMenu(page)).toBeVisible();
});

test("invalid credentials are rejected", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill(ADMIN);
  await page.getByRole("textbox", { name: "Password" }).fill("definitely-wrong");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid email or password")).toBeVisible();
});

test("a deep link is guarded and lands back after signing in", async ({ page }) => {
  await page.goto("/some/deep/path?tab=history#details");
  // Anonymous → bounced to the sign-in form.
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();

  await page.getByRole("textbox", { name: "Email" }).fill(ADMIN);
  await page.getByRole("textbox", { name: "Password" }).fill("changeme");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/some\/deep\/path\?tab=history#details$/);
  // Back at the requested path — inside the shell it renders the not-found page (no blank).
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  await expect(accountMenu(page)).toBeVisible();
});
