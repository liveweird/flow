import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act } from "@testing-library/react";
import ScrollToTop from "./ScrollToTop";
import { renderWithProviders, screen } from "../test/render";

function scrollTo(y: number) {
  Object.defineProperty(window, "scrollY", { value: y, configurable: true });
  act(() => {
    window.dispatchEvent(new Event("scroll"));
  });
}

/** Answers the reduced-motion query as `reduced` and every other query as "no match" (the shared setup forces reduced motion on). */
function reducedMotion(reduced: boolean) {
  window.matchMedia = ((query: string) =>
    ({
      matches: query === "(prefers-reduced-motion: reduce)" && reduced,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList) as typeof window.matchMedia;
}

describe("ScrollToTop", () => {
  const originalMatchMedia = window.matchMedia;
  const originalScrollTo = window.scrollTo;

  beforeEach(() => {
    window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
    Object.defineProperty(window, "scrollY", { value: 0, configurable: true });
  });

  afterEach(() => {
    window.matchMedia = originalMatchMedia;
    window.scrollTo = originalScrollTo;
    Object.defineProperty(window, "scrollY", { value: 0, configurable: true });
  });

  test("appears only once the page is scrolled past 400px", () => {
    renderWithProviders(<ScrollToTop />);
    expect(screen.queryByRole("button", { name: "Back to top" })).not.toBeInTheDocument();
    scrollTo(300);
    expect(screen.queryByRole("button", { name: "Back to top" })).not.toBeInTheDocument();
    scrollTo(401);
    expect(screen.getByRole("button", { name: "Back to top" })).toBeInTheDocument();
    scrollTo(0);
    expect(screen.queryByRole("button", { name: "Back to top" })).not.toBeInTheDocument();
  });

  test("is shown at once when the page is already scrolled on mount", () => {
    Object.defineProperty(window, "scrollY", { value: 900, configurable: true });
    renderWithProviders(<ScrollToTop />);
    expect(screen.getByRole("button", { name: "Back to top" })).toBeInTheDocument();
  });

  test("scrolls smoothly to the top and focuses the page heading", async () => {
    reducedMotion(false);
    renderWithProviders(
      <main id="main-content">
        <h2>Cycle time</h2>
        <ScrollToTop />
      </main>,
    );
    scrollTo(1200);
    await userEvent.setup().click(screen.getByRole("button", { name: "Back to top" }));
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "smooth" });
    expect(screen.getByRole("heading", { level: 2, name: "Cycle time" })).toHaveFocus();
  });

  test("focuses the main region when the page has no heading", async () => {
    reducedMotion(false);
    renderWithProviders(
      <main id="main-content" aria-label="Main">
        <ScrollToTop />
      </main>,
    );
    scrollTo(1200);
    await userEvent.setup().click(screen.getByRole("button", { name: "Back to top" }));
    expect(screen.getByRole("main")).toHaveFocus();
  });

  test("scrolls instantly under prefers-reduced-motion", async () => {
    reducedMotion(true);
    renderWithProviders(<ScrollToTop />);
    scrollTo(1200);
    await userEvent.setup().click(screen.getByRole("button", { name: "Back to top" }));
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "auto" });
  });

  test("is marked hidden for print", () => {
    Object.defineProperty(window, "scrollY", { value: 900, configurable: true });
    renderWithProviders(<ScrollToTop />);
    expect(document.querySelector('[data-print="hide"]')).toContainElement(screen.getByRole("button", { name: "Back to top" }));
  });
});
