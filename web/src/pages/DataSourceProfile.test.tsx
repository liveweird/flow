import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceProfile from "./DataSourceProfile";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const EMPTY_PROFILE = {
  computedAt: null,
  estimates: { totalIssues: 0, storyPointsCount: 0, storyPointsPercent: 0, originalEstimateCount: 0, originalEstimatePercent: 0 },
  worklogs: { count: 0, totalHours: 0, itemsWithWorklog: 0, itemsWithWorklogPercent: 0, authorCount: 0 },
  reopens: { count: 0, totalIssues: 0, percent: 0 },
  sprints: { count: 0, stateCounts: {}, itemsWithSprint: 0, itemsWithSprintPercent: 0, carryOverCount: 0, carryOverPercent: 0 },
  people: { activeAssignees: 0, unassignedPercent: 0 },
};

const FULL_PROFILE = {
  computedAt: 1_700_000_000_000,
  range: { earliestCreatedAt: 1_600_000_000_000, latestUpdatedAt: 1_700_000_000_000 },
  projects: [{ projectKey: "ENG", issueCounts: { Story: 10, Bug: 5 } }],
  workflows: [
    {
      projectKey: "ENG",
      issueType: "Story",
      observedStatuses: [{ statusId: "1", name: "To Do", category: "TODO", transitionCount: 3 }],
      referenceStatusNames: ["To Do", "In Progress", "Done"],
    },
  ],
  boards: [
    {
      boardId: 1,
      name: "ENG board",
      boardType: "scrum",
      projectKey: "ENG",
      columns: [{ name: "To Do", statusNames: ["To Do"] }],
      unmappedStatusNames: ["Blocked"],
    },
  ],
  customFields: [{ id: "cf1", name: "Story Points", type: "number", nonNullCount: 80, fillPercent: 80.5, role: "STORY_POINTS" }],
  estimates: { totalIssues: 100, storyPointsCount: 80, storyPointsPercent: 80, originalEstimateCount: 50, originalEstimatePercent: 50 },
  worklogs: { count: 200, totalHours: 400.5, itemsWithWorklog: 60, itemsWithWorklogPercent: 60, authorCount: 12 },
  reopens: { count: 5, totalIssues: 100, percent: 5 },
  sprints: { count: 10, stateCounts: { ACTIVE: 1, CLOSED: 9 }, itemsWithSprint: 70, itemsWithSprintPercent: 70, carryOverCount: 15, carryOverPercent: 21.4 },
  people: { activeAssignees: 8, unassignedPercent: 12.3 },
  anomalyCounts: { STATUS_CHAIN_BROKEN: 2 },
};

function serve(mockFetch: FetchMock, profile: unknown) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/data-sources/1/profile") return Promise.resolve(jsonResponse(200, profile));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id/profile" element={<DataSourceProfile />} />
    </Routes>,
    { route: "/data-sources/1/profile" },
  );
}

describe("DataSourceProfile page", () => {
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

  test("shows the empty state before the first PROCESS pass", async () => {
    serve(mockFetch, EMPTY_PROFILE);
    renderPage();
    expect(await screen.findByText("Run a sync to compute the profile.")).toBeInTheDocument();
  });

  test("renders every populated section", async () => {
    serve(mockFetch, FULL_PROFILE);
    renderPage();

    expect(await screen.findByText("Computed at 2023-11-14 22:13")).toBeInTheDocument();
    expect(screen.getAllByText("ENG").length).toBeGreaterThan(0);
    expect(screen.getByText("Story")).toBeInTheDocument();
    expect(screen.getByText("To Do")).toBeInTheDocument();
    expect(screen.getByText("Reference workflow: To Do, In Progress, Done")).toBeInTheDocument();
    expect(screen.getByText("ENG board")).toBeInTheDocument();
    expect(screen.getByText("Blocked")).toBeInTheDocument();
    expect(screen.getByText("Story Points")).toBeInTheDocument();
    expect(screen.getByText("80.5%")).toBeInTheDocument();
    expect(screen.getByText("80 (80.0%)")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE: 1, CLOSED: 9")).toBeInTheDocument();
    expect(screen.getByText("STATUS_CHAIN_BROKEN")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  test("headings descend h2 (page) -> h3 (sections) and every table is named", async () => {
    serve(mockFetch, FULL_PROFILE);
    renderPage();

    await screen.findByText("Computed at 2023-11-14 22:13");
    expect(headingOutline()).toEqual([
      [2, "Data profile"],
      [3, "Range"],
      [3, "Projects"],
      [3, "Workflows"],
      [3, "Boards"],
      [3, "Custom fields"],
      [3, "Estimates"],
      [3, "Worklogs"],
      [3, "Reopens"],
      [3, "Sprints"],
      [3, "People"],
      [3, "Anomalies"],
    ]);
    for (const name of ["Range", "Projects", "Boards", "Custom fields", "Estimates", "Worklogs", "Reopens", "Sprints", "People", "Anomalies"]) {
      expect(screen.getByRole("table", { name })).toBeInTheDocument();
    }
    // One table per workflow, named after its project and issue type.
    expect(screen.getByRole("table", { name: /^ENG · / })).toBeInTheDocument();
    expect(screen.getAllByRole("table").every((table) => table.getAttribute("aria-label"))).toBe(true);
  });

  test("renders the no-data fallback for empty sections, and omits the range section when absent", async () => {
    const sparse = {
      ...EMPTY_PROFILE,
      computedAt: 1_700_000_000_000,
      projects: [],
      workflows: [],
      boards: [{ boardId: 2, name: "Kanban board", boardType: "kanban", projectKey: null, columns: [], unmappedStatusNames: [] }],
      customFields: [],
      anomalyCounts: {},
    };
    serve(mockFetch, sparse);
    renderPage();

    expect(await screen.findByText("Kanban board")).toBeInTheDocument();
    expect(screen.queryByText("Range")).not.toBeInTheDocument();
    expect(screen.getAllByText("No data yet.").length).toBeGreaterThan(0);
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  test("a data source not found by id shows the not-found message", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This data source does not exist (or was deleted).")).toBeInTheDocument();
  });
});
