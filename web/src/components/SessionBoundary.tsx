import { Fragment, useState, useSyncExternalStore, type ReactNode } from "react";
import { getSessionSnapshot } from "../api/session";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";

function currentIdentity(): string | null {
  return getSessionSnapshot().identity;
}

/** Discard mounted forms, mutation observers, and local results when the account changes. */
export default function SessionBoundary({ children }: { children: ReactNode }) {
  const identity = useSyncExternalStore(subscribeLogicalSession, currentIdentity, () => null);
  const [lastAuthenticatedIdentity, setLastAuthenticatedIdentity] = useState(identity);
  if (identity !== null && identity !== lastAuthenticatedIdentity) {
    setLastAuthenticatedIdentity(identity);
  }
  // On sign-out the route guard/navigation already unmounts protected pages. Remounting here
  // would run RequireAuth on the old URL before explicit logout navigates to /login, saving a
  // stale return destination. Keep the key until the next authenticated identity arrives.
  return <Fragment key={identity ?? lastAuthenticatedIdentity ?? "anonymous"}>{children}</Fragment>;
}
