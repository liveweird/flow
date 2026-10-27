import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import PersonTrendPicker from "./PersonTrendPicker";
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
const KEY = "flow.viewSettings.reports.personTrend.team";

function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (!url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    const params = new URL(url, "http://x").searchParams;
    const team = params.get("teamId");
    const items = PEOPLE.filter((person) => team === null || ROSTER[team]?.includes(person.accountId));
    return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: Number(params.get("pageSize")), total: items.length }));
  });
}

/** The page's role: the pick lives in the parent (on the page it is the URL). */
function Harness({ initial = [] }: { initial?: string[] }) {
  const [accountIds, setAccountIds] = useState<string[]>(initial);
  return (
    <>
      <PersonTrendPicker accountIds={accountIds} names={{}} teams={FILTERS.teams} onChange={setAccountIds} />
      <div data-testid="picks">{accountIds.join(",")}</div>
    </>
  );
}

const picks = () => screen.getByTestId("picks").textContent;

describe("PersonTrendPicker", () => {
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

  test("one Team select before one Person select, in a labelled group", () => {
    serve(mockFetch);
    renderWithProviders(<Harness />);
    expect(screen.getByRole("group", { name: "Person to follow" })).toBeInTheDocument();
    expect(screen.getAllByRole("combobox")).toHaveLength(2);
    expect(screen.getByRole("combobox", { name: "Person" })).toBeEnabled();
    expect(screen.getByRole("combobox", { name: "Team" })).toHaveValue("");
  });

  test("a team lists its members; picking one sets the pick and clearing it empties the pick", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await user.click(screen.getByRole("combobox", { name: "Person" }));
    expect(screen.queryByRole("option", { name: "Cy Coder" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
    await waitFor(() => expect(picks()).toBe("acc-bob"));

    await user.click(screen.getByLabelText("Clear Person"));
    await waitFor(() => expect(picks()).toBe(""));
  });

  test("the team is remembered under reports.personTrend.team and a remount restores it; a vanished team is ignored", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    expect(localStorage.getItem(KEY)).toBe("2");
    first.unmount();

    const second = renderWithProviders(<Harness />);
    expect(screen.getByRole("combobox", { name: "Team" })).toHaveValue("Beta");
    second.unmount();

    localStorage.setItem(KEY, "99");
    renderWithProviders(<Harness />);
    expect(screen.getByRole("combobox", { name: "Team" })).toHaveValue("");
  });

  test("the picked person is shown by their loaded name, else the account id", () => {
    serve(mockFetch);
    renderWithProviders(<Harness initial={["acc-unknown"]} />);
    expect(screen.getByRole("combobox", { name: "Person" })).toHaveValue("acc-unknown");
  });
});
