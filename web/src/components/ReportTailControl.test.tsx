import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ReportTailControl from "./ReportTailControl";
import { renderWithProviders, screen } from "../test/render";

describe("ReportTailControl", () => {
  test("shows the default 5% and its help text, which describes the control's group", () => {
    renderWithProviders(<ReportTailControl filter={{}} onChange={vi.fn()} />);
    expect(screen.getByRole("combobox", { name: "Long tail" })).toHaveValue("5%");
    const help = screen.getByText(
      "Values above the (100 − N)th percentile, roughly the top N%, are shown as one final bucket (≥ X), so a few outliers don't squash the rest. 0 shows the plain histogram.",
    );
    expect(screen.getByRole("group", { name: "Long tail" })).toHaveAccessibleDescription(help.textContent!);
  });

  test("a pick writes tailPercent; going back to the default 5 removes it; other filters stay", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<ReportTailControl filter={{ teamId: 2 }} onChange={onChange} />);
    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    for (const percent of ["0%", "1%", "2%", "5%", "10%", "15%", "20%", "25%"]) {
      expect(screen.getByRole("option", { name: percent })).toBeInTheDocument();
    }
    await user.click(screen.getByRole("option", { name: "10%" }));
    expect(onChange).toHaveBeenLastCalledWith({ teamId: 2, tailPercent: 10 });
  });

  test("0 is a real choice (the plain histogram), and returning to 5 clears the param", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { rerender } = renderWithProviders(<ReportTailControl filter={{}} onChange={onChange} />);
    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    await user.click(screen.getByRole("option", { name: "0%" }));
    expect(onChange).toHaveBeenLastCalledWith({ tailPercent: 0 });
    rerender(<ReportTailControl filter={{ tailPercent: 0 }} onChange={onChange} />);
    expect(screen.getByRole("combobox", { name: "Long tail" })).toHaveValue("0%");
    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    await user.click(screen.getByRole("option", { name: "5%" }));
    expect(onChange).toHaveBeenLastCalledWith({});
  });

  test("a hand-edited value in range that is not a preset still shows", () => {
    renderWithProviders(<ReportTailControl filter={{ tailPercent: 7 }} onChange={vi.fn()} />);
    expect(screen.getByRole("combobox", { name: "Long tail" })).toHaveValue("7%");
  });
});
