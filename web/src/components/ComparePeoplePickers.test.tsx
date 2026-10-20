import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ComparePeoplePickers from "./ComparePeoplePickers";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;
const PEOPLE = [
  { accountId: "acc-ann", displayName: "Ann Author" },
  { accountId: "acc-bob", displayName: "Bob Builder" },
  { accountId: "acc-cy", displayName: "Cy Coder" },
];
/** Who each fixture team currently has (Alpha 1, Beta 2, Gamma 3 is empty). */
const ROSTER: Record<string, string[]> = { "1": ["acc-ann", "acc-bob"], "2": ["acc-cy"], "3": [] };

const KEY_A = "flow.viewSettings.reports.comparePeople.teamA";
const KEY_B = "flow.viewSettings.reports.comparePeople.teamB";

function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (!url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    const params = new URL(url, "http://x").searchParams;
    const q = params.get("q")?.toLowerCase();
    const team = params.get("teamId");
    const items = PEOPLE.filter(
      (person) => (team === null || ROSTER[team]?.includes(person.accountId)) && (q === undefined || person.displayName.toLowerCase().includes(q)),
    );
    return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: Number(params.get("pageSize")), total: items.length }));
  });
}

/** The page's role: the picks live in the parent (on the page they are the URL). */
function Harness({ initial = [] }: { initial?: string[] }) {
  const [accountIds, setAccountIds] = useState<string[]>(initial);
  return (
    <>
      <ComparePeoplePickers accountIds={accountIds} names={{}} teams={FILTERS.teams} onChange={setAccountIds} />
      <div data-testid="picks">{accountIds.join(",")}</div>
    </>
  );
}

const userCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/jira-users?"));
const picks = () => screen.getByTestId("picks").textContent;

async function chooseTeam(user: ReturnType<typeof userEvent.setup>, combobox: string, option: string) {
  await user.click(screen.getByRole("combobox", { name: combobox }));
  await user.click(await screen.findByRole("option", { name: option }));
}

describe("ComparePeoplePickers teams", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("each side has an optional Team select listing the filter bar's teams, before its person select", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    expect(screen.getAllByRole("combobox")).toHaveLength(4);
    expect(screen.getByRole("combobox", { name: "Team (A)" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Team (B)" })).toBeEnabled();
    expect(
      screen.getByRole("combobox", { name: "Team (A)" }).compareDocumentPosition(screen.getByRole("combobox", { name: "Person A" })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    await user.click(screen.getByRole("combobox", { name: "Team (A)" }));
    for (const team of FILTERS.teams) expect(await screen.findByRole("option", { name: team.name })).toBeInTheDocument();
    // No team = today's unit-wide search, one page of 20, no teamId.
    await waitFor(() => expect(userCalls(mockFetch)).toEqual(["/api/v1/jira-users?page=1&pageSize=20&sort=displayName&scope=UNIT"]));
  });

  test("with a team the person select lists its members (teamId, a full page of 100), typing narrows it, clearing the team returns to the unit search", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await chooseTeam(user, "Team (A)", "Beta");
    await user.click(screen.getByRole("combobox", { name: "Person A" }));
    expect(await screen.findByRole("option", { name: "Cy Coder" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Ann Author" })).not.toBeInTheDocument();
    expect(userCalls(mockFetch)).toContain("/api/v1/jira-users?page=1&pageSize=100&sort=displayName&teamId=2&scope=UNIT");

    await user.type(screen.getByRole("combobox", { name: "Person A" }), "cy");
    await waitFor(() => expect(userCalls(mockFetch)).toContain("/api/v1/jira-users?page=1&pageSize=100&sort=displayName&q=cy&teamId=2&scope=UNIT"));

    await user.click(screen.getByLabelText("Clear Team (A)"));
    await user.clear(screen.getByRole("combobox", { name: "Person A" }));
    await user.click(screen.getByRole("combobox", { name: "Person A" }));
    expect(await screen.findByRole("option", { name: "Ann Author" })).toBeInTheDocument();
    expect(userCalls(mockFetch).at(-1)).toBe("/api/v1/jira-users?page=1&pageSize=20&sort=displayName&scope=UNIT");
  });

  test("picking a person from a team's list sets the pick; B's team works before A is picked while B's person waits for A", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await chooseTeam(user, "Team (B)", "Beta");
    expect(screen.getByRole("combobox", { name: "Person B" })).toBeDisabled();
    expect(localStorage.getItem(KEY_B)).toBe("2");

    await chooseTeam(user, "Team (A)", "Alpha");
    await user.click(screen.getByRole("combobox", { name: "Person A" }));
    await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
    await waitFor(() => expect(picks()).toBe("acc-bob"));

    // B lists Beta's members only (and never A's own pick).
    await user.click(screen.getByRole("combobox", { name: "Person B" }));
    await user.click(await screen.findByRole("option", { name: "Cy Coder" }));
    await waitFor(() => expect(picks()).toBe("acc-bob,acc-cy"));
  });

  test("changing a team never clears an already-picked person: they stay in the options", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await chooseTeam(user, "Team (A)", "Alpha");
    await user.click(screen.getByRole("combobox", { name: "Person A" }));
    await user.click(await screen.findByRole("option", { name: "Ann Author" }));
    await waitFor(() => expect(picks()).toBe("acc-ann"));

    await chooseTeam(user, "Team (A)", "Beta");
    expect(picks()).toBe("acc-ann");
    expect(screen.getByRole("combobox", { name: "Person A" })).toHaveValue("Ann Author");
    await user.click(screen.getByRole("combobox", { name: "Person A" }));
    expect(await screen.findByRole("option", { name: "Cy Coder" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Ann Author" })).toBeInTheDocument();
  });

  test("each side remembers its team: a remount restores it, and a remembered team that no longer exists is ignored", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderWithProviders(<Harness />);
    await chooseTeam(user, "Team (A)", "Alpha");
    await chooseTeam(user, "Team (B)", "Gamma");
    expect(localStorage.getItem(KEY_A)).toBe("1");
    expect(localStorage.getItem(KEY_B)).toBe("3");
    first.unmount();

    renderWithProviders(<Harness />);
    expect(screen.getByRole("combobox", { name: "Team (A)" })).toHaveValue("Alpha");
    expect(screen.getByRole("combobox", { name: "Team (B)" })).toHaveValue("Gamma");
  });

  test("a stale or corrupt remembered team is treated as none: the unit-wide search runs", async () => {
    serve(mockFetch);
    localStorage.setItem(KEY_A, "99");
    localStorage.setItem(KEY_B, JSON.stringify("not-a-number"));
    renderWithProviders(<Harness />);
    expect(screen.getByRole("combobox", { name: "Team (A)" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Team (B)" })).toHaveValue("");
    await waitFor(() => expect(userCalls(mockFetch)).toEqual(["/api/v1/jira-users?page=1&pageSize=20&sort=displayName&scope=UNIT"]));
  });
});
