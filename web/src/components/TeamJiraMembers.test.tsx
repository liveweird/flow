import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import TeamJiraMembers from "./TeamJiraMembers";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const MEMBERSHIPS = {
  items: [
    { id: 10, accountId: "acc-1", validFrom: Date.UTC(2024, 0, 1), validTo: null, createdAt: 1, updatedAt: 1 },
    { id: 11, accountId: "acc-2", validFrom: Date.UTC(2023, 0, 1), validTo: Date.UTC(2023, 6, 1), createdAt: 1, updatedAt: 1 },
  ],
};

const DIRECTORY = {
  items: [
    { accountId: "acc-1", displayName: "Alice Admin" },
    { accountId: "acc-2", displayName: "Bob Basic" },
    { accountId: "acc-3", displayName: "Cara New" },
  ],
  page: 1,
  pageSize: 100,
  total: 3,
};

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
    if (method === "GET" && url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
    if (method === "GET" && url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, DIRECTORY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

function unitDirectoryCalls(mockFetch: FetchMock): number {
  return mockFetch.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/jira-users") && String(u).includes("scope=UNIT")).length;
}

describe("TeamJiraMembers", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a regular user sees the table read-only — no add button, no operations menu", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    expect(await screen.findByText("Alice Admin")).toBeInTheDocument();
    expect(screen.getByText("Bob Basic")).toBeInTheDocument();
    expect(screen.getByText("current")).toBeInTheDocument();
    expect(screen.getByText("2024-01-01")).toBeInTheDocument();
    expect(screen.getByText("2023-07-01")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add jira member/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
  });

  test("names beyond the first directory page are resolved by paging through the whole directory", async () => {
    const firstPage = Array.from({ length: 100 }, (_, i) => ({ accountId: `bulk-${i}`, displayName: `Bulk ${i}` }));
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/teams/5/jira-memberships") {
        return Promise.resolve(
          jsonResponse(200, { items: [{ id: 10, accountId: "late-acc", validFrom: Date.UTC(2024, 0, 1), validTo: null, createdAt: 1, updatedAt: 1 }] }),
        );
      }
      if (url.startsWith("/api/v1/jira-users")) {
        const params = new URL(url, "http://localhost").searchParams;
        if (params.get("page") === "2") {
          return Promise.resolve(jsonResponse(200, { items: [{ accountId: "late-acc", displayName: "Zed Late" }], page: 2, pageSize: 100, total: 101 }));
        }
        return Promise.resolve(jsonResponse(200, { items: firstPage, page: 1, pageSize: 100, total: 101 }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    expect(await screen.findByText("Zed Late")).toBeInTheDocument();
    const pages = mockFetch.mock.calls.map(([u]) => new URL(String(u), "http://localhost")).filter((u) => u.pathname === "/api/v1/jira-users");
    expect(pages.map((u) => u.searchParams.get("page"))).toEqual(["1", "2"]);
    expect(pages.every((u) => u.searchParams.get("scope") === "UNIT" && u.searchParams.get("pageSize") === "100")).toBe(true);
  });

  test("an admin adds a Jira member — current members excluded from the picker", async () => {
    serve(mockFetch, { "POST /api/v1/teams/5/jira-memberships": { status: 201, body: { id: 12, accountId: "acc-3", validFrom: 1, validTo: null, createdAt: 1, updatedAt: 1 } } });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: /add jira member/i }));
    const modal = await screen.findByRole("dialog");
    await user.click(modal.querySelector('[role="combobox"]') as HTMLElement);
    expect(await screen.findByRole("option", { name: /Cara New/ })).toBeInTheDocument();
    // Alice is currently on the team — excluded from the picker.
    expect(screen.queryByRole("option", { name: /Alice Admin/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: /Cara New/ }));
    await user.type(screen.getByLabelText(/valid from/i), "2024-06-01");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/teams/5/jira-memberships")).toBeDefined());
    // Adding also refreshes the UNIT directory the names are read from (a new SITE person is named at once).
    await waitFor(() => expect(unitDirectoryCalls(mockFetch)).toBe(2));
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/teams/5/jira-memberships")![1] as RequestInit).body as string);
    // The picked day is a day in the configured zone: Warsaw midnight (CEST, +2) of 2024-06-01.
    expect(body).toEqual({ accountId: "acc-3", validFrom: Date.UTC(2024, 4, 31, 22), validTo: undefined });
  });

  test("an overlap 409 renders inline in the add modal, never a toast", async () => {
    serve(mockFetch, {
      "POST /api/v1/teams/5/jira-memberships": {
        status: 409,
        body: { title: "Conflict", status: 409, detail: "Overlapping team membership for this account", instance: "/x", type: "about:blank" },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: /add jira member/i }));
    const modal = await screen.findByRole("dialog");
    await user.click(modal.querySelector('[role="combobox"]') as HTMLElement);
    await user.click(await screen.findByRole("option", { name: /Cara New/ }));
    await user.type(screen.getByLabelText(/valid from/i), "2024-06-01");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    expect(
      await screen.findByText(/already has a membership covering that period/i),
    ).toBeInTheDocument();
  });

  test("ending a membership PUTs validTo as the start of today in the configured metrics zone", async () => {
    // Fake only Date so timers/promises keep running: 22:30Z on the 15th is already the 16th in Warsaw.
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-06-15T22:30:00Z"));
    serve(mockFetch, { "PUT /api/v1/teams/5/jira-memberships/10": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    const item = await screen.findByRole("menuitem", { name: /end membership/i });
    await waitFor(() => expect(item).toBeEnabled());
    await user.click(item);

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/5/jira-memberships/10")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/teams/5/jira-memberships/10")![1] as RequestInit).body as string);
    // Warsaw midnight (CEST, +2) on the 16th — not the UTC midnight of the 15th or the 16th.
    expect(body).toEqual({ validFrom: Date.UTC(2024, 0, 1), validTo: Date.UTC(2026, 5, 15, 22) });
    // Ending only refreshes the memberships — the directory walk is not re-run (nor waited for).
    await waitFor(() => expect(mockFetch.mock.calls.filter(([u]) => u === "/api/v1/teams/5/jira-memberships")).toHaveLength(2));
    expect(unitDirectoryCalls(mockFetch)).toBe(1);
  });

  test("End membership stays disabled until the metrics zone is known, and nothing is sent", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/reports/filters") return new Promise(() => {});
      if (method === "GET" && url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
      if (method === "GET" && url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, DIRECTORY));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    const item = await screen.findByRole("menuitem", { name: /end membership/i });
    expect(item).toBeDisabled();
    await user.click(item);
    expect(findCall(mockFetch, "PUT", "/api/v1/teams/5/jira-memberships/10")).toBeUndefined();
  });

  test("a failed zone load says why End is disabled and Retry recovers", async () => {
    let filtersFail = true;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/reports/filters") {
        return Promise.resolve(filtersFail ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
      }
      if (method === "GET" && url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
      if (method === "GET" && url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, DIRECTORY));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    const alert = await screen.findByText(/time zone could not be loaded/);
    expect(alert).toHaveTextContent("Load failed (500)");
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    const item = await screen.findByRole("menuitem", { name: /end membership/i });
    expect(item).toBeDisabled();
    // The disabled item points at the explanation.
    expect(document.getElementById(item.getAttribute("aria-describedby") ?? "")).toHaveTextContent(/time zone could not be loaded/);
    await user.keyboard("{Escape}");

    // The same hint sits inside the add form, whose Create stays disabled.
    await user.click(screen.getByRole("button", { name: /add jira member/i }));
    const modal = await screen.findByRole("dialog");
    expect(modal).toHaveTextContent(/time zone could not be loaded/);
    expect(await screen.findByRole("button", { name: "Create" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    filtersFail = false;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.queryByText(/time zone could not be loaded/)).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    expect(await screen.findByRole("menuitem", { name: /end membership/i })).toBeEnabled();
  });

  test("membership dates render as days in the configured zone: a Warsaw end on Nov 1 shows 2026-11-01", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
      if (url === "/api/v1/teams/5/jira-memberships") {
        return Promise.resolve(
          jsonResponse(200, {
            items: [
              // Warsaw midnights: 2024-06-01 00:00 CEST and 2026-11-01 00:00 CET.
              { id: 10, accountId: "acc-1", validFrom: Date.UTC(2024, 4, 31, 22), validTo: Date.UTC(2026, 9, 31, 23), createdAt: 1, updatedAt: 1 },
            ],
          }),
        );
      }
      if (url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, DIRECTORY));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    expect(await screen.findByText("2026-11-01")).toBeInTheDocument();
    expect(screen.getByText("2024-06-01")).toBeInTheDocument();
  });

  test("a failed name directory is shown inline instead of silently falling back to account ids", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
      if (url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
      if (url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(502, { title: "Bad Gateway", status: 502 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    expect(await screen.findByText("Load failed (502)")).toBeInTheDocument();
    expect(screen.getAllByText("acc-1").length).toBeGreaterThan(0);
  });

  test("a directory longer than the page cap says so — the rest of the names are unresolved", async () => {
    const fullPage = Array.from({ length: 100 }, (_, i) => ({ accountId: `bulk-${i}`, displayName: `Bulk ${i}` }));
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
      if (url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
      if (url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, { items: fullPage, page: 1, pageSize: 100, total: 10000 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    expect(await screen.findByText(/Only the first 5000 of 10000 people could be loaded/)).toBeInTheDocument();
  });

  test("unmounting aborts the directory walk", async () => {
    let walkSignal: AbortSignal | undefined;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, { timeZone: "Europe/Warsaw", teams: [] }));
      if (url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
      if (url.startsWith("/api/v1/jira-users")) {
        walkSignal = init?.signal ?? undefined;
        return new Promise(() => {});
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const { unmount } = renderWithProviders(<TeamJiraMembers teamId={5} />);

    await waitFor(() => expect(walkSignal).toBeDefined());
    expect(walkSignal!.aborted).toBe(false);
    unmount();
    await waitFor(() => expect(walkSignal!.aborted).toBe(true));
  });
});
