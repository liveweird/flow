import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import { Route, Routes, useLocation } from "react-router-dom";
import DataSourceIdentities from "./DataSourceIdentities";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const BASE = { kind: "USER", bot: false, deleted: false, seen: true, proposal: null, decision: null } as const;

const MATCHED = {
  ...BASE,
  identityKey: "u2",
  userId: 2,
  username: "sample.dev1",
  displayName: "Sample Dev One",
  state: "active",
  emails: ["user1@flow-sample.example", "sample.dev1@users.noreply.example"],
  status: "PROPOSED",
  proposal: { decision: "LINKED", jiraAccountId: "acc-1", jiraDisplayName: "Sample User 1" },
};
const BOT = {
  ...BASE,
  identityKey: "u8",
  userId: 8,
  username: "project_3_bot",
  displayName: "Project bot",
  state: "active",
  bot: true,
  emails: [],
  status: "PROPOSED",
  proposal: { decision: "BOT", jiraAccountId: null, jiraDisplayName: null },
};
const GHOST = { ...BASE, identityKey: "u9", userId: 9, username: null, displayName: null, state: null, emails: [], status: "UNMAPPED" };
const COMMIT_ONLY = {
  ...BASE,
  identityKey: "e0f45686a7f36e47065f3265969dc831",
  kind: "EMAIL",
  userId: null,
  username: null,
  displayName: "Sample Contractor",
  state: null,
  emails: ["contractor.sample@mail.example"],
  status: "UNMAPPED",
};
const DECIDED = {
  ...BASE,
  identityKey: "u4",
  userId: 4,
  username: "sample.dev3",
  displayName: "Sample Dev Three",
  state: "blocked",
  emails: ["sample.dev3@mail.example"],
  status: "LINKED",
  proposal: { decision: "LINKED", jiraAccountId: "acc-3", jiraDisplayName: "Sample User 3" },
  decision: { decision: "LINKED", jiraAccountId: "acc-9", jiraDisplayName: "Sample User 9", decidedByUserId: 1, decidedAt: "2026-10-31T09:05:00Z" },
};
const ORPHAN = {
  ...BASE,
  identityKey: "u77",
  userId: null,
  username: null,
  displayName: null,
  state: null,
  emails: [],
  seen: false,
  status: "IGNORED_OR_BOT",
  decision: { decision: "IGNORED", jiraAccountId: null, jiraDisplayName: null, decidedByUserId: 1, decidedAt: "2026-10-30T08:00:00Z" },
};

const PAGES: Record<string, unknown[]> = {
  PROPOSED: [MATCHED, BOT],
  UNMAPPED: [GHOST, COMMIT_ONLY],
  LINKED: [DECIDED],
  IGNORED_OR_BOT: [ORPHAN],
};

function SearchProbe() {
  return <div data-testid="search">{useLocation().search}</div>;
}

function renderPage(route = "/data-sources/5/identities") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/data-sources/:id/identities" element={<DataSourceIdentities />} />
      </Routes>
      <SearchProbe />
    </>,
    { route },
  );
}

function listUrls(mockFetch: FetchMock): URL[] {
  return mockFetch.mock.calls
    .filter(([url, init]) => typeof url === "string" && url.includes("/code-identities?") && (init?.method ?? "GET") === "GET")
    .map(([url]) => new URL(url as string, "http://test"));
}

