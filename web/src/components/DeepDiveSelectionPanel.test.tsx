import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { ReportFilters } from "../api/reports";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";
import type { DeepDiveSelection } from "../utils/deepDiveFilter";
import DeepDiveSelectionPanel from "./DeepDiveSelectionPanel";

type FetchMock = ReturnType<typeof vi.fn>;
type User = ReturnType<typeof userEvent.setup>;

const SPRINTS = [
  { id: 41, connectionId: 1, name: "Sprint 4", state: "closed", startAt: 1, endAt: 2, completeAt: 2, taskCount: 23 },
  { id: 42, connectionId: 1, name: "Sprint 5", state: "closed", startAt: 3, endAt: 4, completeAt: 4, taskCount: 9 },
];
const EPICS = [
  { id: 1, connectionId: 1, key: "FLO-1", summary: "Onboarding", domain: "FLO" },
  { id: 2, connectionId: 1, key: "FLO-2", summary: "Billing", domain: "FLO" },
];
const TASKS = [
  { id: 11, connectionId: 1, key: "FLO-11", summary: "Sign-up form" },
  { id: 12, connectionId: 1, key: "FLO-12", summary: null },
];

const page = (items: unknown[], total = items.length) => ({ items, page: 1, pageSize: 100, total });

/** Serves the filters and the three option lists; a search for `zzz` finds nothing, and `q` narrows by a substring of the label. */
function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    const q = new URL(url, "http://x").searchParams.get("q")?.toLowerCase();
    const narrowed = <T extends { name?: string; key?: string; summary?: string | null }>(items: T[]) =>
      items.filter((item) => q === undefined || `${item.name ?? item.key} ${item.summary ?? ""}`.toLowerCase().includes(q));
    if (url.startsWith("/api/v1/reports/deep-dive/sprints?")) return Promise.resolve(jsonResponse(200, page(narrowed(SPRINTS))));
    if (url.startsWith("/api/v1/reports/deep-dive/epics/")) return Promise.resolve(jsonResponse(200, page(narrowed(TASKS))));
    if (url.startsWith("/api/v1/reports/deep-dive/epics?")) return Promise.resolve(jsonResponse(200, page(narrowed(EPICS))));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

const urls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((call) => call[0] as string);
const urlsFor = (mockFetch: FetchMock, prefix: string) => urls(mockFetch).filter((url) => url.startsWith(prefix));
const queryOf = (url: string) => new URL(url, "http://x").searchParams;

function renderPanel(options: { selection?: DeepDiveSelection; filters?: ReportFilters; labels?: Record<string, string> } = {}) {
  const onShow = vi.fn();
  renderWithProviders(
    <DeepDiveSelectionPanel
      selection={options.selection ?? {}}
      filters={options.filters ?? FILTERS}
      labels={options.labels ?? {}}
      onShow={onShow}
    />,
  );
  return onShow;
}

const showButton = () => screen.getByRole("button", { name: "Show" });
/** A control by its label. */
const field = (label: string) => screen.getByRole("combobox", { name: label });
const dateField = (label: string) => screen.getByRole("textbox", { name: label });
const mode = (name: string) => screen.getByRole("radio", { name });

async function chooseDomain(user: User) {
  await user.click(field("Domain"));
  await user.click(await screen.findByRole("option", { name: "Flow" }));
}

