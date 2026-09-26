import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
import { DEFAULT_THEME } from "@mantine/core";
import i18n from "../i18n";

// Deterministic English in tests (the global i18n instance is shared by every test, including the
// many that render with their own inline providers — no per-test I18nextProvider needed).
void i18n.changeLanguage("en");

if (typeof globalThis.localStorage === "undefined") {
  const store = new Map<string, string>();
  const localStorageMock: Storage = {
    get length() {
      return store.size;
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
  Object.defineProperty(globalThis, "localStorage", {
    value: localStorageMock,
    configurable: true,
  });
  if (typeof window !== "undefined") {
    Object.defineProperty(window, "localStorage", {
      value: localStorageMock,
      configurable: true,
    });
  }
}

// Mantine's Transition calls useTransition before its env="test" render shortcut. Force the
// reduced-motion path so the hook completes synchronously instead of leaving an rAF -> rAF ->
// setTimeout chain that can race happy-dom teardown. Other media queries keep their normal
// happy-dom behavior.
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";
if (typeof window !== "undefined") {
  const originalMatchMedia = window.matchMedia?.bind(window);
  const mediaQueryList = (query: string, matches: boolean): MediaQueryList =>
    ({
      matches,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
  window.matchMedia = (query: string) =>
    query === REDUCED_MOTION_QUERY
      ? mediaQueryList(query, true)
      : originalMatchMedia
        ? originalMatchMedia(query)
        : mediaQueryList(query, false);
}

// Tests use both the shared themed provider and a bare provider. Make both honor the reduced
// motion preference without changing the application theme or runtime behavior.
DEFAULT_THEME.respectReducedMotion = true;

// happy-dom does not implement the FontFaceSet API. Mantine's autosize Textarea
// subscribes to `document.fonts` "loadingdone" events on mount; without this shim
// it throws "Cannot read properties of undefined (reading 'addEventListener')".
if (typeof document !== "undefined" && !document.fonts) {
  Object.defineProperty(document, "fonts", {
    value: {
      addEventListener: () => {},
      removeEventListener: () => {},
      ready: Promise.resolve(),
    },
    configurable: true,
  });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});
