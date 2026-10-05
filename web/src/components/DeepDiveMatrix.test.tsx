import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { DeepDiveReport } from "../api/reports";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { act, fireEvent, renderWithProviders, screen, waitFor, within } from "../test/render";
import { theme } from "../theme";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { DeepDiveLayers } from "../utils/deepDiveCell";
import DeepDiveMatrix from "./DeepDiveMatrix";

const ALL: DeepDiveLayers = { pv: true, exec: true, cost: true };
const GRID_LABEL = "Plan, execution and cost by epic and time";

/**
 * Thu 2026-08-27 … Tue 2026-09-08 (offsets 0..12). E-1 "Alpha" has two tasks, a planned window across both months and a
 * budget; E-2 has a task and worklogs on the epic itself; E-3 and E-4 are planned entirely before / after the range and
 * have no tasks; the last row is the "(no epic)" one.
 */
function sampleReport(): DeepDiveReport {
  return deepDiveReport({
    ...MONTH_CROSSING,
    authors: [{ accountId: "a1", displayName: "Ann Lee" }],
    epics: [
      deepDiveEpic("E-1", { summary: "Alpha", plannedStart: 3, plannedDue: 8, budgetMd: 5 }),
      deepDiveEpic("E-2", {
        summary: "Beta",
        ownCost: { cost: [{ d: 6, a: 0, md: 0.75 }], totalMd: 0.75 },
      }),
      deepDiveEpic("E-3", { summary: "Gamma", plannedStart: -10, plannedDue: -3 }),
      deepDiveEpic("E-4", { summary: "Delta", plannedStart: 20, plannedDue: 30 }),
      deepDiveEpic(null),
    ],
    tasks: [
      deepDiveTask("FLO-1", {
        summary: "First",
        epicKey: "E-1",
        planBasisMd: 3,
        planSource: "EARLIEST",
        pv: [
          { d: 0, md: 1 },
          { d: 5, md: 2 },
        ],
        exec: [{ d: 5, td: 0.5 }],
        cost: [
          { d: 5, a: 0, md: 1.5 },
          { d: 5, a: null, md: 0.5 },
        ],
        done: { d: 6, evMd: 3 },
        totals: { pvMd: 3, execTaskDays: 0.5, evMd: 3, costMd: 2 },
      }),
      deepDiveTask("FLO-2", {
        summary: "Second",
        epicKey: "E-1",
        noPlanReason: "NO_ESTIMATE",
        cost: [{ d: 1, a: 0, md: 1 }],
        totals: { pvMd: 0, execTaskDays: 0, evMd: 0, costMd: 1 },
      }),
      deepDiveTask("FLO-3", { summary: "Third", epicKey: "E-2", pv: [{ d: 6, md: 1 }], totals: { pvMd: 1, execTaskDays: 0, evMd: 0, costMd: 0 } }),
      deepDiveTask("FLO-4", { summary: "Loose", exec: [{ d: 0, td: 1 }], totals: { pvMd: 0, execTaskDays: 1, evMd: 0, costMd: 0 } }),
    ],
  });
}

const gridOf = () => screen.getByRole("grid");
const bars = (layer: string) => gridOf().querySelectorAll(`[data-layer="${layer}"]`);
const rowOf = (name: RegExp | string) => within(gridOf()).getByRole("rowheader", { name }).closest("tr") as HTMLElement;
const headerTexts = () => within(gridOf()).getAllByRole("columnheader").map((th) => th.textContent);
const expandMonth = (label: string) => screen.getByRole("button", { name: `Expand ${label} into weeks` });
const expandWeek = (label: string) => screen.getByRole("button", { name: `Expand ${label} into days` });
const alphaCell = (column: string) => within(rowOf(/E-1 Alpha/)).getByRole("gridcell", { name: new RegExp(`^E-1 Alpha, ${column}[ ,:]`) });
const focusOn = (element: HTMLElement) => act(() => element.focus());
const rect = (left: number, top: number, bottom: number, width = 80) =>
  ({ left, top, bottom, right: left + width, width, height: bottom - top, x: left, y: top, toJSON: () => ({}) }) as DOMRect;
/** The element holding the grid's one tab stop. */
const tabStops = () => gridOf().querySelectorAll('[data-grid-pos][tabindex="0"]');
const assertFocusedIsTabStop = () => {
  expect(tabStops()).toHaveLength(1);
  expect(document.activeElement).toBe(tabStops()[0]);
};

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

test("renders month columns first, over epic rows with the no-epic row last", () => {
  renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
  expect(gridOf()).toHaveAttribute("aria-label", GRID_LABEL);
  expect(headerTexts()).toEqual(["Epic / task", "2026-08", "2026-09"]);
  expect(expandMonth("2026-08")).toHaveAttribute("aria-expanded", "false");
  expect(expandMonth("2026-09")).toHaveAttribute("aria-expanded", "false");
  const rows = within(gridOf()).getAllByRole("rowheader").map((th) => th.textContent);
  expect(rows[0]).toContain("E-1 Alpha");
  expect(rows[rows.length - 1]).toBe("(no epic)");
  expect(gridOf()).toHaveAttribute("aria-rowcount", "6");
  expect(gridOf()).toHaveAttribute("aria-colcount", "3");
});

