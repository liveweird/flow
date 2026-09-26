import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useResetPassword } from "./useResetPassword";
import type { UserResponse } from "../api/users";
import { jsonResponse } from "../test/http";

type FetchMock = ReturnType<typeof vi.fn>;

const TARGET: UserResponse = { id: 2, name: "Bob Basic", email: "bob@example.com", roles: [], disabledFeatures: [], language: "en" as const };

describe("useResetPassword", () => {
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

  test("request arms the confirm state; clearTarget disarms it", () => {
    const { result } = renderHook(() => useResetPassword());
    expect(result.current.target).toBeNull();

    act(() => result.current.request(TARGET));
    expect(result.current.target).toEqual(TARGET);
    expect(result.current.error).toBeNull();

    act(() => result.current.clearTarget());
    expect(result.current.target).toBeNull();
  });

  test("confirm PUTs a client-generated password and reveals it exactly once", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    const { result } = renderHook(() => useResetPassword());

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/users/2/password");
    expect(init.method).toBe("PUT");
    const body = JSON.parse(init.body as string) as { password: string };
    expect(body.password).toMatch(/^[A-Za-z0-9_-]{16}$/);

    expect(result.current.revealed).toEqual({ email: "bob@example.com", password: body.password });
    expect(result.current.target).toBeNull();
    expect(result.current.pending).toBe(false);
    expect(result.current.error).toBeNull();

    act(() => result.current.closeReveal());
    expect(result.current.revealed).toBeNull();
  });

  test("an API failure surfaces the status-tagged message and keeps the confirm state", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
    const { result } = renderHook(() => useResetPassword());

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).toBe("Reset failed (500)");
    expect(result.current.revealed).toBeNull();
    expect(result.current.target).toEqual(TARGET);
    expect(result.current.pending).toBe(false);
  });

  test("a network failure surfaces the generic message", async () => {
    mockFetch.mockRejectedValue(new Error("offline"));
    const { result } = renderHook(() => useResetPassword());

    act(() => result.current.request(TARGET));
    await act(() => result.current.confirm());

    expect(result.current.error).toBe("Reset failed. Check your connection and try again.");
    expect(result.current.revealed).toBeNull();
  });

  test("confirm without a target is a no-op", async () => {
    const { result } = renderHook(() => useResetPassword());
    await act(() => result.current.confirm());
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
