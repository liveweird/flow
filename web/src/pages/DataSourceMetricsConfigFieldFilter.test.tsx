import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceMetricsConfig from "./DataSourceMetricsConfig";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { DataSourceMetricsConfigOptions } from "../api/metrics";

// The Fields tab's default filter (fields of the projects' field scheme, the has-data fallback, the user's own selections,
// a "Show all" switch). Split from DataSourceMetricsConfig.test.tsx like the Statuses filter's file.

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const CONFIG_URL = "/api/v1/data-sources/1/metrics-config";
const OPTIONS_URL = "/api/v1/data-sources/1/metrics-config/options";
const TEAMS_URL = "/api/v1/teams?page=1&pageSize=100&sort=name";

type FieldOptions = DataSourceMetricsConfigOptions["fields"];

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

function baseConfig(fields: Record<string, string | null> = {}) {
  return {
    configured: true,
    statusStages: [],
    domainStatusStages: [],
    fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: null, workCategory: null, ...fields },
    domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }],
    boards: [],
    activityTypes: [{ issueType: "Story", activityType: "Story" }],
    workCategories: [],
    blockedStatuses: [] as string[],
    sprintCapacities: [],
  };
}

const SCHEME_FIELDS: FieldOptions = [
  { fieldId: "customfield_1", name: "Story points", type: "number", detectedRole: "STORY_POINTS", inScheme: true, nonNullCount: 40, inEpicScheme: null, inTaskScheme: null },
  { fieldId: "customfield_2", name: "Team", type: "team", detectedRole: "TEAM", inScheme: true, nonNullCount: 0, inEpicScheme: null, inTaskScheme: null },
  { fieldId: "customfield_3", name: "Legacy cost centre", type: "string", detectedRole: "OTHER", inScheme: false, nonNullCount: 9, inEpicScheme: null, inTaskScheme: null },
  { fieldId: "customfield_4", name: "Old risk", type: "string", detectedRole: "OTHER", inScheme: false, nonNullCount: 0, inEpicScheme: null, inTaskScheme: null },
];
const SWITCH_NAME = "Show all fields (4)";

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id/metrics-config" element={<DataSourceMetricsConfig />} />
    </Routes>,
    { route: "/data-sources/1/metrics-config" },
  );
}

