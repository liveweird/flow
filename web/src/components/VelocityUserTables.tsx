import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import type { ReportFilters, VelocityGroup, VelocitySprint } from "../api/reports";
import { personLabel, userPositions, velocityFigureColumns, velocityShareRows, type VelocityShareRow } from "../utils/velocityReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/**
 * The tables of velocity's user mode (several people picked, or one): each person's period totals (`groups`; the
 * team-level "By member" table links further down, and a user level has nothing further to drill, so this one is plain
 * text — numbered like the chart's legend, so a person is told by number, not colour — and titled differently — "By member" stays the drill table of the team level) and — with two or more people — their share of every sprint, which is where the stacked chart's numbers live (with
 * one person the sprints table beside the chart already is that person's row per sprint).
 */
export default function VelocityUserTables({
  sprints,
  groups,
  filters,
}: {
  sprints: readonly VelocitySprint[];
  groups: readonly VelocityGroup[];
  filters: ReportFilters;
}) {
  const { t } = useTranslation();
  const shares = velocityShareRows(sprints, filters);
  const people = new Set(shares.map((row) => row.share.accountId)).size;
  const positions = userPositions(sprints, filters);

  const groupColumns: ColumnDef<VelocityGroup>[] = [
    {
      key: "name",
      header: t("reports.groups.member"),
      render: (group) => {
        const name = group.label ?? group.accountId ?? t("reports.groups.unassigned");
        const position = group.accountId ? positions.get(group.accountId) : undefined;
        return <Text size="sm">{position === undefined ? name : personLabel(position, name)}</Text>;
      },
    },
    ...velocityFigureColumns<VelocityGroup>(t, (group) => group).map((column) => ({ ...column, align: "right" as const })),
  ];
  const shareColumns: ColumnDef<VelocityShareRow>[] = [
    { key: "sprint", header: t("reports.sprints.sprint"), render: (row) => row.sprint.name },
    { key: "person", header: t("reports.velocity.column.person"), render: (row) => personLabel(row.position, row.share.label) },
    ...velocityFigureColumns<VelocityShareRow>(t, (row) => row.share).map((column) => ({ ...column, align: "right" as const })),
  ];

  return (
    <Stack gap="md">
      {groups.length > 0 && (
        <Stack gap="xs">
          <Title order={3} size="h4">
            {t("reports.velocity.personTotalsTitle")}
          </Title>
          <ColumnTable
            aria-label={t("reports.velocity.personTotalsTitle")}
            columns={groupColumns}
            rows={groups}
            rowKey={(group) => `${group.accountId ?? "-"}:${group.label ?? "-"}`}
          />
        </Stack>
      )}
      {people > 1 && (
        <Stack gap="xs">
          <Title order={3} size="h4">
            {t("reports.velocity.sharesTitle")}
          </Title>
          <ScrollRegion label={t("reports.velocity.sharesAria")} minWidth={640} maxHeight={480}>
            <ColumnTable
              aria-label={t("reports.velocity.sharesAria")}
              columns={shareColumns}
              rows={shares}
              rowKey={(row) => row.key}
              stickyHeader
            />
          </ScrollRegion>
        </Stack>
      )}
    </Stack>
  );
}
