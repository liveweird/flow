import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportComparePeople from "./ReportComparePeople";
import { codeCompareItems, COMPARE_PEOPLE } from "../test/comparePeopleFixtures";
import { showItems } from "../test/drill";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// The CODE group of Compare people (A50, Report 21's member cells): the rows as the server lists them, the volumes as plain context, and the lists
// behind them. The page itself is ReportComparePeople.test.tsx's; this file owns what the GitLab rows add.

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/compare-people?";
const ITEMS = "/api/v1/reports/compare-people/items?";
const PEOPLE = "accountId=acc-ann&accountId=acc-cy";
const TWO = `/reports/compare-people?${PEOPLE}`;

function serve(mockFetch: FetchMock, items?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS)) return Promise.resolve(items?.(url) ?? jsonResponse(200, codeCompareItems("MERGE_REQUEST", 7)));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, COMPARE_PEOPLE));
    if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/compare-people" element={<ReportComparePeople />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const table = () => screen.getByRole("table", { name: "Comparison of the two people, metric by metric" });
const rowOf = (name: string) => within(table()).getByRole("rowheader", { name }).closest("tr") as HTMLElement;
const cellsOf = (name: string) => within(rowOf(name)).getAllByRole("cell").map((cell) => cell.textContent);

describe("ReportComparePeople page — the code rows", () => {
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

  test("the code group is context: its volumes carry no tint or words, its merge-request p50 is the one verdict, a withheld p50 shows its sample", async () => {
    serve(mockFetch);
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    const group = within(table()).getByRole("rowheader", { name: "Code (GitLab)" });
    expect(group.getAttribute("scope")).toBe("rowgroup");
    // The rows follow the server's list, after the last Jira group.
    const rows = within(table()).getAllByRole("rowheader").filter((header) => header.getAttribute("scope") === "row").map((header) => header.textContent);
    expect(rows.slice(-8)).toEqual([
      "Commits",
      "Lines changed in commits (added + deleted)",
      "Merge requests merged",
      "Merge request cycle time, working days — median",
      "Merge request size (lines) — median",
      "Merge requests reviewed",
      "Merge requests approved",
      "Comments",
    ]);
    expect(cellsOf("Commits")).toEqual(["52n = 52", "32n = 32", "20"]);
    expect(cellsOf("Lines changed in commits (added + deleted)")).toEqual(["3400n = 52", "2200n = 32", "1200"]);
    expect(cellsOf("Merge requests reviewed")).toEqual(["0n = 0", "7n = 7", "7"]);
    // A volume is never marked, whatever the gap; the cycle p50 (lower is better) names B, with its words.
    for (const name of ["Commits", "Lines changed in commits (added + deleted)", "Merge requests merged", "Merge requests reviewed", "Merge requests approved", "Comments"]) {
      const row = rowOf(name);
      expect(row.querySelector("[data-better]"), name).toBeNull();
      expect(row.querySelector(".tabler-icon-check"), name).toBeNull();
      expect(row.textContent, name).not.toMatch(/better by/);
    }
    expect(cellsOf("Merge request cycle time, working days — median")).toEqual(["3n = 14", "1.5n = 5 (better by 1.5 days)", "1.5"]);
    expect(rowOf("Merge request cycle time, working days — median").querySelector("[data-better]")).toBe(within(rowOf("Merge request cycle time, working days — median")).getAllByRole("cell")[1]);
    // B has 3 merge requests with a known size: under the minimum of 5, so the p50 is withheld and there is no verdict.
    expect(cellsOf("Merge request size (lines) — median")).toEqual(["120n = 14", "—n = 3, below the minimum of 5", "—"]);
    expect(rowOf("Merge request size (lines) — median").querySelector("[data-better]")).toBeNull();
    // A person with nothing in a count has a plain 0, not a button; a figure with items behind it is one.
    expect(within(rowOf("Merge requests reviewed")).queryByRole("button", { name: showItems("Merge requests reviewed", "A · Ann Author") })).toBeNull();
    expect(within(rowOf("Commits")).getByRole("button", { name: showItems("Commits", "A · Ann Author") })).toBeInTheDocument();
  });

  test("the code rows list GitLab commits and merge requests: plain keys (no host to link to), a Person column, each view in its own unit and date", async () => {
    serve(mockFetch, (url) =>
      jsonResponse(
        200,
        url.includes("view=COMMITS")
          ? codeCompareItems("COMMIT", 52, 7)
          : url.includes("view=MR_SIZE_LINES")
            ? codeCompareItems("MERGE_REQUEST", 14, 120)
            : codeCompareItems("MERGE_REQUEST", 7),
      ),
    );
    const user = userEvent.setup();
    renderPage(TWO);
    await screen.findByRole("table", { name: "Comparison of the two people, metric by metric" });
    const headers = (drawer: HTMLElement) =>
      within(within(drawer).getByRole("table", { name: "Items behind the bar" }))
        .getAllByRole("columnheader")
        .map((header) => header.textContent);
    const open = async (row: string, column: string, title: string) => {
      await user.click(within(rowOf(row)).getByRole("button", { name: showItems(row, column) }));
      const drawer = await screen.findByRole("dialog", { name: title });
      await within(drawer).findByRole("table", { name: "Items behind the bar" });
      return drawer;
    };

    let drawer = await open("Commits", "A · Ann Author", "Commits · A · Ann Author");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Person", "Committed", "Lines changed"]);
    expect(await within(drawer).findByText("52 items")).toBeInTheDocument();
    // The key is text: this report names no GitLab host, so there is nothing to link to (and never a Jira link).
    expect(within(drawer).getByText("group/app@3f9a1c2b4d00")).toBeInTheDocument();
    expect(within(drawer).queryByRole("link")).toBeNull();
    // The table said 52 commits, the list says 52: no note.
    expect(within(drawer).queryByText("The data changed since the chart was drawn.")).not.toBeInTheDocument();
    expect(calls(mockFetch, ITEMS).at(-1)).toBe(`${ITEMS}${PEOPLE}&view=COMMITS&segmentAccountId=acc-ann`);
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    drawer = await open("Merge request size (lines) — median", "A · Ann Author", "Merge request size (lines) · A · Ann Author");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Person", "Merged", "Lines changed"]);
    expect(within(drawer).getByText("group/app!1")).toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    // A review act has no figure of its own: no figure column, a "Done on" date.
    drawer = await open("Merge requests reviewed", "B · Cy Coder", "Merge requests reviewed · B · Cy Coder");
    expect(headers(drawer)).toEqual(["Issue", "Summary", "Person", "Done on"]);
    expect(calls(mockFetch, ITEMS).at(-1)).toBe(`${ITEMS}${PEOPLE}&view=REVIEWS_GIVEN&segmentAccountId=acc-cy`);
  });
});
