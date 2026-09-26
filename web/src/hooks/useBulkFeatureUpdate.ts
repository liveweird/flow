import { useState } from "react";

export type BulkFeatureUpdate<T> = {
  /** Which bulk button is fetching the full filtered set (true = enable), else null. */
  preparing: boolean | null;
  /** The affected rows awaiting the confirm modal, or null while no bulk is pending. */
  pending: { target: boolean; rows: T[] } | null;
  /** True while the confirmed per-row updates (or a retry) are running. */
  running: boolean;
  /** The rows whose update failed in the last run — the retry set; null when none. */
  failed: { target: boolean; rows: T[] } | null;
  prepare: (targetEnabled: boolean) => Promise<void>;
  run: () => Promise<void>;
  /** Re-run only the failed rows. */
  retry: () => Promise<void>;
  cancel: () => void;
};

/**
 * The bulk enable/disable state machine of the /feature-flags screen (Lettuce's, ported): page through
 * EVERY row matching the current filters, keep only the rows not already in the target state,
 * hold them for a count-stating confirm, then apply one sequential update per row. Rows that
 * fail KEEP THEIR IDENTITY in `failed` so the page can name them and offer `retry`.
 * The page owns everything user-facing — the fetch, the affected predicate, the per-row
 * update, and the toast/error terminals — via the option callbacks (the useDeleteConfirm
 * shape, adapted to the hand-rolled async flow).
 */
export function useBulkFeatureUpdate<T>(options: {
  fetchAll: () => Promise<T[]>;
  isAffected: (row: T, targetEnabled: boolean) => boolean;
  applyOne: (row: T, targetEnabled: boolean) => Promise<void>;
  /** The zero-affected short-circuit (toast; the modal never opens). */
  onNothingToDo: () => void;
  /** After a run or retry: invalidation + the success toast; `failed` names the losers. */
  onDone: (failed: T[], total: number) => Promise<void> | void;
  onPrepareError: (err: unknown) => void;
}): BulkFeatureUpdate<T> {
  const [preparing, setPreparing] = useState<boolean | null>(null);
  const [pending, setPending] = useState<{ target: boolean; rows: T[] } | null>(null);
  const [failed, setFailed] = useState<{ target: boolean; rows: T[] } | null>(null);
  const [running, setRunning] = useState(false);

  async function prepare(targetEnabled: boolean) {
    setPreparing(targetEnabled);
    setFailed(null);
    try {
      const all = await options.fetchAll();
      const affected = all.filter((row) => options.isAffected(row, targetEnabled));
      if (affected.length === 0) {
        options.onNothingToDo();
        return;
      }
      setPending({ target: targetEnabled, rows: affected });
    } catch (err) {
      options.onPrepareError(err);
    } finally {
      setPreparing(null);
    }
  }

  async function applyRows(target: boolean, rows: T[]) {
    setRunning(true);
    const failedRows: T[] = [];
    for (const row of rows) {
      try {
        await options.applyOne(row, target);
      } catch {
        failedRows.push(row);
      }
    }
    setRunning(false);
    setFailed(failedRows.length > 0 ? { target, rows: failedRows } : null);
    await options.onDone(failedRows, rows.length);
  }

  async function run() {
    if (!pending) return;
    const { target, rows } = pending;
    setPending(null);
    await applyRows(target, rows);
  }

  async function retry() {
    if (!failed) return;
    await applyRows(failed.target, failed.rows);
  }

  return { preparing, pending, running, failed, prepare, run, retry, cancel: () => setPending(null) };
}