describe("DeepDiveMatrix: drilling the columns", () => {
  test("an open month stays in the header as the control that closes it, over the weeks it produced", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    // 2026-09-01 (a Tuesday) starts ISO week 36 inside September; the Monday after starts week 37.
    expect(headerTexts()).toEqual(["Epic / task", "2026-08", "2026-09", "2026-W36", "2026-W37"]);
    const group = screen.getByRole("button", { name: "Collapse 2026-09" });
    expect(group).toHaveAttribute("aria-expanded", "true");
    expect(group.closest("th")).toHaveAttribute("colspan", "2");
    expect(expandWeek("2026-W36")).toHaveAttribute("aria-expanded", "false");
    // two header rows now: aria-rowcount and -colcount follow the multi-row header
    expect(gridOf()).toHaveAttribute("aria-rowcount", "7");
    expect(gridOf()).toHaveAttribute("aria-colcount", "4");
    await user.click(group);
    expect(headerTexts()).toEqual(["Epic / task", "2026-08", "2026-09"]);
    expect(gridOf()).toHaveAttribute("aria-rowcount", "6");
  });

  test("a week expands into its days, which are plain headers under the open week and month", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    await user.click(expandWeek("2026-W37"));
    const day = within(gridOf()).getByRole("columnheader", { name: "2026-09-07" });
    expect(day).toHaveTextContent("09-07");
    expect(within(day).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Collapse 2026-W37" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("button", { name: "Collapse 2026-09" })).toHaveAttribute("aria-expanded", "true");
    expect(gridOf()).toHaveAttribute("aria-rowcount", "8"); // five body rows + three header rows
  });

  test("Collapse all columns returns to months and appears only while something is open", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    expect(screen.queryByRole("button", { name: "Collapse all columns" })).not.toBeInTheDocument();
    await user.click(expandMonth("2026-09"));
    await user.click(expandWeek("2026-W37"));
    await user.click(screen.getByRole("button", { name: "Collapse all columns" }));
    expect(headerTexts()).toEqual(["Epic / task", "2026-08", "2026-09"]);
    expect(screen.queryByRole("button", { name: "Collapse all columns" })).not.toBeInTheDocument();
  });

  test("focus follows the drill: to the first new column on expand, back to the closed header on collapse, to the tab stop on collapse-all", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    expect(expandWeek("2026-W36")).toHaveFocus();
    assertFocusedIsTabStop();

    await user.click(expandWeek("2026-W37"));
    // the first day of the week just opened; days are plain headers, so the header cell itself holds focus
    expect(within(gridOf()).getByRole("columnheader", { name: "2026-09-07" })).toHaveFocus();
    assertFocusedIsTabStop();

    await user.click(screen.getByRole("button", { name: "Collapse 2026-W37" }));
    expect(expandWeek("2026-W37")).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "Collapse 2026-09" }));
    expect(expandMonth("2026-09")).toHaveFocus();
    assertFocusedIsTabStop();

    await user.click(expandMonth("2026-09"));
    await user.click(screen.getByRole("button", { name: "Collapse all columns" }));
    expect(headerTexts()).toEqual(["Epic / task", "2026-08", "2026-09"]);
    // the roving stop sat on the week header row, whose position now holds the month header: focus lands on the tab stop
    expect(expandMonth("2026-09")).toHaveFocus();
    assertFocusedIsTabStop();
  });

  test("focus goes to the roving stop after Collapse all rows", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    await user.click(screen.getByRole("button", { name: "Collapse all rows" }));
    expect(screen.getByRole("button", { name: "E-1 Alpha" })).toHaveFocus();
    assertFocusedIsTabStop();
  });

  test("a drill is announced in a polite live region", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toBeEmptyDOMElement();
    await user.click(expandMonth("2026-09"));
    expect(status).toHaveTextContent("Showing weeks of 2026-09");
    await user.click(expandWeek("2026-W37"));
    expect(status).toHaveTextContent("Showing days of 2026-W37");
    await user.click(screen.getByRole("button", { name: "Collapse 2026-W37" }));
    expect(status).toHaveTextContent("Collapsed 2026-W37");
    await user.click(screen.getByRole("button", { name: "Collapse all columns" }));
    expect(status).toHaveTextContent("Showing months");
  });
});

