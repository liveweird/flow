import { useState } from "react";
import { Select } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { listFlowUsers } from "../api/flowUsers";
import { useAdmin } from "../auth";
import ErrorAlert from "./ErrorAlert";

/** The picked manager, held by the caller (the form) so its label survives later searches. */
export type PickedManager = { value: string; label: string };

/**
 * The team editor's Manager field: a searchable, clearable Select over the Flow users list (server-side
 * name filter, debounced 300 ms — the `AddMemberPicker` pattern). `GET /flow-users` is ADMIN-only, and so
 * is every team write, so the query is `enabled` for an ADMIN session only.
 */
export default function TeamManagerSelect({ picked, onPick }: { picked: PickedManager | null; onPick: (picked: PickedManager | null) => void }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const [search, setSearch] = useState(picked?.label ?? "");
  // Choosing an option makes Mantine put its label into the search box — that is not a search term.
  const term = picked !== null && search === picked.label ? "" : search.trim();
  const [debounced] = useDebouncedValue(term, 300);
  const settling = term !== debounced;

  const users = useQuery({
    queryKey: ["flowUsers", "picker", debounced],
    queryFn: () => listFlowUsers({ page: 1, pageSize: 50, sort: "name", name: debounced || undefined }),
    placeholderData: keepPreviousData,
    enabled: admin,
  });
  const loading = users.isLoading || users.isPlaceholderData;
  // The picked manager leads the options under its own (stable) label: a new term replaces the rows
  // and Mantine drops a value that is no longer in `data`.
  const options = (users.data?.items ?? [])
    .filter((u) => String(u.id) !== picked?.value)
    .map((u) => ({ value: String(u.id), label: `${u.name} (${u.email})` }));
  if (picked !== null) options.unshift(picked);

  return (
    <>
      <Select
        label={t("teams.manager")}
        description={t("teams.managerHint")}
        placeholder={t("teams.pickManager")}
        data={options}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("teams.clearManager") }}
        searchValue={search}
        onSearchChange={setSearch}
        value={picked?.value ?? null}
        onChange={(value, option) => onPick(value !== null && option ? { value: option.value, label: option.label } : null)}
        // "No matching users" states a COMPLETED search for the CURRENT term.
        nothingFoundMessage={loading || users.isError || settling ? undefined : t("teams.noMatchingUsers")}
        aria-busy={loading}
      />
      {users.isError && <ErrorAlert error={users.error} />}
    </>
  );
}
