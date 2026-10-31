// Builds one project's default-branch history as a real commit DAG: direct commits, merge commits with a second
// parent, squash commits, fast-forwarded (rebased) branches, rebases (authored date older than committed date),
// clock-skewed commits, a handful of hand-placed special commits, and the `gitlab-day2` changes.
import { DAY_MS, HOUR_MS, MIN_MS } from "./constants.mjs";
import { aggregateFiles, makeCommitFiles, makeFile, makeTree, modifyEntry, statsOf, TEMPLATES } from "./tree.mjs";
import { sha1 } from "./rng.mjs";
import { commitBody, commitTitle } from "./text.mjs";
import { binaryDiff, modifyDiff, newFileDiff, sourceLine } from "./diff.mjs";

let seqCounter = 0;
export const resetSeq = () => {
  seqCounter = 0;
};

/** Pick a working-hours timestamp in [lo, hi], weekends mostly pushed to the following Monday. */
export function workTime(rng, lo, hi) {
  let t = lo + Math.floor(rng.float() * (hi - lo));
  const day = Math.floor(t / DAY_MS);
  const dow = (day + 4) % 7; // 1970-01-01 was a Thursday; 0 = Sunday
  if ((dow === 0 || dow === 6) && rng.bool(0.85)) t += (dow === 6 ? 2 : 1) * DAY_MS;
  const base = Math.floor(t / DAY_MS) * DAY_MS;
  t = base + rng.int(7, 17) * HOUR_MS + rng.int(0, 59) * MIN_MS + rng.int(0, 59) * 1000;
  return Math.min(Math.max(t, lo), hi);
}

/** Weighted pick over [{user, weight}] contributors. */
const pickContributor = (rng, contributors) => rng.pickWeighted(contributors.map((c) => [c.user, c.weight]));

function emailFor(rng, who) {
  return rng.pickWeighted(who.commitEmails.map(([e, w]) => [e, w]));
}

/**
 * Creates a commit object. `author`/`committer` are user records (or the contractor); `parents` are shas.
 * Not inserted anywhere: the caller decides whether it lands in the repository map.
 */
export function makeCommit(rng, ctx, o) {
  const seq = ++seqCounter;
  const author = o.author;
  const committer = o.committer ?? author;
  const title = o.title ?? commitTitle(rng);
  const body = o.body ?? commitBody(rng);
  const message = `${title}\n${body ? `\n${body}\n` : ""}${o.trailer ? `\n${o.trailer}\n` : ""}`;
  const files = o.files;
  const commit = {
    sha: o.sha ?? sha1(`${ctx.projectId}:${seq}:${title}:${o.authoredMs}`),
    seq, projectId: ctx.projectId, parents: o.parents, title, message,
    authorName: author.kind === "human" && rng.bool(0.1) ? author.username : author.name, authorEmail: o.authorEmail ?? emailFor(rng, author), authorTz: author.tz, authorUserId: author.id,
    committerName: committer.kind === "human" && committer !== author && rng.bool(0.1) ? committer.username : committer.name,
    committerEmail: o.committerEmail ?? (committer === author ? undefined : emailFor(rng, committer)), committerTz: committer.tz, committerUserId: committer.id,
    authoredMs: o.authoredMs, committedMs: o.committedMs, files, stats: statsOf(files),
    isMerge: o.parents.length > 1, kind: o.kind, mrIid: o.mrIid ?? null, special: o.special ?? null, offMain: !!o.offMain,
  };
  commit.committerEmail ??= commit.authorEmail;
  return commit;
}

function bigMigrationFiles(rng) {
  const files = [];
  for (let i = 0; i < 700; i++) {
    const n = String(i).padStart(4, "0");
    const oldPath = `src/legacy/mod${n}/index.ts`;
    const newPath = `src/modules/mod${n}/index.ts`;
    if (rng.bool(0.3)) {
      const d = modifyDiff(rng, { lines: (r) => sourceLine(r, "ts"), hunks: 1, runMax: 2, oldLen: 40 });
      files.push(makeFile({ oldPath, newPath, renamedFile: true, ...d }));
    } else files.push(makeFile({ oldPath, newPath, renamedFile: true, diff: "", additions: 0, deletions: 0 }));
  }
  for (let i = 0; i < 340; i++) {
    const path = `generated/schema/type${String(i).padStart(4, "0")}.ts`;
    files.push(makeFile({ oldPath: path, newPath: path, newFile: true, ...newFileDiff(rng, { lines: (r) => sourceLine(r, "ts"), n: rng.int(3, 12) }) }));
  }
  return files.sort((a, b) => (a.newPath < b.newPath ? -1 : a.newPath > b.newPath ? 1 : 0));
}

