import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, Stack, Table, Text, Title } from "@mantine/core";
import type { Distribution } from "../api/reports";
import { histogramLabels, type ValueFormat } from "../utils/reportFormat";
import LoadingBlock from "./LoadingBlock";
import MinSampleNotice from "./MinSampleNotice";
import ScrollRegion from "./ScrollRegion";

// The histogram (and with it recharts) rides its own lazy chunk.
const DistributionHistogram = lazy(() => import("./DistributionHistogram"));

const MISSING = "—";

/**
 * One distribution: a percentile strip (median, p90, p95, mean, item count), the histogram and — the
 * chart's numbers — the histogram as a table. `hidden` (fewer items than the minimum sample
 * size) replaces all of it with the MinSampleNotice: percentiles of three items would be noise.
 * `format` renders a value in the measure's own unit (a ratio, a percentage).
 */
export default function DistributionPanel({
  title,
  caption,
  distribution,
  minSampleSize,
  format,
  axisLabel,
}: {
  title: string;
  caption?: string;
  distribution: Distribution;
  minSampleSize: number;
  format: ValueFormat;
  /** The x-axis title of the histogram: what the ranges measure ("actual ÷ estimate"). */
  axisLabel: string;
}) {
  const { t } = useTranslation();
  const show = (value: number | null | undefined) => (value == null ? MISSING : format(value));
  const labels = histogramLabels(distribution.histogram, format);
  const rows = distribution.histogram.map((bucket, index) => ({ label: labels[index], count: bucket.count }));
  const strip: [string, string][] = [
    [t("reports.distribution.p50"), show(distribution.p50)],
    [t("reports.distribution.p90"), show(distribution.p90)],
    [t("reports.distribution.p95"), show(distribution.p95)],
    [t("reports.distribution.mean"), show(distribution.mean)],
    [t("reports.distribution.n"), String(distribution.n)],
  ];
  return (
    <Stack gap="sm">
      <Stack gap={0}>
        <Title order={5} size="h6">
          {title}
        </Title>
        {caption && (
          <Text size="xs" c="dimmed">
            {caption}
          </Text>
        )}
      </Stack>
      {distribution.hidden ? (
        <MinSampleNotice n={distribution.n} minSampleSize={minSampleSize} />
      ) : (
        <>
          <Group gap="lg" role="group" aria-label={t("reports.distribution.stripLabel")}>
            {strip.map(([label, value]) => (
              <Box key={label}>
                <Text size="xs" c="dimmed">
                  {label}
                </Text>
                <Text fw={600}>{value}</Text>
              </Box>
            ))}
          </Group>
          <Suspense fallback={<LoadingBlock />}>
            <DistributionHistogram rows={rows} name={title} xAxisLabel={axisLabel} />
          </Suspense>
          <ScrollRegion label={`${t("reports.distribution.histogramTable")} — ${title}`} minWidth={260}>
            <Table verticalSpacing={4} aria-label={`${t("reports.distribution.histogramTable")} — ${title}`}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("reports.distribution.range")}</Table.Th>
                  <Table.Th ta="right">{t("reports.distribution.count")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row, index) => (
                  // Keyed by position: ranges are ordered and fixed, and a label is not a guaranteed identity.
                  <Table.Tr key={index}>
                    <Table.Td>{row.label}</Table.Td>
                    <Table.Td ta="right">{row.count}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollRegion>
        </>
      )}
    </Stack>
  );
}
