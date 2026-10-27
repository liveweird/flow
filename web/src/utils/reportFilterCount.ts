import { activePeriodChoice, wipColumnAvailable, type ReportControls, type ReportFilterState } from "./reportFilter";

/**
 * How many filter dimensions of the bar differ from the report's default — the badge on the
 * collapsed "Filters" toggle, so a narrowing the user cannot see (a remembered team, a pasted
 * link) is still announced. A dimension counts only where the bar OFFERS its control (`controls`,
 * and — for the period — a report that reads one) and its value is not what the control shows
 * with nothing in the URL: the default 90-day preset, the whole unit (a `teamRequired` report has no
 * such default, so its team never counts), no member (one or several), any domain / activity type /
 * work category / connection, the report's own domain view, WEEK, STAGE, TASK.
 * `connectionCount` is how many Jira connections there are to choose from (the control is shown
 * only for more than one).
 */
export function activeReportFilterCount(
  filter: ReportFilterState,
  controls: ReportControls,
  context: { today: string; connectionCount: number },
): number {
  const differs: boolean[] = [
    !controls.noPeriod && activePeriodChoice(filter, context.today) !== (controls.defaultPeriod ?? "last90"),
    // A required team is always set and the bar offers no "whole unit" to compare it with: it narrows nothing visible.
    !controls.teamRequired && !controls.peopleOnly && filter.teamId !== undefined,
    // Compare people's two people are what the page IS about (picked beside the bar), not a narrowing of it.
    !controls.noMember &&
      !controls.peopleOnly &&
      filter.teamId !== undefined &&
      (filter.accountId !== undefined || (filter.accountIds?.length ?? 0) > 0),
    !!controls.domain && filter.domain !== undefined,
    !!controls.connection && context.connectionCount > 1 && filter.connectionId !== undefined,
    !!controls.activityType && filter.activityType !== undefined,
    !!controls.workCategory && filter.workCategory !== undefined,
    controls.domainView !== undefined && filter.domainView !== undefined && filter.domainView !== controls.domainView,
    !!controls.bucket && filter.bucket !== undefined && filter.bucket !== "WEEK",
    // A board-column keying the filter cannot honour reads as the default (the bar shows STAGE).
    !!controls.wipBy && filter.by !== undefined && filter.by !== "STAGE" && (filter.by !== "COLUMN" || wipColumnAvailable(filter)),
    !!controls.itemKind && filter.itemKind !== undefined && filter.itemKind !== "TASK",
  ];
  return differs.filter(Boolean).length;
}
