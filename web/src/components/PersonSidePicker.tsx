import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Group, Loader, Select, type OptionsFilter } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listJiraUsers } from "../api/metrics";
import type { ReportFilterTeam } from "../api/reports";
import { useStoredState } from "../hooks/useStoredState";
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
      description={!loading && !settling && total > shown && shown > 0 ? t("reports.personPicker.cut", { shown, total }) : undefined}
      // "Nothing found" states a COMPLETED search for the CURRENT term: never while it loads, while the typed term settles or after a failure.
      nothingFoundMessage={loading || people.isError || settling ? undefined : t("reports.personPicker.nothingFound")}
      error={people.isError ? loadErrorMessage(people.error, t) : undefined}
      // Left, so the clear button and the chevron stay where they are while a search runs.
      leftSection={loading ? <Loader size="xs" role="status" aria-label={t("reports.personPicker.searching")} /> : undefined}
      w={300}
      maw="100%"
    />
  );
}

/** A remembered team is a bare id: null or an integer (anything else in storage is ignored). */
const isRememberedTeam = (value: unknown) => value === null || (typeof value === "number" && Number.isInteger(value));

/**
 * A picker's team, remembered per viewer under `flow.viewSettings.<key>`. It is a picker aid only — never part of the URL or the report
 * request — and a remembered team that no longer exists is treated as none.
 */
function useRememberedTeam(key: string, teams: ReadonlyArray<ReportFilterTeam>): [number | null, (teamId: number | null) => void] {
  const [stored, setStored] = useStoredState<number | null>(key, null, isRememberedTeam);
  return [stored !== null && teams.some((team) => team.id === stored) ? stored : null, setStored];
}

/**
 * One person's picker, shared by Compare people (a side each) and Person trend: an optional Team select (remembered under `teamKey`) before the
 * searchable person select. A team lists its current members, no team is the unit-wide name search, and changing the team never touches an
 * already-picked person.
 */
export default function PersonSidePicker({
  teamLabel,
  personLabel,
  placeholder,
  teamKey,
  teams,
  value,
  excluded,
  names,
  disabled,
  clearable,
  onChange,
}: {
  teamLabel: string;
  personLabel: string;
  placeholder: string;
  /** The view-settings key the team is remembered under (`reports.comparePeople.teamA`, `reports.personTrend.team`). */
  teamKey: string;
  /** The report filter bar's teams (`GET /reports/filters`). */
  teams: ReadonlyArray<ReportFilterTeam>;
  value: string | null;
  /** The other side's account (Compare people), not offered; `null` where there is none. */
  excluded: string | null;
  names: Readonly<Record<string, string>>;
  disabled: boolean;
  clearable: boolean;
  onChange: (accountId: string | null) => void;
}) {
  const { t } = useTranslation();
  const [team, setTeam] = useRememberedTeam(teamKey, teams);
  return (
    <Group gap="sm" align="flex-end" wrap="wrap">
      <ReportFilterSelect
        label={teamLabel}
        placeholder={t("reports.personPicker.teamPlaceholder")}
        data={teams.map((candidate) => ({ value: String(candidate.id), label: candidate.name }))}
        value={team === null ? null : String(team)}
        onChange={(next) => setTeam(next === null ? null : Number(next))}
      />
      <PersonSelect
        label={personLabel}
        placeholder={placeholder}
        value={value}
        excluded={excluded}
        names={names}
        teamId={team}
        disabled={disabled}
        clearable={clearable}
        onChange={onChange}
      />
    </Group>
  );
}
