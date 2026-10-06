import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { useDeepDiveUrlState } from "./useDeepDiveUrlState";

/** The hook plus the router's own view of the URL and history, so a test can see what a setter wrote and how. */
function renderState(route: string) {
  return renderHook(
    () => ({ state: useDeepDiveUrlState(), location: useLocation(), navigate: useNavigate() }),
    {
      wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>,
    },
  );
}

const search = (result: { current: { location: { search: string } } }) => new URLSearchParams(result.current.location.search);

describe("useDeepDiveUrlState", () => {
  test("no params: an empty, incomplete selection on the matrix view", () => {
    const { result } = renderState("/reports/deep-dive");
    expect(result.current.state.selection).toEqual({});
    expect(result.current.state.selectionKey).toBe("");
    expect(result.current.state.complete).toBe(false);
    expect(result.current.state.view).toBe("matrix");
  });

  test("parses the selection and the view from the URL; the key is canonical", () => {
    const { result } = renderState("/reports/deep-dive?epicId=FLO-2&epicId=FLO-1&view=burnup");
    expect(result.current.state.selection.epicIds).toEqual(["FLO-1", "FLO-2"]);
    expect(result.current.state.selectionKey).toBe("epicId=FLO-1&epicId=FLO-2");
    expect(result.current.state.complete).toBe(true);
    expect(result.current.state.view).toBe("burnup");
  });

  test("a conflicting selection drops what cannot hold: sprint ids without a domain are not a selection", () => {
    const { result } = renderState("/reports/deep-dive?sprintId=3");
    expect(result.current.state.complete).toBe(false);
    expect(result.current.state.selectionKey).toBe("");
  });

  test("applySelection writes the selection as a NEW history entry and keeps foreign params", () => {
    const { result } = renderState("/reports/deep-dive?foo=bar");
    act(() => result.current.state.applySelection({ epicIds: ["FLO-1"] }));
    expect(search(result).get("epicId")).toBe("FLO-1");
    expect(search(result).get("foo")).toBe("bar");
    expect(result.current.state.complete).toBe(true);
    // pushed, not replaced: going back restores the previous (empty) selection
    act(() => result.current.navigate(-1));
    expect(search(result).has("epicId")).toBe(false);
    expect(result.current.state.complete).toBe(false);
  });

  test("setView swaps the view IN PLACE (replace): matrix is the param's absence", () => {
    const { result } = renderState("/reports/deep-dive?epicId=FLO-1");
    act(() => result.current.state.setView("burnup"));
    expect(result.current.state.view).toBe("burnup");
    expect(search(result).get("view")).toBe("burnup");
    expect(search(result).get("epicId")).toBe("FLO-1");
    act(() => result.current.state.setView("matrix"));
    expect(result.current.state.view).toBe("matrix");
    expect(search(result).has("view")).toBe(false);
  });
});
