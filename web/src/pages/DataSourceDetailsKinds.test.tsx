import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceDetails from "./DataSourceDetails";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const JIRA_CONNECTION = {
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
  gitlab: null,
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

const GITLAB_CONNECTION = {
  ...JIRA_CONNECTION,
  kind: "GITLAB",
  name: "Platform GitLab",
  jira: null,
  gitlab: { baseUrl: "http://gitlab-stub:8080", hasToken: true, groupPaths: ["platform", "apps"], pathExclusions: ["package-lock.json", "*.min.js"] },
};

const JIRA_COUNTS = {
  rawIssues: 10,
  tombstonedDeleted: 1,
  tombstonedMovedOut: 0,
  changelogs: 5,
  worklogs: 2,
  entitiesByKind: {},
  needsProcessing: 1,
};

const GITLAB_COUNTS = {
  ...JIRA_COUNTS,
  rawIssues: 0,
  gitlab: {
    projects: 4,
    projectsMovedOut: 1,
    projectsDeleted: 2,
    users: 8,
    usersDeleted: 3,
    commits: 300,
    commitsMerge: 19,
    commitsDeleted: 5,
    commitFilesPending: 7,
    mergeRequests: 60,
    mergeRequestsMerged: 34,
    mrEvents: 698,
    mrDetailsPending: 9,
    mrDiffStatsMissing: 1,
    mergeRequestsDeleted: 2,
    mrDetailsUnread: 3,
  },
};

function serve(mockFetch: ReturnType<typeof vi.fn>, connection: unknown, counts: unknown) {
  const status = { connection, cursors: [], counts, lastJobs: {}, currentJob: null };
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

describe("DataSourceDetails page: the connection kinds", () => {
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

  test("a Jira source shows its kind, site and scope, and offers every Jira-only page and action", async () => {
    serve(mockFetch, JIRA_CONNECTION, JIRA_COUNTS);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Acme Jira" })).toBeInTheDocument();
    const connection = screen.getByRole("table", { name: "Connection" });
    expect(within(connection).getByText("Jira Cloud")).toBeInTheDocument();
    expect(within(connection).getByText("https://acme.atlassian.net")).toBeInTheDocument();
    expect(within(connection).getByText("ENG, OPS")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Data profile" })).toHaveAttribute("href", "/data-sources/1/profile");
    expect(screen.getByRole("link", { name: "Raw issue inspector" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Metrics configuration" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reconcile now" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reprocess" })).toBeInTheDocument();
    expect(screen.getByText("Raw issues")).toBeInTheDocument();
  });

  test("a GitLab source shows its instance, groups and GitLab counts; the Jira-only pages and Reprocess are not offered, Reconcile now is", async () => {
    serve(mockFetch, GITLAB_CONNECTION, GITLAB_COUNTS);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Platform GitLab" })).toBeInTheDocument();
    const connection = screen.getByRole("table", { name: "Connection" });
    expect(within(connection).getByText("GitLab")).toBeInTheDocument();
    expect(within(connection).getByText("http://gitlab-stub:8080")).toBeInTheDocument();
    expect(within(connection).getByText("platform, apps")).toBeInTheDocument();
    expect(within(connection).getByText("Excluded paths").closest("tr")).toHaveTextContent("package-lock.json, *.min.js");
    expect(within(connection).queryByText("Service-account email")).not.toBeInTheDocument();

    const counts = screen.getByRole("table", { name: "Raw store counts" });
    const row = (label: string) => within(counts).getByText(label).closest("tr");
    expect(row("Projects")).toHaveTextContent("4");
    expect(row("Projects moved out of scope")).toHaveTextContent("1");
    expect(row("Projects deleted")).toHaveTextContent("2");
    expect(row("Users")).toHaveTextContent("8");
    expect(row("Users deleted")).toHaveTextContent("3");
    expect(row("Commits")).toHaveTextContent("300");
    expect(row("Merge commits")).toHaveTextContent("19");
    expect(row("Commits deleted")).toHaveTextContent("5");
    expect(row("Commits awaiting file details")).toHaveTextContent("7");
    expect(row("Merge requests")).toHaveTextContent("60");
    expect(row("Merged merge requests")).toHaveTextContent("34");
    expect(row("MR events (notes, approvals, reviewer states)")).toHaveTextContent("698");
    expect(row("Merge requests awaiting details")).toHaveTextContent("9");
    expect(row("Merge requests without size statistics")).toHaveTextContent("1");
    expect(row("Merge requests deleted")).toHaveTextContent("2");
    expect(row("Merge requests with unreadable details")).toHaveTextContent("3");
    expect(within(counts).queryByText("Raw issues")).not.toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Sync now" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    for (const link of ["Data profile", "Raw issue inspector", "Metrics configuration"]) {
      expect(screen.queryByRole("link", { name: link })).not.toBeInTheDocument();
    }
    expect(screen.getByRole("button", { name: "Reconcile now" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reprocess" })).not.toBeInTheDocument();
  });

  test("a GitLab source whose store is off in this process shows dashes for its counts", async () => {
    serve(mockFetch, GITLAB_CONNECTION, { ...GITLAB_COUNTS, gitlab: null });
    renderPage();

    const counts = await screen.findByRole("table", { name: "Raw store counts" });
    expect(within(counts).getByText("Projects").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Users").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Commits").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Commits awaiting file details").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Merge requests").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("MR events (notes, approvals, reviewer states)").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Merge requests without size statistics").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Merge requests deleted").closest("tr")).toHaveTextContent("—");
    expect(within(counts).getByText("Merge requests with unreadable details").closest("tr")).toHaveTextContent("—");
  });

  test("a GitLab source with an empty exclusion list says so", async () => {
    serve(mockFetch, { ...GITLAB_CONNECTION, gitlab: { ...GITLAB_CONNECTION.gitlab, pathExclusions: [] } }, GITLAB_COUNTS);
    renderPage();

    const connection = await screen.findByRole("table", { name: "Connection" });
    expect(within(connection).getByText("Excluded paths").closest("tr")).toHaveTextContent("None");
  });
});
