import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import TeamCapacityDefaultCard from "./TeamCapacityDefaultCard";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { jsonResponse } from "../test/http";
import type { TeamCapacity } from "../api/capacity";

const toast = vi.hoisted(() => ({ show: vi.fn() }));
vi.mock("../utils/toast", () => ({ showSuccessToast: toast.show }));

const capacity = (over: Partial<TeamCapacity> = {}): TeamCapacity => ({ teamId: 3, defaultCapacityMd: 20, canEdit: true, updatedAt: 1, ...over });
const field = () => screen.getByRole("textbox", { name: "Default capacity (MD)" });
const saveButton = () => screen.getByRole("button", { name: "Save default" });

describe("TeamCapacityDefaultCard", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const onDirtyChange = vi.fn();

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    onDirtyChange.mockClear();
    toast.show.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const puts = () => mockFetch.mock.calls.filter(([, init]) => init?.method === "PUT").map(([url, init]) => [url, JSON.parse(init.body)]);

  test("shows the stored figure, clean; editing marks it dirty and saving PUTs the figure, toasts and settles the dirty flag", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    expect(field()).toHaveValue("20");
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
    await user.clear(field());
    await user.type(field(), "12.5");
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true));
    await user.click(saveButton());
    await waitFor(() => expect(toast.show).toHaveBeenCalledWith("Saved — reports re-derive shortly"));
    expect(puts()).toEqual([["/api/v1/teams/3/capacity", { defaultCapacityMd: 12.5 }]]);
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(false));
  });

  test("a blank field clears the default (an explicit null)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    await user.clear(field());
    await user.click(saveButton());
    await waitFor(() => expect(puts()).toEqual([["/api/v1/teams/3/capacity", { defaultCapacityMd: null }]]));
  });

  test("a figure out of range is flagged and cannot be saved", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    await user.clear(field());
    await user.type(field(), "1000000");
    expect(await screen.findByText("Enter a figure from 0 to 999999.99, or leave it blank for none.")).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    expect(puts()).toEqual([]);
  });

  test("a caller who cannot edit sees the read-only note and a locked field and button", () => {
    renderWithProviders(<TeamCapacityDefaultCard capacity={capacity({ canEdit: false })} onDirtyChange={onDirtyChange} />);
    expect(screen.getByText("Only the team's manager or an administrator can edit the team's capacity.")).toBeInTheDocument();
    expect(field()).toBeDisabled();
    expect(saveButton()).toBeDisabled();
  });

  test.each([
    [403, "You can no longer edit this team's capacity."],
    [404, "This team or sprint no longer exists."],
    [400, "The default capacity was rejected. Enter a figure from 0 to 999999.99."],
    [500, "Could not save the capacity."],
  ])("a %i answer to the save is the card's own wording, inline", async (status, message) => {
    mockFetch.mockResolvedValue(jsonResponse(status, { title: "x", status }));
    const user = userEvent.setup();
    renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    await user.type(field(), "1");
    await user.click(saveButton());
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(toast.show).not.toHaveBeenCalled();
    // still dirty: the edit is not thrown away by a failed save
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
  });

  test("a reload with a new stored figure is adopted while the field is untouched, and never replaces what is being typed", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    rerender(<TeamCapacityDefaultCard capacity={capacity({ defaultCapacityMd: 30 })} onDirtyChange={onDirtyChange} />);
    await waitFor(() => expect(field()).toHaveValue("30"));
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);

    await user.clear(field());
    await user.type(field(), "7");
    rerender(<TeamCapacityDefaultCard capacity={capacity({ defaultCapacityMd: null })} onDirtyChange={onDirtyChange} />);
    expect(field()).toHaveValue("7");
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true));
  });

  test("unmounting clears the dirty flag", async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<TeamCapacityDefaultCard capacity={capacity()} onDirtyChange={onDirtyChange} />);
    await user.type(field(), "1");
    unmount();
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
  });
});
