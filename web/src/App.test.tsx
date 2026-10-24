import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { consumeSignedOut } from "./auth";
import { APP_VERSION } from "./changelog/version";
import { jsonResponse } from "./test/http";
import { currentFixture, historyFixture } from "./test/telemetryFixtures";

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

function scrollWindowTo(y: number) {
  Object.defineProperty(window, "scrollY", { value: y, configurable: true });
  act(() => {
    window.dispatchEvent(new Event("scroll"));
  });
}

describe("App shell", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    Object.defineProperty(window, "scrollY", { value: 0, configurable: true });
    vi.unstubAllGlobals();
    localStorage.clear();
    // The signed-out banner flag is module-level state (`auth.tsx`); the sign-out flows set it and only the login page consumes it.
    consumeSignedOut();
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
      // A non-admin session still sees Administration — Teams, Capacity and Public holidays are everyone's, only Flow users and
      // Feature flags are admin-only and drop out.
      const admin = screen.getByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).getByRole("link", { name: "Capacity" })).toHaveAttribute("href", "/capacity");
      expect(within(admin).getByRole("link", { name: "Public holidays" })).toHaveAttribute("href", "/public-holidays");
      expect(within(admin).queryByRole("link", { name: "Flow users" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Feature flags" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Data sources" })).not.toBeInTheDocument();
      // Account items live in the header menu, not the sidebar.
      expect(screen.queryByRole("link", { name: "Change password" })).not.toBeInTheDocument();
    });

    test("the Back to top button rides the overview, once the page is scrolled", async () => {
      renderApp("/");
      await screen.findByRole("heading", { level: 2, name: "Flow" });
      expect(screen.queryByRole("button", { name: "Back to top" })).not.toBeInTheDocument();
      scrollWindowTo(900);
      expect(screen.getByRole("button", { name: "Back to top" })).toBeInTheDocument();
    });

    test("the Back to top button is a report-and-overview affair: no other page has it", async () => {
      renderApp("/changelog");
      await screen.findByRole("heading", { level: 2, name: "Changelog" });
      scrollWindowTo(900);
      expect(screen.queryByRole("button", { name: "Back to top" })).not.toBeInTheDocument();
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

    test("the mobile burger has an accessible name and reports whether the drawer is open", async () => {
      // Mantine hides the burger from `sm` up through a media query happy-dom evaluates against
      // the window width, so the test runs at a phone width (restored afterwards).
      const desktopWidth = window.innerWidth;
      window.innerWidth = 390;
      try {
        const user = userEvent.setup();
        renderApp("/");
        await screen.findByRole("group", { name: "Overview" });
        const burger = screen.getByRole("button", { name: "Toggle navigation" });
        expect(burger).toHaveAttribute("aria-expanded", "false");
        await user.click(burger);
        expect(burger).toHaveAttribute("aria-expanded", "true");
      } finally {
        window.innerWidth = desktopWidth;
      }
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

    test("an admin session additionally sees Flow users and Feature flags in Administration", async () => {
      localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
      renderApp("/");
      const admin = await screen.findByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).getByRole("link", { name: "Flow users" })).toHaveAttribute("href", "/flow-users");
      expect(within(admin).getByRole("link", { name: "Feature flags" })).toHaveAttribute("href", "/feature-flags");
      expect(within(admin).getByRole("link", { name: "Data sources" })).toHaveAttribute("href", "/data-sources");
    });

    describe("the telemetry panel", () => {
      const PANEL_KEY = "flow.viewSettings.telemetry.panelOpen";

      function stubTelemetry() {
        const fetchMock = vi.fn((url: string) => {
          if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(jsonResponse(200, currentFixture()));
          if (url.startsWith("/api/v1/telemetry/history")) return Promise.resolve(jsonResponse(200, historyFixture("memory", "24h", [])));
          return Promise.reject(new Error("offline"));
        });
        vi.stubGlobal("fetch", fetchMock);
        return fetchMock;
      }

      test("an admin opens it from the account menu (it pushes the page, lands in an aside) and closes it with its own button", async () => {
        localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
        stubTelemetry();
        const user = userEvent.setup();
        renderApp("/");
        await user.click(await screen.findByRole("button", { name: /account menu/i }));
        const item = await screen.findByRole("menuitem", { name: "Telemetry panel" });
        expect(item).not.toHaveAttribute("aria-current");
        await user.click(item);
        const aside = await screen.findByRole("complementary", { name: "Telemetry" });
        expect(await within(aside).findByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
        expect(localStorage.getItem(PANEL_KEY)).toBe("true");
        await user.click(within(aside).getByRole("button", { name: "Close" }));
        await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
        expect(localStorage.getItem(PANEL_KEY)).toBe("false");
      });

      test("a stored open flag never shows it to a regular user, and nothing is requested", async () => {
        localStorage.setItem(PANEL_KEY, "true");
        const fetchMock = stubTelemetry();
        renderApp("/");
        await screen.findByRole("group", { name: "Overview" });
        expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument();
        expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/telemetry/"))).toBe(false);
      });

      test("a render crash inside the panel keeps the page and offers Close panel, which clears the stored flag", async () => {
        localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        // A malformed answer (no processes) crashes the panel's render.
        vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { generatedAt: 1 }))));
        const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
        const user = userEvent.setup();
        try {
          renderApp("/");
          const aside = await screen.findByRole("complementary", { name: "Telemetry" });
          expect(await within(aside).findByText("The telemetry panel stopped working")).toBeInTheDocument();
          // The shell around it survives.
          expect(screen.getByRole("group", { name: "Overview" })).toBeInTheDocument();
          await user.click(within(aside).getByRole("button", { name: "Close panel" }));
          await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
          expect(localStorage.getItem(PANEL_KEY)).toBe("false");
        } finally {
          consoleError.mockRestore();
        }
      });

      test("an admin with the flag stored finds it open after a reload", async () => {
        localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        stubTelemetry();
        renderApp("/");
        expect(await screen.findByRole("complementary", { name: "Telemetry" })).toBeInTheDocument();
      });
    });

    test("the account menu holds the Changelog link; the stamp links there and the trigger carries the dot", async () => {
      const user = userEvent.setup();
      renderApp("/");
      expect(await screen.findByTitle("Build version")).toHaveAttribute("href", "/changelog");
      // The what's-new dot rides the account-menu trigger while the version is unseen.
      expect(screen.getByTitle("What's new")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: /account menu/i }));
      expect(await screen.findByRole("menuitem", { name: /Changelog/ })).toHaveAttribute("href", "/changelog");
    });

    test("opening the changelog clears the what's-new dot immediately", async () => {
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: /account menu/i }));
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
      await user.click(await screen.findByRole("button", { name: /account menu/i }));
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
