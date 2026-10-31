// The `gitlab-day2` scenario applied to the built world: a force-push, a renamed default branch, a deleted merge
// request, a project moved out of the in-scope groups, a new project, and a handful of new commits / MR updates.
import { HOUR_MS, MIN_MS, REFERENCE_MS } from "./constants.mjs";
import { aggregateFiles, makeCommitFiles, makeTree } from "./tree.mjs";
import { makeCommit, branchName } from "./repo.mjs";
import { buildDay2Patch, buildMrActivity } from "./mrs.mjs";

const at = (rng, h) => REFERENCE_MS + h * HOUR_MS + rng.int(0, 50) * MIN_MS;

function directCommit(rng, repo, project, parent, author, ms, extra = {}) {
  const files = makeCommitFiles(rng, repo.tplName, repo.tree, "normal");
  const c = makeCommit(rng, { projectId: project.id }, { parents: [parent.sha], author, authoredMs: ms, committedMs: ms, files, kind: "direct", ...extra });
  repo.commits.set(c.sha, c);
  return c;
}

/** Rewrite the last five direct commits: squash two, drop one, rebase two; then push two brand-new commits. */
function forcePush(rng, repo, project, authors) {
  const { main } = repo;
  const d = main.length - 6;
  const old = main.slice(d + 1);
  if (old.length !== 5 || !old.every((c) => c.parents.length === 1 && c.kind === "direct" && !c.special)) throw new Error(`force-push tail of project ${project.id} is not five plain direct commits`);
  const base = main[d];
  const rebase = (c, parent, ms, files = c.files, title = c.title) => {
    const n = makeCommit(rng, { projectId: project.id }, {
      parents: [parent.sha], author: { kind: "x", id: c.authorUserId, name: c.authorName, tz: c.authorTz, commitEmails: [[c.authorEmail, 1]] }, title, body: "", authoredMs: c.authoredMs, committedMs: ms, files, kind: "direct",
    });
    n.authorName = c.authorName;
    n.authorEmail = c.authorEmail;
    repo.commits.set(n.sha, n);
    return n;
  };
  const n1 = rebase(old[0], base, at(rng, 2), aggregateFiles([old[0].files, old[1].files]));
  const n2 = rebase(old[2], n1, n1.committedMs + 4 * MIN_MS);
  const n3 = rebase(old[4], n2, n2.committedMs + 3 * MIN_MS);
  const a1 = directCommit(rng, repo, project, n3, authors[0], at(rng, 6));
  const a2 = directCommit(rng, repo, project, a1, authors[1] ?? authors[0], at(rng, 9));
  repo.day2Main = [...main.slice(0, d + 1), n1, n2, n3, a1, a2];
  repo.day2Info = {
    forcePush: true, divergenceSha: base.sha, removedShas: old.map((c) => c.sha), droppedShas: [old[1].sha, old[3].sha].filter((s) => s),
    squashedFrom: [old[0].sha, old[1].sha], droppedOutright: old[3].sha, rewrittenShas: [n1.sha, n2.sha, n3.sha], newShas: [a1.sha, a2.sha],
  };
}

