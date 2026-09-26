import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import i18n from "../i18n";
import { RedirectIfAuthed, RequireAuth } from "../auth";
import Login from "../pages/Login";
import UserMenu from "./UserMenu";

const ME = { id: 7, name: "Alice Admin", email: "alice@flow.local", roles: ["ADMIN"], disabledFeatures: [], language: "en" };

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
};

function stubFetch(handler: (url: string, init?: RequestInit) => Response | undefined) {
  const mockFetch = vi.fn((url: string, init?: RequestInit) =>
    Promise.resolve(handler(url, init) ?? new Response(null, { status: 204 })),
  );
  vi.stubGlobal("fetch", mockFetch);
  return mockFetch;
}

function renderMenu() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<UserMenu />} />
      <Route path="/login" element={<p>login page</p>} />
    </Routes>,
  );
}

function renderProtectedMenu() {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<RedirectIfAuthed><Login /></RedirectIfAuthed>} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<p>home page</p>} />
        <Route path="/contracts/:id" element={<UserMenu />} />
      </Route>
    </Routes>,
    { route: "/contracts/7" },
  );
}

describe("UserMenu", () => {
  beforeEach(() => {
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.userId", "7");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(async () => {
    vi.unstubAllGlobals();
    localStorage.clear();
    document.documentElement.removeAttribute("data-mantine-color-scheme");
    await i18n.changeLanguage("en");
  });

  test("shows the signed-in identity from GET /users/{id} with the admin marker", async () => {
    stubFetch((url) => (url === "/api/v1/users/7" ? Response.json(ME) : undefined));
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(await screen.findByText("alice@flow.local")).toBeInTheDocument();
    expect(screen.getAllByText("Alice Admin").length).toBeGreaterThan(0);
    expect(screen.getByText("Admin")).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Change password" })).toHaveAttribute("href", "/change-password");
    expect(screen.getByRole("menuitem", { name: /Changelog/ })).toHaveAttribute("href", "/changelog");
  });

  test("lists every language by its native name; a pick switches the UI and saves server-side", async () => {
    const mockFetch = stubFetch((url) => (url === "/api/v1/users/7" ? Response.json(ME) : undefined));
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(await screen.findByRole("menuitem", { name: "English" })).toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "Polski" }));
    expect(i18n.resolvedLanguage).toBe("pl");
    await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url]) => url === "/api/v1/users/7/language");
      expect(call).toBeDefined();
      expect((call![1] as RequestInit).method).toBe("PUT");
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ language: "pl" });
    });
  });

  test("without a session userId no identity fetch and no language save happen", async () => {
    localStorage.removeItem("flow.auth.userId");
    const mockFetch = stubFetch(() => undefined);
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(await screen.findByRole("menuitem", { name: "Polski" }));
    expect(i18n.resolvedLanguage).toBe("pl");
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("the theme control switches the colour scheme without closing the menu", async () => {
    stubFetch(() => undefined);
    const user = userEvent.setup();
    renderMenu();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(await screen.findByRole("radio", { name: "Dark" }));
    await waitFor(() =>
      expect(document.documentElement.getAttribute("data-mantine-color-scheme")).toBe("dark"),
    );
    expect(screen.getByRole("menuitem", { name: "Sign out" })).toBeInTheDocument();
  });

  test("Sign out from a protected route starts the next session at home without waiting for revocation", async () => {
    const revokeResponse = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/v1/users/7") return Promise.resolve(Response.json(ME));
      if (url === "/api/v1/logout") return revokeResponse.promise;
      if (url === "/api/v1/login" && init?.method === "POST") {
        return Promise.resolve(Response.json({
          token: "next-token",
          expiresAt: 1,
          refreshToken: "next-refresh",
          refreshExpiresAt: 2,
          userId: 7,
          roles: ["ADMIN"],
          disabledFeatures: [],
          language: "en",
        }));
      }
      return Promise.resolve(new Response(null, { status: 204 }));
    }));
    const user = userEvent.setup();
    renderProtectedMenu();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
    await waitFor(() => expect(localStorage.getItem("flow.auth.token")).toBeNull());
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "Email" }), "alice@flow.local");
    await user.type(screen.getByLabelText("Password"), "new-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("home page")).toBeInTheDocument();
    expect(localStorage.getItem("flow.auth.token")).toBe("next-token");
    revokeResponse.resolve(new Response(null, { status: 204 }));
    await waitFor(() => expect(localStorage.getItem("flow.auth.token")).toBe("next-token"));
  });
});
