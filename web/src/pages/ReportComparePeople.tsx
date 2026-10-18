import { useTranslation } from "react-i18next";
import { Paper, Stack, Text } from "@mantine/core";
import { IconScale } from "@tabler/icons-react";
import { getComparePeopleReport } from "../api/reports";
import ComparePeopleDrillDrawer from "../components/ComparePeopleDrillDrawer";
import ComparePeopleMix from "../components/ComparePeopleMix";
import ComparePeoplePickers from "../components/ComparePeoplePickers";
import ComparePeopleTable from "../components/ComparePeopleTable";
import DistributionPanel from "../components/DistributionPanel";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportNote from "../components/ReportNote";
import ReportTailControl from "../components/ReportTailControl";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { COMPARE_CONTROLS, normalizeComparePeopleFilter } from "../utils/comparePeopleReport";
import { distributionPeople } from "../utils/distributionPeople";
import type { CellDrillTarget, DrillTarget, MixDrillTarget } from "../utils/reportDrill";
import { COMPARE_PEOPLE_COUNT, reportQuery, withFilterKey } from "../utils/reportFilter";
import { formatDays } from "../utils/reportFormat";

/** The focus-return targets of the page's cards: the table, the two cycle-time histograms, the work mix. */
const TABLE_ANCHOR = "compare-table";
const CYCLE_ANCHOR = "compare-cycle";
const MIX_ANCHOR = "compare-mix";

function anchorOf(target: DrillTarget): string {
  if (target.kind === "hist") return CYCLE_ANCHOR;
  return target.kind === "mix" ? MIX_ANCHOR : TABLE_ANCHOR;
}

/**
 * Report 18 — Compare people: two people head to head over a date range, across every team they belonged to (the other reports read a
 * person inside one team). The two are picked beside the filter bar (their `accountId`s ARE the URL, remembered like any filter); until
 * both are, the page is the pickers and an empty state, and nothing is requested. Then a table (Metric · A · B · Difference, the
 * better figure's cell tinted teal with a check, the difference unsigned), the two cycle-time histograms per person, and the work mix.
 * Every figure is a button to the items behind it. The capacity rows are in the response only for an editor of both people's teams.
 */
export default function ReportComparePeople() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "compare-people",
    getComparePeopleReport,
    normalizeComparePeopleFilter,
    COMPARE_CONTROLS,
  );
  const accountIds = filter.accountIds ?? [];
  const picked = accountIds.length === COMPARE_PEOPLE_COUNT;
  // With fewer than two people picked no report shows (kept data of an earlier pair would answer a question nobody asks); while a new pair
  // loads, the previous pair's report stays on screen dimmed (`isPlaceholderData`) and its figures are not drillable.
  const report = picked ? query.data : undefined;
  const drillable = report !== undefined && !query.isPlaceholderData;
  const drill = useReportDrill(reportQuery(filter), anchorOf);
  const names = Object.fromEntries((report?.people ?? []).map((person) => [person.accountId, person.label]));

  const openCell = (target: CellDrillTarget | MixDrillTarget, trigger: HTMLElement) => drill.open(target, { trigger });
  const openBar = (view: string) => (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) => {
    // Grouped bars are one person's each; a figure with no person (never drawn here) has nothing to list.
    if (segmentAccountId !== undefined) drill.open({ kind: "hist", view, bucketIndex, segmentAccountId }, { trigger, anchor: CYCLE_ANCHOR });
  };
  const cycle = report?.cycleTime;
  const people = cycle ? distributionPeople(cycle.workingDays) : [];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.comparePeople.title")} description={t("reports.comparePeople.description")} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ComparePeoplePickers
            accountIds={accountIds}
            names={names}
            onChange={(next) => setFilter(withFilterKey(filter, "accountIds", next.length === 0 ? null : next))}
          />
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={COMPARE_CONTROLS} />
          {!picked ? (
            <Paper withBorder p="md">
              <EmptyState icon={IconScale} label={t("reports.comparePeople.pickTwo")} />
            </Paper>
          ) : query.error ? (
            // One answer feeds every block, so a failure is said once, not in each card.
            <ErrorAlert error={query.error} />
          ) : (
            <>
              {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
              {report?.note && <ReportNote note={report.note} derivedAt={report.meta.derivedAt} />}
              <ReportTailControl filter={filter} onChange={setFilter} />
              <ReportChartCard
                title={t("reports.comparePeople.tableTitle")}
                caption={t("reports.comparePeople.tableCaption")}
                isPending={query.isPending}
                isRefreshing={query.isPlaceholderData}
                empty={false}
                hint={t("reports.drill.hintRows")}
                drillAnchor={TABLE_ANCHOR}
              >
                {report && (
                  <Stack gap="sm">
                    <ComparePeopleTable report={report} drill={{ onOpen: openCell, disabled: !drillable }} />
                    {report.capacity && (
                      <Text size="xs" c="dimmed">
                        {t("reports.comparePeople.capacityHint")} {t("reports.comparePeople.capacityDays", { count: report.capacity.days })}
                      </Text>
                    )}
                  </Stack>
                )}
              </ReportChartCard>
              <ReportChartCard
                title={t("reports.comparePeople.cycleTitle")}
                caption={t("reports.comparePeople.cycleCaption")}
                isPending={query.isPending}
                isRefreshing={query.isPlaceholderData}
                empty={false}
                drillAnchor={CYCLE_ANCHOR}
              >
                {report && cycle && (
                  <Stack gap="lg">
                    <DistributionPanel
                      title={t("reports.cycleTime.workingTitle")}
                      caption={t("reports.cycleTime.workingCaption")}
                      distribution={cycle.workingDays}
                      minSampleSize={report.meta.minSampleSize}
                      format={formatDays}
                      axisLabel={t("reports.cycleTime.workingAxis")}
                      people={people}
                      grouped
                      onDrill={openBar("CYCLE_WORKING_DAYS")}
                      drillDisabled={!drillable}
                    />
                    <DistributionPanel
                      title={t("reports.cycleTime.elapsedTitle")}
                      caption={t("reports.cycleTime.elapsedCaption")}
                      distribution={cycle.elapsedDays}
                      minSampleSize={report.meta.minSampleSize}
                      format={formatDays}
                      axisLabel={t("reports.cycleTime.elapsedAxis")}
                      people={distributionPeople(cycle.elapsedDays)}
                      grouped
                      onDrill={openBar("CYCLE_ELAPSED_DAYS")}
                      drillDisabled={!drillable}
                    />
                  </Stack>
                )}
              </ReportChartCard>
              <ReportChartCard
                title={t("reports.comparePeople.mixTitle")}
                caption={t("reports.comparePeople.mixCaption")}
                isPending={query.isPending}
                isRefreshing={query.isPlaceholderData}
                empty={false}
                hint={t("reports.drill.hintRows")}
                drillAnchor={MIX_ANCHOR}
              >
                {report && <ComparePeopleMix report={report} filters={filters} drill={{ onOpen: openCell, disabled: !drillable }} />}
              </ReportChartCard>
              {report && (
                <ComparePeopleDrillDrawer
                  report={report}
                  filters={filters}
                  filter={filter}
                  opened={drill.target !== null}
                  target={drill.shown}
                  onClose={drill.close}
                  onSegmentChange={drill.setSegment}
                />
              )}
            </>
          )}
        </>
      )}
    </Stack>
  );
}
