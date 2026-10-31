import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
import type { DataSourceResponse } from "../api/dataSources";
import DataSourceEditorModal from "./DataSourceEditorModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";

describe("DataSourceEditorModal", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

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

  describe("the GitLab kind", () => {
    const KINDS = (enabled: boolean) => ({
      items: [
        { kind: "JIRA_CLOUD", enabled: true, allowedOrigins: [], defaultPathExclusions: [] },
        {
          kind: "GITLAB",
          enabled,
          allowedOrigins: enabled ? ["http://gitlab-stub:8080"] : [],
          defaultPathExclusions: ["package-lock.json", "*.min.js"],
        },
      ],
    });

    const STORED_GITLAB = {
      id: 9,
      kind: "GITLAB",
      name: "Platform GitLab",
      enabled: true,
      syncIntervalMinutes: 60,
      backfillFrom: "2024-01-01",
      reconcileHourUtc: 3,
      jira: null,
      gitlab: { baseUrl: "http://gitlab-stub:8080", hasToken: true, groupPaths: ["platform"], pathExclusions: ["go.sum"] },
    } as unknown as DataSourceResponse;

    type Call = { method: string; url: string; body: unknown };

    /** Serves the kinds read (and the metrics settings), records every write, and answers POSTs with [writeStatus]/[writeBody]. */
    function serve(opts: { enabled?: boolean; writeStatus?: number; writeBody?: unknown } = {}) {
      const calls: Call[] = [];
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        if (url.includes("/api/v1/data-sources/kinds")) return Promise.resolve(jsonResponse(200, KINDS(opts.enabled ?? true)));
        if (url.includes("/api/v1/metrics-settings")) return Promise.resolve(jsonResponse(200, { historyHorizonFrom: null }));
        calls.push({ method: init?.method ?? "GET", url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        return Promise.resolve(jsonResponse(opts.writeStatus ?? 200, opts.writeBody ?? { rows: [] }));
      });
      return calls;
    }

    async function pickGitLab(user: ReturnType<typeof userEvent.setup>, modal: HTMLElement) {
      await vi.waitFor(() => expect(within(modal).getByRole("radio", { name: "GitLab" })).toBeEnabled());
      await user.click(within(modal).getByRole("radio", { name: "GitLab" }));
    }

    async function fillGitLabFields(user: ReturnType<typeof userEvent.setup>, modal: HTMLElement) {
      await user.type(within(modal).getByLabelText("Base URL"), "http://gitlab-stub:8080");
      await user.type(within(modal).getByLabelText("Access token"), "glpat-secret");
      await user.type(within(modal).getByRole("combobox", { name: "Group paths" }), "Platform{Enter}apps/Web{Enter}");
    }

    test("the picker swaps the connection block and empties the other kind's fields", async () => {
      serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      expect(within(modal).getByLabelText("Site URL")).toBeInTheDocument();
      expect(within(modal).queryByLabelText("Base URL")).not.toBeInTheDocument();
      await user.type(within(modal).getByLabelText("Site URL"), "https://typed.atlassian.net");

      await pickGitLab(user, modal);
      expect(within(modal).getByLabelText("Base URL")).toBeInTheDocument();
      expect(within(modal).getByLabelText("Access token")).toBeInTheDocument();
      expect(within(modal).getByRole("combobox", { name: "Group paths" })).toBeInTheDocument();
      expect(within(modal).queryByLabelText("Site URL")).not.toBeInTheDocument();
      expect(within(modal).queryByLabelText("Service-account email")).not.toBeInTheDocument();
      expect(within(modal).getByText(/Allowed: http:\/\/gitlab-stub:8080/)).toBeInTheDocument();

      await user.click(within(modal).getByRole("radio", { name: "Jira Cloud" }));
      expect(within(modal).getByLabelText("Site URL")).toHaveValue("");
    });

    test("creating a GitLab source posts the gitlab block (normalised paths) and no jira block", async () => {
      const calls = serve();
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");

      await user.type(within(modal).getByLabelText("Name"), "Platform GitLab");
      await pickGitLab(user, modal);
      await fillGitLabFields(user, modal);
      await user.click(within(modal).getByRole("button", { name: /^create$/i }));

      await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
      const create = calls.find((call) => call.method === "POST" && call.url.endsWith("/api/v1/data-sources"));
      expect(create?.body).toMatchObject({
        name: "Platform GitLab",
        gitlab: {
          baseUrl: "http://gitlab-stub:8080",
          token: "glpat-secret",
          groupPaths: ["platform", "apps/web"],
        },
      });
      // Untouched defaults are omitted, so the source keeps following the server's list.
      expect(create?.body).not.toHaveProperty("gitlab.pathExclusions");
      expect(create?.body).not.toHaveProperty("jira");
    });

    test("picking GitLab prefills the server's default excluded paths; removing one and Reset to defaults restores both", async () => {
      serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      await pickGitLab(user, modal);

      expect(within(modal).getByRole("combobox", { name: "Excluded paths" })).toBeInTheDocument();
      expect(within(modal).getByText(/Applied when commits are processed/)).toBeInTheDocument();
      expect(within(modal).getByText("package-lock.json")).toBeInTheDocument();
      expect(within(modal).getByText("*.min.js")).toBeInTheDocument();
      const reset = within(modal).getByRole("button", { name: "Reset to defaults" });
      expect(reset).toBeDisabled();

      // Backspace in the empty input removes the last pill.
      await user.click(within(modal).getByRole("combobox", { name: "Excluded paths" }));
      await user.keyboard("{Backspace}");
      expect(within(modal).queryByText("*.min.js")).not.toBeInTheDocument();
      expect(reset).toBeEnabled();

      await user.click(reset);
      expect(within(modal).getByText("package-lock.json")).toBeInTheDocument();
      expect(within(modal).getByText("*.min.js")).toBeInTheDocument();
      expect(reset).toBeDisabled();
    });

    /** Creates a GitLab source after [edits] ran on the prefilled Excluded paths field; returns the POST body's `gitlab` block. */
    async function createAfter(edits: (user: ReturnType<typeof userEvent.setup>, modal: HTMLElement) => Promise<void>) {
      const calls = serve();
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");
      await user.type(within(modal).getByLabelText("Name"), "Platform GitLab");
      await pickGitLab(user, modal);
      await fillGitLabFields(user, modal);
      await edits(user, modal);
      await user.click(within(modal).getByRole("button", { name: /^create$/i }));

      await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
      const create = calls.find((call) => call.method === "POST" && call.url.endsWith("/api/v1/data-sources"));
      return (create?.body as { gitlab: Record<string, unknown> }).gitlab;
    }

    const backspaces = (count: number) => async (user: ReturnType<typeof userEvent.setup>, modal: HTMLElement) => {
      await user.click(within(modal).getByRole("combobox", { name: "Excluded paths" }));
      await user.keyboard("{Backspace}".repeat(count));
    };

    test("a create with one default removed sends the remaining list", async () => {
      expect((await createAfter(backspaces(1))).pathExclusions).toEqual(["package-lock.json"]);
    });

    test("a create with every default removed sends an empty list", async () => {
      expect(await createAfter(backspaces(2))).toHaveProperty("pathExclusions", []);
    });

    test("a create after removing a default and clicking Reset to defaults omits the list again", async () => {
      const gitlab = await createAfter(async (user, modal) => {
        await backspaces(1)(user, modal);
        await user.click(within(modal).getByRole("button", { name: "Reset to defaults" }));
      });
      expect(gitlab).not.toHaveProperty("pathExclusions");
    });

    test("switching back to Jira empties the excluded paths and the Jira block carries none", async () => {
      serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      await pickGitLab(user, modal);
      await user.click(within(modal).getByRole("radio", { name: "Jira Cloud" }));
      expect(within(modal).queryByRole("combobox", { name: "Excluded paths" })).not.toBeInTheDocument();
      await pickGitLab(user, modal);
      expect(within(modal).getByText("package-lock.json")).toBeInTheDocument();
    });

    test("an instance off the server's allow-list is refused client-side, naming the allowed origins", async () => {
      const calls = serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");

      await user.type(within(modal).getByLabelText("Name"), "Elsewhere");
      await pickGitLab(user, modal);
      await user.type(within(modal).getByLabelText("Base URL"), "https://gitlab.elsewhere.example");
      await user.type(within(modal).getByLabelText("Access token"), "glpat-secret");
      await user.type(within(modal).getByRole("combobox", { name: "Group paths" }), "platform{Enter}");
      await user.click(within(modal).getByRole("button", { name: /^create$/i }));

      expect(await within(modal).findByText(/not on the server's allow-list\. Allowed: http:\/\/gitlab-stub:8080/)).toBeInTheDocument();
      expect(calls.filter((call) => call.method === "POST")).toEqual([]);
    });

    test("Test connection posts the ad-hoc gitlab body and prints each row, failure codes verbatim", async () => {
      const calls = serve({
        writeBody: {
          rows: [
            { name: "version", path: "/api/v4/version", required: true, ok: true, status: 200 },
            { name: "token_scopes", path: "/api/v4/personal_access_tokens/self", required: true, ok: false, code: "TOKEN_SCOPE_TOO_BROAD" },
            { name: "user_admin", path: "/api/v4/user", required: false, ok: false, code: "NOT_ADMIN" },
          ],
          cloudId: null,
        },
      });
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      await pickGitLab(user, modal);
      await fillGitLabFields(user, modal);
      await user.click(within(modal).getByRole("button", { name: "Test connection" }));

      expect(await within(modal).findByText("TOKEN_SCOPE_TOO_BROAD")).toBeInTheDocument();
      expect(within(modal).getByText("NOT_ADMIN")).toBeInTheDocument();
      expect(within(modal).queryByText(/Cloud ID/)).not.toBeInTheDocument();
      const probe = calls.find((call) => call.url.endsWith("/api/v1/data-sources/test"));
      expect(probe?.body).toEqual({
        gitlab: {
          baseUrl: "http://gitlab-stub:8080",
          token: "glpat-secret",
          groupPaths: ["platform", "apps/web"],
        },
      });
    });

    test("Test connection validates only the GitLab fields it needs", async () => {
      const calls = serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");
      await pickGitLab(user, modal);
      await user.click(within(modal).getByRole("button", { name: "Test connection" }));

      expect(await within(modal).findByText("An access token is required")).toBeInTheDocument();
      expect(within(modal).getByText("Enter 1–10 group paths")).toBeInTheDocument();
      expect(within(modal).queryByText("Name must be 1–100 characters")).not.toBeInTheDocument();
      expect(calls.filter((call) => call.method === "POST")).toEqual([]);
    });

    test("GitLab is disabled with the server hint when the kinds read says it is off", async () => {
      serve({ enabled: false });
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");

      expect(await within(modal).findByText("GitLab is not enabled on this server (GITLAB_ALLOWED_HOSTS)")).toBeInTheDocument();
      expect(within(modal).getByRole("radio", { name: "GitLab" })).toBeDisabled();
      expect(within(modal).getByRole("radio", { name: "Jira Cloud" })).toBeEnabled();
    });

    test.each([
      ["urn:flow:problem:data-source-other-instance", "Base URL", "All GitLab data sources must read the same GitLab instance as the existing ones"],
      [
        "urn:flow:problem:data-source-group-overlap",
        "Group paths",
        "One or more of these groups overlap a group another GitLab data source already reads",
      ],
    ])("a 409 of type %s marks %s with fixed words", async (type, label, message) => {
      serve({ writeStatus: 409, writeBody: { type, title: "Conflict", status: 409, detail: "server-side words platform" } });
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");

      await user.type(within(modal).getByLabelText("Name"), "Platform GitLab");
      await pickGitLab(user, modal);
      await fillGitLabFields(user, modal);
      await user.click(within(modal).getByRole("button", { name: /^create$/i }));

      expect(await within(modal).findByText(message)).toBeInTheDocument();
      expect(within(modal).queryByText(/server-side words/)).not.toBeInTheDocument();
      const field = label === "Group paths" ? within(modal).getByRole("combobox", { name: label }) : within(modal).getByLabelText(label);
      expect(field).toHaveAttribute("aria-invalid", "true");
      expect(onSaved).not.toHaveBeenCalled();
    });

    test("editing a GitLab source prefills it, fixes the kind and base URL, and tests the STORED connection with a blank token", async () => {
      const calls = serve();
      const user = userEvent.setup();
      renderWithProviders(<DataSourceEditorModal target={STORED_GITLAB} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");

      expect(within(modal).getByRole("radio", { name: "GitLab" })).toBeChecked();
      expect(within(modal).getByRole("radio", { name: "GitLab" })).toBeDisabled();
      expect(within(modal).getByRole("radio", { name: "Jira Cloud" })).toBeDisabled();
      expect(within(modal).getByLabelText("Base URL")).toHaveValue("http://gitlab-stub:8080");
      expect(within(modal).getByLabelText("Base URL")).toBeDisabled();
      expect(within(modal).getByLabelText("Access token")).toHaveValue("");
      expect(within(modal).getByText("Leave blank to keep the current token")).toBeInTheDocument();
      expect(within(modal).getByText("platform")).toBeInTheDocument();
      // The stored list is kept, never the defaults.
      expect(within(modal).getByText("go.sum")).toBeInTheDocument();
      expect(within(modal).queryByText("package-lock.json")).not.toBeInTheDocument();
      expect(within(modal).getByRole("button", { name: "Reset to defaults" })).toBeEnabled();
      expect(within(modal).queryByLabelText("Site URL")).not.toBeInTheDocument();

      await user.click(within(modal).getByRole("button", { name: "Test connection" }));
      await vi.waitFor(() => expect(calls.some((call) => call.url.endsWith("/api/v1/data-sources/9/test"))).toBe(true));
      expect(calls.find((call) => call.url.endsWith("/data-sources/9/test"))?.body).toBeUndefined();
    });

    test("saving an edited GitLab source with a blank token PUTs the gitlab block without a token and no jira key", async () => {
      const calls = serve();
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={STORED_GITLAB} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");

      await user.click(within(modal).getByRole("button", { name: /^save$/i }));

      await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
      const put = calls.find((call) => call.method === "PUT");
      expect(put?.body).not.toHaveProperty("jira");
      expect(put?.body).toHaveProperty("gitlab.baseUrl", "http://gitlab-stub:8080");
      expect(put?.body).not.toHaveProperty("gitlab.token");
      // Untouched: a PUT without the list keeps the stored one.
      expect(put?.body).not.toHaveProperty("gitlab.pathExclusions");
    });

    test("saving an edited GitLab source whose excluded paths were emptied PUTs an empty list", async () => {
      const calls = serve();
      const user = userEvent.setup();
      const onSaved = vi.fn().mockResolvedValue(undefined);
      renderWithProviders(<DataSourceEditorModal target={STORED_GITLAB} onClose={vi.fn()} onSaved={onSaved} />);
      const modal = screen.getByRole("dialog");
      await backspaces(1)(user, modal);
      await user.click(within(modal).getByRole("button", { name: /^save$/i }));

      await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
      expect(calls.find((call) => call.method === "PUT")?.body).toHaveProperty("gitlab.pathExclusions", []);
    });

    test("a failed kinds read keeps GitLab off and says the server could not be asked", async () => {
      mockFetch.mockImplementation((url: string) =>
        Promise.resolve(url.includes("/api/v1/data-sources/kinds") ? jsonResponse(500, {}) : jsonResponse(200, { historyHorizonFrom: null })),
      );
      renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);
      const modal = screen.getByRole("dialog");

      expect(await within(modal).findByText(/Could not check which kinds this server offers/)).toBeInTheDocument();
      expect(within(modal).getByRole("radio", { name: "GitLab" })).toBeDisabled();
    });
  });
});
