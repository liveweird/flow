import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import type { ReportFilters, CommitmentsGroup, CommitmentsSprint } from "../api/reports";
import {
  personLabel,
  userPositions,
  commitmentsFigureColumns,
  commitmentsFigureDrill,
  commitmentsShareRows,
  type CommitmentsShareRow,
} from "../utils/commitmentsReport";
import type { SprintBucket } from "../utils/reportDrill";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import ScrollRegion from "./ScrollRegion";

/**
 * The tables of commitments' user mode (several people picked, or one): each person's period totals (`groups`; the
 * team-level "By member" table links further down, and a user level has nothing further to drill, so this one is plain
 * text — numbered like the chart's legend, so a person is told by number, not colour — and titled differently — "By member" stays the drill table of the team level) and — with two or more people — their share of every sprint, which is where the stacked chart's numbers live (with
 * one person the sprints table beside the chart already is that person's row per sprint).
 */
export default function CommitmentsUserTables({
  sprints,
  groups,
  filters,
  onDrill,
  drillDisabled,
}: {
  sprints: readonly CommitmentsSprint[];
  groups: readonly CommitmentsGroup[];
  filters: ReportFilters;
  /** Opens the tasks behind one person's committed or final share of one sprint (the share table's Initial and Final figures). */
  onDrill?: (sprint: CommitmentsSprint, bucket: SprintBucket, accountId: string, trigger: HTMLElement) => void;
  /** The tables are kept data of a previous filter: the figures are plain text, not buttons. */
  drillDisabled?: boolean;
}) {
  const { t } = useTranslation();
  const shares = commitmentsShareRows(sprints, filters);
  const people = new Set(shares.map((row) => row.share.accountId)).size;
  const positions = userPositions(sprints, filters);

  const groupColumns: ColumnDef<CommitmentsGroup>[] = [
    {
      key: "name",
      header: t("reports.groups.member"),
      render: (group) => {
        const name = group.label ?? group.accountId ?? t("reports.groups.unassigned");
        const position = group.accountId ? positions.get(group.accountId) : undefined;
        return <Text size="sm">{position === undefined ? name : personLabel(position, name)}</Text>;
      },
    },
    ...commitmentsFigureColumns<CommitmentsGroup>(t, (group) => group).map((column) => ({ ...column, align: "right" as const })),
  ];
  const shareColumns: ColumnDef<CommitmentsShareRow>[] = [
    { key: "sprint", header: t("reports.sprints.sprint"), render: (row) => row.sprint.name },
    { key: "person", header: t("reports.commitments.column.person"), render: (row) => personLabel(row.position, row.share.label) },
    ...commitmentsFigureColumns<CommitmentsShareRow>(t, (row) => row.share).map((column) => {
      const right = { ...column, align: "right" as const };
      const target = commitmentsFigureDrill<CommitmentsShareRow>(column.key, (row) => row.share);
      if (!target) return right;
      const { bucket, items } = target;
      return drillFigureColumn(right, {
        label: (row, figure, columnHeader) =>
          t("reports.drill.figureAria", {
            figure,
            column: columnHeader,
            name: `${row.sprint.name} · ${personLabel(row.position, row.share.label)} · ${t(`reports.drill.bucket.${bucket}`)}`,
          }),
        count: items,
        onOpen: onDrill && ((row, trigger) => onDrill(row.sprint, bucket, row.share.accountId, trigger)),
        disabled: drillDisabled,
      });
    }),
  ];

  return (
    <Stack gap="md">
      {groups.length > 0 && (
        <Stack gap="xs">
          <Title order={3} size="h4">
            {t("reports.commitments.personTotalsTitle")}
          </Title>
          <ColumnTable
            aria-label={t("reports.commitments.personTotalsTitle")}
            columns={groupColumns}
            rows={groups}
            rowKey={(group) => `${group.accountId ?? "-"}:${group.label ?? "-"}`}
          />
        </Stack>
      )}
      {people > 1 && (
        <Stack gap="xs">
          <Title order={3} size="h4">
            {t("reports.commitments.sharesTitle")}
          </Title>
          <ScrollRegion label={t("reports.commitments.sharesAria")} minWidth={640} maxHeight={480}>
            <ColumnTable
              aria-label={t("reports.commitments.sharesAria")}
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
