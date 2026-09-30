import "@mantine/dates/styles.css";
import "dayjs/locale/pl";
import { useTranslation } from "react-i18next";
import { Group, Stack } from "@mantine/core";
import { DatesProvider } from "@mantine/dates";
import type { ReportFilters } from "../api/reports";
import type { ReportControls, ReportFilterState } from "../utils/reportFilter";
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
 * controls, in one row of dropdowns and one row of toggles.
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
  return (
    <DatesProvider settings={{ locale: i18n.resolvedLanguage ?? "en", firstDayOfWeek: 1 }}>
      <Stack gap="sm">
        <Group gap="sm" align="flex-end" wrap="wrap" role="group" aria-label={t("reports.filters.title")}>
          {!controls.noPeriod && (
            <ReportPeriodControls filter={filter} timeZone={filters.timeZone} teams={filters.teams} onChange={onChange} />
          )}
          <ReportScopeControls filters={filters} filter={filter} controls={controls} onChange={onChange} />
          <ReportSliceSelects filters={filters} filter={filter} controls={controls} onChange={onChange} />
        </Group>
        <ReportSliceControls filter={filter} controls={controls} onChange={onChange} />
      </Stack>
    </DatesProvider>
  );
}
