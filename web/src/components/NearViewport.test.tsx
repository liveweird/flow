import { afterEach, describe, expect, test, vi } from "vitest";
import { act, renderWithProviders as render, screen } from "../test/render";
import NearViewport from "./NearViewport";

type Callback = (entries: { isIntersecting: boolean }[]) => void;

/** A controllable IntersectionObserver: the test decides when the box comes near. */
function stubObserver() {
  const state = { callback: undefined as Callback | undefined, observed: 0, disconnected: 0, margin: "" };
  class FakeObserver {
    constructor(callback: Callback, options?: { rootMargin?: string }) {
      state.callback = callback;
      state.margin = options?.rootMargin ?? "";
    }
    observe() {
      state.observed += 1;
    }
    disconnect() {
      state.disconnected += 1;
    }
  }
  vi.stubGlobal("IntersectionObserver", FakeObserver);
  return state;
}

describe("NearViewport", () => {
  afterEach(() => vi.unstubAllGlobals());

  test("without an IntersectionObserver the children mount at once", () => {
    render(
      <NearViewport height={200}>
        <p>chart</p>
      </NearViewport>,
    );
    expect(screen.getByText("chart")).toBeInTheDocument();
  });

  test("holds the height and mounts nothing until the box comes near, then keeps the children for good", () => {
    const observer = stubObserver();
    const { container } = render(
      <NearViewport height={200} margin="300px">
        <p>chart</p>
      </NearViewport>,
    );
    expect(screen.queryByText("chart")).not.toBeInTheDocument();
    expect(container.querySelector("div")).toHaveStyle({ minHeight: "200px" });
    expect(observer.observed).toBe(1);
    expect(observer.margin).toBe("300px");

    // Still far: an entry that is not intersecting changes nothing.
    act(() => observer.callback?.([{ isIntersecting: false }]));
    expect(screen.queryByText("chart")).not.toBeInTheDocument();

    act(() => observer.callback?.([{ isIntersecting: true }]));
    expect(screen.getByText("chart")).toBeInTheDocument();
    // The observer is done once the chart is in; scrolling away never unmounts it.
    expect(observer.disconnected).toBeGreaterThan(0);
    act(() => observer.callback?.([{ isIntersecting: false }]));
    expect(screen.getByText("chart")).toBeInTheDocument();
  });
});
