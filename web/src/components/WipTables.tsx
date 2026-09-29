import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";
import type { WipPoint } from "../api/reports";
import { wipBandSummary, type WipBand } from "../utils/wipReport";

/** The chart's text alternative: per band, the latest day's count, the daily average and the peak. */
export function WipSummaryTable({ series, bands }: { series: readonly WipPoint[]; bands: readonly WipBand[] }) {
  const { t } = useTranslation();
  const latestDay = series.at(-1)?.day ?? "";
  return (
    <Table.ScrollContainer minWidth={320}>
      <Table verticalSpacing={4} aria-label={t("reports.wip.summaryLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.wip.column.band")}</Table.Th>
            <Table.Th ta="right">{t("reports.wip.column.latest", { day: latestDay })}</Table.Th>
            <Table.Th ta="right">{t("reports.wip.column.average")}</Table.Th>
            <Table.Th ta="right">{t("reports.wip.column.peak")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {bands.map((band) => {
            const summary = wipBandSummary(series, band.key);
            return (
              <Table.Tr key={band.name}>
                <Table.Td>{band.label}</Table.Td>
                <Table.Td ta="right">{summary.latest}</Table.Td>
                <Table.Td ta="right">{summary.average}</Table.Td>
                <Table.Td ta="right">{summary.peak}</Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** Every day of the series, newest first: the day and each band's end-of-day count. */
export function WipDailyTable({ series, bands }: { series: readonly WipPoint[]; bands: readonly WipBand[] }) {
  const { t } = useTranslation();
  return (
    <Table.ScrollContainer minWidth={320} maxHeight={360}>
      <Table verticalSpacing={4} stickyHeader aria-label={t("reports.wip.dailyLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.daily.day")}</Table.Th>
            {bands.map((band) => (
              <Table.Th key={band.name} ta="right">
                {band.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {[...series].reverse().map((point) => (
            <Table.Tr key={point.day}>
              <Table.Td>{point.day}</Table.Td>
              {bands.map((band) => (
                <Table.Td key={band.name} ta="right">
                  {point.counts[band.key] ?? 0}
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
