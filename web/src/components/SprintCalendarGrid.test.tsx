import { useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, fireEvent, screen, within } from "@testing-library/react";
import SprintCalendarGrid from "./SprintCalendarGrid";
import { calendarCapacityMd, type CalendarDraft } from "../utils/capacity";
import { renderWithProviders } from "../test/render";

const DAYS = ["2026-03-06", "2026-03-07", "2026-03-08", "2026-03-09"];

function person(accountId: string, displayName: string, coefficient: number, member = true) {
  return {
    accountId,
    displayName,
    member,
    enabled: true,
    coefficient,
    cells: DAYS.map((day) => ({ day, availability: 1 })),
  };
}

// Fri + Mon counted, the weekend off (the server decides that default); Ada at 1, Bob at 0.8 and no longer a member.
const DRAFT: CalendarDraft = {
  teamCoefficient: 1,
  days: DAYS.map((day, i) => ({ day, enabled: i === 0 || i === 3, holiday: null, claimedBy: null })),
  people: [person("ada", "Ada Lovelace", 1), person("bob", "Bob Builder", 0.8, false)],
};

function Harness({ canEdit = true, initial = DRAFT }: { canEdit?: boolean; initial?: CalendarDraft }) {
  const [draft, setDraft] = useState(initial);
  return (
    <>
      <SprintCalendarGrid draft={draft} canEdit={canEdit} onChange={setDraft} />
      <output aria-label="total">{calendarCapacityMd(draft)}</output>
    </>
  );
}

const total = () => screen.getByLabelText("total").textContent;
const cell = (name: string, day: string) => screen.getByRole("textbox", { name: `Availability of ${name} on ${day}` }) as HTMLInputElement;

