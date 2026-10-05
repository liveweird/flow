import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader, MultiSelect, Select, type OptionsFilter } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { loadErrorMessage } from "../utils/saveError";

interface PickerOption {
  value: string;
  label: string;
}

/** One page of a picker's options and how many matched in all (the page is capped, so `total` can exceed it). */
export interface PickerPage {
  options: PickerOption[];
  total: number;
}

/** The server search waits this long after the last keystroke, so typing never fires a request per character. */
const SEARCH_DEBOUNCE_MS = 300;

// The server already filtered by the typed text (over fields the label may not show in one run), so the combobox must not filter again.
const SHOW_ALL: OptionsFilter = ({ options }) => options;

/**
 * A searchable single or multiple choice over a server-side option list: the typed text (debounced) goes to the
 * server as `q`, the answer is the option list, and what is already picked stays in the list however the search
 * changes — its label is the one any answer gave it, else the one in `labels` (names a loaded report knows), else the
 * value itself. A multiple choice holds at most `max` values and says how many are used.
 */
export default function DeepDivePicker({
  label,
  placeholder,
  scope,
  load,
  enabled = true,
  value,
  onChange,
  max,
  labels,
  disabledHint,
}: {
  label: string;
  placeholder: string;
  /** What is searched (the option kind and whatever narrows it): the query key under the typed text. */
  scope: readonly unknown[];
  load: (q: string | undefined) => Promise<PickerPage>;
  enabled?: boolean;
  value: string[];
  onChange: (value: string[]) => void;
  /** Present for a multiple choice: the most values it may hold. */
  max?: number;
  /** Names of values this picker may not have seen (from a loaded report). */
  labels: Readonly<Record<string, string>>;
  /** Said while the picker is disabled: what to do first. */
  disabledHint?: string;
}) {
  const { t } = useTranslation();
  const multiple = max !== undefined;
  const [search, setSearch] = useState("");
  // A single choice shows its own label in the search box, and Mantine writes that label into the box itself — so the
  // box's text says nothing about whether the viewer is searching. Only the viewer's own keystrokes do (`onInput`);
  // selecting, clearing or leaving the box ends the search.
  const [typing, setTyping] = useState(false);
  const typed = multiple || typing ? search.trim() : "";
  const [debounced] = useDebouncedValue(typed, SEARCH_DEBOUNCE_MS);
  const settling = typed !== debounced;

  // Every option any answer listed, by value: a pick's label does not depend on which answer is on screen.
  const [known, setKnown] = useState<Record<string, string>>({});
  const scopeKey = JSON.stringify(scope);
  const query = useQuery({
    queryKey: ["reports", "deep-dive", "options", ...scope, debounced],
    queryFn: async () => {
      const page = await load(debounced === "" ? undefined : debounced);
      setKnown((prev) => ({ ...prev, ...Object.fromEntries(page.options.map((option) => [option.value, option.label])) }));
      return page;
    },
    enabled,
    // Typing keeps the previous answer on screen while the next one loads — but never one listed under another scope.
    placeholderData: (previous, previousQuery) =>
      previousQuery !== undefined && JSON.stringify(previousQuery.queryKey.slice(3, -1)) === scopeKey ? previous : undefined,
  });

  const found = new Map<string, string>();
  for (const option of query.data?.options ?? []) if (!found.has(option.value)) found.set(option.value, option.label);
  const labelOf = (v: string) => known[v] ?? found.get(v) ?? labels[v] ?? v;
  const data = [
    ...value.map((v) => ({ value: v, label: labelOf(v) })),
    ...[...found].filter(([v]) => !value.includes(v)).map(([v, l]) => ({ value: v, label: l })),
  ];

  const change = (next: string[]) => {
    setTyping(false);
    onChange(next);
  };

  const truncated = query.data !== undefined && query.data.total > query.data.options.length;
  const description = [
    multiple ? t("reports.deepDive.panel.selectedCount", { used: value.length, max }) : null,
    truncated ? t("reports.deepDive.panel.moreOptions", { shown: query.data?.options.length, total: query.data?.total }) : null,
    enabled ? null : disabledHint,
  ]
    .filter((part) => part !== null && part !== undefined)
    .join(" ");

  const common = {
    label,
    placeholder,
    description: description === "" ? undefined : description,
    data,
    searchable: true,
    searchValue: search,
    onSearchChange: setSearch,
    filter: SHOW_ALL,
    nothingFoundMessage: query.isFetching || query.isError || settling ? undefined : t("reports.deepDive.panel.nothingFound"),
    error: query.isError ? loadErrorMessage(query.error, t) : undefined,
    disabled: !enabled,
    clearable: true,
    clearButtonProps: { "aria-label": t("reports.filters.clearAria", { name: label }) },
    "aria-busy": query.isFetching,
    // Left, so the clear button and the chevron stay where they are while a search runs.
    leftSection: query.isFetching && enabled ? <Loader size="xs" role="status" aria-label={t("reports.deepDive.panel.searching")} /> : undefined,
    w: 360,
    maw: "100%",
  };

  return multiple ? (
    <MultiSelect {...common} value={value} onChange={change} maxValues={max} hidePickedOptions />
  ) : (
    <Select
      {...common}
      value={value[0] ?? null}
      onChange={(next) => change(next === null ? [] : [next])}
      onInput={() => setTyping(true)}
      onBlur={() => setTyping(false)}
    />
  );
}
