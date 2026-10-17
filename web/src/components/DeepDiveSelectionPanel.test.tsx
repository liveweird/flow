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

const OCT_5 = Date.UTC(2026, 9, 5, 12);
const OCT_19 = Date.UTC(2026, 9, 19, 12);
const sprintOf = (id: number, name: string, connectionId = 1) => ({
  id,
  connectionId,
  name,
  state: "closed",
  startAt: OCT_5,
  endAt: OCT_19,
  completeAt: OCT_19,
  taskCount: 23,
});
const SPRINT_4 = sprintOf(41, "Sprint 4");
const SPRINT_5 = sprintOf(42, "Sprint 5");
/** The sprints each domain lists: Ops only has Sprint 5. */
const SPRINTS_BY_DOMAIN: Record<string, ReturnType<typeof sprintOf>[]> = { FLO: [SPRINT_4, SPRINT_5], OPS: [SPRINT_5] };
const epicOf = (id: number, key: string, summary: string, domain: string | null, connectionId = 1) => ({ id, connectionId, key, summary, domain });
const EPICS = [epicOf(1, "FLO-1", "Onboarding", "FLO"), epicOf(2, "FLO-2", "Billing", "FLO"), epicOf(3, "OPS-1", "Runbooks", "OPS")];
const WITH_OPS: ReportFilters = { ...FILTERS, domains: [...FILTERS.domains, { domainKey: "OPS", domainName: "Ops" }] };
const TWO_CONNECTIONS: ReportFilters = { ...FILTERS, connections: [{ id: 1, name: "Stub" }, { id: 2, name: "Other" }] };

const SPRINT_4_LABEL = /^Sprint 4 · 2026-10-05 → 2026-10-19$/;

const page = (items: unknown[], total = items.length) => ({ items, page: 1, pageSize: 100, total });

/**
 * Serves the two option lists the way the server does: the sprints of the asked `domain`, the epics narrowed to the asked
 * `domain` if any; `q` matches the key OR the summary (or the sprint's name) on its own — never the joined `KEY summary`
 * label the picker shows — and `zzz` finds nothing. `lists` replaces what a list holds.
 */
