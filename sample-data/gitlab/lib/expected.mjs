// expected.json: the ground truth the server tests assert against, derived from the same world model that wrote the
// stub (never hand-copied). Also the generator's own consistency checks (`verifyWorld`).
import { DAY2_MS, HOST, REFERENCE_ISO, SCENARIO, GITLAB_VERSION } from "./constants.mjs";
import { countDiff, hunksConsistent } from "./diff.mjs";
import { ancestors, logWalk, mergeBase } from "./graph.mjs";
import { DEFAULT_EXCLUSIONS, isExcluded } from "./tree.mjs";
import { identityKeyFor, jiraAlignment } from "./users.mjs";
import { effectiveMr } from "./mrs.mjs";
import { isoZ } from "./format.mjs";

const FILE_CAP = 1000;
const PAGE = 100;

export function prettyJson(value, indent = 0, width = 150) {
  const flat = JSON.stringify(value);
  if (value === null || typeof value !== "object" || flat.length + indent * 2 <= width) return flat;
  const pad = "  ".repeat(indent);
  if (Array.isArray(value)) return `[\n${value.map((v) => `${pad}  ${prettyJson(v, indent + 1, width)}`).join(",\n")}\n${pad}]`;
  const entries = Object.entries(value).filter(([, v]) => v !== undefined);
  return `{\n${entries.map(([k, v]) => `${pad}  ${JSON.stringify(k)}: ${prettyJson(v, indent + 1, width)}`).join(",\n")}\n${pad}}`;
}

export function verifyWorld(world) {
  for (const repo of world.repos.values()) {
    for (const c of repo.commits.values()) {
      for (const f of c.files) {
        if (f.tooLarge || f.collapsed) {
          if (f.diff !== "") throw new Error(`${c.sha} ${f.newPath}: a too_large/collapsed file must have an empty diff`);
          continue;
        }
        const n = countDiff(f.diff);
        if (n.additions !== f.additions || n.deletions !== f.deletions) throw new Error(`${c.sha} ${f.newPath}: counted ${JSON.stringify(n)} but generated +${f.additions} -${f.deletions}`);
        if (!hunksConsistent(f.diff)) throw new Error(`${c.sha} ${f.newPath}: inconsistent hunk header counts`);
      }
    }
  }
}

const fileRec = (f) => {
  const r = { path: f.newPath, additions: f.additions, deletions: f.deletions };
  if (f.renamedFile) r.oldPath = f.oldPath;
  if (f.newFile) r.new = true;
  if (f.deletedFile) r.deleted = true;
  if (f.renamedFile) r.renamed = true;
  if (f.binary) r.binary = true;
  if (f.generatedFile) r.generated = true;
  if (f.tooLarge) r.tooLarge = true;
  if (f.collapsed) r.collapsed = true;
  if (!f.tooLarge && !f.collapsed && f.diff.includes("\\ No newline at end of file")) r.noNewlineMarker = true;
  return r;
};

const sum = (list, f) => list.reduce((s, x) => s + f(x), 0);
const totals = (files) => ({ files: files.length, additions: sum(files, (f) => f.additions), deletions: sum(files, (f) => f.deletions) });

function classifyNote(n) {
  if (!n.system) return n.type === "DiffNote" ? "DIFF_COMMENT" : "COMMENT";
  if (n.body === "approved this merge request") return "APPROVAL";
  if (n.body.startsWith("unapproved")) return "UNAPPROVAL";
  if (n.body.startsWith("requested review from")) return "REVIEW_REQUESTED";
  if (n.body === "requested changes") return "CHANGES_REQUESTED";
  if (n.body.startsWith("added ")) return "COMMITS_ADDED";
  return "OTHER_SYSTEM";
}
const countBy = (list, key) => list.reduce((acc, x) => ({ ...acc, [key(x)]: (acc[key(x)] ?? 0) + 1 }), {});
const sortedObj = (o) => Object.fromEntries(Object.entries(o).sort(([a], [b]) => (a < b ? -1 : 1)));

