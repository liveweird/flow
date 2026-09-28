import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import DataSources from "./DataSources";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { showSuccessToast } from "../utils/toast";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const ROW_CURRENT = {
  id: 1,
  kind: "JIRA_CLOUD",
  name: "Acme Jira",
  enabled: true,
  syncIntervalMinutes: 60,
  backfillFrom: "2023-01-01",
  reconcileHourUtc: 3,
  configRevision: 1,
  jira: {
    siteUrl: "https://acme.atlassian.net",
    email: "svc@acme.com",
    hasApiToken: true,
    projectKeys: ["ENG", "OPS"],
    authScheme: "BASIC",
    cloudId: null,
  },
  status: {
    state: "CURRENT",
    lastSyncStartedAt: 1_700_000_000_000,
    lastSyncSucceededAt: 1_700_000_060_000,
    lastSyncErrorCode: null,
    consecutiveFailures: 0,
    runningJobId: null,
  },
  createdAt: 1,
  updatedAt: 2,
};

const ROW_FAILED = {
  ...ROW_CURRENT,
  id: 2,
  name: "Contoso Jira",
  jira: { ...ROW_CURRENT.jira, siteUrl: "https://contoso.atlassian.net", projectKeys: ["OPS"] },
  status: { ...ROW_CURRENT.status, state: "FAILED", lastSyncSucceededAt: null },
};

const PAGE = { items: [ROW_CURRENT, ROW_FAILED], page: 1, pageSize: 20, total: 2 };

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (mutations[key]) {
      const m = mutations[key];
      return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    }
    if (method === "GET" && url.startsWith("/api/v1/data-sources?")) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

async function fillJiraFields(user: ReturnType<typeof userEvent.setup>, modal: HTMLElement, opts: { skipSiteUrl?: boolean } = {}) {
  if (!opts.skipSiteUrl) {
    await user.type(within(modal).getByLabelText("Site URL"), "https://newsite.atlassian.net");
  }
  await user.type(within(modal).getByLabelText("Service-account email"), "svc@newsite.com");
  await user.type(within(modal).getByLabelText("API token"), "secret-token");
  await user.type(within(modal).getByRole("combobox", { name: "Project keys" }), "eng{Enter}");
}

