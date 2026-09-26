import { describe, expect, test, vi } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import { act, renderWithProviders, screen } from "./test/render";
import { authedFetch } from "./api/http";
import { persistSession } from "./api/session";
import { jsonResponse } from "./test/http";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, consumeSignedOut, flagSignedOut, hasPendingSignedOut, useAdmin } from "./auth";

const TOKEN_KEY = "flow.auth.token";

function LocationProbe() {
  const location = useLocation();
  return (
    <div
      data-testid="location"
      data-pathname={location.pathname}
      data-search={location.search}
      data-hash={location.hash}
    />
  );
}

function TestRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<div>home page</div>} />
        <Route path="/secret" element={<div>secret page</div>} />
        <Route path="/contracts/:id/diff" element={<LocationProbe />} />
        <Route element={<RequireAdmin />}>
          <Route path="/admin-only" element={<div>admin page</div>} />
        </Route>
      </Route>
    </Routes>
  );
}

const SESSION = {
  token: "access-before",
  refreshToken: "refresh-before",
  expiresAt: 1,
  refreshExpiresAt: 2,
  userId: 7,
  roles: ["ADMIN" as const],
  disabledFeatures: [],
  language: "en" as const,
};

function AdminProbe() {
  return <div>{useAdmin() ? "admin role" : "regular role"}</div>;
}

describe("route guards", () => {
  test("RequireAuth redirects an anonymous visitor to /login", () => {
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  test("RequireAuth renders the outlet for an authenticated visitor", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("secret page")).toBeInTheDocument();
  });

  test("RedirectIfAuthed bounces an authenticated visitor off /login", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>home page</div>} />
        <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      </Routes>,
      { route: "/login" },
    );
    expect(screen.getByText("home page")).toBeInTheDocument();
  });

  test("RedirectIfAuthed preserves the saved destination's query string and hash", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(
      <Routes>
        <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
        <Route path="/contracts/:id/diff" element={<LocationProbe />} />
      </Routes>,
      {
        route: "/login",
        state: { from: { pathname: "/contracts/5/diff", search: "?from=10&to=11", hash: "#compatibility" } },
      },
    );
    const destination = screen.getByTestId("location");
    expect(destination).toHaveAttribute("data-pathname", "/contracts/5/diff");
    expect(destination).toHaveAttribute("data-search", "?from=10&to=11");
    expect(destination).toHaveAttribute("data-hash", "#compatibility");
  });

  test("RequireAdmin sends a regular user home and renders the outlet for an admin", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    localStorage.setItem("flow.auth.roles", "[]");
    renderWithProviders(<TestRoutes />, { route: "/admin-only" });
    expect(screen.getByText("home page")).toBeInTheDocument();
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
    renderWithProviders(<TestRoutes />, { route: "/admin-only" });
    expect(screen.getByText("admin page")).toBeInTheDocument();
  });

  test("silent refresh removes admin access from mounted role consumers", async () => {
    persistSession(SESSION);
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, { ...SESSION, token: "access-after", refreshToken: "refresh-after", roles: [] }))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    try {
      renderWithProviders(<TestRoutes />, { route: "/admin-only" });
      expect(screen.getByText("admin page")).toBeInTheDocument();
      await act(async () => { await authedFetch("/api/v1/thing"); });
      expect(screen.getByText("home page")).toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  test("role consumers react to promotions written by another tab", () => {
    persistSession({ ...SESSION, roles: [] });
    renderWithProviders(<AdminProbe />);
    expect(screen.getByText("regular role")).toBeInTheDocument();
    act(() => {
      localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
      window.dispatchEvent(new StorageEvent("storage", { key: "flow.auth.roles" }));
    });
    expect(screen.getByText("admin role")).toBeInTheDocument();
  });

  test("the signed-out flag is one-shot", () => {
    flagSignedOut();
    expect(hasPendingSignedOut()).toBe(true);
    expect(hasPendingSignedOut()).toBe(true);
    expect(consumeSignedOut()).toBe(true);
    expect(hasPendingSignedOut()).toBe(false);
    expect(consumeSignedOut()).toBe(false);
  });
});
