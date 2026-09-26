import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import Teams from "./Teams";
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

const PAGE = {
  items: [
    { id: 1, name: "Payments", description: "Money movers", memberCount: 2, createdAt: 1, updatedAt: 2 },
    { id: 2, name: "Identity", description: null, memberCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};
const DETAIL = { id: 1, name: "Payments", description: "Money movers", members: [], createdAt: 1, updatedAt: 2 };

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (mutations[key]) {
      const m = mutations[key];
      return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    }
    if (method === "GET" && url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(200, PAGE));
    if (method === "GET" && url === "/api/v1/teams/1") return Promise.resolve(jsonResponse(200, DETAIL));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/teams" element={<Teams />} />
      <Route path="/teams/:id" element={<Probe />} />
    </Routes>,
    { route: "/teams" },
  );
}

describe("Teams page", () => {
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

  test("a regular user gets the read-only list with the team names as links", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("link", { name: "Open team Payments" })).toHaveAttribute("href", "/teams/1");
    expect(screen.getByText("Money movers")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new team/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
  });

  test("the empty state spans every column", async () => {
    serve(mockFetch);
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 })));
    renderPage();
    const empty = await screen.findByText("No teams yet");
    expect(empty.closest("td")).toHaveAttribute("colspan", String(screen.getAllByRole("columnheader").length));
  });

  test("an admin creates a team through the modal and lands on its roster page", async () => {
    serve(mockFetch, { "POST /api/v1/teams": { status: 201, body: { ...DETAIL, id: 9, name: "Ledger" } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /new team/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "Ledger");
    await user.type(within(modal).getByLabelText("Description"), " keeps the books ");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/teams")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/teams")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Ledger", description: "keeps the books" });
    expect(await screen.findByText("at /teams/9")).toBeInTheDocument();
  });

  test("modal validation blocks an empty name", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /new team/i }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("Name must be 1–100 characters")).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/teams")).toBeUndefined();
  });

  test("an admin edits a team — the modal prefills from the detail and PUTs", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/1": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for Payments" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Payments" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Name")).toHaveValue("Payments");
    await user.clear(within(modal).getByLabelText("Description"));
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/1")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/teams/1")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Payments", description: null });
  });

  test("a 409 on save marks the name field", async () => {
    serve(mockFetch, { "POST /api/v1/teams": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /new team/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "Payments");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("A team with this name already exists")).toBeInTheDocument();
    expect(within(modal).getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
  });

  test("an admin deletes a team after confirming; a 409 names the conflict", async () => {
    serve(mockFetch, { "DELETE /api/v1/teams/2": { status: 204 }, "DELETE /api/v1/teams/1": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for Identity" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Identity" }));
    expect(await screen.findByText(/"Identity" will be deleted/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/teams/2")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Operations for Payments" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Payments" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText(/still referenced elsewhere/)).toBeInTheDocument();
  });

  test("deleting while the first filtered request is pending cannot restore its stale row", async () => {
    const filtered = deferred<Response>();
    let filteredReads = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "DELETE" && url === "/api/v1/teams/2") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (method === "GET" && url.startsWith("/api/v1/teams?")) {
        const name = new URL(url, "http://test").searchParams.get("name");
        if (name === "Identity") {
          filteredReads++;
          if (filteredReads === 1) return filtered.promise;
          return Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 }));
        }
        return Promise.resolve(jsonResponse(200, PAGE));
      }
      return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Identity");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Name"), "Identity");
    await waitFor(() => expect(filteredReads).toBe(1));

    await user.click(screen.getByRole("button", { name: "Operations for Identity" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Identity" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    filtered.resolve(jsonResponse(200, PAGE));
    await waitFor(() => expect(filteredReads).toBe(2));
    await waitFor(() => expect(screen.queryByText("Identity")).not.toBeInTheDocument());
  });

  test("the name filter refetches with name=", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Money movers");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Name"), "pay");
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("name=pay"))).toBe(true));
  });

  test("shows the load-failure alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "boom", status: 500 })));
    renderPage();
    expect(await screen.findByText("Could not load the teams")).toBeInTheDocument();
  });
});
