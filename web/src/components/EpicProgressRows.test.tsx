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
  EPIC_PROGRESS_UNIT,
  EPIC_PROGRESS_UNIT_PERIOD,
  FILTERS,
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
      <EpicProgressRows report={report} filters={FILTERS} />
      <Probe />
    </Fragment>,
    { route, queryClient: new QueryClient() },
  );
}

describe("EpicProgressRows", () => {
  test("UNIT: the domains and, apart, the teams; a deleted team is marked and not linked", () => {
    show(EPIC_PROGRESS_UNIT);
    expect(screen.getByRole("heading", { name: "Domains" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Teams (sprint view)" })).toBeInTheDocument();
    expect(rowsOf("Domains")).toEqual([["Flow", "12", "9", "10", "−3", "0.75", "−1", "0.90"]]);
    const teams = within(screen.getByRole("table", { name: "Teams (sprint view)" }));
    expect(teams.getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(teams.queryByRole("link", { name: "Show Old team" })).not.toBeInTheDocument();
    expect(teams.getByText("Deleted team")).toBeInTheDocument();
    // a team with no sprint figures has an index of "—", not NaN
    expect(rowsOf("Teams (sprint view)")[2].slice(5)).toEqual(["—", "−3", "0.00"]);
  });

  test("a domain with no key (work with no domain) is plain text, not a link to a scope that does not exist", () => {
    show({ ...EPIC_PROGRESS_UNIT, rows: [{ ...EPIC_PROGRESS_UNIT.rows[0], key: null, name: "No domain" }] });
    const domains = within(screen.getByRole("table", { name: "Domains" }));
    expect(domains.getByText("No domain")).toBeInTheDocument();
    expect(domains.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("Nothing to list as of this day.")).toBeInTheDocument();
  });

  test("on the PERIOD basis the rows show their period block, not the cumulative figures", () => {
    show(EPIC_PROGRESS_UNIT_PERIOD);
    expect(rowsOf("Domains")).toEqual([["Flow", "6", "7", "6", "+1", "1.17", "+1", "1.17"]]);
  });

  test("a domain link re-scopes the report to that domain, carrying the period along", async () => {
    show(EPIC_PROGRESS_UNIT, "/reports/epic-progress?from=2026-09-01&to=2026-09-29");
    await userEvent.click(within(screen.getByRole("table", { name: "Domains" })).getByRole("link", { name: "Show Flow" }));
    await waitFor(() => expect(screen.getByTestId("where").textContent).toContain("domain=FLO"));
    expect(screen.getByTestId("where").textContent).toContain("from=2026-09-01");
  });

  test("a team link drops a one-sprint period the team does not list, and keeps it for a team that does", async () => {
    const { unmount } = show(EPIC_PROGRESS_UNIT, "/reports/epic-progress?sprintId=999");
    await userEvent.click(within(screen.getByRole("table", { name: "Teams (sprint view)" })).getByRole("link", { name: "Show Alpha" }));
    await waitFor(() => expect(screen.getByTestId("where").textContent).toContain("teamId=1"));
    expect(screen.getByTestId("where").textContent).not.toContain("sprintId");
    unmount();

    show(EPIC_PROGRESS_UNIT, "/reports/epic-progress?sprintId=11");
    await userEvent.click(within(screen.getByRole("table", { name: "Teams (sprint view)" })).getByRole("link", { name: "Show Alpha" }));
    await waitFor(() => expect(screen.getByTestId("where").textContent).toContain("teamId=1"));
    expect(screen.getByTestId("where").textContent).toContain("sprintId=11");
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
