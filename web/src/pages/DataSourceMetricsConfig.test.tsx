import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import DataSourceMetricsConfig from "./DataSourceMetricsConfig";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { showSuccessToast } from "../utils/toast";
import type { DataSourceMetricsConfigOptions } from "../api/metrics";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const CONFIG_URL = "/api/v1/data-sources/1/metrics-config";
const OPTIONS_URL = "/api/v1/data-sources/1/metrics-config/options";
const TEAMS_URL = "/api/v1/teams?page=1&pageSize=100&sort=name";

const OPTIONS: DataSourceMetricsConfigOptions = {
  statuses: [
    { statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    { statusId: "10", name: "Done", category: "DONE", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
  ],
  fields: [
    { fieldId: "customfield_10001", name: "Story points", type: "number", detectedRole: "STORY_POINTS", inScheme: true, nonNullCount: 12, inEpicScheme: null, inTaskScheme: null },
    { fieldId: "customfield_10002", name: "Category", type: "string", detectedRole: "OTHER", inScheme: true, nonNullCount: 4, inEpicScheme: null, inTaskScheme: null },
  ],
  projects: ["ENG"],
  boards: [
    { boardId: 1, name: "Board A", projectKey: "ENG" },
    { boardId: 2, name: "Board B", projectKey: "ENG" },
  ],
  issueTypes: ["Story"],
  workCategoryValues: [],
  workCategoryValuesTruncated: false,
  sprints: [],
};

function baseConfig(configured: boolean) {
  return {
    configured,
    statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
    domainStatusStages: [] as { domainKey: string; statusId: string; stage: string }[],
    fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: "duedate", workCategory: null },
    domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }],
    boards: [],
    activityTypes: [{ issueType: "Story", activityType: "Story" }],
    workCategories: [],
    blockedStatuses: [],
    sprintCapacities: [],
  };
}

const TEAMS_PAGE = {
  items: [
    { id: 5, name: "Team A", description: null, memberCount: 3, createdAt: 1, updatedAt: 1 },
    { id: 6, name: "Team B", description: null, memberCount: 2, createdAt: 1, updatedAt: 1 },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/data-sources/:id/metrics-config" element={<DataSourceMetricsConfig />} />
    </Routes>,
    { route: "/data-sources/1/metrics-config" },
  );
}

function serve(
  mockFetch: FetchMock,
  options: {
    config?: unknown;
    configAfterSave?: unknown;
    configErrorStatus?: number;
    optionsErrorStatus?: number;
    optionsOverride?: Partial<typeof OPTIONS>;
    putStatus?: number;
    putBody?: unknown;
    workCategoryValuesByField?: Record<string, unknown>;
  } = {},
) {
  const currentConfig = options.config ?? baseConfig(false);
  const effectiveOptions = { ...OPTIONS, ...options.optionsOverride };
  let saved = false;
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === CONFIG_URL) {
      if (options.configErrorStatus) return Promise.resolve(jsonResponse(options.configErrorStatus, {}));
      return Promise.resolve(jsonResponse(200, saved && options.configAfterSave ? options.configAfterSave : currentConfig));
    }
    if (method === "GET" && url === OPTIONS_URL) {
      if (options.optionsErrorStatus) return Promise.resolve(jsonResponse(options.optionsErrorStatus, {}));
      return Promise.resolve(jsonResponse(200, effectiveOptions));
    }
    if (method === "GET" && url.startsWith(OPTIONS_URL + "?workCategoryField=")) {
      const field = new URL(url, "http://localhost").searchParams.get("workCategoryField") ?? "";
      const values = options.workCategoryValuesByField?.[field] ?? [];
      return Promise.resolve(jsonResponse(200, { ...effectiveOptions, workCategoryValues: values }));
    }
    if (method === "GET" && url === TEAMS_URL) {
      return Promise.resolve(jsonResponse(200, TEAMS_PAGE));
    }
    if (method === "PUT" && url === CONFIG_URL) {
      saved = true;
      const status = options.putStatus ?? 204;
      if (status === 204) return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(
        jsonResponse(status, options.putBody ?? { title: "x", status, detail: "x", instance: "/x", type: "about:blank" }),
      );
    }
    return Promise.resolve(jsonResponse(404, {}));
  });
}

