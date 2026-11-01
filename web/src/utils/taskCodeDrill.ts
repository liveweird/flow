import type { paths } from "../api/schema";
import type { TaskCodeReport } from "../api/reports";
import type { DrillAnchor, DrillTarget, DrillUnit } from "./reportDrill";

/** Every `view` the server's task-code drill answers (the generated schema is the source of the names). */
type ServerView = NonNullable<paths["/api/v1/reports/task-code/items"]["get"]["parameters"]["query"]>["view"];

/** The two views that are a bar of a histogram: drawn through the `hist` target (`useHistogramDrill`), with the bar's `bucketIndex`. */
type TaskCodeHistogramView = Extract<ServerView, "CODE_LEAD_TIME" | "MERGED_AFTER_DONE_LAG">;

/** A figure that is not a histogram bar: a count (the `taskcode` target's views). */
export type TaskCodeFigureView = Exclude<ServerView, TaskCodeHistogramView>;

/** The target of a count of the page: the tasks with a merged merge request, without one, or whose code merged after done. */
export type TaskCodeDrillTarget = Extract<DrillTarget, { kind: "taskcode" }>;

/** How a view's list reads: the unit of its figure column (`none`: the list has none) and which instant its date column shows. */
interface TaskCodeViewSpec {
  unit: DrillUnit;
  anchor: DrillAnchor;
}

/**
 * Every figure view the server answers, keyed exhaustively (a view added to the spec fails the type check here). The figure is the task's code
 * lead time (with a merge request) or the lag (merged after done), both in working days; the without list has none.
 */
export const TASK_CODE_VIEWS: Record<TaskCodeFigureView, TaskCodeViewSpec> = {
  TASKS_WITH_MR: { unit: "wd", anchor: "done" },
  TASKS_WITHOUT_MR: { unit: "none", anchor: "done" },
  MERGED_AFTER_DONE: { unit: "wd", anchor: "done" },
};

/** The figure view named by `raw`, or `undefined` for anything the server does not answer as a count (a hand-edited link). */
export function taskCodeViewOf(raw: string): TaskCodeFigureView | undefined {
  return (Object.keys(TASK_CODE_VIEWS) as TaskCodeFigureView[]).find((view) => view === raw);
}

/** The focus-return anchors of a drill opened by a chart double-click: the tasks card, the lead time card and the merged-after-done card. */
export const TASK_CODE_TASKS_ANCHOR = "task-code-tasks";
export const TASK_CODE_LEAD_ANCHOR = "task-code-lead";
export const TASK_CODE_AFTER_ANCHOR = "task-code-after";

/** The card a target was opened from (`useReportDrill`'s fallback once the pressed element is gone). */
export function taskCodeAnchor(target: DrillTarget): string {
  const view = target.kind === "taskcode" || target.kind === "hist" ? target.view : "";
  if (view === "CODE_LEAD_TIME") return TASK_CODE_LEAD_ANCHOR;
  return view === "MERGED_AFTER_DONE" || view === "MERGED_AFTER_DONE_LAG" ? TASK_CODE_AFTER_ANCHOR : TASK_CODE_TASKS_ANCHOR;
}

/**
 * What a page hands its figures to open a drill: `open` is the page's `useReportDrill.open` for a `taskcode` target, `bar(view, anchor)` the opener of a
 * histogram bar (`useHistogramDrill`) and `disabled` says the data shown is kept data of a previous filter (a figure of it must not open a drill
 * under the new one).
 */
export interface TaskCodeDrill {
  open: (target: TaskCodeDrillTarget, from?: { trigger?: HTMLElement; anchor?: string }) => void;
  bar: (view: string, anchor?: string) => (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) => void;
  disabled: boolean;
}

/** The item count the SHOWN report gives the figure a `taskcode` target names: the drawer compares it with the server's recomputed bar. */
export function taskCodeDrillCount(report: TaskCodeReport, view: TaskCodeFigureView): number {
  switch (view) {
    case "TASKS_WITH_MR":
      return report.tasks.withMergeRequest;
    case "TASKS_WITHOUT_MR":
      return report.tasks.withoutMergeRequest;
    case "MERGED_AFTER_DONE":
      return report.mergedAfterDone.count;
  }
}
