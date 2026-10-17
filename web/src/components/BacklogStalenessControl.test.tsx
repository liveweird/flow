import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import BacklogStalenessControl from "./BacklogStalenessControl";
import { renderWithProviders, screen } from "../test/render";

const slider = () => screen.getByRole("slider", { name: "Last change within" });

describe("BacklogStalenessControl", () => {
  test("shows the default 90 days, its steps and the help text that describes the control's group", () => {
    renderWithProviders(<BacklogStalenessControl filter={{}} onChange={vi.fn()} />);
    expect(slider()).toHaveAttribute("aria-valuetext", "90 days (default)");
    // Index-based: stop 4 of 0..7.
    expect(slider()).toHaveAttribute("aria-valuenow", "4");
    expect(screen.getByText("90 days (default)")).toBeInTheDocument();
    for (const mark of ["7", "14", "30", "60", "90", "180", "365", "No limit"]) expect(screen.getByText(mark)).toBeInTheDocument();
    const help = screen.getByText(/^Leaves out items nobody touched/);
    expect(screen.getByRole("group", { name: "Last change within" })).toHaveAccessibleDescription(help.textContent!);
  });

  test("reads the filter: a step, no limit (0) and a stray value snapped to the nearest step", () => {
    const { rerender } = renderWithProviders(<BacklogStalenessControl filter={{ touchedWithinDays: 30 }} onChange={vi.fn()} />);
    expect(slider()).toHaveAttribute("aria-valuetext", "30 days");
    rerender(<BacklogStalenessControl filter={{ touchedWithinDays: 0 }} onChange={vi.fn()} />);
    expect(slider()).toHaveAttribute("aria-valuetext", "No limit");
    expect(slider()).toHaveAttribute("aria-valuenow", "7");
    rerender(<BacklogStalenessControl filter={{ touchedWithinDays: 100 }} onChange={vi.fn()} />);
    expect(slider()).toHaveAttribute("aria-valuetext", "90 days (default)");
  });

  test("the keys commit once each: End is no limit (0), Home the first step, the default is stored as absent, other filters stay", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<BacklogStalenessControl filter={{ teamId: 2 }} onChange={onChange} />);
    slider().focus();
    await user.keyboard("{End}");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenLastCalledWith({ teamId: 2, touchedWithinDays: 0 });
    await user.keyboard("{Home}");
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onChange).toHaveBeenLastCalledWith({ teamId: 2, touchedWithinDays: 7 });
    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenLastCalledWith({ teamId: 2, touchedWithinDays: 14 });
  });

  test("moving to the default step removes the param; the thumb and its label follow while the filter has not changed yet", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<BacklogStalenessControl filter={{ teamId: 2, touchedWithinDays: 180 }} onChange={onChange} />);
    slider().focus();
    await user.keyboard("{ArrowLeft}");
    expect(onChange).toHaveBeenLastCalledWith({ teamId: 2 });
    // The parent has not re-rendered with the new filter: the thumb still shows where it was put.
    expect(slider()).toHaveAttribute("aria-valuetext", "90 days (default)");
  });

  test("a key that changes nothing (End on No limit) writes nothing", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<BacklogStalenessControl filter={{ touchedWithinDays: 0 }} onChange={onChange} />);
    slider().focus();
    await user.keyboard("{End}");
    expect(onChange).not.toHaveBeenCalled();
  });
});
