import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { jsonResponse } from "../test/http";
import { renderPage, serve, type FetchMock } from "../test/capacityKit";

const STORED = "flow.viewSettings.capacity.filter";
const remembered = () => JSON.parse(localStorage.getItem(STORED) ?? "null") as string | null;
const at = () => screen.getByTestId("at");

describe("Capacity remembers the team and sprint a user picked", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["USER"]));
    serve(mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("picking a team and then a sprint stores the pick; another team replaces it and drops the sprint", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Payments" }));
    await waitFor(() => expect(at()).toHaveTextContent("/capacity?teamId=3"));
    expect(remembered()).toBe("teamId=3");

    await user.click(await screen.findByRole("combobox", { name: "Sprint" }));
    await user.click(await screen.findByRole("option", { name: /Sprint 11/ }));
    await waitFor(() => expect(at()).toHaveTextContent("/capacity?teamId=3&sprintId=11"));
    expect(remembered()).toBe("teamId=3&sprintId=11");

    // Another team drops the sprint, in the URL and in the memory.
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Identity" }));
    await waitFor(() => expect(remembered()).toBe("teamId=4"));
  });

  test("a bare visit restores the remembered team and sprint, each by replace once its list is in", async () => {
    localStorage.setItem(STORED, JSON.stringify("teamId=3&sprintId=11"));
    renderPage();
    await waitFor(() => expect(at()).toHaveTextContent("/capacity?teamId=3&sprintId=11"));
    expect(await screen.findByText("This calendar: 3.6 MD")).toBeInTheDocument();
  });

  /** The same server, except that the calendar read of `sprintId` is refused with `status`. */
  function refuseCalendar(sprintId: number, status: number) {
    const base = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      url === `/api/v1/teams/3/sprint-calendars/${sprintId}`
        ? Promise.resolve(jsonResponse(status, { title: "Not Found", status }))
        : base(url, init),
    );
  }

  test("a remembered sprint older than the picker's first page is still restored: the calendar read answers for it", async () => {
    localStorage.setItem(STORED, JSON.stringify("teamId=3&sprintId=5"));
    renderPage();
    await waitFor(() => expect(at()).toHaveTextContent("/capacity?teamId=3&sprintId=5"));
    expect(await screen.findByText("This calendar: 3.6 MD")).toBeInTheDocument();
    expect(remembered()).toBe("teamId=3&sprintId=5");
    expect(mockFetch.mock.calls.some(([url]) => url === "/api/v1/teams/3/sprint-calendars/5")).toBe(true);
  });

  test("a remembered sprint the server refuses (gone) is dropped with no error shown: the team stays, in the URL and the memory", async () => {
    refuseCalendar(99, 404);
    localStorage.setItem(STORED, JSON.stringify("teamId=3&sprintId=99"));
    renderPage();
    await waitFor(() => expect(at()).toHaveTextContent(/^\/capacity\?teamId=3$/));
    await waitFor(() => expect(remembered()).toBe("teamId=3"));
    await screen.findByRole("textbox", { name: "Default capacity (MD)" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("the same refusal for a sprint from a link is shown, not dropped", async () => {
    refuseCalendar(99, 404);
    localStorage.setItem(STORED, JSON.stringify("teamId=3&sprintId=11"));
    renderPage("/capacity?teamId=3&sprintId=99");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(at()).toHaveTextContent("/capacity?teamId=3&sprintId=99");
    expect(remembered()).toBe("teamId=3&sprintId=11");
  });

  test("a team that is gone leaves the page on its empty picker state", async () => {
    localStorage.setItem(STORED, JSON.stringify("teamId=99&sprintId=11"));
    renderPage();
    expect(await screen.findByText("Pick a team and a sprint to see its capacity calendar.")).toBeInTheDocument();
    expect(at()).toHaveTextContent(/^\/capacity$/);
  });

  test("a link that names a team wins and does not overwrite the memory", async () => {
    localStorage.setItem(STORED, JSON.stringify("teamId=4"));
    renderPage("/capacity?teamId=3");
    await screen.findByRole("textbox", { name: "Default capacity (MD)" });
    expect(at()).toHaveTextContent("/capacity?teamId=3");
    expect(remembered()).toBe("teamId=4");
  });

  test("corrupt storage is a plain bare visit", async () => {
    localStorage.setItem(STORED, "{nope");
    renderPage();
    expect(await screen.findByText("Pick a team and a sprint to see its capacity calendar.")).toBeInTheDocument();
  });
});
