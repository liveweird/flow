import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { consumeSignedOut } from "../auth";
import { jsonResponse } from "../test/http";
import { authedFetch } from "./http";
import type { components } from "./schema";
import { getRefreshToken, getToken, persistSession } from "./session";

const session = (token: string, refreshToken: string): components["schemas"]["LoginResponse"] => ({
  token,
  expiresAt: 1,
  refreshToken,
  refreshExpiresAt: 2,
  userId: 1,
  roles: [],
  disabledFeatures: [],
  language: "en",
});

const bearer = (init?: RequestInit) => new Headers(init?.headers).get("Authorization");
const isRefresh = (url: unknown) => String(url).endsWith("/api/v1/refresh");
const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
};

/** The session-continuity core: one silent refresh, one retry, and only a DEFINITIVE rejection ends the session. */
describe("authedFetch", () => {
  const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    persistSession(session("t1", "r1"));
    consumeSignedOut();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a 401 triggers ONE refresh shared by concurrent callers, and each request retries with the new token", async () => {
    const refreshResponse = deferred<Response>();
    fetchMock.mockImplementation((url, init) => {
      if (isRefresh(url)) return refreshResponse.promise;
      return Promise.resolve(bearer(init) === "Bearer t2" ? jsonResponse(200, { ok: true }) : jsonResponse(401, {}));
    });
    const usersRequest = authedFetch("/api/v1/users");
    const teamsRequest = authedFetch("/api/v1/teams");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => !isRefresh(url))).toHaveLength(2));
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1));
    refreshResponse.resolve(jsonResponse(200, session("t2", "r2")));
    const [users, teams] = await Promise.all([usersRequest, teamsRequest]);
    expect(users.status).toBe(200);
    expect(teams.status).toBe(200);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1);
    expect(getToken()).toBe("t2");
    expect(getRefreshToken()).toBe("r2");
  });

  test("a staggered same-session 401 retries with credentials another request already refreshed", async () => {
    const refreshResponse = deferred<Response>();
    const lateUnauthorized = deferred<Response>();
    fetchMock.mockImplementation((url, init) => {
      if (isRefresh(url)) return refreshResponse.promise;
      if (bearer(init) === "Bearer t2") return Promise.resolve(jsonResponse(200, {}));
      if (String(url).endsWith("/api/v1/teams")) return lateUnauthorized.promise;
      return Promise.resolve(jsonResponse(401, {}));
    });

    const usersRequest = authedFetch("/api/v1/users");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1));
    const teamsRequest = authedFetch("/api/v1/teams");
    await vi.waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/api/v1/teams"))).toBe(true));

    refreshResponse.resolve(jsonResponse(200, session("t2", "r2")));
    expect((await usersRequest).status).toBe(200);
    lateUnauthorized.resolve(jsonResponse(401, {}));
    expect((await teamsRequest).status).toBe(200);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1);
  });

  test("a late 401 from session A cannot start a refresh after session B replaces it", async () => {
    const originalResponse = deferred<Response>();
    fetchMock.mockImplementation((url) =>
      isRefresh(url) ? Promise.resolve(jsonResponse(500, {})) : originalResponse.promise,
    );
    const request = authedFetch("/api/v1/users");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    // Simulate another tab logging in: its storage writes do not advance this tab's in-memory
    // generation, so the persisted logical-session identity is the guard under test.
    localStorage.setItem("flow.auth.token", "tb");
    localStorage.setItem("flow.auth.refreshToken", "rb");
    localStorage.setItem("flow.auth.sessionIdentity", "external-b");
    originalResponse.resolve(jsonResponse(401, {}));

    expect((await request).status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(0);
    expect(getToken()).toBe("tb");
    expect(getRefreshToken()).toBe("rb");
  });

  test("session A's delayed successful refresh cannot publish or retry after session B logs in", async () => {
    const refreshResponse = deferred<Response>();
    fetchMock.mockImplementation((url) =>
      isRefresh(url) ? refreshResponse.promise : Promise.resolve(jsonResponse(401, {})),
    );
    const request = authedFetch("/api/v1/users");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1));

    persistSession(session("tb", "rb"));
    refreshResponse.resolve(jsonResponse(200, session("ta2", "ra2")));

    expect((await request).status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => !isRefresh(url))).toHaveLength(1);
    expect(getToken()).toBe("tb");
    expect(getRefreshToken()).toBe("rb");
  });

  test("session A's delayed refresh rejection cannot clear session B", async () => {
    const refreshResponse = deferred<Response>();
    fetchMock.mockImplementation((url) =>
      isRefresh(url) ? refreshResponse.promise : Promise.resolve(jsonResponse(401, {})),
    );
    const request = authedFetch("/api/v1/users");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1));

    persistSession(session("tb", "rb"));
    refreshResponse.resolve(jsonResponse(401, {}));

    expect((await request).status).toBe(401);
    expect(getToken()).toBe("tb");
    expect(getRefreshToken()).toBe("rb");
    expect(consumeSignedOut()).toBe(false);
  });

  test("session B starts its own refresh while session A's refresh remains pending", async () => {
    const refreshA = deferred<Response>();
    const refreshB = deferred<Response>();
    fetchMock.mockImplementation((url, init) => {
      if (isRefresh(url)) {
        const { refreshToken } = JSON.parse(String(init?.body)) as { refreshToken: string };
        return refreshToken === "r1" ? refreshA.promise : refreshB.promise;
      }
      return Promise.resolve(bearer(init) === "Bearer tb2" ? jsonResponse(200, {}) : jsonResponse(401, {}));
    });

    const requestA = authedFetch("/api/v1/users");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1));
    persistSession(session("tb", "rb"));
    const requestB = authedFetch("/api/v1/teams");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(2));

    refreshA.resolve(jsonResponse(200, session("ta2", "ra2")));
    expect((await requestA).status).toBe(401);
    const secondRequestB = authedFetch("/api/v1/domains");
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(2));

    refreshB.resolve(jsonResponse(200, session("tb2", "rb2")));
    expect((await requestB).status).toBe(200);
    expect((await secondRequestB).status).toBe(200);
    expect(getToken()).toBe("tb2");
    expect(getRefreshToken()).toBe("rb2");
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(2);
  });

  test("a rejected refresh ends the session and hands the original 401 back", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(consumeSignedOut()).toBe(true);
  });

  test("an unreachable refresh keeps the session — the 401 is the caller's error, nobody is signed out", async () => {
    fetchMock.mockImplementation((url) =>
      isRefresh(url) ? Promise.reject(new TypeError("network down")) : Promise.resolve(jsonResponse(401, {})),
    );
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(getToken()).toBe("t1");
    expect(getRefreshToken()).toBe("r1");
    expect(consumeSignedOut()).toBe(false);
  });

  test("a 5xx or a malformed refresh answer is transient too", async () => {
    fetchMock.mockImplementation((url) => Promise.resolve(isRefresh(url) ? jsonResponse(503, {}) : jsonResponse(401, {})));
    expect((await authedFetch("/api/v1/users")).status).toBe(401);
    fetchMock.mockImplementation((url) =>
      Promise.resolve(isRefresh(url) ? jsonResponse(200, { unexpected: true }) : jsonResponse(401, {})),
    );
    expect((await authedFetch("/api/v1/users")).status).toBe(401);
    expect(getToken()).toBe("t1");
    expect(consumeSignedOut()).toBe(false);
  });

  test("after a successful refresh the request is retried once, never twice", async () => {
    fetchMock.mockImplementation((url) => Promise.resolve(isRefresh(url) ? jsonResponse(200, session("t2", "r2")) : jsonResponse(401, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/api/v1/users"))).toHaveLength(2);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1);
    expect(getToken()).toBe("t2");
  });

  test("a request without a stored token goes out unauthenticated and a 401 needs no refresh call", async () => {
    localStorage.clear();
    fetchMock.mockImplementation((_url, init) => Promise.resolve(bearer(init) === null ? jsonResponse(401, {}) : jsonResponse(500, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(0);
  });
});
