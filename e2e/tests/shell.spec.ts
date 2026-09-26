import { accountMenu, expect, login, test } from "./helpers";

// The app shell chrome that isn't owned by any single page: the icon rail (desktop navbar
// collapse) and the account menu's colour-scheme switch. Both are DEVICE-level state (plain
// localStorage, no server sync — unlike the language switch in i18n.spec.ts), so this spec
// owns no server-side state at all.
test("the icon rail collapses and expands the navbar, remembered across reload", async ({ page }) => {
  await login(page);
  const toggle = page.getByRole("button", { name: "Show or hide the navigation" });
  await expect(toggle).toHaveAttribute("data-expanded", "true");
  await expect(page.getByRole("link", { name: "Teams" })).toBeVisible();

  await toggle.click();
  // Collapsed to icon-only: the leaf keeps its accessible name via aria-label, so the same
  // by-name locator still finds it.
  await expect(toggle).not.toHaveAttribute("data-expanded", "true");
  await expect(page.getByRole("link", { name: "Teams" })).toBeVisible();

  await page.reload();
  await expect(toggle).not.toHaveAttribute("data-expanded", "true");

  await toggle.click();
  await expect(toggle).toHaveAttribute("data-expanded", "true");
});

test("switching the theme in the account menu updates the color scheme and persists across reload", async ({ page }) => {
  await login(page);
  const html = page.locator("html");

  await accountMenu(page).click();
  await page.getByText("Dark", { exact: true }).click();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "dark");

  await page.keyboard.press("Escape");
  await page.reload();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "dark");

  await accountMenu(page).click();
  await page.getByText("Light", { exact: true }).click();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "light");
});
