// Auth flows — login and logout (transport in ./http, session state in ./session).

import { API_BASE, ApiError, safeJson, timeoutSignal } from "./http";
import type { components, paths } from "./schema";
import { clearSession, getSessionSnapshot, isSessionCurrent, persistSession } from "./session";

type LoginBody = paths["/api/v1/login"]["post"]["requestBody"]["content"]["application/json"];
// Tokens, or (MFA-enabled accounts) a second-factor challenge — discriminate via isMfaChallenge.
type LoginOk = paths["/api/v1/login"]["post"]["responses"]["200"]["content"]["application/json"];
type LoginSuccess = components["schemas"]["LoginResponse"];
export type MfaChallenge = components["schemas"]["MfaChallengeResponse"];

export function isMfaChallenge(data: LoginOk): data is MfaChallenge {
  return "mfaRequired" in data;
}

export async function login(credentials: LoginBody): Promise<LoginOk> {
  const requestSession = getSessionSnapshot();
  const res = await fetch(`${API_BASE}/api/v1/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(credentials),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  const data = (await res.json()) as LoginOk;
  // An MFA challenge carries no tokens — the session starts only after verifyMfa.
  if (!isMfaChallenge(data) && isSessionCurrent(requestSession)) persistSession(data);
  return data;
}

type MfaVerifyBody =
  paths["/api/v1/login/mfa"]["post"]["requestBody"]["content"]["application/json"];

/**
 * Second login step for MFA-enabled accounts: exchanges the challenge id + emailed 6-digit
 * code for the ordinary token pair (persisted like a login). Throws ApiError on 401
 * (invalid/expired code) or 429 (rate-limited).
 */
export async function verifyMfa(challengeId: string, code: string): Promise<LoginSuccess> {
  const requestSession = getSessionSnapshot();
  const body: MfaVerifyBody = { challengeId, code };
  const res = await fetch(`${API_BASE}/api/v1/login/mfa`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  const data = (await res.json()) as LoginSuccess;
  if (isSessionCurrent(requestSession)) persistSession(data);
  return data;
}

type PasswordResetBody =
  paths["/api/v1/password-reset"]["post"]["requestBody"]["content"]["application/json"];

/**
 * Self-service password reset. Always 202 for a well-formed request (no account enumeration);
 * throws ApiError on 429 (one request per minute per address) or 503 (deployment without email).
 */
export async function requestPasswordReset(email: string): Promise<void> {
  const body: PasswordResetBody = { email };
  const res = await fetch(`${API_BASE}/api/v1/password-reset`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: timeoutSignal(),
  });
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
}

export async function logout(): Promise<void> {
  const { token, refreshToken } = getSessionSnapshot();
  // Invalidate the captured session before waiting for the best-effort server revocation. A
  // subsequent login owns a new generation and cannot be erased when this delayed call settles.
  clearSession();
  if (!token) return;
  try {
    await fetch(`${API_BASE}/api/v1/logout`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      // Include the refresh token so an explicit logout revokes it too, not just the access token.
      body: JSON.stringify({ refreshToken }),
      signal: timeoutSignal(),
    });
  } catch {
    // Best-effort revoke: offline/timeout must not block the LOCAL sign-out — a rejected
    // fetch skipping clearSession would leave the user apparently signed in.
  }
}
