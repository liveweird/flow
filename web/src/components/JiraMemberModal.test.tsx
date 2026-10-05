import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, cleanup, screen, waitFor } from "@testing-library/react";
import JiraMemberModal from "./JiraMemberModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const NOTHING_FOUND = "No matching people";
const LOADER = "Loading people…";

function renderModal(options: { timeZone?: string | null; exclude?: string[] } = {}) {
  return renderWithProviders(
    <JiraMemberModal
      teamId={5}
      timeZone={options.timeZone === undefined ? "Europe/Warsaw" : options.timeZone}
      excludeAccountIds={new Set(options.exclude ?? [])}
      onClose={() => {}}
      onCreated={async () => {}}
    />,
  );
}

describe("JiraMemberModal — the person picker's load states", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a failed directory load shows the inline alert, never the 'nothing found' message", async () => {
    mockFetch.mockRejectedValue(new TypeError("Failed to fetch"));
    const user = userEvent.setup();
    renderModal();

    expect(await screen.findByRole("alert")).toHaveTextContent("Network error. Check your connection and try again.");
    await user.click(screen.getByRole("combobox", { name: "Person" }));
    expect(screen.queryByText(NOTHING_FOUND)).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Person" })).toHaveAttribute("aria-busy", "false");
  });

  test("a non-2xx directory answer is status-tagged in the alert", async () => {
    mockFetch.mockResolvedValue(jsonResponse(502, { title: "Bad Gateway", status: 502 }));
    renderModal();

    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (502)");
  });

  test("while the directory is pending the picker is busy with a loader, and says nothing about matches", async () => {
    mockFetch.mockReturnValue(new Promise<Response>(() => {}));
    const user = userEvent.setup();
    renderModal();

    const person = await screen.findByRole("combobox", { name: "Person" });
    expect(person).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("status", { name: LOADER })).toBeInTheDocument();
    await user.click(person);
    expect(screen.queryByText(NOTHING_FOUND)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("a completed empty search still says 'No matching people' — no alert, no loader", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    const user = userEvent.setup();
    renderModal();

    const person = await screen.findByRole("combobox", { name: "Person" });
    await waitFor(() => expect(person).toHaveAttribute("aria-busy", "false"));
    await user.click(person);
    expect(await screen.findByText(NOTHING_FOUND)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("status", { name: LOADER })).not.toBeInTheDocument();
  });

  test("error, then a new term: the alert goes and the loader shows until the answer lands", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let resolveSearch: (r: Response) => void = () => {};
      mockFetch
        .mockRejectedValueOnce(new TypeError("Failed to fetch"))
        .mockReturnValueOnce(new Promise<Response>((resolve) => (resolveSearch = resolve)));
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderModal();

      expect(await screen.findByRole("alert")).toBeInTheDocument();
      const person = screen.getByRole("combobox", { name: "Person" });
      await user.type(person, "ali");
      // Inside the 300 ms debounce window nothing has been asked yet: the old failure still shows.
      expect(screen.getByRole("alert")).toBeInTheDocument();

      await act(() => vi.advanceTimersByTimeAsync(300));
      await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
      expect(screen.getByRole("status", { name: LOADER })).toBeInTheDocument();
      expect(person).toHaveAttribute("aria-busy", "true");
      expect(mockFetch).toHaveBeenCalledTimes(2);
      expect(String(mockFetch.mock.calls[1][0])).toContain("q=ali");

      await act(async () => {
        resolveSearch(jsonResponse(200, { items: [{ accountId: "acc-1", displayName: "Alice Admin" }], page: 1, pageSize: 20, total: 1 }));
      });
      await waitFor(() => expect(screen.queryByRole("status", { name: LOADER })).not.toBeInTheDocument());
      expect(person).toHaveAttribute("aria-busy", "false");
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("JiraMemberModal — server-side search", () => {
  let mockFetch: FetchMock;

  const FIRST_PAGE = Array.from({ length: 20 }, (_, i) => ({
    accountId: `acc-${String(i).padStart(3, "0")}`,
    displayName: `Person ${String(i).padStart(3, "0")}`,
  }));
  const BEYOND = { accountId: "acc-150", displayName: "Zed Zulu" };

  /** The directory has 150 people; only a `q` naming (or matching the account id of) Zed finds the one beyond the first page. */
  function serveDirectory() {
    mockFetch.mockImplementation((url: string) => {
      const params = new URL(url, "http://localhost").searchParams;
      const q = params.get("q")?.toLowerCase();
      if (q === undefined) return Promise.resolve(jsonResponse(200, { items: FIRST_PAGE, page: 1, pageSize: 20, total: 150 }));
      const hit = "zed zulu".includes(q) || "acc-150".includes(q);
      return Promise.resolve(jsonResponse(200, { items: hit ? [BEYOND] : [], page: 1, pageSize: 20, total: hit ? 1 : 0 }));
    });
  }

  function directoryQs() {
    return mockFetch.mock.calls.map(([u]) => new URL(String(u), "http://localhost").searchParams);
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("typing is debounced: one request after 300 ms of quiet, carrying q, sorted and scoped", async () => {
    serveDirectory();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderModal();

    await screen.findByText(/Showing the first 20 of 150 people/);
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(directoryQs()[0].has("q")).toBe(false);

    await user.type(screen.getByRole("combobox", { name: "Person" }), "zed");
    // Well inside the 300 ms window (the fake clock also drifts with real time, so no razor-edge 299/300 split).
    await act(() => vi.advanceTimersByTimeAsync(150));
    expect(mockFetch).toHaveBeenCalledTimes(1);

    await act(() => vi.advanceTimersByTimeAsync(300));
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(2));
    const search = directoryQs()[1];
    expect(search.get("q")).toBe("zed");
    expect(search.get("scope")).toBe("SITE");
    expect(search.get("sort")).toBe("displayName");
    expect(search.get("pageSize")).toBe("20");
  });

  test("a person beyond the first page is found by name, or by account id", async () => {
    serveDirectory();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderModal();

    const person = await screen.findByRole("combobox", { name: "Person" });
    await screen.findByText(/Showing the first 20 of 150 people/);
    expect(screen.queryByRole("option", { name: /Zed Zulu/ })).not.toBeInTheDocument();

    await user.type(person, "zed zu");
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(await screen.findByRole("option", { name: "Zed Zulu (acc-150)" })).toBeInTheDocument();
    // The result is complete for this term: no "showing first" hint.
    expect(screen.queryByText(/Showing the first/)).not.toBeInTheDocument();

    await user.clear(person);
    await user.type(person, "acc-150");
    await act(() => vi.advanceTimersByTimeAsync(300));
    await waitFor(() => expect(directoryQs().some((p) => p.get("q") === "acc-150")).toBe(true));
    expect(await screen.findByRole("option", { name: "Zed Zulu (acc-150)" })).toBeInTheDocument();
  });

  test("the picked person keeps its label while later searches replace the options", async () => {
    serveDirectory();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderModal();

    const person = await screen.findByRole("combobox", { name: "Person" });
    await user.type(person, "zed");
    await act(() => vi.advanceTimersByTimeAsync(300));
    await user.click(await screen.findByRole("option", { name: "Zed Zulu (acc-150)" }));
    expect(person).toHaveValue("Zed Zulu (acc-150)");

    // The label Mantine put into the box is not a search term: the follow-up request is the plain, unfiltered list…
    await act(() => vi.advanceTimersByTimeAsync(400));
    expect(directoryQs().every((p) => p.get("q") !== "Zed Zulu (acc-150)")).toBe(true);

    // …a LATER search really goes out and replaces the options with a set that no longer holds Zed (the answer is empty)…
    await user.clear(person);
    await user.type(person, "nobody");
    await act(() => vi.advanceTimersByTimeAsync(300));
    await waitFor(() => expect(directoryQs().some((p) => p.get("q") === "nobody")).toBe(true));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Person" })).toHaveAttribute("aria-busy", "false"));

    // …and the selection still shows under its own label once the box loses focus.
    await user.tab();
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Person" })).toHaveValue("Zed Zulu (acc-150)"));
  });

  test("the 'showing the first N' count is taken after the exclusion filter, and an all-excluded page never contradicts 'No matching people'", async () => {
    serveDirectory();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderModal({ exclude: ["acc-000", "acc-001"] });

    // 20 rows came back, 2 are current members: 18 are shown of 150.
    await screen.findByText(/Showing the first 18 of 150 people/);
    expect(screen.queryByText(/Showing the first 20 of/)).not.toBeInTheDocument();
    cleanup();

    renderModal({ exclude: FIRST_PAGE.map((p) => p.accountId) });
    const person = await screen.findByRole("combobox", { name: "Person" });
    await waitFor(() => expect(person).toHaveAttribute("aria-busy", "false"));
    await user.click(person);
    expect(await screen.findByText(NOTHING_FOUND)).toBeInTheDocument();
    expect(screen.queryByText(/Showing the first/)).not.toBeInTheDocument();
  });

  test("submit waits for the metrics zone", async () => {
    serveDirectory();
    renderModal({ timeZone: null });

    expect(await screen.findByRole("button", { name: "Create" })).toBeDisabled();
  });

  test("a failed search shows the inline alert and a retry term clears it", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: FIRST_PAGE, page: 1, pageSize: 20, total: 150 }))
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(jsonResponse(200, { items: [BEYOND], page: 1, pageSize: 20, total: 1 }));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderModal();

    const person = await screen.findByRole("combobox", { name: "Person" });
    await screen.findByText(/Showing the first 20 of 150 people/);
    await user.type(person, "ze");
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(await screen.findByRole("alert")).toHaveTextContent("Network error");
    expect(screen.queryByText(NOTHING_FOUND)).not.toBeInTheDocument();

    await user.type(person, "d");
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(await screen.findByRole("option", { name: "Zed Zulu (acc-150)" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
