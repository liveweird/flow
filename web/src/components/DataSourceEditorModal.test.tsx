import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
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
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "boom", status: 500 }));
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
    mockFetch.mockResolvedValue(
      jsonResponse(409, { type, title: "Conflict", status: 409, detail: "server-side words ENG", instance: "/api/v1/data-sources/9" }),
    );
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
    mockFetch.mockResolvedValue(jsonResponse(429, { title: "slow down", status: 429 }));
    const user = userEvent.setup();
    renderWithProviders(<DataSourceEditorModal target={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    const modal = screen.getByRole("dialog");
    await fillJiraFields(user, modal);
    await user.click(within(modal).getByRole("button", { name: "Test connection" }));

    expect(await within(modal).findByText("Too many test attempts — wait a minute and try again")).toBeInTheDocument();
    expect(within(modal).queryByText("Endpoint")).not.toBeInTheDocument();
  });
});
