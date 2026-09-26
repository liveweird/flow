import { useDebouncedValue } from "@mantine/hooks";
import { usePagedSort } from "./usePagedSort";
import { isString, useStoredState } from "./useStoredState";

/**
 * Shared persisted name filter and paging/sort state for the small registry pages.
 * Extra filters stay page-owned and are only passed here so they reset pagination.
 */
export function useRegistryListControls<F extends string>({
  settingsKey,
  sortFields,
  initialSortField,
  extraFilterDeps = [],
}: {
  settingsKey: string;
  sortFields: readonly F[];
  initialSortField: F;
  extraFilterDeps?: unknown[];
}) {
  const [nameFilter, setNameFilter] = useStoredState(`${settingsKey}.filter.name`, "", isString);
  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const paging = usePagedSort<F>(initialSortField, [debouncedName, ...extraFilterDeps], {
    key: settingsKey,
    sortFields,
  });

  return {
    nameFilter,
    setNameFilter,
    debouncedName,
    nameFilterActive: Boolean(nameFilter.trim()),
    ...paging,
  };
}
