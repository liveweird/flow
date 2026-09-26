import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceDetails from "./DataSourceDetails";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { showSuccessToast } from "../utils/toast";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const CONNECTION = {
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
    runningJobId: 5,
  },
  createdAt: 1,
  updatedAt: 2,
};

const RUNNING_JOB = {
  id: 5,
  connectionId: 1,
  kind: "SYNC",
  status: "RUNNING",
  priority: 0,
  configRevision: 1,
  requestedAt: 1,
  startedAt: 2,
  attempt: 1,
  maxAttempts: 5,
  currentStream: "ISSUES",
  progress: { issuesFetched: 42 },
};

const COUNTS = {
  rawIssues: 10,
  tombstonedDeleted: 1,
  tombstonedMovedOut: 0,
  changelogs: 5,
  worklogs: 2,
  entitiesByKind: { FIELD: 3 },
  needsProcessing: 1,
};

const CURSORS = [{ stream: "issues", watermarkAt: 1_700_000_000_000, position: "{}", lastCompletedAt: null }];

function statusWith(currentJob: unknown) {
  return { connection: CONNECTION, cursors: CURSORS, counts: COUNTS, lastJobs: { SYNC: RUNNING_JOB }, currentJob };
}

const JOBS_PAGE = { items: [RUNNING_JOB], page: 1, pageSize: 20, total: 1 };

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}, status: unknown = statusWith(RUNNING_JOB)) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (mutations[key]) {
      const m = mutations[key];
      return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    }
    if (method === "GET" && url === "/api/v1/data-sources/1/status") return Promise.resolve(jsonResponse(200, status));
    if (method === "GET" && url.startsWith("/api/v1/data-sources/1/sync-jobs?")) return Promise.resolve(jsonResponse(200, JOBS_PAGE));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

function renderPage(route = "/data-sources/1") {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id" element={<DataSourceDetails />} />
    </Routes>,
    { route },
  );
}

