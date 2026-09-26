import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import ChangePassword from "./ChangePassword";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const USER_ID_KEY = "flow.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

async function fill(user: ReturnType<typeof userEvent.setup>, current: string, next: string, confirm = next) {
  await user.type(screen.getByLabelText(/^current password( \*)?$/i), current);
  await user.type(screen.getByLabelText(/^new password( \*)?$/i, { selector: "input" }), next);
  await user.type(screen.getByLabelText(/^confirm new password( \*)?$/i), confirm);
  await user.click(screen.getByRole("button", { name: /^save$/i }));
}

describe("ChangePassword page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(USER_ID_KEY, "5");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("PUTs to the caller's own password endpoint and toasts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      Promise.resolve(
        (init?.method ?? "GET") === "PUT" && url === "/api/v1/users/5/password"
          ? new Response(null, { status: 204 })
          : jsonResponse(404, {}),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ChangePassword />);

    await fill(user, "old-password-1", "new-password-42");
    await waitFor(() => expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Password changed", color: "teal" }),
    ));
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/users/5/password",
    );
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      password: "new-password-42",
      currentPassword: "old-password-1",
    });
    // The form resets after success.
    expect((screen.getByLabelText(/^current password( \*)?$/i) as HTMLInputElement).value).toBe("");
    toast.mockRestore();
  });

  test("client-side validation: length, byte ceiling, and mismatch", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangePassword />);

    await fill(user, "old-password-1", "short", "short");
    expect(await screen.findByText(/at least 10 characters/i)).toBeInTheDocument();

    // 24 × 🐸 = 96 UTF-8 bytes — over the 71-byte bcrypt ceiling despite few "characters".
    const froggy = "🐸".repeat(24);
    await user.clear(screen.getByLabelText(/^new password( \*)?$/i, { selector: "input" }));
    await user.clear(screen.getByLabelText(/^confirm new password( \*)?$/i));
    await user.type(screen.getByLabelText(/^new password( \*)?$/i, { selector: "input" }), froggy);
    await user.type(screen.getByLabelText(/^confirm new password( \*)?$/i), froggy);
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText(/at most 71 bytes/i)).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^new password( \*)?$/i, { selector: "input" }));
    await user.clear(screen.getByLabelText(/^confirm new password( \*)?$/i));
    await user.type(screen.getByLabelText(/^new password( \*)?$/i, { selector: "input" }), "new-password-42");
    await user.type(screen.getByLabelText(/^confirm new password( \*)?$/i), "different-42");
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("a 403 reads as wrong current password", async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(jsonResponse(403, { title: "Forbidden", status: 403 })),
    );
    const user = userEvent.setup();
    renderWithProviders(<ChangePassword />);

    await fill(user, "wrong-old-1", "new-password-42");
    expect(await screen.findByText(/current password is incorrect/i)).toBeInTheDocument();
  });
});
