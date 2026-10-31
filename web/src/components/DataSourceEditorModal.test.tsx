import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
import type { DataSourceResponse } from "../api/dataSources";
import DataSourceEditorModal from "./DataSourceEditorModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

async function fillJiraFields(user: ReturnType<typeof userEvent.setup>, modal: HTMLElement) {
  await user.type(within(modal).getByLabelText("Site URL"), "https://newsite.atlassian.net");
  await user.type(within(modal).getByLabelText("Service-account email"), "svc@newsite.com");
  await user.type(within(modal).getByLabelText("API token"), "secret-token");
  await user.type(within(modal).getByRole("combobox", { name: "Project keys" }), "eng{Enter}");
}

describe("DataSourceEditorModal", () => {
  let mockFetch: FetchMock;

  /** Every request (the modal also reads the metrics settings) gets a FRESH response: a Response body can be read once. */
  function respondWith(status: number, body: unknown) {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(status, body)));
  }

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

  test("a non-409 save failure renders the generic save-error alert, not the name-field conflict", async () => {
    respondWith(500, { title: "boom", status: 500 });
    const user = userEvent.setup();
    const onSaved = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={onSaved} />);

    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "New Co");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("Save failed (500)")).toBeInTheDocument();
    expect(within(modal).getByLabelText("Name")).not.toHaveAttribute("aria-invalid", "true");
    expect(onSaved).not.toHaveBeenCalled();
  });

  test.each([
    ["about:blank", "Name", "A data source with this name already exists"],
    ["urn:flow:problem:data-source-other-site", "Site URL", "All data sources must use the same Jira site as the existing ones"],
    [
      "urn:flow:problem:data-source-project-overlap",
      "Project keys",
      "One or more of these project keys already belong to another data source",
    ],
  ])("a 409 of type %s marks the %s field with fixed words, never the server detail", async (type, label, message) => {
    respondWith(409, { type, title: "Conflict", status: 409, detail: "server-side words ENG", instance: "/api/v1/data-sources/9" });
    const user = userEvent.setup();
    const onSaved = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={onSaved} />);

    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), "New Co");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));

    expect(await within(modal).findByText(message)).toBeInTheDocument();
    expect(within(modal).queryByText(/server-side words/)).not.toBeInTheDocument();
    expect(within(modal).queryByText("Save failed (409)")).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    if (label === "Project keys") {
      expect(within(modal).getByRole("combobox", { name: label })).toHaveAttribute("aria-invalid", "true");
    } else {
      expect(within(modal).getByLabelText(label)).toHaveAttribute("aria-invalid", "true");
    }
  });

  test("a failed test-connection call renders the mapped error, not the results table", async () => {
    respondWith(429, { title: "slow down", status: 429 });
    const user = userEvent.setup();
    renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    const modal = screen.getByRole("dialog");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: "Test connection" }));

    expect(await within(modal).findByText("Too many test attempts — wait a minute and try again")).toBeInTheDocument();
    expect(within(modal).queryByText("Endpoint")).not.toBeInTheDocument();
  });

  describe("the history horizon floor", () => {
    const SETTINGS = { historyHorizonFrom: "2025-06-15" };

    /** Serves the metrics settings and kinds GETs and answers every write with 204; returns the writes seen. */
    function serveSettings() {
      const writes: string[] = [];
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        if (url.includes("/api/v1/metrics-settings")) return Promise.resolve(jsonResponse(200, SETTINGS));
        if (url.includes("/api/v1/data-sources/kinds")) return Promise.resolve(jsonResponse(200, { items: [] }));
        writes.push(`${init?.method ?? "GET"} ${init?.body ?? ""}`);
        return Promise.resolve(new Response(null, { status: 204 }));
      });
      return writes;
    }

    const STORED = {
      id: 7,
      kind: "JIRA_CLOUD",
      name: "Existing",
      enabled: true,
      syncIntervalMinutes: 60,
      backfillFrom: "2024-01-01",
      reconcileHourUtc: 3,
      jira: { siteUrl: "https://acme.atlassian.net", email: "svc@acme.com", hasApiToken: true, projectKeys: ["ENG"], authScheme: "BASIC" },
    } as unknown as DataSourceResponse;

    test("create: a backfill date before the horizon is refused client-side and the hint names the floor", async () => {
      const writes = serveSettings();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      expect(await within(modal).findByText(/allows dates from 2025-06-15 on/)).toBeInTheDocument();

      await user.type(within(modal).getByLabelText("Name"), "New Co");
      await fillJiraFields(user, modal);
      await user.type(within(modal).getByLabelText("Backfill from"), "2025-06-14");
      await user.click(within(modal).getByRole("button", { name: /^create$/i }));

      expect(await within(modal).findByText("Backfill date must not be before 2025-06-15 (the history horizon)")).toBeInTheDocument();
      expect(writes).toEqual([]);
    });

    test("edit: moving the date earlier is refused, the unchanged older stored date saves", async () => {
      const writes = serveSettings();
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={STORED} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");
      expect(await within(modal).findByText(/allows dates from 2025-06-15 on/)).toBeInTheDocument();

      const field = within(modal).getByLabelText("Backfill from");
      await user.clear(field);
      await user.type(field, "2023-12-31");
      await user.click(within(modal).getByRole("button", { name: /^save$/i }));
      expect(await within(modal).findByText("Backfill date must not be before 2025-06-15 (the history horizon)")).toBeInTheDocument();
      expect(writes).toEqual([]);

      await user.clear(field);
      await user.type(field, "2024-01-01");
      await user.click(within(modal).getByRole("button", { name: /^save$/i }));
      await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
      expect(writes).toHaveLength(1);
    });
  });
});