describe("DataSourceMetricsConfig page", () => {
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

  test("renders preselected stages and the not-configured banner", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByText("Showing computed defaults — save to confirm them")).toBeInTheDocument();
    const stageInput = (await screen.findByRole("combobox", { name: "Stage for In Progress" })) as HTMLInputElement;
    await waitFor(() => expect(stageInput.value).toBe("In progress"));
  });

  test("PUTs the full form body on save, including untouched tabs", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT")).toBe(true),
    );
    const putCall = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
      domainStatusStages: [],
      fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: "duedate", workCategory: null },
      domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: null }],
      boards: [],
      activityTypes: [{ issueType: "Story", activityType: "Story" }],
      workCategories: [],
      blockedStatuses: [],
      sprintCapacities: [],
    });
  });

  test("a board-team 409 marks the offending board row inline", async () => {
    serve(mockFetch, {
      putStatus: 409,
      putBody: { title: "Conflict", status: 409, detail: "This team is already mapped to another board", instance: "/x", type: "about:blank" },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("tab", { name: "Boards" }));

    const teamSelectA = await screen.findByRole("combobox", { name: "Team for Board A" });
    await user.click(teamSelectA);
    await user.click(await screen.findByRole("option", { name: "Team A" }));

    await user.click(screen.getByRole("button", { name: /^save$/i }));

    const rowA = (await screen.findByText("Board A")).closest("tr");
    expect(rowA).not.toBeNull();
    expect(rowA!.textContent).toContain("This team is already mapped to another board");

    const rowB = screen.getByText("Board B").closest("tr");
    expect(rowB!.textContent).not.toContain("This team is already mapped to another board");
  });

  test("a 400 shows the server's message inline", async () => {
    serve(mockFetch, {
      putStatus: 400,
      putBody: { title: "Bad Request", status: 400, detail: "Unknown status id: 999", instance: "/x", type: "about:blank" },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Unknown status id: 999")).toBeInTheDocument();
  });

  test("the work-category tab fetches values scoped to the chosen field", async () => {
    serve(mockFetch, {
      workCategoryValuesByField: {
        customfield_10002: [{ valueId: "v1", valueName: "Bug" }],
      },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("tab", { name: "Fields" }));

    const workCategorySelect = screen.getByRole("combobox", { name: "Work-category field" });
    await user.click(workCategorySelect);
    await user.click(await screen.findByRole("option", { name: "Category (Other)" }));

    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url]) => url === `${OPTIONS_URL}?workCategoryField=customfield_10002`)).toBe(true),
    );

    await user.click(screen.getByRole("tab", { name: "Work categories" }));
    expect(await screen.findByText("Bug")).toBeInTheDocument();
  });

  test("saving flips the connection from not-configured to configured", async () => {
    serve(mockFetch, { configAfterSave: baseConfig(true) });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(showSuccessToast).toHaveBeenCalledWith("Saved — reports re-derive shortly"));
    await waitFor(() => expect(screen.queryByText("Showing computed defaults — save to confirm them")).not.toBeInTheDocument());
  });

  test("picking a stage and toggling blocked updates the PUT body", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(await screen.findByRole("combobox", { name: "Stage for Done" }));
    await user.click(await screen.findByRole("option", { name: "Done" }));
    await user.click(screen.getByLabelText("Blocked for In Progress"));

    await user.click(screen.getByRole("button", { name: /^save$/i }));

    const putCall = await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
      expect(call).toBeTruthy();
      return call!;
    });
    const body = JSON.parse((putCall[1] as RequestInit).body as string);
    expect(body.statusStages).toEqual(
      expect.arrayContaining([
        { statusId: "3", stage: "IN_PROGRESS" },
        { statusId: "10", stage: "DONE" },
      ]),
    );
    expect(body.blockedStatuses).toEqual(["3"]);
  });

  test("shows the stored owner team for a domain", async () => {
    serve(mockFetch, {
      config: {
        ...baseConfig(true),
        domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: 5 }],
      },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Board A");
    await user.click(screen.getByRole("tab", { name: "Domains" }));

    const ownerSelect = (await screen.findByRole("combobox", { name: "Owner team for ENG" })) as HTMLInputElement;
    await waitFor(() => expect(ownerSelect.value).toBe("Team A"));
  });

  test("changing the owner team on one row updates every row sharing the same domain key", async () => {
    serve(mockFetch, {
      config: {
        ...baseConfig(true),
        domains: [
          { projectKey: "ENG", domainKey: "SHARED", domainName: "Shared", ownerTeamId: 5 },
          { projectKey: "ENG2", domainKey: "SHARED", domainName: "Shared", ownerTeamId: 5 },
        ],
      },
      optionsOverride: { projects: ["ENG", "ENG2"] },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Board A");
    await user.click(screen.getByRole("tab", { name: "Domains" }));

    const ownerSelectEng = await screen.findByRole("combobox", { name: "Owner team for ENG" });
    await user.click(ownerSelectEng);
    await user.click(await screen.findByRole("option", { name: "Team B" }));

    const ownerSelectEng2 = (await screen.findByRole("combobox", { name: "Owner team for ENG2" })) as HTMLInputElement;
    await waitFor(() => expect(ownerSelectEng2.value).toBe("Team B"));

    await user.click(screen.getByRole("button", { name: /^save$/i }));

    const putCall = await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
      expect(call).toBeTruthy();
      return call!;
    });
    const body = JSON.parse((putCall[1] as RequestInit).body as string);
    expect(body.domains).toEqual([
      { projectKey: "ENG", domainKey: "SHARED", domainName: "Shared", ownerTeamId: 6 },
      { projectKey: "ENG2", domainKey: "SHARED", domainName: "Shared", ownerTeamId: 6 },
    ]);
  });

  test("editing domains, activity types, a chosen work category and a sprint capacity updates the PUT body", async () => {
    serve(mockFetch, {
      config: { ...baseConfig(true), workCategories: [{ valueId: "v1", valueName: "Bug", category: "Stored default" }] },
      optionsOverride: { sprints: [{ sprintId: 11, boardId: 1, name: "Sprint 1", state: "active" }] },
      workCategoryValuesByField: { customfield_10002: [{ valueId: "v1", valueName: "Bug" }] },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Board A"); // waits for full initial load without asserting on the banner (config is `configured: true` here)

    await user.click(screen.getByRole("tab", { name: "Domains" }));
    const domainKeyInput = screen.getByLabelText("Domain key for ENG") as HTMLInputElement;
    expect(domainKeyInput.maxLength).toBe(50); // the server's column widths, mirrored so an over-long value is never typed
    await user.clear(domainKeyInput);
    await user.type(domainKeyInput, "ENGINEERING");
    const domainNameInput = screen.getByLabelText("Domain name for ENG") as HTMLInputElement;
    expect(domainNameInput.maxLength).toBe(100);
    await user.clear(domainNameInput);
    await user.type(domainNameInput, "Engineering");

    await user.click(screen.getByRole("tab", { name: "Activity types" }));
    const activityInput = screen.getByLabelText("Activity type for Story") as HTMLInputElement;
    await user.clear(activityInput);
    await user.type(activityInput, "Feature work");

    await user.click(screen.getByRole("tab", { name: "Fields" }));
    await user.click(screen.getByRole("combobox", { name: "Work-category field" }));
    await user.click(await screen.findByRole("option", { name: "Category (Other)" }));
    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url]) => url === `${OPTIONS_URL}?workCategoryField=customfield_10002`)).toBe(true),
    );

    await user.click(screen.getByRole("tab", { name: "Work categories" }));
    const categoryInput = (await screen.findByLabelText("Category for Bug")) as HTMLInputElement;
    expect(categoryInput.value).toBe("Stored default");
    expect(categoryInput.maxLength).toBe(100);
    await user.clear(categoryInput);
    await user.type(categoryInput, "Defect");

    await user.click(screen.getByRole("tab", { name: "Capacities" }));
    const capacityInput = screen.getByLabelText("Capacity for Sprint 1") as HTMLInputElement;
    await user.type(capacityInput, "10");

    await user.click(screen.getByRole("button", { name: /^save$/i }));

    const putCall = await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
      expect(call).toBeTruthy();
      return call!;
    });
    const body = JSON.parse((putCall[1] as RequestInit).body as string);
    expect(body.domains).toEqual([{ projectKey: "ENG", domainKey: "ENGINEERING", domainName: "Engineering", ownerTeamId: null }]);
    expect(body.activityTypes).toEqual([{ issueType: "Story", activityType: "Feature work" }]);
    expect(body.fields.workCategory).toBe("customfield_10002");
    expect(body.workCategories).toEqual([{ valueId: "v1", valueName: "Bug", category: "Defect" }]);
    expect(body.sprintCapacities).toEqual([{ sprintId: 11, capacityMd: 10 }]);
  }, 20_000); // a long multi-tab editing journey: well under a second locally, but past vitest's 5 s default on a loaded CI runner

  function putBodyOf(): { domainStatusStages: unknown } {
    const call = mockFetch.mock.calls.find(([url, init]) => url === CONFIG_URL && (init as RequestInit | undefined)?.method === "PUT");
    expect(call).toBeTruthy();
    return JSON.parse((call![1] as RequestInit).body as string);
  }

  test("adds a per-domain override that differs from the every-domain stage, and the PUT carries it", async () => {
    serve(mockFetch, { config: baseConfig(true) });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("Per-domain overrides")).toBeInTheDocument();
    expect(screen.getByText("No overrides: every domain uses the stages above.")).toBeInTheDocument();
    expect(screen.queryByText("Differs")).not.toBeInTheDocument();

    const overrideSelect = (await screen.findByRole("combobox", { name: "Stage for In Progress in domain ENG" })) as HTMLInputElement;
    expect(overrideSelect.value).toBe("");
    expect(overrideSelect.placeholder).toBe("Same as all domains");
    await user.click(overrideSelect);
    await user.click(await screen.findByRole("option", { name: "Done" }));

    await waitFor(() => expect(overrideSelect.value).toBe("Done"));
    expect(screen.getByText("Differs")).toBeInTheDocument();
    expect(screen.getByText("Overrides in total: 1")).toBeInTheDocument();
    // The every-domain table is untouched.
    expect(((await screen.findByRole("combobox", { name: "Stage for In Progress" })) as HTMLInputElement).value).toBe("In progress");

    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalled());
    expect(putBodyOf().domainStatusStages).toEqual([{ domainKey: "ENG", statusId: "3", stage: "DONE" }]);
  });

  test("changes and removes stored overrides, flags only the ones that differ, and the PUT is the remainder", async () => {
    serve(mockFetch, {
      config: {
        ...baseConfig(true),
        domainStatusStages: [
          { domainKey: "ENG", statusId: "3", stage: "IN_PROGRESS" }, // same as the every-domain stage: stored, not flagged
          { domainKey: "ENG", statusId: "10", stage: "NOT_STARTED" }, // differs: "Done" has no every-domain stage
        ],
      },
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("Overrides in total: 2")).toBeInTheDocument();
    expect(screen.getAllByText("Differs")).toHaveLength(1);
    const doneOverride = screen.getByRole("combobox", { name: "Stage for Done in domain ENG" }) as HTMLInputElement;
    expect(doneOverride.value).toBe("Not started");

    // Change it, then remove the other one with its own button.
    await user.click(doneOverride);
    await user.click(await screen.findByRole("option", { name: "In progress" }));
    await waitFor(() => expect(doneOverride.value).toBe("In progress"));
    await user.click(screen.getByRole("button", { name: "Remove the override for In Progress in domain ENG" }));
    expect(screen.getByText("Overrides in total: 1")).toBeInTheDocument();
    expect((screen.getByRole("combobox", { name: "Stage for In Progress in domain ENG" }) as HTMLInputElement).value).toBe("");
    expect(screen.queryByRole("button", { name: "Remove the override for In Progress in domain ENG" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalled());
    expect(putBodyOf().domainStatusStages).toEqual([{ domainKey: "ENG", statusId: "10", stage: "IN_PROGRESS" }]);
  });

  test("renaming a domain key flags its overrides inline, and the server's 400 shows too", async () => {
    serve(mockFetch, {
      config: { ...baseConfig(true), domainStatusStages: [{ domainKey: "ENG", statusId: "3", stage: "DONE" }] },
      putStatus: 400,
      putBody: {
        title: "Bad Request",
        status: 400,
        detail: "Unknown domain key in domainStatusStages: ENG",
        instance: "/x",
        type: "about:blank",
      },
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Overrides in total: 1");
    await user.click(screen.getByRole("tab", { name: "Domains" }));
    const domainKeyInput = screen.getByLabelText("Domain key for ENG") as HTMLInputElement;
    await user.clear(domainKeyInput);
    await user.type(domainKeyInput, "ENGINEERING");
    await user.click(screen.getByRole("tab", { name: "Statuses" }));

    expect(await screen.findByText(/"ENG" is no longer a domain/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText("Unknown domain key in domainStatusStages: ENG")).toBeInTheDocument();
  });

  test("an override on a status that left the connection is shown, and saving after removing it succeeds", async () => {
    serve(mockFetch, {
      config: { ...baseConfig(true), domainStatusStages: [{ domainKey: "ENG", statusId: "gone", stage: "DONE" }] },
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText(/Status gone is no longer reported by this connection/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove the override for unknown status gone in domain ENG" }));
    expect(screen.queryByText(/no longer reported/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalled());
    expect(putBodyOf().domainStatusStages).toEqual([]);
  });

  test("shows the not-found message when the connection no longer exists", async () => {
    serve(mockFetch, { configErrorStatus: 404, optionsErrorStatus: 404 });
    renderPage();

    expect(await screen.findByText("This data source does not exist (or was deleted).")).toBeInTheDocument();
  });

  test("falls back to the generic message when a 400 response carries no detail", async () => {
    serve(mockFetch, { putStatus: 400, putBody: {} });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Could not save the metrics configuration")).toBeInTheDocument();
  });

  test("a board-team 409 without a server detail still marks the row with the fallback message", async () => {
    serve(mockFetch, { putStatus: 409, putBody: {} });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("tab", { name: "Boards" }));
    await user.click(await screen.findByRole("combobox", { name: "Team for Board A" }));
    await user.click(await screen.findByRole("option", { name: "Team A" }));
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    const rowA = (await screen.findByText("Board A")).closest("tr");
    expect(rowA!.textContent).toContain("This team is already mapped to another board");
  });

  test("shows the fixed forbidden message on a 403 save", async () => {
    serve(mockFetch, { putStatus: 403 });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Only administrators can edit the metrics configuration")).toBeInTheDocument();
  });

  test("shows the generic message on an unexpected save failure", async () => {
    serve(mockFetch, { putStatus: 500 });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Showing computed defaults — save to confirm them");
    await user.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Could not save the metrics configuration")).toBeInTheDocument();
  });
});
