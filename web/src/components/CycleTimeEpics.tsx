import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import type { EpicCycleTime, EpicCycleTimeGroup, ReportFilters } from "../api/reports";
import { formatDays, formatMedian } from "../utils/reportFormat";
import DistributionWithAccounting from "./DistributionWithAccounting";
import EpicsPerPersonNote from "./EpicsPerPersonNote";
import ReportChartCard from "./ReportChartCard";
import ReportGroupsTable, { type GroupColumn } from "./ReportGroupsTable";

/**
 * The epics beside the tasks on the cycle-time report: the same two views (working days first, elapsed days
 * second), each with its own accounting, for the epics finished in the period — owned by their domain's team,
 * never by a person — and, at UNIT level, the per-owner-team table. At USER level an epic read is empty by
 * design, so one note says so instead of a block of zeros.
 */
export default function CycleTimeEpics({
  epics,
  level,
  minSampleSize,
  filters,
  isRefreshing,
}: {
  epics: EpicCycleTime;
  level: "UNIT" | "TEAM" | "USER";
  minSampleSize: number;
  filters: ReportFilters;
  isRefreshing: boolean;
}) {
  const { t } = useTranslation();
  const neverStarted = [{ label: t("reports.cycleTime.epics.neverStarted"), count: epics.excluded.neverStarted }];
  const groupColumns: GroupColumn<EpicCycleTimeGroup>[] = [
    { key: "population", header: t("reports.cycleTime.column.population"), render: (g) => g.excluded.population },
    { key: "workingN", header: t("reports.cycleTime.column.workingN"), render: (g) => g.workingDays.n },
    { key: "workingMedian", header: t("reports.cycleTime.column.workingMedian"), render: (g) => formatMedian(g.workingDays, formatDays) },
    { key: "elapsedN", header: t("reports.cycleTime.column.elapsedN"), render: (g) => g.elapsedDays.n },
    { key: "elapsedMedian", header: t("reports.cycleTime.column.elapsedMedian"), render: (g) => formatMedian(g.elapsedDays, formatDays) },
  ];

  let body: ReactNode;
  if (level === "USER") {
    body = <EpicsPerPersonNote />;
  } else if (epics.excluded.population === 0) {
    // The task card above carries the generic empty state; this says what is actually missing.
    body = (
      <Text size="sm" c="dimmed">
        {t("reports.cycleTime.epics.none")}
      </Text>
    );
  } else {
    body = (
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
        <DistributionWithAccounting
          title={t("reports.cycleTime.epics.workingTitle")}
          distribution={epics.workingDays}
          minSampleSize={minSampleSize}
          format={formatDays}
          axisLabel={t("reports.cycleTime.workingAxis")}
          population={epics.excluded.population}
          items={neverStarted}
        />
        <DistributionWithAccounting
          title={t("reports.cycleTime.epics.elapsedTitle")}
          distribution={epics.elapsedDays}
          minSampleSize={minSampleSize}
          format={formatDays}
          axisLabel={t("reports.cycleTime.elapsedAxis")}
          population={epics.excluded.population}
          items={neverStarted}
        />
      </SimpleGrid>
    );
  }

  return (
    <>
      <ReportChartCard
        title={t("reports.cycleTime.epics.title")}
        caption={t("reports.cycleTime.epics.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={false}
      >
        {body}
      </ReportChartCard>
      {level === "UNIT" && epics.groups.length > 0 && (
        <Stack gap="xs">
          <ReportGroupsTable
            level={level}
            filters={filters}
            groups={epics.groups}
            columns={groupColumns}
            wording={{
              title: t("reports.cycleTime.epics.byOwnerTeam"),
              teamHeader: t("reports.cycleTime.epics.ownerTeam"),
              unassigned: t("reports.groups.noOwner"),
            }}
          />
          <Text size="xs" c="dimmed">
            {t("reports.groupsCaption.distribution")}
          </Text>
        </Stack>
      )}
    </>
  );
}
