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
  members: [
    { userId: 10, name: "Ann Member", email: "ann@example.com", deleted: false },
    { userId: 11, name: "Gone Person", email: "gone@example.com", deleted: true },
  ],
  createdAt: 1,
  updatedAt: 2,
};
const USERS = {
  items: [
    { id: 10, name: "Ann Member", email: "ann@example.com", roles: [], disabledFeatures: [], language: "en" },
    { id: 12, name: "Cara New", email: "cara@example.com", roles: [], disabledFeatures: [], language: "en" },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}, team: unknown = TEAM) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url === "/api/v1/teams/1") return Promise.resolve(jsonResponse(200, team));
    if (method === "GET" && url.startsWith("/api/v1/users?")) return Promise.resolve(jsonResponse(200, USERS));
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

  test("a regular user sees the roster with the deleted marker and no admin controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Payments" })).toBeInTheDocument();
    expect(screen.getByText("Money movers")).toBeInTheDocument();
    expect(screen.getByText("Ann Member")).toBeInTheDocument();
    expect(screen.getByText("deleted")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.startsWith("/api/v1/users"))).toBe(false);
  });

  test("an admin adds a member from the picker (current members excluded) and the roster refreshes", async () => {
    serve(mockFetch, { "POST /api/v1/teams/1/members/12": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    const picker = await screen.findByRole("combobox", { name: "Add a member" });
    await user.click(picker);
    // Ann is already a member — only Cara is offered.
    expect(await screen.findByRole("option", { name: "Cara New (cara@example.com)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Ann Member/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Cara New (cara@example.com)" }));
    await user.click(screen.getByRole("button", { name: /^add$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/teams/1/members/12")).toBeDefined());
  });

  test("a 409 on add renders the already-a-member message", async () => {
    serve(mockFetch, { "POST /api/v1/teams/1/members/12": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("combobox", { name: "Add a member" }));
    await user.click(await screen.findByRole("option", { name: "Cara New (cara@example.com)" }));
    await user.click(screen.getByRole("button", { name: /^add$/i }));
    expect(await screen.findByText("This user is already a member.")).toBeInTheDocument();
  });

  test("an admin removes a member after confirming", async () => {
    serve(mockFetch, { "DELETE /api/v1/teams/1/members/10": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for Ann Member" }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove Ann Member from the team" }));
    expect(await screen.findByText(/Ann Member will no longer be a member of Payments/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/teams/1/members/10")).toBeDefined());
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
    expect(body).toEqual({ name: "Payments EU", description: "Money movers" });
  });

  test("an empty roster shows the empty state", async () => {
    serve(mockFetch, {}, { ...TEAM, members: [] });
    renderPage();
    expect(await screen.findByText("No members yet")).toBeInTheDocument();
  });

  test("a missing team shows the not-found message with a way back", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This team does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to teams" })).toHaveAttribute("href", "/teams");
  });
});
