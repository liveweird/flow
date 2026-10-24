import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { useLiveSearchParams } from "./useLiveSearchParams";

function renderLive(route: string) {
  return renderHook(() => ({ live: useLiveSearchParams(), location: useLocation(), navigate: useNavigate() }), {
    wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>,
  });
}

describe("useLiveSearchParams", () => {
  test("a functional update from a first-render handler sees the params committed since", () => {
    const { result } = renderLive("/p?a=1");
    const [, staleSet] = result.current.live;
    act(() => result.current.navigate("/p?a=1&b=2"));
    act(() =>
      staleSet((prev) => {
        expect(prev.get("b")).toBe("2");
        const next = new URLSearchParams(prev);
        next.set("c", "3");
        return next;
      }),
    );
    expect(result.current.location.search).toBe("?a=1&b=2&c=3");
  });

  test("a plain value passes through, with the navigate options", () => {
    const { result } = renderLive("/p?a=1");
    act(() => result.current.live[1](new URLSearchParams("z=9"), { replace: true }));
    expect(result.current.location.search).toBe("?z=9");
  });

  test("the setter is stable across renders and the params are the current ones", () => {
    const { result } = renderLive("/p?a=1");
    const first = result.current.live[1];
    act(() => result.current.navigate("/p?a=2"));
    expect(result.current.live[1]).toBe(first);
    expect(result.current.live[0].get("a")).toBe("2");
  });

  test("two writes in one event both land: the second builds on the first", () => {
    const { result } = renderLive("/p?a=1");
    const [, set] = result.current.live;
    act(() => {
      set((prev) => new URLSearchParams([...prev, ["b", "2"]]));
      set((prev) => new URLSearchParams([...prev, ["c", "3"]]));
    });
    expect(result.current.location.search).toBe("?a=1&b=2&c=3");
  });

  test("the live params and state read what the last write set, before any commit", () => {
    const { result } = renderLive("/p?a=1");
    const [, set, live] = result.current.live;
    act(() => {
      set(new URLSearchParams("a=2"), { state: { marker: true } });
      expect(live.params().get("a")).toBe("2");
      expect(live.state()).toEqual({ marker: true });
      set(new URLSearchParams("a=3"));
      expect(live.state()).toBeNull();
    });
    expect(result.current.location.search).toBe("?a=3");
  });
});
