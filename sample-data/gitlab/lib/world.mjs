// Assembles the whole synthetic GitLab world (users, groups, projects, histories, merge requests, day2) from three
// scale parameters. The same parameters always produce the same world.
import { CONFIGURED_GROUP_IDS, DAY_MS, DAY2_NEW_PROJECT, FIXED_PROJECTS, GROUPS, HOUR_MS, REFERENCE_MS } from "./constants.mjs";
import { rngFor } from "./rng.mjs";
import { buildUsers, CONTRACTOR } from "./users.mjs";
import { buildRepo, resetSeq } from "./repo.mjs";
import { buildMrActivity, planMrs } from "./mrs.mjs";
import { applyDay2 } from "./day2.mjs";

export const DEFAULT_PARAMS = { users: 6, projects: 4, months: 6 };

/** Indices into the human list that work on each fixed project (the first four humans are everywhere). */
const FIXED_CONTRIBUTORS = { 1: [0, 1, 2, 3], 2: [1, 4, 5], 3: [0, 2, 3, 4], 4: [0, 4, 5], 5: [1, 3], 6: [2], 7: [0, 1] };
const SPECIALS = { 1: ["collapsed"], 2: ["bigMigration"], 3: ["tooLarge"], 4: ["hostileLines"] };
const EXTRA_TEMPLATES = ["go", "ts", "tsx", "py"];
const EXTRA_MERGE = ["merge", "squash", "ff", "merge"];

export function validateParams(p) {
  for (const [k, min] of [["users", 6], ["projects", 4], ["months", 2]]) {
    if (!Number.isInteger(p[k]) || p[k] < min) throw new Error(`--${k} must be an integer >= ${min}, got: ${p[k]}`);
  }
}

