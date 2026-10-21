import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { ReportFilters } from "../api/reports";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
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
const TWO_CONNECTIONS: ReportFilters = { ...FILTERS, connections: [{ id: 1, name: "Stub", siteUrl: "https://stub.atlassian.net" }, { id: 2, name: "Other", siteUrl: "https://other.atlassian.net" }] };

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

const FOLDED_KEY = "flow.viewSettings.reports.deepDive.selectionFolded";

/** The form tests start unfolded (the viewer's stored choice); `folded: true` leaves the default, folded over a shown selection. */
function renderPanel(options: { selection?: DeepDiveSelection; filters?: ReportFilters; labels?: Record<string, string>; folded?: boolean } = {}) {
  const onShow = vi.fn();
  if (options.folded !== true) localStorage.setItem(FOLDED_KEY, "false");
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
    expect(field("Domain")).toHaveAttribute("placeholder", "Pick a domain");
    await chooseDomain(user);
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

  test("the limits are the server's: 52 sprints, 50 epics, with the count said and a full list taking no more", async () => {
    const user = userEvent.setup();
    localStorage.setItem(FOLDED_KEY, "false");
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

    localStorage.setItem(FOLDED_KEY, "false");
    const epicIds = Array.from({ length: 50 }, (_, i) => `FLO-${100 + i}`);
    const onShowEpics = vi.fn();
    renderWithProviders(<DeepDiveSelectionPanel selection={{ domain: "FLO", epicIds }} filters={FILTERS} labels={{}} onShow={onShowEpics} />);
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
    await chooseDomain(user);
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
    await chooseDomain(user);
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
    // Show folds the panel; the draft is still there to edit.
    await user.click(screen.getByRole("button", { name: "Edit selection" }));

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
    renderPanel({ selection: { domain: "FLO", epicIds: ["FLO-1"] } });
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

describe("DeepDiveSelectionPanel — layout and fold", () => {
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

  test("the form is one row under the mode switch: Domain, the picker, From, To and Show last", () => {
    renderPanel();
    const controls = [
      screen.getByRole("radiogroup", { name: "How to select work" }),
      field("Domain"),
      field("Sprints"),
      dateField("From (optional)"),
      dateField("To (optional)"),
      showButton(),
    ];
    controls.slice(1).forEach((control, i) => {
      expect(controls[i].compareDocumentPosition(control) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
    // The five fields share one row (a wrapping group), the mode switch sits above it.
    const row = showButton().closest("[class*=Group-root]") as HTMLElement;
    for (const control of controls.slice(1)) expect(row).toContainElement(control);
    expect(row).not.toContainElement(controls[0]);
    // Hints sit under their control, so an unfilled picker's hint is a description of it, not a label.
    expect(field("Sprints")).toHaveAccessibleDescription("0 of 52 selected. Pick a domain first.");
    // A bare page has nothing to fold: no toggle.
    expect(screen.queryByRole("button", { name: /^(Edit selection|Hide)$/ })).not.toBeInTheDocument();
  });

  test("a bare page shows the form open whatever was stored", () => {
    localStorage.setItem(FOLDED_KEY, "true");
    renderPanel({ folded: true });
    expect(showButton()).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^(Edit selection|Hide)$/ })).not.toBeInTheDocument();
  });

  test("an incomplete link's selection (sprints without a domain) is no shown selection: the form stays open", () => {
    renderPanel({ folded: true, selection: { sprintIds: [41] } });
    expect(showButton()).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit selection" })).not.toBeInTheDocument();
  });

  test("over a shown selection it folds by default to a summary and an Edit selection button", () => {
    renderPanel({ folded: true, selection: { domain: "FLO", sprintIds: [41, 42], from: "2026-09-01", to: "2026-10-15" }, labels: { "41": "FLO Sprint 4", "42": "FLO Sprint 5" } });
    expect(screen.getByText("Flow · Sprints: FLO Sprint 4, FLO Sprint 5 · 2026-09-01 → 2026-10-15")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive")).toEqual([]);
    const edit = screen.getByRole("button", { name: "Edit selection" });
    expect(edit).toHaveAttribute("aria-expanded", "false");
    expect(edit.getAttribute("aria-controls")).not.toBeNull();
  });

  test("the summary names epics by key, ends a long list in +N more and shows a half-open range with an ellipsis", () => {
    renderPanel({ folded: true, selection: { epicIds: ["FLO-1", "FLO-2", "OPS-1", "OPS-2", "OPS-3"], from: "2026-09-01" } });
    expect(screen.getByText("Epics: FLO-1, FLO-2, OPS-1 +2 more · 2026-09-01 → …")).toBeInTheDocument();
  });

  test("the summary reads an unnamed sprint by its id and shows no range when there is none", () => {
    renderPanel({ folded: true, selection: { domain: "FLO", sprintIds: [41, 42, 43, 44], to: "2026-10-15" }, labels: { "41": "Sprint 4" } });
    expect(screen.getByText("Flow · Sprints: Sprint 4, 42, 43 +1 more · … → 2026-10-15")).toBeInTheDocument();
    expect(screen.queryByText(/ → $/)).not.toBeInTheDocument();
  });

  test("the toggle is one disclosure: a constant name, aria-expanded carries the state; the choice is remembered", async () => {
    const user = userEvent.setup();
    const selection = { domain: "FLO", sprintIds: [41] };
    const { unmount } = renderWithProviders(<DeepDiveSelectionPanel selection={selection} filters={FILTERS} labels={{ "41": "Sprint 4" }} onShow={vi.fn()} />);
    const toggle = screen.getByRole("button", { name: "Edit selection" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(document.getElementById(toggle.getAttribute("aria-controls") ?? "")).not.toBeNull();

    await user.click(toggle);
    // The same button, the same name: only the state moved.
    expect(toggle).toHaveAccessibleName("Edit selection");
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById(toggle.getAttribute("aria-controls") ?? "")).toContainElement(showButton());
    expect(screen.queryByText(/Sprints: Sprint 4/)).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(FOLDED_KEY) ?? "null")).toBe(false);
    unmount();

    // Remembered: the next visit opens unfolded.
    const second = renderWithProviders(<DeepDiveSelectionPanel selection={selection} filters={FILTERS} labels={{}} onShow={vi.fn()} />);
    expect(showButton()).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "false");
    expect(JSON.parse(localStorage.getItem(FOLDED_KEY) ?? "null")).toBe(true);
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();
    second.unmount();

    renderWithProviders(<DeepDiveSelectionPanel selection={selection} filters={FILTERS} labels={{}} onShow={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "false");
  });

  test("Show folds the panel, remembers it and hands focus to the toggle; a blocked Show does not fold", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ selection: { domain: "FLO", sprintIds: [41] } });
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledOnce();
    const toggle = screen.getByRole("button", { name: "Edit selection" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(toggle).toHaveFocus();
    expect(JSON.parse(localStorage.getItem(FOLDED_KEY) ?? "null")).toBe(true);

    await user.click(toggle);
    await user.click(mode("Epics"));
    blocked();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "true");
  });

  test("on a bare page Show folds once the URL's selection arrives, and focus follows to the toggle", async () => {
    const user = userEvent.setup();
    function Harness() {
      const [selection, setSelection] = useState<DeepDiveSelection>({});
      return <DeepDiveSelectionPanel selection={selection} filters={FILTERS} labels={{}} onShow={setSelection} />;
    }
    renderWithProviders(<Harness />);
    await chooseDomain(user);
    await pickOption(user, "Sprints", SPRINT_4_LABEL);
    await user.click(showButton());
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveFocus();
    // The summary reads the sprint by the name the options gave it (no report yet), not by its id nor with its dates.
    expect(screen.getByText("Flow · Sprints: Sprint 4")).toBeInTheDocument();
  });

  test("a pick found by searching keeps its pill name across a fold and unfold", async () => {
    const user = userEvent.setup();
    renderPanel({ selection: { domain: "FLO", sprintIds: [41] }, labels: { "41": "Sprint 4" } });
    await user.type(field("Sprints"), "sprint 5");
    await user.click(await screen.findByRole("option", { name: /^Sprint 5 · / }));
    expect(screen.getByText(/^Sprint 5 · 2026-10-05/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    expect(screen.queryByRole("combobox", { name: "Sprints" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    // Not "42": the draft kept the text the options gave it.
    expect(screen.getByText(/^Sprint 5 · 2026-10-05/)).toBeInTheDocument();
    expect(screen.queryByText("42")).not.toBeInTheDocument();
  });

  test("a shown selection the server refuses opens the form, whatever the stored choice; the toggle still folds it", async () => {
    const user = userEvent.setup();
    const selection = { domain: "FLO", sprintIds: [41] };
    const view = (refused: boolean) => <DeepDiveSelectionPanel selection={selection} filters={FILTERS} labels={{}} refused={refused} onShow={vi.fn()} />;
    const { rerender } = renderWithProviders(view(false));
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();

    rerender(view(true));
    expect(showButton()).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit selection" })).toHaveAttribute("aria-expanded", "true");
    // The visit's refusal does not touch the stored choice.
    expect(localStorage.getItem(FOLDED_KEY)).toBeNull();

    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    expect(screen.queryByRole("button", { name: "Show" })).not.toBeInTheDocument();
  });

  test("an empty blocking reason takes no room: it sits outside the form's gap flow", () => {
    renderPanel({ selection: { epicIds: ["FLO-1"] } });
    ready();
    const reason = document.getElementById(showButton().getAttribute("aria-describedby") ?? "") as HTMLElement;
    expect(reason).toBeEmptyDOMElement();
    expect(reason.style.marginTop).toMatch(/^0/);
  });

  test("a corrupt stored fold choice falls back to folded", () => {
    localStorage.setItem(FOLDED_KEY, JSON.stringify("maybe"));
    renderPanel({ folded: true, selection: { epicIds: ["FLO-1"] } });
    expect(screen.getByRole("button", { name: "Edit selection" })).toBeInTheDocument();
  });
});

describe("DeepDiveSelectionPanel — the epic picker's domain gate", () => {
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

  test("in Epics the picker waits for a domain: no list, its hint as the description, no epic request, listing once one is chosen", async () => {
    const user = userEvent.setup();
    renderPanel({ filters: WITH_OPS });
    await user.click(mode("Epics"));
    expect(field("Epics")).toHaveAccessibleDescription(/Pick a domain first\./);
    await user.click(field("Epics"));
    await user.keyboard("onb");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?")).toEqual([]);

    await chooseDomain(user, "Ops");
    await waitFor(() => expect(field("Epics")).not.toHaveAccessibleDescription(/Pick a domain first\./));
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?").map((url) => queryOf(url).get("domain"))).toEqual(["OPS"]));
  });

  test("in Epics a domain change keeps every epic picked, with its label, and only narrows the list", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ filters: WITH_OPS });
    await user.click(mode("Epics"));
    await chooseDomain(user);
    await pickOption(user, "Epics", "FLO-1 Onboarding");
    await user.keyboard("{Escape}");

    await chooseDomain(user, "Ops");
    // Nothing is dropped and no note is said: the FLO epic is still there, named as the option named it.
    expect(screen.getByText("FLO-1 Onboarding")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await user.click(field("Epics"));
    expect(screen.queryByRole("option", { name: "FLO-2 Billing" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "OPS-1 Runbooks" }));
    expect(screen.getByText("FLO-1 Onboarding")).toBeInTheDocument();
    expect(screen.getByText("OPS-1 Runbooks")).toBeInTheDocument();

    // Clearing the domain keeps them too (the picker waits again), and Show still sends both.
    await user.click(screen.getByLabelText("Clear Domain"));
    expect(field("Epics")).toHaveAccessibleDescription(/Pick a domain first\./);
    expect(screen.getByText("FLO-1 Onboarding")).toBeInTheDocument();
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1", "OPS-1"] });
  });

  test("a link's epics without a domain are a valid selection: Show is ready, only adding more needs a domain", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({ selection: { epicIds: ["FLO-1"] }, labels: { "FLO-1": "FLO-1 Onboarding" } });
    expect(mode("Epics")).toBeChecked();
    expect(screen.getByText("FLO-1 Onboarding")).toBeInTheDocument();
    expect(field("Epics")).toHaveAccessibleDescription(/Pick a domain first\./);
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?")).toEqual([]);
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["FLO-1"] });
  });

  test("with epics and no domain the picks stay removable: one can be dropped, the rest cleared, and nothing is requested", async () => {
    const user = userEvent.setup();
    const onShow = renderPanel({
      selection: { epicIds: ["FLO-1", "OPS-1"] },
      labels: { "FLO-1": "FLO-1 Onboarding", "OPS-1": "OPS-1 Runbooks" },
    });
    expect(field("Epics")).toBeEnabled();
    expect(screen.getByText("2 of 50 selected. Pick a domain first.")).toBeInTheDocument();
    await user.click(field("Epics"));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    // The pill's own remove button (Mantine renders it as a button inside the pill).
    const pill = screen.getByText("FLO-1 Onboarding").closest(".mantine-Pill-root") as HTMLElement;
    await user.click(within(pill).getByRole("button", { hidden: true }));
    expect(screen.queryByText("FLO-1 Onboarding")).not.toBeInTheDocument();
    expect(screen.getByText("OPS-1 Runbooks")).toBeInTheDocument();
    expect(screen.getByText("1 of 50 selected. Pick a domain first.")).toBeInTheDocument();
    ready();
    await user.click(showButton());
    expect(onShow).toHaveBeenCalledExactlyOnceWith({ epicIds: ["OPS-1"] });

    await user.click(screen.getByRole("button", { name: "Edit selection" }));
    await user.click(screen.getByLabelText("Clear Epics"));
    expect(screen.queryByText("OPS-1 Runbooks")).not.toBeInTheDocument();
    expect(screen.getByText("0 of 50 selected. Pick a domain first.")).toBeInTheDocument();
    blocked();
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?")).toEqual([]);
  });

  test("the sprints mode is unchanged by it: the domain is still what enables the sprint picker, and epics are not asked for", async () => {
    const user = userEvent.setup();
    renderPanel();
    expect(field("Sprints")).toBeDisabled();
    expect(screen.getByText(/Pick a domain first\./)).toBeInTheDocument();
    await chooseDomain(user);
    expect(field("Sprints")).toBeEnabled();
    await waitFor(() => expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/sprints?").length).toBeGreaterThan(0));
    expect(urlsFor(mockFetch, "/api/v1/reports/deep-dive/epics?")).toEqual([]);
    await user.click(mode("Epics"));
    await user.click(mode("Sprints of a domain"));
    expect(field("Sprints")).toBeEnabled();
  });
});
