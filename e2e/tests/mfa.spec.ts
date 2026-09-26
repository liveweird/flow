import {
  accountMenu,
  createUserViaUi,
  deleteUserRow,
  expect,
  login,
  openFilters,
  signOut,
  test,
} from "./helpers";

// Email MFA (opt-in via the per-user MFA feature flag) + the feature-flags admin surfaces.
// The full sign-in roundtrip needs the compose stack's Mailpit catcher (http://localhost:8028);
// that test skips itself when Mailpit is unreachable. NEVER touch the seed admin's MFA flag —
// enabling it would make every other spec's login demand a code.

const MAILPIT = "http://localhost:8028";

test("admin toggles a user's MFA on the feature-flags screen and the per-user editor", async ({
  page,
}) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E-Flag");

  // The /feature-flags screen: filtered to the throwaway user, the MFA switch starts OFF
  // (the inverted default — every new user begins with the disabled row).
  await page.goto("/feature-flags");
  await openFilters(page);
  await page.getByLabel("Email", { exact: true }).fill(user.email);
  const rowSwitch = page.getByRole("switch", { name: `Toggle Email MFA for ${user.name}` });
  await expect(rowSwitch).not.toBeChecked();
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/v1/users/${user.id}/features`) && r.ok()),
    rowSwitch.click({ force: true }),
  ]);
  await expect(page.getByText("Feature flags saved").first()).toBeVisible();

  // The per-user editor shows the change and turns it back off.
  await page.goto(`/users/${user.id}/features`);
  const editorSwitch = page.getByRole("switch", { name: "Email MFA" });
  await expect(editorSwitch).toBeChecked();
  await editorSwitch.click({ force: true });
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/v1/users/${user.id}/features`) && r.ok()),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await expect(page).toHaveURL(/\/users$/);

  await deleteUserRow(page, user.name);
});

test("an MFA-enabled account signs in with the emailed code", async ({ page }) => {
  const mailpitUp = await fetch(`${MAILPIT}/api/v1/messages`).then(
    (r) => r.ok,
    () => false,
  );
  test.skip(!mailpitUp, "Mailpit (compose stack) is not reachable — the code email is unobservable");

  await login(page);
  const user = await createUserViaUi(page, "E2E-Mfa");

  // Enable MFA through the per-user editor.
  await page.goto(`/users/${user.id}/features`);
  await page.getByRole("switch", { name: "Email MFA" }).click({ force: true });
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/v1/users/${user.id}/features`) && r.ok()),
    page.getByRole("button", { name: "Save" }).click(),
  ]);
  await signOut(page);

  // Correct credentials answer with the code step, not a session.
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill(user.email);
  await page.getByRole("textbox", { name: "Password" }).fill(user.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Enter your sign-in code")).toBeVisible();

  // Pull the 6-digit code out of Mailpit (delivery is asynchronous).
  let code: string | undefined;
  await expect
    .poll(
      async () => {
        const list = await fetch(`${MAILPIT}/api/v1/messages`).then((r) => r.json());
        const msg = list.messages?.find(
          (m: { To?: { Address: string }[]; Subject?: string }) =>
            m.To?.some((t) => t.Address === user.email) && m.Subject?.includes("sign-in code"),
        );
        if (!msg) return undefined;
        const text: string = (
          await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`).then((r) => r.json())
        ).Text;
        code = text.match(/^\d{6}$/m)?.[0];
        return code;
      },
      { timeout: 15_000 },
    )
    .toBeTruthy();

  // Type the code into the PIN inputs (they auto-advance) and verify.
  await page.getByRole("textbox").first().click();
  await page.keyboard.type(code!);
  await page.getByRole("button", { name: "Verify code" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  await signOut(page);

  // Cleanup as the admin: the spec owns its throwaway account.
  await login(page);
  await deleteUserRow(page, user.name);
});
