import { useEffect } from "react";
import { flushSync } from "react-dom";
import { setPrinting } from "../utils/printing";

/**
 * Tells the printing store when the page starts and stops being printed, so the whole app (Home and every page) prints in the
 * light scheme. `beforeprint` flips it synchronously (the browser lays the page out for paper right after the event), `afterprint`
 * clears it; the `print` media query's change event is the second trigger of both, for the browsers that do not fire the events
 * reliably (Safari). Mounted once, by the app shell.
 */
export function usePrintLifecycle(): void {
  useEffect(() => {
    const start = () => flushSync(() => setPrinting(true));
    const stop = () => flushSync(() => setPrinting(false));
    const media = window.matchMedia?.("print");
    const onMedia = (event: MediaQueryListEvent) => (event.matches ? start() : stop());
    window.addEventListener("beforeprint", start);
    window.addEventListener("afterprint", stop);
    media?.addEventListener?.("change", onMedia);
    return () => {
      window.removeEventListener("beforeprint", start);
      window.removeEventListener("afterprint", stop);
      media?.removeEventListener?.("change", onMedia);
      setPrinting(false);
    };
  }, []);
}
