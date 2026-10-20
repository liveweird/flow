import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Loader, Select, Stack, Text, Tooltip, type OptionsFilter } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { IconArrowsLeftRight } from "@tabler/icons-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listJiraUsers } from "../api/metrics";
import type { ReportFilterTeam } from "../api/reports";
import { useStoredState } from "../hooks/useStoredState";
import { COMPARE_PEOPLE_COUNT } from "../utils/reportFilter";
import { loadErrorMessage } from "../utils/saveError";
import ReportFilterSelect from "./ReportFilterSelect";

/** The server search waits this long after the last keystroke; one page of the directory is plenty for a name search. */
const SEARCH_DEBOUNCE_MS = 300;
const PAGE_SIZE = 20;
/** A team's whole roster in one page (the server's maximum), so opening the dropdown lists it without typing. */
const TEAM_PAGE_SIZE = 100;

// The server already filtered by the typed text, so the combobox must not filter again.
const SHOW_ALL: OptionsFilter = ({ options }) => options;

/**
 * One searchable person select over the unit's Jira directory (`GET /api/v1/jira-users`, scope UNIT: any signed-in user, the accounts
 * already relevant to the unit). The typed text goes to the server (debounced) as `q`; a picked person stays in the list however the
 * search changes — their name is the one any answer gave them, else the one the loaded report knows (`names`), else the account id.
 * With a `teamId` the list is that team's CURRENT members (the same endpoint with `teamId`, one full page) and typing narrows it.
 */
