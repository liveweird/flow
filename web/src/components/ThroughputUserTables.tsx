import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import type { ThroughputGroup } from "../api/reports";
import { personLabel } from "../utils/commitmentsReport";
import {
  throughputCumulativeColumns,
  throughputFigureColumns,
  throughputShareRows,
  type ThroughputSeries,
  type ThroughputShareRow,
} from "../utils/throughputReport";
import { throughputCostDrillColumn } from "../utils/throughputCostDrill";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import ScrollRegion from "./ScrollRegion";

/** Each person's share of every week/month or sprint — where a stacked chart's numbers live. */
function SharesTable({
  title,
  aria,
  slotHeader,
  series,
  cumulative,
  onDrill,
  onDrillCost,
  drillDisabled,
}: {
  title: string;
  aria: string;
  slotHeader: string;
  series: ThroughputSeries;
  cumulative: boolean;
  onDrill?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  /** Opens the time THIS person logged in the slot (the Actual cost figure). */
  onDrillCost?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  drillDisabled?: boolean;
}) {
  const { t } = useTranslation();
  const columns: ColumnDef<ThroughputShareRow>[] = [
    { key: "slot", header: slotHeader, render: (row) => row.label },
    { key: "person", header: t("reports.throughput.column.person"), render: (row) => personLabel(row.person.position, row.person.label) },
    ...throughputFigureColumns<ThroughputShareRow>(t, (row) => row.figures).map((column) => {
      const right = { ...column, align: "right" as const };
      return onDrill
        ? drillFigureColumn(right, {
            label: (row, figure, column) =>
              t("reports.drill.figureAria", { figure, column, name: `${row.label} · ${personLabel(row.person.position, row.person.label)}` }),
            count: (row) => row.figures.deliveredItems,
            onOpen: onDrill,
            disabled: drillDisabled,
          })
        : right;
    }),
    throughputCostDrillColumn<ThroughputShareRow>(
      t,
      (row) => row.figures,
      (row) => `${row.label} · ${personLabel(row.person.position, row.person.label)}`,
      onDrillCost,
      drillDisabled,
    ),
    ...(cumulative
      ? throughputCumulativeColumns<ThroughputShareRow>(t, (row) => row.figures).map((column) => ({ ...column, align: "right" as const }))
      : []),
  ];
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">
        {title}
      </Title>
      <ScrollRegion label={aria} minWidth={cumulative ? 900 : 640} maxHeight={480}>
        <ColumnTable aria-label={aria} columns={columns} rows={throughputShareRows(series)} rowKey={(row) => row.key} stickyHeader />
      </ScrollRegion>
    </Stack>
  );
}

/**
 * The tables of throughput's people view (one or more people picked): each person's period totals (`groups`; the team
 * level's "By member" stays the drill table, and a user level has nothing further to drill, so this one is plain text —
 * numbered like the chart's legend, so a person is told by number, not colour) and — with two or more people — their share of
 * every week/month and every sprint (with one person the period and sprint tables already are that person's rows).
 */
export default function ThroughputUserTables({
  groups,
  buckets,
  sprints,
  bucket,
  cumulative,
  onDrillPeriodShare,
  onDrillSprintShare,
  onDrillPeriodCostShare,
  onDrillSprintCostShare,
  drillDisabled,
}: {
  groups: readonly ThroughputGroup[];
  buckets: ThroughputSeries;
  sprints: ThroughputSeries;
  bucket: "WEEK" | "MONTH";
  cumulative: boolean;
  /** Opens the tasks behind one person's share of one week/month (the share table's Delivered figures). */
  onDrillPeriodShare?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  /** Opens the tasks one person delivered in one sprint (the sprint share table's Delivered figures). */
  onDrillSprintShare?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  /** Opens the time one person logged in a week/month / in a sprint (the share tables' Actual cost figures). */
  onDrillPeriodCostShare?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  onDrillSprintCostShare?: (row: ThroughputShareRow, trigger: HTMLElement) => void;
  /** The tables are kept data of a previous filter: the figures are plain text, not buttons. */
  drillDisabled?: boolean;
}) {
  const { t } = useTranslation();
  // A sprintId period for a sprint not yet started has an empty period window, so its people come from the sprint series.
  const people = buckets.people.length > 0 ? buckets.people : sprints.people;
  const positions = new Map(people.map((person) => [person.accountId, person.position]));
  const groupColumns: ColumnDef<ThroughputGroup>[] = [
    {
      key: "name",
      header: t("reports.groups.member"),
      render: (group) => {
        const name = group.label ?? group.accountId ?? t("reports.groups.unassigned");
        const position = group.accountId ? positions.get(group.accountId) : undefined;
        return <Text size="sm">{position === undefined ? name : personLabel(position, name)}</Text>;
      },
    },
    ...throughputFigureColumns<ThroughputGroup>(t, (group) => group).map((column) => ({ ...column, align: "right" as const })),
  ];
  const severalPeople = people.length > 1;
  return (
    <Stack gap="md">
      {groups.length > 0 && (
        <Stack gap="xs">
          <Title order={3} size="h4">
            {t("reports.throughput.personTotalsTitle")}
          </Title>
          <ColumnTable
            aria-label={t("reports.throughput.personTotalsTitle")}
            columns={groupColumns}
            rows={groups}
            rowKey={(group) => `${group.accountId ?? "-"}:${group.label ?? "-"}`}
          />
        </Stack>
      )}
      {severalPeople && (
        <SharesTable
          title={t("reports.throughput.periodSharesTitle")}
          aria={t("reports.throughput.periodSharesAria")}
          slotHeader={t(`reports.throughput.column.bucket${bucket}`)}
          series={buckets}
          cumulative={cumulative}
          onDrill={onDrillPeriodShare}
          onDrillCost={onDrillPeriodCostShare}
          drillDisabled={drillDisabled}
        />
      )}
      {severalPeople && sprints.rows.length > 0 && (
        <SharesTable
          title={t("reports.throughput.sprintSharesTitle")}
          aria={t("reports.throughput.sprintSharesAria")}
          slotHeader={t("reports.sprints.sprint")}
          series={sprints}
          cumulative={cumulative}
          onDrill={onDrillSprintShare}
          onDrillCost={onDrillSprintCostShare}
          drillDisabled={drillDisabled}
        />
      )}
    </Stack>
  );
}