const HOSTILE_ADDED = ["```diff", "--- a/server.go", "+++ b/server.go", "@@ -1,2 +1,2 @@", "-old line", "+new line", "```"];
const HOSTILE_REMOVED = ["-- legacy contributor workflow", "---", "-- run the formatter by hand"];

function specialFiles(rng, tplName, tree, name) {
  const files = [];
  if (name === "bigMigration") return bigMigrationFiles(rng);
  if (name === "tooLarge") {
    const catalog = makeFile({ oldPath: "src/data/catalog.json", newPath: "src/data/catalog.json", diff: "", additions: 5200, deletions: 140, tooLarge: true });
    const lock = tree.find((f) => f.cat === "lock");
    files.push(catalog);
    if (lock) files.push(makeFile({ oldPath: lock.path, newPath: lock.path, ...modifyEntry(rng, lock) }));
    const note = makeFile({ oldPath: "src/data/README.md", newPath: "src/data/README.md", ...modifyDiff(rng, { lines: (r) => sourceLine(r, "md"), hunks: 1, lang: null }) });
    files.push(note);
  } else if (name === "collapsed") {
    files.push(makeFile({ oldPath: "docs/api/reference.md", newPath: "docs/api/reference.md", diff: "", additions: 1800, deletions: 60, collapsed: true }));
    files.push(makeFile({ oldPath: "docs/api/overview.md", newPath: "docs/api/overview.md", ...modifyDiff(rng, { lines: (r) => sourceLine(r, "md"), hunks: 2 }) }));
  } else if (name === "hostileLines") {
    files.push(makeFile({ oldPath: "docs/contributing.md", newPath: "docs/contributing.md", ...modifyDiff(rng, { lines: (r) => sourceLine(r, "md"), hunks: 1, hostile: { removed: HOSTILE_REMOVED, added: HOSTILE_ADDED } }) }));
    files.push(makeFile({ oldPath: "assets/logo.png", newPath: "assets/logo.png", binary: true, ...binaryDiff("assets/logo.png", "assets/logo.png", false, false) }));
    files.push(makeFile({ oldPath: "assets/banner.png", newPath: "assets/banner.png", newFile: true, binary: true, ...binaryDiff("assets/banner.png", "assets/banner.png", true, false) }));
  }
  return files.sort((a, b) => (a.newPath < b.newPath ? -1 : a.newPath > b.newPath ? 1 : 0));
}
const SPECIAL_TITLES = {
  bigMigration: "chore: move legacy modules under src/modules and regenerate schema types",
  tooLarge: "chore: refresh product catalog fixtures",
  collapsed: "docs: regenerate the API reference",
  hostileLines: "docs: rewrite the contributing guide and add logo assets",
};

function branchName(rng, title) {
  const slug = title.toLowerCase().replace(/^[^:]*:\s*/, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 36);
  return `${rng.pick(["feature", "fix", "chore"])}/${slug}`;
}
export { branchName };

/**
 * Builds the project's history. `mrs` are the planned merge requests of this project (state/times/authors/branchCount
 * already decided); merged ones land here, the rest only get an off-main branch whose head sha the MR keeps.
 */
