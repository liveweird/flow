import { useEffect, useRef } from "react";

export type DirtyReader = boolean | (() => boolean);

/**
 * The browser-side half of the cancel guard (v3.5.2, extracted from `useDiscardGuard`): a
 * `beforeunload` prompt that covers reload/close while the form is dirty. `isDirty` is a
 * value or a fresh reader (`() => form.isDirty()`, or a payload compare); the latest one is
 * kept in a ref so the listener never goes stale. Returns the same reader for callers that
 * also gate their own navigation on it. Screens whose Cancel is a product rule of its own
 * (the succession review's "won't count as a review" modal) call this directly.
 */
export function useBeforeUnloadGuard(isDirty: DirtyReader): () => boolean {
  const dirtyRef = useRef(isDirty);
  useEffect(() => {
    dirtyRef.current = isDirty;
  });
  const dirty = () => (typeof dirtyRef.current === "function" ? dirtyRef.current() : dirtyRef.current);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty()) event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, []);

  return dirty;
}
