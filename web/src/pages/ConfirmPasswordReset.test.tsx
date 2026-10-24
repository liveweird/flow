import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import ConfirmPasswordReset from "./ConfirmPasswordReset";
import { jsonResponse } from "../test/http";

const TOKEN = "a".repeat(43);
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}{location.hash}</div>;
}
function renderConfirm(hash = `#token=${TOKEN}`) {
  return renderWithProviders(<StrictMode><Routes>
    <Route path="/reset-password/confirm" element={<><ConfirmPasswordReset /><LocationProbe /></>} />
  </Routes></StrictMode>, { route: `/reset-password/confirm${hash}` });
}
function fill(password = "chosen-password-123", confirm = password) {
  fireEvent.change(screen.getByLabelText(/^New password/, { selector: "input" }), { target: { value: password } });
  fireEvent.change(screen.getByLabelText(/^Confirm new password/, { selector: "input" }), { target: { value: confirm } });
}

describe("ConfirmPasswordReset", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("removes the fragment without consuming it, confirms once, and clears the local session", async () => {
    const fetch = vi.mocked(globalThis.fetch).mockResolvedValue(new Response(null, { status: 204 }));
    localStorage.setItem("flow.auth.token", "old-session");
    localStorage.setItem("flow.auth.refreshToken", "old-refresh");
    renderConfirm();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/reset-password\/confirm$/));
    expect(fetch).not.toHaveBeenCalled();
    expect(JSON.stringify(localStorage)).not.toContain(TOKEN);
    fill();
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));
    expect(await screen.findByText(/your password has been reset/i)).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith("/api/v1/password-reset/confirm", expect.objectContaining({
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: TOKEN, password: "chosen-password-123" }),
    }));
    expect(localStorage.getItem("flow.auth.token")).toBeNull();
    expect(localStorage.getItem("flow.auth.refreshToken")).toBeNull();
    expect(screen.queryByLabelText(/^New password/, { selector: "input" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "/login");
  });

  test.each(["", "#token=bad", `?token=${TOKEN}`])("missing or malformed fragment %s offers another link", (hash) => {
    renderConfirm(hash);
    expect(screen.getByText(/invalid, expired, or already used/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /request another/i })).toHaveAttribute("href", "/reset-password");
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  test.each([
    ["short", "short", /at least 10 characters/i],
    ["é".repeat(36), "é".repeat(36), /at most 71 bytes/i],
    ["chosen-password", "different-password", /passwords do not match/i],
  ])("validates new password %s before exchange", async (password, confirm, message) => {
    renderConfirm();
    fill(password as string, confirm as string);
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));
    expect(await screen.findByText(message as RegExp)).toBeInTheDocument();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  test.each([401, 429, 400, 500])("handles a %i without refreshing a session", async (status) => {
    const fetch = vi.mocked(globalThis.fetch).mockResolvedValue(jsonResponse(status, { status }));
    localStorage.setItem("flow.auth.token", "existing-session");
    renderConfirm();
    fill();
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("flow.auth.token")).toBe("existing-session");
    if (status === 401) expect(screen.getByRole("link", { name: /request another/i })).toBeInTheDocument();
    else expect(screen.getByRole("button", { name: "Set new password" })).toBeEnabled();
  });

  test("a network failure keeps the in-memory token so the user can retry", async () => {
    vi.mocked(globalThis.fetch).mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    renderConfirm();
    fill();
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));
    expect(await screen.findByText(/check your connection/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));
    expect(await screen.findByText(/your password has been reset/i)).toBeInTheDocument();
  });
});