function serve(mockFetch: FetchMock, lists: { sprints?: unknown[]; epics?: unknown[]; total?: number } = {}) {
  mockFetch.mockImplementation((url: string) => {
    const params = new URL(url, "http://x").searchParams;
    const q = params.get("q")?.toLowerCase();
    const narrowed = <T extends { name?: string; key?: string; summary?: string | null }>(items: T[]) =>
      items.filter(
        (item) => q === undefined || [item.name, item.key, item.summary].some((field) => field?.toLowerCase().includes(q) === true),
      );
    if (url.startsWith("/api/v1/reports/deep-dive/sprints?")) {
      const sprints = (lists.sprints ?? SPRINTS_BY_DOMAIN[params.get("domain") ?? ""] ?? []) as typeof SPRINT_4[];
      return Promise.resolve(jsonResponse(200, page(narrowed(sprints), lists.total)));
    }
    if (url.startsWith("/api/v1/reports/deep-dive/epics?")) {
      const domain = params.get("domain");
      const epics = ((lists.epics ?? EPICS) as typeof EPICS).filter((epic) => domain === null || epic.domain === domain);
      return Promise.resolve(jsonResponse(200, page(narrowed(epics), lists.total)));
    }
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
/** Show stays focusable while blocked: it is `aria-disabled`, never `disabled`. */
const blocked = () => expect(showButton()).toHaveAttribute("aria-disabled", "true");
const ready = () => expect(showButton()).toHaveAttribute("aria-disabled", "false");
/** A control by its label. */
const field = (label: string) => screen.getByRole("combobox", { name: label });
const dateField = (label: string) => screen.getByRole("textbox", { name: label });
const mode = (name: string) => screen.getByRole("radio", { name });

async function chooseDomain(user: User, name = "Flow") {
  await user.click(field("Domain"));
  await user.click(await screen.findByRole("option", { name }));
}

/** Picks an option; a multiple choice stays open after a pick, so a second pick must not click the box shut. */
async function pickOption(user: User, label: string, option: string | RegExp) {
  if (screen.queryByRole("listbox") === null) await user.click(field(label));
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
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("starts on the sprints of a domain, with nothing to show until a domain and sprints are picked", async () => {
    const user = userEvent.setup();
    renderPanel();
    expect(mode("Sprints of a domain")).toBeChecked();
    blocked();
    // No domain, no sprint request: the sprint list is the domain's.
    expect(field("Sprints")).toBeDisabled();
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive")).toEqual([]);

    await chooseDomain(user);
    blocked();
    await pickOption(user, "Sprints", SPRINT_4_LABEL);
    ready();
  });

  test("a sprint option carries its dates, read in the configured zone", async () => {
    const user = userEvent.setup();
    renderPanel();
    await chooseDomain(user);
    await user.click(field("Sprints"));
    expect(await screen.findByRole("option", { name: "Sprint 4 · 2026-10-05 → 2026-10-19" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Sprint 5 · 2026-10-05 → 2026-10-19" })).toBeInTheDocument();
  });

  test("the sprint search carries the domain and waits out the debounce, and Show hands over the selection", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const onShow = renderPanel();
    const sprintCalls = () => urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?");
    await chooseDomain(user);
    await screen.findByText("0 of 52 selected.");
    await waitFor(() => expect(sprintCalls()).toHaveLength(1));
    expect(queryOf(sprintCalls()[0]).get("domain")).toBe("FLO");
    expect(queryOf(sprintCalls()[0]).has("q")).toBe(false);

    await user.type(field("Sprints"), "sprint 5");
    // Typing alone asks nothing; the settled text is asked once.
    expect(sprintCalls()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(299);
    expect(sprintCalls()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    await waitFor(() => expect(sprintCalls()).toHaveLength(2));
    expect(queryOf(sprintCalls()[1]).get("q")).toBe("sprint 5");
    expect(queryOf(sprintCalls()[1]).get("domain")).toBe("FLO");

    await user.click(await screen.findByRole("option", { name: /^Sprint 5 · / }));
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ domain: "FLO", sprintIds: [42] });
  });

  test("what is picked stays, named, while the search changes", async () => {
    const user = userEvent.setup();
    renderPanel();
    await chooseDomain(user);
    await pickOption(user, "Sprints", SPRINT_4_LABEL);
    expect(screen.getByText(SPRINT_4_LABEL)).toBeInTheDocument();

    await user.type(field("Sprints"), "zzz");
    expect(await screen.findByText("Nothing found")).toBeInTheDocument();
    // The pill keeps its NAME (not the id) although the answer to the new search no longer lists it.
    expect(screen.getByText(SPRINT_4_LABEL)).toBeInTheDocument();
    expect(screen.queryByText("41")).not.toBeInTheDocument();
    ready();
  });

  test("changing the mode clears the picks but keeps the domain and the dates", async () => {
    const user = userEvent.setup();
    renderPanel({ selection: { domain: "FLO", sprintIds: [41], from: "2026-01-05", to: "2026-02-01" }, labels: { "41": "Sprint 4" } });
    expect(screen.getByText("Sprint 4")).toBeInTheDocument();
    ready();

    await user.click(mode("Epics"));
    expect(screen.queryByText("Sprint 4")).not.toBeInTheDocument();
    expect(screen.getByText("0 of 50 selected.")).toBeInTheDocument();
    expect(field("Domain")).toHaveValue("Flow");
    blocked();
    expect(dateField("From (optional)")).toHaveValue("2026-01-05");
    expect(dateField("To (optional)")).toHaveValue("2026-02-01");
    // The domain now narrows the epic list.
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").some((url) => queryOf(url).get("domain") === "FLO")).toBe(true));

    await user.click(mode("Sprints of a domain"));
    expect(screen.queryByText("Sprint 4")).not.toBeInTheDocument();
    expect(field("Domain")).toHaveValue("Flow");
    blocked();
  });

  test("the epics mode searches the epics with q and shows the picked epic keys in canonical order", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    await user.click(mode("Epics"));
    expect(field("Domain")).toHaveAttribute("placeholder", "Any domain");
    await pickOption(user, "Epics", "FLO-2 Billing");
    await user.type(field("Epics"), "onboard");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").some((url) => queryOf(url).get("q") === "onboard")).toBe(true));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-2", "FLO-1"] });
  });

  test("in Epics a domain only filters the epic list: the selection stays the epics'", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ filters: WITH_OPS });
    await user.click(mode("Epics"));
    await chooseDomain(user, "Ops");
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").some((url) => queryOf(url).get("domain") === "OPS")).toBe(true));
    await user.click(field("Epics"));
    expect(screen.queryByRole("option", { name: "FLO-1 Onboarding" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "OPS-1 Runbooks" }));
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["OPS-1"] });
  });

  test("in Epics a domain drops the epics outside it with a note, and clearing it keeps every epic", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ filters: WITH_OPS });
    await user.click(mode("Epics"));
    await pickOption(user, "Epics", "FLO-1 Onboarding");
    await pickOption(user, "Epics", "OPS-1 Runbooks");

    await chooseDomain(user, "Ops");
    expect(await screen.findByRole("status")).toHaveTextContent("1 epic removed: not in Ops.");
    expect(screen.getByText("OPS-1 Runbooks")).toBeInTheDocument();
    expect(screen.queryByText("FLO-1 Onboarding")).not.toBeInTheDocument();

    // Clearing the domain clears the note and keeps what is picked; the list is every epic again.
    await user.click(screen.getByLabelText("Clear Domain"));
    expect(screen.queryByText(/removed/)).not.toBeInTheDocument();
    expect(screen.getByText("OPS-1 Runbooks")).toBeInTheDocument();
    await pickOption(user, "Epics", "FLO-2 Billing");
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["OPS-1", "FLO-2"] });
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
    await user.click(await screen.findByRole("option", { name: /^Sprint 4 · / }));
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
    expect(await screen.findByText(/Showing the first 3 of 230 matches; type to narrow the list\./)).toBeInTheDocument();
  });

  test("dates are optional, must be real and ordered, and travel with the selection", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ selection: { epicIds: ["FLO-1"] } });
    const from = dateField("From (optional)");
    const to = dateField("To (optional)");

    await user.type(from, "2026-13-01");
    expect(screen.getByText("Use the format YYYY-MM-DD.")).toBeInTheDocument();
    blocked();

    await user.clear(from);
    await user.type(from, "2026-03-10");
    await user.type(to, "2026-03-01");
    expect(screen.getByText(/The range must start on or before its end/)).toBeInTheDocument();
    blocked();

    await user.clear(to);
    await user.type(to, "2026-03-31");
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"], from: "2026-03-10", to: "2026-03-31" });
  });

  test("with one connection the options are not suffixed, there is no connection picker and no connectionId", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
    await chooseDomain(user);
    await pickOption(user, "Sprints", SPRINT_4_LABEL);
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ domain: "FLO", sprintIds: [41] });
  });

  test("with several connections the options name their connection and the picked one's id travels in the selection", async () => {
    const user = userEvent.setup();
    // Sprint 4 exists in both connections (the same id): only the connection tells the two options apart.
    serve(mockFetch, { sprints: [sprintOf(41, "Sprint 4", 1), sprintOf(41, "Sprint 4", 2), sprintOf(51, "Sprint 9", 2)] });
    const onShow = renderPanel({ filters: TWO_CONNECTIONS });
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
    await chooseDomain(user);
    await user.click(field("Sprints"));
    expect(await screen.findByRole("option", { name: "Sprint 4 · 2026-10-05 → 2026-10-19 · Stub" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Sprint 9 · 2026-10-05 → 2026-10-19 · Other" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Sprint 4 · 2026-10-05 → 2026-10-19 · Other" }));
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ domain: "FLO", sprintIds: [41], connectionId: 2 });
  });

  test("with several connections an epic option names its connection too", async () => {
    const user = userEvent.setup();
    serve(mockFetch, { epics: [epicOf(1, "FLO-1", "Onboarding", "FLO", 1), epicOf(9, "FLO-1", "Onboarding", "FLO", 2)] });
    const onShow = renderPanel({ filters: TWO_CONNECTIONS });
    await user.click(mode("Epics"));
    await pickOption(user, "Epics", "FLO-1 Onboarding · Stub");
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"], connectionId: 1 });
  });

  test("a link's connection rides on its picks; a pick from another connection blocks Show and says so", async () => {
    const user = userEvent.setup();
    serve(mockFetch, { sprints: [sprintOf(41, "Sprint 4", 2), sprintOf(61, "Sprint 6", 1)] });
    const onShow = renderPanel({ filters: TWO_CONNECTIONS, selection: { domain: "FLO", sprintIds: [41], connectionId: 2 }, labels: { "41": "Sprint 4" } });
    // Showing the link's own selection again writes the same URL.
    await user.click(showButton());
    expect(onShow).toHaveBeenLastCalledWith({ domain: "FLO", sprintIds: [41], connectionId: 2 });

    await pickOption(user, "Sprints", "Sprint 6 · 2026-10-05 → 2026-10-19 · Stub");
    blocked();
    expect(document.getElementById(showButton().getAttribute("aria-describedby") ?? "")).toHaveTextContent(
      "Pick work from one connection only — the options name their connection.",
    );
  });

  test("a link's connection the reference data does not list is unknown on its picks, so it does not block Show", async () => {
    const user = userEvent.setup();
    serve(mockFetch, { sprints: [sprintOf(61, "Sprint 6", 1)] });
    const onShow = renderPanel({ filters: TWO_CONNECTIONS, selection: { domain: "FLO", sprintIds: [41], connectionId: 9 }, labels: { "41": "Sprint 4" } });
    await pickOption(user, "Sprints", "Sprint 6 · 2026-10-05 → 2026-10-19");
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenLastCalledWith(expect.objectContaining({ sprintIds: [41, 61], connectionId: 1 }));
  });

  test("a link's connection is not written back on a stack with one connection", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ selection: { epicIds: ["FLO-1"], connectionId: 9 } });
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"] });
  });

  test("a sprint id the new domain lists only in another connection is dropped: the pick is matched by connection too", async () => {
    const user = userEvent.setup();
    const filters: ReportFilters = { ...TWO_CONNECTIONS, domains: WITH_OPS.domains };
    // Sprint 41 exists in both connections; FLO is in both, Ops only in connection 2.
    mockFetch.mockImplementation((url: string) => {
      const domain = new URL(url, "http://x").searchParams.get("domain");
      const items = domain === "OPS" ? [sprintOf(41, "Sprint 4", 2)] : [sprintOf(41, "Sprint 4", 1), sprintOf(41, "Sprint 4", 2)];
      return Promise.resolve(jsonResponse(200, page(items)));
    });
    renderPanel({ filters });
    await chooseDomain(user);
    await pickOption(user, "Sprints", "Sprint 4 · 2026-10-05 → 2026-10-19 · Stub");
    expect(screen.getByText(/^1 of 52 selected\./)).toBeInTheDocument();

    await chooseDomain(user, "Ops");
    expect(await screen.findByRole("status")).toHaveTextContent("1 sprint removed: not in Ops.");
    expect(screen.getByText(/^0 of 52 selected\./)).toBeInTheDocument();
  });

  test("a new domain keeps the sprints it still lists and says how many went", async () => {
    const user = userEvent.setup();
    renderPanel({ filters: WITH_OPS });
    await chooseDomain(user);
    await pickOption(user, "Sprints", SPRINT_4_LABEL);
    await pickOption(user, "Sprints", /^Sprint 5 · /);
    expect(screen.getByText("2 of 52 selected.")).toBeInTheDocument();

    // Ops lists Sprint 5 only: Sprint 4 is not in it.
    await chooseDomain(user, "Ops");
    expect(await screen.findByRole("status")).toHaveTextContent("1 sprint removed: not in Ops.");
    expect(screen.getByText("1 of 52 selected.")).toBeInTheDocument();
    expect(screen.queryByText(SPRINT_4_LABEL)).not.toBeInTheDocument();
    expect(screen.getByText(/^Sprint 5 · /)).toBeInTheDocument();
    ready();
  });

  test("a truncated sprint list leaves the picks alone", async () => {
    const user = userEvent.setup();
    renderPanel({ filters: WITH_OPS });
    await chooseDomain(user);
    await pickOption(user, "Sprints", SPRINT_4_LABEL);

    // The new domain's list is cut (150 matches, one shown): it cannot prove the pick is outside it.
    serve(mockFetch, { sprints: [SPRINT_5], total: 150 });
    await chooseDomain(user, "Ops");
    await screen.findByText(/Showing the first 1 of 150 matches/);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByText(/^1 of 52 selected\./)).toBeInTheDocument();
    expect(screen.getByText(SPRINT_4_LABEL)).toBeInTheDocument();
  });

  test("a failed option list is said inline on its picker", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderPanel({ selection: { epicIds: ["FLO-1"] } });
    expect(await screen.findByText(/500/)).toBeInTheDocument();
  });

  test("a blocked Show stays focusable, says what is missing for the mode, and ignores a click", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel();
    const reason = () => document.getElementById(showButton().getAttribute("aria-describedby") ?? "") as HTMLElement;
    showButton().focus();
    expect(showButton()).toHaveFocus();
    expect(reason()).toHaveTextContent("Pick a domain and at least one sprint.");
    await user.click(showButton());
    expect(onShow).not.toHaveBeenCalled();

    await chooseDomain(user);
    expect(reason()).toHaveTextContent("Pick at least one sprint.");
    await user.click(mode("Epics"));
    expect(reason()).toHaveTextContent("Pick at least one epic.");
    await pickOption(user, "Epics", "FLO-1 Onboarding");
    expect(reason()).toBeEmptyDOMElement();
    ready();
  });

  test("a bad date is the reason Show is blocked", async () => {
    const user = userEvent.setup();
    renderPanel({ selection: { epicIds: ["FLO-1"] } });
    await user.type(dateField("From (optional)"), "nope");
    blocked();
    expect(document.getElementById(showButton().getAttribute("aria-describedby") ?? "")).toHaveTextContent("Fix the dates");
  });

  test("a picker that needs something first says what", () => {
    renderPanel();
    expect(screen.getByText("0 of 52 selected. Pick a domain first.")).toBeInTheDocument();
  });
});
