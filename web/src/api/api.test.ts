import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import {
  ApiError,
  authedFetch,
  buildQuery,
  isTimeoutError,
  jsonRequest,
  shouldRetryQuery,
  safeJson,
  timeoutSignal,
  voidRequest,
} from "./http";
import { login, logout, isMfaChallenge, verifyMfa } from "./auth";
import {
  TOKEN_KEY,
  clearSession,
  getRefreshToken,
  getRoles,
  getToken,
  getUserId,
  isAdmin,
  persistSession,
  setToken,
} from "./session";
import { getDisabledFeatures, hasFeature } from "./session";
import i18n from "../i18n";

const SESSION = {
  token: "access-1",
  expiresAt: 1,
  refreshToken: "refresh-1",
  refreshExpiresAt: 2,
  userId: 7,
  roles: ["ADMIN" as const],
  disabledFeatures: [],
  language: "en" as const,
};

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
};

describe("session", () => {
  test("persistSession applies the stored language to the UI and skips a language-less payload", async () => {
    persistSession({ ...SESSION, language: "pl" });
    await vi.waitFor(() => expect(i18n.resolvedLanguage).toBe("pl"));
    // A mid-deploy older server sends no language — the UI stays untouched.
    persistSession({ ...SESSION, language: undefined as unknown as "en" });
    expect(i18n.resolvedLanguage).toBe("pl");
    await i18n.changeLanguage("en");
  });

  test("persistSession stores the pair, roles, and userId; clearSession removes them", () => {
    persistSession(SESSION);
    expect(getToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
    expect(getRoles()).toEqual(["ADMIN"]);
    expect(getUserId()).toBe(7);
    expect(isAdmin()).toBe(true);

    clearSession();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(getRoles()).toEqual([]);
    expect(getUserId()).toBeNull();
    expect(isAdmin()).toBe(false);
  });

  test("corrupt stored roles and userId degrade to safe defaults", () => {
    localStorage.setItem("flow.auth.roles", "{not json");
    localStorage.setItem("flow.auth.userId", "NaN-ish");
    expect(getRoles()).toEqual([]);
    expect(getUserId()).toBeNull();
  });

  test("disabled features persist with the session and drive hasFeature", () => {
    persistSession({ ...SESSION, disabledFeatures: ["MFA"] });
    expect(getDisabledFeatures()).toEqual(["MFA"]);
    expect(hasFeature("MFA")).toBe(false);

    persistSession(SESSION); // empty set = full access
    expect(hasFeature("MFA")).toBe(true);

    // Corrupt storage degrades to the safe default (everything enabled).
    localStorage.setItem("flow.auth.disabledFeatures", "{not json");
    expect(getDisabledFeatures()).toEqual([]);
    expect(hasFeature("MFA")).toBe(true);
  });

  test("unknown role names are filtered out", () => {
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN", "SUPERUSER"]));
    expect(getRoles()).toEqual(["ADMIN"]);
  });

  test("setToken(null) removes the stored token", () => {
    setToken("abc");
    expect(getToken()).toBe("abc");
    setToken(null);
    expect(getToken()).toBeNull();
  });
});

describe("ApiError and helpers", () => {
  test("detail and instance surface RFC 7807 fields when present", () => {
    const err = new ApiError(409, { title: "Conflict", status: 409, detail: "Duplicate", instance: "/x" });
    expect(err.status).toBe(409);
    expect(err.detail).toBe("Duplicate");
    expect(err.instance).toBe("/x");
    expect(new ApiError(500, null).detail).toBeUndefined();
  });

  test("shouldRetryQuery never retries a 4xx and caps transient retries at two", () => {
    expect(shouldRetryQuery(0, new ApiError(403, null))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(500, null))).toBe(true);
    expect(shouldRetryQuery(1, new Error("network"))).toBe(true);
    expect(shouldRetryQuery(2, new Error("network"))).toBe(false);
  });

  test("safeJson returns null for a non-JSON body", async () => {
    expect(await safeJson(new Response("<html>", { status: 502 }))).toBeNull();
    expect(await safeJson(jsonResponse(400, { a: 1 }))).toEqual({ a: 1 });
  });

  test("buildQuery skips absent values but keeps false and 0", () => {
    expect(buildQuery({ a: "x", b: null, c: undefined, d: "", e: false, f: 0 })).toBe("a=x&e=false&f=0");
  });

  test("isTimeoutError matches only the transport deadline rejection", () => {
    expect(isTimeoutError(new DOMException("t", "TimeoutError"))).toBe(true);
    expect(isTimeoutError(new Error("t"))).toBe(false);
  });

  test("timeoutSignal is an AbortSignal where supported", () => {
    const signal = timeoutSignal();
    if (typeof AbortSignal.timeout === "function") expect(signal).toBeInstanceOf(AbortSignal);
    else expect(signal).toBeUndefined();
  });
});

