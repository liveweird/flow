import { dropReportSpecific, type ReportControls, type ReportFilterState } from "./reportFilter";
import { codeActivityPath, reportSpecificKeep } from "./reportLinks";

/**
 * Code activity (report 21): merge-request flow for ONE team over the GitLab code layer, the members' volumes beside it. The bar offers the
 * team (required: the server answers `400` for the whole unit; or "Unassigned", `teamId=0`, the work no team holds), up to eight members, and the whole period (dates and sprints); no domain,
 * domain view, activity type or work category — the GitLab rows carry none. A module-level constant: the hook reads its identity.
 */
export const CODE_ACTIVITY_CONTROLS: ReportControls = { teamRequired: true, teamUnassigned: true, multiMember: true };

/**
 * Code activity's whole filter, made answerable: none of another report's keys, and no `connectionId` (it names a JIRA connection in the shared
 * filter, where the server reads it as a GitLab source and answers `400` — the page reads every active GitLab source). The URL is rewritten to
 * say so (a `normalize` step for `useReportPage`, a module-level function so its identity is stable). "Unassigned" (`teamId=0`) is read for the
 * whole bucket, never for picked people: it has no roster to pick from, so a member in such a link is dropped too; and it resolves no sprints, so a
 * sprint-relative period is dropped (the Period control offers dates only there).
 */
export function normalizeCodeActivityFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(codeActivityPath));
  delete next.connectionId;
  if (next.teamId === 0) {
    delete next.accountId;
    delete next.accountIds;
    // It resolves no sprints either: a sprint-relative period falls back to the default dates.
    delete next.lastSprints;
    delete next.sprintId;
  }
  return next;
}
