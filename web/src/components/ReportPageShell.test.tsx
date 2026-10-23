import { describe, expect, test } from "vitest";
import type { UseQueryResult } from "@tanstack/react-query";
import type { ReportFilters } from "../api/reports";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { renderWithProviders, screen } from "../test/render";
import ReportPageShell from "./ReportPageShell";

const query = (state: Partial<UseQueryResult<ReportFilters>>) => state as UseQueryResult<ReportFilters>;
const filters = (teams: number) => ({ teams: Array.from({ length: teams }, (_, i) => ({ id: i + 1 })), timeZone: "UTC" }) as unknown as ReportFilters;

describe("ReportPageShell", () => {
  test("hands the loaded filters to the body once there is something to read", () => {
    renderWithProviders(
      <ReportPageShell tabs={DELIVERY_TABS} filtersQuery={query({ isPending: false, isError: false })} filters={filters(2)} noTeamsLabel="No teams">
        {(loaded) => <p>{`zone ${loaded.timeZone}`}</p>}
      </ReportPageShell>,
    );
    expect(screen.getByText("zone UTC")).toBeInTheDocument();
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.queryByText("No teams")).not.toBeInTheDocument();
  });

  test("shows the empty state, not the body, when the unit has no teams", () => {
    renderWithProviders(
      <ReportPageShell filtersQuery={query({ isPending: false, isError: false })} filters={filters(0)} noTeamsLabel="No teams">
        {() => <p>body</p>}
      </ReportPageShell>,
    );
    expect(screen.getByText("No teams")).toBeInTheDocument();
    expect(screen.queryByText("body")).not.toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  test("an explicit empty overrides the default rule both ways", () => {
    const { rerender } = renderWithProviders(
      <ReportPageShell filtersQuery={query({ isPending: false, isError: false })} filters={filters(0)} noTeamsLabel="Nothing" empty={false}>
        {() => <p>body</p>}
      </ReportPageShell>,
    );
    expect(screen.getByText("body")).toBeInTheDocument();
    rerender(
      <ReportPageShell filtersQuery={query({ isPending: false, isError: false })} filters={filters(2)} noTeamsLabel="Nothing" empty>
        {() => <p>body</p>}
      </ReportPageShell>,
    );
    expect(screen.getByText("Nothing")).toBeInTheDocument();
    expect(screen.queryByText("body")).not.toBeInTheDocument();
  });

  test("renders neither body nor empty state while the filters are still loading", () => {
    renderWithProviders(
      <ReportPageShell filtersQuery={query({ isPending: true, isError: false })} filters={undefined} noTeamsLabel="No teams">
        {() => <p>body</p>}
      </ReportPageShell>,
    );
    expect(screen.queryByText("body")).not.toBeInTheDocument();
    expect(screen.queryByText("No teams")).not.toBeInTheDocument();
  });
});
