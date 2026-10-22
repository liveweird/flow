import "@mantine/charts/styles.css";
import { DonutChart } from "@mantine/charts";
import { Box, Paper, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { formatMd, formatPercent } from "../utils/reportFormat";

/** One drawn slice: its words, figure, share and colour; [includes] lists the keys folded into "Other" (the tooltip repeats them). */
export interface DonutEntry {
  id: string;
  label: string;
  md: number;
  share: number;
  color: string;
  includes?: string;
}

/** What recharts hands the tooltip: the hovered pie datum, which carries the `id` of the entry it was drawn from. */
interface TooltipEntry {
  payload?: { id?: string };
}

/** The hover card of a slice: name, man-days and share — and, for "Other", what it folds. */
function SliceTooltip({ entry, entries }: { entry: TooltipEntry | undefined; entries: readonly DonutEntry[] }) {
  const { t } = useTranslation();
  const slice = entries.find((candidate) => candidate.id === entry?.payload?.id);
  if (!slice) return null;
  return (
    <Paper withBorder shadow="md" p="xs" maw={280}>
      <Text size="sm" fw={500}>
        {slice.label}
      </Text>
      <Text size="sm">{t("reports.costs.breakdown.figure", { md: formatMd(slice.md), share: formatPercent(slice.share) })}</Text>
      {slice.includes && (
        <Text size="xs" c="dimmed">
          {t("reports.costs.breakdown.includes", { keys: slice.includes })}
        </Text>
      )}
    </Paper>
  );
}

/**
 * The donut's cells. Mantine keys a segment by its name, so a duplicate name would collide: a repeat (two keys that read alike) gets its
 * position appended. The `id` rides along (Mantine spreads the cell into the pie datum) so the hover card finds its entry by id, never by name.
 */
function cellsOf(entries: readonly DonutEntry[]) {
  const seen = new Set<string>();
  return entries.map((entry, index) => {
    const name = seen.has(entry.label) ? `${entry.label} (${index + 1})` : entry.label;
    seen.add(name);
    return { name, value: entry.md, color: entry.color, id: entry.id };
  });
}

/**
 * A donut of one cost breakdown (activity type or work category): a part-of-whole in man-days, a slice per entry on the
 * categorical palette (gray for "Other"), ONE axis-less mark set. A click on a slice opens its tasks. The legend table beside it
 * (the page's) carries every figure and is the keyboard and screen-reader route — recharts' sectors take no focus. A lazy chunk:
 * recharts never enters the main bundle.
 */
export default function CostBreakdownChart({
  entries,
  label,
  onSelect,
}: {
  entries: readonly DonutEntry[];
  label: string;
  onSelect: (id: string) => void;
}) {
  return (
    <Box role="group" aria-label={label}>
      <DonutChart
        size={200}
        thickness={36}
        mx="auto"
        data={cellsOf(entries)}
        withTooltip
        tooltipDataSource="segment"
        strokeWidth={2}
        cellProps={{ style: { cursor: "pointer" } }}
        pieProps={{ onClick: (_slice, index) => onSelect(entries[index].id) }}
        tooltipProps={{ content: ({ payload }) => <SliceTooltip entry={payload?.[0] as TooltipEntry | undefined} entries={entries} /> }}
      />
    </Box>
  );
}
