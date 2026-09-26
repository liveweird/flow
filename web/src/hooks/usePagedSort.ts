import { useEffect, useState } from "react";
import type { SortDir } from "../components/SortHeader";
import { readStoredJson, writeStoredJson } from "./useStoredState";

export const PAGE_SIZE_OPTIONS = [20, 40, 60] as const;
const DEFAULT_PAGE_SIZE = 20;

type StoredPaging<F extends string> = { sortField: F; sortDir: SortDir; pageSize: number };

// Persisted sort/pageSize for a view, validated against the view's sort whitelist so a stale
// stored field (e.g. after a column is removed) silently falls back to the defaults.
function readStoredPaging<F extends string>(
  key: string,
  sortFields: readonly F[],
): Partial<StoredPaging<F>> {
  const parsed = (readStoredJson(`${key}.paging`) ?? {}) as Partial<StoredPaging<F>>;
  return {
    sortField: sortFields.includes(parsed.sortField as F) ? (parsed.sortField as F) : undefined,
    sortDir: parsed.sortDir === "asc" || parsed.sortDir === "desc" ? parsed.sortDir : undefined,
    pageSize: (PAGE_SIZE_OPTIONS as readonly number[]).includes(parsed.pageSize as number)
      ? parsed.pageSize
      : undefined,
  };
}

type PagedSortPersist<F extends string> = {
  /** The view's settings namespace, e.g. "catalogFiles". */
  key: string;
  /** Runtime whitelist of sortable fields — stored values outside it are discarded. */
  sortFields: readonly F[];
};

export function usePagedSort<F extends string>(
  initialSortField: F,
  filterDeps: unknown[],
  persist?: PagedSortPersist<F>,
) {
  // Read storage once (lazy initializer), not on every render.
  const [stored] = useState(() =>
    persist ? readStoredPaging(persist.key, persist.sortFields) : {},
  );
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(stored.pageSize ?? DEFAULT_PAGE_SIZE);
  const [sortField, setSortField] = useState<F>(stored.sortField ?? initialSortField);
  const [sortDir, setSortDir] = useState<SortDir>(stored.sortDir ?? "asc");

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset to page 1 whenever a filter or the sort changes
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- filterDeps is intentionally spread
  }, [...filterDeps, sortField, sortDir]);

  const persistKey = persist?.key;
  useEffect(() => {
    if (!persistKey) return;
    writeStoredJson(`${persistKey}.paging`, { sortField, sortDir, pageSize });
  }, [persistKey, sortField, sortDir, pageSize]);

  const sortParam = `${sortDir === "desc" ? "-" : ""}${sortField}`;

  function toggleSort(field: F) {
    if (field === sortField) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortField(field);
      setSortDir("asc");
    }
  }

  function changePageSize(size: number) {
    setPageSize(size);
    setPage(1);
  }

  return {
    page,
    setPage,
    pageSize,
    setPageSize: changePageSize,
    sortField,
    sortDir,
    sortParam,
    toggleSort,
  };
}
