import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Switch, Text } from "@mantine/core";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import type { AgingWipReport } from "../api/reports";
import { AGING_BAND_COLORS, agingPlot, type AgingKind } from "../utils/agingChart";
import { bandPercentileLabel } from "../utils/agingReport";
import { AgingDotGlyph } from "./AgingDotMark";
import ChartLegendList, { type LegendEntry } from "./ChartLegendList";
import LoadingBlock from "./LoadingBlock";
import MinSampleNotice from "./MinSampleNotice";

const AgingScatterChart = lazy(() => import("./AgingScatterChart"));

/**
 * The aging chart's card body for the kind the page's Tasks | Epics switch picked (their thresholds are different scales, so they
 * are never one plot): the log-scale switch (remembered per viewer), the hidden-thresholds note, the dot legend (bands, waiting,
 * blocked), the lazy chart, and the truncation note. The table below the card is the chart's text alternative and where a clicked
 * dot lands.
 */
export default function AgingChartSection({
  report,
  kind,
  selectedKey,
  onSelect,
}: {
  report: AgingWipReport;
  kind: AgingKind;
  selectedKey: string | null;
  onSelect: (key: string) => void;
}) {
  const { t } = useTranslation();
  // Remembered per viewer: real data has one very old item that squashes a linear axis, so the choice should stick.
  const [logScale, setLogScale] = useStoredState<boolean>("reports.agingWip.logScale", false, isBoolean);
  const plot = agingPlot(report, kind);
  const thresholds = kind === "EPIC" ? report.epicThresholds : report.thresholds;

  const ranked = [...thresholds.percentiles].sort((a, b) => a.percentile - b.percentile);
  const top = ranked.at(-1);
  const second = ranked.at(-2);
  const lowerBoundary = second ?? top;
  const entries: LegendEntry[] = thresholds.hidden || top === undefined
    ? [{ key: "none", glyph: <AgingDotGlyph color={AGING_BAND_COLORS.gray} />, text: t("reports.agingWip.chart.legend.noBand") }]
    : [
        { key: "red", glyph: <AgingDotGlyph color={AGING_BAND_COLORS.red} />, text: t("reports.agingWip.band.above", { p: bandPercentileLabel(`P${top.percentile}`) }) },
        ...(second === undefined
          ? []
          : [{ key: "orange", glyph: <AgingDotGlyph color={AGING_BAND_COLORS.orange} />, text: t("reports.agingWip.band.above", { p: bandPercentileLabel(`P${second.percentile}`) }) }]),
        {
          key: "gray",
          glyph: <AgingDotGlyph color={AGING_BAND_COLORS.gray} />,
          text: t("reports.agingWip.chart.legend.upTo", { p: bandPercentileLabel(`P${(lowerBoundary ?? top).percentile}`) }),
        },
      ];
  entries.push(
    { key: "waiting", glyph: <AgingDotGlyph color={AGING_BAND_COLORS.gray} waiting />, text: t("reports.agingWip.chart.legend.waiting") },
    { key: "blocked", glyph: <AgingDotGlyph color={AGING_BAND_COLORS.gray} blocked />, text: t("reports.agingWip.chart.legend.blocked") },
  );

  return (
    <Stack gap="sm">
      <Switch size="xs" wrapperProps={{ "data-print": "hide" }} label={t("reports.agingWip.chart.logScale")} checked={logScale} onChange={(event) => setLogScale(event.currentTarget.checked)} />
      {thresholds.hidden && <MinSampleNotice n={thresholds.n} minSampleSize={report.meta.minSampleSize} subject="thresholds" />}
      <ChartLegendList label={t("reports.agingWip.chart.legendLabel")} entries={entries} />
      <Suspense fallback={<LoadingBlock />}>
        <AgingScatterChart plot={plot} thresholds={thresholds} selectedKey={selectedKey} onSelect={onSelect} logScale={logScale} />
      </Suspense>
      <Text size="xs" c="dimmed">
        {t("reports.agingWip.chart.hint")}
      </Text>
      {logScale && (
        <Text size="xs" c="dimmed">
          {t("reports.agingWip.chart.logHint")}
        </Text>
      )}
      {report.itemsTruncated && (
        <Text size="xs" c="dimmed">
          {t("reports.agingWip.truncated", { count: plot.items.length })}
        </Text>
      )}
    </Stack>
  );
}
