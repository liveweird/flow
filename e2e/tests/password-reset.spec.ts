import { createUserViaUi, deleteUserRow, expect, login, loginViaForm, signOut, test, uniqueText } from "./helpers";
import { MAILPIT_URL } from "../playwright.config";

// Self-service password reset: the "Forgot password?" flow on the login screen.
// The full email roundtrip needs the compose stack's Mailpit catcher (E2E_MAILPIT_URL, default
// http://localhost:8028 — see docker-compose.yaml); when it is unreachable (e.g. a dev stack on the log transport) that
// one test skips itself. The dev stack lifts the per-IP request bucket (100/min, the login-bucket
// idiom) and the confirm bucket, so back-to-back runs never trip them; the per-email throttle is exercised
// explicitly. The emailed link is followed by path + fragment RELATIVE to the baseURL: the compose stack's
// MAIL_APP_URL may name another origin than the stack under test (an isolated project on another port).

function uniqueEmail(prefix: string): string {
  return `${uniqueText(prefix).toLowerCase().replace(/[^a-z0-9-]/g, "-")}@flow.local`;
}

test("the forgot-password link leads to the reset form; unknown emails get the neutral answer", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("link", { name: "Forgot password?" }).click();
  await expect(page).toHaveURL(/\/reset-password$/);
  // Lazy route: wait for an element unique to the reset page before interacting.
  await expect(page.getByRole("button", { name: "Send reset link" })).toBeVisible();

  await page.getByRole("textbox", { name: "Email" }).fill(uniqueEmail("e2e-reset-nobody"));
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

  // A second request for the same address within a minute gets the neutral limit message.
  const throttled = uniqueEmail("e2e-reset-throttle");
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText(/password reset requests are temporarily limited/i)).toBeVisible();
});

test("a reset link preserves the old password until confirmation and cannot be reused", async ({ page }) => {
  const mailpitUp = await fetch(`${MAILPIT_URL}/api/v1/messages`).then(
    (r) => r.ok,
    () => false,
  );
  test.skip(!mailpitUp, "Mailpit (compose stack) is not reachable — email roundtrip untestable");

  await login(page);
  const user = await createUserViaUi(page, "E2E-Reset");
  try {
    await signOut(page);
    await page.goto("/reset-password");
    await page.getByRole("textbox", { name: "Email" }).fill(user.email);
    await page.getByRole("button", { name: "Send reset link" }).click();
    await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

    // Pull the link out of the Mailpit catcher (delivery is asynchronous): path + fragment only.
    let body: string | undefined;
    await expect
      .poll(
        async () => {
          const list = await fetch(`${MAILPIT_URL}/api/v1/messages`).then((r) => r.json());
          const msg = list.messages?.find(
            (m: { Subject: string; To?: { Address: string }[] }) =>
              m.Subject === "Reset your Flow password" && m.To?.some((t) => t.Address === user.email),
          );
          if (!msg) return undefined;
          body = (await fetch(`${MAILPIT_URL}/api/v1/message/${msg.ID}`).then((r) => r.json())).Text as string;
          return body;
        },
        { timeout: 15_000 },
      )
      .toBeTruthy();
    expect(body).toContain("Your password is unchanged");
    const resetPath = body!.match(/\/reset-password\/confirm#token=[A-Za-z0-9_-]{43}/)?.[0];
    expect(resetPath, "the email carries the single-use confirm link").toBeTruthy();

    // Requesting a reset preserves the password; the public confirmation page also works signed in.
    await loginViaForm(page, user.email, user.password);
    const oldToken = await page.evaluate(() => localStorage.getItem("flow.auth.token"));
    expect(oldToken).toBeTruthy();
    await page.goto(resetPath!);
    await expect(page.getByRole("button", { name: "Set new password" })).toBeVisible();
    await expect(page).toHaveURL(/\/reset-password\/confirm$/);
    // A regular user cannot list Flow users, but a 403 proves the session survived opening the link.
    const stillActive = await page.request.get("/api/v1/flow-users", {
      headers: { Authorization: `Bearer ${oldToken}` },
    });
    expect(stillActive.status()).toBe(403);

    const chosenPassword = "Reset-chosen-password-123";
    await page.getByRole("textbox", { name: "New password", exact: true }).fill(chosenPassword);
    await page.getByRole("textbox", { name: "Confirm new password", exact: true }).fill(chosenPassword);
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText(/your password has been reset/i)).toBeVisible();
    // The reset ended every session of the account: the bearer from before it is dead.
    const afterReset = await page.request.get("/api/v1/flow-users", {
      headers: { Authorization: `Bearer ${oldToken}` },
    });
    expect(afterReset.status()).toBe(401);

    await loginViaForm(page, user.email, chosenPassword);
    await signOut(page);

    // The old (revealed-at-creation) password no longer works. Development stacks lift the
    // per-IP login bucket, so the rejection message is deterministic here.
    await page.goto("/login");
    await page.getByRole("textbox", { name: "Email" }).fill(user.email);
    await page.getByRole("textbox", { name: "Password" }).fill(user.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Invalid email or password")).toBeVisible();

    // The link is single-use: reopening it and submitting is refused.
    await page.goto(resetPath!);
    await page.getByRole("textbox", { name: "New password", exact: true }).fill(chosenPassword);
    await page.getByRole("textbox", { name: "Confirm new password", exact: true }).fill(chosenPassword);
    await page.getByRole("button", { name: "Set new password" }).click();
    await expect(page.getByText(/this reset link is invalid/i)).toBeVisible();
    await expect(page.getByRole("link", { name: "Request another reset link" })).toBeVisible();
  } finally {
    // Cleanup: the spec owns its throwaway account.
    await login(page);
    await deleteUserRow(page, user.name);
  }
});
