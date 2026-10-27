import { lazy, Suspense } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Alert, Stack, Text, Title } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import type { PersonTrendReport, PersonTrendRow, ReportFilters } from "../api/reports";
import { marginText, rowFormat } from "../utils/comparePeopleReport";
import type { CycleTrendRow } from "../utils/cycleTimeReport";
import { trendGlyph } from "../utils/homeTrend";
import { chartPointsOf, hasFigures, isPartial, mixRowsOf, TREND_CHARTS, trendOfCue } from "../utils/personTrendReport";
import DailyTableDisclosure from "./DailyTableDisclosure";
import LoadingBlock from "./LoadingBlock";
import { PersonTrendFigureTable, PersonTrendMixTable } from "./PersonTrendTables";
import ReportChartCard from "./ReportChartCard";
import TrendMark from "./TrendMark";

// recharts never enters the main bundle: each chart is its own lazy chunk.
const CycleTimeTrendChart = lazy(() => import("./CycleTimeTrendChart"));
const PersonTrendChart = lazy(() => import("./PersonTrendChart"));
const PersonTrendMixChart = lazy(() => import("./PersonTrendMixChart"));

/** What the row's direction says in words, for the card's caption. */
function directionText(row: PersonTrendRow, t: TFunction): string {
  if (row.direction === "TARGET") return t("reports.personTrend.direction.TARGET", { target: (row.target ?? 1).toFixed(1) });
  return t(`reports.personTrend.direction.${row.direction}`);
}

/**
 * The row's cue under the chart: the mark (arrow + word) of the first full window against the last, with the two window ends and the change in
 * the row's unit; or why there is none. `name` tells the two lines of the shared cycle chart apart.
 */
function CueLine({ row, name }: { row: PersonTrendRow; name?: string }) {
  const { t } = useTranslation();
  const cue = row.trend;
  const trend = trendOfCue(cue, row.tolerance);
  return (
    <Text size="sm" data-testid={`cue-${row.key}`}>
      {name !== undefined && <strong>{name}: </strong>}
      {cue === undefined ? (
        t("reports.personTrend.cue.none")
      ) : (
        <>
          {trendGlyph(trend) !== null && (
            <>
              <TrendMark trend={trend} wordOf={(tone) => (tone === "none" ? t("reports.personTrend.cue.unclear") : t(`reports.personTrend.cue.${tone}`))} /> ·{" "}
            </>
          )}
          {t("reports.personTrend.cue.between", { from: cue.fromEnd, to: cue.toEnd })}
          {cue.difference !== undefined && ` · ${t("reports.personTrend.cue.change", { gap: marginText(row, Math.abs(cue.difference), t) })}`}
        </>
      )}
    </Text>
  );
}

