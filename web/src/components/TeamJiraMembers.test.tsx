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
    if (method === "GET" && url === "/api/v1/teams/5/jira-memberships") return Promise.resolve(jsonResponse(200, MEMBERSHIPS));
    if (method === "GET" && url.startsWith("/api/v1/jira-users")) return Promise.resolve(jsonResponse(200, DIRECTORY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
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
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/teams/5/jira-memberships")![1] as RequestInit).body as string);
    expect(body).toEqual({ accountId: "acc-3", validFrom: Date.UTC(2024, 5, 1), validTo: undefined });
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

  test("ending a membership PUTs validTo as today's start", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/5/jira-memberships/10": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<TeamJiraMembers teamId={5} />);

    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: "Operations for Alice Admin" }));
    await user.click(await screen.findByRole("menuitem", { name: /end membership/i }));

    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/5/jira-memberships/10")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/teams/5/jira-memberships/10")![1] as RequestInit).body as string);
    // "today" is the real system clock (UTC midnight) — the same helper the component calls.
    const todayUtcMidnight = new Date(new Date().toISOString().slice(0, 10)).getTime();
    expect(body).toEqual({ validFrom: Date.UTC(2024, 0, 1), validTo: todayUtcMidnight });
  });
});
