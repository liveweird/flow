import type { ReportFilters } from "../api/reports";
import {
  applyDeepDiveSelection,
  applyDeepDiveView,
  normalizeDeepDiveSelection,
  parseDeepDiveSelection,
  parseDeepDiveView,
  type DeepDiveSelection,
  type DeepDiveView,
} from "./deepDiveFilter";

/**
 * The pure half of the Deep dive's "last shown selection" memory (`hooks/useDeepDiveUrlState.ts` is the storage half). What is
 * remembered is the URL the page wrote for the selection the user SHOWED — the canonical selection query plus `view=burnup` — read back
 * through the very parser a pasted link goes through, so a retired key (`issueId`), a conflicting mode or a malformed value is
 * dropped exactly as it would be there.
 */

/** The query to remember for a shown selection and the open view. */
export function rememberedDeepDive(selection: DeepDiveSelection, view: DeepDiveView): string {
  return applyDeepDiveView(applyDeepDiveSelection(new URLSearchParams(), selection), view).toString();
}

/** What the page restores on a bare visit. */
export interface RestoredDeepDive {
  selection: DeepDiveSelection;
  view: DeepDiveView;
}

/**
 * A remembered query read back: parsed like a link, and cleared of the references the reference data no longer knows — a domain
 * that is gone (which takes the sprints that were read in it with it) and a connection that is no longer listed. Sprint and epic ids
 * have no client-side list; the report's own `400` for an unknown one is what drops those (the page forgets a restored selection
 * the server refuses).
 */
export function restoreDeepDive(stored: string, reference: Pick<ReportFilters, "domains" | "connections">): RestoredDeepDive {
  const params = new URLSearchParams(stored);
  const selection = parseDeepDiveSelection(params);
  if (selection.domain !== undefined && !reference.domains.some((domain) => domain.domainKey === selection.domain)) delete selection.domain;
  if (selection.connectionId !== undefined && !reference.connections.some((connection) => connection.id === selection.connectionId)) {
    delete selection.connectionId;
  }
  return { selection: normalizeDeepDiveSelection(selection), view: parseDeepDiveView(params) };
}
