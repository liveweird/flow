import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import CodeIdentityLinkModal from "./CodeIdentityLinkModal";
import type { CodeIdentityResponse } from "../api/codeIdentities";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const IDENTITY: CodeIdentityResponse = {
  identityKey: "e0f45686a7f36e47065f3265969dc831",
  kind: "EMAIL",
  userId: null,
  username: null,
  displayName: "Sample Contractor",
  state: null,
  bot: false,
  deleted: false,
  emails: ["contractor.sample@mail.example"],
  status: "UNMAPPED",
  seen: true,
  proposal: null,
  decision: null,
};

const PEOPLE = {
  items: [
    { accountId: "acc-2", displayName: "Sample User 2" },
    { accountId: "acc-3", displayName: "Sample User 3" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

describe("CodeIdentityLinkModal", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function renderModal(onLinked = async () => {}, identity = IDENTITY) {
    return renderWithProviders(<CodeIdentityLinkModal dataSourceId={5} identity={identity} onClose={() => {}} onLinked={onLinked} />);
  }

  test("lists the whole site directory, and saves the chosen person as a LINKED decision", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    const linked = vi.fn(async () => {});
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, PEOPLE));
      if (init?.method === "PUT") return Promise.resolve(jsonResponse(200, IDENTITY));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderModal(linked);

    const picker = await screen.findByRole("combobox", { name: "Jira person" });
    await waitFor(() => expect(picker).toHaveAttribute("aria-busy", "false"));
    expect(new URL(mockFetch.mock.calls[0][0] as string, "http://test").searchParams.get("scope")).toBe("SITE");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.click(picker);
    await user.click(await screen.findByRole("option", { name: "Sample User 2 (acc-2)" }));
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(linked).toHaveBeenCalled());
    const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === "PUT") as [string, RequestInit];
    expect(url).toBe("/api/v1/data-sources/5/code-identities/e0f45686a7f36e47065f3265969dc831");
    expect(JSON.parse(init.body as string)).toEqual({ decision: "LINKED", jiraAccountId: "acc-2" });
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Linked" }));
  });

  test("preselects the current link", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, PEOPLE));
    renderModal(async () => {}, {
      ...IDENTITY,
      status: "LINKED",
      decision: { decision: "LINKED", jiraAccountId: "acc-3", jiraDisplayName: "Sample User 3", decidedByUserId: 1, decidedAt: "2026-10-31T09:05:00Z" },
    });

    expect(await screen.findByRole("combobox", { name: "Jira person" })).toHaveValue("Sample User 3");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  test("a 400 stays inline in the modal and no toast is shown", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, PEOPLE));
      if (init?.method === "PUT") return Promise.resolve(jsonResponse(400, { title: "Bad Request", status: 400 }));
      return Promise.resolve(jsonResponse(404, {}));
    });
    const user = userEvent.setup();
    renderModal();

    const picker = await screen.findByRole("combobox", { name: "Jira person" });
    await waitFor(() => expect(picker).toHaveAttribute("aria-busy", "false"));
    await user.click(picker);
    await user.click(await screen.findByRole("option", { name: "Sample User 3 (acc-3)" }));
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The request was rejected");
    expect(toast).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
