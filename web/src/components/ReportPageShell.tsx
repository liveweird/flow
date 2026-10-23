import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { Paper } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import type { ReportFilters } from "../api/reports";
import { type ReportTabDef } from "../utils/reportLinks";
import EmptyState from "./EmptyState";
import ReportFiltersStatus from "./ReportFiltersStatus";
import ReportTabs from "./ReportTabs";

/**
 * The skeleton every report page shares under its `ReportPageHeader`: the nav group's tabs (if the report has any), the reference
 * data's own load/failure state, the "no teams" empty state when there is nothing to read, and — once the reference data is in
 * and there is something to read — the page's body, handed the loaded `filters`. `empty` overrides the default rule (the unit
 * has no team) for a report that also reads without one (a domain or an epic pick). Renders fragments only, so the page's
 * `Stack` keeps its direct children.
 */
export default function ReportPageShell({
  tabs,
  filtersQuery,
  filters,
  noTeamsLabel,
  empty,
  children,
}: {
  tabs?: ReadonlyArray<ReportTabDef>;
  filtersQuery: UseQueryResult<ReportFilters>;
  filters: ReportFilters | undefined;
  /** The report's own "there are no teams" sentence. */
  noTeamsLabel: string;
  empty?: boolean;
  children: (filters: ReportFilters) => ReactNode;
}) {
  const nothingToRead = empty ?? (filters !== undefined && filters.teams.length === 0);
  return (
    <>
      {tabs && <ReportTabs tabs={tabs} />}
      <ReportFiltersStatus query={filtersQuery} />
      {nothingToRead && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={noTeamsLabel} />
        </Paper>
      )}
      {filters && !nothingToRead && children(filters)}
    </>
  );
}
