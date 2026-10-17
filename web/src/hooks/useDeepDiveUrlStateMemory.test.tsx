import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { FILTERS } from "../test/reportFixtures";
import { useDeepDiveUrlState } from "./useDeepDiveUrlState";

const STORED = "flow.viewSettings.reports.deepDive.filter";
const store = (query: string) => localStorage.setItem(STORED, JSON.stringify(query));
const stored = () => JSON.parse(localStorage.getItem(STORED) ?? "null") as string | null;

function renderRemembering(route: string, filters: typeof FILTERS | undefined = FILTERS) {
  return renderHook(
    () => ({ state: useDeepDiveUrlState(filters), location: useLocation(), navigationType: useNavigationType(), navigate: useNavigate() }),
    { wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter> },
  );
}

const search = (result: { current: { location: { search: string } } }) => new URLSearchParams(result.current.location.search);

describe("useDeepDiveUrlState memory", () => {
  test("a bare visit restores the last shown selection and view, and rewrites the URL with replace", async () => {
    store("domain=FLO&sprintId=41&sprintId=42&view=burnup");
    const { result } = renderRemembering("/reports/deep-dive");
    expect(result.current.state.selection).toEqual({ domain: "FLO", sprintIds: [41, 42] });
    expect(result.current.state.complete).toBe(true);
    expect(result.current.state.view).toBe("burnup");
    await waitFor(() => expect(search(result).get("domain")).toBe("FLO"));
    expect(search(result).getAll("sprintId")).toEqual(["41", "42"]);
    expect(search(result).get("view")).toBe("burnup");
    expect(result.current.navigationType).toBe("REPLACE");
    expect(result.current.state.restoredFromMemory).toBe(true);
    // a restore is not the user's change
    expect(stored()).toBe("domain=FLO&sprintId=41&sprintId=42&view=burnup");
  });

  test("nothing is restored before the reference data is in, then it is", async () => {
    store("epicId=FLO-1");
    const { result, rerender } = renderHook(
      ({ loaded }: { loaded: boolean }) => ({ state: useDeepDiveUrlState(loaded ? FILTERS : undefined), location: useLocation() }),
      {
        initialProps: { loaded: false },
        wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={["/reports/deep-dive"]}>{children}</MemoryRouter>,
      },
    );
    expect(result.current.state.complete).toBe(false);
    rerender({ loaded: true });
    await waitFor(() => expect(result.current.state.selection).toEqual({ epicIds: ["FLO-1"] }));
  });

  test("a link with any selection param wins and the memory is left as it was; so does an old link carrying only the retired key", () => {
    store("epicId=FLO-9");
    const { result } = renderRemembering("/reports/deep-dive?epicId=FLO-1");
    expect(result.current.state.selection).toEqual({ epicIds: ["FLO-1"] });
    expect(stored()).toBe("epicId=FLO-9");
    const retiredOnly = renderRemembering("/reports/deep-dive?issueId=FLO-11");
    expect(retiredOnly.result.current.state.complete).toBe(false);
    expect(retiredOnly.result.current.state.retired).toBe(true);
  });

  test("a stored domain or connection the reference data does not list is dropped (the sprints go with the domain)", () => {
    store("domain=GONE&sprintId=41&connectionId=9");
    expect(renderRemembering("/reports/deep-dive").result.current.state.complete).toBe(false);
    store("epicId=FLO-1&connectionId=9");
    expect(renderRemembering("/reports/deep-dive").result.current.state.selection).toEqual({ epicIds: ["FLO-1"] });
  });

  test("the retired issueId, a corrupt value and a non-string in storage read as the plain selection or as nothing", () => {
    store("epicId=FLO-1&issueId=FLO-11");
    expect(renderRemembering("/reports/deep-dive").result.current.state.selection).toEqual({ epicIds: ["FLO-1"] });
    localStorage.setItem(STORED, "{nope");
    expect(renderRemembering("/reports/deep-dive").result.current.state.complete).toBe(false);
    localStorage.setItem(STORED, JSON.stringify({ epicId: "FLO-1" }));
    expect(renderRemembering("/reports/deep-dive").result.current.state.complete).toBe(false);
  });

  test("applySelection and setView are the user's changes and write the memory; Back does not", () => {
    const { result } = renderRemembering("/reports/deep-dive?foo=bar");
    expect(stored()).toBeNull();
    act(() => result.current.state.applySelection({ epicIds: ["FLO-2", "FLO-1"] }));
    expect(stored()).toBe("epicId=FLO-1&epicId=FLO-2");
    act(() => result.current.state.setView("burnup"));
    expect(stored()).toBe("epicId=FLO-1&epicId=FLO-2&view=burnup");
    act(() => result.current.state.setView("matrix"));
    expect(stored()).toBe("epicId=FLO-1&epicId=FLO-2");
    act(() => result.current.navigate(-1));
    expect(stored()).toBe("epicId=FLO-1&epicId=FLO-2");
  });

  test("forgetRestored empties the memory and returns to the bare page; a followed link is never flagged as restored", async () => {
    store("epicId=FLO-9");
    const { result } = renderRemembering("/reports/deep-dive");
    await waitFor(() => expect(result.current.state.restoredFromMemory).toBe(true));
    act(() => result.current.state.forgetRestored());
    expect(stored()).toBe("");
    expect(result.current.location.search).toBe("");
    expect(result.current.state.complete).toBe(false);
    expect(result.current.state.restoredFromMemory).toBe(false);
    expect(renderRemembering("/reports/deep-dive?epicId=FLO-9").result.current.state.restoredFromMemory).toBe(false);
  });
});