async function pickOption(user: User, label: string, option: string | RegExp) {
  await user.click(field(label));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("DeepDiveSelectionPanel", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve(mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("starts on the sprints of a domain, with nothing to show until a domain and sprints are picked", async () => {
    const user = userEvent.setup();
    renderPanel();
    expect(mode("Sprints of a domain")).toBeChecked();
    expect(showButton()).toBeDisabled();
    // No domain, no sprint request: the sprint list is the domain's.
    expect(field("Sprints")).toBeDisabled();
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive")).toEqual([]);

    await chooseDomain(user);
    expect(showButton()).toBeDisabled();
    await pickOption(user, "Sprints", "Sprint 4");
    expect(showButton()).toBeEnabled();
  });

  test("the sprint search carries the domain and the debounced q, and Show hands over the selection", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    await chooseDomain(user);
    await screen.findByText("0 of 52 selected.");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?")).toHaveLength(1));
    expect(queryOf(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?")[0]).get("domain")).toBe("FLO");
    expect(queryOf(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?")[0]).has("q")).toBe(false);

    await user.type(field("Sprints"), "sprint 5");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?").some((url) => queryOf(url).get("q") === "sprint 5")).toBe(true));
    // One request per settled search, not one per keystroke.
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?")).toHaveLength(2);
    expect(queryOf(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?")[1]).get("domain")).toBe("FLO");

    await user.click(await screen.findByRole("option", { name: "Sprint 5" }));
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ domain: "FLO", sprintIds: [42] });
  });

  test("what is picked stays, named, while the search changes", async () => {
    const user = userEvent.setup();
    renderPanel();
    await chooseDomain(user);
    await pickOption(user, "Sprints", "Sprint 4");
    expect(screen.getByText("Sprint 4")).toBeInTheDocument();

    await user.type(field("Sprints"), "zzz");
    expect(await screen.findByText("Nothing found")).toBeInTheDocument();
    // The pill keeps its NAME (not the id) although the answer to the new search no longer lists it.
    expect(screen.getByText("Sprint 4")).toBeInTheDocument();
    expect(screen.queryByText("41")).not.toBeInTheDocument();
    expect(showButton()).toBeEnabled();
  });

  test("changing the mode clears the picks but keeps the connection and the dates", async () => {
    const user = userEvent.setup();
    renderPanel({ selection: { domain: "FLO", sprintIds: [41], from: "2026-01-05", to: "2026-02-01" }, labels: { "41": "Sprint 4" } });
    expect(screen.getByText("Sprint 4")).toBeInTheDocument();
    expect(showButton()).toBeEnabled();

    await user.click(mode("Epics"));
    expect(screen.queryByText("Sprint 4")).not.toBeInTheDocument();
    expect(screen.getByText("0 of 50 selected.")).toBeInTheDocument();
    expect(showButton()).toBeDisabled();
    expect(dateField("From (optional)")).toHaveValue("2026-01-05");
    expect(dateField("To (optional)")).toHaveValue("2026-02-01");

    await user.click(mode("Sprints of a domain"));
    expect(screen.queryByText("Sprint 4")).not.toBeInTheDocument();
    expect(field("Domain")).toHaveValue("");
    expect(showButton()).toBeDisabled();
  });

  test("the epics mode searches the epics with q and shows the picked epic keys in canonical order", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    await user.click(mode("Epics"));
    await pickOption(user, "Epics", "FLO-2 Billing");
    await user.type(field("Epics"), "onboard");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").some((url) => queryOf(url).get("q") === "onboard")).toBe(true));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-2", "FLO-1"] });
  });

  test("the tasks mode lists one epic's tasks, and a different epic drops the tasks picked under the old one", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    await user.click(mode("Tasks of an epic"));
    expect(field("Tasks")).toBeDisabled();

    await pickOption(user, "Epic", "FLO-1 Onboarding");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics/FLO-1/tasks?")).toHaveLength(1));
    expect(showButton()).toBeDisabled();
    await pickOption(user, "Tasks", "FLO-11 Sign-up form");
    await pickOption(user, "Tasks", "FLO-12");
    expect(showButton()).toBeEnabled();

    await user.click(showButton());
    expect(onShow).toHaveBeenLastCalledWith({ epicIds: ["FLO-1"], issueIds: ["FLO-11", "FLO-12"] });

    await user.click(field("Epic"));
    await user.click(await screen.findByRole("option", { name: "FLO-2 Billing" }));
    expect(screen.getByText("0 of 500 selected.")).toBeInTheDocument();
    expect(showButton()).toBeDisabled();
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics/FLO-2/tasks?")).toHaveLength(1));
  });

  test("the limits are the server's: 52 sprints, 50 epics, with the count said and a full list taking no more", async () => {
    const user = userEvent.setup();
    const sprintIds = Array.from({ length: 52 }, (_, i) => 100 + i);
    const onShowSprints = vi.fn();
    const { unmount } = renderWithProviders(
      <DeepDiveSelectionPanel selection={{ domain: "FLO", sprintIds }} filters={FILTERS} labels={{}} onShow={onShowSprints} />,
    );
    expect(screen.getByText("52 of 52 selected.")).toBeInTheDocument();
    await user.click(field("Sprints"));
    await user.click(await screen.findByRole("option", { name: "Sprint 4" }));
    expect(screen.getByText("52 of 52 selected.")).toBeInTheDocument();
    await user.click(showButton());
    expect(onShowSprints.mock.calls[0][0].sprintIds).toEqual(sprintIds);
    unmount();

    const epicIds = Array.from({ length: 50 }, (_, i) => `FLO-${100 + i}`);
    const onShowEpics = vi.fn();
    renderWithProviders(<DeepDiveSelectionPanel selection={{ epicIds }} filters={FILTERS} labels={{}} onShow={onShowEpics} />);
    expect(screen.getByText("50 of 50 selected.")).toBeInTheDocument();
    await user.click(field("Epics"));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    await user.click(showButton());
    expect(onShowEpics.mock.calls[0][0].epicIds).toEqual(epicIds);
  });

  test("a long option list says it is cut and asks for a narrower search", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, page(EPICS, 230))));
    const user = userEvent.setup();
    renderPanel();
    await user.click(mode("Epics"));
    expect(await screen.findByText(/Showing the first 2 of 230 matches; type to narrow the list\./)).toBeInTheDocument();
  });

  test("dates are optional, must be real and ordered, and travel with the selection", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ selection: { epicIds: ["FLO-1"] } });
    const from = dateField("From (optional)");
    const to = dateField("To (optional)");

    await user.type(from, "2026-13-01");
    expect(screen.getByText("Use the format YYYY-MM-DD.")).toBeInTheDocument();
    expect(showButton()).toBeDisabled();

    await user.clear(from);
    await user.type(from, "2026-03-10");
    await user.type(to, "2026-03-01");
    expect(screen.getByText(/The range must start on or before its end/)).toBeInTheDocument();
    expect(showButton()).toBeDisabled();

    await user.clear(to);
    await user.type(to, "2026-03-31");
    expect(showButton()).toBeEnabled();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"], from: "2026-03-10", to: "2026-03-31" });
  });

  test("with one connection there is no connection picker", () => {
    renderPanel();
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
  });

  test("with several connections the picker offers one, and its id narrows the search and the selection", async () => {
    const user = userEvent.setup();
    const filters: ReportFilters = { ...FILTERS, connections: [{ id: 1, name: "Stub" }, { id: 2, name: "Other" }] };
    const onShow = renderPanel({ filters });
    await user.click(mode("Epics"));
    await user.click(field("Connection"));
    await user.click(await screen.findByRole("option", { name: "Other" }));
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").some((url) => queryOf(url).get("connectionId") === "2")).toBe(true));
    await pickOption(user, "Epics", "FLO-1 Onboarding");
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"], connectionId: 2 });
  });

  test("a failed option list is said inline on its picker", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderPanel({ selection: { epicIds: ["FLO-1"] } });
    expect(await screen.findByText(/500/)).toBeInTheDocument();
  });
});
