import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceDetails from "./DataSourceDetails";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

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
    projectKeys: ["ENG"],
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

const PAUSED_STATUS = {
  ...CONNECTION.status,
  state: "FAILED",
  lastSyncErrorCode: "TIME_EXCEEDED",
  pausedAt: 1_700_000_120_000,
  pauseReason: "TIME_EXCEEDED",
  limitTrips: 5,
};

function serve(mockFetch: ReturnType<typeof vi.fn>, connection: unknown) {
  const status = {
    connection,
    cursors: [],
    counts: { rawIssues: 0, tombstonedDeleted: 0, tombstonedMovedOut: 0, changelogs: 0, worklogs: 0, entitiesByKind: {}, needsProcessing: 0 },
    lastJobs: {},
    currentJob: null,
  };
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/data-sources/1/status") return Promise.resolve(jsonResponse(200, status));
    if (url.startsWith("/api/v1/data-sources/1/sync-jobs?")) {
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage() {
  renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id" element={<DataSourceDetails />} />
    </Routes>,
    { route: "/data-sources/1" },
  );
}

describe("DataSourceDetails page: a paused connection", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("explains the pause, the reason and how to resume, beside the Paused badge", async () => {
    serve(mockFetch, { ...CONNECTION, status: PAUSED_STATUS });
    renderPage();

    expect(await screen.findByText("Scheduled syncing is paused")).toBeInTheDocument();
    expect(screen.getByText(/the time limit per run was hit/)).toHaveTextContent('Run "Sync now" or save the connection to resume.');
    expect(screen.getByText("Paused")).toBeInTheDocument();
    expect(screen.getAllByText("Failed").length).toBeGreaterThan(0);
  });

  test("a connection that is not paused shows no pause alert and no badge", async () => {
    serve(mockFetch, CONNECTION);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Acme Jira" })).toBeInTheDocument();
    expect(screen.queryByText("Scheduled syncing is paused")).not.toBeInTheDocument();
    expect(screen.queryByText("Paused")).not.toBeInTheDocument();
  });
});
