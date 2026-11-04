// Merge-request planning (state, times, people) and the notes/approvals/reviewer states built on top of it.
import { DAY_MS, HOUR_MS, MIN_MS, REFERENCE_MS } from "./constants.mjs";
import { mrTitle } from "./text.mjs";
import { branchName, workTime } from "./repo.mjs";
import { aggregateFiles } from "./tree.mjs";
import { sha1 } from "./rng.mjs";
import { applyIssueKeyCases } from "./keycases.mjs";

const COMMENTS = [
  "Looks good overall, one question on the retry path.",
  "Could we add a test for the empty case?",
  "Fixed in the latest push.",
  "Thanks, merging once the pipeline is green.",
  "I would keep the old name here to avoid churn in the callers.",
  "Nit: this log line is noisy at info level.",
  "Agreed, moved the check into the constructor.",
  "Is this safe to deploy before the migration lands?",
  "Added the missing assertion.",
  "Please squash before merging.",
  "This reads much better than the previous version.",
  "Can you link the ticket in the description?",
];
const DIFF_COMMENTS = [
  "Should this be bounded?",
  "Why not reuse the existing helper here?",
  "This branch is never taken, can it go?",
  "Naming: this shadows the outer variable.",
  "Extract this into a function, it appears twice.",
  "Off by one at the upper bound?",
];

/** Plan the merge requests of one project. `contributors` = [{user, weight}], `bot` = the dependency bot or null. */
export function planMrs(rng, o) {
  const { project, count, contributors, bot, startMs, endMs, fullPath } = o;
  const humans = contributors.map((c) => c.user);
  const locked = [1, 3].includes(project.id) ? 1 : 0;
  const opened = Math.round(count * 0.25);
  const closed = Math.round(count * 0.13);
  const mergedN = Math.max(count - opened - closed - locked, 0);
  const states = rng.shuffle([...Array(mergedN).fill("merged"), ...Array(opened).fill("opened"), ...Array(closed).fill("closed"), ...Array(locked).fill("locked")]);
  const span = endMs - startMs;
  const plans = states.map((state, i) => {
    const isBot = bot && i % 7 === 2;
    const author = isBot ? bot : rng.pickWeighted(contributors.map((c) => [c.user, c.weight]));
    const others = humans.filter((h) => h !== author);
    const reviewers = rng.shuffle(others).slice(0, Math.min(others.length, rng.pickWeighted([[1, 60], [2, 40]])));
    const title = isBot ? `chore(deps): bump ${rng.pick(["lodash-es", "zod", "undici", "vite", "react-query"])} to ${rng.int(1, 6)}.${rng.int(0, 20)}.${rng.int(0, 9)}` : mrTitle(rng);
    const plan = {
      projectId: project.id, state, author, reviewers, assignees: isBot || rng.bool(0.3) ? [reviewers[0] ?? author] : [author], title, draft: false, wasDraft: false,
      squash: project.merge === "merge" && rng.bool(0.25), branchCount: isBot ? 1 : rng.pickWeighted([[1, 45], [2, 35], [3, 20]]), isBot: !!isBot,
      sourceBranch: branchName(rng, title), targetBranch: project.branch, labels: rng.shuffle(["backend", "bug", "feature", "tech-debt", "docs"]).slice(0, rng.int(0, 2)), fullPath,
      merger: reviewers[0] ?? humans.find((h) => h !== author) ?? author, closer: null, mergedMs: null, closedMs: null, day2Action: null,
    };
    if (state === "merged") {
      plan.mergedMs = workTime(rng, startMs + 3 * DAY_MS, startMs + Math.floor(span * 0.93));
      plan.createdMs = Math.max(startMs + HOUR_MS, plan.mergedMs - rng.int(2, 160) * HOUR_MS);
      plan.wasDraft = !isBot && rng.bool(0.06);
    } else if (state === "closed") {
      plan.createdMs = workTime(rng, startMs + 3 * DAY_MS, endMs - 20 * DAY_MS);
      plan.closedMs = plan.createdMs + rng.int(1, 15) * DAY_MS;
      plan.closer = rng.bool(0.5) ? author : plan.merger;
    } else {
      plan.createdMs = state === "locked" ? workTime(rng, endMs - 5 * DAY_MS, endMs - 6 * HOUR_MS) : workTime(rng, endMs - 40 * DAY_MS, endMs - 2 * HOUR_MS);
      plan.draft = state === "opened" && !isBot && rng.bool(0.35);
    }
    return plan;
  });
  states.length = 0;
  plans.sort((a, b) => a.createdMs - b.createdMs);
  plans.forEach((m, idx) => {
    m.iid = idx + 1;
    m.headSha = sha1(`head:${project.id}:${m.iid}`);
    if (m.draft) m.title = `Draft: ${m.title}`;
  });
  // Day-2 scripts: which opened merge requests get which update (see sample-data/README.md "gitlab-day2").
  const openNonDraft = plans.filter((m) => m.state === "opened" && !m.isBot && !m.draft);
  const drafts = plans.filter((m) => m.state === "opened" && m.draft);
  const script = { 1: ["changes"], 3: ["delete", "merge", "comment"], 4: ["close"], 2: ["comment"] }[project.id] ?? [];
  script.forEach((action, i) => {
    if (openNonDraft[i]) openNonDraft[i].day2Action = action;
  });
  if (project.id === 3) {
    // Guarantee one draft that is marked ready on day2.
    let draft = drafts.find((m) => !m.day2Action);
    if (!draft) {
      draft = plans.find((m) => m.state === "opened" && !m.isBot && !m.day2Action);
      if (draft) {
        draft.draft = true;
        draft.title = `Draft: ${draft.title}`;
      }
    }
    if (draft) draft.day2Action = "ready";
  }
  applyIssueKeyCases(plans, project);
  return plans;
}

