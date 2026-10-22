import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import TeamWorkCategoryTargets from "./TeamWorkCategoryTargets";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "flow.auth.token";
const ROLES_KEY = "flow.auth.roles";
const TARGETS_URL = "/api/v1/teams/1/work-category-targets";

type FetchMock = ReturnType<typeof vi.fn>;

const TEAM = { id: 1, name: "Payments", description: null, managerUserId: null, managerName: null, createdAt: 1, updatedAt: 2 };

function row(key: string, targetPercent: number | null, mapped = true) {
  return { key, targetPercent, mapped };
}
function targets(categories: ReturnType<typeof row>[], updatedAt: number | null = null) {
  return { teamId: 1, categories, updatedAt, updatedByUserId: updatedAt === null ? null : 7 };
}

/** Nothing stored: two mapped categories and the "No category" row. */
const EMPTY = targets([row("Feature", null), row("Maintenance", null), row("UNCATEGORIZED", null)]);
/** Stored 60 / 20 / 20, the last one on a category whose mapping is gone. */
const WITH_UNMAPPED = targets([row("Feature", 60), row("UNCATEGORIZED", 20), row("Legacy", 20, false)], 5);

function serve(mockFetch: FetchMock, body: unknown, put: { status: number; body?: unknown } = { status: 204 }) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/teams/1") return Promise.resolve(jsonResponse(200, TEAM));
    if (method === "GET" && url === TARGETS_URL) return Promise.resolve(jsonResponse(200, body));
    if (method === "PUT" && url === TARGETS_URL) {
      return Promise.resolve(put.body === undefined ? new Response(null, { status: put.status }) : jsonResponse(put.status, put.body));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function putBody(mockFetch: FetchMock): unknown {
  const call = mockFetch.mock.calls.find(([u, init]) => u === TARGETS_URL && (init as RequestInit | undefined)?.method === "PUT");
  return call ? JSON.parse((call[1] as RequestInit).body as string) : undefined;
}

function renderPage(route = "/teams/1/work-category-targets") {
  return renderWithProviders(
    <Routes>
      <Route path="/teams/:id/work-category-targets" element={<TeamWorkCategoryTargets />} />
    </Routes>,
    { route },
  );
}

describe("Team work-category targets page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an admin fills the targets, the total reaches 100 and Save PUTs the whole set", async () => {
    serve(mockFetch, EMPTY);
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByRole("heading", { name: "Work-category targets: Payments" })).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Work-category targets: Payments"]]);
    // The server's UNCATEGORIZED literal reads as "No category".
    expect(screen.getByText("No category")).toBeInTheDocument();
    expect(screen.queryByText("UNCATEGORIZED")).not.toBeInTheDocument();
    expect(screen.getByText("No targets are set for this team.")).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "Target % for Feature" }), "70");
    await user.type(screen.getByRole("textbox", { name: "Target % for Maintenance" }), "20");
    await user.type(screen.getByRole("textbox", { name: "Target % for No category" }), "10");
    expect(screen.getByText("100 %")).toBeInTheDocument();
    expect(screen.queryByText(/add up to/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBody(mockFetch)).toBeDefined());
    expect(putBody(mockFetch)).toEqual({
      targets: [
        { key: "Feature", targetPercent: 70 },
        { key: "Maintenance", targetPercent: 20 },
        { key: "UNCATEGORIZED", targetPercent: 10 },
      ],
    });
  });

  test("a total other than 100 blocks Save and says so; an empty form saves nothing but an empty set", async () => {
    serve(mockFetch, EMPTY);
    const user = userEvent.setup();
    renderPage();
    const feature = await screen.findByRole("textbox", { name: "Target % for Feature" });
    // Every field empty = no targets: valid, no message.
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    expect(screen.queryByText(/add up to/)).not.toBeInTheDocument();

    await user.type(feature, "40");
    expect(await screen.findByText(/The targets add up to 40 %, not 100 %/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(putBody(mockFetch)).toBeUndefined();

    await user.clear(feature);
    await waitFor(() => expect(screen.queryByText(/add up to/)).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBody(mockFetch)).toEqual({ targets: [] }));
  });

  test("Clear targets asks first, then PUTs an empty set", async () => {
    serve(mockFetch, targets([row("Feature", 100), row("UNCATEGORIZED", null)], 5));
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Clear targets" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/"Payments" will have no work-category targets/)).toBeInTheDocument();
    expect(putBody(mockFetch)).toBeUndefined();
    await user.click(within(dialog).getByRole("button", { name: "Clear targets" }));
    await waitFor(() => expect(putBody(mockFetch)).toEqual({ targets: [] }));
  });

  test("Clear targets is disabled while nothing is stored", async () => {
    serve(mockFetch, EMPTY);
    renderPage();
    expect(await screen.findByRole("button", { name: "Clear targets" })).toBeDisabled();
  });

  test("an unmapped stored row is badged, takes no input, is left out of the total and dropped on save", async () => {
    serve(mockFetch, WITH_UNMAPPED);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("textbox", { name: "Target % for Feature" });
    expect(screen.getByText("Not mapped any more")).toBeInTheDocument();
    expect(screen.getByText("Legacy")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Legacy/ })).not.toBeInTheDocument();
    expect(screen.getByText(/saving drops its stored target/)).toBeInTheDocument();
    // 60 + 20 mapped = 80: the unmapped 20 does not count, so Save is blocked until the mapped rows add up.
    expect(await screen.findByText(/The targets add up to 80 %, not 100 %/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    const none = screen.getByRole("textbox", { name: "Target % for No category" });
    await user.clear(none);
    await user.type(none, "40");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBody(mockFetch)).toBeDefined());
    expect(putBody(mockFetch)).toEqual({
      targets: [
        { key: "Feature", targetPercent: 60 },
        { key: "UNCATEGORIZED", targetPercent: 40 },
      ],
    });
  });

  test("a regular user reads the same table with no inputs, no Save and no Clear", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch, WITH_UNMAPPED);
    renderPage();
    expect(await screen.findByText("60 %")).toBeInTheDocument();
    expect(screen.getAllByText("20 %")).toHaveLength(2);
    expect(screen.getByText("Not mapped any more")).toBeInTheDocument();
    expect(screen.getByText("No category")).toBeInTheDocument();
    expect(screen.getByText("Only administrators can edit the targets.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Clear targets" })).not.toBeInTheDocument();
    // The stored set (unmapped row included) totals 100.
    expect(screen.getByText("100 %")).toBeInTheDocument();
  });

  test("a rejected save shows the fixed message inline", async () => {
    serve(mockFetch, targets([row("Feature", null)]), { status: 400, body: { title: "Bad", status: 400 } });
    const user = userEvent.setup();
    renderPage();
    await user.type(await screen.findByRole("textbox", { name: "Target % for Feature" }), "100");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/The targets are invalid/)).toBeInTheDocument();
  });

  test("an unknown team shows the not-found state", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This team does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to teams" })).toHaveAttribute("href", "/teams");
  });

  test("a non-numeric team id is not found without any request", async () => {
    renderPage("/teams/abc/work-category-targets");
    expect(await screen.findByText("This team does not exist (or was deleted).")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