describe("DataSourceDetails page", () => {
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
    vi.useRealTimers();
    localStorage.clear();
  });

  test("renders the connection summary, the current job's progress, cursors and counts", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Acme Jira" })).toBeInTheDocument();
    expect(screen.getByText("https://acme.atlassian.net")).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(screen.getAllByText("Running").length).toBeGreaterThan(0);
    expect(screen.getByText("ISSUES")).toBeInTheDocument();
    expect(screen.getByText("issuesFetched")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("issues")).toBeInTheDocument();
    expect(screen.getByText("10")).toBeInTheDocument();
  });

  test("a data source not found by id shows the not-found message with a way back", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This data source does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to data sources" })).toHaveAttribute("href", "/data-sources");
  });

  test("Sync now and Reconcile now post the right kind and toast", async () => {
    serve(mockFetch, {
      "POST /api/v1/data-sources/1/sync-jobs": { status: 202, body: { job: RUNNING_JOB, coalesced: false } },
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Sync now" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")).toBeDefined());
    let body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")![1] as RequestInit).body as string);
    expect(body).toEqual({ kind: "SYNC" });
    expect(showSuccessToast).toHaveBeenCalledWith("Sync queued");

    mockFetch.mockClear();
    serve(mockFetch, {
      "POST /api/v1/data-sources/1/sync-jobs": { status: 202, body: { job: RUNNING_JOB, coalesced: true } },
    });
    await user.click(screen.getByRole("button", { name: "Reconcile now" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")).toBeDefined());
    body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")![1] as RequestInit).body as string);
    expect(body).toEqual({ kind: "RECONCILE" });
    expect(showSuccessToast).toHaveBeenCalledWith("A reconcile is already in progress");
  });

  test("Reprocess explains it rebuilds the normalized layer, and posts on confirm", async () => {
    serve(mockFetch, {
      "POST /api/v1/data-sources/1/sync-jobs": { status: 202, body: { job: RUNNING_JOB, coalesced: false } },
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Reprocess" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByText(/Rebuilds the normalized layer/)).toBeInTheDocument();
    await user.click(within(modal).getByRole("button", { name: "Reprocess" }));

    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")![1] as RequestInit).body as string);
    expect(body).toEqual({ kind: "REPROCESS" });
  });

  test("Cancel running job posts a cancel and toasts", async () => {
    serve(mockFetch, { "POST /api/v1/data-sources/1/sync-jobs/5/cancel": { status: 202, body: { ...RUNNING_JOB, cancelRequestedAt: 3 } } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Cancel running job" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs/5/cancel")).toBeDefined());
    expect(showSuccessToast).toHaveBeenCalledWith("Cancellation requested");
  });

  test("a job-request failure renders inline, never as a toast", async () => {
    serve(mockFetch, { "POST /api/v1/data-sources/1/sync-jobs": { status: 500, body: { title: "boom", status: 500 } } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Sync now" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("Edit opens the prefilled modal, and Cancel closes it without saving", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Name")).toHaveValue("Acme Jira");
    await user.click(within(modal).getByRole("button", { name: /^cancel$/i }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("a cancel-job conflict (already finished) renders inline", async () => {
    serve(mockFetch, { "POST /api/v1/data-sources/1/sync-jobs/5/cancel": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Cancel running job" }));
    expect(await screen.findByText("This job already finished — nothing to cancel.")).toBeInTheDocument();
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("no cursors yet renders the empty-cursors message", async () => {
    serve(mockFetch, {}, { ...statusWith(null), cursors: [] });
    renderPage();
    expect(await screen.findByText("No cursors yet — this connection hasn't synced.")).toBeInTheDocument();
  });

  test("the jobs table's filters and per-row cancel all post/refetch correctly", async () => {
    const jobsPage = {
      items: [
        { ...RUNNING_JOB, id: 6, status: "PENDING" },
        { ...RUNNING_JOB, id: 7, status: "SUCCEEDED", errorCode: null },
        { ...RUNNING_JOB, id: 8, status: "FAILED", errorCode: "TIMEOUT" },
        { ...RUNNING_JOB, id: 9, status: "CANCELLED" },
      ],
      page: 1,
      pageSize: 20,
      total: 4,
    };
    const mutations = { "POST /api/v1/data-sources/1/sync-jobs/6/cancel": { status: 202, body: { ...RUNNING_JOB, id: 6, status: "CANCELLED" } } };
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      const key = `${method} ${url}`;
      if (mutations[key as keyof typeof mutations]) {
        const m = mutations[key as keyof typeof mutations];
        return Promise.resolve(jsonResponse(m.status, m.body));
      }
      if (method === "GET" && url === "/api/v1/data-sources/1/status") return Promise.resolve(jsonResponse(200, statusWith(null)));
      if (method === "GET" && url.startsWith("/api/v1/data-sources/1/sync-jobs?")) return Promise.resolve(jsonResponse(200, jobsPage));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findAllByText("Succeeded")).not.toHaveLength(0);
    expect(screen.getAllByText("Cancelled").length).toBeGreaterThan(0);
    expect(screen.getByText("TIMEOUT")).toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Kind" }));
    await user.click(await screen.findByRole("option", { name: "Sync" }));
    await user.click(screen.getByRole("combobox", { name: "Status" }));
    await user.click(await screen.findByRole("option", { name: "Pending" }));

    await user.click(screen.getByRole("button", { name: "Cancel job #6" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs/6/cancel")).toBeDefined());
    expect(showSuccessToast).toHaveBeenCalledWith("Cancellation requested");
  });

  test("Reprocess's Cancel closes the confirm modal without posting", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Reprocess" }));
    const modal = await screen.findByRole("dialog");
    await user.click(within(modal).getByRole("button", { name: /^cancel$/i }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/data-sources/1/sync-jobs")).toBeUndefined();
  });

  test("saving the edit modal closes it and refreshes the page", async () => {
    serve(mockFetch, { "PUT /api/v1/data-sources/1": { status: 204 } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    const modal = await screen.findByRole("dialog");
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/data-sources/1")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("changing the jobs table's page size resets to page 1", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("issues");
    const pageSizeSelect = screen.getByRole("combobox", { name: "Rows per page" });
    await user.click(pageSizeSelect);
    await user.click(await screen.findByRole("option", { name: "40 / page" }));
    await waitFor(() => expect(pageSizeSelect).toHaveValue("40 / page"));
  });

  test("auto-refreshes every 5s only while a job is open, and stops once it's not", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let call = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/data-sources/1/status") {
        call += 1;
        // Still running for the first two fetches, idle from the third onward.
        return Promise.resolve(jsonResponse(200, statusWith(call < 3 ? RUNNING_JOB : null)));
      }
      if (method === "GET" && url.startsWith("/api/v1/data-sources/1/sync-jobs?")) return Promise.resolve(jsonResponse(200, JOBS_PAGE));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });

    renderPage();
    await vi.waitFor(() => expect(call).toBe(1));

    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(call).toBe(2));

    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(call).toBe(3));

    // The job is now idle (currentJob: null) — a further 5s must NOT trigger another fetch.
    await vi.advanceTimersByTimeAsync(20_000);
    expect(call).toBe(3);
  });
});
