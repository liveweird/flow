import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { CostsReport, ReportFilters } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { COSTS_CAPACITY_ANCHOR } from "../utils/costsReport";
import { costsCapacityRows, costsSprintTarget, type CostsCapacityRow } from "../utils/costsSprints";
import { drillFigureColumn } from "../utils/drillFigure";
import { formatMd, formatPercent } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ScrollRegion from "./ScrollRegion";

// The chart (and with it recharts) rides its own lazy chunk.
const CostsCapacityChart = lazy(() => import("./CostsCapacityChart"));

/**
 * Costs' actual cost per sprint against the sprint's capacity: a bar per closed sprint with the capacity line over it and, beside the
 * chart, the table (Sprint · Actual cost · Capacity · Actual ÷ capacity · Source) that carries every number. The capacity is the TEAM's
 * (a sprint calendar, else the team default, else members × working days) and every viewer sees it; with one author picked there is
 * none, and the page says so. A sprint's bar drills whole — a double-click on it or a click on its figure in the table opens the worklogs
 * behind it. While the rows are kept data of a previous filter (`drillable` false) nothing opens.
 */
export default function CostsCapacitySection({
  sprints,
  level,
  filters,
  drill,
  drillable,
  refreshing,
}: {
  sprints: CostsReport["sprints"];
  level: CostsReport["meta"]["level"];
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const rows = costsCapacityRows(sprints, filters);
  const tableLabel = t("reports.costs.sprints.capacityTableAria");
  const open = (rowId: string, trigger?: HTMLElement) => {
    const target = costsSprintTarget(rowId);
    if (target) drill.open(target, { trigger, anchor: COSTS_CAPACITY_ANCHOR });
  };
  const columns: ColumnDef<CostsCapacityRow>[] = [
    { key: "sprint", header: t("reports.sprints.sprint"), render: (row) => row.label },
    drillFigureColumn<CostsCapacityRow>(
      { key: "acMd", header: t("reports.costs.sprints.column.acMd"), align: "right", render: (row) => formatMd(row.acMd) },
      {
        label: (row, shown, column) =>
          t("reports.drill.figureAria", { figure: shown, column, name: `${row.label} · ${t("reports.drill.actualCost")}` }),
        count: (row) => (row.acMd > 0 ? 1 : 0),
        onOpen: (row, trigger) => open(row.id, trigger),
        disabled: !drillable,
      },
    ),
    {
      key: "capacityMd",
      header: t("reports.costs.sprints.column.capacityMd"),
      align: "right",
      render: (row) => (row.capacityMd === null ? "—" : formatMd(row.capacityMd)),
    },
    {
      key: "load",
      header: t("reports.costs.sprints.column.load"),
      align: "right",
      render: (row) => (row.capacityMd === null || row.capacityMd === 0 ? "—" : formatPercent(row.acMd / row.capacityMd)),
    },
    {
      key: "source",
      header: t("reports.costs.sprints.column.source"),
      render: (row) => (row.capacitySource === null ? "—" : t(`reports.costs.sprints.source.${row.capacitySource}`)),
    },
  ];
  return (
    <ReportChartCard
      title={t("reports.costs.sprints.capacityTitle")}
      caption={t("reports.costs.sprints.capacityCaption")}
      isPending={false}
      isRefreshing={refreshing}
      empty={false}
      hint={t("reports.drill.hint")}
      drillAnchor={COSTS_CAPACITY_ANCHOR}
    >
      <Stack gap="md">
        <Suspense fallback={<LoadingBlock />}>
          <CostsCapacityChart
            rows={rows}
            onBarDoubleClick={
              drillable
                ? (hit) => {
                    if (hit.series === "acMd") open(hit.rowId);
                  }
                : undefined
            }
          />
        </Suspense>
        <ScrollRegion label={tableLabel} minWidth={640}>
          <ColumnTable aria-label={tableLabel} columns={columns} rows={rows} rowKey={(row) => row.id} />
        </ScrollRegion>
        <Text size="xs" c="dimmed">
          {t(level === "USER" ? "reports.costs.sprints.capacityUserNote" : "reports.costs.sprints.capacityNote")}
        </Text>
      </Stack>
    </ReportChartCard>
  );
}
