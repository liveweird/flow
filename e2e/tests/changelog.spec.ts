import { expect, login, test } from "./helpers";

// The changelog is a build-time artifact and the what's-new state is DEVICE-level
// (localStorage) — a fresh Playwright context always starts unseen, so this spec owns no
// server-side state at all. No exact version assertion (a release must not break e2e);
// the newest entry only has to look like a version + date.
test("the what's-new dot leads to the changelog and clears once it is read", async ({ page }) => {
  await login(page);

  // A fresh context has never seen any version — the dot is on the navbar stamp.
  await expect(page.getByTitle("What's new")).toBeVisible();

  // The stamp itself links to the changelog.
  await page.getByTitle("Build version").click();
  await expect(page.getByRole("heading", { name: "Changelog" })).toBeVisible();

  // Reading the page marks the version seen — the dot is gone.
  await expect(page.getByTitle("What's new")).toHaveCount(0);

  // The newest entry renders a version and a date (no exact pin — releases must not break e2e).
  await expect(page.getByText(/^v\d+\.\d+\.\d+$/).first()).toBeVisible();
  await expect(page.getByText(/^\d{4}-\d{2}-\d{2}$/).first()).toBeVisible();

  // NO language switching here: this spec runs as the SEED ADMIN, and the switcher writes
  // the server-side user language (V18) — a mid-run Polish seed admin would flip every
  // parallel spec's UI. PL bodies are pinned by Changelog.test.tsx; the language journey
  // lives in i18n.spec.ts on a throwaway user.

  // The dot stays cleared on later navigation.
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Flow" })).toBeVisible();
  await expect(page.getByTitle("What's new")).toHaveCount(0);
});
