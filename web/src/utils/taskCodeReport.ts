import { dropMember, dropReportSpecific, type ReportControls, type ReportFilterState } from "./reportFilter";
import { reportSpecificKeep, taskCodePath } from "./reportLinks";

/**
 * Tasks and code (report 22): the done tasks of a team or a domain (Cycle time's population, so its slices: the domain and its view, activity type,
 * work category) joined to the GitLab merge requests that name them. The bar offers a team (required, the way Cycle time has it) that a domain may narrow
 * (the server's team x domain read, so the domain does not exclude the team); a domain-wide link with no team (`teamOrDomain`) is still read, for the domain, the whole period
 * (dates and sprints) and the Jira connection that narrows the tasks. There is NO member control (`noMember`): the endpoint has no person level and
 * answers an `accountId` with a `400`. The team may also be "Unassigned" (`teamUnassigned`, `teamId=0`): the done tasks with no credit team, which the server
 * answers like a team (a domain and every other slice still apply). A module-level constant: the hook reads its identity.
 */
export const TASK_CODE_CONTROLS: ReportControls = {
  teamRequired: true,
  teamUnassigned: true,
  teamOrDomain: true,
  noMember: true,
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
  connection: true,
};

/**
 * Tasks and code's whole filter, made answerable: none of another report's keys (a bucket, a WIP keying, an item kind) and no member, because the report has
 * no person level (a pasted `accountId` would be a `400`). The URL is rewritten to say so (a `normalize` step for `useReportPage`, a module-level function
 * so its identity is stable). "Unassigned" (`teamId=0`) resolves no sprints (the server answers a sprint-relative period with nothing to read), so such a
 * period is dropped beside it and the Period control offers dates only there -- the way Code activity has it; the domain and every slice are kept.
 */
export function normalizeTaskCodeFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropMember(dropReportSpecific(filter, reportSpecificKeep(taskCodePath)));
  if (next.teamId !== 0 || (next.lastSprints === undefined && next.sprintId === undefined)) return next;
  const dated = { ...next };
  delete dated.lastSprints;
  delete dated.sprintId;
  return dated;
}