function TrendCard({ report, rows, isRefreshing }: { report: PersonTrendReport; rows: readonly PersonTrendRow[]; isRefreshing: boolean }) {
  const { t } = useTranslation();
  const first = rows[0];
  const cycle = rows.length > 1;
  const minSampleSize = report.meta.minSampleSize;
  const pointsOf = (row: PersonTrendRow) => chartPointsOf(report, row);
  const labels = rows.map((row) => t(`reports.comparePeople.row.${row.key}`));
  const title = cycle ? t("reports.personTrend.cycleTitle") : labels[0];
  const points = rows.map(pointsOf);
  const cycleRows: CycleTrendRow[] = points[0].map((point, index) => ({
    label: point.label,
    p50: point.value,
    p90: points[1]?.[index].value ?? null,
    n: point.n,
    partial: isPartial(point),
  }));
  return (
    <ReportChartCard
      title={title}
      caption={`${directionText(first, t)} ${t("reports.personTrend.cue.caption")}`}
      isPending={false}
      isRefreshing={isRefreshing}
      empty={false}
    >
      <Stack gap="sm">
        {rows.map((row, position) => (
          <CueLine key={row.key} row={row} name={cycle ? t(position === 0 ? "reports.cycleTime.series.p50" : "reports.cycleTime.series.p90") : undefined} />
        ))}
        {points.some(hasFigures) ? (
          <Suspense fallback={<LoadingBlock />}>
            {cycle ? (
              <CycleTimeTrendChart rows={cycleRows} />
            ) : (
              <PersonTrendChart
                points={points[0]}
                seriesLabel={title}
                axisLabel={t(`reports.personTrend.unit.${first.unit}`)}
                format={rowFormat(first)}
                minSampleSize={minSampleSize}
                domain={first.unit === "SHARE" ? [0, 1] : undefined}
              />
            )}
          </Suspense>
        ) : (
          // No window has a figure to draw: say so instead of an empty frame.
          <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
            {t("reports.personTrend.noFigures")}
          </Alert>
        )}
        <DailyTableDisclosure showLabel={t("reports.personTrend.table.show")} hideLabel={t("reports.personTrend.table.hide")}>
          <PersonTrendFigureTable rows={rows} seriesLabels={labels} pointsOf={pointsOf} minSampleSize={minSampleSize} label={t("reports.personTrend.table.label", { name: title })} />
        </DailyTableDisclosure>
      </Stack>
    </ReportChartCard>
  );
}

function MixPart({ by, report, filters }: { by: "activityType" | "workCategory"; report: PersonTrendReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const series = report.workMix[by];
  const title = t(`reports.comparePeople.mix.${by}`);
  if (series.length === 0) return null;
  const names = series.map((item) => (by === "workCategory" && item.key === "UNCATEGORIZED" ? t("reports.filters.uncategorized") : item.key));
  return (
    <Stack gap="xs">
      <Title order={4} size="h5">
        {title}
      </Title>
      <Suspense fallback={<LoadingBlock />}>
        <PersonTrendMixChart
          rows={mixRowsOf(series, report.windows)}
          names={names}
          label={title}
          axisLabel={t("reports.personTrend.mix.axis")}
          partialAt={report.windows.map((window) => window.coverage === "PARTIAL")}
        />
      </Suspense>
      <DailyTableDisclosure showLabel={t("reports.personTrend.table.show")} hideLabel={t("reports.personTrend.table.hide")}>
        <PersonTrendMixTable by={by} series={series} windows={report.windows} domains={filters.domains} label={t("reports.personTrend.table.label", { name: title })} />
      </DailyTableDisclosure>
    </Stack>
  );
}

/**
 * Person trend's cards: one chart per row in `TREND_CHARTS` order (the two cycle-time percentiles share one), each with its cue, the line and a
 * disclosure of the numbers, then the work mix as stacked shares. A row the server did not send (a newer client than server) is skipped.
 */
export default function PersonTrendSection({ report, filters, isRefreshing }: { report: PersonTrendReport; filters: ReportFilters; isRefreshing: boolean }) {
  const { t } = useTranslation();
  const mixEmpty = report.workMix.activityType.length === 0 && report.workMix.workCategory.length === 0;
  return (
    <>
      {TREND_CHARTS.map((chart) => {
        const rows = chart.keys.flatMap((key) => report.rows.filter((row) => row.key === key));
        return rows.length === chart.keys.length ? <TrendCard key={chart.id} report={report} rows={rows} isRefreshing={isRefreshing} /> : null;
      })}
      <ReportChartCard title={t("reports.personTrend.mix.title")} caption={t("reports.personTrend.mix.caption")} isPending={false} isRefreshing={isRefreshing} empty={false}>
        {mixEmpty ? (
          <Text size="sm" c="dimmed">
            {t("reports.personTrend.mix.empty")}
          </Text>
        ) : (
          <Stack gap="lg">
            <MixPart by="activityType" report={report} filters={filters} />
            <MixPart by="workCategory" report={report} filters={filters} />
          </Stack>
        )}
      </ReportChartCard>
    </>
  );
}
