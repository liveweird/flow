import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Alert, Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { getEpicProgressReport, type EpicProgressReport } from "../api/reports";
import DailyTableDisclosure from "../components/DailyTableDisclosure";
import EpicPlanPanel from "../components/EpicPlanPanel";
import EpicProgressBasisControl from "../components/EpicProgressBasisControl";
import EpicProgressBreadcrumb from "../components/EpicProgressBreadcrumb";
import EpicProgressRows from "../components/EpicProgressRows";
import EpicProgressTable from "../components/EpicProgressTable";
import EpicProgressTiles from "../components/EpicProgressTiles";
import EpicProgressTrendTable from "../components/EpicProgressTrendTable";
import LoadingBlock from "../components/LoadingBlock";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportNote from "../components/ReportNote";
import { useReportPage } from "../hooks/useReportPage";
import { applyBarChange, evmRows, hasOriginalPlan, normalizeEpicProgressFilter, TREND_WINDOW_DAYS } from "../utils/epicProgressReport";
import { FLOW_TABS } from "../utils/reportLinks";

// The chart (and with it recharts) rides its own lazy chunk.
const EpicProgressChart = lazy(() => import("../components/EpicProgressChart"));
const EpicProgressTrendChart = lazy(() => import("../components/EpicProgressTrendChart"));

// EVM is one of epic, domain or team (never a team × domain split), always the epic view, and has no
// user level: the bar offers the domain and the team — the last one touched wins — but no member. It is never read for the whole
// unit either: exactly one of a specific team or a specific domain (`teamRequired` + `teamOrDomain`, like WIP) — except the single-epic view
// (`epicId`, reached from the report's own drills), which `epicScope` lets stand in for both.
const CONTROLS: ReportControls = { teamRequired: true, teamOrDomain: true, epicScope: true, domain: true, domainExcludesTeam: true, noMember: true };

/** The card title: what the numbers are about, named by the report's own scope. */
function levelTitle(report: EpicProgressReport, t: TFunction): string {
  const { scope } = report;
  if (report.level === "UNIT" || scope === null) return t("reports.epicProgress.levelTitle.UNIT");
  const name = report.level === "EPIC" && scope.key && scope.name !== scope.key ? `${scope.key} · ${scope.name}` : scope.name;
  return t(`reports.epicProgress.levelTitle.${report.level}`, { name });
}

/** The first card's caption: the as-of day, and (on the period basis) the first day of the period it counts from. */
function asOfCaption(report: EpicProgressReport | undefined, t: TFunction): string | undefined {
  if (!report?.asOf.day) return undefined;
  if (report.basis === "PERIOD" && report.period.firstDay) {
    return t("reports.epicProgress.asOfPeriod", { from: report.period.firstDay, day: report.asOf.day });
  }
  return t("reports.epicProgress.asOf", { day: report.asOf.day });
}

/**
 * Report 15 — epic progress (EVM): planned value, earned value and actual cost as curves, with SV, SPI, CV, CPI, on the basis the
 * user picks — this period, or since the start (the server defaults per level: one epic since its start, everything else the
 * period) — plus the weekly SPI/CPI trend.
 */
