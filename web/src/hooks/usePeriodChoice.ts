import { useState } from "react";
import type { ReportFilterTeam } from "../api/reports";
import {
  activePeriodChoice,
  presetRange,
  withPeriod,
  type DATE_PRESETS,
  type PeriodChoice,
  type ReportFilterState,
} from "../utils/reportFilter";

/**
 * The period dropdown's state: the choice it shows and the handler that turns a pick into a whole
 * next filter. "Custom range" can coincide with a preset's dates (the range it starts from), so
 * picking it is remembered here rather than re-derived from the URL, which cannot tell the two apart.
 */
export function usePeriodChoice({
  filter,
  today,
  sprints,
  onChange,
}: {
  filter: ReportFilterState;
  today: string;
  /** The picked team's sprints, newest first — "one sprint" starts on the newest completed one. */
  sprints: ReadonlyArray<ReportFilterTeam["sprints"][number]>;
  onChange: (next: ReportFilterState) => void;
}): { choice: PeriodChoice; changePeriod: (value: string | null) => void } {
  const [customPicked, setCustomPicked] = useState(false);
  const derivedChoice = activePeriodChoice(filter, today);
  const choice: PeriodChoice =
    customPicked && derivedChoice !== "sprint" && !derivedChoice.startsWith("lastSprints:") ? "custom" : derivedChoice;

  const changePeriod = (value: string | null) => {
    if (value === null) return;
    const picked = value as PeriodChoice;
    setCustomPicked(picked === "custom");
    if (picked === "custom") {
      const range = filter.from === undefined && filter.to === undefined ? presetRange("last90", today) : filter;
      onChange(withPeriod(filter, { from: range.from, to: range.to }));
    } else if (picked === "sprint") {
      // The newest sprint that has actually completed; an open one only when none has.
      const latest = sprints.find((sprint) => sprint.completeAt != null) ?? sprints[0];
      if (latest) onChange(withPeriod(filter, { sprintId: latest.sprintId }));
    } else if (picked.startsWith("lastSprints:")) {
      onChange(withPeriod(filter, { lastSprints: Number(picked.slice("lastSprints:".length)) }));
    } else {
      onChange(withPeriod(filter, presetRange(picked as (typeof DATE_PRESETS)[number], today)));
    }
  };

  return { choice, changePeriod };
}
