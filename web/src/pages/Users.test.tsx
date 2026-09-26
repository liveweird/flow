import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import Users from "./Users";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";
const USER_ID_KEY = "flow.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const SEED_USERS = [
  { id: 1, name: "Alice Admin", email: "alice@example.com", roles: ["ADMIN"] },
  { id: 2, name: "Bob Basic", email: "bob@example.com", roles: [] },
];

function usersPage(items: typeof SEED_USERS, total = items.length) {
  return jsonResponse(200, { items, page: 1, pageSize: 20, total });
}

function setupMocks(mockFetch: FetchMock, byUrl: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) =>
    url.startsWith("/api/v1/users")
      ? Promise.resolve(byUrl(url))
      : Promise.resolve(jsonResponse(404, {})),
  );
}

function PathProbe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname}</div>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/users" element={<Users />} />
      <Route path="/" element={<PathProbe />} />
    </Routes>,
    { route: "/users" },
  );
}

describe("Users page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    localStorage.setItem(USER_ID_KEY, "1");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the empty state spanning every column when there are no users", async () => {
    setupMocks(mockFetch, () => usersPage([], 0));
    renderPage();
    const empty = await screen.findByText("No users");
    // The empty-state cell spans every column — pins the page's columnCount literal.
    expect(empty.closest("td")).toHaveAttribute(
      "colspan",
      String(screen.getAllByRole("columnheader").length),
    );
  });

  test("renders rows with the admin badge and the own-row You marker", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    renderPage();

    expect(await screen.findByText("Alice Admin")).toBeInTheDocument();
    expect(screen.getByText("Bob Basic")).toBeInTheDocument();
    expect(screen.getByText("Admin")).toBeInTheDocument();
    expect(screen.getByText("You")).toBeInTheDocument();
  });

  test("the own row's menu offers Edit but neither Delete nor Reset password", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    expect(await screen.findByRole("menuitem", { name: "Edit Alice Admin" })).toHaveAttribute(
      "href",
      "/users/1/edit",
    );
    expect(screen.getByRole("menuitem", { name: "Feature flags for Alice Admin" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete Alice Admin" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("menuitem", { name: "Reset password for Alice Admin" }),
    ).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    // The other row has all four.
    await user.click(screen.getByRole("button", { name: "Operations for Bob Basic" }));
    expect(await screen.findByRole("menuitem", { name: "Delete Bob Basic" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Reset password for Bob Basic" })).toBeInTheDocument();
  });

  test("the role filter refetches with role=", async () => {
    setupMocks(mockFetch, () => usersPage(SEED_USERS));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    fireEvent.click(screen.getByLabelText("Role", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Admin" }));

    await waitFor(() => {
      const called = mockFetch.mock.calls.some(
        ([url]) => typeof url === "string" && url.includes("role=ADMIN"),
      );
      expect(called).toBe(true);
    });
  });

  test("deleting a user confirms, DELETEs, refetches, and toasts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    let listCount = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && url === "/api/v1/users/2") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/users?")) {
        listCount++;
        return Promise.resolve(usersPage(listCount === 1 ? SEED_USERS : [SEED_USERS[0]]));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Operations for Bob Basic" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Bob Basic" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(screen.queryByText("Bob Basic")).not.toBeInTheDocument());
    expect(listCount).toBeGreaterThanOrEqual(2);
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ message: "User deleted", color: "teal" }),
    );
    toast.mockRestore();
  });

  test("deleting while the first filtered request is pending cannot restore its stale row", async () => {
    const filtered = deferred<Response>();
    let filteredReads = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && url === "/api/v1/users/2") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "GET" && url.startsWith("/api/v1/users?")) {
        const name = new URL(url, "http://test").searchParams.get("name");
        if (name === "Bob Basic") {
          filteredReads++;
          if (filteredReads === 1) return filtered.promise;
          return Promise.resolve(usersPage([SEED_USERS[0]]));
        }
        return Promise.resolve(usersPage(SEED_USERS));
      }
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Bob Basic");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Name"), "Bob Basic");
    await waitFor(() => expect(filteredReads).toBe(1));

    await user.click(screen.getByRole("button", { name: "Operations for Bob Basic" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Bob Basic" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    filtered.resolve(usersPage(SEED_USERS));
    await waitFor(() => expect(filteredReads).toBe(2));
    await waitFor(() => expect(screen.queryByText("Bob Basic")).not.toBeInTheDocument());
  });

  test("a 409 on delete surfaces the last-administrator message in the modal", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "DELETE") {
        return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 }));
      }
      if (url.startsWith("/api/v1/users?")) return Promise.resolve(usersPage(SEED_USERS));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Operations for Bob Basic" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Bob Basic" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^delete$/i }));

    expect(await screen.findByText(/last administrator cannot be removed/i)).toBeInTheDocument();
  });

  test("resetting a password confirms, PUTs a generated one, and reveals it once", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "PUT" && url === "/api/v1/users/2/password") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.startsWith("/api/v1/users?")) return Promise.resolve(usersPage(SEED_USERS));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Operations for Bob Basic" }));
    await user.click(await screen.findByRole("menuitem", { name: "Reset password for Bob Basic" }));
    let dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^reset$/i }));

    // The reveal modal shows the masked generated password; the PUT body carried it.
    expect(await screen.findByText("Password reset")).toBeInTheDocument();
    const putCall = mockFetch.mock.calls.find(
      ([url, init]) =>
        (init as RequestInit | undefined)?.method === "PUT" && url === "/api/v1/users/2/password",
    );
    const body = JSON.parse((putCall![1] as RequestInit).body as string) as { password: string };
    expect(body.password).toMatch(/^[A-Za-z0-9_-]{16}$/);
    expect(body).not.toHaveProperty("currentPassword");
    dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("*".repeat(16))).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: /show password/i }));
    expect(within(dialog).getByText(body.password)).toBeInTheDocument();

    // Closing discards the plaintext for good.
    await user.click(within(dialog).getByRole("button", { name: /^close$/i }));
    await waitFor(() => expect(screen.queryByText(body.password)).not.toBeInTheDocument());
  });

  test("shows the load-failure alert", async () => {
    setupMocks(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByText("Failed to load users")).toBeInTheDocument();
  });
});
