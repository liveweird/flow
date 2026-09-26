import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  changeUserPassword,
  createUser,
  deleteUser,
  getUser,
  listUsers,
  updateUser,
} from "./users";

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

  test("listUsers assembles the full query string", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 3, pageSize: 40, total: 0 }));
    await listUsers({
      page: 3,
      pageSize: 40,
      sort: "-email",
      name: "ali",
      email: "example.com",
      role: "ADMIN",
    });
    expect(lastCall()[0]).toBe(
      "/api/v1/users?page=3&pageSize=40&sort=-email&name=ali&email=example.com&role=ADMIN",
    );
  });

  test("listUsers omits absent filters", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    await listUsers({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/users?page=1&pageSize=20");
  });

  test("getUser GETs the id path", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(200, { id: 2, name: "Bob", email: "bob@example.com", roles: [] }),
    );
    await getUser(2);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/users/2");
    expect(init.method).toBeUndefined(); // GET
  });

  test("createUser POSTs the new-user body", async () => {
    const body = { name: "Bob", email: "bob@example.com", password: "p".repeat(16), roles: [] };
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 2, ...body }));
    await createUser(body);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/users");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  test("updateUser PUTs the changed fields to the id path", async () => {
    const body = { name: "Bobby", email: "bobby@example.com", roles: ["ADMIN" as const] };
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateUser(2, body);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/users/2");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual(body);
  });

  test("deleteUser DELETEs the id path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteUser(2);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/users/2");
    expect(init.method).toBe("DELETE");
  });

  test("changeUserPassword PUTs the password body to the nested path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await changeUserPassword(2, { password: "new-password-123" });
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/users/2/password");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({ password: "new-password-123" });
  });
});
