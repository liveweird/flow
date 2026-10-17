import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, Stack, Text, Title } from "@mantine/core";
import type { AccuracyMeasures, Distribution } from "../api/reports";
import { personLabel } from "../utils/commitmentsReport";
import { histogramRowsWithShares, type DistributionPerson, type HistogramRowWithShares } from "../utils/distributionPeople";
import { histogramLabels, type ValueFormat } from "../utils/reportFormat";
import AccuracyMeasuresTable from "./AccuracyMeasuresTable";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import LoadingBlock from "./LoadingBlock";
import MinSampleNotice from "./MinSampleNotice";
import ScrollRegion from "./ScrollRegion";

// The histogram (and with it recharts) rides its own lazy chunk.
const DistributionHistogram = lazy(() => import("./DistributionHistogram"));

const MISSING = "—";

/** The account id behind a stacked segment's series name (`p<i>` = the i-th person); the whole-bucket series (`count`, `tail`) have none. */
function personOfSeries(series: string, people: readonly DistributionPerson[]): string | undefined {
  const match = /^p(\d+)$/.exec(series);
  return match ? people[Number(match[1])]?.accountId : undefined;
}

/**
 * One distribution: a percentile strip (median, p90, p95, mean, item count), the histogram and — the
 * chart's numbers — the histogram as a table. `strip="centre"` keeps only the median, the mean and the item count (the accuracy
 * pages read the spread from [accuracy] instead of the upper percentiles); `accuracy`, when given, adds the accuracy-measures table
 * between the strip and the histogram. `hidden` (fewer items than the minimum sample
 * size) replaces all of it with the MinSampleNotice: percentiles of three items would be noise.
 * `format` renders a value in the measure's own unit (a ratio, a percentage). In people mode (`people` = the distribution's
 * per-person split, numbered) the histogram stacks per person and its table gains one column per person beside the total.
 * `onDrill` (opt-in, per consumer) makes the bars drillable: a double-click on a bar or on one person's segment, or a count in the table (a person's count opens
 * their share), calls it with the bucket's index (and the person's account id for a segment) — the drill drawer is the page's.
 */
export default function DistributionPanel({
  title,
  name = title,
  caption,
  distribution,
  minSampleSize,
  format,
  axisLabel,
  people = [],
  strip: stripKind = "full",
  accuracy,
  onDrill,
  drillDisabled = false,
}: {
  title: string;
  /** What assistive technology calls this histogram (its group, table and figure buttons) when the visible [title] alone is not unique on the page. */
  name?: string;
  caption?: string;
  distribution: Distribution;
  minSampleSize: number;
  format: ValueFormat;
  /** The x-axis title of the histogram: what the ranges measure ("actual ÷ estimate"). */
  axisLabel: string;
  people?: readonly DistributionPerson[];
  /** `full` (default): median, p90, p95, mean, items; `centre`: median, mean, items. */
  strip?: "full" | "centre";
  /** The distribution's accuracy measures (estimate-vs-actual reports); none (or null while hidden) = no table. */
  accuracy?: AccuracyMeasures | null;
  /** Opens the items behind bucket `bucketIndex` of this histogram (a person's share of it when `segmentAccountId` is given). */
  onDrill?: (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) => void;
  /** The rows are kept data of a previous filter: the table figures are plain text, not buttons, and the bars ignore a double-click. */
  drillDisabled?: boolean;
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
    // The counts are the figures a bar stands behind: a person's count opens that person's share of the bucket, the total the whole bucket.
    ...people.map((person, index) =>
      drillFigureColumn<HistogramRowWithShares>(
        {
          key: `person-${index}`,
          header: personLabel(person.position, person.label),
          render: (row) => row.shares?.[index] ?? 0,
          align: "right",
        },
        {
          label: (row, figure, column) =>
            t("reports.drill.figureAria", { figure, column, name: `${name}: ${row.label} · ${personLabel(person.position, person.label)}` }),
          count: (row) => row.shares?.[index] ?? 0,
          onOpen: onDrill && ((row, trigger) => onDrill(Number(row.id), person.accountId, trigger)),
          disabled: drillDisabled,
        },
      ),
    ),
    drillFigureColumn<HistogramRowWithShares>(
      { key: "count", header: t("reports.distribution.count"), render: (row) => row.count, align: "right" },
      {
        label: (row, figure, column) => t("reports.drill.figureAria", { figure, column, name: `${name}: ${row.label}` }),
        count: (row) => row.count,
        onOpen: onDrill && ((row, trigger) => onDrill(Number(row.id), undefined, trigger)),
        disabled: drillDisabled,
      },
    ),
  ];
  const strip: [string, string][] = [
    [t("reports.distribution.p50"), show(distribution.p50)],
    ...(stripKind === "full"
      ? ([
          [t("reports.distribution.p90"), show(distribution.p90)],
          [t("reports.distribution.p95"), show(distribution.p95)],
        ] satisfies [string, string][])
      : []),
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
          {accuracy && <AccuracyMeasuresTable measures={accuracy} name={name} />}
          <Suspense fallback={<LoadingBlock />}>
            <DistributionHistogram
              rows={rows}
              name={name}
              xAxisLabel={axisLabel}
              people={people}
              onBarDoubleClick={
                onDrill && !drillDisabled ? ({ rowId, series }) => onDrill(Number(rowId), personOfSeries(series, people)) : undefined
              }
            />
          </Suspense>
          {onDrill && (
            <Text size="xs" c="dimmed">
              {t("reports.drill.hint")}
            </Text>
          )}
          <ScrollRegion label={`${t("reports.distribution.histogramTable")} — ${name}`} minWidth={260}>
            <ColumnTable
              verticalSpacing={4}
              aria-label={`${t("reports.distribution.histogramTable")} — ${name}`}
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
