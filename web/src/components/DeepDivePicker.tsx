import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader, MultiSelect, type OptionsFilter } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import type { PickFacts } from "../utils/deepDivePanel";
import { loadErrorMessage } from "../utils/saveError";

/** One option: its `connectionId` (and, for an epic, its `domain`) are what the server said about it; the panel derives the selection's connection from them. */
export interface PickerOption {
  value: string;
  label: string;
  connectionId?: number;
  domain?: string | null;
  /** A sprint's plain name (the label adds its dates); absent: the label is the name. */
  name?: string;
}

/** One page of a picker's options and how many matched in all (the page is capped, so `total` can exceed it). */
export interface PickerPage {
  options: PickerOption[];
  total: number;
}

/** The option's identity in the list: a value in two connections is two options, so a connection-placed one carries its connection. */
const tokenOf = (value: string, connectionId: number | null | undefined) => (connectionId == null ? value : `${value}@${connectionId}`);

/** The server search waits this long after the last keystroke, so typing never fires a request per character. */
const SEARCH_DEBOUNCE_MS = 300;

// The server already filtered by the typed text (over fields the label may not show in one run), so the combobox must not filter again.
const SHOW_ALL: OptionsFilter = ({ options }) => options;

/**
 * A searchable multiple choice over a server-side option list: the typed text (debounced) goes to the
 * server as `q`, the answer is the option list, and what is already picked stays in the list however the search
 * changes — its label is the one any answer gave it, else the one in `labels` (names a loaded report knows), else the
 * value itself. It holds at most `max` values and says how many are used. `onChange` also hands back what
 * the answers said about the picked values (`PickFacts`); `onOptions` sees every answer as it arrives (with the typed
 * text it answered, `undefined` for the unfiltered list).
 */
export default function DeepDivePicker({
  label,
  placeholder,
  scope,
  load,
  enabled = true,
  value,
  connectionOf,
  onChange,
  onOptions,
  max,
  labels,
  disabledHint,
  removableWhenDisabled = false,
}: {
  label: string;
  placeholder: string;
  /** What is searched (the option kind and whatever narrows it): the query key under the typed text. */
  scope: readonly unknown[];
  load: (q: string | undefined) => Promise<PickerPage>;
  enabled?: boolean;
  value: string[];
  /** The connection a picked value was placed in (`null`/absent: unknown), so two connections' options of one value stay two options. */
  connectionOf?: (value: string) => number | null | undefined;
  onChange: (value: string[], facts: Record<string, PickFacts>) => void;
  /** Told of each answer right after it is stored: the panel's hook for facts it learns from the lists. */
  onOptions?: (page: PickerPage, q: string | undefined) => void;
  /** The most values it may hold. */
  max: number;
  /** Names of values this picker may not have seen (from a loaded report). */
  labels: Readonly<Record<string, string>>;
  /** Said while the picker is disabled: what to do first. */
  disabledHint?: string;
  /**
   * While not `enabled`, stay a live control instead of `disabled` (which hides the clear button and the pills' remove buttons): the
   * picks can still be removed or cleared, but nothing is listed, searched or requested — only ADDING waits for `enabled`.
   */
  removableWhenDisabled?: boolean;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const typed = search.trim();
  const [debounced] = useDebouncedValue(typed, SEARCH_DEBOUNCE_MS);
  const settling = typed !== debounced;

  // Every option any answer listed, by token: a pick's label does not depend on which answer is on screen.
  const [known, setKnown] = useState<Record<string, PickerOption>>({});
  const scopeKey = JSON.stringify(scope);
  const query = useQuery({
    queryKey: ["reports", "deep-dive", "options", ...scope, debounced],
    queryFn: async () => {
      const q = debounced === "" ? undefined : debounced;
      const page = await load(q);
      setKnown((prev) => ({ ...prev, ...Object.fromEntries(page.options.map((option) => [tokenOf(option.value, option.connectionId), option])) }));
      onOptions?.(page, q);
      return page;
    },
    enabled,
    // Typing keeps the previous answer on screen while the next one loads — but never one listed under another scope.
    placeholderData: (previous, previousQuery) =>
      previousQuery !== undefined && JSON.stringify(previousQuery.queryKey.slice(3, -1)) === scopeKey ? previous : undefined,
  });

  // The picks as tokens (a value placed in a connection is `value@connection`), and each token's plain value.
  const pickedTokens = value.map((v) => tokenOf(v, connectionOf?.(v)));
  const valueOf = new Map(pickedTokens.map((token, i) => [token, value[i]] as const));
  const found = new Map<string, string>();
  for (const option of query.data?.options ?? []) {
    const token = tokenOf(option.value, option.connectionId);
    if (!found.has(token)) found.set(token, option.label);
    if (!valueOf.has(token)) valueOf.set(token, option.value);
  }
  const labelOf = (token: string) => known[token]?.label ?? found.get(token) ?? labels[valueOf.get(token) ?? token] ?? token;
  const data = [
    ...pickedTokens.map((token) => ({ value: token, label: labelOf(token) })),
    ...[...found].filter(([token]) => !pickedTokens.includes(token)).map(([token, l]) => ({ value: token, label: l })),
  ];

  const change = (tokens: string[]) => {
    // Back to plain values, with what the answers said about each: choosing a value in another connection replaces the pick.
    const next: string[] = [];
    const facts: Record<string, PickFacts> = {};
    for (const token of tokens) {
      const option = known[token];
      const v = valueOf.get(token) ?? option?.value ?? token;
      if (!next.includes(v)) next.push(v);
      if (option !== undefined) {
        facts[v] = {
          connectionId: option.connectionId ?? null,
          label: option.label,
          ...(option.name !== undefined && { name: option.name }),
          ...(option.domain !== undefined && { domain: option.domain }),
        };
      }
    }
    onChange(next, facts);
  };

  const truncated = query.data !== undefined && query.data.total > query.data.options.length;
  const description = [
    t("reports.deepDive.panel.selectedCount", { used: value.length, max }),
    truncated ? t("reports.deepDive.panel.moreOptions", { shown: query.data?.options.length, total: query.data?.total }) : null,
    enabled ? null : disabledHint,
  ]
    .filter((part) => part !== null && part !== undefined)
    .join(" ");

  const common = {
    label,
    placeholder,
    description: description === "" ? undefined : description,
    // The count / cut / "pick a domain first" hint sits UNDER the control, so it never pushes the control off the form row's line.
    inputWrapperOrder: ["label", "input", "description", "error"] as ("label" | "input" | "description" | "error")[],
    data,
    searchable: enabled,
    searchValue: search,
    onSearchChange: setSearch,
    filter: SHOW_ALL,
    nothingFoundMessage: query.isFetching || query.isError || settling ? undefined : t("reports.deepDive.panel.nothingFound"),
    error: query.isError ? loadErrorMessage(query.error, t) : undefined,
    disabled: !enabled && !removableWhenDisabled,
    // A gated picker that keeps its picks editable lists nothing: no dropdown, no typing; the hint (its description) says what to do first.
    ...(!enabled && removableWhenDisabled && { dropdownOpened: false }),
    clearable: true,
    clearButtonProps: { "aria-label": t("reports.filters.clearAria", { name: label }) },
    "aria-busy": query.isFetching,
    // Left, so the clear button and the chevron stay where they are while a search runs.
    leftSection: query.isFetching && enabled ? <Loader size="xs" role="status" aria-label={t("reports.deepDive.panel.searching")} /> : undefined,
    w: 340,
    maw: "100%",
  };

  return <MultiSelect {...common} value={pickedTokens} onChange={change} maxValues={max} hidePickedOptions />;
}
