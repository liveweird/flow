import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Select } from "@mantine/core";
import { DatePickerInput } from "@mantine/dates";
import type { ReportFilterTeam } from "../api/reports";
import { usePeriodChoice } from "../hooks/usePeriodChoice";
import { todayIsoDate } from "../utils/isoDate";
import { DATE_PRESETS, LAST_SPRINT_COUNTS, withPeriod, type ReportFilterState } from "../utils/reportFilter";
import { pickerSprints } from "../utils/reportSprints";
import { sprintLabel } from "../utils/sprintLabel";

function CustomRange({
  filter,
  timeZone,
  horizonFrom,
  onChange,
}: {
  filter: ReportFilterState;
  timeZone: string;
  horizonFrom?: string | null;
  onChange: (next: ReportFilterState) => void;
}) {
  const { t } = useTranslation();
  // A range picker reports [start, null] after its first click; keep that half-choice local and
  // publish to the URL only once both ends exist.
  const [range, setRange] = useState<[string | null, string | null]>([filter.from ?? null, filter.to ?? null]);
  return (
    <DatePickerInput
      type="range"
      label={t("reports.filters.range")}
      valueFormat="YYYY-MM-DD"
      value={range}
      onChange={(next) => {
        setRange(next);
        const [from, to] = next;
        if (from && to) onChange(withPeriod(filter, { from, to }));
      }}
      w={230}
      minDate={horizonFrom ?? undefined}
      maxDate={todayIsoDate(timeZone)}
    />
  );
}

/**
 * The period controls: the preset/last-N-sprints/custom/one-sprint dropdown plus whichever second
 * control the choice needs (the date range, the sprint picker). Renders nothing for an "as of now"
 * report (`noPeriod`). "Today" and every preset are days in the configured zone — the one the
 * server reads from/to in.
 */
export default function ReportPeriodControls({
  filter,
  timeZone,
  horizonFrom,
  teams,
  datesOnly = false,
  onChange,
}: {
  filter: ReportFilterState;
  timeZone: string;
  /** The history horizon's first day (`filters.historyHorizon?.from`), if one is set: no earlier day is offered, and a preset starts no earlier. */
  horizonFrom?: string | null;
  teams: ReadonlyArray<ReportFilterTeam>;
  /** Offer the date presets and the custom range only — no sprint periods (Compare people reads two people across teams, and a sprint is one team's). */
  datesOnly?: boolean;
  onChange: (next: ReportFilterState) => void;
}) {
  const { t } = useTranslation();
  const sprints = pickerSprints(teams, filter);
  const { choice, changePeriod } = usePeriodChoice({ filter, today: todayIsoDate(timeZone), horizonFrom, sprints, onChange });

  const periodData = [
    {
      group: t("reports.filters.periodDates"),
      items: [
        ...DATE_PRESETS.map((preset) => ({ value: preset, label: t(`reports.filters.preset.${preset}`) })),
        { value: "custom", label: t("reports.filters.preset.custom") },
      ],
    },
    ...(datesOnly
      ? []
      : [
          {
            group: t("reports.filters.periodSprints"),
            items: [
              ...LAST_SPRINT_COUNTS.map((n) => ({
                value: `lastSprints:${n}`,
                label: t("reports.filters.lastSprints", { count: n }),
              })),
              { value: "sprint", label: t("reports.filters.oneSprint"), disabled: sprints.length === 0 },
            ],
          },
        ]),
  ];

  return (
    <>
      <Select
        label={t("reports.filters.period")}
        data={periodData}
        value={choice}
        onChange={changePeriod}
        allowDeselect={false}
        w={200}
      />
      {choice === "custom" && (
        // Keyed by the URL's range so Back/forward resyncs the picker's local half-choice.
        <CustomRange key={`${filter.from}|${filter.to}`} filter={filter} timeZone={timeZone} horizonFrom={horizonFrom} onChange={onChange} />
      )}
      {choice === "sprint" && (
        <Select
          label={t("reports.filters.sprint")}
          data={sprints.map((sprint) => ({ value: String(sprint.sprintId), label: sprintLabel(sprint, timeZone) }))}
          value={filter.sprintId === undefined ? null : String(filter.sprintId)}
          onChange={(value) => value !== null && onChange(withPeriod(filter, { sprintId: Number(value) }))}
          allowDeselect={false}
          w={300}
          maw="100%"
        />
      )}
    </>
  );
}
