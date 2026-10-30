import { Fragment } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { useLocation } from "react-router-dom";
import EpicProgressRows from "./EpicProgressRows";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import {
  EPIC_PROGRESS_DOMAIN,
  EPIC_PROGRESS_DOMAIN_PERIOD,
  EPIC_PROGRESS_EPIC,
} from "../test/reportFixtures";

/** The router state a drill link would hand over, shown so a test can read it after following the link. */
function Probe() {
  const location = useLocation();
  return <output data-testid="where">{`${location.pathname}?${location.search.slice(1)}`}</output>;
}

const rowsOf = (name: string) =>
  within(screen.getByRole("table", { name }))
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));

function show(report: Parameters<typeof EpicProgressRows>[0]["report"], route = "/reports/epic-progress") {
  return renderWithProviders(
    <Fragment>
      <EpicProgressRows report={report} />
      <Probe />
    </Fragment>,
    { route, queryClient: new QueryClient() },
  );
}

describe("EpicProgressRows", () => {
  test("on the PERIOD basis the rows show their period block, not the cumulative figures", () => {
    show(EPIC_PROGRESS_DOMAIN_PERIOD);
    expect(rowsOf("Epics").map((row) => row.slice(1))).toEqual([
      ["4", "5", "4", "+1", "1.25", "+1", "1.25"],
      ["2", "2", "2", "0", "1.00", "0", "1.00"],
    ]);
  });

  test("an epic link re-scopes the report to that epic, carrying the period along and the domain in the router state", async () => {
    show(EPIC_PROGRESS_DOMAIN, "/reports/epic-progress?from=2026-09-01&to=2026-09-29&domain=FLO");
    await userEvent.click(within(screen.getByRole("table", { name: "Epics" })).getByRole("link", { name: "Show FLO-33" }));
    await waitFor(() => expect(screen.getByTestId("where").textContent).toContain("epicId=FLO-33"));
    expect(screen.getByTestId("where").textContent).toContain("from=2026-09-01");
    expect(screen.getByTestId("where").textContent).not.toContain("domain=FLO");
  });

  test("DOMAIN: its epics, each linked by key with a Jira icon and the name beside it unless the name IS the key", () => {
    show(EPIC_PROGRESS_DOMAIN);
    expect(screen.getByRole("heading", { name: "Epics" })).toBeInTheDocument();
    const epics = within(screen.getByRole("table", { name: "Epics" }));
    expect(epics.getByRole("link", { name: "Show FLO-33" })).toBeInTheDocument();
    expect(epics.getByText("Reporting epic")).toBeInTheDocument();
    // FLO-40's name is its key: printed once
    expect(epics.getAllByText("FLO-40")).toHaveLength(1);
  });

  test("an epic that changed domain carries a 'part' badge with its explanation; a whole epic does not", async () => {
    show({
      ...EPIC_PROGRESS_DOMAIN,
      rows: EPIC_PROGRESS_DOMAIN.rows.map((row, i) => ({ ...row, partial: i === 0 ? true : null })),
    });
    const badges = within(screen.getByRole("table", { name: "Epics" })).getAllByText("part");
    expect(badges).toHaveLength(1);
    expect(within(screen.getAllByRole("row")[1]).getByText("part")).toBe(badges[0]);
    await userEvent.hover(badges[0]);
    expect(await screen.findByText(/holds only this domain's share/)).toBeInTheDocument();
  });

  test("DOMAIN on PERIOD, and a report with no rows says there is nothing to list", () => {
    show({ ...EPIC_PROGRESS_DOMAIN_PERIOD, rows: [] });
    expect(screen.getByText("Nothing to list as of this day.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("EPIC and TEAM levels have nothing further to drill: nothing is drawn", () => {
    show(EPIC_PROGRESS_EPIC);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});