function PersonSelect({
  label,
  placeholder,
  value,
  excluded,
  names,
  teamId,
  disabled,
  clearable,
  onChange,
}: {
  label: string;
  placeholder: string;
  value: string | null;
  /** The other side's account: one person cannot be compared with themself, so it is not offered. */
  excluded: string | null;
  names: Readonly<Record<string, string>>;
  /** The team whose current members are listed; `null` = the unit-wide name search. */
  teamId: number | null;
  disabled: boolean;
  clearable: boolean;
  onChange: (accountId: string | null) => void;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  // Choosing an option makes Mantine put its label into the search box — that is not a search term.
  const [known, setKnown] = useState<Record<string, string>>({});
  const pickedLabel = value === null ? null : (known[value] ?? names[value] ?? value);
  const term = pickedLabel !== null && search === pickedLabel ? "" : search.trim();
  const [debounced] = useDebouncedValue(term, SEARCH_DEBOUNCE_MS);
  const settling = term !== debounced;

  const people = useQuery({
    queryKey: ["jira-users", "picker", "UNIT", teamId, debounced],
    queryFn: async ({ signal }) => {
      const page = await listJiraUsers(
        {
          page: 1,
          pageSize: teamId === null ? PAGE_SIZE : TEAM_PAGE_SIZE,
          sort: "displayName",
          q: debounced || undefined,
          teamId: teamId ?? undefined,
          scope: "UNIT",
        },
        signal,
      );
      setKnown((prev) => ({ ...prev, ...Object.fromEntries(page.items.map((person) => [person.accountId, person.displayName])) }));
      return page;
    },
    placeholderData: keepPreviousData,
    enabled: !disabled,
  });
  const loading = !disabled && (people.isLoading || people.isPlaceholderData);
  const options = (people.data?.items ?? [])
    .filter((person) => person.accountId !== excluded)
    .map((person) => ({ value: person.accountId, label: person.displayName }));
  if (value !== null && pickedLabel !== null && !options.some((option) => option.value === value)) {
    options.unshift({ value, label: pickedLabel });
  }
  // The answer's own length, not the options' (the other side is filtered out of those): "Showing 20 of 340" is about the directory.
  const shown = people.data?.items.length ?? 0;
  const total = people.data?.total ?? 0;

  return (
    <Select
      label={label}
      placeholder={placeholder}
      data={options}
      value={value}
      onChange={onChange}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      filter={SHOW_ALL}
      clearable={clearable}
      clearButtonProps={{ "aria-label": t("reports.filters.clearAria", { name: label }) }}
      allowDeselect={false}
      disabled={disabled}
      aria-busy={loading}
      description={!loading && !settling && total > shown && shown > 0 ? t("reports.comparePeople.pickers.cut", { shown, total }) : undefined}
      // "Nothing found" states a COMPLETED search for the CURRENT term: never while it loads, while the typed term settles or after a failure.
      nothingFoundMessage={loading || people.isError || settling ? undefined : t("reports.comparePeople.pickers.nothingFound")}
      error={people.isError ? loadErrorMessage(people.error, t) : undefined}
      // Left, so the clear button and the chevron stay where they are while a search runs.
      leftSection={loading ? <Loader size="xs" role="status" aria-label={t("reports.comparePeople.pickers.searching")} /> : undefined}
      w={300}
      maw="100%"
    />
  );
}

/** A remembered team is a bare id: null or an integer (anything else in storage is ignored). */
const isRememberedTeam = (value: unknown) => value === null || (typeof value === "number" && Number.isInteger(value));

/**
 * One side's team, remembered per viewer under `flow.viewSettings.reports.comparePeople.team<side>`. It is a picker aid only — never part
 * of the URL or the report request — and a remembered team that no longer exists is treated as none.
 */
function useSideTeam(side: "A" | "B", teams: ReadonlyArray<ReportFilterTeam>): [number | null, (teamId: number | null) => void] {
  const [stored, setStored] = useStoredState<number | null>(`reports.comparePeople.team${side}`, null, isRememberedTeam);
  return [stored !== null && teams.some((team) => team.id === stored) ? stored : null, setStored];
}

/**
 * The two people of Compare people, side A and side B, picked beside the filter bar (they are what the page is about, not a narrowing
 * of it). The picks ARE the URL's repeated `accountId` (`accountIds`, A first): B can be chosen once A is, A can be cleared only while B is
 * empty, and a swap button turns the comparison around (the cue always names the better side, so the orientation is the reader's choice).
 * Each side has its own optional Team select before its person select (comparing across teams is the point): a team lists its current
 * members, no team is the unit-wide name search, and changing a team never touches an already-picked person.
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
  const [teamA, setTeamA] = useSideTeam("A", teams);
  const [teamB, setTeamB] = useSideTeam("B", teams);
  const teamData = teams.map((team) => ({ value: String(team.id), label: team.name }));
  const teamSelect = (label: string, value: number | null, set: (teamId: number | null) => void) => (
    <ReportFilterSelect
      label={label}
      placeholder={t("reports.comparePeople.pickers.teamPlaceholder")}
      data={teamData}
      value={value === null ? null : String(value)}
      onChange={(next) => set(next === null ? null : Number(next))}
    />
  );
  return (
    // The help text sits on its own line under the pickers, so it never squeezes beside them.
    <Stack gap={6} role="group" aria-label={t("reports.comparePeople.pickers.group")} data-print="hide">
      <Group gap="sm" align="flex-end" wrap="wrap">
        <Group gap="sm" align="flex-end" wrap="wrap">
          {teamSelect(t("reports.comparePeople.pickers.teamA"), teamA, setTeamA)}
          <PersonSelect
            label={t("reports.comparePeople.pickers.a")}
            placeholder={t("reports.comparePeople.pickers.placeholder")}
            value={a}
            excluded={b}
            names={names}
            teamId={teamA}
            disabled={false}
            clearable={a !== null && b === null}
            onChange={(id) => onChange(id === null ? [] : b === null ? [id] : [id, b])}
          />
        </Group>
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
        <Group gap="sm" align="flex-end" wrap="wrap">
          {teamSelect(t("reports.comparePeople.pickers.teamB"), teamB, setTeamB)}
          <PersonSelect
            label={t("reports.comparePeople.pickers.b")}
            placeholder={a === null ? t("reports.comparePeople.pickers.pickAFirst") : t("reports.comparePeople.pickers.placeholder")}
            value={b}
            excluded={a}
            names={names}
            teamId={teamB}
            disabled={a === null}
            clearable={b !== null}
            onChange={(id) => a !== null && onChange(id === null ? [a] : [a, id])}
          />
        </Group>
      </Group>
      <Text size="xs" c="dimmed">
        {t("reports.comparePeople.pickers.help")}
      </Text>
    </Stack>
  );
}
