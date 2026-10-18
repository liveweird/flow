import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import DrillFigure from "./DrillFigure";
import HomeHiddenTable from "./HomeHiddenTable";

interface Row {
  team: string;
  first: number;
  second: number;
}

const ROWS: Row[] = [
  { team: "Alpha", first: 3, second: 5 },
  { team: "Beta", first: 0, second: 2 },
  { team: "Gamma", first: 4, second: 6 },
];

describe("HomeHiddenTable", () => {
  function table() {
    return renderWithProviders(
      <>
        <button>before</button>
        <HomeHiddenTable
          label="Median estimate (MD), as a table"
          rows={ROWS}
          rowKey={(row) => row.team}
          columns={[
            { key: "team", header: "Team", render: (row) => row.team },
            {
              key: "first",
              header: "First",
              render: (row) => (
                <DrillFigure label={`${row.first} — First: ${row.team}`} count={row.first} onOpen={() => undefined}>
                  {row.first}
                </DrillFigure>
              ),
            },
            {
              key: "second",
              header: "Second",
              render: (row) => (
                <DrillFigure label={`${row.second} — Second: ${row.team}`} count={row.second} onOpen={() => undefined}>
                  {row.second}
                </DrillFigure>
              ),
            },
          ]}
        />
        <button>after</button>
      </>,
    );
  }

  test("is a named table in the accessibility tree", () => {
    table();
    expect(screen.getByRole("table", { name: "Median estimate (MD), as a table" })).toBeInTheDocument();
  });

  // The visual idiom (clipped, never display:none; revealed on :focus-within) lives in theme.module.css, which happy-dom does not apply: the
  // e2e suite pins the reveal. What a unit can pin is the keyboard contract of the buttons inside.
  test("ONE Tab stop for the whole table: Tab enters on the first figure and the next Tab leaves it", async () => {
    const user = userEvent.setup();
    table();
    await user.tab();
    expect(screen.getByRole("button", { name: "before" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("button", { name: "3 — First: Alpha" })).toHaveFocus();
    // The other figures are reachable by arrow keys only.
    expect(screen.getByRole("button", { name: "5 — Second: Alpha" })).toHaveAttribute("tabindex", "-1");
    await user.tab();
    expect(screen.getByRole("button", { name: "after" })).toHaveFocus();
  });

  test("arrow keys move along a row and down a column (skipping a row with no figure there), Home/End go to the row's ends", async () => {
    const user = userEvent.setup();
    table();
    await user.tab();
    await user.tab();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("button", { name: "5 — Second: Alpha" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("button", { name: "2 — Second: Beta" })).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    // Beta's first figure is zero: plain text, not a button, so there is nowhere to go.
    expect(screen.getByRole("button", { name: "2 — Second: Beta" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("button", { name: "6 — Second: Gamma" })).toHaveFocus();
    await user.keyboard("{Home}");
    expect(screen.getByRole("button", { name: "4 — First: Gamma" })).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("button", { name: "6 — Second: Gamma" })).toHaveFocus();
    await user.keyboard("{ArrowUp}");
    expect(screen.getByRole("button", { name: "2 — Second: Beta" })).toHaveFocus();
    await user.keyboard("{Control>}{Home}{/Control}");
    expect(screen.getByRole("button", { name: "3 — First: Alpha" })).toHaveFocus();
    await user.keyboard("{Control>}{End}{/Control}");
    expect(screen.getByRole("button", { name: "6 — Second: Gamma" })).toHaveFocus();
  });

  test("the tab stop is the last figure focused, so Tab back in returns to where the reader was", async () => {
    const user = userEvent.setup();
    table();
    await user.tab();
    await user.tab();
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(screen.getByRole("button", { name: "4 — First: Gamma" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("button", { name: "after" })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole("button", { name: "4 — First: Gamma" })).toHaveFocus();
  });

  test("other keys are left alone", async () => {
    const user = userEvent.setup();
    table();
    await user.tab();
    await user.tab();
    await user.keyboard("{ArrowRight}{ArrowRight}");
    expect(screen.getByRole("button", { name: "5 — Second: Alpha" })).toHaveFocus();
    await user.keyboard("a");
    expect(screen.getByRole("button", { name: "5 — Second: Alpha" })).toHaveFocus();
  });
});