export function buildRepo(rng, o) {
  const { project, tplName, contributors, merger: mergerOf, startMs, endMs, total, mrs, fullPath, flags } = o;
  const ctx = { projectId: project.id };
  const commits = new Map();
  const main = [];
  const tree = makeTree(rng, tplName);
  const specials = {};
  const insert = (c) => {
    commits.set(c.sha, c);
    return c;
  };
  const tip = () => main.at(-1);
  const mainAsOf = (ms) => {
    for (let i = main.length - 1; i >= 0; i--) if (main[i].committedMs <= ms) return main[i];
    return main[0];
  };
  const humanOrFirst = () => contributors[0].user;

  // --- root: a tiny import commit at the start of the window -------------------------------------------------
  const tpl = TEMPLATES[tplName];
  const rootAuthor = humanOrFirst();
  const rootFiles = [tpl.manifest, "README.md", ".gitignore"].map((p) =>
    makeFile({ oldPath: p, newPath: p, newFile: true, ...newFileDiff(rng, { lines: (r) => sourceLine(r, p.endsWith(".md") ? "md" : "yaml"), n: rng.int(6, 30) }) }),
  ).sort((a, b) => (a.newPath < b.newPath ? -1 : 1));
  const root = insert(makeCommit(rng, ctx, {
    parents: [], author: rootAuthor, title: "chore: import project skeleton", body: "", authoredMs: startMs, committedMs: startMs, files: rootFiles, kind: "direct",
  }));
  main.push(root);

  // --- plan the events -----------------------------------------------------------------------------------------
  const merged = mrs.filter((m) => m.state === "merged").sort((a, b) => a.mergedMs - b.mergedMs);
  const mrCommits = merged.reduce((s, m) => s + (project.merge === "merge" ? (m.squash ? 2 : m.branchCount + 1) : project.merge === "squash" ? 1 : m.branchCount), 0);
  const directs = Math.max(total - mrCommits - 1, 12);
  const directTimes = [];
  for (let i = 0; i < directs - 5; i++) directTimes.push(workTime(rng, startMs + HOUR_MS, endMs - 7 * DAY_MS));
  for (let i = 0; i < 5; i++) directTimes.push(workTime(rng, endMs - 6 * DAY_MS, endMs - 6 * HOUR_MS));
  const events = [
    ...directTimes.map((t) => ({ type: "direct", t })),
    ...merged.map((mr) => ({ type: "mr", t: mr.mergedMs, mr })),
  ].sort((a, b) => a.t - b.t);
  const directOrdinals = events.map((e, i) => (e.type === "direct" ? i : -1)).filter((i) => i >= 0);
  const specialAt = new Map();
  for (const name of flags.specials ?? []) {
    const frac = { bigMigration: 0.45, tooLarge: 0.35, collapsed: 0.5, hostileLines: 0.4 }[name];
    specialAt.set(directOrdinals[Math.floor(frac * (directOrdinals.length - 6))], name);
  }
  // Clock-skewed commits: committed a few minutes BEFORE their parent.
  const skewAt = new Set([0.2, 0.55, 0.8].map((f) => directOrdinals[Math.floor(f * directOrdinals.length)]));

  const mkFiles = (kind) => makeCommitFiles(rng, tplName, tree, kind);
  const filesKind = () => rng.pickWeighted([["normal", 86], ["deps", 6], ["regen", 5], ["wide", 3]]);

  // --- walk -----------------------------------------------------------------------------------------------------
  events.forEach((ev, idx) => {
    if (ev.type === "direct") {
      const special = specialAt.get(idx);
      let author = pickContributor(rng, contributors);
      let kind = filesKind();
      if (!special && flags.bot && rng.bool(0.07)) {
        author = flags.bot;
        kind = "deps";
      } else if (!special && flags.contractor && rng.bool(0.04)) author = flags.contractor;
      const parent = tip();
      let committedMs = ev.t;
      let authoredMs = ev.t;
      if (skewAt.has(idx)) {
        committedMs = parent.committedMs - rng.int(2, 30) * MIN_MS;
        authoredMs = committedMs - rng.int(0, 10) * MIN_MS;
      } else if (rng.bool(0.12)) authoredMs = ev.t - rng.int(1, 9) * DAY_MS; // rebased: authored long before committed
      const files = special ? specialFiles(rng, tplName, tree, special) : mkFiles(kind);
      const c = insert(makeCommit(rng, ctx, {
        parents: [parent.sha], author, title: special ? SPECIAL_TITLES[special] : author.kind === "bot" ? `chore(deps): bump ${rng.pick(["lodash-es", "zod", "undici", "vite", "react-query"])} to ${rng.int(1, 6)}.${rng.int(0, 20)}.${rng.int(0, 9)}` : undefined,
        authoredMs: Math.max(authoredMs, startMs), committedMs, files, kind: "direct", special,
        committer: rng.bool(0.05) ? pickContributor(rng, contributors) : author,
      }));
      if (special) specials[special] = c.sha;
      main.push(c);
      return;
    }
    // ---- a merged merge request --------------------------------------------------------------------------------
    const mr = ev.mr;
    const merger = mergerOf(mr);
    const author = mr.author;
    const tm = mr.mergedMs;
    const k = mr.branchCount;
    const tFirst = Math.max(startMs + HOUR_MS, mr.createdMs - rng.int(30, 600) * MIN_MS);
    const stepSpan = Math.max(HOUR_MS, tm - tFirst - 20 * MIN_MS);
    const mrKind = project.merge;
    const rebased = mrKind === "ff" || rng.bool(0.3);
    const branch = [];
    let prev = null;
    for (let i = 0; i < k; i++) {
      const authoredMs = Math.round(tFirst + (stepSpan * i) / Math.max(k, 1) + rng.int(0, 20) * MIN_MS);
      const committedMs = rebased ? tm - (k - i) * rng.int(2, 8) * MIN_MS : authoredMs + rng.int(0, 5) * MIN_MS;
      const files = mkFiles(author.kind === "bot" ? "deps" : filesKind());
      const parents = prev ? [prev.sha] : [(rebased || mrKind === "ff" ? tip() : mainAsOf(tFirst)).sha];
      const c = makeCommit(rng, ctx, {
        parents, author, title: author.kind === "bot" ? mr.title : undefined, authoredMs: Math.min(authoredMs, committedMs), committedMs, files, kind: "feature",
        mrIid: mr.iid, offMain: true, committer: mrKind === "ff" ? merger : author,
      });
      branch.push(c);
      prev = c;
    }
    mr.branchCommits = branch;
    const net = aggregateFiles(branch.map((c) => c.files));
    const trailer = `See merge request ${fullPath}!${mr.iid}`;
    const cleanTitle = mr.title.replace(/^Draft: /, "");
    if (mrKind === "ff") {
      for (const c of branch) {
        c.offMain = false;
        c.kind = "ff";
        insert(c);
        main.push(c);
      }
      mr.sha = branch.at(-1).sha;
      mr.mergeCommitSha = null;
      mr.squashCommitSha = null;
    } else if (mrKind === "squash") {
      const s = insert(makeCommit(rng, ctx, {
        parents: [tip().sha], author, committer: merger, title: cleanTitle, body: "", trailer, authoredMs: tm - 2 * MIN_MS, committedMs: tm, files: net, kind: "squash", mrIid: mr.iid,
      }));
      main.push(s);
      mr.sha = branch.at(-1).sha;
      mr.mergeCommitSha = null;
      mr.squashCommitSha = s.sha;
    } else {
      let chain = branch;
      mr.sha = branch.at(-1).sha;
      mr.squashCommitSha = null;
      if (mr.squash) {
        // Squash-and-merge with a merge commit: ONE squash commit hangs off the second parent; the MR keeps its own head sha.
        const sq = makeCommit(rng, ctx, {
          parents: [branch[0].parents[0]], author, committer: merger, title: cleanTitle, body: "", trailer, authoredMs: tm - 3 * MIN_MS, committedMs: tm - MIN_MS, files: net, kind: "squash", mrIid: mr.iid, offMain: true,
        });
        mr.squashCommitSha = sq.sha;
        chain = [sq];
      }
      const head = chain.at(-1);
      for (const c of chain) insert(c);
      const mc = insert(makeCommit(rng, ctx, {
        parents: [tip().sha, head.sha], author: merger, title: `Merge branch '${mr.sourceBranch}' into '${project.branch}'`, body: `${cleanTitle}\n\n${trailer}`, authoredMs: tm, committedMs: tm,
        files: net, kind: "merge", mrIid: mr.iid,
      }));
      main.push(mc);
      mr.mergeCommitSha = mc.sha;
    }
    mr.mergedOnMain = true;
  });

  // Merge requests that never merged keep an off-main branch (their head sha is a commit that is not on main).
  for (const mr of mrs.filter((m) => m.state !== "merged")) makeOffMainBranch(rng, ctx, mr, tplName, tree, mr.author, startMs, filesKindFn(rng));

  return { projectId: project.id, commits, main, tree, specials, tplName };
}

