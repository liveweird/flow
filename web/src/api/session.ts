// Session state — token/roles storage and the render-time accessors
// (transport lives in ./http).

import i18n, { asSupportedLanguage } from "../i18n";
import type { components } from "./schema";

type LoginSuccess = components["schemas"]["LoginResponse"];

export type SessionSnapshot = Readonly<{
  generation: number;
  identity: string | null;
  token: string | null;
  refreshToken: string | null;
}>;

export const TOKEN_KEY = "flow.auth.token";
const REFRESH_TOKEN_KEY = "flow.auth.refreshToken";
export const ROLES_KEY = "flow.auth.roles";
const USER_ID_KEY = "flow.auth.userId";
const DISABLED_FEATURES_KEY = "flow.auth.disabledFeatures";
const SESSION_IDENTITY_KEY = "flow.auth.sessionIdentity";

type SessionIdentityListener = () => void;
const sessionIdentityListeners = new Set<SessionIdentityListener>();

// Changes made through this module advance the generation. The persisted identity additionally
// catches another tab replacing localStorage, while the token-pair comparison protects refresh
// publication within one logical session.
let sessionGeneration = 0;

/** Subscribe to same-tab logical session replacements (login, logout, or definitive expiry). */
export function subscribeSessionIdentityChange(listener: SessionIdentityListener): () => void {
  sessionIdentityListeners.add(listener);
  return () => sessionIdentityListeners.delete(listener);
}

function notifySessionIdentityChange(): void {
  sessionIdentityListeners.forEach((listener) => listener());
}

function newSessionIdentity(): string {
  return crypto.randomUUID();
}

function getSessionIdentity(): string | null {
  const stored = localStorage.getItem(SESSION_IDENTITY_KEY);
  if (stored || !getToken()) return stored;
  // Sessions created before this key existed receive an identity on first use. If another tab
  // races this migration, the different stored value makes pending work fail closed as stale.
  const identity = newSessionIdentity();
  localStorage.setItem(SESSION_IDENTITY_KEY, identity);
  return identity;
}

/** Additional roles — every user is implicitly a regular user; an empty set means no extra privileges. */
const USER_ROLES = ["ADMIN"] as const;
export type UserRole = (typeof USER_ROLES)[number];

/** Per-user gateable features — the DISABLED set travels the wire; empty = full access. */
export const FEATURES = ["MFA"] as const;
export type Feature = (typeof FEATURES)[number];

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token === null) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_IDENTITY_KEY);
  } else {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(SESSION_IDENTITY_KEY, newSessionIdentity());
  }
  sessionGeneration += 1;
  notifySessionIdentityChange();
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

function setRefreshToken(token: string | null): void {
  if (token === null) localStorage.removeItem(REFRESH_TOKEN_KEY);
  else localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

export function getRoles(): UserRole[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ROLES_KEY) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((r): r is UserRole => USER_ROLES.includes(r)) : [];
  } catch {
    return [];
  }
}

export function getUserId(): number | null {
  const raw = localStorage.getItem(USER_ID_KEY);
  if (raw === null) return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function getDisabledFeatures(): Feature[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(DISABLED_FEATURES_KEY) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((f): f is Feature => FEATURES.includes(f)) : [];
  } catch {
    return [];
  }
}

export function hasFeature(feature: Feature): boolean {
  return !getDisabledFeatures().includes(feature);
}

export function isAdmin(): boolean {
  return getRoles().includes("ADMIN");
}

export function getSessionSnapshot(): SessionSnapshot {
  return {
    generation: sessionGeneration,
    identity: getSessionIdentity(),
    token: getToken(),
    refreshToken: getRefreshToken(),
  };
}

export function isSameSessionIdentity(left: SessionSnapshot, right: SessionSnapshot): boolean {
  return left.generation === right.generation && left.identity === right.identity;
}

export function isSameSession(left: SessionSnapshot, right: SessionSnapshot): boolean {
  return isSameSessionIdentity(left, right)
    && left.token === right.token
    && left.refreshToken === right.refreshToken;
}

export function isSessionCurrent(snapshot: SessionSnapshot): boolean {
  return isSameSession(snapshot, getSessionSnapshot());
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(ROLES_KEY);
  localStorage.removeItem(USER_ID_KEY);
  localStorage.removeItem(DISABLED_FEATURES_KEY);
  localStorage.removeItem(SESSION_IDENTITY_KEY);
  sessionGeneration += 1;
  notifySessionIdentityChange();
}

function storeSession(data: LoginSuccess): void {
  localStorage.setItem(TOKEN_KEY, data.token);
  setRefreshToken(data.refreshToken);
  localStorage.setItem(ROLES_KEY, JSON.stringify(data.roles));
  localStorage.setItem(USER_ID_KEY, String(data.userId));
  localStorage.setItem(DISABLED_FEATURES_KEY, JSON.stringify(data.disabledFeatures ?? []));
}

/**
 * Persist the access + refresh pair (and the current roles/userId/feature flags) returned by
 * /login or /refresh. `?? []` keeps a mid-deploy older server (no disabledFeatures yet) harmless.
 */
export function persistSession(data: LoginSuccess): void {
  storeSession(data);
  localStorage.setItem(SESSION_IDENTITY_KEY, newSessionIdentity());
  sessionGeneration += 1;
  notifySessionIdentityChange();
  applySessionLanguage(data);
}

/** Publish a rotated token pair only if the exact credentials sent to /refresh still own storage. */
export function persistRefreshedSession(
  data: LoginSuccess,
  expected: SessionSnapshot,
): SessionSnapshot | null {
  if (!isSessionCurrent(expected)) return null;
  storeSession(data);
  applySessionLanguage(data);
  return getSessionSnapshot();
}

function applySessionLanguage(data: LoginSuccess): void {
  // Apply the user's stored language (V18) — one chokepoint covers login, the MFA step, and
  // the silent refresh (so an admin change propagates within the refresh window). The
  // inequality guard avoids re-firing languageChanged app-wide on every refresh; the
  // data.language truthiness guard keeps a mid-deploy older server harmless (the
  // disabledFeatures ?? [] precedent). changeLanguage caches to flow.lang, so the stored
  // language also becomes the device language.
  const lang = asSupportedLanguage(data.language);
  if (data.language && lang !== asSupportedLanguage(i18n.resolvedLanguage)) {
    void i18n.changeLanguage(lang);
  }
}
