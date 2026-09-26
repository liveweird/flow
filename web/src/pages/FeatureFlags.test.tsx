import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import { QueryClient } from "@tanstack/react-query";
import FeatureFlags from "./FeatureFlags";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/feature-flags" element={<FeatureFlags />} />
      <Route path="/" element={<PathProbe />} />
    </Routes>,
    { route: "/feature-flags" },
  );
}

type TestUser = {
  id: number;
  name: string;
  email: string;
  roles: "ADMIN"[];
  disabledFeatures: "MFA"[];
};

const ROWS: TestUser[] = [
  { id: 1, name: "Alice Admin", email: "alice@example.com", roles: ["ADMIN"], disabledFeatures: [] },
  { id: 2, name: "Bob Basic", email: "bob@example.com", roles: [], disabledFeatures: ["MFA"] },
];

function usersPage(items: TestUser[], total = items.length) {
  return jsonResponse(200, { items, page: 1, pageSize: 20, total });
}

describe("FeatureFlags page", () => {
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

  function mockApi({ putStatuses = [] as number[] } = {}) {
    let putIndex = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "PUT") {
        const status = putStatuses[putIndex] ?? 204;
        putIndex += 1;
        return Promise.resolve(
          status === 204 ? new Response(null, { status: 204 }) : jsonResponse(status, { status }),
        );
      }
      if (url.startsWith("/api/v1/users")) return Promise.resolve(usersPage(ROWS));
      return Promise.resolve(jsonResponse(404, {}));
    });
  }

  function putCalls(): Array<{ url: string; body: Record<string, unknown> }> {
    return mockFetch.mock.calls
      .filter(([, init]) => (init as RequestInit)?.method === "PUT")
      .map(([url, init]) => ({
        url: url as string,
        body: JSON.parse((init as { body: string }).body) as Record<string, unknown>,
      }));
  }

  test("renders rows with switches reflecting each user's disabled set", async () => {
    mockApi();
    renderPage();
    const aliceSwitch = (await screen.findByRole("switch", {
      name: "Toggle Email MFA for Alice Admin",
    })) as HTMLInputElement;
    expect(aliceSwitch.checked).toBe(true);
    expect(
      (screen.getByRole("switch", { name: "Toggle Email MFA for Bob Basic" }) as HTMLInputElement)
        .checked,
    ).toBe(false);
    // The name is the way into the user's per-user features editor.
    expect(screen.getByRole("link", { name: "Feature flags for Alice Admin" })).toHaveAttribute(
      "href",
      "/users/1/features",
    );
  });

  test("toggling one row PUTs its new wholesale set and toasts", async () => {
    mockApi();
    const showSpy = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("switch", { name: "Toggle Email MFA for Alice Admin" }));

    await waitFor(() => expect(putCalls()).toHaveLength(1));
    expect(putCalls()[0]).toEqual({
      url: "/api/v1/users/1/features",
      body: { disabledFeatures: ["MFA"] },
    });
    expect(showSpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Feature flags saved" }),
    );
  });

  test("a successful toggle cannot be overwritten by a pending first filtered response", async () => {
    const filtered = deferred<Response>();
    const updatedRows: TestUser[] = [{ ...ROWS[0], disabledFeatures: ["MFA"] }, ROWS[1]];
    let filteredReads = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "PUT" && url === "/api/v1/users/1/features") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "GET" && url.startsWith("/api/v1/users?")) {
        const name = new URL(url, "http://test").searchParams.get("name");
        if (name === "Alice Admin") {
          filteredReads++;
          if (filteredReads === 1) return filtered.promise;
          return Promise.resolve(usersPage(updatedRows));
        }
        return Promise.resolve(usersPage(ROWS));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = queryClient.invalidateQueries.bind(queryClient);
    vi.spyOn(queryClient, "invalidateQueries").mockImplementation((filters, options) => {
      const result = invalidate(filters, options);
      // Deliver the pre-toggle response precisely when the successful mutation refreshes.
      filtered.resolve(usersPage(ROWS));
      return result;
    });
    const user = userEvent.setup();
    renderWithProviders(<FeatureFlags />, { queryClient });

    const toggle = await screen.findByRole("switch", { name: "Toggle Email MFA for Alice Admin" });
    expect(toggle).toBeChecked();
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Name"), "Alice Admin");
    await waitFor(() => expect(filteredReads).toBe(1));

    await user.click(toggle);

    await waitFor(() => expect(toggle).not.toBeDisabled());
    await waitFor(() => expect(toggle).not.toBeChecked());
    expect(filteredReads).toBe(2);
  });

  test("the state filter sends the feature+featureEnabled pair; 'any' sends neither", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice@example.com");
    expect(mockFetch.mock.calls[0][0]).not.toContain("feature=");

    // The filter controls live behind the collapsed FilterPanel disclosure.
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.click(screen.getByRole("combobox", { name: "State" }));
    await user.click(await screen.findByRole("option", { name: "Disabled" }));

    await waitFor(() =>
      expect(
        mockFetch.mock.calls.some(
          ([url]) =>
            typeof url === "string" &&
            url.includes("feature=MFA") &&
            url.includes("featureEnabled=false"),
        ),
      ).toBe(true),
    );
  });

  test("bulk disable confirms with the affected count, PUTs each enabled row, and toasts", async () => {
    mockApi();
    const showSpy = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice@example.com");

    // Only Alice is currently enabled — the confirm names ONE affected row.
    await user.click(screen.getByRole("button", { name: "Disable for all matching" }));
    const modal = await screen.findByRole("dialog");
    expect(
      within(modal).getByText("Disable Email MFA for 1 user currently enabled?"),
    ).toBeInTheDocument();
    await user.click(within(modal).getByRole("button", { name: "Disable" }));

    await waitFor(() => expect(putCalls()).toHaveLength(1));
    expect(putCalls()[0]).toEqual({
      url: "/api/v1/users/1/features",
      body: { disabledFeatures: ["MFA"] },
    });
    expect(showSpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: "Feature flags saved" }),
    );
  });

  test("bulk enable with nothing to do toasts and never opens the modal", async () => {
    // Both rows... Bob is disabled — so make both enabled first: use rows where none affected.
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url.startsWith("/api/v1/users"))
        return Promise.resolve(usersPage([ROWS[0]], 1)); // only the enabled row
      return Promise.resolve(jsonResponse(404, {}));
    });
    const showSpy = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("alice@example.com");

    await user.click(screen.getByRole("button", { name: "Enable for all matching" }));

    await waitFor(() =>
      expect(showSpy).toHaveBeenCalledWith(
        expect.objectContaining({ message: "Every matching user is already in that state" }),
      ),
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  test("a bulk partial failure names the failed rows and retry re-PUTs only them", async () => {
    // Two disabled rows to enable; the first PUT fails, the second succeeds.
    const rows = [
      { ...ROWS[1], id: 2, name: "Bob Basic" },
      { ...ROWS[1], id: 3, name: "Cara Case", email: "cara@example.com" },
    ];
    let putIndex = 0;
    const putStatuses = [500, 204, 204];
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "PUT") {
        const status = putStatuses[putIndex] ?? 204;
        putIndex += 1;
        return Promise.resolve(
          status === 204 ? new Response(null, { status: 204 }) : jsonResponse(status, { status }),
        );
      }
      if (url.startsWith("/api/v1/users")) return Promise.resolve(usersPage(rows, 2));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("bob@example.com");

    await user.click(screen.getByRole("button", { name: "Enable for all matching" }));
    const modal = await screen.findByRole("dialog");
    await user.click(within(modal).getByRole("button", { name: "Enable" }));

    const failedAlert = await screen.findByText("The update failed for 1 user");
    // Bob's name appears both in the table and the failed-rows alert — scope to the alert.
    expect(failedAlert.closest(".mantine-Alert-root")?.textContent).toContain("Bob Basic");

    await user.click(screen.getByRole("button", { name: "Retry the failed users" }));
    await waitFor(() => {
      const calls = mockFetch.mock.calls.filter(([, init]) => (init as RequestInit)?.method === "PUT");
      expect(calls).toHaveLength(3);
    });
    // The retry targeted only Bob.
    const last = mockFetch.mock.calls.filter(([, init]) => (init as RequestInit)?.method === "PUT").at(-1)!;
    expect(last[0]).toBe("/api/v1/users/2/features");
  });

  test("the empty state spans every column", async () => {
    mockFetch.mockImplementation((url: string) =>
      url.startsWith("/api/v1/users")
        ? Promise.resolve(usersPage([], 0))
        : Promise.resolve(jsonResponse(404, {})),
    );
    renderPage();
    const empty = await screen.findByText("No users");
    expect(empty.closest("td")).toHaveAttribute(
      "colspan",
      String(screen.getAllByRole("columnheader").length),
    );
  });
});
