import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, Stack, Text, Title } from "@mantine/core";
import type { Distribution } from "../api/reports";
import { personLabel } from "../utils/commitmentsReport";
import { histogramRowsWithShares, type DistributionPerson, type HistogramRowWithShares } from "../utils/distributionPeople";
import { histogramLabels, type ValueFormat } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
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
 * `format` renders a value in the measure's own unit (a ratio, a percentage). In people mode (`people` = the distribution's
 * per-person split, numbered) the histogram stacks per person and its table gains one column per person beside the total.
 */
export default function DistributionPanel({
  title,
  caption,
  distribution,
  minSampleSize,
  format,
  axisLabel,
  people = [],
}: {
  title: string;
  caption?: string;
  distribution: Distribution;
  minSampleSize: number;
  format: ValueFormat;
  /** The x-axis title of the histogram: what the ranges measure ("actual ÷ estimate"). */
  axisLabel: string;
  people?: readonly DistributionPerson[];
}) {
  const { t } = useTranslation();
  const show = (value: number | null | undefined) => (value == null ? MISSING : format(value));
  const labels = histogramLabels(distribution.histogram, format);
  // The final open bucket (`to` null) is the long tail: its row and bar say so in words, not only in a gray tone.
  const rows = histogramRowsWithShares(distribution, labels);
  const columns: ColumnDef<HistogramRowWithShares>[] = [
    {
      key: "range",
      header: t("reports.distribution.range"),
      render: (row) =>
        row.open ? (
          <>
            {row.label}{" "}
            <Text span size="xs" c="dimmed">
              ({t("reports.distribution.tailRow")})
            </Text>
          </>
        ) : (
          row.label
        ),
    },
    ...people.map((person, index) => ({
      key: `person-${index}`,
      header: personLabel(person.position, person.label),
      render: (row: HistogramRowWithShares) => row.shares?.[index] ?? 0,
      align: "right" as const,
    })),
    { key: "count", header: t("reports.distribution.count"), render: (row) => row.count, align: "right" },
  ];
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
            <DistributionHistogram rows={rows} name={title} xAxisLabel={axisLabel} people={people} />
          </Suspense>
          <ScrollRegion label={`${t("reports.distribution.histogramTable")} — ${title}`} minWidth={260}>
            <ColumnTable
              verticalSpacing={4}
              aria-label={`${t("reports.distribution.histogramTable")} — ${title}`}
              columns={columns}
              rows={rows}
              // Keyed by position: ranges are ordered and fixed, and a label is not a guaranteed identity.
              rowKey={(_row, index) => String(index)}
            />
          </ScrollRegion>
        </>
      )}
    </Stack>
  );
}
