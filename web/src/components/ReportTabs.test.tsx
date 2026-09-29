import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import ReportTabs from "./ReportTabs";
import { DELIVERY_TABS, ESTIMATION_TABS } from "../utils/reportLinks";
import { renderWithProviders, screen } from "../test/render";

function Where() {
  const { pathname, search } = useLocation();
  return <output data-testid="where">{pathname + search}</output>;
}

describe("ReportTabs", () => {
  test("a group with one report renders no tabs", () => {
    renderWithProviders(<ReportTabs tabs={DELIVERY_TABS.slice(0, 1)} />, { route: "/reports/velocity" });
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  test("the Delivery group offers velocity, throughput and sprint consistency, with the current route selected", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <ReportTabs tabs={DELIVERY_TABS} />
        <Where />
      </>,
      { route: "/reports/throughput?teamId=2" },
    );
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Velocity", "Throughput", "Sprint consistency"]);
    expect(screen.getByRole("tab", { name: "Throughput" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("tab", { name: "Sprint consistency" }));
    expect(screen.getByTestId("where").textContent).toBe("/reports/sprint-consistency?teamId=2");
  });

  test("the Estimation group offers task accuracy, epic accuracy and adjustments, and keeps the shared filter", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <ReportTabs tabs={ESTIMATION_TABS} />
        <Where />
      </>,
      { route: "/reports/task-estimation-accuracy?teamId=2&domain=FLO&activityType=Bug&domainView=EPIC" },
    );
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Task accuracy", "Epic accuracy", "Adjustments"]);
    expect(screen.getByRole("tab", { name: "Task accuracy" })).toHaveAttribute("aria-selected", "true");
    // Epics have no activity type / domain-view control, so those params do not follow.
    await user.click(screen.getByRole("tab", { name: "Epic accuracy" }));
    expect(screen.getByTestId("where").textContent).toBe("/reports/epic-estimation-accuracy?teamId=2&domain=FLO");
  });

  test("switching to a report without the controls drops the params it cannot show", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <ReportTabs tabs={DELIVERY_TABS} />
        <Where />
      </>,
      { route: "/reports/throughput?teamId=2&domain=FLO&bucket=MONTH" },
    );
    await user.click(screen.getByRole("tab", { name: "Velocity" }));
    expect(screen.getByTestId("where").textContent).toBe("/reports/velocity?teamId=2");
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
