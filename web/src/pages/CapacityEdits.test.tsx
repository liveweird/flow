import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor, within } from "@testing-library/react";
import { calendarGets, putCalls, refetchCalendar, renderPage, serve, type Api, type FetchMock } from "../test/capacityKit";

describe("Capacity page: editing", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["USER"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("unsaved edits are never lost silently", () => {
    const friday = () => screen.getByRole("textbox", { name: "Availability of Ada Lovelace on 2026-03-06" });
    const editFriday = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.clear(friday());
      await user.type(friday(), "0.5");
    };

    test("a refetch that changes nothing keeps the draft", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      await editFriday(user);
      const before = calendarGets(mockFetch);
      await refetchCalendar(queryClient);
      expect(calendarGets(mockFetch)).toBeGreaterThan(before);
      expect(friday()).toHaveValue("0.5");
      expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument();
    });

    test("a refetch that brings a changed document keeps a dirty draft and says so; Reset loads the latest", async () => {
      const api: Api = {};
      serve(mockFetch, api);
      const user = userEvent.setup();
      const queryClient = renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      await editFriday(user);
      api.calendar = { teamCoefficient: 1.5, updatedAt: 2000 };
      await refetchCalendar(queryClient);
      expect(await screen.findByText("This calendar changed elsewhere — Reset to load the latest. Saving now overwrites it.")).toBeInTheDocument();
      // The edit is still there, and the team coefficient is still the one the draft started from.
      expect(friday()).toHaveValue("0.5");
      expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1");
      await user.click(screen.getByRole("button", { name: "Reset" }));
      expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1.5");
      expect(friday()).toHaveValue("1");
    });

    test("a refetch that brings a changed document is adopted by a clean draft", async () => {
      const api: Api = {};
      serve(mockFetch, api);
      const queryClient = renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      api.calendar = { teamCoefficient: 1.5, updatedAt: 2000 };
      await refetchCalendar(queryClient);
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1.5"));
      expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument();
    });

    test("after a successful save the draft is the baseline: clean, no warning, and a later change is adopted", async () => {
      const api: Api = {};
      serve(mockFetch, api);
      const user = userEvent.setup();
      const queryClient = renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      const coefficient = screen.getByRole("textbox", { name: "Team coefficient" });
      await user.clear(coefficient);
      await user.type(coefficient, "1.5");
      expect(screen.getByRole("button", { name: "Reset" })).toBeEnabled();
      // The server stores what was sent: the reload brings the same document back.
      api.calendar = { teamCoefficient: 1.5, updatedAt: 2000 };
      await user.click(screen.getByRole("button", { name: "Save calendar" }));
      await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
      await waitFor(() => expect(screen.getByRole("button", { name: "Reset" })).toBeDisabled());
      expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("1.5");
      // Clean again, so a document someone else saves next is adopted.
      api.calendar = { teamCoefficient: 0.5, updatedAt: 3000 };
      await refetchCalendar(queryClient);
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Team coefficient" })).toHaveValue("0.5"));
    });

    test("a dirty draft makes a picker ask first: Keep editing stays, Discard goes", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      await editFriday(user);
      await user.click(screen.getByRole("combobox", { name: "Sprint" }));
      await user.click(await screen.findByRole("option", { name: /Sprint 12/ }));
      const dialog = await screen.findByRole("dialog", { name: "Discard changes?" });
      expect(within(dialog).getByText("Your unsaved changes will be lost.")).toBeInTheDocument();
      await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
      await waitFor(() => expect(screen.queryByRole("dialog", { name: "Discard changes?" })).not.toBeInTheDocument());
      expect(screen.getByTestId("at")).toHaveTextContent("sprintId=11");
      expect(friday()).toHaveValue("0.5");

      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Identity" }));
      await user.click(within(await screen.findByRole("dialog", { name: "Discard changes?" })).getByRole("button", { name: "Discard" }));
      await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent(/^\/capacity\?teamId=4$/));
    });

    test("an unsaved team default guards the pickers too, and a saved one does not", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/capacity?teamId=3");
      const field = await screen.findByRole("textbox", { name: "Default capacity (MD)" });
      await user.type(field, "1");
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Identity" }));
      await user.click(within(await screen.findByRole("dialog", { name: "Discard changes?" })).getByRole("button", { name: "Keep editing" }));
      await waitFor(() => expect(screen.queryByRole("dialog", { name: "Discard changes?" })).not.toBeInTheDocument());
      expect(screen.getByTestId("at")).toHaveTextContent("teamId=3");
      // Saved: the figure is now the baseline, so leaving needs no confirmation.
      await user.click(screen.getByRole("button", { name: "Save default" }));
      await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Identity" }));
      await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent(/^\/capacity\?teamId=4$/));
      expect(screen.queryByRole("dialog", { name: "Discard changes?" })).not.toBeInTheDocument();
    });

    test("the browser's own reload/close prompt appears only while something is unsaved", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      const prevented = () => {
        const event = new Event("beforeunload", { cancelable: true });
        window.dispatchEvent(event);
        return event.defaultPrevented;
      };
      expect(prevented()).toBe(false);
      await editFriday(user);
      expect(prevented()).toBe(true);
      await user.click(screen.getByRole("button", { name: "Reset" }));
      expect(prevented()).toBe(false);
    });
  });

  describe("Capacity in reports after a save", () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    test("the calendar is re-read, with growing gaps, until the derived figure moves", async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const api: Api = {};
      serve(mockFetch, api);
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("Capacity in reports: 20 MD (the team default)");
      await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
      await user.click(screen.getByRole("button", { name: "Save calendar" }));
      await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
      const afterSave = await waitFor(() => {
        const n = calendarGets(mockFetch);
        expect(n).toBeGreaterThan(1);
        return n;
      });
      // The first poll is not due yet; once it is, the re-derive has not landed, so it asks again.
      await act(() => vi.advanceTimersByTimeAsync(1600));
      await waitFor(() => expect(calendarGets(mockFetch)).toBe(afterSave + 1));
      // The re-derive lands: the next poll shows it and the polling stops.
      api.calendar = { derivedCapacityMd: 5.4, derivedCapacitySource: "CALENDAR" };
      await act(() => vi.advanceTimersByTimeAsync(3100));
      expect(await screen.findByText("Capacity in reports: 5.4 MD (the sprint's calendar)")).toBeInTheDocument();
      const settled = calendarGets(mockFetch);
      await act(() => vi.advanceTimersByTimeAsync(30_000));
      expect(calendarGets(mockFetch)).toBe(settled);
    });

    test("it gives up after a few tries when the figure never moves", async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      serve(mockFetch);
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage("/capacity?teamId=3&sprintId=11");
      await screen.findByText("This calendar: 3.6 MD");
      await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
      await user.click(screen.getByRole("button", { name: "Save calendar" }));
      await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
      const afterSave = await waitFor(() => {
        const n = calendarGets(mockFetch);
        expect(n).toBeGreaterThan(1);
        return n;
      });
      // Four polls at most (1.5 s, 3 s, 6 s, 12 s), then it stops for good.
      await act(() => vi.advanceTimersByTimeAsync(30_000));
      expect(calendarGets(mockFetch)).toBe(afterSave + 4);
      await act(() => vi.advanceTimersByTimeAsync(60_000));
      expect(calendarGets(mockFetch)).toBe(afterSave + 4);
    });
  });
});
