import "@mantine/dates/styles.css";
import "dayjs/locale/pl";
import { useTranslation } from "react-i18next";
import { Group, Stack, Text } from "@mantine/core";
import { DatesProvider } from "@mantine/dates";
import type { ReportFilters } from "../api/reports";
import type { ReportControls, ReportFilterState } from "../utils/reportFilter";
import { activeReportFilterCount } from "../utils/reportFilterCount";
import { todayIsoDate } from "../utils/isoDate";
import { reportFilterSummaryLines } from "../utils/reportPrintSummary";
import FilterPanel from "./FilterPanel";
import ReportPeriodControls from "./ReportPeriodControls";
import ReportScopeControls from "./ReportScopeControls";
import ReportSliceControls, { ReportSliceSelects } from "./ReportSliceControls";

/** Re-exported so the report pages keep importing it beside the bar. */
export type { ReportControls } from "../utils/reportFilter";

/**
 * The reports filter bar: period presets + custom range + per-team sprint picker, the org drill
 * (team → member) and the report-specific extras. Pure controlled component — the URL state lives
 * in `useReportFilter`; every control produces a whole next filter through `onChange`. The bar is
 * only the frame: `ReportPeriodControls`, `ReportScopeControls` and `ReportSliceControls` own the
 * controls, in one row of dropdowns and one row of toggles. The controls sit in a `FilterPanel`:
 * collapsed by default, the choice remembered per report under `<storageKey>.filtersOpen`, and a
 * badge counting the dimensions that differ from the report's default (`activeReportFilterCount`).
 * The folded panel also names the current period and scope beside the toggle.
 */
export default function ReportFilterBar({
  filters,
  filter,
  onChange,
  controls = {},
  storageKey,
}: {
  filters: ReportFilters;
  filter: ReportFilterState;
  onChange: (next: ReportFilterState) => void;
  controls?: ReportControls;
  /** The report's view key (`useReportPage` returns it: `reports.<report>`) — the panel's open flag lives under it. */
  storageKey: string;
}) {
  const { t, i18n } = useTranslation();
  const activeFilterCount = activeReportFilterCount(filter, controls, {
    today: todayIsoDate(filters.timeZone),
    horizonFrom: filters.historyHorizon?.from,
  });
  const summaryLines = reportFilterSummaryLines(filter, filters, controls, t);
  return (
    <DatesProvider settings={{ locale: i18n.resolvedLanguage ?? "en", firstDayOfWeek: 1 }}>
      <FilterPanel
        activeFilterCount={activeFilterCount}
        storageKey={storageKey}
        collapsedSummary={
          <Group
            gap="xs"
            wrap="wrap"
            role="group"
            aria-label={t("reports.filters.summary")}
            style={{ flex: "1 1 20rem", minWidth: 0 }}
          >
            {summaryLines.map((line) => (
              <Text key={line.label} size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
                <Text component="span" inherit fw={600}>
                  {line.label}:
                </Text>{" "}
                {line.value}
              </Text>
            ))}
          </Group>
        }
      >
        <Stack gap="sm" w="100%">
          <Group gap="sm" align="flex-end" wrap="wrap" role="group" aria-label={t("reports.filters.title")}>
            {!controls.noPeriod && (
              <ReportPeriodControls
                filter={filter}
                timeZone={filters.timeZone}
                horizonFrom={filters.historyHorizon?.from}
                teams={filters.teams}
                // "Unassigned" (Code activity, Tasks and code; `teamId=0`) resolves no sprints: the period is dates only, like a people-only report's.
                datesOnly={controls.peopleOnly || (controls.teamUnassigned === true && filter.teamId === 0)}
                onChange={onChange}
              />
            )}
            <ReportScopeControls filters={filters} filter={filter} controls={controls} onChange={onChange} />
            <ReportSliceSelects filters={filters} filter={filter} controls={controls} onChange={onChange} />
          </Group>
          <ReportSliceControls filter={filter} controls={controls} onChange={onChange} />
        </Stack>
      </FilterPanel>
    </DatesProvider>
  );
}
