import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { APP_VERSION } from "./changelog/version";

const TOKEN_KEY = "flow.auth.token";

function renderApp(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe("App shell", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("when authenticated", () => {
    beforeEach(() => {
      localStorage.setItem(TOKEN_KEY, "fake-token");
    });

    test("renders the brand and the home page at /", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { level: 2, name: "Flow" })).toBeInTheDocument();
      expect(screen.getAllByText("Flow").length).toBeGreaterThan(0);
    });

    test("the navbar shows the version stamp", async () => {
      renderApp("/");
      expect(await screen.findByText(new RegExp(`v${APP_VERSION.replace(/\./g, "\\.")}`))).toBeInTheDocument();
    });

    test("the sidebar renders its sections with every leaf link addressable", async () => {
      renderApp("/");
      // Sections are labelled groups, never toggles — every leaf is in the DOM immediately.
      const overview = await screen.findByRole("group", { name: "Overview" });
      expect(within(overview).getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
      // A non-admin session still sees Administration — Teams is everyone's, only Users and
      // Feature flags are admin-only and drop out.
      const admin = screen.getByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Feature flags" })).not.toBeInTheDocument();
      // Account items live in the header menu, not the sidebar.
      expect(screen.queryByRole("link", { name: "Change password" })).not.toBeInTheDocument();
    });

    test("the nav toggle collapses the sidebar to an icon rail and persists the choice", async () => {
      const user = userEvent.setup();
      renderApp("/");
      const overview = await screen.findByRole("group", { name: "Overview" });
      expect(within(overview).getByText("Overview")).toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Show or hide the navigation" }));

      // The section label text is gone, but every leaf is still addressable by name and href.
      expect(screen.queryByText("Overview")).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
      expect(screen.getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(localStorage.getItem("flow.viewSettings.appShell.navCollapsed")).toBe("true");
    });

    test("a fresh render starts collapsed once the choice was persisted", async () => {
      localStorage.setItem("flow.viewSettings.appShell.navCollapsed", "true");
      renderApp("/");
      await screen.findByRole("heading", { level: 2, name: "Flow" });
      expect(screen.queryByText("Overview")).not.toBeInTheDocument();
      const toggle = screen.getByRole("button", { name: "Show or hide the navigation" });
      expect(toggle).not.toHaveAttribute("data-expanded");
      expect(screen.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
    });

    test("an admin session additionally sees Users and Feature flags in Administration", async () => {
      localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
      renderApp("/");
      const admin = await screen.findByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).getByRole("link", { name: "Users" })).toHaveAttribute("href", "/users");
      expect(within(admin).getByRole("link", { name: "Feature flags" })).toHaveAttribute("href", "/feature-flags");
    });

    test("the account menu holds the Changelog link; the stamp links there and the trigger carries the dot", async () => {
      const user = userEvent.setup();
      renderApp("/");
      expect(await screen.findByTitle("Build version")).toHaveAttribute("href", "/changelog");
      // The what's-new dot rides the account-menu trigger while the version is unseen.
      expect(screen.getByTitle("What's new")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Account menu" }));
      expect(await screen.findByRole("menuitem", { name: /Changelog/ })).toHaveAttribute("href", "/changelog");
    });

    test("opening the changelog clears the what's-new dot immediately", async () => {
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: "Account menu" }));
      await user.click(await screen.findByRole("menuitem", { name: /Changelog/ }));
      // Explicit timeout — the Changelog chunk is lazy.
      expect(
        await screen.findByRole("heading", { level: 2, name: "Changelog" }, { timeout: 5000 }),
      ).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByTitle("What's new")).not.toBeInTheDocument());
      expect(JSON.parse(localStorage.getItem("flow.changelog")!)).toEqual({
        seenVersion: APP_VERSION,
      });
    });

    test("shows no dot when the current version was already seen", async () => {
      localStorage.setItem("flow.changelog", JSON.stringify({ seenVersion: APP_VERSION }));
      renderApp("/");
      await screen.findByRole("heading", { level: 2, name: "Flow" });
      expect(screen.queryByTitle("What's new")).not.toBeInTheDocument();
    });

    test("an unmatched URL renders the not-found page inside the shell", async () => {
      renderApp("/definitely/not-a-page");
      expect(
        await screen.findByRole("heading", { level: 2, name: "Page not found" }),
      ).toBeInTheDocument();
      // The Shell mounted around it — an unmatched URL must never render a blank document.
      expect(screen.getByText("Flow")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
    });

    test("logout clears the session and lands on the login page", async () => {
      (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        new Response(null, { status: 204 }),
      );
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: "Account menu" }));
      await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
      await waitFor(() => expect(localStorage.getItem(TOKEN_KEY)).toBeNull());
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      // The signed-out banner rides the flagSignedOut() handoff.
      expect(screen.getByText("You've been signed out.")).toBeInTheDocument();
    });
  });

  describe("when not authenticated", () => {
    test("a protected route redirects to the login page", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    });

    test("the login page renders directly at /login", async () => {
      renderApp("/login");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      expect(screen.getByText("Flow")).toBeInTheDocument();
    });
  });
});
