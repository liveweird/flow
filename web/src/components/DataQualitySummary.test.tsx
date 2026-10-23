import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import DataQualitySummary from "./DataQualitySummary";
import { renderWithProviders, screen, within } from "../test/render";
import { DATA_QUALITY, DATA_QUALITY_CLEAN } from "../test/reportFixtures";
import type { DataQualityReport } from "../api/reports";

/** The tile whose link text is [title]: the figure beside it and its hint, read from the tile's paper. */
function tile(title: string): { value: string; hint: string | null } {
  const paper = screen.getByRole("link", { name: title }).parentElement!;
  const [, value, hint] = Array.from(paper.children).map((child) => child.textContent);
  return { value: value!, hint: hint ?? null };
}

describe("DataQualitySummary", () => {
  test("states the populations the findings were counted over, then the five captioned groups of tiles", () => {
    renderWithProviders(<DataQualitySummary report={DATA_QUALITY} />);
    const populations = screen.getByRole("group", { name: "What was checked" });
    expect(within(populations).getAllByText(/^\d+$/).map((node) => node.textContent)).toEqual(["20", "3", "4", "30"]);
    const tiles = screen.getByRole("group", { name: "Headline counts, each linking to its card" });
    expect(within(tiles).getAllByRole("heading", { level: 4 })).toHaveLength(5);
    // every tile links to its card by anchor
    for (const link of within(tiles).getAllByRole("link")) expect(link.getAttribute("href")).toMatch(/^#dq-/);
  });

  test("a period with no done task shows an em dash and says so, instead of a 0 % coverage", () => {
    const report: DataQualityReport = {
      ...DATA_QUALITY_CLEAN,
      worklogCoverage: { ...DATA_QUALITY_CLEAN.worklogCoverage, doneTasks: 0, withWorklogs: 0, coverage: null },
    };
    renderWithProviders(<DataQualitySummary report={report} />);
    expect(tile("Done tasks without worklogs")).toEqual({ value: "—", hint: "No done task in this period" });
  });

  test("a measured coverage is a percentage with its counts", () => {
    renderWithProviders(<DataQualitySummary report={DATA_QUALITY_CLEAN} />);
    expect(tile("Done tasks without worklogs")).toEqual({ value: "100%", hint: "20 of 20 done tasks have worklogs" });
  });

  test("an unconfigured work-category field is a dash with its reason, not a count of zero", () => {
    const report: DataQualityReport = {
      ...DATA_QUALITY_CLEAN,
      missing: { ...DATA_QUALITY_CLEAN.missing, workCategoryConfigured: false },
    };
    renderWithProviders(<DataQualitySummary report={report} />);
    expect(tile("Tasks without a work category")).toEqual({ value: "—", hint: "no work-category field configured" });
  });

  test("the boards tile adds the unattributed done tasks only when there are some", () => {
    const { unmount } = renderWithProviders(<DataQualitySummary report={DATA_QUALITY_CLEAN} />);
    expect(tile("Boards without a team").hint).toBe("boards without a team");
    unmount();
    const report: DataQualityReport = {
      ...DATA_QUALITY_CLEAN,
      unmappedBoards: { total: 2, items: [], unattributedDoneTasks: 5 },
    };
    renderWithProviders(<DataQualitySummary report={report} />);
    expect(tile("Boards without a team")).toEqual({ value: "2", hint: "boards without a team · unattributed done tasks: 5" });
  });

  test("a tile link scrolls to its card and focuses it without writing a hash into the URL", async () => {
    renderWithProviders(
      <>
        <div id="dq-coverage" tabIndex={-1}>
          card
        </div>
        <DataQualitySummary report={DATA_QUALITY} />
      </>,
    );
    const card = document.getElementById("dq-coverage")!;
    const scroll = vi.fn();
    card.scrollIntoView = scroll;
    const hashBefore = window.location.hash;
    await userEvent.click(screen.getByRole("link", { name: "Done tasks without worklogs" }));
    expect(scroll).toHaveBeenCalledWith({ block: "start" });
    expect(card).toHaveFocus();
    expect(window.location.hash).toBe(hashBefore);
  });

  test("a tile whose card is not on the page does nothing, quietly", async () => {
    renderWithProviders(<DataQualitySummary report={DATA_QUALITY} />);
    await expect(userEvent.click(screen.getByRole("link", { name: "Done tasks without worklogs" }))).resolves.toBeUndefined();
  });
});
