import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceMetricsConfig from "./DataSourceMetricsConfig";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { DataSourceMetricsConfigOptions } from "../api/metrics";

// The Statuses tab's default filter (relevant statuses + the admin's own choices, a "Show all" switch). Split from
// DataSourceMetricsConfig.test.tsx to keep that file's describe under the function-length cap.

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const CONFIG_URL = "/api/v1/data-sources/1/metrics-config";
const OPTIONS_URL = "/api/v1/data-sources/1/metrics-config/options";
const TEAMS_URL = "/api/v1/teams?page=1&pageSize=100&sort=name";

const BASE_OPTIONS: DataSourceMetricsConfigOptions = {
  statuses: [],
  fields: [],
  projects: ["ENG"],
  boards: [],
  issueTypes: ["Story"],
  workCategoryValues: [],
  workCategoryValuesTruncated: false,
  sprints: [],
};

function baseConfig() {
  return {
    configured: true,
    statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
    domainStatusStages: [] as { domainKey: string; statusId: string; stage: string }[],
    fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: "duedate", workCategory: null },
    domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }],
    boards: [],
    activityTypes: [{ issueType: "Story", activityType: "Story" }],
    workCategories: [],
    blockedStatuses: [] as string[],
    sprintCapacities: [],
  };
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id/metrics-config" element={<DataSourceMetricsConfig />} />
    </Routes>,
    { route: "/data-sources/1/metrics-config" },
  );
}