export default function ReportEpicProgress() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "epic-progress",
    getEpicProgressReport,
    normalizeEpicProgressFilter,
    CONTROLS,
  );
  const report = query.data;
  const rows = report ? evmRows(report.series) : [];
  const withOriginal = report?.level === "EPIC" && hasOriginalPlan(rows);
  const basis = report?.basis ?? "CUMULATIVE";
  const periodBasis = basis === "PERIOD";
  // Neither a team nor a domain exists to read (the hook gives a team whenever there is one; a single epic needs neither): nothing to pick.
  // A domain but no team yet leaves the bar up with a prompt, since the query waits for a pick.
  const nothingToRead = filters !== undefined && filters.teams.length === 0 && filters.domains.length === 0 && filter.epicId === undefined;
  const needsPick = filters !== undefined && !nothingToRead && filter.teamId === undefined && filter.domain === undefined && filter.epicId === undefined;

  return (
    <Stack gap="md">
      <ReportPageHeader
        title={t("reports.epicProgress.title")}
        description={t("reports.epicProgress.description")}
        filter={filter}
        filters={filters}
        controls={CONTROLS}
        extraLines={report ? [{ label: t("reports.epicProgress.basis.label"), value: t(`reports.epicProgress.basis.${basis}`) }] : []}
      />
      <ReportPageShell
        tabs={FLOW_TABS}
        filtersQuery={filtersQuery}
        filters={filters}
        noTeamsLabel={t("reports.epicProgress.noTeams")}
        empty={nothingToRead}
      >
        {(filters) => (
          <>
            <ReportFilterBar
              storageKey={storageKey}
              filters={filters}
              filter={filter}
              onChange={(next) => setFilter(applyBarChange(filter, next))}
              controls={CONTROLS}
            />
            {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
            {report?.note && <ReportNote note={report.note} derivedAt={report.meta.derivedAt} />}
            {report && <EpicProgressBreadcrumb report={report} />}
            {needsPick ? (
              <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
                {t("reports.epicProgress.pickScope")}
              </Alert>
            ) : (
              <ReportChartCard
                title={report ? levelTitle(report, t) : t("reports.epicProgress.title")}
                caption={asOfCaption(report, t)}
                isPending={query.isPending}
                isRefreshing={query.isPlaceholderData}
                error={query.error}
                empty={report !== undefined && report.asOf.day === null}
              >
                {report && (
                  <Stack gap="md">
                    <EpicProgressBasisControl value={filter.basis ?? report.basis} onChange={(next) => setFilter({ ...filter, basis: next })} />
                    <EpicProgressTiles report={report} />
                    <Text size="xs" c="dimmed">
                      {t(`reports.epicProgress.levelNote.${report.level}`)}
                    </Text>
                  </Stack>
                )}
              </ReportChartCard>
            )}
            {report && report.series.length > 0 && (
              <ReportChartCard
                title={t(periodBasis ? "reports.epicProgress.chartTitlePeriod" : "reports.epicProgress.chartTitle")}
                caption={t(periodBasis ? "reports.epicProgress.chartCaptionPeriod" : "reports.epicProgress.chartCaption")}
                isPending={false}
                isRefreshing={query.isPlaceholderData}
                empty={false}
              >
                <Stack gap="md">
                  <Suspense fallback={<LoadingBlock />}>
                    <EpicProgressChart rows={rows} withOriginal={withOriginal} basis={basis} />
                  </Suspense>
                  <DailyTableDisclosure>
                    <EpicProgressTable rows={rows} withOriginal={withOriginal} basis={basis} />
                  </DailyTableDisclosure>
                </Stack>
              </ReportChartCard>
            )}
            {report && report.trend.length > 0 && (
              <ReportChartCard
                title={t("reports.epicProgress.trend.title")}
                caption={t("reports.epicProgress.trend.caption", { days: TREND_WINDOW_DAYS })}
                isPending={false}
                isRefreshing={query.isPlaceholderData}
                empty={false}
              >
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
                  <Suspense fallback={<LoadingBlock />}>
                    <EpicProgressTrendChart points={report.trend} />
                  </Suspense>
                  <EpicProgressTrendTable points={report.trend} />
                </SimpleGrid>
              </ReportChartCard>
            )}
            {report?.epic && (
              <ReportChartCard
                title={t("reports.epicProgress.epic.title")}
                isPending={false}
                isRefreshing={query.isPlaceholderData}
                empty={false}
              >
                <EpicPlanPanel epic={report.epic} timeZone={filters.timeZone} />
              </ReportChartCard>
            )}
            {report && (report.level === "UNIT" || report.level === "DOMAIN") && report.asOf.day !== null && (
              <Paper withBorder p="md">
                <EpicProgressRows report={report} filters={filters} />
              </Paper>
            )}
          </>
        )}
      </ReportPageShell>
    </Stack>
  );
}
