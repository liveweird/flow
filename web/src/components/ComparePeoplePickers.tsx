import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Loader, Select, Text, Tooltip, type OptionsFilter } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { IconArrowsLeftRight } from "@tabler/icons-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listJiraUsers } from "../api/metrics";
import { COMPARE_PEOPLE_COUNT } from "../utils/reportFilter";
import { loadErrorMessage } from "../utils/saveError";

/** The server search waits this long after the last keystroke; one page of the directory is plenty for a name search. */
const SEARCH_DEBOUNCE_MS = 300;
const PAGE_SIZE = 20;

// The server already filtered by the typed text, so the combobox must not filter again.
const SHOW_ALL: OptionsFilter = ({ options }) => options;

/**
 * One searchable person select over the unit's Jira directory (`GET /api/v1/jira-users`, scope UNIT: any signed-in user, the accounts
 * already relevant to the unit). The typed text goes to the server (debounced) as `q`; a picked person stays in the list however the
 * search changes — their name is the one any answer gave them, else the one the loaded report knows (`names`), else the account id.
 */
function PersonSelect({
  label,
  placeholder,
  value,
  excluded,
  names,
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
    queryKey: ["jira-users", "picker", "UNIT", debounced],
    queryFn: async ({ signal }) => {
      const page = await listJiraUsers({ page: 1, pageSize: PAGE_SIZE, sort: "displayName", q: debounced || undefined, scope: "UNIT" }, signal);
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

/**
 * The two people of Compare people, side A and side B, picked beside the filter bar (they are what the page is about, not a narrowing
 * of it). The picks ARE the URL's repeated `accountId` (`accountIds`, A first): B can be chosen once A is, A can be cleared only while B is
 * empty, and a swap button turns the comparison around (the cue always names the better side, so the orientation is the reader's choice).
 */
export default function ComparePeoplePickers({
  accountIds,
  names,
  onChange,
}: {
  accountIds: readonly string[];
  /** The names the loaded report knows its two people by (a deep link's account ids have no name until it arrives). */
  names: Readonly<Record<string, string>>;
  onChange: (accountIds: string[]) => void;
}) {
  const { t } = useTranslation();
  const [a, b] = [accountIds[0] ?? null, accountIds[1] ?? null];
  const both = accountIds.length === COMPARE_PEOPLE_COUNT;
  return (
    <Group gap="sm" align="flex-end" wrap="wrap" role="group" aria-label={t("reports.comparePeople.pickers.group")} data-print="hide">
      <PersonSelect
        label={t("reports.comparePeople.pickers.a")}
        placeholder={t("reports.comparePeople.pickers.placeholder")}
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
      <PersonSelect
        label={t("reports.comparePeople.pickers.b")}
        placeholder={a === null ? t("reports.comparePeople.pickers.pickAFirst") : t("reports.comparePeople.pickers.placeholder")}
        value={b}
        excluded={a}
        names={names}
        disabled={a === null}
        clearable={b !== null}
        onChange={(id) => a !== null && onChange(id === null ? [a] : [a, id])}
      />
      <Text size="xs" c="dimmed" maw={360}>
        {t("reports.comparePeople.pickers.help")}
      </Text>
    </Group>
  );
}
