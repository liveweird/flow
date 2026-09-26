/* eslint-disable react-refresh/only-export-components */
// -- the auth store helpers (signIn/signOut/useAuthed) live beside the route guards on purpose; a mixed file opts out of fast-refresh, which is fine for this rarely-edited module
import { useSyncExternalStore, type ReactElement } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { getToken, isAdmin, ROLES_KEY, TOKEN_KEY } from "./api/session";

const listeners = new Set<() => void>();

function subscribe(cb: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === TOKEN_KEY || e.key === ROLES_KEY || e.key === null) cb();
  };
  window.addEventListener("storage", onStorage);
  listeners.add(cb);
  return () => {
    window.removeEventListener("storage", onStorage);
    listeners.delete(cb);
  };
}

export function notifyAuthChange(): void {
  listeners.forEach((cb) => cb());
}

let pendingSignedOutBanner = false;

export function flagSignedOut(): void {
  pendingSignedOutBanner = true;
}

export function hasPendingSignedOut(): boolean {
  return pendingSignedOutBanner;
}

export function consumeSignedOut(): boolean {
  const v = pendingSignedOutBanner;
  pendingSignedOutBanner = false;
  return v;
}

function useAuth(): { token: string | null; isAuthenticated: boolean } {
  const token = useSyncExternalStore(subscribe, getToken, () => null);
  return { token, isAuthenticated: token !== null };
}

/** Subscribe to role updates published by silent refresh and other browser tabs. */
export function useAdmin(): boolean {
  return useSyncExternalStore(subscribe, isAdmin, () => false);
}

type LocationStateWithFrom = { from?: { pathname?: string; search?: string; hash?: string } } | null;

function destination(state: LocationStateWithFrom): string {
  const from = state?.from;
  return from?.pathname ? `${from.pathname}${from.search ?? ""}${from.hash ?? ""}` : "/";
}

export function RequireAuth(): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
}

/**
 * The ADMIN-only route element (wraps the user-management routes in App.tsx): a regular user is sent
 * home instead of watching the page 403. UX only — the server's `requireAdmin` is the rule; the pages'
 * queries additionally stay `enabled: isAdmin()` so no request fires for a redirected caller.
 */
export function RequireAdmin(): ReactElement {
  if (!useAdmin()) return <Navigate to="/" replace />;
  return <Outlet />;
}

export function RedirectIfAuthed({ children }: { children: ReactElement }): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (isAuthenticated) {
    return <Navigate to={destination(location.state as LocationStateWithFrom)} replace />;
  }
  return children;
}
