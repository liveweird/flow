import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import HomeCodeSection from "./HomeCodeSection";
import { HOME } from "../test/homeFixtures";
import { renderWithProviders, screen, within } from "../test/render";
import type { HomeOverview } from "../api/home";
import type { DrillTarget } from "../utils/reportDrill";

const open = vi.fn();

afterEach(() => {
  localStorage.clear();
  open.mockReset();
});

function renderSection(overview: HomeOverview = HOME) {
  return renderWithProviders(<HomeCodeSection overview={overview} open={open} />);
}

const cells = (row: HTMLElement) => within(row).getAllByRole("cell").map((cell) => cell.textContent);
const rowOf = (name: string) => screen.getByRole("row", { name: new RegExp(`^${name}`) });

describe("HomeCodeSection — the code figures of each team in one table", () => {
  test("the title is the way into Code activity for the first team and the window; the caption names the directions in words", () => {
    renderSection();
    const link = screen.getByRole("link", { name: "Code" });
    const url = new URL(link.getAttribute("href") ?? "", "http://flow.test");
    expect(url.pathname).toBe("/reports/code-activity");
    expect(Object.fromEntries(url.searchParams)).toEqual({ from: "2026-07-02", to: "2026-09-29", teamId: "1" });
    const caption = screen.getByText(/More merged is better/);
    expect(caption).toHaveTextContent("a shorter cycle time and fewer merged without review are better");
    expect(caption).toHaveTextContent("The arrow compares with 2026-04-03 – 2026-07-01");
  });

  test("a row per team: the counts with their trend as a word, the medians, the drafts apart, the share beside its count", () => {
    renderSection();
    const table = screen.getByRole("table", { name: "Merge requests per team" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Team",
      "Merged",
      "Cycle time, p50 (working days)",
      "Open now",
      "Open age, p50 (working days)",
      "Merged without review",
      "Done tasks without a merged merge request",
    ]);
    expect(within(table).getAllByRole("rowheader").map((header) => header.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(cells(rowOf("Alpha"))).toEqual([
      "9 ↑ Better (was 7)",
      "2.4 ↓ Better (was 3.1)",
      "3 +1 draft",
      "4.2",
      "2 25% of 8 read ↓ Better (was 50%)",
      "10 25% of 40 done",
    ]);
  });

  test("a statistic under the minimum sample says so; the merged-without-review count stays and its share is hidden", () => {
    renderSection();
    expect(cells(rowOf("Beta"))).toEqual([
      "4 → No change (was 4)",
      "Below the minimum sample",
      "1",
      "Below the minimum sample",
      "1 share hidden: only 4 read",
      "0 0% of 3 done",
    ]);
  });

  test("a team with no linked GitLab identity reads so across its merge request columns, keeps its tasks cell, and a plain user gets no link", () => {
    renderSection();
    const gamma = rowOf("Gamma");
    expect(cells(gamma)).toEqual(["No linked GitLab identities", "No task done in the window"]);
    expect(within(gamma).getAllByRole("cell")[0]).toHaveAttribute("colspan", "5");
    expect(within(gamma).queryByRole("link")).not.toBeInTheDocument();
    expect(within(gamma).queryByRole("button")).not.toBeInTheDocument();
  });

  test("an administrator gets the way to link the identities of each source", () => {
    localStorage.setItem("flow.auth.roles", JSON.stringify(["ADMIN"]));
    renderSection();
    expect(within(rowOf("Gamma")).getByRole("link", { name: "Link identities on Stub GitLab" })).toHaveAttribute("href", "/data-sources/7/identities");
  });

  test("the three counts are drill buttons in tab order, a zero is plain text, and the medians are not buttons", async () => {
    renderSection();
    const alpha = rowOf("Alpha");
    const buttons = within(alpha).getAllByRole("button");
    expect(buttons.map((button) => button.getAttribute("aria-label"))).toEqual([
      "9 — Merged: show the items of Alpha",
      "3 — Open now: show the items of Alpha",
      "2 — Merged without review: show the items of Alpha",
      "10 — Done tasks without a merged merge request: show the items of Alpha",
    ]);
    const targets: DrillTarget[] = [];
    open.mockImplementation((target: DrillTarget) => targets.push(target));
    for (const button of buttons) await userEvent.click(button);
    expect(targets).toEqual([
      { kind: "home", view: "CODE_MERGED", teamId: 1 },
      { kind: "home", view: "CODE_OPEN", teamId: 1 },
      { kind: "home", view: "CODE_MERGED_WITHOUT_REVIEW", teamId: 1 },
      { kind: "home", view: "CODE_TASKS_WITHOUT_MR", teamId: 1 },
    ]);
    expect(open.mock.calls[0][1]).toMatchObject({ anchor: "home-code" });
    // Beta's merged-without-review count is 1 (a button even though its share is hidden); its tasks count is a zero, plain text.
    expect(within(rowOf("Beta")).getAllByRole("button")).toHaveLength(3);
  });

  test("the tasks cell is neutral context: no arrow, no verdict, and a team's zero or empty sample reads in words, not as 0 % or a dash", () => {
    renderSection();
    for (const name of ["Alpha", "Beta", "Gamma"]) {
      const tasks = within(rowOf(name)).getAllByRole("cell").at(-1) as HTMLElement;
      expect(tasks).not.toHaveTextContent(/Better|Worse|No change|was /);
    }
    // Beta: all three done tasks have a merged merge request - a zero count of a real sample, with its share.
    expect(within(within(rowOf("Beta")).getAllByRole("cell").at(-1) as HTMLElement).queryByRole("button")).not.toBeInTheDocument();
    // Gamma: nothing done - no share at all.
    expect(within(rowOf("Gamma")).queryByText(/%/)).not.toBeInTheDocument();
  });

  test("a tasks figure the server left out (its read was over the row budget) says so while the rest of the row stays", () => {
    const [alpha, ...others] = HOME.teams;
    renderSection({ ...HOME, teams: [{ ...alpha, code: { ...alpha.code!, tasks: null } }, ...others] });
    const cellsOfAlpha = cells(rowOf("Alpha"));
    expect(cellsOfAlpha.at(-1)).toBe("Too many to count here");
    expect(cellsOfAlpha[0]).toBe("9 ↑ Better (was 7)");
    expect(within(rowOf("Alpha")).getAllByRole("button")).toHaveLength(3);
  });

  test("over the row budget the section keeps its title and says so instead of a table", () => {
    renderSection({ ...HOME, code: { ...HOME.code, overBudget: true }, teams: HOME.teams.map((team) => ({ ...team, code: null })) });
    expect(screen.getByRole("link", { name: "Code" })).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("too many to summarise here");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
