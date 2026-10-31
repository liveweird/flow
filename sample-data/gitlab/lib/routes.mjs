// The endpoint contract of the stub: every mapping, defined once against the world model.
import { SCENARIO } from "./constants.mjs";
import { PRIORITY } from "./writer.mjs";
import { ancestors, logWalk, mergeBase } from "./graph.mjs";
import {
  approvalsJson, branchJson, commitJson, diffFileJson, groupJson, mrJson, noteJson, projectJson, projectView, reviewersJson, tokenSelfJson, userAdmin, versionJson,
} from "./format.mjs";
import { effectiveMr } from "./mrs.mjs";

const API = "/api/v4";
const enc = (s) => encodeURIComponent(s);
const re = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
/** Case-insensitive pattern for a URL-encoded full path (clients differ in %2F vs %2f). */
const encodedPathPattern = (prefix, fullPath) => `(?i)${re(`${prefix}/${enc(fullPath)}`)}`;

export function defineRoutes(w, world) {
  const groupById = new Map(world.groups.map((g) => [g.id, g]));
  const projectById = new Map(world.projects.map((p) => [p.id, p]));
  const admin = world.users[0];
  const stateKey = (s) => (s === "day2" ? "day2" : "started");

  // ---- auth ------------------------------------------------------------------------------------------------------
  // Anything under /api is refused unless PRIVATE-TOKEN is non-empty OR Authorization is `Bearer <something>`.
  w.raw("auth-401", {
    priority: PRIORITY.unauthorized,
    request: {
      method: "ANY", urlPathPattern: "/api/.*",
      headers: {
        "PRIVATE-TOKEN": { or: [{ absent: true }, { doesNotMatch: ".+" }] },
        Authorization: { or: [{ absent: true }, { doesNotMatch: "^Bearer .+$" }] },
      },
    },
    response: { status: 401, headers: { "Content-Type": "application/json" }, bodyFileName: "auth-401.json" },
  }, { message: "401 Unauthorized" });

  // ---- instance ---------------------------------------------------------------------------------------------------
  w.emit("version", () => ({ type: "single", urlPath: `${API}/version`, body: versionJson() }));
  w.emit("user", () => ({ type: "single", urlPath: `${API}/user`, body: userAdmin(admin) }));
  w.emit("personal-access-token-self", () => ({ type: "single", urlPath: `${API}/personal_access_tokens/self`, body: tokenSelfJson(admin) }));

  // ---- groups ------------------------------------------------------------------------------------------------------
  const descendants = (gid) => {
    const out = [gid];
    for (const g of world.groups) if (g.parentId && out.includes(g.parentId) && !out.includes(g.id)) out.push(g.id);
    return out;
  };
  for (const g of world.groups) {
    w.emit(`group-${g.id}`, () => ({
      type: "single", urlPath: `${API}/groups/${g.id}`, extraPaths: [encodedPathPattern(`${API}/groups`, g.fullPath)], body: groupJson(g, groupById),
    }));
  }

  const viewsFor = (state) => world.projects.map((p) => {
    const repo = world.repos.get(p.id);
    if (p.day2Only && state === "Started") return null;
    return projectView(p, repo, state === "day2" ? "day2" : "Started", groupById);
  });

  for (const g of world.groups) {
    for (const mode of ["live", "all"]) {
      w.emit(`group-${g.id}-projects-${mode}`, (state) => {
        const ids = descendants(g.id);
        const items = viewsFor(state).filter((v, i) => v && ids.includes(v.groupId) && (mode === "all" || !v.archived)).map((v) => projectJson(v, projectById.get(v.id), { groupById }));
        items.sort((a, b) => a.id - b.id);
        const query = { include_subgroups: "true", per_page: "100", ...(mode === "live" ? { archived: "false" } : { archived: { absent: true } }) };
        return { type: "paged", urlPath: `${API}/groups/${g.id}/projects`, query, linkParams: { archived: mode === "live" ? "false" : undefined, id: g.id, include_subgroups: "true" }, items };
      });
    }
  }

  // ---- projects and branches -----------------------------------------------------------------------------------
  for (const p of world.projects) {
    const repo = world.repos.get(p.id);
    w.emit(`project-${p.id}`, (state) => {
      if (p.day2Only && state === "Started") return null;
      const v = projectView(p, repo, state === "day2" ? "day2" : "Started", groupById);
      return { type: "single", urlPath: `${API}/projects/${p.id}`, extraPaths: [encodedPathPattern(`${API}/projects`, v.fullPath)], body: projectJson(v, p, { detail: true, groupById }) };
    });
    w.emit(`project-${p.id}-default-branch`, (state) => {
      const view = repo.views[stateKey(state)];
      if (!view) return null;
      const v = projectView(p, repo, state === "day2" ? "day2" : "Started", groupById);
      return { type: "single", urlPath: `${API}/projects/${p.id}/repository/branches/${enc(view.branch)}`, body: branchJson(repo.commits.get(view.tip), { fullPath: v.fullPath }, view.branch) };
    });
  }

  // ---- users -----------------------------------------------------------------------------------------------------
  w.emit("users", () => ({ type: "paged", urlPath: `${API}/users`, query: { per_page: "100" }, items: world.users.map(userAdmin) }));
  for (const u of world.users) {
    w.emit(`user-${u.id}-emails`, () => ({ type: "single", urlPath: `${API}/users/${u.id}/emails`, body: u.secondary }));
  }

  // ---- commits ---------------------------------------------------------------------------------------------------
  const walkItems = (repo, p, view, exclude) => logWalk(repo.commits, view.tip, exclude).map((c) => commitJson(c, p, { stats: true }));
  for (const p of world.projects) {
    const repo = world.repos.get(p.id);
    const head = world.heads[p.id];
    const listing = (id, refOf, itemsOf) => (state) => {
      const view = repo.views[stateKey(state)];
      if (!view) return null;
      const ref = refOf(view);
      if (ref == null) return null;
      return {
        type: "paged", urlPath: `${API}/projects/${p.id}/repository/commits`, query: { ref_name: ref, with_stats: "true", per_page: "100" },
        linkParams: { id: p.id, ref_name: ref, with_stats: "true" }, items: itemsOf(view),
      };
    };
    w.emit(`p${p.id}-commits`, listing("full", (v) => v.branch, (v) => walkItems(repo, p, v, null)));
    if (head) {
      w.emit(`p${p.id}-commits-from-stored-head`, listing("stored", (v) => `${head.storedSha}..${v.branch}`, (v) => walkItems(repo, p, v, head.storedSha)));
      w.emit(`p${p.id}-commits-from-started-tip`, listing("tip", (v) => `${head.startedTipSha}..${v.branch}`, (v) => walkItems(repo, p, v, head.startedTipSha)));
      // merge_base of a stored head and the branch (by name or by tip sha), per state.
      for (const [label, sha] of [["stored-head", head.storedSha], ["started-tip", head.startedTipSha]]) {
        w.emit(`p${p.id}-merge-base-${label}`, (state) => {
          const view = repo.views[stateKey(state)];
          if (!view) return null;
          const base = mergeBase(repo.commits, sha, view.tip);
          return {
            type: "single", urlPath: `${API}/projects/${p.id}/repository/merge_base`,
            query: { "refs[]": { includes: [{ equalTo: sha }, { equalTo: view.branch }] } }, body: commitJson(base, p),
          };
        });
        w.emit(`p${p.id}-merge-base-${label}-by-tip`, (state) => {
          const view = repo.views[stateKey(state)];
          if (!view) return null;
          const base = mergeBase(repo.commits, sha, view.tip);
          return { type: "single", urlPath: `${API}/projects/${p.id}/repository/merge_base`, query: { "refs[]": { includes: [{ equalTo: sha }, { equalTo: view.tip }] } }, body: commitJson(base, p) };
        });
      }
    }
    // Re-list sweep confirmations (PR 7): the connector asks `merge_base(<stored commit>, <the listing's tip>)` for each commit the re-list listing did not show
    // (by tip, so the answer is about the snapshot it listed), after one probe `merge_base(<tip>, <branch>)` that the tip is still an ancestor of the branch.
    // A commit the listing no longer shows is only a CANDIDATE (GitLab's `since` can stop a listing early on a commit with a skewed date): its own merge base
    // means it is still on the branch (every old commit at day zero, the divergence commit always); any other commit means it left the branch (the old commits
    // after the force-push: they still exist as unreachable commits, their merge base is the fork point). Also mapped by branch name, as a human would ask.
    if (repo.day2Info?.forcePush) {
      const emitMergeBase = (name, sha, refOf, onlyDay2 = false) => w.emit(name, (state) => {
        const view = repo.views[stateKey(state)];
        if (!view || (onlyDay2 && stateKey(state) !== "day2")) return null;
        return {
          type: "single", urlPath: `${API}/projects/${p.id}/repository/merge_base`,
          query: { "refs[]": { includes: [{ equalTo: sha(view) }, { equalTo: refOf(view) }] } }, body: commitJson(mergeBase(repo.commits, sha(view), view.tip), p),
        };
      });
      for (const sha of [...repo.day2Info.removedShas, repo.day2Info.divergenceSha]) {
        emitMergeBase(`p${p.id}-merge-base-candidate-${sha.slice(0, 12)}`, () => sha, (view) => view.branch);
        emitMergeBase(`p${p.id}-merge-base-candidate-${sha.slice(0, 12)}-by-tip`, () => sha, (view) => view.tip);
      }
      // At day zero the probe is the started tip against the branch, which the `-started-tip` mappings above already answer.
      emitMergeBase(`p${p.id}-merge-base-tip-probe`, (view) => view.tip, (view) => view.branch, true);
    }
    // Commit diffs: gated to day2 only for commits that did not exist in the Started history.
    const startedSet = repo.views.started ? ancestors(repo.commits, repo.views.started.tip) : new Set();
    const ordered = [...repo.commits.values()].sort((a, b) => a.seq - b.seq);
    for (const c of ordered) {
      const state = startedSet.has(c.sha) ? undefined : "day2";
      const d = { type: "paged", urlPath: `${API}/projects/${p.id}/repository/commits/${c.sha}/diff`, query: { per_page: "100" }, linkParams: { id: p.id, sha: c.sha }, items: c.files.map(diffFileJson) };
      w.write(`p${p.id}-diff-${c.sha.slice(0, 12)}${state ? "-day2" : ""}`, d, state);
    }
  }

  // ---- merge requests ----------------------------------------------------------------------------------------------
  const mrState = (m, state) => {
    if (m.day2Only && state === "Started") return null;
    return effectiveMr(m, state === "day2" ? "day2" : "Started");
  };
  const pathIn = (m, state) => projectView(projectById.get(m.projectId), world.repos.get(m.projectId), state === "day2" ? "day2" : "Started", groupById);
  for (const g of world.groups) {
    w.emit(`group-${g.id}-merge-requests`, (state) => {
      const ids = descendants(g.id);
      const list = [];
      for (const m of world.mrs) {
        const e = mrState(m, state);
        if (!e) continue;
        const v = pathIn(m, state);
        if (ids.includes(v.groupId)) list.push({ e, v });
      }
      list.sort((a, b) => b.e.updatedMs - a.e.updatedMs || b.e.id - a.e.id);
      return {
        type: "paged", urlPath: `${API}/groups/${g.id}/merge_requests`,
        query: { scope: "all", state: "all", include_subgroups: "true", order_by: "updated_at", sort: "desc", per_page: "100" },
        linkParams: { id: g.id, include_subgroups: "true", order_by: "updated_at", scope: "all", sort: "desc", state: "all" },
        items: list.map(({ e, v }) => mrJson(e, { ...projectById.get(e.projectId), fullPath: v.fullPath })),
      };
    });
  }
  for (const m of world.mrs) {
    const base = `${API}/projects/${m.projectId}/merge_requests/${m.iid}`;
    w.emit(`p${m.projectId}-mr-${m.iid}-notes`, (state) => {
      const e = mrState(m, state);
      if (!e) return null;
      const notes = [...e.notes].sort((a, b) => a.at - b.at || a.id - b.id);
      return {
        type: "paged", urlPath: `${base}/notes`, query: { sort: "asc", order_by: "created_at", per_page: "100" },
        linkParams: { id: m.projectId, merge_request_iid: m.iid, order_by: "created_at", sort: "asc" }, items: notes.map((n) => noteJson(n, e)),
      };
    });
    w.emit(`p${m.projectId}-mr-${m.iid}-approvals`, (state) => {
      const e = mrState(m, state);
      return e ? { type: "single", urlPath: `${base}/approvals`, body: approvalsJson(e, { fullPath: pathIn(m, state).fullPath }) } : null;
    });
    w.emit(`p${m.projectId}-mr-${m.iid}-reviewers`, (state) => {
      const e = mrState(m, state);
      return e ? { type: "single", urlPath: `${base}/reviewers`, body: reviewersJson(e) } : null;
    });
  }

  // ---- GraphQL diffStatsSummary -------------------------------------------------------------------------------------
  const nullGap = world.nullGapMr;
  for (const p of world.projects) {
    w.emit(`graphql-p${p.id}`, (state) => {
      if (p.day2Only && state === "Started") return null;
      const fullPath = pathIn({ projectId: p.id }, state).fullPath;
      const list = world.mrs.filter((m) => m.projectId === p.id).map((m) => mrState(m, state)).filter(Boolean).sort((a, b) => a.iid - b.iid);
      const errors = [];
      const nodes = list.map((m, i) => {
        if (nullGap && m.projectId === nullGap.projectId && m.iid === nullGap.iid) {
          errors.push({ message: "Internal server error: could not compute the diff stats of this merge request", path: ["project", "mergeRequests", "nodes", i, "diffStatsSummary"], extensions: { code: "INTERNAL_SERVER_ERROR" } });
          return { iid: String(m.iid), diffStatsSummary: null };
        }
        const files = m.files;
        return { iid: String(m.iid), diffStatsSummary: { additions: files.reduce((s, f) => s + f.additions, 0), deletions: files.reduce((s, f) => s + f.deletions, 0), fileCount: files.length } };
      });
      const body = { data: { project: { mergeRequests: { nodes, pageInfo: { hasNextPage: false, endCursor: null } } } } };
      if (errors.length) body.errors = errors;
      return { type: "single", method: "POST", urlPath: "/api/graphql", bodyPatterns: [{ contains: fullPath }, { contains: "diffStatsSummary" }], body };
    });
  }

  // ---- 404s ----------------------------------------------------------------------------------------------------------
  const ids = (state) => world.projects.filter((p) => !(p.day2Only && state === "Started")).map((p) => p.id);
  for (const state of ["Started", "day2"]) {
    const known = ids(state).join("|");
    w.raw(`project-unknown-${state === "day2" ? "day2" : "started"}`, {
      priority: PRIORITY.notFound, scenarioName: SCENARIO, requiredScenarioState: state,
      request: { method: "GET", urlPathPattern: `(?i)${re(API)}/projects/(?!(?:${known})(?:/|$))[^/]+(?:/.*)?` },
      response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-project-not-found.json" },
    }, state === "Started" ? { message: "404 Project Not Found" } : undefined);
  }
  const groupIds = world.groups.map((g) => g.id).join("|");
  w.raw("group-unknown", {
    priority: PRIORITY.notFound,
    request: { method: "GET", urlPathPattern: `(?i)${re(API)}/groups/(?!(?:${groupIds})(?:/|$))[^/]+(?:/.*)?` },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-group-not-found.json" },
  }, { message: "404 Group Not Found" });
  w.raw("user-unknown", {
    priority: PRIORITY.notFound,
    request: { method: "GET", urlPathPattern: `${re(API)}/users/(?!(?:${world.users.map((u) => u.id).join("|")})(?:/|$))[^/]+(?:/.*)?` },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-user-not-found.json" },
  }, { message: "404 User Not Found" });
  w.raw("mr-unknown", {
    priority: PRIORITY.notFound,
    request: { method: "GET", urlPathPattern: `${re(API)}/projects/\\d+/merge_requests/\\d+(?:/.*)?` },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-mr-not-found.json" },
  }, { message: "404 Not found" });
  w.raw("commit-unknown", {
    priority: PRIORITY.notFound,
    request: { method: "GET", urlPathPattern: `${re(API)}/projects/\\d+/repository/commits/[0-9a-f]+(?:/.*)?` },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-commit-not-found.json" },
  }, { message: "404 Commit Not Found" });
  w.raw("branch-unknown", {
    priority: PRIORITY.notFound,
    request: { method: "GET", urlPathPattern: `${re(API)}/projects/\\d+/repository/branches/.+` },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-branch-not-found.json" },
  }, { message: "404 Branch Not Found" });
  w.raw("graphql-unknown-project", {
    priority: PRIORITY.notFound,
    request: { method: "POST", urlPath: "/api/graphql" },
    response: { status: 200, headers: { "Content-Type": "application/json" }, bodyFileName: "graphql-unknown-project.json" },
  }, { data: { project: null } });
  w.raw("api-404", {
    priority: PRIORITY.generic,
    request: { method: "ANY", urlPathPattern: "/api/.*" },
    response: { status: 404, headers: { "Content-Type": "application/json" }, bodyFileName: "404-not-found.json" },
  }, { error: "404 Not Found" });
}