function lastListUrl(mockFetch: FetchMock): URL {
  const urls = listUrls(mockFetch);
  return urls[urls.length - 1];
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function serve(mockFetch: FetchMock, extra?: (url: string, init: RequestInit | undefined) => Response | undefined) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const custom = extra?.(url, init);
    if (custom) return Promise.resolve(custom);
    if (/^\/api\/v1\/data-sources\/[56]\/code-identities\?/.test(url)) {
      const status = new URL(url, "http://test").searchParams.get("status") ?? "";
      const items = PAGES[status] ?? [];
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 20, total: items.length }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function installFetchMock(): FetchMock {
  const mockFetch = vi.fn();
  vi.stubGlobal("fetch", mockFetch);
  localStorage.setItem("flow.auth.token", "fake-token");
  localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
  return mockFetch;
}

function restoreFetchMock() {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
}

describe("DataSourceIdentities page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = installFetchMock();
  });

  afterEach(restoreFetchMock);

  test("opens on the Proposed tab: the request carries status, paging and sort, and the proposals read as proposals", async () => {
    serve(mockFetch);
    renderPage();

    const table = await screen.findByRole("table", { name: "Proposed" });
    const matched = (await within(table).findByText("Sample Dev One")).closest("tr") as HTMLElement;
    expect(within(matched).getByText("sample.dev1")).toBeInTheDocument();
    expect(within(matched).getByText("user1@flow-sample.example")).toBeInTheDocument();
    expect(within(matched).getByText("Sample User 1")).toBeInTheDocument();
    expect(within(matched).getByText("acc-1")).toBeInTheDocument();
    expect(within(matched).getByText("Proposed by e-mail")).toBeInTheDocument();
    const bot = within(table).getByText("Project bot").closest("tr") as HTMLElement;
    expect(within(bot).getAllByText("Bot").length).toBeGreaterThanOrEqual(2);

    const [first] = listUrls(mockFetch);
    expect(first.searchParams.get("status")).toBe("PROPOSED");
    expect(first.searchParams.get("page")).toBe("1");
    expect(first.searchParams.get("pageSize")).toBe("20");
    expect(first.searchParams.get("sort")).toBe("displayName");
    expect(screen.getByRole("tab", { name: "Proposed" })).toHaveAttribute("aria-selected", "true");
    expect(headingOutline()).toEqual([[2, "Identities"]]);
  });

  test("switching to Linked puts the status in the URL and the request; a decided row shows what was proposed beside the decision", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("tab", { name: "Linked" }));

    const row = (await screen.findByText("Sample Dev Three")).closest("tr") as HTMLElement;
    expect(screen.getByTestId("search")).toHaveTextContent("?status=LINKED");
    expect(listUrls(mockFetch).some((url) => url.searchParams.get("status") === "LINKED")).toBe(true);
    expect(within(row).getByText("Sample User 9")).toBeInTheDocument();
    expect(within(row).getByText("Proposed: Sample User 3")).toBeInTheDocument();
    expect(within(row).getByText("Blocked")).toBeInTheDocument();
    expect(within(row).getByText("2026-10-31 09:05")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Confirm all proposals/ })).not.toBeInTheDocument();
  });

  test("a status in the URL opens its tab", async () => {
    serve(mockFetch);
    renderPage("/data-sources/5/identities?status=UNMAPPED");
    expect(await screen.findByText("GitLab user #9")).toBeInTheDocument();
    expect(screen.getByText("contractor.sample@mail.example")).toBeInTheDocument();
    expect(screen.getByText("Commit e-mail only")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Unmapped" })).toHaveAttribute("aria-selected", "true");
  });

  test("an unknown status in the URL shows the Proposed tab", async () => {
    serve(mockFetch);
    renderPage("/data-sources/5/identities?status=bogus");
    expect(await screen.findByText("Sample Dev One")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Proposed" })).toHaveAttribute("aria-selected", "true");
  });

  test("an orphaned decision is badged, has no name, and offers only Remove", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/data-sources/5/identities?status=IGNORED_OR_BOT");

    const row = (await screen.findByText("u77")).closest("tr") as HTMLElement;
    expect(within(row).getByText("Not seen")).toBeInTheDocument();
    // The hint is visible text (reachable without a pointer), not a tooltip.
    expect(within(row).getByText("No data source on this GitLab instance still has this identity.")).toBeInTheDocument();
    await user.click(within(row).getByRole("button", { name: "Operations for u77" }));
    expect(await screen.findByRole("menuitem", { name: "Remove decision" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Ignore" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Mark as bot" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Link to a Jira person…" })).not.toBeInTheDocument();
  });

  test("Confirm all shows the count, asks first, POSTs and toasts the written counts", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    serve(mockFetch, (url, init) =>
      init?.method === "POST" && url === "/api/v1/data-sources/5/code-identities/confirm-proposals"
        ? jsonResponse(200, { linked: 6, bots: 1 })
        : undefined,
    );
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Confirm all proposals (2)" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/2 decisions/)).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: "Confirm all proposals" }));

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "6 linked, 1 marked as bots", color: "teal" })),
    );
    expect(mockFetch.mock.calls.some(([url, init]) => init?.method === "POST" && url.endsWith("/confirm-proposals"))).toBe(true);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("a single proposal is confirmed through the ordinary PUT with the proposal as the decision", async () => {
    serve(mockFetch, (_url, init) => (init?.method === "PUT" ? jsonResponse(200, MATCHED) : undefined));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Operations for Sample Dev One" }));
    await user.click(await screen.findByRole("menuitem", { name: "Confirm proposal" }));

    await waitFor(() => expect(mockFetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
    const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === "PUT") as [string, RequestInit];
    expect(url).toBe("/api/v1/data-sources/5/code-identities/u2");
    expect(JSON.parse(init.body as string)).toEqual({ decision: "LINKED", jiraAccountId: "acc-1" });
  });

  test("Ignore PUTs the IGNORED decision with no account", async () => {
    serve(mockFetch, (_url, init) => (init?.method === "PUT" ? jsonResponse(200, GHOST) : undefined));
    const user = userEvent.setup();
    renderPage("/data-sources/5/identities?status=UNMAPPED");

    await screen.findByText("GitLab user #9");
    await user.click(screen.getByRole("button", { name: "Operations for GitLab user #9" }));
    await user.click(await screen.findByRole("menuitem", { name: "Ignore" }));

    await waitFor(() => expect(mockFetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
    const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === "PUT") as [string, RequestInit];
    expect(url).toBe("/api/v1/data-sources/5/code-identities/u9");
    expect(JSON.parse(init.body as string)).toEqual({ decision: "IGNORED" });
  });

  test("a failed decision shows an inline alert, not a toast", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    serve(mockFetch, (_url, init) => (init?.method === "PUT" ? jsonResponse(404, { title: "Not Found", status: 404 }) : undefined));
    const user = userEvent.setup();
    renderPage("/data-sources/5/identities?status=UNMAPPED");

    await screen.findByText("GitLab user #9");
    await user.click(screen.getByRole("button", { name: "Operations for GitLab user #9" }));
    await user.click(await screen.findByRole("menuitem", { name: "Mark as bot" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("no longer exists");
    expect(toast).not.toHaveBeenCalled();
  });

  test("Remove decision goes through the delete confirm and DELETEs", async () => {
    serve(mockFetch, (_url, init) => (init?.method === "DELETE" ? new Response(null, { status: 204 }) : undefined));
    const user = userEvent.setup();
    renderPage("/data-sources/5/identities?status=LINKED");

    await screen.findByText("Sample Dev Three");
    await user.click(screen.getByRole("button", { name: "Operations for Sample Dev Three" }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove decision" }));
    const dialog = await screen.findByRole("dialog");
    expect(mockFetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() =>
      expect(mockFetch.mock.calls.some(([url, init]) => init?.method === "DELETE" && url === "/api/v1/data-sources/5/code-identities/u4")).toBe(true),
    );
  });

  test("a search goes to the server as q, and disables Confirm all while it is active", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.type(screen.getByLabelText("Search name, username or address"), "zoe");

    await waitFor(() => expect(listUrls(mockFetch).some((url) => url.searchParams.get("q") === "zoe")).toBe(true));
    expect(screen.getByRole("button", { name: /Confirm all proposals/ })).toBeDisabled();
    expect(screen.getByText("Clear the search to confirm all proposals.")).toBeInTheDocument();
  });

  test("removing an orphaned decision goes through the confirm, DELETEs its key and refreshes the list", async () => {
    let removed = false;
    serve(mockFetch, (url, init) => {
      if (init?.method === "DELETE") {
        removed = true;
        return new Response(null, { status: 204 });
      }
      if (removed && url.startsWith("/api/v1/data-sources/5/code-identities?")) return jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 });
      return undefined;
    });
    const user = userEvent.setup();
    renderPage("/data-sources/5/identities?status=IGNORED_OR_BOT");

    const row = (await screen.findByText("u77")).closest("tr") as HTMLElement;
    await user.click(within(row).getByRole("button", { name: "Operations for u77" }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove decision" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Remove the decision for u77? The identity goes back to proposed or unmapped.")).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    const [url] = (await waitFor(() => {
      const call = mockFetch.mock.calls.find(([, init]) => init?.method === "DELETE");
      expect(call).toBeDefined();
      return call;
    })) as [string];
    expect(url).toBe("/api/v1/data-sources/5/code-identities/u77");
    expect(await screen.findByText("Nothing ignored.")).toBeInTheDocument();
    expect(screen.queryByText("u77")).not.toBeInTheDocument();
  });

  test("clicking the identity header sorts descending by name", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    expect(screen.getByRole("columnheader", { name: /GitLab identity/ })).toHaveAttribute("aria-sort", "ascending");
    await user.click(screen.getByRole("button", { name: "GitLab identity" }));

    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("sort")).toBe("-displayName"));
    expect(screen.getByRole("columnheader", { name: /GitLab identity/ })).toHaveAttribute("aria-sort", "descending");
  });

  test("a 404 (or a Jira source's 409) renders the not-found state with a way back", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { title: "Conflict", status: 409 }));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent("This data source does not exist or is not a GitLab source.");
    expect(screen.getByRole("heading", { level: 2, name: "Identities" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the data source" })).toHaveAttribute("href", "/data-sources/5");
  });

  test("an empty tab says so", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    renderPage();
    expect(await screen.findByText("No proposals — every matching address is decided.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm all proposals (0)" })).toBeDisabled();
  });
});

