import type { QueryClient } from "@tanstack/react-query";
import { getSessionSnapshot, subscribeSessionIdentityChange } from "../api/session";

const AUTH_STORAGE_PREFIX = "flow.auth.";

/** Observe both this tab's session writes and completed writes from another tab. */
export function subscribeLogicalSession(listener: () => void): () => void {
  const unsubscribe = subscribeSessionIdentityChange(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === null || event.key.startsWith(AUTH_STORAGE_PREFIX)) listener();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    unsubscribe();
    window.removeEventListener("storage", onStorage);
  };
}

/**
 * Keep caller-scoped server data inside one logical browser session. A replacement login,
 * sign-out, definitive session expiry, or another tab changing the stored identity cancels
 * in-flight reads and removes their cached results before auth consumers can render again.
 * Silent token rotation preserves the session identity and therefore preserves its cache.
 */
export function bindSessionQueryCache(queryClient: QueryClient): () => void {
  let identity = getSessionSnapshot().identity;

  function resetForIdentityChange(): void {
    const nextIdentity = getSessionSnapshot().identity;
    if (nextIdentity === identity) return;
    identity = nextIdentity;
    // `clear()` removes cache entries, but an already-mounted QueryObserver otherwise retains
    // its last result until React renders again. Reset first so no A-owned data remains visible
    // during the identity handoff; reset() also cancels that query without starting a refetch.
    queryClient.getQueryCache().getAll().forEach((query) => query.reset());
    queryClient.clear();
  }

  return subscribeLogicalSession(resetForIdentityChange);
}