const filesKindFn = (rng) => () => rng.pickWeighted([["normal", 88], ["deps", 6], ["regen", 3], ["wide", 3]]);

export function makeOffMainBranch(rng, ctx, mr, tplName, tree, author, startMs, kindFn) {
  const k = mr.branchCount;
  const branch = [];
  let prev = null;
  const t0 = Math.max(startMs, mr.createdMs - rng.int(30, 400) * MIN_MS);
  for (let i = 0; i < k; i++) {
    const t = t0 + i * rng.int(20, 300) * MIN_MS;
    const files = makeCommitFiles(rng, tplName, tree, author.kind === "bot" ? "deps" : kindFn());
    const c = makeCommit(rng, ctx, {
      parents: prev ? [prev.sha] : [sha1(`base:${ctx.projectId}:${mr.iid}`)], author, title: author.kind === "bot" ? mr.title : undefined, authoredMs: t, committedMs: t, files, kind: "feature",
      mrIid: mr.iid, offMain: true, sha: mr.headSha && i === k - 1 ? mr.headSha : undefined,
    });
    branch.push(c);
    prev = c;
  }
  mr.branchCommits = branch;
  mr.sha = branch.at(-1).sha;
  mr.mergeCommitSha = null;
  mr.squashCommitSha = null;
}