describe("DataSourceIdentities page paging", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = installFetchMock();
  });

  afterEach(restoreFetchMock);

  const manyRows = (url: string) =>
    /code-identities\?/.test(url) ? jsonResponse(200, { items: [MATCHED], page: 1, pageSize: 20, total: 45 }) : undefined;

  test("the pager asks for the page it is clicked to", async () => {
    serve(mockFetch, manyRows);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    expect(screen.getByText("45 total")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("page")).toBe("2"));
  });

  test("a status change goes back to page 1", async () => {
    serve(mockFetch, manyRows);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("page")).toBe("2"));
    await user.click(screen.getByRole("tab", { name: "Linked" }));

    await waitFor(() => {
      const last = lastListUrl(mockFetch);
      expect(last.searchParams.get("status")).toBe("LINKED");
      expect(last.searchParams.get("page")).toBe("1");
    });
  });

  test("a search change goes back to page 1", async () => {
    serve(mockFetch, manyRows);
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("page")).toBe("2"));
    fireEvent.change(screen.getByLabelText("Search name, username or address"), { target: { value: "zoe" } });

    await waitFor(() => {
      const last = lastListUrl(mockFetch);
      expect(last.searchParams.get("q")).toBe("zoe");
      expect(last.searchParams.get("page")).toBe("1");
    });
  });

  test("the last row of the last page decided away steps back to the new last page, not to an empty state", async () => {
    let decided = false;
    serve(mockFetch, (url, init) => {
      if (init?.method === "PUT") {
        decided = true;
        return jsonResponse(200, BOT);
      }
      if (!url.startsWith("/api/v1/data-sources/5/code-identities?")) return undefined;
      const page = new URL(url, "http://test").searchParams.get("page");
      if (decided) {
        // 20 proposals left: the old page 2 is now past the end (empty, unchanged total), page 1 is full.
        return jsonResponse(200, { items: page === "1" ? [MATCHED] : [], page: Number(page), pageSize: 20, total: 20 });
      }
      return jsonResponse(200, { items: page === "1" ? [MATCHED] : [BOT], page: Number(page), pageSize: 20, total: 21 });
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "2" }));
    await user.click(await screen.findByRole("button", { name: "Operations for Project bot" }));
    await user.click(await screen.findByRole("menuitem", { name: "Ignore" }));

    await waitFor(() => expect(listUrls(mockFetch).some((url) => url.searchParams.get("page") === "2")).toBe(true));
    await waitFor(() => {
      expect(lastListUrl(mockFetch).searchParams.get("page")).toBe("1");
      expect(screen.getByText("Sample Dev One")).toBeInTheDocument();
    });
    expect(screen.queryByText("No proposals — every matching address is decided.")).not.toBeInTheDocument();
  });
});

