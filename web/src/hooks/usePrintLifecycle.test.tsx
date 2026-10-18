import { afterEach, describe, expect, test } from "vitest";
import { act } from "@testing-library/react";
import { useComputedColorScheme } from "@mantine/core";
import { theme } from "../theme";
import PrintAwareMantineProvider from "../components/PrintAwareMantineProvider";
import { render, screen } from "../test/render";
import { usePrintLifecycle } from "./usePrintLifecycle";

/** A chart-like consumer: picks its colours from the computed scheme, as the heat cells and chart marks do. */
function SchemeProbe() {
  usePrintLifecycle();
  return <output>{useComputedColorScheme("light")}</output>;
}

function renderDark() {
  localStorage.setItem("mantine-color-scheme-value", "dark");
  return render(
    <PrintAwareMantineProvider env="test" theme={theme}>
      <SchemeProbe />
    </PrintAwareMantineProvider>,
  );
}

describe("usePrintLifecycle + PrintAwareMantineProvider", () => {
  afterEach(() => {
    localStorage.removeItem("mantine-color-scheme-value");
  });

  test("components that read the computed scheme print light, then go back to the user's scheme", () => {
    renderDark();
    expect(screen.getByRole("status")).toHaveTextContent("dark");
    act(() => {
      window.dispatchEvent(new Event("beforeprint"));
    });
    expect(screen.getByRole("status")).toHaveTextContent("light");
    act(() => {
      window.dispatchEvent(new Event("afterprint"));
    });
    expect(screen.getByRole("status")).toHaveTextContent("dark");
    // Never persisted: the stored choice is still the user's.
    expect(localStorage.getItem("mantine-color-scheme-value")).toBe("dark");
  });

  test("the print media query's change is a second trigger, for the browsers that skip the events", () => {
    const original = window.matchMedia;
    let listener: ((event: MediaQueryListEvent) => void) | undefined;
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      addEventListener: (_type: string, handler: (event: MediaQueryListEvent) => void) => {
        if (query === "print") listener = handler;
      },
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
      onchange: null,
    })) as unknown as typeof window.matchMedia;
    try {
      renderDark();
      act(() => listener?.({ matches: true } as MediaQueryListEvent));
      expect(screen.getByRole("status")).toHaveTextContent("light");
      act(() => listener?.({ matches: false } as MediaQueryListEvent));
      expect(screen.getByRole("status")).toHaveTextContent("dark");
    } finally {
      window.matchMedia = original;
    }
  });

  test("unmounting while a print is pending leaves the app in the user's scheme", () => {
    const { unmount } = renderDark();
    act(() => {
      window.dispatchEvent(new Event("beforeprint"));
    });
    unmount();
    renderDark();
    expect(screen.getByRole("status")).toHaveTextContent("dark");
  });
});
