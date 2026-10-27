import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { ReportFilterTeam } from "../api/reports";
import PersonSidePicker from "./PersonSidePicker";

/**
 * The one person of Person trend, picked beside the filter bar (they are what the page is about, not a narrowing of it). The pick IS the URL's
 * `accountId` (`accountIds`, one): it can be cleared at any time. An optional Team select before the person narrows the list to the team's current
 * members (anyone else is found by typing their name); that team is remembered per viewer under `flow.viewSettings.reports.personTrend.team`.
 */
export default function PersonTrendPicker({
  accountIds,
  names,
  teams,
  onChange,
}: {
  accountIds: readonly string[];
  /** The name the loaded report knows its person by (a deep link's account id has no name until it arrives). */
  names: Readonly<Record<string, string>>;
  /** The report filter bar's teams (`GET /reports/filters`). */
  teams: ReadonlyArray<ReportFilterTeam>;
  onChange: (accountIds: string[]) => void;
}) {
  const { t } = useTranslation();
  const person = accountIds[0] ?? null;
  return (
    <Stack gap={6} role="group" aria-label={t("reports.personTrend.picker.group")} data-print="hide">
      <PersonSidePicker
        teamLabel={t("reports.personTrend.picker.team")}
        personLabel={t("reports.personTrend.picker.person")}
        placeholder={t("reports.personTrend.picker.placeholder")}
        teamKey="reports.personTrend.team"
        teams={teams}
        value={person}
        excluded={null}
        names={names}
        disabled={false}
        clearable={person !== null}
        onChange={(id) => onChange(id === null ? [] : [id])}
      />
      <Text size="xs" c="dimmed">
        {t("reports.personTrend.picker.help")}
      </Text>
    </Stack>
  );
}
