import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconArrowsLeftRight } from "@tabler/icons-react";
import type { ReportFilterTeam } from "../api/reports";
import { COMPARE_PEOPLE_COUNT } from "../utils/reportFilter";
import PersonSidePicker from "./PersonSidePicker";

/**
 * The two people of Compare people, side A and side B, picked beside the filter bar (they are what the page is about, not a narrowing
 * of it). The picks ARE the URL's repeated `accountId` (`accountIds`, A first): B can be chosen once A is, A can be cleared only while B is
 * empty, and a swap button turns the comparison around (the cue always names the better side, so the orientation is the reader's choice).
 * Each side is a `PersonSidePicker`: its own optional Team select before its person select (comparing across teams is the point).
 */
export default function ComparePeoplePickers({
  accountIds,
  names,
  teams,
  onChange,
}: {
  accountIds: readonly string[];
  /** The names the loaded report knows its two people by (a deep link's account ids have no name until it arrives). */
  names: Readonly<Record<string, string>>;
  /** The report filter bar's teams (`GET /reports/filters`). */
  teams: ReadonlyArray<ReportFilterTeam>;
  onChange: (accountIds: string[]) => void;
}) {
  const { t } = useTranslation();
  const [a, b] = [accountIds[0] ?? null, accountIds[1] ?? null];
  const both = accountIds.length === COMPARE_PEOPLE_COUNT;
  return (
    // The help text sits on its own line under the pickers, so it never squeezes beside them.
    <Stack gap={6} role="group" aria-label={t("reports.comparePeople.pickers.group")} data-print="hide">
      <Group gap="sm" align="flex-end" wrap="wrap">
        <PersonSidePicker
          teamLabel={t("reports.comparePeople.pickers.teamA")}
          personLabel={t("reports.comparePeople.pickers.a")}
          placeholder={t("reports.comparePeople.pickers.placeholder")}
          teamKey="reports.comparePeople.teamA"
          teams={teams}
          value={a}
          excluded={b}
          names={names}
          disabled={false}
          clearable={a !== null && b === null}
          onChange={(id) => onChange(id === null ? [] : b === null ? [id] : [id, b])}
        />
        <Tooltip label={t("reports.comparePeople.pickers.swap")}>
          <ActionIcon
            variant="default"
            size="lg"
            mb={1}
            disabled={!both}
            aria-label={t("reports.comparePeople.pickers.swap")}
            onClick={() => a !== null && b !== null && onChange([b, a])}
          >
            <IconArrowsLeftRight size={18} />
          </ActionIcon>
        </Tooltip>
        <PersonSidePicker
          teamLabel={t("reports.comparePeople.pickers.teamB")}
          personLabel={t("reports.comparePeople.pickers.b")}
          placeholder={a === null ? t("reports.comparePeople.pickers.pickAFirst") : t("reports.comparePeople.pickers.placeholder")}
          teamKey="reports.comparePeople.teamB"
          teams={teams}
          value={b}
          excluded={a}
          names={names}
          disabled={a === null}
          clearable={b !== null}
          onChange={(id) => a !== null && onChange(id === null ? [a] : [a, id])}
        />
      </Group>
      <Text size="xs" c="dimmed">
        {t("reports.comparePeople.pickers.help")}
      </Text>
    </Stack>
  );
}
