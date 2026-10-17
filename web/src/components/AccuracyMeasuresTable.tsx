import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Stack, Title, Tooltip } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import type { AccuracyMeasures } from "../api/reports";
import { formatPercent, formatRatio, type ValueFormat } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

const MISSING = "—";

/** One row per measure, in reading order: how often the estimate was close, how big a miss typically is, then the spread and the tail. */
const MEASURES: ReadonlyArray<{ key: keyof AccuracyMeasures; format: ValueFormat }> = [
  { key: "pred10", format: formatPercent },
  { key: "pred25", format: formatPercent },
  { key: "pred50", format: formatPercent },
  { key: "mdmre", format: formatPercent },
  { key: "smape", format: formatPercent },
  { key: "iqr", format: formatRatio },
  { key: "idr", format: formatRatio },
  { key: "mad", format: formatRatio },
  { key: "tailShare", format: formatPercent },
];

/**
 * The accuracy measures of one estimate-vs-actual distribution as a two-column table (measure, value). Each measure's one-line
 * meaning sits behind an info icon beside its name (a tooltip on hover and keyboard focus), not in the row. Fractions
 * read as percentages and the spreads as ratios; the tail share is null for a sample with no spread and reads "—". [name] is the
 * distribution's title, so the two views of a page are told apart by their table names.
 */
export default function AccuracyMeasuresTable({ measures, name }: { measures: AccuracyMeasures; name: string }) {
  const { t } = useTranslation();
  const title = `${t("reports.accuracy.title")} — ${name}`;
  const columns: ColumnDef<(typeof MEASURES)[number]>[] = [
    {
      key: "measure",
      header: t("reports.accuracy.column.measure"),
      render: (row) => {
        const label = t(`reports.accuracy.label.${row.key}`);
        return (
          <Group gap={4} wrap="nowrap">
            {label}
            <Tooltip label={t(`reports.accuracy.meaning.${row.key}`)} multiline w={320} events={{ hover: true, focus: true, touch: true }}>
              <ActionIcon variant="subtle" color="gray" size={24} aria-label={t("reports.accuracy.about", { measure: label })}>
                <IconInfoCircle size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        );
      },
    },
    {
      key: "value",
      header: t("reports.accuracy.column.value"),
      render: (row) => {
        const value = measures[row.key];
        return value === null ? MISSING : row.format(value);
      },
      align: "right",
    },
  ];
  return (
    <Stack gap={4}>
      <Title order={5} size="h6">
        {t("reports.accuracy.title")}
      </Title>
      <ScrollRegion label={title} minWidth={420}>
        <ColumnTable verticalSpacing={4} aria-label={title} columns={columns} rows={MEASURES} rowKey={(row) => row.key} />
      </ScrollRegion>
    </Stack>
  );
}
