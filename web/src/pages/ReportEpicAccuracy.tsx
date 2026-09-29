import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Table, Text } from "@mantine/core";
import { getEpicEstimationAccuracyReport, type EpicAccuracyGroup } from "../api/reports";
import DistributionPanel from "../components/DistributionPanel";
import EpicsPerPersonNote from "../components/EpicsPerPersonNote";
import ExcludedList from "../components/ExcludedList";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatDate } from "../utils/formatDate";
import { formatMd, formatMedian, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

// An epic's domain is its own space under either domain view and it has no activity type, so those
// two controls would change nothing — only domain and work category slice this report.
const CONTROLS: ReportControls = { domain: true, workCategory: true };

const optional = (value: number | null, format: (n: number) => string) => (value === null ? "—" : format(value));

/** Report 4 — epic estimation accuracy: actual ÷ the epic's OWN estimate, at start (primary) and at done. */
export default function ReportEpicAccuracy() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("epic-estimation-accuracy", getEpicEstimationAccuracyReport);
  const report = query.data;

  const groupColumns: GroupColumn<EpicAccuracyGroup>[] = [
    { key: "population", header: t("reports.epicAccuracy.column.population"), render: (g) => g.excluded.population },
    { key: "startN", header: t("reports.epicAccuracy.column.startN"), render: (g) => g.atStart.n },
    { key: "startMedian", header: t("reports.epicAccuracy.column.startMedian"), render: (g) => formatMedian(g.atStart, formatRatio) },
    { key: "doneN", header: t("reports.epicAccuracy.column.doneN"), render: (g) => g.atDone.n },
    { key: "doneMedian", header: t("reports.epicAccuracy.column.doneMedian"), render: (g) => formatMedian(g.atDone, formatRatio) },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.epicAccuracy.title")} description={t("reports.epicAccuracy.description")} />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          {report?.meta.level === "USER" ? (
            <EpicsPerPersonNote />
          ) : (
            <ReportChartCard
              title={t("reports.epicAccuracy.title")}
              caption={t("reports.ratio.hint")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.excluded.population === 0}
            >
              {report && (
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                  {/* Each view carries ITS OWN accounting: the two partitions differ, so they never share a list. */}
                  <Stack gap="lg">
                    <DistributionPanel
                      title={t("reports.epicAccuracy.startTitle")}
                      caption={t("reports.epicAccuracy.startCaption")}
                      distribution={report.atStart}
                      minSampleSize={report.meta.minSampleSize}
                      format={formatRatio}
                      axisLabel={t("reports.ratio.axis")}
                    />
                    <ExcludedList
                      population={report.excluded.population}
                      measured={report.atStart.n}
                      items={[
                        { label: t("reports.epicAccuracy.excluded.noActual"), count: report.excluded.noActual },
                        { label: t("reports.epicAccuracy.excluded.neverStarted"), count: report.excluded.neverStarted },
                        { label: t("reports.epicAccuracy.excluded.unestimatedAtStart"), count: report.excluded.unestimatedAtStart },
                      ]}
                    />
                  </Stack>
                  <Stack gap="lg">
                    <DistributionPanel
                      title={t("reports.epicAccuracy.doneTitle")}
                      caption={t("reports.epicAccuracy.doneCaption")}
                      distribution={report.atDone}
                      minSampleSize={report.meta.minSampleSize}
                      format={formatRatio}
                      axisLabel={t("reports.ratio.axis")}
                    />
                    <ExcludedList
                      population={report.excluded.population}
                      measured={report.atDone.n}
                      items={[
                        { label: t("reports.epicAccuracy.excluded.noActual"), count: report.excluded.noActual },
                        { label: t("reports.epicAccuracy.excluded.unestimatedAtDone"), count: report.excluded.unestimatedAtDone },
                      ]}
                    />
                  </Stack>
                </SimpleGrid>
              )}
            </ReportChartCard>
          )}
          {report && report.epics.length > 0 && (
            <ReportChartCard
              title={t("reports.epicAccuracy.epicsTitle")}
              caption={report.epicsTruncated ? t("reports.epicAccuracy.epicsTruncated", { count: report.epics.length }) : undefined}
              isPending={false}
              isRefreshing={query.isPlaceholderData}
              empty={false}
            >
              <Table.ScrollContainer minWidth={900}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t("reports.epicAccuracy.column.epic")}</Table.Th>
                      <Table.Th>{t("reports.epicAccuracy.column.doneAt")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.estimateStart")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.estimateDone")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.childSum")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.actual")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.ratioStart")}</Table.Th>
                      <Table.Th ta="right">{t("reports.epicAccuracy.column.ratioDone")}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {report.epics.map((epic, index) => (
                      // The list is ordered and fixed per response; key and position together stay unique across connections.
                      <Table.Tr key={`${epic.issueKey}:${index}`}>
                        <Table.Td>
                          <Text size="sm" fw={600}>
                            {epic.issueKey}
                          </Text>
                          {epic.summary && (
                            <Text size="xs" c="dimmed">
                              {epic.summary}
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>{formatDate(epic.doneAt, "—", filters.timeZone)}</Table.Td>
                        <Table.Td ta="right">{optional(epic.ownEstimateAtStartMd, formatMd)}</Table.Td>
                        <Table.Td ta="right">{optional(epic.ownEstimateAtDoneMd, formatMd)}</Table.Td>
                        <Table.Td ta="right">{formatMd(epic.childSumMd)}</Table.Td>
                        <Table.Td ta="right">{formatMd(epic.actualMd)}</Table.Td>
                        <Table.Td ta="right">{optional(epic.ratio, formatRatio)}</Table.Td>
                        <Table.Td ta="right">{optional(epic.ratioAtDone, formatRatio)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </ReportChartCard>
          )}
          {report && report.groups.length > 0 && (
            <Stack gap="xs">
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
              <Text size="xs" c="dimmed">
                {t("reports.groupsCaption.distribution")}
              </Text>
            </Stack>
          )}
        </>
      )}
    </Stack>
  );
}
