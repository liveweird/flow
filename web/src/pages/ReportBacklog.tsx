import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { SimpleGrid, Stack } from "@mantine/core";
import { getBacklogReport, type BacklogReport } from "../api/reports";
import BacklogStalenessControl from "../components/BacklogStalenessControl";
import BacklogTable from "../components/BacklogTable";
import DailyTableDisclosure from "../components/DailyTableDisclosure";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportNote from "../components/ReportNote";
import ReportTile from "../components/ReportTile";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { backlogRows, roundSprints, sprintsGap } from "../utils/backlogReport";
import { formatMd } from "../utils/reportFormat";
import {
  DEFAULT_BACKLOG_STALENESS_DAYS,
  dropDomainWithTeam,
  dropMember,
  dropReportSpecific,
  snapBacklogStaleness,
  type ReportFilterState,
} from "../utils/reportFilter";
import { backlogPath, FLOW_TABS, reportSpecificKeep } from "../utils/reportLinks";

// The trend chart (and with it recharts) rides its own lazy chunk.
const BacklogTrendChart = lazy(() => import("../components/BacklogTrendChart"));

// The backlog reads the task's own domain (no domain view), takes a domain but never one WITH a
// team (no team × domain split in the daily aggregate), answers 400 to activity type/work category and is owned by a team, so it
// has no member control (the server answers a member with an empty block and a note).
const CONTROLS: ReportControls = { domain: true, domainExcludesTeam: true, noMember: true };

const MISSING = "—";

/** "≈ N sprints ahead"; a positive figure that rounds to 0 reads "< 0.1", never "≈ 0"; null is a dash. */
function sprintsValue(sprints: number | null | undefined, t: TFunction): string {
  if (sprints == null) return MISSING;
  const rounded = roundSprints(sprints);
  return sprints > 0 && sprints < 0.05 ? t("reports.backlog.lessThanTenth") : t("reports.backlog.sprintsAhead", { count: rounded });
}

/** The "≈ N sprints ahead" tile (report 13): the figure, or a dash with the reason it is missing. */
function SprintsTile({ report }: { report: BacklogReport }) {
  const { t } = useTranslation();
  const { current } = report;
  const gap = sprintsGap(current);
  const hints: string[] = [];
  if (gap !== null) {
    hints.push(t(`reports.backlog.${gap}`));
  } else if (current.meanDeliveredMd != null) {
    // At UNIT level the pace is a SUM of per-team means and `sprintsUsed` the MINIMUM across the teams.
    const unit = report.meta.level === "UNIT";
    const basis = { count: current.sprintsUsed, md: formatMd(current.meanDeliveredMd), window: current.windowSprints };
    hints.push(unit ? t("reports.backlog.unitBasis", basis) : t("reports.backlog.basis", basis));
    if (current.sprintsUsed < current.windowSprints) {
      hints.push(unit ? t("reports.backlog.unitBasisShort") : t("reports.backlog.basisShort"));
    }
    if (unit) hints.push(t("reports.backlog.unitNote"));
  }
  return (
    <ReportTile
      label={t("reports.backlog.tile.sprints")}
      value={sprintsValue(current.backlogInSprints, t)}
      hint={hints.join(" ")}
    />
  );
}

/** Off the request and the URL: params the page has no control for, a pasted member, the team-over-domain rule, then a staleness window off the slider's steps (snapped). */
const normalizeBacklogFilter = (filter: ReportFilterState) =>
  snapBacklogStaleness(dropDomainWithTeam(dropMember(dropReportSpecific(filter, reportSpecificKeep(backlogPath)))));

/** The Items tile's note: how many items the staleness window leaves out today; nothing when the window is off. */
function hiddenHint(report: BacklogReport, t: TFunction): string | undefined {
  const days = report.touchedWithinDays;
  if (days == null) return undefined;
  const { hiddenItems, hiddenMd } = report.current;
  return hiddenItems > 0
    ? t("reports.backlog.staleness.hidden", { count: hiddenItems, md: formatMd(hiddenMd), days })
    : t("reports.backlog.staleness.nothingHidden", { days });
}

/** Reports 10 and 13 — the estimated backlog over time, and how many sprints of delivery it amounts to. */
export default function ReportBacklog() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "backlog",
    // The default window is always SENT (the URL stays bare for it); `0` = no limit goes through as it is.
    (requested) => getBacklogReport({ ...requested, touchedWithinDays: requested.touchedWithinDays ?? DEFAULT_BACKLOG_STALENESS_DAYS }),
    normalizeBacklogFilter,
  );
  const report = query.data;
  const rows = report ? backlogRows(report.trend) : [];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.backlog.title")} description={t("reports.backlog.description")} />
      <ReportTabs tabs={FLOW_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          {report?.note && <ReportNote note={report.note} derivedAt={report.meta.derivedAt} />}
          <BacklogStalenessControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={
              report?.current.asOfDay ? t("reports.backlog.currentTitle", { day: report.current.asOfDay }) : t("reports.backlog.title")
            }
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.trend.length === 0}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
                <ReportTile label={t("reports.backlog.tile.md")} value={formatMd(report.current.md)} />
                <ReportTile label={t("reports.backlog.tile.items")} value={String(report.current.items)} hint={hiddenHint(report, t)} />
                <SprintsTile report={report} />
              </SimpleGrid>
            )}
          </ReportChartCard>
          {report && report.trend.length > 0 && (
            <ReportChartCard
              title={t("reports.backlog.chartTitle")}
              caption={t("reports.backlog.chartCaption")}
              isPending={false}
              isRefreshing={query.isPlaceholderData}
              empty={false}
            >
              <Stack gap="md">
                <Suspense fallback={<LoadingBlock />}>
                  <BacklogTrendChart rows={rows} />
                </Suspense>
                <DailyTableDisclosure>
                  <BacklogTable rows={rows} />
                </DailyTableDisclosure>
              </Stack>
            </ReportChartCard>
          )}
        </>
      )}
    </Stack>
  );
}
