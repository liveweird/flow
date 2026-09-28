import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import RawIssueInspector from "./RawIssueInspector";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const INSPECTION = {
  issueId: 10001,
  issueKey: "ENG-123",
  fetchedAt: 1_700_000_000_000,
  changedAt: 1_700_000_000_000,
  sha256: "abc",
  needsProcessing: false,
  payload: { fields: { summary: "Fix the thing" } },
  changelogs: [{ id: "1", created: "2023-01-01" }],
  worklogs: [{ id: "2", timeSpentSeconds: 3600 }],
  workItem: {
    issueKey: "ENG-123",
    projectKey: "ENG",
    issueType: "Story",
    statusId: "1",
    statusName: "In Progress",
    statusCategory: "IN_PROGRESS",
    assigneeAccountId: "acc-1",
    processedAt: 1_700_000_000_000,
    processingVersion: 1,
  },
  statusIntervals: [
    { seq: 0, statusId: "0", statusName: "To Do", category: "TODO", fromAtMs: 1_699_000_000_000, toAtMs: 1_700_000_000_000, source: "CREATED" },
  ],
  fieldIntervals: [{ field: "ASSIGNEE", seq: 0, valueId: "acc-1", valueText: "Ann", fromAtMs: 1_699_000_000_000, toAtMs: null }],
  anomalies: ["STATUS_CHAIN_BROKEN"],
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id/inspect" element={<RawIssueInspector />} />
    </Routes>,
    { route: "/data-sources/1/inspect" },
  );
}

describe("RawIssueInspector page", () => {
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

  test("shows the empty prompt before any lookup", async () => {
    renderPage();
    expect(await screen.findByText("Look up an issue key or id to inspect its raw and normalized data.")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("looks up an issue and renders the payload, work item, intervals and anomalies", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/data-sources/1/raw-issues/ENG-123") return Promise.resolve(jsonResponse(200, INSPECTION));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Issue key or id"), "ENG-123");
    await user.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByRole("heading", { level: 4, name: "ENG-123" })).toBeInTheDocument();
    expect(screen.getByText("In Progress (In progress)")).toBeInTheDocument();
    expect(screen.getByText("To Do")).toBeInTheDocument();
    expect(screen.getByText("Ann")).toBeInTheDocument();
    expect(screen.getByText("STATUS_CHAIN_BROKEN")).toBeInTheDocument();
    expect(screen.getByText("Changelogs (1)")).toBeInTheDocument();
    expect(screen.getByText("Worklogs (1)")).toBeInTheDocument();
    expect(screen.getByText(/"summary": "Fix the thing"/)).toBeInTheDocument();
  });

  test("an unprocessed issue with no anomalies/changelogs/worklogs renders every empty-state fallback", async () => {
    const bare = {
      issueId: 10002,
      issueKey: "ENG-124",
      fetchedAt: 1_700_000_000_000,
      changedAt: 1_700_000_000_000,
      sha256: "def",
      needsProcessing: true,
      payload: { fields: { summary: "Not yet processed" } },
    };
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/data-sources/1/raw-issues/ENG-124") return Promise.resolve(jsonResponse(200, bare));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Issue key or id"), "ENG-124");
    await user.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByText("This issue has not been through PROCESS yet.")).toBeInTheDocument();
    expect(screen.getByText("Needs processing")).toBeInTheDocument();
    expect(screen.getByText("No anomalies detected")).toBeInTheDocument();
    expect(screen.getAllByText("None").length).toBeGreaterThan(0);
    expect(screen.getByText("Changelogs (0)")).toBeInTheDocument();
    expect(screen.getByText("Worklogs (0)")).toBeInTheDocument();
  });

  test("a 404 renders inline — no such issue for this data source", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Issue key or id"), "ENG-999");
    await user.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByText("No issue with that key or id was found for this data source")).toBeInTheDocument();
  });

  test("a 400 (malformed key) renders inline", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 })));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText("Issue key or id"), "not a key");
    await user.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByText("Enter a valid issue key (e.g. ENG-123) or numeric id")).toBeInTheDocument();
  });
});