describe("SprintCalendarGrid", () => {
  test("renders a real table: a scoped header per day and person, the off days shaded, the footer per day", () => {
    renderWithProviders(<Harness />);
    const table = screen.getByRole("table", { name: "Availability of each person on each day of the sprint" });
    expect(screen.getByRole("region", { name: "Availability of each person on each day of the sprint" })).toContainElement(table);
    expect(within(table).getAllByRole("columnheader")).toHaveLength(2 + DAYS.length);
    expect(within(table).getByRole("rowheader", { name: "Ada Lovelace" })).toBeInTheDocument();
    expect(within(table).getByRole("rowheader", { name: "Bob Builder" })).toBeInTheDocument();
    // The weekend columns come from the data (enabled: false), not from the weekday.
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-06" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-07" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-08" })).not.toBeChecked();
    // Counted per day: the counted people's cells (1 + 1), before any coefficient; a dash on an off day.
    const footer = within(table).getByRole("rowheader", { name: "Counted per day" }).closest("tr") as HTMLElement;
    expect(within(footer).getAllByRole("cell").map((c) => c.textContent)).toEqual(["", "2", "–", "–", "2"]);
    expect(screen.getAllByText("not a member")).toHaveLength(1);
    expect(total()).toBe("3.6");
  });

  test("every control has an accessible name and the off days' cells are disabled", () => {
    renderWithProviders(<Harness />);
    expect(screen.getByRole("checkbox", { name: "Count Ada Lovelace" })).toBeChecked();
    expect(screen.getByRole("textbox", { name: "Coefficient of Bob Builder" })).toHaveValue("0.8");
    expect(cell("Ada Lovelace", "2026-03-06")).toBeEnabled();
    expect(cell("Ada Lovelace", "2026-03-07")).toBeDisabled();
    expect(cell("Bob Builder", "2026-03-08")).toBeDisabled();
  });

  test("editing a cell changes the live total", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    expect(total()).toBe("3.6");
    // Ada's Friday at 0.5: (0.5 + 1) + 0.8 x (1 + 1) = 3.1.
    const friday = cell("Ada Lovelace", "2026-03-06");
    await user.clear(friday);
    await user.type(friday, "0.5");
    expect(friday).toHaveValue("0.5");
    expect(total()).toBe("3.1");
  });

  test("typing past a field's range holds the committed value to it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const friday = cell("Ada Lovelace", "2026-03-06");
    await user.clear(friday);
    await user.type(friday, "7");
    // Capped at one man-day: the total is unchanged from the default.
    expect(total()).toBe("3.6");
    await user.tab();
    expect(friday).toHaveValue("1");
  });

  test("turning a day column on counts it and enables its cells; turning one off disables them", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
    expect(cell("Ada Lovelace", "2026-03-07")).toBeEnabled();
    expect(total()).toBe("5.4");
    await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-06" }));
    expect(cell("Ada Lovelace", "2026-03-06")).toBeDisabled();
    expect(cell("Bob Builder", "2026-03-06")).toBeDisabled();
    // Saturday and Monday are left: 2 x 1.8.
    expect(total()).toBe("3.6");
  });

  test("turning a person off disables their row and drops it from the total", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("checkbox", { name: "Count Bob Builder" }));
    expect(screen.getByRole("textbox", { name: "Coefficient of Bob Builder" })).toBeDisabled();
    expect(cell("Bob Builder", "2026-03-06")).toBeDisabled();
    expect(cell("Ada Lovelace", "2026-03-06")).toBeEnabled();
    expect(total()).toBe("2");
  });

  test("a coefficient scales the person's row", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const coefficient = screen.getByRole("textbox", { name: "Coefficient of Ada Lovelace" });
    await user.clear(coefficient);
    await user.type(coefficient, "1.5");
    // 1.5 x 2 + 0.8 x 2 = 4.6.
    expect(total()).toBe("4.6");
  });

  test("read-only with withheld coefficients: no coefficient column, the rest of the grid stays", () => {
    const withheld: CalendarDraft = { ...DRAFT, people: DRAFT.people.map((p) => ({ ...p, coefficient: null })) };
    renderWithProviders(<Harness canEdit={false} initial={withheld} />);
    expect(screen.queryByRole("columnheader", { name: "Coefficient" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Coefficient of/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole("columnheader")).toHaveLength(1 + DAYS.length);
    expect(screen.getByRole("checkbox", { name: "Count Ada Lovelace" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-07" })).not.toBeChecked();
    expect(cell("Ada Lovelace", "2026-03-06")).toHaveValue("1");
    // The footer is plain cell sums, so it needs no coefficient either.
    const footer = screen.getByRole("rowheader", { name: "Counted per day" }).closest("tr") as HTMLElement;
    expect(within(footer).getAllByRole("cell").map((c) => c.textContent)).toEqual(["2", "–", "–", "2"]);
  });

  test("two edits committed in the same tick both survive (every edit is a function of the previous draft)", () => {
    renderWithProviders(<Harness />);
    // One act = one batch: with a closure over the rendered draft the second commit would overwrite the first.
    act(() => {
      fireEvent.change(cell("Ada Lovelace", "2026-03-06"), { target: { value: "0.5" } });
      fireEvent.change(cell("Bob Builder", "2026-03-09"), { target: { value: "0.25" } });
      fireEvent.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
    });
    // Saturday on: Ada 1 x (0.5 + 1 + 1) + Bob 0.8 x (1 + 1 + 0.25) = 2.5 + 1.8.
    expect(total()).toBe("4.3");
    expect(cell("Ada Lovelace", "2026-03-06")).toHaveValue("0.5");
    expect(cell("Bob Builder", "2026-03-09")).toHaveValue("0.25");
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-07" })).toBeChecked();
  });

  test("a comma is a decimal separator", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const friday = cell("Ada Lovelace", "2026-03-06");
    await user.clear(friday);
    await user.type(friday, "0,5");
    expect(friday).toHaveValue("0.5");
    expect(total()).toBe("3.1");
  });

  test("a negative number cannot be typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const friday = cell("Ada Lovelace", "2026-03-06");
    await user.clear(friday);
    await user.type(friday, "-1");
    expect(friday).toHaveValue("1");
  });

  test("the weekday initial is decoration, hidden from the accessibility tree", () => {
    const { container } = renderWithProviders(<Harness />);
    const initial = container.querySelector('th[title="2026-03-06"] span[aria-hidden="true"]');
    expect(initial).toHaveTextContent(/^\S$/);
    // The checkbox still names the day in full.
    expect(screen.getByRole("checkbox", { name: "Count 2026-03-06" })).toBeInTheDocument();
  });

  test("an editor sees the coefficient column", () => {
    renderWithProviders(<Harness />);
    expect(screen.getByRole("columnheader", { name: "Coefficient" })).toBeInTheDocument();
  });

  test("read-only: every control is disabled and nothing changes", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness canEdit={false} />);
    for (const box of screen.getAllByRole("checkbox")) expect(box).toBeDisabled();
    for (const input of screen.getAllByRole("textbox")) expect(input).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "Count 2026-03-07" }));
    expect(total()).toBe("3.6");
  });
  describe("a public holiday column", () => {
    // Monday 2026-03-09 is a holiday: off whatever was stored (the server projects it off), named, and locked.
    const HOLIDAY: CalendarDraft = {
      ...DRAFT,
      days: DAYS.map((day, i) => ({ day, enabled: i === 0, holiday: i === 3 ? "Test Holiday" : null, claimedBy: null })),
    };

    test("is unticked and disabled, named in the checkbox label and as the header tooltip, and its cells are disabled", () => {
      const { container } = renderWithProviders(<Harness initial={HOLIDAY} />);
      const box = screen.getByRole("checkbox", { name: "2026-03-09 is a public holiday: Test Holiday" });
      expect(box).not.toBeChecked();
      expect(box).toBeDisabled();
      expect(container.querySelector('th[title="Public holiday: Test Holiday"]')).toBeInTheDocument();
      expect(cell("Ada Lovelace", "2026-03-09")).toBeDisabled();
      // The other days keep their ordinary label and stay editable.
      expect(screen.getByRole("checkbox", { name: "Count 2026-03-06" })).toBeEnabled();
      expect(screen.queryByRole("checkbox", { name: "Count 2026-03-09" })).not.toBeInTheDocument();
    });

    test("never counts, even when the draft says enabled, and cannot be switched on", async () => {
      const user = userEvent.setup();
      const stored: CalendarDraft = { ...HOLIDAY, days: HOLIDAY.days.map((d) => (d.holiday ? { ...d, enabled: true } : d)) };
      renderWithProviders(<Harness initial={stored} />);
      expect(screen.getByRole("checkbox", { name: "2026-03-09 is a public holiday: Test Holiday" })).not.toBeChecked();
      // Only Friday counts: Ada 1 + Bob 0.8.
      expect(total()).toBe("1.8");
      expect(cell("Ada Lovelace", "2026-03-09")).toBeDisabled();
      await user.click(screen.getByRole("checkbox", { name: "2026-03-09 is a public holiday: Test Holiday" }));
      expect(total()).toBe("1.8");
    });

    test("shows a legend entry for holidays only when the sprint has one", () => {
      const { unmount } = renderWithProviders(<Harness initial={HOLIDAY} />);
      expect(screen.getByText("Public holiday (always not counted)")).toBeInTheDocument();
      unmount();
      renderWithProviders(<Harness />);
      expect(screen.queryByText("Public holiday (always not counted)")).not.toBeInTheDocument();
    });
  });

  describe("a day an earlier sprint holds", () => {
    // Monday 2026-03-09 is counted in "Sprint 6": off whatever was stored (the server projects it off), named, and locked.
    const claimedBy = { sprintId: 7, sprintName: "Sprint 6" };
    const CLAIMED: CalendarDraft = {
      ...DRAFT,
      days: DAYS.map((day, i) => ({ day, enabled: i === 0, holiday: null, claimedBy: i === 3 ? claimedBy : null })),
    };

    test("is unticked and disabled, named in the checkbox label and as the header tooltip, and its cells are disabled", () => {
      const { container } = renderWithProviders(<Harness initial={CLAIMED} />);
      const box = screen.getByRole("checkbox", { name: "2026-03-09 is counted in Sprint 6" });
      expect(box).not.toBeChecked();
      expect(box).toBeDisabled();
      expect(container.querySelector('th[title="Counted in Sprint 6"]')).toBeInTheDocument();
      expect(cell("Ada Lovelace", "2026-03-09")).toBeDisabled();
      // The other days keep their ordinary label and stay editable.
      expect(screen.getByRole("checkbox", { name: "Count 2026-03-06" })).toBeEnabled();
      expect(screen.queryByRole("checkbox", { name: "Count 2026-03-09" })).not.toBeInTheDocument();
    });

    test("never counts, even when the draft says enabled, and cannot be switched on", async () => {
      const user = userEvent.setup();
      const stored: CalendarDraft = { ...CLAIMED, days: CLAIMED.days.map((d) => (d.claimedBy ? { ...d, enabled: true } : d)) };
      renderWithProviders(<Harness initial={stored} />);
      const box = screen.getByRole("checkbox", { name: "2026-03-09 is counted in Sprint 6" });
      expect(box).not.toBeChecked();
      // Only Friday counts: Ada 1 + Bob 0.8.
      expect(total()).toBe("1.8");
      await user.click(box);
      expect(total()).toBe("1.8");
    });

    test("shows a legend entry only when a day is claimed", () => {
      const { unmount } = renderWithProviders(<Harness initial={CLAIMED} />);
      expect(screen.getByText("Counted in an earlier sprint (not counted here)")).toBeInTheDocument();
      expect(screen.queryByText("Public holiday (always not counted)")).not.toBeInTheDocument();
      unmount();
      renderWithProviders(<Harness />);
      expect(screen.queryByText("Counted in an earlier sprint (not counted here)")).not.toBeInTheDocument();
    });

    test("a holiday that is also claimed reads as the holiday", () => {
      const both: CalendarDraft = { ...CLAIMED, days: CLAIMED.days.map((d) => (d.claimedBy ? { ...d, holiday: "Test Holiday" } : d)) };
      renderWithProviders(<Harness initial={both} />);
      expect(screen.getByRole("checkbox", { name: "2026-03-09 is a public holiday: Test Holiday" })).toBeDisabled();
      expect(screen.queryByText("Counted in an earlier sprint (not counted here)")).not.toBeInTheDocument();
    });
  });
});
