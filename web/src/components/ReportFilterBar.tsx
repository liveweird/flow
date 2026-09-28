import "@mantine/dates/styles.css";
import "dayjs/locale/pl";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, Select, SegmentedControl, Stack, Text } from "@mantine/core";
import { DatePickerInput, DatesProvider } from "@mantine/dates";
import type { ReportFilters, ReportFilterTeam } from "../api/reports";
import { todayIsoDate } from "../utils/isoDate";
import {
  activePeriodChoice,
  BREAKDOWNS,
  DATE_PRESETS,
  LAST_SPRINT_COUNTS,
  presetRange,
  withPeriod,
  type Breakdown,
  type DomainView,
  type PeriodChoice,
  type ReportFilterState,
} from "../utils/reportFilter";

/**
 * Which optional controls a report offers — the bar shows a control ONLY where the report uses
 * it (velocity ignores domain/activity/category/breakdown, so it passes none). `domainView` is
 * the report's own default view (D3), its presence turns the "delivered in / earned in" toggle on.
 */
export interface ReportControls {
  domainView?: DomainView;
  domain?: boolean;
  activityType?: boolean;
  workCategory?: boolean;
  breakdown?: boolean;
}

const UNCATEGORIZED = "UNCATEGORIZED";

/** A team's sprints, newest first (completion, else start). */
function sprintsNewestFirst(team: ReportFilterTeam | undefined) {
  return [...(team?.sprints ?? [])].sort(
    (a, b) => (b.completeAt ?? b.startAt ?? 0) - (a.completeAt ?? a.startAt ?? 0) || b.sprintId - a.sprintId,
  );
}

function CustomRange({
  filter,
  timeZone,
  onChange,
}: {
  filter: ReportFilterState;
  timeZone: string;
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
      maxDate={todayIsoDate(timeZone)}
    />
  );
}

/**
 * The reports filter bar: period presets + custom range + per-team sprint picker, the org drill
 * (team → member) and the report-specific extras. Pure controlled component — the URL state lives
 * in `useReportFilter`; every control produces a whole next filter through `onChange`.
 */
