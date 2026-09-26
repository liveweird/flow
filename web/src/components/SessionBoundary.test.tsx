import { useState } from "react";
import { useMutation, QueryClient } from "@tanstack/react-query";
import { act, fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import { describe, expect, test } from "vitest";
import { getSessionSnapshot, getUserId, persistRefreshedSession, persistSession } from "../api/session";
import type { components } from "../api/schema";
import { bindSessionQueryCache } from "../utils/sessionQueryCache";
import SessionBoundary from "./SessionBoundary";

type LoginSuccess = components["schemas"]["LoginResponse"];

function session(userId: number, token: string): LoginSuccess {
  return {
    token,
    expiresAt: 1,
    refreshToken: `refresh-${token}`,
    refreshExpiresAt: 2,
    userId,
    roles: [],
    disabledFeatures: [],
    language: "en",
  };
}

function ResultPanel({ request, published }: { request: () => Promise<string>; published: () => void }) {
  const [result, setResult] = useState("empty");
  const mutation = useMutation({
    mutationFn: request,
    onSuccess: (value) => {
      published();
      setResult(value);
    },
  });
  return (
    <div>
      <button onClick={() => mutation.mutate()}>Run</button>
      <span>{getUserId()}:{result}</span>
    </div>
  );
}

describe("session boundary", () => {
  test("a late mutation cannot show the old account's result after a cross-tab switch", async () => {
    persistSession(session(1, "token-a"));
    let release!: (value: string) => void;
    const response = new Promise<string>((resolve) => { release = resolve; });
    let publications = 0;
    const client = new QueryClient();
    const unbind = bindSessionQueryCache(client);
    try {
      renderWithProviders(
        <SessionBoundary>
          <ResultPanel request={() => response} published={() => { publications += 1; }} />
        </SessionBoundary>,
        { queryClient: client },
      );
      fireEvent.click(screen.getByRole("button", { name: "Run" }));
      expect(screen.getByText("1:empty")).toBeInTheDocument();

      await act(async () => {
        localStorage.setItem("flow.auth.token", "token-b");
        localStorage.setItem("flow.auth.userId", "2");
        localStorage.setItem("flow.auth.sessionIdentity", "external-session-b");
        window.dispatchEvent(new StorageEvent("storage", {
          key: "flow.auth.sessionIdentity",
          newValue: "external-session-b",
          storageArea: localStorage,
        }));
      });
      expect(screen.getByText("2:empty")).toBeInTheDocument();

      await act(async () => { release("A's private result"); });
      await waitFor(() => expect(publications).toBe(1));
      expect(screen.getByText("2:empty")).toBeInTheDocument();
      expect(screen.queryByText(/A's private result/)).not.toBeInTheDocument();
    } finally {
      unbind();
      client.clear();
    }
  });

  test("a new login remounts local state, while token rotation keeps it", async () => {
    persistSession(session(1, "token-a"));
    renderWithProviders(
      <SessionBoundary>
        <ResultPanel request={async () => "saved for A"} published={() => undefined} />
      </SessionBoundary>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await screen.findByText("1:saved for A");

    const beforeRefresh = getSessionSnapshot();
    await act(async () => {
      expect(persistRefreshedSession(session(1, "token-a2"), beforeRefresh)).not.toBeNull();
    });
    expect(screen.getByText("1:saved for A")).toBeInTheDocument();

    await act(async () => { persistSession(session(2, "token-b")); });
    expect(screen.getByText("2:empty")).toBeInTheDocument();
  });
});