describe("DeepDiveMatrix: drilling the rows", () => {
  test("expanding an epic shows its tasks and the on-the-epic line; Collapse all rows closes them", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const alpha = screen.getByRole("button", { name: "E-1 Alpha" });
    expect(alpha).toHaveAttribute("aria-expanded", "false");
    expect(within(gridOf()).queryByRole("rowheader", { name: "FLO-1 First" })).not.toBeInTheDocument();
    await user.click(alpha);
    expect(alpha).toHaveAttribute("aria-expanded", "true");
    expect(alpha).toHaveFocus(); // the same element, so focus stays put
    expect(within(gridOf()).getByRole("rowheader", { name: "FLO-1 First" })).toBeInTheDocument();
    expect(within(gridOf()).getByRole("rowheader", { name: "FLO-2 Second" })).toBeInTheDocument();
    expect(within(gridOf()).queryByText("(on the epic)")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "E-2 Beta" }));
    expect(within(gridOf()).getByRole("rowheader", { name: "FLO-3 Third" })).toBeInTheDocument();
    expect(within(gridOf()).getByRole("rowheader", { name: "(on the epic)" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collapse all rows" }));
    expect(within(gridOf()).queryByRole("rowheader", { name: "FLO-1 First" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "E-1 Alpha" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Collapse all rows" })).not.toBeInTheDocument();
  });

  test("an epic with nothing under it is a plain row header, not a toggle", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    expect(screen.queryByRole("button", { name: /E-3/ })).not.toBeInTheDocument();
    expect(within(gridOf()).getByRole("rowheader", { name: /E-3 Gamma/ })).toBeInTheDocument();
  });

  test("an epic row header's accessible name carries its planned window and budget", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const header = within(gridOf()).getByRole("rowheader", { name: /^E-1 Alpha/ });
    expect(header).toHaveAccessibleName("E-1 Alpha Planned 2026-08-30 to 2026-09-04 · Budget 5 MD");
    // an epic with neither says nothing more
    expect(within(gridOf()).getByRole("rowheader", { name: "(no epic)" })).toBeInTheDocument();
  });
});

describe("DeepDiveMatrix: layers and bars", () => {
  test("layer toggles hide and show the bars and the done marker", () => {
    const report = sampleReport();
    const { rerender } = renderWithProviders(<DeepDiveMatrix report={report} layers={ALL} />);
    expect(bars("pv").length).toBeGreaterThan(0);
    expect(bars("exec").length).toBeGreaterThan(0);
    expect(bars("cost").length).toBeGreaterThan(0);
    expect(bars("done").length).toBeGreaterThan(0);

    rerender(<DeepDiveMatrix report={report} layers={{ pv: false, exec: true, cost: true }} />);
    expect(bars("pv")).toHaveLength(0);
    expect(bars("exec").length).toBeGreaterThan(0);

    rerender(<DeepDiveMatrix report={report} layers={{ pv: true, exec: false, cost: true }} />);
    expect(bars("exec")).toHaveLength(0);
    expect(bars("done")).toHaveLength(0);
    expect(bars("pv").length).toBeGreaterThan(0);

    rerender(<DeepDiveMatrix report={report} layers={{ pv: true, exec: true, cost: false }} />);
    expect(bars("cost")).toHaveLength(0);

    rerender(<DeepDiveMatrix report={report} layers={{ pv: false, exec: false, cost: false }} />);
    expect(gridOf().querySelectorAll("[data-layer]")).toHaveLength(0);
  });

  test("bars encode the figure by height against the shared scale and by width per layer", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    // E-1, 2026-09: PV 2 MD and cost 2 MD share the MD scale whose maximum is 2 (E-1's September), so both fill the cell.
    const cell = alphaCell("2026-09");
    const pv = cell.querySelector<HTMLElement>('[data-layer="pv"]');
    const exec = cell.querySelector<HTMLElement>('[data-layer="exec"]');
    const cost = cell.querySelector<HTMLElement>('[data-layer="cost"]');
    expect(pv?.style.height).toBe("100%");
    expect(cost?.style.height).toBe("100%");
    // Execution has its own scale: 0.5 task-days is the largest execution figure, so it also fills.
    expect(exec?.style.height).toBe("50%");
    expect(pv?.className).not.toBe(exec?.className);
    expect(exec?.className).not.toBe(cost?.className);
    expect(cell.querySelector('[data-layer="done"]')).toHaveTextContent("◆");
  });
});

describe("DeepDiveMatrix: labels and tooltip text", () => {
  test("every cell's aria-label carries its numbers, the author list and the date span", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    expect(alphaCell("2026-09")).toHaveAccessibleName(
      "E-1 Alpha, 2026-09, 2026-09-01 to 2026-09-08: Plan (PV): 2 MD; Execution (task-days): 0.5; Earned value (EV): 3 MD; Done: FLO-1; Cost (AC): 2 MD (Ann Lee: 1.5 MD, unknown author: 0.5 MD)",
    );
    // a cell with nothing in the layers shown says so instead of reading a row of zeros
    const empty = within(rowOf(/E-3 Gamma/)).getByRole("gridcell", { name: /2026-08,/ });
    expect(empty).toHaveAccessibleName("E-3 Gamma, 2026-08, 2026-08-27 to 2026-08-31: nothing in this period");
    // a cell with a figure in one layer reads every shown layer, zeros included
    const partial = within(rowOf("(no epic)")).getByRole("gridcell", { name: /2026-08,/ });
    expect(partial).toHaveAccessibleName(
      "(no epic), 2026-08, 2026-08-27 to 2026-08-31: Plan (PV): 0 MD; Execution (task-days): 1; Earned value (EV): 0 MD; Cost (AC): 0 MD",
    );
  });

  test("a cell with only hidden layers says there is nothing in it", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={{ pv: false, exec: false, cost: false }} />);
    expect(alphaCell("2026-09")).toHaveAccessibleName("E-1 Alpha, 2026-09, 2026-09-01 to 2026-09-08: nothing in this period");
  });

  test("a non-working column's cells say so in their label, and the column is hatched", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    await user.click(expandWeek("2026-W36"));
    // 2026-09-05 and -06 are the weekend: offsets 9 and 10
    expect(within(gridOf()).getByRole("columnheader", { name: "2026-09-05" }).className).toContain("ddHatched");
    expect(within(gridOf()).getByRole("columnheader", { name: "2026-09-04" }).className).not.toContain("ddHatched");
    const saturday = alphaCell("2026-09-05");
    expect(saturday.className).toContain("ddHatched");
    expect(saturday).toHaveAccessibleName("E-1 Alpha, 2026-09-05 (non-working): nothing in this period");
    expect(alphaCell("2026-09-04")).not.toHaveAccessibleName(expect.stringContaining("non-working"));
  });

  test("the tooltip says the same as the label for working and non-working cells alike", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    // "a; b (Ann: 1 MD, unknown author: 2 MD)": the label's figures, the author list flattened into its own entries
    const tokensOf = (figures: string) =>
      figures.split("; ").flatMap((part) => {
        const open = part.lastIndexOf(" (");
        return part.endsWith(")") && open > 0 && part.slice(open).includes(": ")
          ? [part.slice(0, open), ...part.slice(open + 2, -1).split(", ")]
          : [part];
      });
    const compare = async (cell: HTMLElement, headLength: number) => {
      focusOn(cell);
      const tip = await screen.findByRole("tooltip");
      const label = cell.getAttribute("aria-label") ?? "";
      const tokens = tokensOf(label.slice(headLength + 2));
      for (const token of tokens) expect(tip, token).toHaveTextContent(token);
      // title and date line: everything in the label before the figures
      for (const part of label.slice(0, headLength).split(/, (?=\d{4}-)/)) expect(tip, part).toHaveTextContent(part);
      return tokens.length;
    };
    const cell = alphaCell("2026-09");
    expect(await compare(cell, "E-1 Alpha, 2026-09, 2026-09-01 to 2026-09-08".length)).toBeGreaterThanOrEqual(6);
    await user.click(expandMonth("2026-09"));
    await user.click(expandWeek("2026-W36"));
    const saturday = alphaCell("2026-09-05");
    await compare(saturday, "E-1 Alpha, 2026-09-05 (non-working)".length);
    const tipText = screen.getByRole("tooltip").textContent ?? "";
    expect(tipText).toContain("2026-09-05 (non-working)");
    // a day column's label IS its date: the card says it once, in the title
    expect(tipText.split("2026-09-05")).toHaveLength(2);
  });

  test("the tooltip opens on keyboard focus with the author list and closes on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    const cell = alphaCell("2026-09");
    focusOn(cell);
    const tip = await screen.findByRole("tooltip");
    expect(tip).toHaveTextContent("E-1 Alpha, 2026-09");
    expect(tip).toHaveTextContent("2026-09-01 to 2026-09-08");
    expect(tip).toHaveTextContent("Plan (PV): 2 MD");
    expect(tip).toHaveTextContent("Execution (task-days): 0.5");
    expect(tip).toHaveTextContent("Earned value (EV): 3 MD");
    expect(tip).toHaveTextContent("Done: FLO-1");
    expect(tip).toHaveTextContent("Cost (AC): 2 MD");
    expect(within(tip).getByText("Ann Lee: 1.5 MD")).toBeInTheDocument();
    expect(within(tip).getByText("unknown author: 0.5 MD")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    // dismissed stays dismissed while the same cell is hovered, and returns for another cell
    await user.hover(cell);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    focusOn(within(rowOf(/E-1 Alpha/)).getAllByRole("gridcell")[0]);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("2026-08");
  });

  test("the tooltip of an empty cell says so", async () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    focusOn(within(rowOf(/E-3 Gamma/)).getByRole("gridcell", { name: /2026-08,/ }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("nothing in this period");
  });

  test("the tooltip shows only the layers that are on", async () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={{ pv: true, exec: false, cost: false }} />);
    focusOn(alphaCell("2026-09"));
    const tip = await screen.findByRole("tooltip");
    expect(tip).toHaveTextContent("Plan (PV): 2 MD");
    expect(tip).not.toHaveTextContent("Execution");
    expect(tip).not.toHaveTextContent("Cost (AC)");
  });
});

