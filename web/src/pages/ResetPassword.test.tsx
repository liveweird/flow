import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import ResetPassword from "./ResetPassword";
import { renderWithProviders } from "../test/render";

describe("ResetPassword page", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function submit(email: string) {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), email);
    await user.click(screen.getByRole("button", { name: /send new password/i }));
  }

  test("posts the email and shows the neutral confirmation on 202", async () => {
    const mockFetch = globalThis.fetch as ReturnType<typeof vi.fn>;
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 202 }));
    renderWithProviders(<ResetPassword />, { route: "/reset-password" });

    await submit("someone@example.com");

    expect(
      await screen.findByText(/if an account with this address exists/i),
    ).toBeInTheDocument();
    // The form is gone — no way to tell whether the account existed.
    expect(screen.queryByRole("button", { name: /send new password/i })).not.toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/v1/password-reset",
      expect.objectContaining({ method: "POST" }),
    );
    expect(JSON.parse(mockFetch.mock.calls[0][1].body)).toEqual({ email: "someone@example.com" });
  });

  test("429 shows the throttle message and keeps the form", async () => {
    const mockFetch = globalThis.fetch as ReturnType<typeof vi.fn>;
    mockFetch.mockResolvedValueOnce(new Response("{}", { status: 429 }));
    renderWithProviders(<ResetPassword />, { route: "/reset-password" });

    await submit("someone@example.com");

    expect(await screen.findByText(/password reset requests are temporarily limited/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /send new password/i })).toBeInTheDocument();
  });

  test("503 explains the deployment cannot send email", async () => {
    const mockFetch = globalThis.fetch as ReturnType<typeof vi.fn>;
    mockFetch.mockResolvedValueOnce(new Response("{}", { status: 503 }));
    renderWithProviders(<ResetPassword />, { route: "/reset-password" });

    await submit("someone@example.com");

    expect(await screen.findByText(/not available on this deployment/i)).toBeInTheDocument();
  });

  test("invalid email is rejected client-side without an API call", async () => {
    const mockFetch = globalThis.fetch as ReturnType<typeof vi.fn>;
    renderWithProviders(<ResetPassword />, { route: "/reset-password" });

    await submit("not-an-email");

    expect(await screen.findByText(/enter a valid email/i)).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("offers a way back to the sign-in page", () => {
    renderWithProviders(<ResetPassword />, { route: "/reset-password" });
    expect(screen.getByRole("link", { name: /back to sign in/i })).toHaveAttribute(
      "href",
      "/login",
    );
  });
});