describe("DataSourceMetricsConfig Statuses filter", () => {
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

  function serve(config: unknown, statuses: DataSourceMetricsConfigOptions["statuses"]) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === CONFIG_URL) return Promise.resolve(jsonResponse(200, config));
      if (method === "GET" && url === OPTIONS_URL) return Promise.resolve(jsonResponse(200, { ...BASE_OPTIONS, statuses }));
      if (method === "GET" && url === TEAMS_URL) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
      if (method === "PUT" && url === CONFIG_URL) return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, {}));
    });
  }

  const FILTERED_STATUSES: DataSourceMetricsConfigOptions["statuses"] = [
    { statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    { statusId: "10", name: "Done", category: "DONE", inWorkflow: false, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    { statusId: "20", name: "Archived", category: "DONE", inWorkflow: false, seenInHistory: false, inEpicWorkflow: false, inTaskWorkflow: false },
    { statusId: "30", name: "Legacy", category: "TODO", inWorkflow: false, seenInHistory: false, inEpicWorkflow: false, inTaskWorkflow: false },
  ];
  /** The server seeds every status with its category default, hidden ones included. */
  const FILTERED_CONFIG = {
    ...baseConfig(),
    statusStages: [
      { statusId: "3", stage: "IN_PROGRESS" },
      { statusId: "10", stage: "DONE" },
      { statusId: "20", stage: "DONE" },
      { statusId: "30", stage: "NOT_STARTED" },
    ],
  };
  const SWITCH_NAME = "Show all statuses (4)";

  function serveFiltered(config: unknown = FILTERED_CONFIG, statuses = FILTERED_STATUSES) {
    serve(config, statuses);
  }

  async function putBody(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    const call = await waitFor(() => {
      const found = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
      expect(found).toBeTruthy();
      return found!;
    });
    return JSON.parse((call[1] as RequestInit).body as string);
  }

  test("lists only workflow/history statuses by default, in both tables, and the switch reveals the rest", async () => {
    serveFiltered();
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByRole("combobox", { name: "Stage for In Progress" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Done" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Stage for Archived" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Stage for Legacy" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Stage for Archived in domain ENG" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Done in domain ENG" })).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: SWITCH_NAME })).toHaveAccessibleDescription(/only statuses used by this connection/);

    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    expect(await screen.findByRole("combobox", { name: "Stage for Archived" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Legacy" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Archived in domain ENG" })).toBeInTheDocument();

    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Stage for Archived" })).not.toBeInTheDocument());
  });

  test("badges each status with the workflows (Epic, Task, both) that use it", async () => {
    serveFiltered(FILTERED_CONFIG, [
      { statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: true, inEpicWorkflow: true, inTaskWorkflow: true },
      { statusId: "10", name: "Done", category: "DONE", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: true },
      { statusId: "20", name: "On Hold", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: false, inEpicWorkflow: true, inTaskWorkflow: false },
      { statusId: "30", name: "Legacy", category: "TODO", inWorkflow: false, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    ]);
    renderPage();

    const badgesOf = async (name: string) => {
      const label = (await screen.findAllByText(name, { selector: "p" }))[0];
      const row = label.closest("tr") as HTMLElement;
      return ["Epic", "Task"].filter((badge) => within(row).queryByText(badge) !== null);
    };
    expect(await badgesOf("In Progress")).toEqual(["Epic", "Task"]);
    expect(await badgesOf("Done")).toEqual(["Task"]);
    expect(await badgesOf("On Hold")).toEqual(["Epic"]);
    expect(await badgesOf("Legacy")).toEqual([]);
    expect(screen.getByText(/Which workflow uses the status/)).toBeInTheDocument();
  });

  test("no badges and no hint while the epic/task split is unknown", async () => {
    serveFiltered();
    renderPage();

    await screen.findByRole("combobox", { name: "Stage for In Progress" });
    expect(screen.queryByText("Epic")).not.toBeInTheDocument();
    expect(screen.queryByText("Task")).not.toBeInTheDocument();
    expect(screen.queryByText(/Which workflow uses the status/)).not.toBeInTheDocument();
  });

  test("a hidden-by-default status stays listed once the admin changed it", async () => {
    serveFiltered();
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("combobox", { name: "Stage for In Progress" });
    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    await user.click(await screen.findByRole("combobox", { name: "Stage for Archived" }));
    await user.click(await screen.findByRole("option", { name: "In progress" }));
    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));

    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Stage for Legacy" })).not.toBeInTheDocument());
    expect(screen.getByRole("combobox", { name: "Stage for Archived" })).toBeInTheDocument();
  });

  test("a hidden status the admin edited keeps its row even after the edit restored its default", async () => {
    serveFiltered();
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("combobox", { name: "Stage for In Progress" });
    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    await user.click(screen.getByLabelText("Blocked for Legacy"));
    await user.click(screen.getByLabelText("Blocked for Legacy"));
    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));

    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Stage for Archived" })).not.toBeInTheDocument());
    expect(screen.getByRole("combobox", { name: "Stage for Legacy" })).toBeInTheDocument();
  });

  test("statuses carrying a saved choice are listed without the switch: a non-default stage, Blocked, a per-domain override", async () => {
    serveFiltered({
      ...FILTERED_CONFIG,
      statusStages: [
        { statusId: "3", stage: "IN_PROGRESS" },
        { statusId: "10", stage: "DONE" },
        { statusId: "30", stage: "IN_PROGRESS" },
      ],
      blockedStatuses: ["20"],
    });
    renderPage();

    // "Legacy": to-do by category but saved as In progress; "Archived": DONE by category but saved unmapped AND blocked.
    expect(await screen.findByRole("combobox", { name: "Stage for Legacy" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Archived" })).toBeInTheDocument();
  });

  test("a status with a per-domain override is listed by default", async () => {
    serveFiltered({ ...FILTERED_CONFIG, domainStatusStages: [{ domainKey: "ENG", statusId: "30", stage: "DONE" }] });
    renderPage();

    expect(await screen.findByRole("combobox", { name: "Stage for Legacy" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Legacy in domain ENG" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Stage for Archived" })).not.toBeInTheDocument();
  });

  test("the PUT still carries every status, hidden ones at their current state", async () => {
    serveFiltered();
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("combobox", { name: "Stage for In Progress" });
    expect(screen.queryByRole("combobox", { name: "Stage for Archived" })).not.toBeInTheDocument();
    const body = await putBody(user);

    expect(body.statusStages).toEqual([
      { statusId: "3", stage: "IN_PROGRESS" },
      { statusId: "10", stage: "DONE" },
      { statusId: "20", stage: "DONE" },
      { statusId: "30", stage: "NOT_STARTED" },
    ]);
    expect(body.blockedStatuses).toEqual([]);
  });

  test("with no workflow or history information every status is listed and there is no switch", async () => {
    serveFiltered(
      FILTERED_CONFIG,
      FILTERED_STATUSES.map((s) => ({ ...s, inWorkflow: false, seenInHistory: false, inEpicWorkflow: false, inTaskWorkflow: false })),
    );
    renderPage();

    expect(await screen.findByRole("combobox", { name: "Stage for Archived" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Stage for Legacy" })).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("no switch is offered when every status is already listed", async () => {
    serve(baseConfig(), [{ statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false }]);
    renderPage();

    await screen.findByRole("combobox", { name: "Stage for In Progress" });
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });
});