describe("authedFetch", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;

  test("sends the bearer and returns the response", async () => {
    persistSession(SESSION);
    fetchMock().mockResolvedValueOnce(jsonResponse(200, { ok: true }));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(200);
    const [url, init] = fetchMock().mock.calls[0];
    expect(url).toBe("/api/v1/thing");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-1");
  });

  test("a 401 triggers one silent refresh and a retry with the new token", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { ...SESSION, token: "access-2", refreshToken: "refresh-2" }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(200);
    expect(getToken()).toBe("access-2");
    const retryInit = fetchMock().mock.calls[2][1];
    expect(new Headers(retryInit.headers).get("Authorization")).toBe("Bearer access-2");
  });

  test("a rejected refresh clears the session", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(401, {}));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(401);
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  test("a transient refresh failure keeps the session", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(503, {}));

    const res = await authedFetch("/api/v1/thing");

    expect(res.status).toBe(401);
    expect(getToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
  });

  test("jsonRequest parses the body and voidRequest throws ApiError on failure", async () => {
    persistSession(SESSION);
    fetchMock()
      .mockResolvedValueOnce(jsonResponse(200, { hello: "world" }))
      .mockResolvedValueOnce(jsonResponse(404, { title: "Not Found", status: 404, detail: "gone" }));

    await expect(jsonRequest<{ hello: string }>("/api/v1/thing")).resolves.toEqual({ hello: "world" });
    await expect(voidRequest("/api/v1/thing", { method: "PUT", body: "{}" })).rejects.toMatchObject({
      status: 404,
      detail: "gone",
    });
  });
});

describe("auth flows", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const fetchMock = () => fetch as unknown as ReturnType<typeof vi.fn>;

  test("login persists the session on 200", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    const data = await login({ email: "a@test", password: "pw" });
    expect(isMfaChallenge(data)).toBe(false);
    if (!isMfaChallenge(data)) expect(data.token).toBe("access-1");
    expect(getToken()).toBe("access-1");
    expect(isAdmin()).toBe(true);
  });

  test("a late login response cannot replace a newer session", async () => {
    const loginResponse = deferred<Response>();
    fetchMock().mockReturnValueOnce(loginResponse.promise);
    const pendingLogin = login({ email: "a@test", password: "pw" });
    persistSession({ ...SESSION, token: "access-b", refreshToken: "refresh-b" });

    loginResponse.resolve(jsonResponse(200, SESSION));
    await expect(pendingLogin).resolves.toEqual(SESSION);
    expect(getToken()).toBe("access-b");
    expect(getRefreshToken()).toBe("refresh-b");
  });

  test("an MFA challenge starts no session; verifyMfa persists the pair", async () => {
    fetchMock().mockResolvedValueOnce(
      jsonResponse(200, { mfaRequired: true, challengeId: "ch-1", expiresAt: 99 }),
    );
    const data = await login({ email: "a@test", password: "pw" });
    expect(isMfaChallenge(data)).toBe(true);
    expect(getToken()).toBeNull();

    fetchMock().mockResolvedValueOnce(jsonResponse(200, SESSION));
    const tokens = await verifyMfa("ch-1", "123456");
    expect(tokens.token).toBe("access-1");
    expect(getToken()).toBe("access-1");
    const [url, init] = fetchMock().mock.calls.at(-1)!;
    expect(url).toBe("/api/v1/login/mfa");
    expect(JSON.parse((init as { body: string }).body)).toEqual({ challengeId: "ch-1", code: "123456" });
  });

  test("a late MFA verification response cannot replace a newer session", async () => {
    const verifyResponse = deferred<Response>();
    fetchMock().mockReturnValueOnce(verifyResponse.promise);
    const pendingVerify = verifyMfa("ch-1", "123456");
    persistSession({ ...SESSION, token: "access-b", refreshToken: "refresh-b" });

    verifyResponse.resolve(jsonResponse(200, SESSION));
    await expect(pendingVerify).resolves.toEqual(SESSION);
    expect(getToken()).toBe("access-b");
    expect(getRefreshToken()).toBe("refresh-b");
  });

  test("login throws ApiError with the response body on 401", async () => {
    fetchMock().mockResolvedValueOnce(jsonResponse(401, { title: "Unauthorized", status: 401 }));
    await expect(login({ email: "a@test", password: "bad" })).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBeNull();
  });

  test("logout revokes server-side and clears the session even when the revoke fails", async () => {
    persistSession(SESSION);
    fetchMock().mockRejectedValueOnce(new Error("offline"));
    await logout();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  test("logout invalidates locally before revocation and its delayed completion cannot clear a new login", async () => {
    const revokeResponse = deferred<Response>();
    persistSession(SESSION);
    fetchMock().mockReturnValueOnce(revokeResponse.promise);

    const pendingLogout = logout();
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    const [, init] = fetchMock().mock.calls[0];
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access-1");
    expect(JSON.parse(String(init.body))).toEqual({ refreshToken: "refresh-1" });

    persistSession({ ...SESSION, token: "access-b", refreshToken: "refresh-b" });
    revokeResponse.resolve(new Response(null, { status: 204 }));
    await pendingLogout;

    expect(getToken()).toBe("access-b");
    expect(getRefreshToken()).toBe("refresh-b");
  });

  test("logout without a stored token is a no-op fetch-wise", async () => {
    localStorage.setItem(TOKEN_KEY, "");
    clearSession();
    await logout();
    expect(fetchMock()).not.toHaveBeenCalled();
  });
});
