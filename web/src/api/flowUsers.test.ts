import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  changeFlowUserPassword,
  createFlowUser,
  deleteFlowUser,
  getFlowUser,
  listFlowUsers,
  updateFlowUser,
} from "./flowUsers";

type FetchMock = ReturnType<typeof vi.fn>;

describe("users API wrappers", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];

  test("listFlowUsers assembles the full query string", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 3, pageSize: 40, total: 0 }));
    await listFlowUsers({
      page: 3,
      pageSize: 40,
      sort: "-email",
      name: "ali",
      email: "example.com",
      role: "ADMIN",
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/flow-users?page=3&pageSize=40&sort=-email&name=ali&email=example.com&role=ADMIN",
    );
  });

  test("listFlowUsers omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listFlowUsers({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/flow-users?page=1&pageSize=20");
  });

  test("getFlowUser GETs the id path", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(200, { id: 2, name: "Bob", email: "bob@example.com", roles: [] }),
    );
    await getFlowUser(2);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/flow-users/2");
    expect(init.method).toBeUndefined(); // GET
  });

  test("createFlowUser POSTs the new-user body", async () => {
    const body = { name: "Bob", email: "bob@example.com", password: "p".repeat(16), roles: [] };
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 2, ...body }));
    await createFlowUser(body);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/flow-users");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  test("updateFlowUser PUTs the changed fields to the id path", async () => {
    const body = { name: "Bobby", email: "bobby@example.com", roles: ["ADMIN" as const] };
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateFlowUser(2, body);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/flow-users/2");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  test("deleteFlowUser DELETEs the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteFlowUser(2);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/flow-users/2");
    expect(init.method).toBe("DELETE");
  });

  test("changeFlowUserPassword PUTs the password body to the nested path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await changeFlowUserPassword(2, { password: "new-password-123" });
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/flow-users/2/password");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ password: "new-password-123" });
  });
});