const SYS = {
  draft: "marked this merge request as **draft**",
  ready: "marked this merge request as **ready**",
  approved: "approved this merge request",
  unapproved: "unapproved this merge request",
  changes: "requested changes",
  merged: "merged",
  closed: "closed",
  reopened: "reopened",
};
export const reviewRequested = (u) => `requested review from @${u.username}`;
const addedCommits = (mr, fromIdx, diffId, startSha) => {
  const cs = mr.branchCommits.slice(fromIdx);
  const list = cs.map((c) => `* ${c.sha.slice(0, 8)} - ${c.title}`).join("\n");
  return `added ${cs.length} commit${cs.length === 1 ? "" : "s"}\n\n${list}\n\n[Compare with previous version](/${mr.fullPath}/-/merge_requests/${mr.iid}/diffs?diff_id=${diffId}&start_sha=${startSha})`;
};

/** Builds `mr.notes` (chronological), `mr.approvedBy`, `mr.reviewerStates` for the STARTED state. Needs `mr.branchCommits`/`mr.sha`. */
export function buildMrActivity(rng, mr) {
  const notes = [];
  const sys = (at, user, body) => notes.push({ at, user, body, system: true, type: null });
  const plain = (at, user) => notes.push({ at, user, body: rng.pick(COMMENTS), system: false, type: null });
  const files = aggregateFiles(mr.branchCommits.map((c) => c.files)).filter((f) => !f.binary && f.diff);
  mr.files = aggregateFiles(mr.branchCommits.map((c) => c.files));
  const startSha = mr.branchCommits[0].parents[0] ?? sha1(`base:${mr.projectId}:${mr.iid}`);
  const diffNote = (at, user) => {
    const f = files.length ? rng.pick(files) : { newPath: "README.md", oldPath: "README.md" };
    const line = rng.int(1, 120);
    notes.push({
      at, user, body: rng.pick(DIFF_COMMENTS), system: false, type: "DiffNote",
      position: { base_sha: startSha, start_sha: startSha, head_sha: mr.sha, old_path: f.oldPath, new_path: f.newPath, position_type: "text", old_line: rng.bool(0.25) ? line : null, new_line: line, line_range: null },
      resolved: rng.bool(0.6),
    });
  };
  const t0 = mr.createdMs;
  const end = mr.state === "merged" ? mr.mergedMs : mr.state === "closed" ? mr.closedMs : Math.min(REFERENCE_MS - HOUR_MS, t0 + rng.int(1, 20) * DAY_MS);
  const lastAct = Math.max(t0 + 2 * HOUR_MS, end - 5 * MIN_MS);
  if (mr.draft || mr.wasDraft) sys(t0 + 20_000, mr.author, SYS.draft);
  mr.reviewers.forEach((r, i) => sys(t0 + (i + 1) * rng.int(1, 8) * MIN_MS, mr.author, reviewRequested(r)));
  const k = mr.branchCommits.length;
  if (k >= 2) {
    for (let j = 1; j < Math.min(k, 3); j++) sys(t0 + Math.round(((lastAct - t0) * j) / k), mr.author, addedCommits(mr, j, j + 1, startSha));
  }
  if (mr.wasDraft) sys(t0 + Math.round((lastAct - t0) * 0.4), mr.author, SYS.ready);
  const states = new Map();
  for (const r of mr.reviewers) {
    const outcome = rng.pickWeighted(
      mr.state === "merged" ? [["approve", 60], ["changes_then_approve", 12], ["comment", 18], ["none", 10]]
        : mr.state === "opened" ? [["approve", 30], ["changes", 25], ["comment", 25], ["none", 20]]
          : mr.state === "locked" ? [["approve", 100]] : [["comment", 40], ["none", 40], ["changes", 20]],
    );
    const when = () => t0 + HOUR_MS + Math.floor(rng.float() * Math.max(lastAct - t0 - HOUR_MS, MIN_MS));
    let state = "unreviewed";
    for (let c = rng.int(0, outcome === "none" ? 0 : 3); c > 0; c--) {
      (rng.bool(0.45) ? diffNote : plain)(when(), r);
      state = "reviewed";
    }
    if (outcome === "approve" || outcome === "changes_then_approve") {
      const ta = Math.max(when(), t0 + 2 * HOUR_MS);
      if (outcome === "changes_then_approve") sys(Math.max(t0 + HOUR_MS, ta - rng.int(1, 30) * HOUR_MS), r, SYS.changes);
      sys(ta, r, SYS.approved);
      state = "approved";
      states.set(r.id, { user: r, state, at: ta });
      continue;
    }
    if (outcome === "changes") {
      const tr = when();
      sys(tr, r, SYS.changes);
      state = "requested_changes";
      states.set(r.id, { user: r, state, at: tr });
      continue;
    }
    states.set(r.id, { user: r, state, at: t0 + (mr.reviewers.indexOf(r) + 1) * MIN_MS });
  }
  // Author replies, so threads have two voices.
  for (let c = rng.int(1, 4); c > 0; c--) (rng.bool(0.4) ? diffNote : plain)(t0 + 2 * HOUR_MS + Math.floor(rng.float() * Math.max(lastAct - t0 - 2 * HOUR_MS, MIN_MS)), mr.author);
  if (mr.state === "merged") sys(mr.mergedMs, mr.merger, SYS.merged);
  if (mr.state === "closed") sys(mr.closedMs, mr.closer, SYS.closed);
  if (mr.busy) {
    for (let i = 0; i < mr.busy; i++) plain(t0 + 2 * HOUR_MS + Math.round(((lastAct - t0 - 2 * HOUR_MS) * i) / mr.busy), i % 2 ? mr.author : mr.reviewers[0] ?? mr.author);
  }
  notes.sort((a, b) => a.at - b.at || (a.system === b.system ? 0 : a.system ? -1 : 1));
  mr.notes = notes;
  mr.approvedBy = notes.filter((n) => n.system && n.body === SYS.approved).map((n) => ({ user: n.user, at: n.at }));
  mr.reviewerStates = mr.reviewers.map((r) => states.get(r.id));
  mr.updatedMs = Math.max(t0, ...notes.map((n) => n.at), mr.state === "merged" ? mr.mergedMs : 0, mr.state === "closed" ? mr.closedMs : 0);
}

