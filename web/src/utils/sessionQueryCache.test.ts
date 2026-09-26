import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { getSessionSnapshot, persistRefreshedSession, persistSession } from "../api/session";
import type { components } from "../api/schema";
import { bindSessionQueryCache } from "./sessionQueryCache";

type LoginSuccess = components["schemas"]["LoginResponse"];

const session = (userId: number, token: string): LoginSuccess => ({
  token,
  expiresAt: 1,
  refreshToken: `refresh-${token}`,
  refreshExpiresAt: 2,
  userId,
  roles: [],
  disabledFeatures: [],
  language: "en",
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
};

describe("session query cache", () => {
  let queryClient: QueryClient;
  let unbind: () => void;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity } },
    });
    unbind = bindSessionQueryCache(queryClient);
  });

  afterEach(() => {
    unbind();
    queryClient.clear();
  });

  test("a replacement login removes the previous caller's cached notifications", () => {
    persistSession(session(1, "token-a"));
    queryClient.setQueryData(["notifications", "unread"], { total: 3 });
    queryClient.setQueryData(["notifications", "list", 1], { items: [{ id: 41 }], total: 1 });
    const unreadObserver = new QueryObserver(queryClient, {
      queryKey: ["notifications", "unread"],
      queryFn: async () => ({ total: 0 }),
      enabled: false,
    });
    const unsubscribe = unreadObserver.subscribe(() => undefined);

    persistSession(session(2, "token-b"));

    expect(queryClient.getQueryData(["notifications", "unread"])).toBeUndefined();
    expect(queryClient.getQueryData(["notifications", "list", 1])).toBeUndefined();
    expect(unreadObserver.getCurrentResult().data).toBeUndefined();
    unsubscribe();
  });

  test("a late response from the previous caller cannot repopulate the replacement session", async () => {
    persistSession(session(1, "token-a"));
    const response = deferred<{ total: number }>();
    const pending = queryClient.fetchQuery({
      queryKey: ["notifications", "unread"],
      queryFn: () => response.promise,
    });

    persistSession(session(2, "token-b"));
    response.resolve({ total: 7 });
    await pending.catch(() => undefined);

    expect(queryClient.getQueryData(["notifications", "unread"])).toBeUndefined();
  });

  test("another tab replacing the stored identity clears caller-scoped data", () => {
    persistSession(session(1, "token-a"));
    queryClient.setQueryData(["notifications", "unread"], { total: 2 });
    localStorage.setItem("flow.auth.token", "token-b");
    localStorage.setItem("flow.auth.sessionIdentity", "external-session-b");

    window.dispatchEvent(new StorageEvent("storage", {
      key: "flow.auth.sessionIdentity",
      newValue: "external-session-b",
      storageArea: localStorage,
    }));

    expect(queryClient.getQueryData(["notifications", "unread"])).toBeUndefined();
  });

  test("silent refresh preserves cache within the same logical session", () => {
    persistSession(session(1, "token-a"));
    const beforeRefresh = getSessionSnapshot();
    queryClient.setQueryData(["notifications", "unread"], { total: 4 });

    expect(persistRefreshedSession(session(1, "token-a2"), beforeRefresh)).not.toBeNull();

    expect(queryClient.getQueryData(["notifications", "unread"])).toEqual({ total: 4 });
  });
});
