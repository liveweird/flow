import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Stack, Table, Text, Title } from "@mantine/core";
import { Link as RouterLink, useLocation, useSearchParams } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import { applyReportFilter, parseReportFilter } from "../utils/reportFilter";

/** The identity every report's `groups` row carries (a team at UNIT level, a member at TEAM level). */
export interface GroupIdentity {
  teamId?: number | null;
  accountId?: string | null;
  label?: string | null;
}

export interface GroupColumn<G> {
  key: string;
  header: string;
  render: (group: G) => ReactNode;
}

/**
 * The next org level of a report: one row per team (UNIT) or member (TEAM). The row NAME is the
 * way in — a real link to the same report narrowed to that team/member (cmd-click opens a tab),
 * with an interpolated accessible name. A row with no identity is the unassigned bucket: shown,
 * never linked. USER level has nothing further to drill, so it renders nothing.
 */
export default function ReportGroupsTable<G extends GroupIdentity>({
  level,
  filters,
  groups,
  columns,
}: {
  level: "UNIT" | "TEAM" | "USER";
  filters: ReportFilters;
  groups: ReadonlyArray<G>;
  columns: ReadonlyArray<GroupColumn<G>>;
}) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  if (level === "USER" || groups.length === 0) return null;

  const unit = level === "UNIT";
  const narrowed = (group: G): string | null => {
    const current = parseReportFilter(params);
    if (unit && group.teamId != null) {
      return applyReportFilter(params, { ...current, teamId: group.teamId, accountId: undefined }).toString();
    }
    if (!unit && group.accountId != null) {
      return applyReportFilter(params, { ...current, accountId: group.accountId }).toString();
    }
    return null;
  };
  const displayName = (group: G): string | null => {
    if (group.label) return group.label;
    if (unit) return filters.teams.find((team) => team.id === group.teamId)?.name ?? null;
    return (
      filters.teams.flatMap((team) => team.members).find((member) => member.accountId === group.accountId)
        ?.displayName ?? null
    );
  };

  const title = unit ? t("reports.groups.byTeam") : t("reports.groups.byMember");

  return (
    <Stack gap="xs">
      <Title order={3} size="h4">
        {title}
      </Title>
      <Table aria-label={title}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{unit ? t("reports.groups.team") : t("reports.groups.member")}</Table.Th>
            {columns.map((column) => (
              <Table.Th key={column.key} ta="right">
                {column.header}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {groups.map((group) => {
            const name = displayName(group);
            const search = narrowed(group);
            return (
              <Table.Tr key={`${group.teamId ?? "-"}:${group.accountId ?? "-"}:${group.label ?? "-"}`}>
                <Table.Td>
                  {search === null ? (
                    <Text size="sm" c="dimmed">
                      {name ?? t("reports.groups.unassigned")}
                    </Text>
                  ) : (
                    <Anchor
                      component={RouterLink}
                      to={{ pathname, search }}
                      size="sm"
                      aria-label={t("reports.groups.drillAria", { name: name ?? String(group.teamId ?? group.accountId) })}
                    >
                      {name ?? String(group.teamId ?? group.accountId)}
                    </Anchor>
                  )}
                </Table.Td>
                {columns.map((column) => (
                  <Table.Td key={column.key} ta="right">
                    {column.render(group)}
                  </Table.Td>
                ))}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