export function applyDay2(rng, world) {
  const { projects, repos, mrs } = world;
  const byId = new Map(projects.map((p) => [p.id, p]));
  for (const [id, repo] of repos) {
    repo.views = { started: { tip: repo.main.at(-1).sha, branch: byId.get(id).branch }, day2: null };
  }
  const authorsOf = (id) => world.contributors.get(id).map((c) => c.user);

  // 1) force-push on project 1
  {
    const repo = repos.get(1);
    forcePush(rng, repo, byId.get(1), authorsOf(1));
    repo.views.day2 = { tip: repo.day2Main.at(-1).sha, branch: "main" };
  }
  // 2) project 3: new commits and one open MR merged
  {
    const repo = repos.get(3);
    const project = byId.get(3);
    const authors = authorsOf(3);
    const mr = mrs.find((m) => m.projectId === 3 && m.day2Action === "merge");
    const main2 = [...repo.main];
    const push = (c) => main2.push(c);
    push(directCommit(rng, repo, project, main2.at(-1), authors[0], at(rng, 3)));
    push(directCommit(rng, repo, project, main2.at(-1), authors[1] ?? authors[0], at(rng, 5)));
    if (mr) {
      const branch = mr.branchCommits;
      const mainAsOf = (ms) => [...repo.main].reverse().find((c) => c.committedMs <= ms) ?? repo.main[0];
      branch[0].parents = [mainAsOf(branch[0].authoredMs).sha];
      branch.forEach((c) => {
        c.offMain = false;
        repo.commits.set(c.sha, c);
      });
      const mergedMs = at(rng, 8);
      const mc = makeCommit(rng, { projectId: 3 }, {
        parents: [main2.at(-1).sha, branch.at(-1).sha], author: mr.merger, title: `Merge branch '${mr.sourceBranch}' into 'main'`,
        body: `${mr.title.replace(/^Draft: /, "")}\n\nSee merge request ${mr.fullPath}!${mr.iid}`, authoredMs: mergedMs, committedMs: mergedMs,
        files: aggregateFiles(branch.map((c) => c.files)), kind: "merge", mrIid: mr.iid,
      });
      repo.commits.set(mc.sha, mc);
      push(mc);
      mr.day2Merge = { mergedMs, mergeCommitSha: mc.sha };
    }
    push(directCommit(rng, repo, project, main2.at(-1), authors[2] ?? authors[0], at(rng, 11)));
    push(directCommit(rng, repo, project, main2.at(-1), authors[0], at(rng, 14)));
    repo.day2Main = main2;
    repo.views.day2 = { tip: main2.at(-1).sha, branch: "main" };
    repo.day2Info = { newShas: main2.slice(repo.main.length).map((c) => c.sha), mergedMrIid: mr?.iid ?? null };
  }
  // 3) project 4: the default branch is renamed master -> main, two new commits
  {
    const repo = repos.get(4);
    const project = byId.get(4);
    const authors = authorsOf(4);
    const main2 = [...repo.main];
    main2.push(directCommit(rng, repo, project, main2.at(-1), authors[0], at(rng, 4)));
    main2.push(directCommit(rng, repo, project, main2.at(-1), authors[1] ?? authors[0], at(rng, 10)));
    repo.day2Main = main2;
    repo.views.day2 = { tip: main2.at(-1).sha, branch: "main" };
    repo.day2Info = { renamedBranch: { from: "master", to: "main" }, newShas: main2.slice(repo.main.length).map((c) => c.sha) };
  }
  // 4) project 2: moved to the (out-of-scope) sandbox group; history unchanged
  {
    const repo = repos.get(2);
    repo.day2Main = repo.main;
    repo.views.day2 = { tip: repo.views.started.tip, branch: "main" };
    repo.day2Info = { movedToGroupId: 12, newFullPath: "sandbox/build-kit" };
  }
  // 5) project 7: a new project with a few commits and one merged MR
  {
    const project = byId.get(7);
    const [a, b] = authorsOf(7);
    const tree = makeTree(rng, "ts");
    const repo = { projectId: 7, commits: new Map(), main: [], tree, specials: {}, tplName: "ts", views: { started: null, day2: null } };
    const ctx = { projectId: 7 };
    const put = (c) => {
      repo.commits.set(c.sha, c);
      return c;
    };
    const mk = (parents, author, ms, extra = {}) => put(makeCommit(rng, ctx, { parents, author, authoredMs: ms, committedMs: ms, files: makeCommitFiles(rng, "ts", tree, "normal"), kind: "direct", ...extra }));
    const root = mk([], a, at(rng, 1), { title: "chore: import project skeleton" });
    const d1 = mk([root.sha], a, at(rng, 2));
    const feature = mk([d1.sha], b, at(rng, 4), { kind: "feature", mrIid: 1 });
    const d2 = mk([d1.sha], a, at(rng, 5));
    const mergedMs = at(rng, 7);
    const mr = {
      projectId: 7, iid: 1, state: "merged", author: b, reviewers: [a], assignees: [b], merger: a, title: "feat(lint): add the no-shadow rule", draft: false, wasDraft: false, squash: false,
      branchCount: 1, sourceBranch: branchName(rng, "feat(lint): add the no-shadow rule"), targetBranch: "main", labels: ["feature"], fullPath: project.fullPath, createdMs: at(rng, 3), mergedMs,
      closedMs: null, closer: null, day2Action: null, day2Only: true, headSha: feature.sha, isBot: false, branchCommits: [feature], sha: feature.sha, squashCommitSha: null,
    };
    const mc = mk([d2.sha, feature.sha], a, mergedMs, { kind: "merge", mrIid: 1, title: "Merge branch 'feat/lint-no-shadow' into 'main'", files: feature.files });
    mr.mergeCommitSha = mc.sha;
    buildMrActivity(rng, mr);
    repo.main = [root, d1, d2, mc];
    repo.views.day2 = { tip: mc.sha, branch: "main" };
    repo.day2Info = { newProject: true, shas: [root.sha, d1.sha, feature.sha, d2.sha, mc.sha] };
    repos.set(7, repo);
    mrs.push(mr);
  }
  // 6) merge-request patches
  for (const mr of mrs) {
    if (mr.day2Only) continue;
    if (mr.day2Action) mr.day2 = buildDay2Patch(rng, mr, mr.day2Merge ?? {});
  }
}
