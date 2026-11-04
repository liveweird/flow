import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import { HOME } from "../test/homeFixtures";
import { serve } from "../test/homeServe";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", async () => (await import("../test/homeChartsMock")).homeChartsMock);

type FetchMock = ReturnType<typeof vi.fn>;

const sections = () => screen.getAllByRole("region").filter((region) => region.tagName === "SECTION");
const ready = () => screen.findByRole("region", { name: "What is missing from the data" });

function renderHome() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Home />} />
    </Routes>,
  );
}

describe("Home page — the Code section and the notes around the quality table", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("no GitLab source: no Code section and no code line in the freshness; the other seven stay", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, code: { sources: [], overBudget: false }, teams: HOME.teams.map((team) => ({ ...team, code: null })) }));
    renderHome();
    await ready();
    expect(sections()).toHaveLength(7);
    expect(screen.queryByRole("region", { name: "Code" })).not.toBeInTheDocument();
    expect(screen.queryByText(/\(GitLab\)/)).not.toBeInTheDocument();
  });

  test("the freshness names each GitLab source's last sync, and the Code section sits between delivery and the data quality table", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const freshness = screen.getByRole("group", { name: "How fresh the data is" });
    expect(within(freshness).getByText("Stub GitLab (GitLab): synced 2026-09-29 11:45")).toBeInTheDocument();
    expect(sections().map((card) => card.getAttribute("data-drill-anchor")).slice(-3)).toEqual(["home-throughput", "home-code", "home-quality"]);
  });

  test("no unassigned work: no note", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, unassigned: { doneTasks: 0, openTasks: 0, epics: 0 } }));
    renderHome();
    await ready();
    expect(screen.queryByText(/Not in any team above/)).not.toBeInTheDocument();
  });
});
