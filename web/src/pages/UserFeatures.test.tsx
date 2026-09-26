import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import UserFeatures from "./UserFeatures";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage(id: number | string = 7) {
  return renderWithProviders(
    <Routes>
      <Route path="/users/:id/features" element={<UserFeatures />} />
      <Route path="/users" element={<PathProbe />} />
    </Routes>,
    { route: `/users/${id}/features` },
  );
}

const EXISTING_USER = {
  id: 7,
  name: "Alice",
  email: "alice@example.com",
  roles: [] as string[],
  disabledFeatures: ["MFA"] as string[],
};

describe("UserFeatures page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function mockApi({
    user = EXISTING_USER,
    userStatus = 200,
    putStatus = 204,
  }: { user?: typeof EXISTING_USER; userStatus?: number; putStatus?: number } = {}) {
    mockFetch.mockImplementation((_input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "PUT") {
        return Promise.resolve(
          putStatus === 204
            ? new Response(null, { status: 204 })
            : jsonResponse(putStatus, { title: "err", status: putStatus }),
        );
      }
      return Promise.resolve(
        userStatus === 200
          ? jsonResponse(200, user)
          : jsonResponse(userStatus, { title: "not found", status: userStatus }),
      );
    });
  }

  test("renders the MFA switch seeded from the disabled set (disabled row = unchecked)", async () => {
    mockApi();
    renderPage(7);
    const mfa = (await screen.findByRole("switch", { name: /email mfa/i })) as HTMLInputElement;
    expect(mfa.checked).toBe(false);
  });

  test("toggling and saving PUTs the wholesale disabled set, toasts, and returns to /users", async () => {
    mockApi();
    const showSpy = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderPage(7);

    // Enable MFA → the new disabled set is empty.
    await user.click(await screen.findByRole("switch", { name: /email mfa/i }));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(screen.getByTestId("probe")).toHaveTextContent("/users"));
    const putCall = mockFetch.mock.calls.find(([, init]) => (init as RequestInit)?.method === "PUT");
    expect(putCall![0]).toBe("/api/v1/users/7/features");
    expect(JSON.parse((putCall![1] as { body: string }).body)).toEqual({ disabledFeatures: [] });
    expect(showSpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Feature flags saved" }),
    );
  });

  test("a failing save shows an inline error and stays on the page", async () => {
    mockApi({ putStatus: 500 });
    const user = userEvent.setup();
    renderPage(7);

    await user.click(await screen.findByRole("switch", { name: /email mfa/i }));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Saving the flags failed (500)")).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).toBeNull();
  });

  test("a 404 user shows the not-found state", async () => {
    mockApi({ userStatus: 404 });
    renderPage(7);
    expect(await screen.findByText("User not found.")).toBeInTheDocument();
  });

  test("a non-numeric id redirects without fetching the user", () => {
    mockApi();
    renderPage("abc");
    expect(screen.getByTestId("probe")).toHaveTextContent("/users");
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
