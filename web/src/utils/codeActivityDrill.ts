import type { paths } from "../api/schema";
import type { CodeActivityGroup, CodeActivityReport } from "../api/reports";
import type { DrillAnchor, DrillTarget, DrillUnit } from "./reportDrill";

/** Every `view` the server's drill answers (the generated schema is the source of the names). */
type ServerView = NonNullable<paths["/api/v1/reports/code-activity/items"]["get"]["parameters"]["query"]>["view"];

/** The five views that are a bar of a histogram: drawn through the `hist` target (`useHistogramDrill`), with the bar's `bucketIndex`. */
export const HISTOGRAM_VIEWS = [
  "MR_CYCLE_WORKING_DAYS",
  "MR_CYCLE_ELAPSED_DAYS",
  "TIME_TO_FIRST_REVIEW",
  "MR_SIZE_LINES",
  "MR_SIZE_FILES",
] as const satisfies readonly ServerView[];

/** A figure that is not a histogram bar: a tile, a member's cell or a weekly bar (the `code` target's views). */
export type CodeDrillView = Exclude<ServerView, (typeof HISTOGRAM_VIEWS)[number]>;

/** The target of a figure that is not a histogram bar: a tile, a member's cell or a weekly bar (`code:WEEK:2026-03-02`). */
export type CodeDrillTarget = Extract<DrillTarget, { kind: "code" }>;

/** How a view's list reads: the unit of its figure column (`none`: the list has none) and which instant its date column shows. */
interface CodeViewSpec {
  unit: DrillUnit;
  anchor: DrillAnchor;
}

const MERGED = { unit: "wd", anchor: "merged" } as const;
const ACT = { unit: "none", anchor: "event" } as const;

/**
 * Every figure view the server answers, keyed exhaustively (a view added to the spec fails the type check here): how its list is shown. A
 * histogram bar's list is shown by `CodeActivityDrillDrawer`'s own views.
 */
export const CODE_VIEWS: Record<CodeDrillView, CodeViewSpec> = {
  COMMITS: { unit: "lines", anchor: "committed" },
  MRS_MERGED: MERGED,
  MRS_OPENED: { unit: "lines", anchor: "created" },
  OPEN_MRS: { unit: "ageWd", anchor: "created" },
  OPEN_MRS_WAITING: { unit: "ageWd", anchor: "created" },
  MERGED_WITHOUT_REVIEW: MERGED,
  MERGED_WITHOUT_APPROVAL: MERGED,
  REVIEWS_GIVEN: ACT,
  APPROVALS_GIVEN: ACT,
  CHANGES_REQUESTED_GIVEN: ACT,
  COMMENTS: ACT,
  REVIEW_REQUESTS_RECEIVED: ACT,
  WEEK: MERGED,
};

/** The view named by `raw`, or `undefined` for anything the server does not answer (a hand-edited link). */
export function codeViewOf(raw: string): CodeDrillView | undefined {
  return (Object.keys(CODE_VIEWS) as CodeDrillView[]).find((view) => view === raw);
}

/** The focus-return anchors of a drill opened by a chart double-click: the flow card, the weekly card and the open-now card. */
export const CODE_FLOW_ANCHOR = "code-flow";
export const CODE_WEEKLY_ANCHOR = "code-weekly";
export const CODE_OPEN_ANCHOR = "code-open";

/**
 * What a page hands its figures to open a drill: `open` is the page's `useReportDrill.open` for a `code` target, `bar(view, anchor)` the opener of a
 * histogram bar (`useHistogramDrill`) and `disabled` says the data shown is kept data of a previous filter (a figure of it must not open a drill
 * under the new one).
 */
export interface CodeDrill {
  open: (target: CodeDrillTarget, from?: { trigger?: HTMLElement; anchor?: string }) => void;
  bar: (view: string, anchor?: string) => (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) => void;
  disabled: boolean;
}

/** The figure of the member's table a cell stands for: the view that lists it and the count the table shows (the drawer compares the two). */
export const MEMBER_CELLS: Readonly<
  Record<
    "commits" | "mrsMerged" | "mrsOpened" | "openMrs" | "reviewsGiven" | "approvalsGiven" | "comments",
    { view: CodeDrillView; count: (group: CodeActivityGroup) => number }
  >
> = {
  commits: { view: "COMMITS", count: (group) => group.commits },
  mrsMerged: { view: "MRS_MERGED", count: (group) => group.mrsMerged },
  mrsOpened: { view: "MRS_OPENED", count: (group) => group.mrsOpened },
  openMrs: { view: "OPEN_MRS", count: (group) => group.openMrs },
  reviewsGiven: { view: "REVIEWS_GIVEN", count: (group) => group.reviewsGiven },
  approvalsGiven: { view: "APPROVALS_GIVEN", count: (group) => group.approvalsGiven },
  comments: { view: "COMMENTS", count: (group) => group.comments },
};

/**
 * The item count the SHOWN report gives the figure a `code` target names, or `undefined` when the report has none for it (an unknown
 * person, a week it did not draw): the drawer compares it with the server's recomputed bar, and a target with no figure is dropped.
 * A member's cell is the group's own count; a tile or a weekly bar is the team's.
 */
export function codeDrillCount(report: CodeActivityReport, target: CodeDrillTarget): number | undefined {
  const { flow, open, groups } = report;
  if (target.segmentAccountId !== undefined) {
    const group = groups.find((candidate) => candidate.accountId === target.segmentAccountId);
    const cell = Object.values(MEMBER_CELLS).find((candidate) => candidate.view === target.view);
    return group === undefined || cell === undefined ? undefined : cell.count(group);
  }
  switch (target.view) {
    case "MRS_MERGED":
      return flow.merged;
    case "MERGED_WITHOUT_REVIEW":
      return flow.mergedWithoutReview.count;
    case "MERGED_WITHOUT_APPROVAL":
      return flow.mergedWithoutApproval.count;
    case "OPEN_MRS_WAITING":
      return open.count;
    case "WEEK":
      return flow.weekly.find((week) => week.bucketStart === target.bucketStart)?.merged;
    default:
      return undefined;
  }
}

/** The name a person is shown by in a drawer: the group's label, else the roster's display name, else the account id. */
export function memberName(report: CodeActivityReport, roster: ReadonlyArray<{ accountId: string; displayName: string }>, accountId: string): string {
  const group = report.groups.find((candidate) => candidate.accountId === accountId);
  return group?.label ?? roster.find((member) => member.accountId === accountId)?.displayName ?? accountId;
}
