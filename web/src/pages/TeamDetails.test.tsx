import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import TeamDetails from "./TeamDetails";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const TEAM = {
  id: 1,
  name: "Payments",
  description: "Money movers",
  managerUserId: 12,
  managerName: "Cara New",
  createdAt: 1,
  updatedAt: 2,
};
function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}, team: unknown = TEAM) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url === "/api/v1/teams/1") return Promise.resolve(jsonResponse(200, team));
    if (method === "GET" && url.startsWith("/api/v1/teams/1/jira-memberships")) return Promise.resolve(jsonResponse(200, { items: [] }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

function renderPage(route = "/teams/1") {
  return renderWithProviders(
    <Routes>
      <Route path="/teams/:id" element={<TeamDetails />} />
    </Routes>,
    { route },
  );
}

describe("TeamDetails page", () => {
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

  test("a regular user sees the team, its manager and its Jira members, and no admin controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Payments" })).toBeInTheDocument();
    expect(screen.getByText("Money movers")).toBeInTheDocument();
    expect(screen.getByText("Manager: Cara New")).toBeInTheDocument();
    expect(await screen.findByText("Jira members")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.startsWith("/api/v1/teams/1/members"))).toBe(false);
  });

  test("the Edit action opens the prefilled modal and PUTs", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/1": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Name")).toHaveValue("Payments");
    await user.type(within(modal).getByLabelText("Name"), " EU");
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/1")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/teams/1")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Payments EU", description: "Money movers", managerUserId: 12 });
  });

  test("a team without a manager says so", async () => {
    serve(mockFetch, {}, { ...TEAM, managerUserId: null, managerName: null });
    renderPage();
    expect(await screen.findByText("No manager")).toBeInTheDocument();
    expect(screen.queryByText(/^Manager:/)).not.toBeInTheDocument();
  });

  test("a missing team shows the not-found message with a way back", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This team does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to teams" })).toHaveAttribute("href", "/teams");
  });
});