describe("DataSourceMetricsConfig Fields filter", () => {
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

  function serve(fields: FieldOptions, config: unknown = baseConfig()) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === CONFIG_URL) return Promise.resolve(jsonResponse(200, config));
      if (method === "GET" && url.startsWith(OPTIONS_URL)) return Promise.resolve(jsonResponse(200, { ...BASE_OPTIONS, fields }));
      if (method === "GET" && url === TEAMS_URL) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
      if (method === "PUT" && url === CONFIG_URL) return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, {}));
    });
  }

  async function openFieldsTab(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole("tab", { name: "Fields" }));
  }

  async function optionNames(user: ReturnType<typeof userEvent.setup>, selectName: string) {
    await user.click(screen.getByRole("combobox", { name: selectName }));
    const names = (await screen.findAllByRole("option")).map((o) => o.textContent);
    await user.keyboard("{Escape}");
    return names;
  }

  test("lists only the fields of the project field scheme (plus the system due date) in every Select", async () => {
    serve(SCHEME_FIELDS);
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    const expected = ["Due date (system field)", "Story points (Story points)", "Team (Team)"];
    expect(await optionNames(user, "Task estimate field")).toEqual(expected);
    expect(await optionNames(user, "Work-category field")).toEqual(expected);
    expect(screen.queryByText(/isn't available from Jira/)).not.toBeInTheDocument();
  });

  test("the switch shows every field and hides the extras again", async () => {
    serve(SCHEME_FIELDS);
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    await user.click(await screen.findByRole("switch", { name: SWITCH_NAME }));
    const all = await optionNames(user, "Epic start-date field");
    expect(all).toContain("Legacy cost centre (Other)");
    expect(all).toContain("Old risk (Other)");
    expect(all).toHaveLength(5);

    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    await waitFor(async () => expect(await optionNames(user, "Epic start-date field")).toHaveLength(3));
  });

  test("a field selected in a slot stays listed although the scheme excludes it", async () => {
    serve(SCHEME_FIELDS, baseConfig({ workCategory: "customfield_3" }));
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    const names = await optionNames(user, "Task estimate field");
    expect(names).toContain("Legacy cost centre (Other)");
    expect(names).not.toContain("Old risk (Other)");
    expect(screen.getByRole("combobox", { name: "Work-category field" })).toHaveValue("Legacy cost centre (Other)");
  });

  test("with an unknown scheme it lists the fields that have data and says why", async () => {
    serve(SCHEME_FIELDS.map((f) => ({ ...f, inScheme: null })));
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    expect(await screen.findByText(/The field scheme isn't available from Jira/)).toBeInTheDocument();
    expect(await optionNames(user, "Task estimate field")).toEqual([
      "Due date (system field)",
      "Story points (Story points)",
      "Legacy cost centre (Other)",
    ]);
    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    expect(await optionNames(user, "Task estimate field")).toHaveLength(5);
  });

  test("epic slots list the epic scheme's fields and task slots the task scheme's", async () => {
    const split: FieldOptions = [
      { fieldId: "customfield_1", name: "Story points", type: "number", detectedRole: "STORY_POINTS", inScheme: true, nonNullCount: 40, inEpicScheme: false, inTaskScheme: true },
      { fieldId: "customfield_2", name: "Epic start", type: "date", detectedRole: "OTHER", inScheme: true, nonNullCount: 3, inEpicScheme: true, inTaskScheme: false },
      { fieldId: "customfield_3", name: "Team", type: "team", detectedRole: "TEAM", inScheme: true, nonNullCount: 3, inEpicScheme: true, inTaskScheme: true },
      { fieldId: "customfield_4", name: "Old risk", type: "string", detectedRole: "OTHER", inScheme: false, nonNullCount: 0, inEpicScheme: false, inTaskScheme: false },
    ];
    serve(split);
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    const task = ["Due date (system field)", "Story points (Story points)", "Team (Team)"];
    const epic = ["Due date (system field)", "Epic start (Other)", "Team (Team)"];
    expect(await optionNames(user, "Task estimate field")).toEqual(task);
    expect(await optionNames(user, "Work-category field")).toEqual(task);
    expect(await optionNames(user, "Epic estimate field")).toEqual(epic);
    expect(await optionNames(user, "Epic start-date field")).toEqual(epic);
    expect(await optionNames(user, "Epic due-date field")).toEqual(epic);

    await user.click(screen.getByRole("switch", { name: SWITCH_NAME }));
    expect(await optionNames(user, "Epic start-date field")).toHaveLength(5);
  });

  test("a slot falls back to the union scheme while the epic/task split is unknown", async () => {
    serve(SCHEME_FIELDS);
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    const union = ["Due date (system field)", "Story points (Story points)", "Team (Team)"];
    expect(await optionNames(user, "Epic start-date field")).toEqual(union);
    expect(await optionNames(user, "Task estimate field")).toEqual(union);
  });

  test("no switch and no note when nothing is hidden", async () => {
    serve(SCHEME_FIELDS.slice(0, 2));
    const user = userEvent.setup();
    renderPage();
    await openFieldsTab(user);

    expect(await optionNames(user, "Task estimate field")).toHaveLength(3);
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("the PUT carries the selected hidden field", async () => {
    serve(SCHEME_FIELDS, baseConfig({ estimateTask: "customfield_4" }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("tab", { name: "Fields" });
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    const call = await waitFor(() => {
      const found = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
      expect(found).toBeTruthy();
      return found!;
    });
    expect(JSON.parse((call[1] as RequestInit).body as string).fields.estimateTask).toBe("customfield_4");
  });
});
