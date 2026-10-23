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

// The server ROTATES the refresh token: each one works once, and a replay is a 401 once the server's
// short replay grace (60 s by default, for a lost response) is over. Tabs share one session through
// localStorage, so tabs that refresh with the same token race - one wins and publishes the new pair,
// and a loser outside the grace is rejected. A rejection must therefore never end the session while
// storage already holds a different pair of the SAME session: that pair was minted by the winning
// tab, so the loser adopts it and retries.
const REFRESH_LOCK_NAME = "flow.auth.refresh";

// Cross-tab SERIALISATION of refreshes where the browser has Web Locks (secure contexts): the tab
// that waits re-reads storage once it holds the lock and adopts the pair the previous holder stored,
// instead of replaying. Without Web Locks - or if the lock cannot be acquired (the request can
// reject with InvalidStateError/SecurityError) - the refresh runs unserialised: the server's grace
// answers a racing loser with a pair of its own, and the adoption on rejection repairs the rest.
async function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === "undefined" ? undefined : (navigator.locks as LockManager | undefined);
  if (!locks) return work();
  let started = false;
  try {
    return await locks.request(REFRESH_LOCK_NAME, () => {
      started = true;
      return work();
    });
  } catch (error) {
    if (started) throw error; // `work` itself failed - do not run it twice
    return work();
  }
}

// The session another tab has rotated since `session` was captured: same logical session
// (identity), a stored pair whose refresh token differs from the one we hold. A login in another
// tab replaces the identity and is not adopted.
function pairRotatedByAnotherTab(session: SessionSnapshot): SessionSnapshot | null {
  const stored = getSessionSnapshot();
  if (!isSameSessionIdentity(session, stored) || !stored.token || !stored.refreshToken) return null;
  return stored.refreshToken === session.refreshToken ? null : stored;
}

function doRefresh(session: SessionSnapshot): Promise<RefreshOutcome> {
  return withRefreshLock(async () => {
    // A tab that held the lock before us may have rotated the pair: use it instead of replaying.
    const adopted = pairRotatedByAnotherTab(session);
    return adopted ? { kind: "ok", session: adopted } : exchangeRefreshToken(session);
  });
}

// The network half of a refresh, with no session state: the new pair, or why there is none. Never throws.
type PairExchange = { kind: "pair"; data: LoginSuccess } | { kind: "rejected" } | { kind: "unavailable" };

async function requestRefreshedPair(refreshToken: string): Promise<PairExchange> {
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
  return { kind: "pair", data };
}

/**
 * Exchanges a refresh token for a new pair WITHOUT reading or writing the stored session. For `logout()`,
 * which has already cleared the session but still has to authenticate its server-side revocation; the pair
 * is used for that one call and discarded. `null` on any failure (it never throws).
 */
export async function refreshPairDetached(refreshToken: string): Promise<{ token: string; refreshToken: string } | null> {
  const exchange = await requestRefreshedPair(refreshToken);
  return exchange.kind === "pair" ? { token: exchange.data.token, refreshToken: exchange.data.refreshToken } : null;
}

async function exchangeRefreshToken(session: SessionSnapshot): Promise<RefreshOutcome> {
  const refreshToken = session.refreshToken;
  if (!refreshToken) return { kind: "rejected" };
  const exchange = await requestRefreshedPair(refreshToken);
  if (exchange.kind === "rejected") {
    const adopted = pairRotatedByAnotherTab(session);
    return adopted ? { kind: "ok", session: adopted } : { kind: "rejected" };
  }
  if (exchange.kind === "unavailable") return { kind: "unavailable" };
  const refreshed = persistRefreshedSession(exchange.data, session);
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

  /** The RFC 7807 `type` URI, when the problem body carried one (`about:blank` is the unspecialised default). */
  get type(): string | undefined {
    return this.problemField("type");
  }

  private problemField(field: "detail" | "instance" | "type"): string | undefined {
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
 * The two standard wrapper shapes: every ordinary endpoint wrapper in `api/flowUsers.ts` and
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
 * The query-string builder behind the list/export wrappers (`listFlowUsers`, `listCatalogFiles`,
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
