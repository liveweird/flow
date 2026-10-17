import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import JiraSeedModal from "./JiraSeedModal";
import { showSuccessToast } from "../utils/toast";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

type FetchMock = ReturnType<typeof vi.fn>;

const TEAMS = {
  items: [
    { id: "atl-1", name: "Payments", issueCount: 12 },
    { id: "atl-2", name: "Growth", issueCount: 3 },
  ],
};

const day = (y: number, m: number, d: number) => Date.UTC(y, m - 1, d, 12);

const CANDIDATES = {
  items: [
    { accountId: "acc-1", displayName: "Alice Admin", issueCount: 5, firstSeen: day(2025, 3, 10), lastSeen: day(2025, 9, 1), validFrom: day(2025, 3, 9), conflict: null },
    { accountId: "acc-2", displayName: "Bob Basic", issueCount: 2, firstSeen: day(2025, 4, 1), lastSeen: day(2025, 4, 20), validFrom: day(2025, 3, 31), conflict: null },
    {
      accountId: "acc-3",
      displayName: "Cara Committed",
      issueCount: 4,
      firstSeen: day(2025, 5, 2),
      lastSeen: day(2025, 6, 2),
      validFrom: day(2025, 5, 1),
      conflict: { teamId: 9, teamName: "Platform Core" },
    },
  ],
};

const SEED_URL = "/api/v1/teams/5/jira-memberships/seed";

function serve(mockFetch: FetchMock, seed: { status: number; body?: unknown } = { status: 200, body: { created: [], skipped: [] } }) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/jira-teams") return Promise.resolve(jsonResponse(200, TEAMS));
    if (method === "GET" && url === "/api/v1/teams/5/jira-memberships/seed-candidates?atlassianTeamId=atl-1") {
      return Promise.resolve(jsonResponse(200, CANDIDATES));
    }
    if (method === "GET" && url === "/api/v1/teams/5/jira-memberships/seed-candidates?atlassianTeamId=atl-2") {
      return Promise.resolve(jsonResponse(200, { items: [] }));
    }
    if (method === "POST" && url === SEED_URL) return Promise.resolve(jsonResponse(seed.status, seed.body));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function seedBody(mockFetch: FetchMock) {
  const call = mockFetch.mock.calls.find(([u, init]) => u === SEED_URL && (init as RequestInit | undefined)?.method === "POST");
  return call ? JSON.parse((call[1] as RequestInit).body as string) : undefined;
}

async function pickTeam(user: ReturnType<typeof userEvent.setup>, label: RegExp) {
  await user.click(await screen.findByRole("combobox", { name: /atlassian team/i }));
  await user.click(await screen.findByRole("option", { name: label }));
}

