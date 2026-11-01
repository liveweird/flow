// Tasks and code (report 22) test fixtures: responses typed against the generated schema, so a spec drift breaks the fixtures and not just the page.
// Team Alpha (FILTERS): 40 done tasks, 24 of them with a merged merge request; one GitLab source; every distribution shown.
import type { ReportDrillItems } from "../api/reportDrill";
import type { Distribution, TaskCodeReport } from "../api/reports";
import { shownDistribution } from "./reportFixtures";

const META: TaskCodeReport["meta"] = {
  derivedAt: 1_780_000_000_000,
  configRevision: 4,
  from: "2026-07-02",
  to: "2026-09-29",
  level: "TEAM",
  domainView: "TASK",
  resolvedSprints: [],
  minSampleSize: 5,
  historyHorizon: null,
};

const hidden = (n: number): Distribution => ({ n, hidden: true, histogram: [] });

export const TASK_CODE: TaskCodeReport = {
  meta: META,
  sources: [{ connectionId: 7, name: "Stub GitLab", baseUrl: "http://gitlab-stub:8080", lastSyncAt: 1_780_000_000_000, commitFilesPending: 0, mrDetailsPending: 0 }],
  tasks: { done: 40, withMergeRequest: 24, withoutMergeRequest: 16, withoutShare: 0.4 },
  codeLeadTimeWorkingDays: shownDistribution([6, 9, 5, 3, 1], 0, 2, { p50: 3.5, p90: 7.5, p95: 8.5, mean: 3.9 }),
  mergedAfterDone: {
    count: 6,
    share: 0.25,
    lagWorkingDays: shownDistribution([3, 2, 1], 0, 1, { p50: 0.5, p90: 2.4, p95: 2.7, mean: 1 }),
  },
  quality: { neverStarted: 2, onlyUnmergedMrs: 3, tasksWithAmbiguousKey: 1 },
};

/** Nothing the join cannot use: no note at all. */
export const TASK_CODE_CLEAN: TaskCodeReport = { ...TASK_CODE, quality: { neverStarted: 0, onlyUnmergedMrs: 0, tasksWithAmbiguousKey: 0 } };

/** No GitLab source is active: nothing can link, so every task is "without" - the page must not present that as a finding. */
export const TASK_CODE_NO_SOURCES: TaskCodeReport = {
  ...TASK_CODE,
  sources: [],
  tasks: { done: 40, withMergeRequest: 0, withoutMergeRequest: 40, withoutShare: 1 },
  codeLeadTimeWorkingDays: hidden(0),
  mergedAfterDone: { count: 0, lagWorkingDays: hidden(0) },
  quality: { neverStarted: 0, onlyUnmergedMrs: 0, tasksWithAmbiguousKey: 0 },
};

/** Few tasks with a merged merge request: both distributions withheld, the counts stay. */
export const TASK_CODE_SMALL: TaskCodeReport = {
  ...TASK_CODE,
  tasks: { done: 9, withMergeRequest: 3, withoutMergeRequest: 6, withoutShare: 6 / 9 },
  codeLeadTimeWorkingDays: hidden(3),
  mergedAfterDone: { count: 1, share: 1 / 3, lagWorkingDays: hidden(1) },
  quality: { neverStarted: 0, onlyUnmergedMrs: 0, tasksWithAmbiguousKey: 0 },
};

/** A source is active but no task was done in the period: no share to print, three cards that say there is no data. */
export const TASK_CODE_QUIET: TaskCodeReport = {
  ...TASK_CODE,
  tasks: { done: 0, withMergeRequest: 0, withoutMergeRequest: 0 },
  codeLeadTimeWorkingDays: hidden(0),
  mergedAfterDone: { count: 0, lagWorkingDays: hidden(0) },
  quality: { neverStarted: 0, onlyUnmergedMrs: 0, tasksWithAmbiguousKey: 0 },
};

/** Done tasks, none with a merged merge request: no share for the merged-after-done tile. */
export const TASK_CODE_NO_MERGES: TaskCodeReport = {
  ...TASK_CODE,
  tasks: { done: 12, withMergeRequest: 0, withoutMergeRequest: 12, withoutShare: 1 },
  codeLeadTimeWorkingDays: hidden(0),
  mergedAfterDone: { count: 0, lagWorkingDays: hidden(0) },
  quality: { neverStarted: 0, onlyUnmergedMrs: 4, tasksWithAmbiguousKey: 0 },
};

const DONE_AT = 1_780_000_000_000;

/**
 * The tasks behind a count or a histogram bar: three listed (the cap, as far as the page cares), `count` of them behind the figure. Each task lists the merge
 * requests the figure counted for it as GitLab keys (`linkedKeys`): two, one, and none (a task nothing names).
 */
export function taskCodeItems(count: number, over: Partial<ReportDrillItems> = {}): ReportDrillItems {
  return {
    meta: { ...META, level: "TEAM" },
    bar: { count, sumMd: null, from: null, to: null },
    items: [
      { issueKey: "FLO-12", summary: "Add the export", itemKind: "TASK", accountId: "acc-ann", assignee: "Ann Author", teamId: 1, anchorAt: DONE_AT, figure: 5.5, linkedKeys: ["grp/app!12", "grp/sub/api!7"] },
      { issueKey: "FLO-30", summary: "Retry the upload", itemKind: "TASK", accountId: "acc-bob", assignee: "Bob Builder", teamId: 1, anchorAt: DONE_AT, figure: 2, linkedKeys: ["grp/app!15"] },
      { issueKey: "FLO-31", summary: null, itemKind: "TASK", accountId: null, assignee: null, teamId: 1, anchorAt: DONE_AT, figure: null, linkedKeys: [] },
    ],
    total: count,
    truncated: count > 3,
    ...over,
  };
}
