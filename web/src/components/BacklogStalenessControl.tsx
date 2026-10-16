import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Box, Group, Slider, Stack, Text } from "@mantine/core";
import {
  BACKLOG_STALENESS_OFF,
  BACKLOG_STALENESS_STEPS,
  DEFAULT_BACKLOG_STALENESS_DAYS,
  snapToStalenessStep,
  withFilterKey,
  type ReportFilterState,
} from "../utils/reportFilter";

// The slider runs over the INDEX of a stop, not the number of days (the steps are not evenly spaced): 0..6 are
// `BACKLOG_STALENESS_STEPS`, and the last stop is "no limit".
const NO_LIMIT_INDEX = BACKLOG_STALENESS_STEPS.length;
const DEFAULT_INDEX = BACKLOG_STALENESS_STEPS.findIndex((step) => step === DEFAULT_BACKLOG_STALENESS_DAYS);

/** The stops whose label is left out below the `xs` breakpoint (14, 60, 180 and 365 days), so the rest stay legible. */
const NARROW_HIDDEN_MARKS: readonly number[] = [1, 3, 5, 6];

/** The stop a filter sits on: absent = the default, 0 = no limit, any other number the nearest step (a hand-edited link). */
function indexOfFilter(filter: ReportFilterState): number {
  const days = filter.touchedWithinDays;
  if (days === undefined) return DEFAULT_INDEX;
  if (days === BACKLOG_STALENESS_OFF) return NO_LIMIT_INDEX;
  return BACKLOG_STALENESS_STEPS.findIndex((step) => step === snapToStalenessStep(days));
}

/** The days a stop stands for (`0` for "no limit"). */
function daysOfIndex(index: number): number {
  return index >= NO_LIMIT_INDEX ? BACKLOG_STALENESS_OFF : BACKLOG_STALENESS_STEPS[index];
}

function valueText(index: number, t: TFunction): string {
  if (index === NO_LIMIT_INDEX) return t("reports.backlog.staleness.noLimit");
  const days = t("reports.backlog.staleness.days", { count: BACKLOG_STALENESS_STEPS[index] });
  return index === DEFAULT_INDEX ? t("reports.backlog.staleness.defaultValue", { value: days }) : days;
}

/**
 * The estimated backlog's staleness slider: only the items somebody touched within the chosen number of days are counted, day by day.
 * It sits above the tiles, outside the filter bar (the user drags it while reading the figures), like `ReportTailControl`, and is part of
 * the request, the query key and the URL (`touchedWithinDays`, absent for the default 90, `0` for no limit). The thumb moves freely
 * while dragging (`onChange` only drives the label); the filter is written once, when the drag or the key press ends.
 */
export default function BacklogStalenessControl({
  filter,
  onChange,
}: {
  filter: ReportFilterState;
  onChange: (next: ReportFilterState) => void;
}) {
  const { t } = useTranslation();
  const helpId = useId();
  const committed = indexOfFilter(filter);
  // The thumb's own position while it is dragged; it follows the filter again whenever the filter changes (adjusting state during render).
  const [local, setLocal] = useState({ base: committed, index: committed });
  if (local.base !== committed) setLocal({ base: committed, index: committed });
  const shown = local.index;
  const label = t("reports.backlog.staleness.label");
  const current = valueText(shown, t);
  const marks = [...BACKLOG_STALENESS_STEPS.map(String), t("reports.backlog.staleness.noLimit")].map((text, index) => ({
    value: index,
    // At phone width eight labels run into each other: the in-between stops keep their tick, and the thumb tooltip names the value.
    label: NARROW_HIDDEN_MARKS.includes(index) ? (
      <Box component="span" visibleFrom="xs">
        {text}
      </Box>
    ) : (
      text
    ),
  }));

  return (
    <Stack gap={6} role="group" aria-label={label} aria-describedby={helpId}>
      <Group gap="xs" align="baseline" wrap="wrap">
        <Text size="sm" fw={500}>
          {label}
        </Text>
        <Text size="sm" c="dimmed" aria-hidden>
          {current}
        </Text>
      </Group>
      {/* Side padding keeps the end marks and the value tooltip inside the page at phone width; the track shrinks with the box. */}
      <Box w="100%" maw={400} px={40} pb="lg">
        <Slider
          min={0}
          max={NO_LIMIT_INDEX}
          step={1}
          marks={marks}
          restrictToMarks
          value={shown}
          label={(index) => valueText(index, t)}
          thumbLabel={label}
          thumbValueText={(index) => valueText(index, t)}
          onChange={(index) => setLocal((prev) => ({ ...prev, index }))}
          onChangeEnd={(index) => {
            setLocal((prev) => ({ ...prev, index }));
            if (index === committed) return;
            const days = daysOfIndex(index);
            onChange(withFilterKey(filter, "touchedWithinDays", days === DEFAULT_BACKLOG_STALENESS_DAYS ? null : days));
          }}
        />
      </Box>
      <Text id={helpId} size="xs" c="dimmed" maw={480}>
        {t("reports.backlog.staleness.help")}
      </Text>
    </Stack>
  );
}
