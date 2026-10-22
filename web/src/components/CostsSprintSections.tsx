import { useTranslation } from "react-i18next";
import { Paper, Stack, Text, Title } from "@mantine/core";
import { IconChartBar } from "@tabler/icons-react";
import type { CostsReport, ReportFilters } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { hasEpicSegments } from "../utils/sprintEpics";
import { formatMd } from "../utils/reportFormat";
import CostsCapacitySection from "./CostsCapacitySection";
import CostsSprintEpicSection from "./CostsSprintEpicSection";
import EmptyState from "./EmptyState";

/**
 * Costs' sprint widgets (A44), under the donuts: the actual cost of each closed sprint by epic and against its capacity, then one line
 * for the time logged in the period outside those sprints. They exist for a real team: with no sprint listed (the unit level, which the
 * page never asks for, or a team that completed none in the period) one card says why instead.
 */
export default function CostsSprintSections({
  report,
  filters,
  drill,
  drillable,
  refreshing,
}: {
  report: CostsReport;
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const { sprints, outsideSprintsMd } = report;
  if (sprints.length === 0) {
    return (
      <Paper withBorder p="md">
        <Stack gap="sm">
          <Title order={3} size="h4">
            {t("reports.costs.sprints.title")}
          </Title>
          <EmptyState icon={IconChartBar} label={t(report.meta.level === "UNIT" ? "reports.costs.sprints.needTeam" : "reports.costs.sprints.none")} />
        </Stack>
      </Paper>
    );
  }
  return (
    <>
      {hasEpicSegments(sprints) && (
        <CostsSprintEpicSection sprints={sprints} filters={filters} drill={drill} drillable={drillable} refreshing={refreshing} />
      )}
      <CostsCapacitySection
        sprints={sprints}
        level={report.meta.level}
        filters={filters}
        drill={drill}
        drillable={drillable}
        refreshing={refreshing}
      />
      {outsideSprintsMd !== null && outsideSprintsMd > 0 && (
        <Text size="xs" c="dimmed">
          {t("reports.costs.sprints.outside", { md: formatMd(outsideSprintsMd) })}
        </Text>
      )}
    </>
  );
}
