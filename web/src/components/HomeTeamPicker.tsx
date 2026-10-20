import { useTranslation } from "react-i18next";
import { Button, Group, MultiSelect } from "@mantine/core";
import type { ReportFilterTeam } from "../api/reports";
import { HOME_MAX_TEAMS } from "../utils/homeTeams";

const INPUT_MIN_WIDTH = 48;

/**
 * Home's team picker, the page header's toolbar: up to `HOME_MAX_TEAMS` teams (Mantine's `maxValues`; the field says so in its
 * description once the cap is reached), searchable and clearable — clearing, like the "Back to my default teams" button (shown
 * only while a pick is remembered), goes back to the default. The idioms of `ReportFilterMultiSelect`, without reusing it: its cap
 * and copy are the people picker's.
 */
export default function HomeTeamPicker({
  teams,
  value,
  picked,
  onChange,
  onReset,
}: {
  teams: ReadonlyArray<ReportFilterTeam>;
  /** The team ids the page shows now (the pick, or the default). */
  value: readonly number[];
  /** Whether `value` is a remembered pick rather than the default. */
  picked: boolean;
  onChange: (ids: number[]) => void;
  onReset: () => void;
}) {
  const { t } = useTranslation();
  const label = t("home.teams.label");
  let description: string | undefined;
  if (value.length >= HOME_MAX_TEAMS) description = t("home.teams.cap", { max: HOME_MAX_TEAMS });
  else if (!picked) description = t("home.teams.defaultHint");
  return (
    <Group align="flex-end" gap="sm" wrap="wrap">
      <MultiSelect
        label={label}
        description={description}
        placeholder={value.length === 0 ? t("home.teams.placeholder") : undefined}
        data={teams.map((team) => ({ value: String(team.id), label: team.name }))}
        value={value.map(String)}
        onChange={(ids) => onChange(ids.map(Number))}
        clearable
        clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
        searchable
        hidePickedOptions
        maxValues={HOME_MAX_TEAMS}
        w={360}
        styles={{ inputField: { minWidth: INPUT_MIN_WIDTH } }}
      />
      {picked && (
        <Button variant="subtle" size="xs" onClick={onReset}>
          {t("home.teams.reset")}
        </Button>
      )}
    </Group>
  );
}