describe("DataSourceIdentities page search", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = installFetchMock();
  });

  afterEach(restoreFetchMock);

  test("q lives in the URL beside the status, travels with a tab change and is not remembered for another source", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderPage();

    await screen.findByText("Sample Dev One");
    fireEvent.change(screen.getByLabelText("Search name, username or address"), { target: { value: "zoe" } });
    expect(screen.getByTestId("search")).toHaveTextContent("?q=zoe");
    await user.click(screen.getByRole("tab", { name: "Linked" }));
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("q")).toBe("zoe"));
    expect(screen.getByTestId("search").textContent).toMatch(/status=LINKED/);
    expect(screen.getByTestId("search").textContent).toMatch(/q=zoe/);
    expect(Object.keys(localStorage).some((key) => key.endsWith(".filter.q"))).toBe(false);

    first.unmount();
    renderPage("/data-sources/6/identities");
    await screen.findByText("Sample Dev One");
    expect(screen.getByLabelText("Search name, username or address")).toHaveValue("");
    expect(lastListUrl(mockFetch).searchParams.has("q")).toBe(false);
  });

  test("an empty result while searching says no identity matches, not that the tab is empty", async () => {
    serve(mockFetch, (url) =>
      new URL(url, "http://test").searchParams.get("q") === "nobody" ? jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }) : undefined,
    );
    renderPage("/data-sources/5/identities?q=nobody");

    expect(await screen.findByText("No identities match the search.")).toBeInTheDocument();
    expect(screen.queryByText("No proposals — every matching address is decided.")).not.toBeInTheDocument();
  });

  test("nothing is sent before the 300 ms debounce, then one request carries the term", async () => {
    serve(mockFetch);
    renderPage();

    await screen.findByText("Sample Dev One");
    const before = listUrls(mockFetch).length;
    fireEvent.change(screen.getByLabelText("Search name, username or address"), { target: { value: "zoe" } });
    await sleep(150);
    expect(listUrls(mockFetch)).toHaveLength(before);
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.get("q")).toBe("zoe"));
    expect(listUrls(mockFetch)).toHaveLength(before + 1);
  });

  test("a term longer than the server's 100 characters is cut to 100 in the request", async () => {
    serve(mockFetch);
    renderPage();

    await screen.findByText("Sample Dev One");
    fireEvent.change(screen.getByLabelText("Search name, username or address"), { target: { value: `  ${"x".repeat(150)}` } });
    await waitFor(() => expect(lastListUrl(mockFetch).searchParams.has("q")).toBe(true));
    expect(lastListUrl(mockFetch).searchParams.get("q")).toBe("x".repeat(100));
  });
});

