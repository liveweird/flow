import { afterEach, describe, expect, test, vi } from "vitest";
import type { ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useDeleteConfirm } from "./useDeleteConfirm";
import { showSuccessToast } from "../utils/toast";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

type Row = { id: number; name: string };
const ROW: Row = { id: 7, name: "Bob" };

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useDeleteConfirm", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  test("requestDelete opens the modal with the target; cancelDelete closes and clears it", () => {
    const { result } = renderHook(
      () =>
        useDeleteConfirm<Row>({
          mutationFn: vi.fn().mockResolvedValue(undefined),
          onSuccess: vi.fn(),
        }),
      { wrapper: createWrapper() },
    );
    expect(result.current.opened).toBe(false);
    expect(result.current.target).toBeNull();

    act(() => result.current.requestDelete(ROW));
    expect(result.current.opened).toBe(true);
    expect(result.current.target).toEqual(ROW);

    act(() => result.current.cancelDelete());
    expect(result.current.opened).toBe(false);
    expect(result.current.target).toBeNull();
  });

  test("confirmDelete runs mutationFn, then closes, toasts, and calls onSuccess with the row", async () => {
    const mutationFn = vi.fn().mockResolvedValue(undefined);
    const onSuccess = vi.fn();
    const { result } = renderHook(
      () => useDeleteConfirm<Row>({ mutationFn, onSuccess, successMessage: "User deleted" }),
      { wrapper: createWrapper() },
    );

    act(() => result.current.requestDelete(ROW));
    act(() => result.current.confirmDelete());

    await waitFor(() => expect(result.current.opened).toBe(false));
    expect(mutationFn.mock.calls[0][0]).toEqual(ROW);
    expect(onSuccess).toHaveBeenCalledWith(ROW);
    expect(showSuccessToast).toHaveBeenCalledWith("User deleted");
    expect(result.current.target).toBeNull();
  });

  test("without a successMessage no toast is shown", async () => {
    const { result } = renderHook(
      () =>
        useDeleteConfirm<Row>({
          mutationFn: vi.fn().mockResolvedValue(undefined),
          onSuccess: vi.fn(),
        }),
      { wrapper: createWrapper() },
    );

    act(() => result.current.requestDelete(ROW));
    act(() => result.current.confirmDelete());

    await waitFor(() => expect(result.current.opened).toBe(false));
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("a failed mutation keeps the modal open with the target and surfaces the error", async () => {
    const onSuccess = vi.fn();
    const { result } = renderHook(
      () =>
        useDeleteConfirm<Row>({
          mutationFn: vi.fn().mockRejectedValue(new Error("boom")),
          onSuccess,
          successMessage: "User deleted",
        }),
      { wrapper: createWrapper() },
    );

    act(() => result.current.requestDelete(ROW));
    act(() => result.current.confirmDelete());

    await waitFor(() => expect(result.current.mutation.isError).toBe(true));
    expect(result.current.opened).toBe(true);
    expect(result.current.target).toEqual(ROW);
    expect(onSuccess).not.toHaveBeenCalled();
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("confirmDelete without a target is a no-op", () => {
    const mutationFn = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(
      () => useDeleteConfirm<Row>({ mutationFn, onSuccess: vi.fn() }),
      { wrapper: createWrapper() },
    );
    act(() => result.current.confirmDelete());
    expect(mutationFn).not.toHaveBeenCalled();
  });

  test("cancelDelete is ignored while the mutation is pending", async () => {
    const { result } = renderHook(
      () =>
        useDeleteConfirm<Row>({
          mutationFn: () => new Promise(() => {}), // never settles
          onSuccess: vi.fn(),
        }),
      { wrapper: createWrapper() },
    );

    act(() => result.current.requestDelete(ROW));
    act(() => result.current.confirmDelete());
    await waitFor(() => expect(result.current.mutation.isPending).toBe(true));

    act(() => result.current.cancelDelete());
    expect(result.current.opened).toBe(true);
    expect(result.current.target).toEqual(ROW);
  });
});
