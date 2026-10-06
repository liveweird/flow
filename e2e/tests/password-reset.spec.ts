import { createUserViaUi, deleteUserRow, expect, login, signOut, test, uniqueText } from "./helpers";

// Self-service password reset: the "Forgot password?" flow on the login screen.
// The full email roundtrip needs the compose stack's Mailpit catcher (http://localhost:8028 —
// see docker-compose.yaml); when it is unreachable (e.g. a dev stack on the log transport) that
// one test skips itself. The dev stack lifts the per-IP reset bucket (100/min, the login-bucket
// idiom), so back-to-back runs never trip it; the per-email throttle is exercised explicitly.

const MAILPIT = "http://localhost:8028";

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
  await expect(page.getByRole("button", { name: "Send new password" })).toBeVisible();

  await page.getByRole("textbox", { name: "Email" }).fill(uniqueEmail("e2e-reset-nobody"));
  await page.getByRole("button", { name: "Send new password" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

  // A second request for the same address within a minute gets the neutral limit message.
  const throttled = uniqueEmail("e2e-reset-throttle");
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send new password" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();
  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(throttled);
  await page.getByRole("button", { name: "Send new password" }).click();
  await expect(page.getByText(/password reset requests are temporarily limited/i)).toBeVisible();
});

test("a reset email delivers a working new password and kills the old one", async ({ page }) => {
  const mailpitUp = await fetch(`${MAILPIT}/api/v1/messages`).then(
    (r) => r.ok,
    () => false,
  );
  test.skip(!mailpitUp, "Mailpit (compose stack) is not reachable — email roundtrip untestable");

  await login(page);
  const user = await createUserViaUi(page, "E2E-Reset");
  await signOut(page);

  await page.goto("/reset-password");
  await page.getByRole("textbox", { name: "Email" }).fill(user.email);
  await page.getByRole("button", { name: "Send new password" }).click();
  await expect(page.getByText(/if an account with this address exists/i)).toBeVisible();

  // Pull the new password out of the Mailpit catcher (delivery is asynchronous).
  let newPassword: string | undefined;
  await expect
    .poll(
      async () => {
        const list = await fetch(`${MAILPIT}/api/v1/messages`).then((r) => r.json());
        const msg = list.messages?.find((m: { To?: { Address: string }[] }) =>
          m.To?.some((t) => t.Address === user.email),
        );
        if (!msg) return undefined;
        const text: string = (
          await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`).then((r) => r.json())
        ).Text;
        newPassword = text.match(/^[A-Za-z0-9_-]{16}$/m)?.[0];
        return newPassword;
      },
      { timeout: 15_000 },
    )
    .toBeTruthy();

  // The worker SENDS before it STORES (send-before-store, security.md), and the store hashes at
  // bcrypt cost 12 — under load the email can land a few hundred ms before the new password works.
  // Wait for the store through the API (at most 3 tries, under the 5-failure lockout) before the UI login.
  await expect
    .poll(
      async () =>
        (await page.request.post("/api/v1/login", { data: { email: user.email, password: newPassword } })).status(),
      { intervals: [1_000, 2_000], timeout: 4_000 },
    )
    .toBe(200);

  await login(page, user.email, newPassword!);
  await signOut(page);

  // The old (revealed-at-creation) password no longer works. Development stacks lift the
  // per-IP login bucket, so the rejection message is deterministic here.
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill(user.email);
  await page.getByRole("textbox", { name: "Password" }).fill(user.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid email or password")).toBeVisible();

  // Cleanup: the spec owns its throwaway account.
  await login(page);
  await deleteUserRow(page, user.name);
});