describe("DeepDiveMatrix: tooltip pointer and scroll rules", () => {
  const hover = (cell: HTMLElement) => fireEvent.mouseOver(cell);

  test("the card abuts its cell, so the pointer reaches it without crossing a gap", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const cell = alphaCell("2026-09");
    vi.spyOn(cell, "getBoundingClientRect").mockReturnValue(rect(100, 200, 240));
    hover(cell);
    const tip = screen.getByRole("tooltip");
    expect(tip.style.top).toBe("240px");
    expect(tip.style.left).toBe("100px");
  });

  test("a different cell only replaces an open card after a short delay, so the pointer can travel to it", () => {
    vi.useFakeTimers();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    hover(alphaCell("2026-08"));
    expect(screen.getByRole("tooltip")).toHaveTextContent("E-1 Alpha, 2026-08");
    hover(alphaCell("2026-09"));
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(screen.getByRole("tooltip")).toHaveTextContent("E-1 Alpha, 2026-08");
    act(() => {
      vi.advanceTimersByTime(30);
    });
    expect(screen.getByRole("tooltip")).toHaveTextContent("E-1 Alpha, 2026-09");
  });

  test("reaching the card cancels the pending replacement and keeps it open; leaving closes it", () => {
    vi.useFakeTimers();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    hover(alphaCell("2026-08"));
    hover(alphaCell("2026-09"));
    const tip = screen.getByRole("tooltip");
    fireEvent.mouseEnter(tip);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole("tooltip")).toHaveTextContent("E-1 Alpha, 2026-08");
    fireEvent.mouseLeave(tip);
    act(() => {
      vi.advanceTimersByTime(130);
    });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  test("the pointer leaving the grid closes the card after a short grace and cancels a pending replacement", () => {
    vi.useFakeTimers();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    hover(alphaCell("2026-08"));
    hover(alphaCell("2026-09"));
    fireEvent.mouseLeave(gridOf());
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  test("the same cell does not re-read its rectangle on every pointer movement over its parts", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const cell = alphaCell("2026-09");
    const spy = vi.spyOn(cell, "getBoundingClientRect");
    hover(cell);
    hover(cell.querySelector("[data-layer]") as HTMLElement);
    hover(cell);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  test("a scroll inside the card does not close it; a scroll elsewhere closes a pointer-opened card", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    hover(alphaCell("2026-09"));
    const tip = screen.getByRole("tooltip");
    fireEvent.scroll(tip);
    expect(screen.getByRole("tooltip")).toBeInTheDocument();
    fireEvent.scroll(screen.getByRole("region", { name: GRID_LABEL }));
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  test("a focus-opened card follows its cell when the grid scrolls and closes only once the cell has left the scroller", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const region = screen.getByRole("region", { name: GRID_LABEL });
    vi.spyOn(region, "getBoundingClientRect").mockReturnValue(rect(0, 100, 700, 1000));
    const cell = alphaCell("2026-09");
    const cellRect = vi.spyOn(cell, "getBoundingClientRect").mockReturnValue(rect(100, 200, 240));
    focusOn(cell);
    expect(screen.getByRole("tooltip").style.top).toBe("240px");

    cellRect.mockReturnValue(rect(100, 300, 340));
    fireEvent.scroll(region);
    expect(screen.getByRole("tooltip").style.top).toBe("340px");

    cellRect.mockReturnValue(rect(100, 800, 840)); // scrolled out of the scroller's box
    fireEvent.scroll(region);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
});

describe("DeepDiveMatrix: keyboard", () => {
  test("one tab stop for the grid, arrows move focus, Enter on a header toggles it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    expect(tabStops()).toHaveLength(1);

    // The scroller is itself a stop (axe), then the grid's roving stop: the first epic's toggle.
    await user.tab();
    expect(screen.getByRole("region", { name: GRID_LABEL })).toHaveFocus();
    await user.tab();
    const alpha = screen.getByRole("button", { name: "E-1 Alpha" });
    expect(alpha).toHaveFocus();

    await user.keyboard("{ArrowRight}");
    const cells = within(rowOf(/E-1 Alpha/)).getAllByRole("gridcell");
    expect(cells[0]).toHaveFocus();
    await user.keyboard("{ArrowRight}");
    expect(cells[1]).toHaveFocus();
    await user.keyboard("{ArrowRight}");
    expect(cells[1]).toHaveFocus(); // the last column stays put
    expect(tabStops()).toHaveLength(1);
    expect(cells[1]).toHaveAttribute("tabindex", "0");

    await user.keyboard("{ArrowUp}");
    expect(expandMonth("2026-09")).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(expandMonth("2026-08")).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(within(gridOf()).getByRole("columnheader", { name: "Epic / task" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(alpha).toHaveFocus();

    await user.keyboard("{Enter}");
    expect(alpha).toHaveAttribute("aria-expanded", "true");
    await user.keyboard("{ArrowDown}");
    expect(within(gridOf()).getByRole("rowheader", { name: "FLO-1 First" }).querySelector("[data-grid-pos]")).toHaveFocus();

    await user.keyboard("{End}");
    const firstTask = within(rowOf("FLO-1 First")).getAllByRole("gridcell");
    expect(firstTask[firstTask.length - 1]).toHaveFocus();
    await user.keyboard("{Home}");
    expect(within(gridOf()).getByRole("rowheader", { name: "FLO-1 First" }).querySelector("[data-grid-pos]")).toHaveFocus();
    await user.keyboard("{Control>}{End}{/Control}");
    const lastRow = within(gridOf()).getAllByRole("row").at(-1) as HTMLElement;
    const lastCells = within(lastRow).getAllByRole("gridcell");
    expect(lastCells[lastCells.length - 1]).toHaveFocus();

    await user.keyboard("{Control>}{Home}{/Control}");
    expect(within(gridOf()).getByRole("columnheader", { name: "Epic / task" })).toHaveFocus();
    // Enter on a column header opens it, and focus moves on to its first new column
    await user.keyboard("{ArrowRight}{ArrowRight}{Enter}");
    expect(screen.getByRole("button", { name: "Collapse 2026-09" })).toHaveAttribute("aria-expanded", "true");
    expect(expandWeek("2026-W36")).toHaveFocus();
  });

  test("arrows cross a multi-row header: spanning cells cover their columns and cells that span down are skipped", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09")); // focus lands on the W36 header
    const group = screen.getByRole("button", { name: "Collapse 2026-09" });
    const w36 = expandWeek("2026-W36");
    const w37 = expandWeek("2026-W37");
    const august = expandMonth("2026-08");
    expect(w36).toHaveFocus();

    await user.keyboard("{ArrowUp}");
    expect(group).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(w36).toHaveFocus();
    await user.keyboard("{ArrowRight}");
    expect(w37).toHaveFocus();
    await user.keyboard("{ArrowUp}"); // W37's column is covered by the open month's span
    expect(group).toHaveFocus();
    await user.keyboard("{ArrowRight}"); // nothing beyond the span: stays
    expect(group).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(august).toHaveFocus();
    await user.keyboard("{ArrowDown}"); // August spans both header rows, so the next stop down is the body
    const firstCell = within(rowOf(/E-1 Alpha/)).getAllByRole("gridcell")[0];
    expect(firstCell).toHaveFocus();
    await user.keyboard("{ArrowUp}");
    expect(august).toHaveFocus();
    await user.keyboard("{ArrowDown}{ArrowRight}{ArrowUp}");
    expect(w36).toHaveFocus(); // the W36 column's header, one row below the month's
    await user.keyboard("{Home}");
    expect(w36).toHaveFocus(); // the first header cell of that row
  });

  test("tab order: the collapse buttons, then the scroller, then the grid's one stop — which is the last focused header", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    expect(expandWeek("2026-W36")).toHaveFocus();
    await user.click(document.body);
    await user.tab();
    expect(screen.getByRole("button", { name: "Collapse all columns" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("region", { name: GRID_LABEL })).toHaveFocus();
    await user.tab();
    expect(expandWeek("2026-W36")).toHaveFocus();
    assertFocusedIsTabStop();
    await user.tab();
    // past the grid: the next stops are the table's siblings below, never another cell
    expect(gridOf().contains(document.activeElement)).toBe(false);
    await user.tab({ shift: true });
    expect(expandWeek("2026-W36")).toHaveFocus();
  });
});

describe("DeepDiveMatrix: planned window", () => {
  test("the epic's planned window is outlined across the columns it spans; a window outside the range is a hint", () => {
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const outlined = rowOf(/E-1 Alpha/).querySelectorAll("[data-window]");
    expect(outlined).toHaveLength(2);
    expect(outlined[0].className).toContain("ddOutlineStart");
    expect(outlined[0].className).not.toContain("ddOutlineEnd");
    expect(outlined[1].className).toContain("ddOutlineEnd");
    expect(outlined[1].className).not.toContain("ddOutlineStart");

    for (const row of [rowOf(/E-3 Gamma/), rowOf(/E-4 Delta/)]) expect(row.querySelectorAll("[data-window]")).toHaveLength(0);
    expect(within(rowOf(/E-3 Gamma/)).getByText("Planned before the range")).toBeInTheDocument();
    expect(within(rowOf(/E-4 Delta/)).getByText("Planned after the range")).toBeInTheDocument();
    expect(within(rowOf(/E-1 Alpha/)).queryByText(/Planned (before|after)/)).not.toBeInTheDocument();
    // their dates are still stated: window offsets are from range.from (2026-08-27)
    expect(within(rowOf(/E-3 Gamma/)).getByText("Planned 2026-08-17 to 2026-08-24")).toBeInTheDocument();
  });

  test("the outline follows the drill: a window running past the range is open at that edge", async () => {
    const user = userEvent.setup();
    const report = deepDiveReport({
      ...MONTH_CROSSING,
      epics: [deepDiveEpic("E-9", { summary: "Open", plannedStart: -4, plannedDue: 6 })],
      tasks: [deepDiveTask("FLO-9", { epicKey: "E-9" })],
    });
    renderWithProviders(<DeepDiveMatrix report={report} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    // columns: 2026-08, W36, W37 — the window covers 2026-08 and W36 (offset 6 is 09-02); it starts before the range
    const outlined = rowOf(/E-9 Open/).querySelectorAll("[data-window]");
    expect(outlined).toHaveLength(2);
    expect(outlined[0].className).not.toContain("ddOutlineStart");
    expect(outlined[1].className).toContain("ddOutlineEnd");
  });
});

describe("DeepDiveMatrix: size guards", () => {
  test("a grid beyond the cell ceiling is replaced by a notice that asks to narrow; at the ceiling it still draws", () => {
    const over = renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={9} />);
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    expect(screen.getByText(/5 rows by 2 columns is 10 cells/)).toBeInTheDocument();
    // nothing is open to collapse, so it says to narrow the selection or the range
    expect(screen.getByText(/Narrow the selection or the date range/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show figures per visible column" })).not.toBeInTheDocument();
    over.unmount();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={10} />);
    expect(screen.getByRole("grid")).toBeInTheDocument();
  });

  test("the notice still offers the collapses that bring the grid back, and says to collapse while something is open", async () => {
    const user = userEvent.setup();
    // 5 epic rows × 2 months = 10 cells fit; opening E-1 adds two task rows (7 × 2 = 14) and trips a ceiling of 12
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={12} />);
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    expect(screen.getByText(/Collapse some columns or epics/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collapse all rows" }));
    expect(screen.getByRole("grid")).toBeInTheDocument();
  });

  test("the documented default is 25,000 visible cells: 710 epics over 36 months draw nothing", () => {
    const epics = Array.from({ length: 710 }, (_, i) => deepDiveEpic(`E-${i}`));
    const range = { from: "2023-02-01", to: "2026-01-31", asOfDay: "2026-01-31" };
    renderWithProviders(<DeepDiveMatrix report={deepDiveReport({ range, epics })} layers={ALL} />);
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    expect(screen.getByText(/710 rows by 36 columns is 25560 cells, too many to draw/)).toBeInTheDocument();
  });

  test("the table of figures is guarded by cells × lines: a grid that fits can still be too big for it", () => {
    // 10 cells fit a ceiling of 30, but every layer on is four table lines per row (pv, execution, EV, cost)
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={30} />);
    expect(screen.getByRole("grid")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show figures per visible column" })).not.toBeInTheDocument();
    expect(screen.getByText(/would hold 40 cells, too many to draw/)).toBeInTheDocument();
  });
});

test("the legend names the three layers, the shared scale, the done marker, the window and the hatching", () => {
  renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
  const legend = screen.getByRole("group", { name: "Legend" });
  expect(legend).toHaveTextContent("Plan (PV): full-width blue bar");
  expect(legend).toHaveTextContent("Execution: two-thirds-width teal bar");
  expect(legend).toHaveTextContent("Cost (AC): one-third-width gray bar");
  expect(legend).toHaveTextContent("Plan and cost share one man-day scale; execution has its own, in task-days.");
  expect(legend).toHaveTextContent("A task finished on this day (earned value)");
  expect(legend).toHaveTextContent("The epic's planned window (dashed outline)");
  expect(legend).toHaveTextContent("Hatched: non-working days");
});

test("the summary table gives each epic and task its in-range and whole-life figures, the plan basis, and each epic's window and budget", () => {
  renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
  const table = screen.getByRole("table", { name: "Totals per epic and task" });
  const task = within(table).getByRole("rowheader", { name: "FLO-1 First" }).closest("tr") as HTMLElement;
  // in range / whole life: PV 3 / 3, execution 0.5 / 0.5, EV 3 / 3, cost 2 / 2, then basis 3, the source, no reason, no window, no budget
  expect(within(task).getAllByRole("cell").map((td) => td.textContent)).toEqual(["3", "3", "0.5", "0.5", "3", "3", "2", "2", "3", "Earliest sprint", "", "", ""]);
  const second = within(table).getByRole("rowheader", { name: "FLO-2 Second" }).closest("tr") as HTMLElement;
  const cells = within(second).getAllByRole("cell").map((td) => td.textContent);
  expect(cells.slice(-5)).toEqual(["—", "None", "No estimate", "", ""]);
  const alpha = within(table).getByRole("rowheader", { name: "E-1 Alpha" }).closest("tr") as HTMLElement;
  expect(within(alpha).getAllByRole("cell").map((td) => td.textContent).slice(-2)).toEqual(["2026-08-30 to 2026-09-04", "5"]);
  expect(within(table).getByRole("rowheader", { name: "(on the epic)" })).toBeInTheDocument();
  expect(within(table).getByRole("rowheader", { name: "All tasks" })).toBeInTheDocument();
});

test("whole-life totals still count what falls outside the shown range", () => {
  const report = deepDiveReport({
    ...MONTH_CROSSING,
    epics: [deepDiveEpic("E-1", { summary: "Alpha" })],
    tasks: [
      deepDiveTask("FLO-1", {
        epicKey: "E-1",
        pv: [{ d: 2, md: 1 }],
        totals: { pvMd: 4, execTaskDays: 0, evMd: 0, costMd: 0 },
      }),
    ],
  });
  renderWithProviders(<DeepDiveMatrix report={report} layers={ALL} />);
  const row = within(screen.getByRole("table", { name: "Totals per epic and task" })).getByRole("rowheader", { name: "E-1 Alpha" }).closest("tr") as HTMLElement;
  expect(within(row).getAllByRole("cell")[0]).toHaveTextContent("1");
  expect(within(row).getAllByRole("cell")[1]).toHaveTextContent("4");
});

test("the visible figures sit behind a disclosure that names them, and follow the drill and the layers", async () => {
  const user = userEvent.setup();
  renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={{ pv: true, exec: false, cost: true }} />);
  expect(screen.queryByRole("table", { name: "Figures per visible column and row" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Show daily figures" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Show figures per visible column" }));
  expect(screen.getByRole("button", { name: "Hide figures per visible column" })).toHaveAttribute("aria-expanded", "true");
  const table = screen.getByRole("table", { name: "Figures per visible column and row" });
  expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["Epic / task", "2026-08", "2026-09"]);
  const pv = within(table).getByRole("rowheader", { name: "E-1 Alpha: Plan (PV)" }).closest("tr") as HTMLElement;
  expect(within(pv).getAllByRole("cell").map((td) => td.textContent)).toEqual(["1", "2"]);
  const cost = within(table).getByRole("rowheader", { name: "E-1 Alpha: Cost (AC)" }).closest("tr") as HTMLElement;
  expect(within(cost).getAllByRole("cell").map((td) => td.textContent)).toEqual(["1", "2"]);
  expect(within(table).queryByRole("rowheader", { name: /Execution/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
  expect(within(table).getByRole("rowheader", { name: "FLO-1 First: Plan (PV)" })).toBeInTheDocument();
});

test("a selection with no epics says so instead of drawing an empty grid", () => {
  renderWithProviders(<DeepDiveMatrix report={deepDiveReport()} layers={ALL} />);
  expect(screen.getByText("There is no work to show for this selection.")).toBeInTheDocument();
  expect(screen.queryByRole("grid")).not.toBeInTheDocument();
});

test.each(["light", "dark"] as const)("renders in the %s scheme with the layer colours from the shared vocabulary", (scheme) => {
  render(
    <MantineProvider env="test" theme={theme} forceColorScheme={scheme}>
      <MemoryRouter>
        <DeepDiveMatrix report={sampleReport()} layers={ALL} />
      </MemoryRouter>
    </MantineProvider>,
  );
  const style = gridOf().getAttribute("style") ?? "";
  expect(style).toContain("--dd-pv: var(--mantine-color-flow-6)");
  expect(style).toContain("--dd-exec: var(--mantine-color-teal-8)");
  expect(style).toContain("--dd-cost: var(--mantine-color-gray-6)");
  expect(style).toContain("--dd-window: var(--mantine-color-flow-6)");
  expect(style).toContain("--dd-hatch: var(--mantine-color-gray-6)");
  expect(style).toContain("--dd-fill: 32%");
  expect(bars("pv").length).toBeGreaterThan(0);
  expect(document.documentElement).toHaveAttribute("data-mantine-color-scheme", scheme);
});

test("a hover that moves onto another cell does not wait forever: the tooltip keeps working after the delay", async () => {
  renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
  fireEvent.mouseOver(alphaCell("2026-08"));
  fireEvent.mouseOver(alphaCell("2026-09"));
  await waitFor(() => expect(screen.getByRole("tooltip")).toHaveTextContent("E-1 Alpha, 2026-09"));
});

describe("DeepDiveMatrix: follow-up behaviours", () => {
  test("an open month or week header is a column header (scope col), not a column group", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    expect(screen.getByRole("button", { name: "Collapse 2026-09" }).closest("th")).toHaveAttribute("scope", "col");
    for (const th of within(gridOf()).getAllByRole("columnheader")) expect(th).toHaveAttribute("scope", "col");
  });

  test("closing a month also forgets the weeks opened inside it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    await user.click(expandMonth("2026-09"));
    await user.click(expandWeek("2026-W37"));
    expect(screen.getByRole("button", { name: "Collapse 2026-W37" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collapse 2026-09" }));
    await user.click(expandMonth("2026-09"));
    expect(expandWeek("2026-W37")).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Collapse 2026-W37" })).not.toBeInTheDocument();
    expect(expandWeek("2026-W36")).toHaveFocus(); // the first child in order
  });

  test("opening and closing an epic is announced too, and every announcement is a fresh node so a repeat is read again", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    const status = screen.getAllByRole("status")[0];
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    expect(status).toHaveTextContent("Opened E-1 Alpha");
    await user.click(screen.getByRole("button", { name: "Collapse all rows" }));
    const first = status.firstElementChild;
    expect(status).toHaveTextContent("All rows collapsed");
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    expect(status).toHaveTextContent("Closed E-1 Alpha");
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    await user.click(screen.getByRole("button", { name: "Collapse all rows" }));
    expect(status).toHaveTextContent("All rows collapsed");
    expect(status.firstElementChild).not.toBe(first);
    expect(first?.isConnected).toBe(false);
  });

  test("a drill that trips the size guard moves focus into the notice, which announces the true state", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={12} />);
    await user.click(screen.getByRole("button", { name: "E-1 Alpha" }));
    const notice = screen.getByText(/Collapse some columns or epics/).closest('[role="status"]') as HTMLElement;
    expect(notice).not.toBeNull();
    expect(within(notice).getByRole("button", { name: "Collapse all rows" })).toHaveFocus();
    await user.click(within(notice).getByRole("button", { name: "Collapse all rows" }));
    expect(screen.getByRole("grid")).toBeInTheDocument();
    assertFocusedIsTabStop();
  });

  test("a column drill that trips the size guard also lands on the notice", async () => {
    const user = userEvent.setup();
    // 5 rows × 2 months = 10 cells fit; 5 × 3 = 15 (month, two weeks) do not
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} maxCells={12} />);
    await user.click(expandMonth("2026-09"));
    const notice = screen.getByText(/Collapse some columns or epics/).closest('[role="status"]') as HTMLElement;
    expect(within(notice).getByRole("button", { name: "Collapse all columns" })).toHaveFocus();
  });

  test("a card the keyboard opened stays while the pointer comes and goes, until focus leaves or Escape", async () => {
    vi.useFakeTimers();
    renderWithProviders(<DeepDiveMatrix report={sampleReport()} layers={ALL} />);
    focusOn(alphaCell("2026-09"));
    expect(screen.getByRole("tooltip")).toBeInTheDocument();
    fireEvent.mouseOver(within(rowOf(/E-3 Gamma/)).getAllByRole("gridcell")[0]); // pointer elsewhere...
    fireEvent.mouseLeave(gridOf());
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(screen.getByRole("tooltip")).toBeInTheDocument(); // ...focus is still on the cell, so the card stays
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
});

describe("the stylesheet's accessibility pins", () => {
  const css = readFileSync(join(process.cwd(), "src", "theme.module.css"), "utf8");
  const block = (selector: string) => {
    const start = css.indexOf(`${selector} {`);
    expect(start, selector).toBeGreaterThanOrEqual(0);
    return css.slice(start, css.indexOf("}", start));
  };

  test("a focused cell is scrolled clear of the sticky first column and the sticky header rows (WCAG 2.4.11)", () => {
    expect(block(".ddTd")).toContain("scroll-margin-left: var(--dd-item-width)");
    expect(block(".ddTd")).toContain("scroll-margin-top: calc(var(--dd-head-row) * var(--dd-head-rows, 1))");
    expect(block(".ddColHead")).toContain("scroll-margin-left: var(--dd-item-width)");
    expect(block(".ddRowHeader")).toContain("width: var(--dd-item-width)");
  });

  test("the row toggles and the column toggles scroll clear of the sticky chrome too", () => {
    const rows = block(".ddRowHeader .ddToggle,\n.ddRowHeader .ddItem");
    expect(rows).toContain("scroll-margin-top: calc(var(--dd-head-row) * var(--dd-head-rows, 1))");
    expect(block(".ddColHead .ddToggle")).toContain("scroll-margin-left: var(--dd-item-width)");
  });

  test("the planned window's dashed outline has a 1px surface ring on both sides of each drawn edge", () => {
    const outline = block(".ddOutline");
    expect(outline).toContain("border-top: 2px dashed var(--dd-window)");
    expect(outline).toContain("0 -1px 0 0 var(--mantine-color-body)");
    expect(outline).toContain("inset 0 1px 0 0 var(--mantine-color-body)");
    expect(block(".ddOutlineStart")).toContain("inset 1px 0 0 0 var(--mantine-color-body)");
    expect(block(".ddOutlineEnd")).toContain("inset -1px 0 0 0 var(--mantine-color-body)");
  });

  test("every bar carries a 1px surface halo above its 2px edge, and the diamond a surface text halo", () => {
    expect(block(".ddBar")).toContain("border-top: 2px solid var(--dd-color)");
    expect(block(".ddBar")).toContain("box-shadow: 0 -1px 0 0 var(--mantine-color-body)");
    expect(block(".ddDoneMark")).toContain("text-shadow");
  });

  test("the hatch is the neutral gray variable, in both schemes (no translucent black/white)", () => {
    expect(block(".ddHatched")).toContain("var(--dd-hatch)");
    expect(block(".ddHatched")).not.toContain("rgba");
  });

  test("the cell padding reset targets td only, so the header and row-header paddings apply", () => {
    expect(css).toContain(".table.ddTable td {\n  padding: 0;");
    expect(css).not.toMatch(/\.table\.ddTable th/);
    expect(block(".ddRowHeader")).toContain("padding: 2px 8px");
    expect(block(".ddColHead")).toContain("padding: 4px 2px");
  });

  test("the tooltip sits on Mantine's popover layer and scrolls inside itself", () => {
    expect(block(".ddTooltip")).toContain("z-index: var(--mantine-z-index-popover)");
    expect(block(".ddTooltip")).toContain("max-height: calc(100vh - 16px)");
    expect(block(".ddTooltip")).toContain("overflow: auto");
  });
});
