import { describe, expect, test } from "vitest";
import ReportFilterMultiSelect from "./ReportFilterMultiSelect";
import { renderWithProviders, screen } from "../test/render";

const data = Array.from({ length: 10 }, (_, i) => ({ value: `acc-${i}`, label: `Person ${i}` }));

function renderPicked(count: number) {
  return renderWithProviders(
    <ReportFilterMultiSelect
      label="Member"
      placeholder="Whole team"
      data={data}
      value={data.slice(0, count).map((o) => o.value)}
      onChange={() => undefined}
    />,
  );
}

describe("ReportFilterMultiSelect", () => {
  test("says nothing about the cap until the cap is reached, then says it in words", () => {
    const first = renderPicked(7);
    expect(screen.queryByText("Up to 8 people")).not.toBeInTheDocument();
    first.unmount();
    renderPicked(8);
    expect(screen.getByText("Up to 8 people")).toBeInTheDocument();
  });

  test("lets the search input shrink below Mantine's 100px floor so the first pill and the input share a line", () => {
    renderPicked(1);
    const input = screen.getByRole("combobox", { name: "Member" });
    expect(input).toHaveStyle({ minWidth: "48px" });
    expect(screen.getByText("Person 0")).toBeInTheDocument();
  });
});
