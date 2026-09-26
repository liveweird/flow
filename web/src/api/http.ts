// Transport — authedFetch with the single-flighted silent refresh, ApiError, and the
// shared JSON helpers (session state lives in ./session).

import { flagSignedOut, notifyAuthChange } from "../auth";
import type { components } from "./schema";
import {
  clearSession,
  getSessionSnapshot,
  isSameSession,
  isSameSessionIdentity,
  isSessionCurrent,
  persistRefreshedSession,
  type SessionSnapshot,
} from "./session";

export const API_BASE = import.meta.env.VITE_API_BASE ?? "";

type LoginSuccess = components["schemas"]["LoginResponse"];

// Every request gets a deadline (v2.22.0) — without one a hung response leaves the promise
// pending forever, with buttons stuck in their loading state and no error ever shown.
const REQUEST_TIMEOUT_MS = 30_000;

/**
 * The default per-request deadline: rejects the fetch with a "TimeoutError" DOMException
 * after 30 s. Callers may override by passing their own `signal`. Feature-detected because
 * happy-dom (tests) lacks AbortSignal.timeout.
 */
export function timeoutSignal(): AbortSignal | undefined {
  return typeof AbortSignal.timeout === "function"
    ? AbortSignal.timeout(REQUEST_TIMEOUT_MS)
    : undefined;
}

/** True for the transport deadline's rejection — the server did not answer in time. */
export function isTimeoutError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "TimeoutError";
}

// The refresh outcome distinguishes a DEFINITIVE rejection (the session is over) from a
// TRANSIENT failure (network blip, timeout, 5xx, the refresh rate bucket's 429, a malformed
// body) — only the former may destroy the stored session: the refresh token is still valid
// through a transient failure, so signing the user out would discard a working session (and
// any in-progress form) over a hiccup.
type RefreshOutcome =
  | { kind: "ok"; session: SessionSnapshot }
  | { kind: "rejected" }
  | { kind: "unavailable" }
  | { kind: "stale" };

// Exchange the stored refresh token for a fresh access + refresh pair. Single-flighted:
// concurrent callers (e.g. several requests that all 401 at once) share one in-flight
// /refresh call.
type RefreshFlight = { session: SessionSnapshot; promise: Promise<RefreshOutcome> };
let refreshInflight: RefreshFlight[] = [];

function refresh(session: SessionSnapshot): Promise<RefreshOutcome> {
  const existing = refreshInflight.find((flight) => isSameSession(flight.session, session));
  if (existing) return existing.promise;

  const flight: RefreshFlight = {
    session,
    promise: doRefresh(session).finally(() => {
      refreshInflight = refreshInflight.filter((candidate) => candidate !== flight);
    }),
  };
  refreshInflight.push(flight);
  return flight.promise;
}

async function doRefresh(session: SessionSnapshot): Promise<RefreshOutcome> {
  const refreshToken = session.refreshToken;
  if (!refreshToken) return { kind: "rejected" };
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/v1/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      signal: timeoutSignal(),
    });
  } catch {
    return { kind: "unavailable" };
  }
  if (res.status === 401 || res.status === 403) return { kind: "rejected" };
  if (!res.ok) return { kind: "unavailable" };
  let data: LoginSuccess;
  try {
    data = (await res.json()) as LoginSuccess;
  } catch {
    return { kind: "unavailable" };
  }
  if (typeof data.token !== "string" || typeof data.refreshToken !== "string") {
    return { kind: "unavailable" };
  }
  const refreshed = persistRefreshedSession(data, session);
  if (refreshed) notifyAuthChange();
  return refreshed ? { kind: "ok", session: refreshed } : { kind: "stale" };
}

export async function authedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const requestSession = getSessionSnapshot();
  let res = await sendWithToken(path, init, requestSession.token);
  if (res.status === 401 && requestSession.token) {
    const currentSession = getSessionSnapshot();
    if (!isSameSessionIdentity(requestSession, currentSession)) return res;
    if (!isSameSession(requestSession, currentSession)) {
      // Another request already rotated this logical session's credentials while this request
      // was pending. Retry once with that published token instead of starting another refresh.
      return sendWithToken(path, init, currentSession.token);
    }
    // The access token is likely expired. Try one silent refresh (single-flighted), then retry once.
    const outcome = await refresh(requestSession);
    if (outcome.kind === "ok" && isSessionCurrent(outcome.session)) {
      res = await sendWithToken(path, init, outcome.session.token);
    } else if (outcome.kind === "rejected" && isSessionCurrent(requestSession)) {
      // No refresh token, or the server rejected it — the session is over.
      clearSession();
      flagSignedOut();
      notifyAuthChange();
    }
    // "unavailable": keep the session — the original 401 becomes the caller's error and a
    // later retry (the tokens are untouched) can succeed once the server is reachable again.
  }
  return res;
}

function sendWithToken(path: string, init: RequestInit, token: string | null): Promise<Response> {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  else headers.delete("Authorization");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return fetch(`${API_BASE}${path}`, { signal: timeoutSignal(), ...init, headers });
}

export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, body: unknown) {
    super(`API ${status}`);
    this.status = status;
    this.body = body;
  }

  /** The RFC 7807 `detail` string, when the problem body carried one. */
  get detail(): string | undefined {
    return this.problemField("detail");
  }

  /** The RFC 7807 `instance` path, when the problem body carried one. */
  get instance(): string | undefined {
    return this.problemField("instance");
  }

  private problemField(field: "detail" | "instance"): string | undefined {
    const value = (this.body as Record<string, unknown> | null)?.[field];
    return typeof value === "string" ? value : undefined;
  }
}

/**
 * The QueryClient's retry policy (wired in main.tsx): never retry a 4xx — the server's
 * answer won't change, and retrying only delays the error UI (a 403 used to sit behind
 * ~7 s of backoff); transient failures (network, timeout, 5xx) get up to two retries.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  return failureCount < 2 && !(error instanceof ApiError && error.status >= 400 && error.status < 500);
}

export async function safeJson(res: Response): Promise<unknown> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

/**
 * The two standard wrapper shapes: every ordinary endpoint wrapper in `api/users.ts` and
 * `api/catalogFiles.ts` is one call to these (ok-check + ProblemDetail body + parse);
 * `authedFetch`/`ApiError`/`safeJson` stay exported for the special cases (extra response
 * logic, non-authed auth flows).
 */
export async function jsonRequest<T>(input: string, init?: RequestInit): Promise<T> {
  const res = await authedFetch(input, init);
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  return (await res.json()) as T;
}

/** [jsonRequest]'s sibling for 201/204-style responses whose body is ignored. */
export async function voidRequest(input: string, init?: RequestInit): Promise<void> {
  const res = await authedFetch(input, init);
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
}

/**
 * The query-string builder behind the list/export wrappers (`listUsers`, `listCatalogFiles`,
 * `exportCatalogFiles`). Skips null/undefined/"" (an absent or cleared filter); `false` and
 * `0` ARE sent — a param that must be OMITTED rather than sent as false/empty is passed as
 * `value || undefined` at the call site (the pages' `filter || undefined` idiom). An array
 * value appends one repeated key per non-empty entry (the server's documented IN idiom —
 * `labelValue`); an empty array is an absent filter.
 */
export function buildQuery(
  params: Record<string, string | number | boolean | readonly string[] | null | undefined>,
): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === "") continue;
    if (Array.isArray(value)) {
      for (const entry of value) {
        if (entry !== "") search.append(key, entry);
      }
      continue;
    }
    search.set(key, String(value));
  }
  return search.toString();
}