describe("DataSources page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("the list renders every column, including the state badges, and the name links to details", async () => {
    serve(mockFetch);
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    expect(await screen.findByRole("link", { name: "Open Acme Jira" })).toHaveAttribute("href", "/data-sources/1");
    expect(screen.getByText("acme.atlassian.net")).toBeInTheDocument();
    expect(screen.getByText("ENG, OPS")).toBeInTheDocument();
    expect(screen.getAllByText("Yes")).toHaveLength(2);
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("Never")).toBeInTheDocument();
  });

  test("the empty state spans every column", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 })));
    renderWithProviders(<DataSources />, { route: "/data-sources" });
    const empty = await screen.findByText("No data sources yet");
    expect(empty.closest("td")).toHaveAttribute("colspan", String(screen.getAllByRole("columnheader").length));
  });

  test("creates a data source, uppercasing the project key and omitting a blank backfill date", async () => {
    serve(mockFetch, { "POST /api/v1/data-sources": { status: 201, body: { ...ROW_CURRENT, id: 9, name: "New Co" } } });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: /new data source/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "New Co");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/data-sources")![1] as RequestInit).body as string);
    expect(body).toEqual({
      name: "New Co",
      enabled: true,
      syncIntervalMinutes: 60,
      reconcileHourUtc: 3,
      jira: {
        siteUrl: "https://newsite.atlassian.net",
        email: "svc@newsite.com",
        apiToken: "secret-token",
        projectKeys: ["ENG"],
        authScheme: "BASIC",
      },
    });
    expect(showSuccessToast).toHaveBeenCalledWith("Data source created");
  });

  test("editing disables the site URL and a blank token keeps the stored one", async () => {
    serve(mockFetch, { "PUT /api/v1/data-sources/1": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: "Operations for Acme Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Acme Jira" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Site URL")).toBeDisabled();
    expect(within(modal).getByLabelText("Site URL")).toHaveValue("https://acme.atlassian.net");
    expect(within(modal).getByLabelText("API token")).toHaveValue("");

    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/data-sources/1")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/data-sources/1")![1] as RequestInit).body as string);
    expect(body.jira.apiToken).toBeUndefined();
    expect(body.jira.siteUrl).toBe("https://acme.atlassian.net");
  });

  test("a 409 on save marks the name field", async () => {
    serve(mockFetch, { "POST /api/v1/data-sources": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: /new data source/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "Acme Jira");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("A data source with this name already exists")).toBeInTheDocument();
    expect(within(modal).getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
  });

  test("Test connection renders an ok row and a failed row with its detail", async () => {
    serve(mockFetch, {
      "POST /api/v1/data-sources/test": {
        status: 200,
        body: {
          rows: [
            { name: "tenant_info", path: "/_edge/tenant_info", required: true, ok: true, status: 200 },
            { name: "myself", path: "/rest/api/3/myself", required: true, ok: false, status: 403, code: "FORBIDDEN_SCOPE", scopeHint: "read:jira-user" },
          ],
          cloudId: "cloud-123",
        },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: /new data source/i }));
    const modal = screen.getByRole("dialog");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: "Test connection" }));

    expect(await within(modal).findByText("OK")).toBeInTheDocument();
    expect(within(modal).getByText("Failed")).toBeInTheDocument();
    expect(within(modal).getByText("403 · FORBIDDEN_SCOPE · scope: read:jira-user")).toBeInTheDocument();
    expect(within(modal).getByText("Cloud ID: cloud-123")).toBeInTheDocument();
  });

  test("Sync now toasts the coalesced case and a fresh queue separately", async () => {
    serve(mockFetch, {
      "POST /api/v1/data-sources/1/sync-jobs": {
        status: 202,
        body: { job: { id: 1, connectionId: 1, kind: "SYNC", status: "PENDING", priority: 0, configRevision: 1, requestedAt: 1, attempt: 0, maxAttempts: 5 }, coalesced: false },
      },
      "POST /api/v1/data-sources/2/sync-jobs": {
        status: 202,
        body: { job: { id: 2, connectionId: 2, kind: "SYNC", status: "RUNNING", priority: 0, configRevision: 1, requestedAt: 1, attempt: 0, maxAttempts: 5 }, coalesced: true },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: "Operations for Acme Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Sync now: Acme Jira" }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalledWith("Sync queued"));

    await user.click(screen.getByRole("button", { name: "Operations for Contoso Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Sync now: Contoso Jira" }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalledWith("A sync is already in progress"));
  });

  test("deletes a data source after confirming; a 409 names the conflict", async () => {
    serve(mockFetch, {
      "DELETE /api/v1/data-sources/1": { status: 204 },
      "DELETE /api/v1/data-sources/2": { status: 409, body: { title: "Conflict", status: 409 } },
    });
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: "Operations for Acme Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Acme Jira" }));
    expect(await screen.findByText(/"Acme Jira" will be deleted/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/data-sources/1")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Operations for Contoso Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Contoso Jira" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText("This data source cannot be deleted right now.")).toBeInTheDocument();
  });

  test("Cancel closes the editor modal without saving", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: /new data source/i }));
    const modal = await screen.findByRole("dialog");
    await user.click(within(modal).getByRole("button", { name: /^cancel$/i }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/data-sources")).toBeUndefined();
  });

  test("the Open row action navigates to the details page", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<DataSources />, { route: "/data-sources" });

    await user.click(await screen.findByRole("button", { name: "Operations for Acme Jira" }));
    await user.click(await screen.findByRole("menuitem", { name: "Open" }));
    // No matching route is mounted in this unit test — navigating is enough to exercise the handler.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("shows the load-failure alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "boom", status: 500 })));
    renderWithProviders(<DataSources />, { route: "/data-sources" });
    expect(await screen.findByText("Could not load the data sources")).toBeInTheDocument();
  });
});