export function buildExpected(world, { mappingCount }) {
  const { users, projects, repos, mrs, heads } = world;
  const byId = new Map(projects.map((p) => [p.id, p]));
  const scopeIds = (scope) => projects.filter((p) => p.scope === scope && !p.day2Only).map((p) => p.id);
  const inScope = scopeIds("in");

  // ---- commits (Started history of each project) -------------------------------------------------------------------
  const commitsOut = {};
  const commitFiles = {};
  const identity = new Map();
  const excl = {};
  let outOfOrder = 0;
  let rebased = 0;
  for (const p of projects) {
    const repo = repos.get(p.id);
    if (!repo.views.started) continue;
    const walk = logWalk(repo.commits, repo.views.started.tip);
    const nonMerge = walk.filter((c) => !c.isMerge);
    commitsOut[p.id] = {
      total: walk.length, nonMerge: nonMerge.length, merges: walk.length - nonMerge.length, tip: repo.views.started.tip, pages: Math.max(1, Math.ceil(walk.length / PAGE)),
      stats: { additions: sum(nonMerge, (c) => c.stats.additions), deletions: sum(nonMerge, (c) => c.stats.deletions) },
      filesChangedNonMerge: sum(nonMerge, (c) => c.files.length),
    };
    commitFiles[p.id] = {};
    for (const c of walk) {
      commitFiles[p.id][c.sha] = {
        parents: c.parents.length, merge: c.isMerge, kind: c.kind, stats: { additions: c.stats.additions, deletions: c.stats.deletions }, diffPages: Math.max(1, Math.ceil(c.files.length / PAGE)),
        files: c.files.map(fileRec),
      };
    }
    if (p.scope === "in") {
      outOfOrder += walk.filter((c) => c.parents.length && repo.commits.get(c.parents[0]).committedMs > c.committedMs).length;
      rebased += walk.filter((c) => c.committedMs - c.authoredMs > 86_400_000).length;
      const acc = { raw: { files: 0, additions: 0, deletions: 0 }, counted: { files: 0, additions: 0, deletions: 0 }, countedUncapped: { files: 0, additions: 0, deletions: 0 }, filesCapped: 0 };
      for (const c of nonMerge) {
        const capped = c.files.slice(0, FILE_CAP);
        const keep = capped.filter((f) => !isExcluded(f.newPath));
        const keepAll = c.files.filter((f) => !isExcluded(f.newPath));
        const add = (t, files) => {
          const x = totals(files);
          t.files += x.files;
          t.additions += x.additions;
          t.deletions += x.deletions;
        };
        add(acc.raw, c.files);
        add(acc.counted, keep);
        add(acc.countedUncapped, keepAll);
        if (c.files.length > FILE_CAP) acc.filesCapped++;
        commitFiles[p.id][c.sha].counted = totals(keep);
      }
      excl[p.id] = acc;
      for (const c of nonMerge) {
        const { key, user } = identityKeyFor(users, c.authorEmail);
        const e = identity.get(key) ?? { key, userId: user?.id ?? null, username: user?.username ?? null, kind: user ? user.kind : "unmatched", commitsNonMerge: 0, perProject: {}, emails: new Set() };
        e.commitsNonMerge++;
        e.perProject[p.id] = (e.perProject[p.id] ?? 0) + 1;
        e.emails.add(c.authorEmail);
        identity.set(key, e);
      }
      for (const c of walk.filter((x) => x.isMerge)) {
        const { key } = identityKeyFor(users, c.authorEmail);
        const e = identity.get(key);
        if (e) e.merges = (e.merges ?? 0) + 1;
      }
    }
  }
  const sumExcl = (k) => ({ files: sum(Object.values(excl), (x) => x[k].files), additions: sum(Object.values(excl), (x) => x[k].additions), deletions: sum(Object.values(excl), (x) => x[k].deletions) });
  const identities = [...identity.values()].map((e) => ({ ...e, merges: e.merges ?? 0, emails: [...e.emails].sort() })).sort((a, b) => b.commitsNonMerge - a.commitsNonMerge || (a.key < b.key ? -1 : 1));

  // ---- special commits -----------------------------------------------------------------------------------------------
  const findSpecial = (name) => {
    for (const [pid, repo] of repos) if (repo.specials[name]) return { projectId: pid, sha: repo.specials[name], commit: repo.commits.get(repo.specials[name]) };
    return null;
  };
  const special = {};
  const big = findSpecial("bigMigration");
  if (big) {
    const files = big.commit.files;
    special.manyFiles = {
      projectId: big.projectId, sha: big.sha, files: files.length, diffPages: Math.ceil(files.length / PAGE), cap: FILE_CAP,
      firstCappedFilesTotals: totals(files.slice(0, FILE_CAP)), allFilesTotals: totals(files), stats: big.commit.stats,
      countedAfterDefaultExclusionsCapped: totals(files.slice(0, FILE_CAP).filter((f) => !isExcluded(f.newPath))),
      countedAfterDefaultExclusionsUncapped: totals(files.filter((f) => !isExcluded(f.newPath))),
      renamedFiles: files.filter((f) => f.renamedFile).length, generatedFiles: files.filter((f) => f.generatedFile).length,
    };
  }
  const tl = findSpecial("tooLarge");
  if (tl) {
    const files = tl.commit.files;
    const tooLargeFile = files.find((f) => f.tooLarge);
    const others = files.filter((f) => !f.tooLarge);
    special.tooLarge = {
      projectId: tl.projectId, sha: tl.sha, path: tooLargeFile.newPath, trueAdditions: tooLargeFile.additions, trueDeletions: tooLargeFile.deletions, commitStats: tl.commit.stats,
      otherFiles: others.map(fileRec), otherFilesRaw: totals(others), otherFilesCounted: totals(others.filter((f) => !isExcluded(f.newPath))),
      fallbackByDesign: {
        additions: tl.commit.stats.additions - sum(others, (f) => f.additions),
        deletions: tl.commit.stats.deletions - sum(others, (f) => f.deletions),
        note: "commit stats minus the OTHER files' RAW lines (gitlab-integration.md 'Path exclusions'); equals the true lines of the too_large file",
      },
      fallbackSubtractingCountedOthersSuperseded: {
        additions: tl.commit.stats.additions - sum(others.filter((f) => !isExcluded(f.newPath)), (f) => f.additions),
        deletions: tl.commit.stats.deletions - sum(others.filter((f) => !isExcluded(f.newPath)), (f) => f.deletions),
        note: "superseded: stats minus the other files' COUNTED lines also absorbs the excluded files' lines; kept for comparison only",
      },
    };
  }
  const col = findSpecial("collapsed");
  if (col) special.collapsed = { projectId: col.projectId, sha: col.sha, path: col.commit.files.find((f) => f.collapsed).newPath, trueAdditions: col.commit.files.find((f) => f.collapsed).additions, trueDeletions: col.commit.files.find((f) => f.collapsed).deletions, commitStats: col.commit.stats };
  const hostile = findSpecial("hostileLines");
  if (hostile) special.hostileLines = { projectId: hostile.projectId, sha: hostile.sha, files: hostile.commit.files.map(fileRec), note: "added lines starting '++++'/'+---'/'+@@' and removed lines starting '---' are real content lines and count" };
  const allCommits = [...repos.values()].flatMap((r) => [...r.commits.values()]);
  const firstWith = (pred) => {
    for (const [pid, repo] of repos) for (const c of [...repo.commits.values()].sort((a, b) => a.seq - b.seq)) { const f = c.files.find(pred); if (f) return { projectId: pid, sha: c.sha, ...fileRec(f) }; }
    return null;
  };
  special.examples = {
    binaryFile: firstWith((f) => f.binary && !f.newFile && !f.deletedFile), newBinaryFile: firstWith((f) => f.binary && f.newFile), deletedFile: firstWith((f) => f.deletedFile && !f.binary),
    pureRename: firstWith((f) => f.renamedFile && f.additions === 0 && f.deletions === 0 && f.diff === "" && !f.newFile), renameWithEdits: firstWith((f) => f.renamedFile && f.additions + f.deletions > 0),
    noNewlineAtEndOfFile: firstWith((f) => f.diff.includes("\\ No newline at end of file")), minifiedBundle: firstWith((f) => /\.min\.js$/.test(f.newPath)),
    lockFile: firstWith((f) => /(^|\/)(package-lock\.json|go\.sum|poetry\.lock)$/.test(f.newPath)),
  };

  // ---- merge requests -------------------------------------------------------------------------------------------------
  const nullGap = world.nullGapMr;
  const mrList = (state) => mrs.map((m) => (m.day2Only && state === "Started" ? null : effectiveMr(m, state === "day2" ? "day2" : "Started"))).filter(Boolean);
  const mrRow = (m) => ({
    projectId: m.projectId, iid: m.iid, id: m.id, state: m.state, draft: m.draft, title: m.title, authorId: m.author.id, authorKind: m.author.kind, reviewerIds: m.reviewers.map((r) => r.id),
    assigneeIds: m.assignees.map((a) => a.id), mergedById: m.state === "merged" ? m.merger.id : null, sha: m.sha, mergeCommitSha: m.mergeCommitSha ?? null, squashCommitSha: m.squashCommitSha ?? null,
    createdAt: isoZ(m.createdMs), updatedAt: isoZ(m.updatedMs), mergedAt: m.state === "merged" ? isoZ(m.mergedMs) : null, closedAt: m.state === "closed" ? isoZ(m.closedMs) : null,
    notes: m.notes.length, notePages: Math.max(1, Math.ceil(m.notes.length / PAGE)), approvals: m.approvedBy.length, reviewerStates: m.reviewerStates.map((s) => ({ userId: s.user.id, state: s.state })),
    diffStats: nullGap && nullGap.projectId === m.projectId && nullGap.iid === m.iid ? null : { additions: sum(m.files, (f) => f.additions), deletions: sum(m.files, (f) => f.deletions), fileCount: m.files.length },
  });
  const started = mrList("Started");
  const eventsOf = (list) => {
    const notes = list.flatMap((m) => m.notes);
    return {
      notesByKind: sortedObj(countBy(notes, classifyNote)), noteTotal: notes.length,
      approvalsEndpointTotal: sum(list, (m) => m.approvedBy.length), reviewerStates: sortedObj(countBy(list.flatMap((m) => m.reviewerStates), (s) => s.state)),
      reviewersTotal: sum(list, (m) => m.reviewerStates.length),
    };
  };
  const mergeRequests = {
    total: started.length, byState: sortedObj(countBy(started, (m) => m.state)), drafts: started.filter((m) => m.draft).length, perProject: sortedObj(countBy(started, (m) => String(m.projectId))),
    mergedOnMain: started.filter((m) => m.state === "merged" && (m.mergeCommitSha || m.squashCommitSha)).length, nullDiffStatsGap: nullGap, groupListPages: {},
    busiestNotes: Math.max(...started.map((m) => m.notes.length)), list: started.map(mrRow),
  };
  for (const g of world.groups) {
    const ids = [g.id, ...world.groups.filter((x) => x.parentId === g.id).map((x) => x.id)];
    const n = started.filter((m) => ids.includes(byId.get(m.projectId).groupId)).length;
    mergeRequests.groupListPages[g.id] = { total: n, pages: Math.max(1, Math.ceil(n / PAGE)) };
  }
  // The MR merge/squash shas must be real default-branch commits.
  for (const m of started) {
    for (const sha of [m.mergeCommitSha, m.squashCommitSha]) {
      if (sha && !ancestors(repos.get(m.projectId).commits, repos.get(m.projectId).views.started.tip).has(sha) && m.squashCommitSha !== sha) throw new Error(`MR ${m.projectId}!${m.iid} points at a commit that is not on the default branch`);
    }
  }

  // ---- stored heads -------------------------------------------------------------------------------------------------
  const storedHeads = {};
  for (const [pid, h] of Object.entries(heads)) {
    const repo = repos.get(Number(pid));
    const day2 = repo.views.day2;
    storedHeads[pid] = {
      storedSha: h.storedSha, storedIndexOnFirstParentChain: h.storedIndex, commitsAfterStoredHead: logWalk(repo.commits, repo.views.started.tip, h.storedSha).length,
      startedTipSha: h.startedTipSha, rangeFromStartedTipInStarted: 0,
      day2: day2 ? {
        tip: day2.tip, branch: day2.branch, commitsAfterStoredHead: logWalk(repo.commits, day2.tip, h.storedSha).length, commitsAfterStartedTip: logWalk(repo.commits, day2.tip, h.startedTipSha).length,
        mergeBaseWithStoredHead: mergeBase(repo.commits, h.storedSha, day2.tip).sha, mergeBaseWithStartedTip: mergeBase(repo.commits, h.startedTipSha, day2.tip).sha,
        startedTipIsAncestorOfDay2Tip: ancestors(repo.commits, day2.tip).has(h.startedTipSha),
      } : null,
    };
  }

  // ---- day2 ----------------------------------------------------------------------------------------------------------
  const r1 = repos.get(1);
  const day2Mrs = mrs.filter((m) => m.day2 || m.day2Only);
  const day2Listing = {};
  for (const p of projects) {
    const repo = repos.get(p.id);
    if (repo.views.day2) day2Listing[p.id] = { total: logWalk(repo.commits, repo.views.day2.tip).length, tip: repo.views.day2.tip, branch: repo.views.day2.branch };
  }
  const deleted = mrs.find((m) => m.day2?.deleted);
  const groupLists = (state) => Object.fromEntries(world.groups.map((g) => {
    const ids = [g.id, ...world.groups.filter((x) => x.parentId === g.id).map((x) => x.id)];
    const inGroup = (p, st) => {
      const repo = repos.get(p.id);
      const moved = st === "day2" && repo?.day2Info?.movedToGroupId ? repo.day2Info.movedToGroupId : p.groupId;
      return ids.includes(moved) && !(p.day2Only && st === "Started");
    };
    const all = projects.filter((p) => inGroup(p, state)).map((p) => p.id).sort((a, b) => a - b);
    return [g.id, { live: projects.filter((p) => inGroup(p, state) && !p.archived).map((p) => p.id).sort((a, b) => a - b), all }];
  }));
  const day2 = {
    scenario: SCENARIO, stateName: "day2", now: isoZ(DAY2_MS),
    forcePush: {
      projectId: 1, divergenceSha: r1.day2Info.divergenceSha, oldTip: r1.views.started.tip, newTip: r1.views.day2.tip, removedShas: r1.day2Info.removedShas, droppedOutrightSha: r1.day2Info.droppedOutright,
      squashedIntoOne: r1.day2Info.squashedFrom, rewrittenShas: r1.day2Info.rewrittenShas, newShas: r1.day2Info.newShas, mergeBaseOldTipAndNewTip: mergeBase(r1.commits, r1.views.started.tip, r1.views.day2.tip).sha,
      oldTipStillFetchable: true, removedCommitsStillServeDiffs: true,
    },
    renamedDefaultBranch: { projectId: 4, from: "master", to: "main", tipUnchangedByRename: true, newCommits: repos.get(4).day2Info.newShas.length, oldBranchEndpointStatus: 404 },
    deletedMergeRequest: deleted ? { projectId: deleted.projectId, iid: deleted.iid, id: deleted.id, endpointStatus: 404, goneFrom: ["group merge request list", "notes", "approvals", "reviewers", "graphql nodes"] } : null,
    movedProject: { projectId: 2, newFullPath: repos.get(2).day2Info.newFullPath, newGroupId: 12, goneFromGroupProjects: [7, 8], mergeRequestsStillServedByProjectId: true },
    newProject: { id: 7, fullPath: byId.get(7).fullPath, groupId: 8, defaultBranch: "main", commits: repos.get(7).commits.size, shas: repos.get(7).day2Info.shas, mergeRequests: day2Mrs.filter((m) => m.day2Only).length },
    commitDeltaByProject: Object.fromEntries([1, 3, 4, 7].map((id) => {
      const repo = repos.get(id);
      const before = new Set(repo.views.started ? logWalk(repo.commits, repo.views.started.tip).map((c) => c.sha) : []);
      const after = logWalk(repo.commits, repo.views.day2.tip).map((c) => c.sha);
      const afterSet = new Set(after);
      return [id, { newInListing: after.filter((s) => !before.has(s)).length, goneFromListing: [...before].filter((s) => !afterSet.has(s)).length }];
    })),
    commitListingByProject: day2Listing,
    groupProjects: { started: groupLists("Started"), day2: groupLists("day2") },
    mergeRequestUpdates: day2Mrs.filter((m) => !m.day2?.deleted).map((m) => ({
      projectId: m.projectId, iid: m.iid, action: m.day2Only ? "created+merged" : m.day2Action, newState: effectiveMr(m, "day2").state, newDraft: effectiveMr(m, "day2").draft,
      updatedAt: isoZ(effectiveMr(m, "day2").updatedMs), newNotes: m.day2Only ? m.notes.length : m.day2.newNoteCount, mergeCommitSha: effectiveMr(m, "day2").mergeCommitSha ?? null,
    })),
    mergeRequestsByState: sortedObj(countBy(mrList("day2"), (m) => m.state)), mergeRequestsTotal: mrList("day2").length, events: eventsOf(mrList("day2")),
  };

  // ---- assemble ---------------------------------------------------------------------------------------------------------
  return {
    generator: {
      note: "Generated by sample-data/gitlab/generate.mjs - do not edit by hand.", referenceTime: REFERENCE_ISO, day2Time: isoZ(DAY2_MS), host: HOST, params: world.params, mappingCount,
      gitlabVersion: GITLAB_VERSION.version, commitWindow: { from: isoZ(world.startMs), to: isoZ(world.endMs) },
    },
    token: { userId: 1, username: users[0].username, isAdmin: true, scopes: ["read_api"], active: true, expiresAt: "2027-08-01" },
    groups: world.groups.map((g) => ({ id: g.id, fullPath: g.fullPath, parentId: g.parentId, configured: world.configuredGroupIds.includes(g.id) })),
    configuredGroupIds: world.configuredGroupIds,
    projects: {
      inScopeIds: inScope, archivedIds: scopeIds("archived"), outOfScopeIds: scopeIds("out"), day2NewIds: projects.filter((p) => p.day2Only).map((p) => p.id),
      list: projects.map((p) => ({
        id: p.id, fullPath: p.fullPath, groupId: p.groupId, scope: p.scope, archived: p.archived, defaultBranch: p.branch, mergeMethod: p.merge, day2Only: p.day2Only,
      })),
      startedGroupProjects: groupLists("Started"),
    },
    users: {
      list: users.map((u) => ({
        id: u.id, username: u.username, kind: u.kind, bot: u.bot, isAdmin: u.isAdmin, state: u.state, email: u.email, commitEmail: u.commitEmail, secondaryEmails: u.secondary.map((s) => s.email), jiraUserNumber: u.jiraNo,
      })),
      botIds: users.filter((u) => u.bot).map((u) => u.id), adminIds: users.filter((u) => u.isAdmin).map((u) => u.id), withSecondaryEmailIds: users.filter((u) => u.secondary.length).map((u) => u.id),
      unmatchedAuthors: [{ name: world.contractor.name, email: world.contractor.email, identityKey: identityKeyFor(users, world.contractor.email).key }],
      noreplyCommitEmailPattern: `<id>-<username>@users.noreply.${HOST}`,
      jiraAlignment: { note: "Jira stub people (user<n>@flow-sample.example) that are on a team in jira/expected.json teams.roster", matches: jiraAlignment(users) },
    },
    identities,
    commits: {
      note: "Per project, the Started default-branch history: total includes merge commits; merge commits (2 parents) are left out of counts.",
      perProject: commitsOut,
      inScopeTotals: {
        total: sum(inScope, (id) => commitsOut[id].total), nonMerge: sum(inScope, (id) => commitsOut[id].nonMerge), merges: sum(inScope, (id) => commitsOut[id].merges),
      },
      committedBeforeParent: outOfOrder, authoredMoreThanADayBeforeCommitted: rebased,
    },
    commitFiles,
    pathExclusions: { globs: DEFAULT_EXCLUSIONS, fileCapPerCommit: FILE_CAP, perProject: excl, inScopeTotals: { raw: sumExcl("raw"), counted: sumExcl("counted"), countedUncapped: sumExcl("countedUncapped") } },
    special,
    mergeRequests,
    events: eventsOf(started),
    storedHeads,
    day2,
    totals: { commitsEverything: allCommits.length },
  };
}
