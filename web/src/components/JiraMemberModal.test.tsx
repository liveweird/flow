import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor } from "@testing-library/react";
import JiraMemberModal from "./JiraMemberModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const NOTHING_FOUND = "No matching people";
const LOADER = "Loading people…";

function renderModal() {
  return renderWithProviders(
    <JiraMemberModal teamId={5} excludeAccountIds={new Set()} onClose={() => {}} onCreated={async () => {}} />,
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