export function buildWorld(params = DEFAULT_PARAMS) {
  validateParams(params);
  resetSeq();
  const { users: U, projects: P, months: M } = params;
  const endMs = REFERENCE_MS - HOUR_MS;
  const startMs = REFERENCE_MS - M * 30 * DAY_MS;
  const users = buildUsers(U);
  const humans = users.filter((u) => u.kind === "human");
  const bot = users.find((u) => u.kind === "bot");

  // --- projects ---------------------------------------------------------------------------------------------------
  const groupById = new Map(GROUPS.map((g) => [g.id, g]));
  const inScopeFixed = FIXED_PROJECTS.filter((p) => p.scope === "in");
  const extraCount = P - inScopeFixed.length;
  const extras = Array.from({ length: extraCount }, (_, i) => ({
    id: 101 + i, name: `Service ${i + 1}`, path: `service-${i + 1}`, groupId: [7, 8, 9][i % 3], branch: "main", tpl: EXTRA_TEMPLATES[i % 4], merge: EXTRA_MERGE[i % 4], share: 0.2, archived: false, scope: "in",
  }));
  const projects = [...inScopeFixed, ...extras, ...FIXED_PROJECTS.filter((p) => p.scope !== "in"), DAY2_NEW_PROJECT].map((p) => {
    const group = groupById.get(p.groupId);
    return { ...p, fullPath: `${group.fullPath}/${p.path}`, day2Only: p.id === DAY2_NEW_PROJECT.id, createdMs: startMs - 20 * DAY_MS };
  });
  const inScope = projects.filter((p) => p.scope === "in" && !p.day2Only);
  const shareSum = inScope.reduce((s, p) => s + p.share, 0);
  const totalCommits = Math.round((U * M * 50) / 6);
  const totalMrs = Math.round((U * M * 5) / 3);

  // --- contributors -----------------------------------------------------------------------------------------------
  const contributors = new Map();
  const weights = (users2) => users2.map((user, i) => ({ user, weight: Math.max(1, 4 - i) }));
  for (const p of projects) {
    let idx = FIXED_CONTRIBUTORS[p.id];
    if (!idx) {
      const j = p.id - 101;
      idx = [(j * 3) % humans.length, (j * 3 + 1) % humans.length, (j * 3 + 2) % humans.length];
    }
    contributors.set(p.id, weights(idx.map((i) => humans[i % humans.length])));
  }
  // Humans beyond the six default ones are spread over the in-scope projects.
  humans.slice(6).forEach((h, i) => {
    const target = inScope[i % inScope.length];
    contributors.get(target.id).push({ user: h, weight: 2 });
  });

  // --- merge requests ---------------------------------------------------------------------------------------------
  const mrs = [];
  for (const p of inScope) {
    const rng = rngFor(`mrs:${p.id}`);
    const count = Math.max(4, Math.round((totalMrs * p.share) / shareSum));
    const plans = planMrs(rng, { project: p, count, contributors: contributors.get(p.id), bot: p.id === 3 ? bot : null, startMs, endMs, fullPath: p.fullPath });
    mrs.push(...plans);
  }
  const p1Merged = mrs.find((m) => m.projectId === 1 && m.state === "merged" && !m.isBot);
  if (p1Merged) p1Merged.busy = 125; // a long discussion: its notes span two pages
  [...mrs].sort((a, b) => a.createdMs - b.createdMs || a.projectId - b.projectId).forEach((m, i) => {
    m.id = 41000 + i;
  });

  // --- histories --------------------------------------------------------------------------------------------------
  const repos = new Map();
  for (const p of projects) {
    if (p.day2Only) continue;
    const rng = rngFor(`repo:${p.id}`);
    const mine = mrs.filter((m) => m.projectId === p.id);
    const total = p.scope === "in" ? Math.max(30, Math.round((totalCommits * p.share) / shareSum)) : Math.max(14, Math.round(((p.scope === "archived" ? 14 : 16) * M) / 6));
    const repo = buildRepo(rng, {
      project: p, tplName: p.tpl, contributors: contributors.get(p.id), merger: (m) => m.merger, startMs, endMs, total, mrs: mine, fullPath: p.fullPath,
      flags: { bot: p.id === 3 ? bot : null, contractor: p.id === 1 ? CONTRACTOR : null, specials: SPECIALS[p.id] ?? [] },
    });
    repos.set(p.id, repo);
  }
  for (const mr of mrs) buildMrActivity(rngFor(`notes:${mr.projectId}:${mr.iid}`), mr);

  const world = { params, users, humans, bot, groups: GROUPS, configuredGroupIds: CONFIGURED_GROUP_IDS, projects, repos, mrs, contributors, startMs, endMs, contractor: CONTRACTOR };
  applyDay2(rngFor("day2"), world);

  // --- note ids: started notes first (by time), day2 notes after ----------------------------------------------------
  let nextId = 710_000;
  const stamp = (list) => {
    for (const n of list) if (n.id === undefined) n.id = nextId++;
  };
  const startedNotes = mrs.filter((m) => !m.day2Only).flatMap((m) => m.notes.map((n, i) => ({ n, m, i })));
  startedNotes.sort((a, b) => a.n.at - b.n.at || a.m.id - b.m.id || a.i - b.i);
  stamp(startedNotes.map((x) => x.n));
  const dayNotes = mrs.flatMap((m) => (m.day2 && !m.day2.deleted ? m.day2.notes : m.day2Only ? m.notes : [])).filter((n) => n.id === undefined);
  dayNotes.sort((a, b) => a.at - b.at);
  stamp(dayNotes);
  world.heads = computeHeads(world);
  return world;
}

/**
 * A realistic "stored head" per project: a mainline commit about 80% into the history (an earlier sync stopped there),
 * and the tip of the Started history (the head after a complete sync). For the force-pushed project the stored head sits
 * before the divergence point, so it stays an ancestor in day2.
 */
function computeHeads(world) {
  const heads = {};
  for (const p of world.projects) {
    const repo = world.repos.get(p.id);
    if (!repo?.views.started) continue;
    let idx = Math.floor(0.8 * (repo.main.length - 1));
    const div = repo.day2Info?.divergenceSha;
    if (div) idx = Math.min(idx, repo.main.findIndex((c) => c.sha === div) - 2);
    heads[p.id] = { storedSha: repo.main[idx].sha, storedIndex: idx, startedTipSha: repo.views.started.tip };
  }
  return heads;
}
