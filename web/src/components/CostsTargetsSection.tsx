import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Badge, Group, Stack, Text } from "@mantine/core";
import type { CostsCategoryTargetRow, CostsCategoryTargets } from "../api/reports";
import { categoryLabel, deltaBars, formatDeltaPoints } from "../utils/costsCategories";
import { formatMd, formatPercent } from "../utils/reportFormat";
import { teamWorkCategoryTargetsPath } from "../utils/teamLinks";
import { PersonSwatch } from "./ChartLegendList";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ScrollRegion from "./ScrollRegion";

// The chart (and with it recharts) rides its own lazy chunk.
const CostsTargetsChart = lazy(() => import("./CostsTargetsChart"));

const dash = "—";

/**
 * Costs' work-category targets against the team's actual mix (A45, TEAM level): a diverging bar of each category's actual share minus
 * its target, and the table with every figure (target, man-days, share, the delta in percentage points). A team with no targets yet gets
 * a short note and the link to its targets page instead — for everyone, since only editing is the administrators'. A category the
 * metrics configuration no longer maps wears an orange badge. The targets are the team's CURRENT ones, also for an earlier period.
 */
export default function CostsTargetsSection({
  targets,
  teamId,
  teamName,
  colors,
  refreshing,
}: {
  targets: CostsCategoryTargets;
  teamId: number | undefined;
  teamName: string;
  colors: Record<string, string>;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const title = t("reports.costs.categories.targets.title");
  if (!targets.configured) {
    return (
      <ReportChartCard title={title} isPending={false} isRefreshing={refreshing} empty={false}>
        <Stack gap="xs" data-testid="costs-targets-unconfigured">
          <Text size="sm">{t("reports.costs.categories.targets.none", { team: teamName })}</Text>
          {teamId !== undefined && (
            <Anchor component={RouterLink} to={teamWorkCategoryTargetsPath(teamId)} size="sm">
              {t("reports.costs.categories.targets.link", { team: teamName })}
            </Anchor>
          )}
        </Stack>
      </ReportChartCard>
    );
  }
  const tableLabel = t("reports.costs.categories.targets.tableAria");
  const bars = deltaBars(targets.rows, t);
  const columns: ColumnDef<CostsCategoryTargetRow>[] = [
    {
      key: "category",
      header: t("reports.costs.categories.column.category"),
      render: (row) => (
        <Group gap={8} wrap="nowrap">
          <PersonSwatch colour={colors[row.key] ?? "gray.6"} />
          <Text size="sm">{categoryLabel(t, row.key)}</Text>
          {!row.mapped && (
            <Badge color="orange" variant="light">
              {t("reports.costs.categories.unmapped")}
            </Badge>
          )}
        </Group>
      ),
    },
    {
      key: "target",
      header: t("reports.costs.categories.column.target"),
      align: "right",
      render: (row) => (row.targetPercent === null ? dash : String(row.targetPercent)),
    },
    { key: "actualMd", header: t("reports.costs.categories.column.actualMd"), align: "right", render: (row) => formatMd(row.actualMd) },
    {
      key: "actualShare",
      header: t("reports.costs.categories.column.actualShare"),
      align: "right",
      render: (row) => (row.actualShare === null ? dash : formatPercent(row.actualShare)),
    },
    {
      key: "delta",
      header: t("reports.costs.categories.column.delta"),
      align: "right",
      render: (row) => (row.deltaPoints === null ? dash : formatDeltaPoints(row.deltaPoints)),
    },
  ];
  return (
    <ReportChartCard
      title={title}
      caption={t("reports.costs.categories.targets.caption", { team: teamName })}
      isPending={false}
      isRefreshing={refreshing}
      empty={targets.rows.length === 0}
    >
      <Stack gap="md">
        {bars.length > 0 && (
          <Suspense fallback={<LoadingBlock />}>
            <CostsTargetsChart bars={bars} />
          </Suspense>
        )}
        <ScrollRegion label={tableLabel} minWidth={520}>
          <ColumnTable aria-label={tableLabel} columns={columns} rows={targets.rows} rowKey={(row) => row.key} />
        </ScrollRegion>
        <Text size="xs" c="dimmed">
          {t("reports.costs.categories.targets.note")}
        </Text>
      </Stack>
    </ReportChartCard>
  );
}