export default function ReportFilterBar({
  filters,
  filter,
  onChange,
  controls = {},
}: {
  filters: ReportFilters;
  filter: ReportFilterState;
  onChange: (next: ReportFilterState) => void;
  controls?: ReportControls;
}) {
  const { t, i18n } = useTranslation();
  // "Today" and every preset are days in the configured zone — the one the server reads from/to in.
  const today = todayIsoDate(filters.timeZone);
  const team = filters.teams.find((candidate) => candidate.id === filter.teamId);
  // A sprint link without a team: the sprint picker still needs the team that lists it.
  const sprintTeam =
    team ??
    (filter.sprintId === undefined
      ? undefined
      : filters.teams.find((candidate) => candidate.sprints.some((sprint) => sprint.sprintId === filter.sprintId)));
  const sprints = sprintsNewestFirst(sprintTeam);
  // "Custom range" can coincide with a preset's dates (the range it starts from), so picking it is
  // remembered here rather than re-derived from the URL, which cannot tell the two apart.
  const [customPicked, setCustomPicked] = useState(false);
  const derivedChoice = activePeriodChoice(filter, today);
  const choice: PeriodChoice =
    customPicked && derivedChoice !== "sprint" && !derivedChoice.startsWith("lastSprints:") ? "custom" : derivedChoice;

  const setKey = <K extends keyof ReportFilterState>(key: K, value: ReportFilterState[K] | null) => {
    const next = { ...filter };
    if (value === null || value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  };

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

  const changeTeam = (value: string | null) => {
    const next: ReportFilterState = { ...filter };
    delete next.accountId;
    if (value === null) {
      delete next.teamId;
    } else {
      next.teamId = Number(value);
    }
    // A sprint belongs to one team — it survives only a change to a team that lists it.
    const stillListed = filters.teams
      .find((candidate) => String(candidate.id) === value)
      ?.sprints.some((sprint) => sprint.sprintId === filter.sprintId);
    onChange(filter.sprintId !== undefined && !stillListed ? withPeriod(next, {}) : next);
  };

  const periodData = [
    {
      group: t("reports.filters.periodDates"),
      items: [
        ...DATE_PRESETS.map((preset) => ({ value: preset, label: t(`reports.filters.preset.${preset}`) })),
        { value: "custom", label: t("reports.filters.preset.custom") },
      ],
    },
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
  ];

  return (
    <DatesProvider settings={{ locale: i18n.resolvedLanguage ?? "en", firstDayOfWeek: 1 }}>
      <Stack gap="sm">
        <Group gap="sm" align="flex-end" wrap="wrap" role="group" aria-label={t("reports.filters.title")}>
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
            <CustomRange
              key={`${filter.from}|${filter.to}`}
              filter={filter}
              timeZone={filters.timeZone}
              onChange={onChange}
            />
          )}
          {choice === "sprint" && (
            <Select
              label={t("reports.filters.sprint")}
              data={sprints.map((sprint) => ({ value: String(sprint.sprintId), label: sprint.name }))}
              value={filter.sprintId === undefined ? null : String(filter.sprintId)}
              onChange={(value) => value !== null && onChange(withPeriod(filter, { sprintId: Number(value) }))}
              allowDeselect={false}
              w={200}
            />
          )}
          <Select
            label={t("reports.filters.team")}
            placeholder={t("reports.filters.allTeams")}
            data={filters.teams.map((candidate) => ({ value: String(candidate.id), label: candidate.name }))}
            value={filter.teamId === undefined ? null : String(filter.teamId)}
            onChange={changeTeam}
            clearable
            clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: t("reports.filters.team") }) }}
            searchable
            w={200}
          />
          {team && (
            <Select
              label={t("reports.filters.member")}
              placeholder={t("reports.filters.wholeTeam")}
              data={team.members.map((member) => ({ value: member.accountId, label: member.displayName }))}
              value={filter.accountId ?? null}
              onChange={(value) => setKey("accountId", value)}
              clearable
              clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: t("reports.filters.member") }) }}
              searchable
              w={200}
            />
          )}
          {controls.domain && (
            <Select
              label={t("reports.filters.domain")}
              placeholder={t("reports.filters.anyValue")}
              data={filters.domains.map((domain) => ({ value: domain.domainKey, label: domain.domainName }))}
              value={filter.domain ?? null}
              onChange={(value) => setKey("domain", value)}
              clearable
              clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: t("reports.filters.domain") }) }}
              searchable
              w={200}
            />
          )}
          {controls.activityType && (
            <Select
              label={t("reports.filters.activityType")}
              placeholder={t("reports.filters.anyValue")}
              data={filters.activityTypes}
              value={filter.activityType ?? null}
              onChange={(value) => setKey("activityType", value)}
              clearable
              clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: t("reports.filters.activityType") }) }}
              searchable
              w={200}
            />
          )}
          {controls.workCategory && (
            <Select
              label={t("reports.filters.workCategory")}
              placeholder={t("reports.filters.anyValue")}
              data={[
                ...filters.workCategories.map((category) => ({ value: category, label: category })),
                { value: UNCATEGORIZED, label: t("reports.filters.uncategorized") },
              ]}
              value={filter.workCategory ?? null}
              onChange={(value) => setKey("workCategory", value)}
              clearable
              clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: t("reports.filters.workCategory") }) }}
              searchable
              w={200}
            />
          )}
        </Group>
        {(controls.domainView !== undefined || controls.breakdown) && (
          <Group gap="lg" align="flex-end" wrap="wrap">
            {controls.domainView !== undefined && (
              <Box>
                <Text size="sm" fw={500} mb={4} id="report-domain-view">
                  {t("reports.filters.domainView")}
                </Text>
                <SegmentedControl
                  aria-labelledby="report-domain-view"
                  data={[
                    { value: "TASK", label: t("reports.filters.deliveredIn") },
                    { value: "EPIC", label: t("reports.filters.earnedIn") },
                  ]}
                  value={filter.domainView ?? controls.domainView}
                  onChange={(value) => setKey("domainView", value as DomainView)}
                />
              </Box>
            )}
            {controls.breakdown && (
              <Box>
                <Text size="sm" fw={500} mb={4} id="report-breakdown">
                  {t("reports.filters.breakdown")}
                </Text>
                <SegmentedControl
                  aria-labelledby="report-breakdown"
                  data={BREAKDOWNS.map((value) => ({ value, label: t(`reports.filters.breakdownOption.${value}`) }))}
                  value={filter.breakdown ?? "NONE"}
                  onChange={(value) => setKey("breakdown", value as Breakdown)}
                />
              </Box>
            )}
          </Group>
        )}
      </Stack>
    </DatesProvider>
  );
}
