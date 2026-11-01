// Code activity (report 21) test fixtures: responses typed against the generated schema, so a spec drift breaks the fixtures and not just the page.
// Team Alpha (FILTERS): Ann with the busy volumes, Bob with a roster row of zeros; one GitLab source, 40 merged merge requests, every distribution shown.
import type { ReportDrillItems } from "../api/reportDrill";
import type { CodeActivityGroup, CodeActivityReport } from "../api/reports";
import { shownDistribution } from "./reportFixtures";

const META: CodeActivityReport["meta"] = {
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

const EMPTY_GROUP = {
  commits: 0,
  filesChanged: 0,
  linesAdded: 0,
  linesDeleted: 0,
  commitsLinesUnknown: 0,
  mrsMerged: 0,
  mrsOpened: 0,
  openMrs: 0,
  reviewsGiven: 0,
  approvalsGiven: 0,
  changesRequestedGiven: 0,
  comments: 0,
  reviewRequestsReceived: 0,
};

export const ANN: CodeActivityGroup = {
  ...EMPTY_GROUP,
  accountId: "acc-ann",
  label: "Ann Author",
  commits: 84,
  filesChanged: 210,
  linesAdded: 4159,
  linesDeleted: 1682,
  commitsLinesUnknown: 2,
  mrsMerged: 18,
  mrsOpened: 20,
  openMrs: 3,
  mrCycleWorkingDaysP50: 2.5,
  mrSizeLinesP50: 120,
  reviewsGiven: 11,
  approvalsGiven: 9,
  comments: 37,
};

/** A roster row with nothing in the period: kept, so the roster is visible. */
export const BOB: CodeActivityGroup = { ...EMPTY_GROUP, accountId: "acc-bob", label: "Bob Builder" };

export const CODE_ACTIVITY: CodeActivityReport = {
  meta: META,
  sources: [{ connectionId: 7, name: "Stub GitLab", baseUrl: "http://gitlab-stub:8080", lastSyncAt: 1_780_000_000_000, commitFilesPending: 0, mrDetailsPending: 0 }],
  quality: {
    unlinkedIdentities: 0,
    unlinkedCommits: 0,
    unlinkedMergeRequests: 0,
    unlinkedEvents: 0,
    commitsLinesUnknown: 0,
    mrsSizeUnknown: 0,
    mrsDetailsNotRead: 0,
    eventsUndated: 0,
  },
  flow: {
    merged: 40,
    closedUnmerged: 3,
    cycleWorkingDays: shownDistribution([10, 14, 9, 4, 3], 0, 2, { p50: 3.5, p90: 8, p95: 9, mean: 4 }),
    cycleElapsedDays: shownDistribution([8, 12, 10, 6, 4], 0, 3, { p50: 5, p90: 11, p95: 13, mean: 6 }),
    timeToFirstReviewWorkingDays: shownDistribution([20, 10, 4, 2, 1], 0, 1, { p50: 0.5, p90: 2.5, p95: 3, mean: 1 }),
    sizeLines: shownDistribution([15, 12, 8, 3, 2], 0, 100, { p50: 140, p90: 380, p95: 450, mean: 190 }),
    sizeFiles: shownDistribution([18, 11, 7, 3, 1], 0, 5, { p50: 6, p90: 17, p95: 20, mean: 8 }),
    mergedWithoutReview: { count: 4, share: 0.1 },
    mergedWithoutApproval: { count: 9, share: 0.225 },
    weekly: [
      { bucketStart: "2026-09-07", merged: 12 },
      { bucketStart: "2026-09-14", merged: 0 },
      { bucketStart: "2026-09-21", merged: 28 },
    ],
  },
  open: { count: 6, drafts: 2, age: { n: 6, hidden: false, p50: 2.5, p85: 6 } },
  review: { n: 38, reviewersPerMergedMrP50: 2, topReviewerShare: 0.35 },
  groups: [ANN, BOB],
};

/** The same team with nothing yet attributed, nothing read and two sources: the note and its pointers. */
export const CODE_ACTIVITY_PENDING: CodeActivityReport = {
  ...CODE_ACTIVITY,
  sources: [
    { connectionId: 7, name: "Stub GitLab", baseUrl: "http://gitlab-stub:8080", lastSyncAt: 1_780_000_000_000, commitFilesPending: 12, mrDetailsPending: 5 },
    { connectionId: 9, name: "Second GitLab", baseUrl: "http://gitlab-two:8080", commitFilesPending: 0, mrDetailsPending: 0 },
  ],
  quality: {
    unlinkedIdentities: 3,
    unlinkedCommits: 21,
    unlinkedMergeRequests: 4,
    unlinkedEvents: 30,
    commitsLinesUnknown: 2,
    mrsSizeUnknown: 1,
    mrsDetailsNotRead: 5,
    eventsUndated: 1,
  },
};

/** No GitLab source is active: the shell answers with an empty body. */
export const CODE_ACTIVITY_NO_SOURCES: CodeActivityReport = {
  ...CODE_ACTIVITY,
  sources: [],
  flow: { ...CODE_ACTIVITY.flow, merged: 0, closedUnmerged: 0, weekly: [] },
  review: undefined,
  groups: [],
};

/** Few merged merge requests: every percentile withheld, the review figures absent, the open ages withheld. */
export const CODE_ACTIVITY_SMALL: CodeActivityReport = {
  ...CODE_ACTIVITY,
  flow: {
    ...CODE_ACTIVITY.flow,
    merged: 2,
    closedUnmerged: 0,
    cycleWorkingDays: { n: 2, hidden: true, histogram: [] },
    cycleElapsedDays: { n: 2, hidden: true, histogram: [] },
    timeToFirstReviewWorkingDays: { n: 1, hidden: true, histogram: [] },
    sizeLines: { n: 2, hidden: true, histogram: [] },
    sizeFiles: { n: 2, hidden: true, histogram: [] },
    mergedWithoutReview: { count: 1 },
    mergedWithoutApproval: { count: 2 },
    weekly: [{ bucketStart: "2026-09-21", merged: 2 }],
  },
  open: { count: 1, drafts: 0, age: { n: 1, hidden: true } },
  review: { n: 2 },
};

/** The USER level: two picked people, every distribution split per person, the roster rows are the picks. */
export const CODE_ACTIVITY_USERS: CodeActivityReport = {
  ...CODE_ACTIVITY,
  meta: { ...META, level: "USER" },
  flow: {
    ...CODE_ACTIVITY.flow,
    cycleWorkingDays: {
      ...CODE_ACTIVITY.flow.cycleWorkingDays,
      byUser: [
        { accountId: "acc-ann", label: "Ann Author", counts: [6, 8, 5, 2, 1] },
        { accountId: "acc-bob", label: "Bob Builder", counts: [4, 6, 4, 2, 2] },
      ],
    },
  },
  groups: [ANN, BOB],
};

/** `teamId=0`'s answer: identities no one has linked, named by their GitLab names, and a ghost with no name at all. */
export const CODE_ACTIVITY_UNASSIGNED: CodeActivityReport = {
  ...CODE_ACTIVITY,
  groups: [
    { ...EMPTY_GROUP, label: "sample.stranger", commits: 5 },
    { ...EMPTY_GROUP, commits: 1 },
  ],
};

/** A source is active and synced, but nothing was merged or closed in the period: no weeks, nothing to measure, the roster still listed. */
export const CODE_ACTIVITY_QUIET: CodeActivityReport = {
  ...CODE_ACTIVITY,
  flow: {
    ...CODE_ACTIVITY.flow,
    merged: 0,
    closedUnmerged: 0,
    cycleWorkingDays: { n: 0, hidden: true, histogram: [] },
    cycleElapsedDays: { n: 0, hidden: true, histogram: [] },
    timeToFirstReviewWorkingDays: { n: 0, hidden: true, histogram: [] },
    sizeLines: { n: 0, hidden: true, histogram: [] },
    sizeFiles: { n: 0, hidden: true, histogram: [] },
    mergedWithoutReview: { count: 0 },
    mergedWithoutApproval: { count: 0 },
    weekly: [],
  },
  open: { count: 0, drafts: 0, age: { n: 0, hidden: true } },
  review: { n: 0 },
  groups: [BOB],
};

const MERGED_AT = Date.UTC(2026, 8, 10, 9);

/** The merge requests behind the merged tile / a histogram bar / a week: three listed (the cap, as far as the page cares), `count` of them behind the figure. */
export function codeMrItems(count: number, over: Partial<ReportDrillItems> = {}): ReportDrillItems {
  return {
    meta: { ...META, level: "TEAM" },
    bar: { count, sumMd: null, from: null, to: null },
    items: [
      { issueKey: "grp/app!12", summary: "Add the export", itemKind: "MERGE_REQUEST", accountId: "acc-ann", assignee: "Ann Author", teamId: 1, anchorAt: MERGED_AT, figure: 3 },
      { issueKey: "grp/sub/api!7", summary: "Fix the retry", itemKind: "MERGE_REQUEST", accountId: "acc-bob", assignee: "Bob Builder", teamId: 1, anchorAt: MERGED_AT, figure: 2 },
      { issueKey: "grp/app!3", summary: null, itemKind: "MERGE_REQUEST", accountId: null, assignee: null, teamId: 1, anchorAt: MERGED_AT, figure: null },
    ],
    total: count,
    truncated: count > 3,
    ...over,
  };
}

/** Ann's commits (84 behind the figure, three listed): `figure` is the counted lines, unknown for the last. */
export function codeCommitItems(count = 84): ReportDrillItems {
  return {
    meta: { ...META, level: "TEAM" },
    bar: { count, sumMd: null, from: null, to: null },
    items: [
      { issueKey: "grp/app@3f9a1c2b4d5e", summary: "Add the export", itemKind: "COMMIT", accountId: "acc-ann", assignee: "Ann Author", teamId: 1, anchorAt: MERGED_AT, figure: 240 },
      { issueKey: "grp/app@a1b2c3d4e5f6", summary: "Tidy", itemKind: "COMMIT", accountId: "acc-ann", assignee: "Ann Author", teamId: 1, anchorAt: MERGED_AT, figure: 12 },
      { issueKey: "grp/app@0123456789ab", summary: "Unknown lines", itemKind: "COMMIT", accountId: "acc-ann", assignee: "Ann Author", teamId: 1, anchorAt: MERGED_AT, figure: null },
    ],
    total: count,
    truncated: count > 3,
  };
}