describe("JiraSeedModal", () => {
  let mockFetch: FetchMock;
  const onClose = vi.fn();
  const onSeeded = vi.fn(async () => {});

  function renderModal() {
    return renderWithProviders(<JiraSeedModal teamId={5} timeZone="Europe/Warsaw" onClose={onClose} onSeeded={onSeeded} />);
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("lists the Atlassian teams with their issue counts, then the candidates: non-conflicts ticked, conflicts disabled and named", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderModal();

    await user.click(await screen.findByRole("combobox", { name: /atlassian team/i }));
    expect(await screen.findByRole("option", { name: "Payments (12)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Growth (3)" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Payments (12)" }));

    expect(await screen.findByText("Alice Admin")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Seed Alice Admin" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Seed Bob Basic" })).toBeChecked();
    const conflicted = screen.getByRole("checkbox", { name: "Seed Cara Committed" });
    expect(conflicted).not.toBeChecked();
    expect(conflicted).toBeDisabled();
    expect(screen.getByText("Already in Platform Core")).toBeInTheDocument();

    // First/last issue days are read in the configured zone.
    const aliceRow = screen.getByText("Alice Admin").closest("tr") as HTMLElement;
    expect(within(aliceRow).getByText("2025-03-10")).toBeInTheDocument();
    expect(within(aliceRow).getByText("2025-09-01")).toBeInTheDocument();
    expect(within(aliceRow).getByText("5")).toBeInTheDocument();
    // The Starts column is what the seed stores (here one day before the first issue's day: a zone-day start).
    expect(within(aliceRow).getByText("2025-03-09")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seed selected (2)" })).toBeEnabled();
  });

  test("Apply posts only the ticked people, toasts created and skipped, and hands over to the parent", async () => {
    serve(mockFetch, {
      status: 200,
      body: { created: [{ accountId: "acc-1", validFrom: day(2025, 3, 9) }], skipped: [{ accountId: "acc-2", reason: "CONFLICT" }] },
    });
    const user = userEvent.setup();
    renderModal();

    await pickTeam(user, /Payments/);
    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("checkbox", { name: "Seed Bob Basic" }));
    expect(screen.getByRole("checkbox", { name: "Seed Bob Basic" })).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "Seed selected (1)" }));

    await waitFor(() => expect(onSeeded).toHaveBeenCalledTimes(1));
    expect(seedBody(mockFetch)).toEqual({ atlassianTeamId: "atl-1", accountIds: ["acc-1"] });
    expect(showSuccessToast).toHaveBeenCalledWith("Seeded 1, skipped 1");
  });

  test("with nobody ticked Apply is disabled; an empty team says so", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderModal();

    await pickTeam(user, /Payments/);
    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("checkbox", { name: "Seed Alice Admin" }));
    await user.click(screen.getByRole("checkbox", { name: "Seed Bob Basic" }));
    expect(screen.getByRole("button", { name: "Seed selected (0)" })).toBeDisabled();

    await pickTeam(user, /Growth/);
    expect(await screen.findByText("Nobody is assigned to issues of this team")).toBeInTheDocument();
    expect(screen.queryByText("Alice Admin")).not.toBeInTheDocument();
  });

  test("a 409 on Apply renders inline and keeps the dialog open", async () => {
    serve(mockFetch, { status: 409, body: { title: "Conflict", status: 409, detail: "Overlapping team membership for this account" } });
    const user = userEvent.setup();
    renderModal();

    await pickTeam(user, /Payments/);
    await screen.findByText("Alice Admin");
    await user.click(screen.getByRole("button", { name: "Seed selected (2)" }));

    expect(await screen.findByText(/A membership changed in the meantime/)).toBeInTheDocument();
    expect(onSeeded).not.toHaveBeenCalled();
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  test("a refetch never auto-ticks a candidate that appeared after the first load", async () => {
    const extra = { ...CANDIDATES.items[1], accountId: "acc-9", displayName: "Dora Late" };
    let calls = 0;
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/jira-teams") return Promise.resolve(jsonResponse(200, TEAMS));
      if (url.startsWith("/api/v1/teams/5/jira-memberships/seed-candidates")) {
        calls += 1;
        return Promise.resolve(jsonResponse(200, { items: calls === 1 ? CANDIDATES.items : [...CANDIDATES.items, extra] }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    renderWithProviders(<JiraSeedModal teamId={5} timeZone="Europe/Warsaw" onClose={onClose} onSeeded={onSeeded} />, { queryClient });

    await pickTeam(user, /Payments/);
    await screen.findByText("Alice Admin");
    expect(screen.getByRole("button", { name: "Seed selected (2)" })).toBeEnabled();

    await queryClient.refetchQueries({ queryKey: ["team-jira-seed-candidates"] });
    expect(await screen.findByText("Dora Late")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Seed Dora Late" })).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Seed selected (2)" })).toBeEnabled();
    await user.click(screen.getByRole("checkbox", { name: "Seed Dora Late" }));
    expect(screen.getByRole("button", { name: "Seed selected (3)" })).toBeEnabled();
  });

  test("more than 100 ticked people block Apply with a message", async () => {
    const many = Array.from({ length: 101 }, (_, i) => ({
      accountId: `bulk-${i}`,
      displayName: `Bulk ${i}`,
      issueCount: 1,
      firstSeen: day(2025, 1, 1),
      lastSeen: day(2025, 1, 2),
      validFrom: day(2025, 1, 1),
      conflict: null,
    }));
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/jira-teams") return Promise.resolve(jsonResponse(200, TEAMS));
      if (url.startsWith("/api/v1/teams/5/jira-memberships/seed-candidates")) return Promise.resolve(jsonResponse(200, { items: many }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderModal();

    await pickTeam(user, /Payments/);
    expect(await screen.findByText("At most 100 people can be seeded at once — untick some.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seed selected (101)" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "Seed Bulk 0" }));
    expect(screen.queryByText(/At most 100 people/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seed selected (100)" })).toBeEnabled();
  });
});