/**
 * Day-2 patch of one merge request: a `day2` block holding the overrides and the extra notes. The repository side of a
 * day-2 merge (the commits) is created by day2.mjs, which passes `mergeCommitSha`/`mergedMs` in.
 */
export function buildDay2Patch(rng, mr, extra = {}) {
  const at = (h) => REFERENCE_MS + h * HOUR_MS + rng.int(0, 59) * MIN_MS;
  const notes = [];
  const sys = (t, user, body) => notes.push({ at: t, user, body, system: true, type: null });
  const patch = {};
  const reviewer = mr.reviewers[0] ?? mr.author;
  const reviewerStates = mr.reviewerStates.map((s) => ({ ...s }));
  const setState = (u, state, t) => {
    const s = reviewerStates.find((x) => x.user === u);
    if (s) Object.assign(s, { state, at: t });
  };
  let approvedBy = [...mr.approvedBy];
  switch (mr.day2Action) {
    case "delete":
      return { deleted: true };
    case "changes": {
      const t = at(3);
      sys(t, reviewer, SYS.changes);
      notes.push({ at: t + 2 * MIN_MS, user: reviewer, body: "Two requests inline; happy to re-review after the next push.", system: false, type: null });
      setState(reviewer, "requested_changes", t);
      break;
    }
    case "close": {
      const t = at(5);
      sys(t, mr.author, SYS.closed);
      Object.assign(patch, { state: "closed", closedMs: t, closer: mr.author });
      break;
    }
    case "ready": {
      const t = at(2);
      sys(t, mr.author, SYS.ready);
      Object.assign(patch, { draft: false, title: mr.title.replace(/^Draft: /, "") });
      break;
    }
    case "comment": {
      notes.push({ at: at(4), user: reviewer, body: rng.pick(COMMENTS), system: false, type: null });
      break;
    }
    case "merge": {
      const ta = extra.mergedMs - 2 * HOUR_MS;
      sys(ta, reviewer, SYS.approved);
      approvedBy = [...approvedBy, { user: reviewer, at: ta }];
      setState(reviewer, "approved", ta);
      sys(extra.mergedMs, mr.merger, SYS.merged);
      Object.assign(patch, { state: "merged", mergedMs: extra.mergedMs, mergeCommitSha: extra.mergeCommitSha, squashCommitSha: null });
      break;
    }
    default:
      break;
  }
  const all = [...mr.notes, ...notes].sort((a, b) => a.at - b.at);
  return {
    ...patch, notes: all, newNoteCount: notes.length, approvedBy, reviewerStates,
    updatedMs: Math.max(mr.updatedMs, ...notes.map((n) => n.at), patch.mergedMs ?? 0, patch.closedMs ?? 0),
  };
}

/** The merge request as served in `state` ('Started' or 'day2'). */
export function effectiveMr(mr, state) {
  if (state === "day2" && mr.day2) {
    if (mr.day2.deleted) return null;
    return { ...mr, ...mr.day2 };
  }
  return mr;
}