describe("DataSourceIdentities page action failures", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = installFetchMock();
  });

  afterEach(restoreFetchMock);

  const FAIL = (status: number) => jsonResponse(status, { title: "Failed", status });

  test("a failed Confirm all shows its error inline, closes the dialog and toasts nothing", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    serve(mockFetch, (_url, init) => (init?.method === "POST" ? FAIL(500) : undefined));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Confirm all proposals (2)" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm all proposals" }));

    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(toast).not.toHaveBeenCalled();
  });

  test("a Confirm all that finds the data source gone says so, in its own words", async () => {
    serve(mockFetch, (_url, init) => (init?.method === "POST" ? FAIL(404) : undefined));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Confirm all proposals (2)" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm all proposals" }));

    expect(await screen.findByText("The data source no longer exists.")).toBeInTheDocument();
    expect(screen.queryByText(/The identity or its decision no longer exists/)).not.toBeInTheDocument();
  });

  test("a failed decision's error is gone once Confirm all runs", async () => {
    serve(mockFetch, (_url, init) => {
      if (init?.method === "PUT") return FAIL(500);
      if (init?.method === "POST") return jsonResponse(200, { linked: 1, bots: 1 });
      return undefined;
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Operations for Sample Dev One" }));
    await user.click(await screen.findByRole("menuitem", { name: "Ignore" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Confirm all proposals (2)" }));
    expect(screen.queryByText("Action failed (500)")).not.toBeInTheDocument();
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm all proposals" }));
    await waitFor(() => expect(mockFetch.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByText("Action failed (500)")).not.toBeInTheDocument();
  });

  test("a failed Confirm all's error is gone once a row action runs", async () => {
    serve(mockFetch, (_url, init) => {
      if (init?.method === "POST") return FAIL(500);
      if (init?.method === "PUT") return jsonResponse(200, MATCHED);
      return undefined;
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Confirm all proposals (2)" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Confirm all proposals" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Operations for Sample Dev One" }));
    await user.click(await screen.findByRole("menuitem", { name: "Ignore" }));
    await waitFor(() => expect(mockFetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
    await waitFor(() => expect(screen.queryByText("Action failed (500)")).not.toBeInTheDocument());
  });

  test("a failure is not carried over to another tab", async () => {
    serve(mockFetch, (_url, init) => (init?.method === "PUT" ? FAIL(500) : undefined));
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Sample Dev One");
    await user.click(screen.getByRole("button", { name: "Operations for Sample Dev One" }));
    await user.click(await screen.findByRole("menuitem", { name: "Ignore" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Unmapped" }));
    await screen.findByText("GitLab user #9");
    expect(screen.queryByText("Action failed (500)")).not.toBeInTheDocument();
  });
});
