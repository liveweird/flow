import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import ReportTabs from "./ReportTabs";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { renderWithProviders, screen } from "../test/render";

function Where() {
  const { pathname, search } = useLocation();
  return <output data-testid="where">{pathname + search}</output>;
}

describe("ReportTabs", () => {
  test("a group with one report renders no tabs", () => {
    renderWithProviders(<ReportTabs tabs={DELIVERY_TABS} />, { route: "/reports/velocity" });
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  test("switching reports keeps the filter query", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <ReportTabs
          tabs={[
            { to: "/reports/velocity", label: "reports.tabs.velocity" },
            { to: "/reports/other", label: "reports.groups.team" },
          ]}
        />
        <Where />
      </>,
      { route: "/reports/velocity?teamId=2&lastSprints=3" },
    );
    expect(screen.getByRole("tab", { name: "Velocity" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("tab", { name: "Team" }));
    expect(screen.getByTestId("where").textContent).toBe("/reports/other?teamId=2&lastSprints=3");
  });
});
