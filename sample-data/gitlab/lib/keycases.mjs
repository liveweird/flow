// Deterministic issue-key edge cases (gitlab-integration.md "Issue keys"): a handful of in-scope merge requests get a hand-written title
// and source branch so the candidate extractor meets every shape it must handle, whatever the seeded generator happened to draw. They are
// applied AFTER a project's merge requests are planned and draw nothing from the PRNG, so every other figure of the world keeps its
// stream (only the retitled merge requests' own text, and the squash commits' shas that hash it, differ). Keys are the Jira stub's
// synthetic ones (FLO/PLT/GTM/OPS) plus invented ones the Jira stub does not know (ABC, SEC).
//
// Each case: the merge request's `title`, its `branch` (the source branch), and `wants` - the candidates a correct extractor finds
// (`[key, inTitle, inBranch]`), pinned by generate.test.mjs against the independent oracle in expected.mjs.

/** Per project id: the cases, in order; case i takes the (OFFSETS[i])-th eligible merge request of the project. */
export const KEY_CASES = {
  1: [
    { name: "twoKeys", title: "feat(ledger): post the nightly import in order (FLO-120) (PLT-45)", branch: "feature/post-the-nightly-import-in-order", wants: [["FLO-120", true, false], ["PLT-45", true, false]] },
    { name: "titleAndBranch", title: "FLO-210: guard the retry budget under load", branch: "fix/flo-210-guard-the-retry-budget", wants: [["FLO-210", true, true]] },
    { name: "branchOnlyLowercase", title: "fix(cache): drop stale session token after a restart", branch: "fix/plt-77-drop-stale-session-token", wants: [["PLT-77", false, true]] },
    { name: "movedIssue", title: "FLO-366: rename the pending order state", branch: "chore/rename-the-pending-order-state", wants: [["FLO-366", true, false]] },
  ],
  2: [
    { name: "junkUtf8", title: "fix(import): handle byte order marks in the catalog feed", branch: "fix/handle-utf-8-bom-in-the-catalog-feed", wants: [["UTF-8", false, true]] },
    { name: "junkV23", title: "chore(api): bump the schema to the second revision", branch: "chore/bump-schema-v2-3", wants: [["V2-3", false, true]] },
    { name: "underscoreSuffix", title: "feat(queue): add the backpressure gauge", branch: "feature/fix_flo-8-backpressure-gauge", wants: [["FIX_FLO-8", false, true], ["FLO-8", false, true]] },
  ],
  3: [
    { name: "unknownProject", title: "ABC-12: tidy the route table reload", branch: "chore/tidy-the-route-table-reload", wants: [["ABC-12", true, false]] },
    { name: "unconfiguredProject", title: "fix(auth): bound the session lookup (SEC-5)", branch: "fix/bound-the-session-lookup", wants: [["SEC-5", true, false]] },
  ],
  4: [
    { name: "beyondRange", title: "FLO-900: expose the tenant quota check", branch: "feature/expose-the-tenant-quota-check", wants: [["FLO-900", true, false]] },
  ],
};

/** Which eligible merge request (0-based, in iid order) each case of a project takes: spread out so neighbours stay untouched. */
const OFFSETS = [1, 3, 5, 7];

/**
 * Overwrites `title` and `sourceBranch` of the project's eligible plans: a human author, not a draft, no day-2 script (the day-2
 * actions rewrite titles). Marks the plan `keyCase` (the case name) for the oracle test. A project too small to have the Nth eligible
 * merge request simply gets fewer cases.
 */
export function applyIssueKeyCases(plans, project) {
  const cases = KEY_CASES[project.id] ?? [];
  const eligible = plans.filter((m) => !m.isBot && !m.draft && !m.day2Action).sort((a, b) => a.iid - b.iid);
  cases.forEach((c, i) => {
    const plan = eligible[OFFSETS[i]];
    if (!plan) return;
    Object.assign(plan, { title: c.title, sourceBranch: c.branch, keyCase: c.name });
  });
}
