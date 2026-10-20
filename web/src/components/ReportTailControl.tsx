import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Group, Text } from "@mantine/core";
import {
  DEFAULT_TAIL_PERCENT,
  TAIL_PERCENT_CHOICES,
  withFilterKey,
  type ReportFilterState,
} from "../utils/reportFilter";
import ReportFilterSelect from "./ReportFilterSelect";

/**
 * The long-tail control of the eight distribution reports: the top N% of every histogram's values are folded into one final
 * "≥ X" bucket. It sits above the first histogram, not in the filter bar — it is no narrowing (the figures stay over every
 * item; only the buckets move), so it never counts on the bar's badge — but it is part of the request, the query key and the
 * URL (`tailPercent`, absent for the default). The page hands the same `filter`/`onChange` pair as the bar.
 */
export default function ReportTailControl({
  filter,
  onChange,
  strictTail = false,
}: {
  filter: ReportFilterState;
  onChange: (next: ReportFilterState) => void;
  /** The report's histograms use the fixed-width layout, whose open tail holds values strictly above X ("> X", Task estimates). */
  strictTail?: boolean;
}) {
  const { t } = useTranslation();
  const helpId = useId();
  const current = filter.tailPercent ?? DEFAULT_TAIL_PERCENT;
  // A hand-edited link may carry any integer in range: it must still show (and be the picked option), not a blank box.
  const choices = TAIL_PERCENT_CHOICES.includes(current) ? TAIL_PERCENT_CHOICES : [...TAIL_PERCENT_CHOICES, current].sort((a, b) => a - b);
  return (
    <Group gap="sm" align="flex-end" wrap="wrap" role="group" aria-label={t("reports.distribution.tail.label")} aria-describedby={helpId} data-print="hide">
      <ReportFilterSelect
        label={t("reports.distribution.tail.label")}
        data={choices.map((percent) => ({ value: String(percent), label: `${percent}%` }))}
        value={String(current)}
        onChange={(value) => {
          if (value === null) return;
          const percent = Number(value);
          onChange(withFilterKey(filter, "tailPercent", percent === DEFAULT_TAIL_PERCENT ? null : percent));
        }}
        required
      />
      <Text id={helpId} size="xs" c="dimmed" maw={480}>
        {t(strictTail ? "reports.distribution.tail.helpStrict" : "reports.distribution.tail.help")}
      </Text>
    </Group>
  );
}
