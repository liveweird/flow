// Self-test of generate.mjs: `node --test sample-data/gitlab/generate.test.mjs` (Node >=24, no install). Generates into temp
// directories (never over the committed stub) and checks determinism, that the committed stub is current, and the
// invariants the later connector tests rely on.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { generate, parseArgs } from "./generate.mjs";
import { countDiff, hunksConsistent } from "./lib/diff.mjs";
import { isExcluded } from "./lib/tree.mjs";
import { issueKeyCandidates } from "./lib/expected.mjs";
import { KEY_CASES } from "./lib/keycases.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const sampleData = path.resolve(here, "..");

function inTempDir(fn) {
  const dir = mkdtempSync(path.join(tmpdir(), "flow-gitlab-"));
  try {
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function listFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else out.push(path.relative(root, p));
    }
  };
  walk(root);
  return out.sort();
}

function sameTree(a, b) {
  const fa = listFiles(a);
  assert.deepEqual(fa, listFiles(b), "same file names");
  for (const f of fa) assert.equal(readFileSync(path.join(a, f), "utf8"), readFileSync(path.join(b, f), "utf8"), `same bytes in ${f}`);
}

const defaults = () => parseArgs([]);

test("the default world is byte-identical across runs and equal to the committed stub", () => {
  inTempDir((a) =>
    inTempDir((b) => {
      generate({ ...defaults(), out: a });
      generate({ ...defaults(), out: b });
      sameTree(path.join(a, "gitlab-stub"), path.join(b, "gitlab-stub"));
      assert.equal(readFileSync(path.join(a, "gitlab", "expected.json"), "utf8"), readFileSync(path.join(b, "gitlab", "expected.json"), "utf8"));
      // The committed output is a build artifact of the generator: regenerate and commit when this fails.
      sameTree(path.join(a, "gitlab-stub"), path.join(sampleData, "gitlab-stub"));
      assert.equal(readFileSync(path.join(a, "gitlab", "expected.json"), "utf8"), readFileSync(path.join(sampleData, "gitlab", "expected.json"), "utf8"));
    }),
  );
});

test("explicit default flags equal no flags; unknown flags and too-small values are refused", () => {
  inTempDir((a) =>
    inTempDir((b) => {
      generate({ ...defaults(), out: a });
      generate({ ...parseArgs(["--users", "6", "--projects=4", "--months", "6"]), out: b });
      sameTree(a, b);
    }),
  );
  assert.throws(() => parseArgs(["--bogus"]), /Unknown argument/);
  assert.throws(() => generate({ ...parseArgs(["--users", "2"]), out: "/nonexistent/never-written" }), /--users must be/);
});

test("the default world has the documented shape", () => {
  inTempDir((dir) => {
    const { expected: e } = generate({ ...defaults(), out: dir });
    assert.deepEqual(e.projects.inScopeIds, [1, 2, 3, 4]);
    assert.equal(e.projects.archivedIds.length, 1);
    assert.equal(e.projects.outOfScopeIds.length, 1);
    assert.equal(e.groups.length, 4);
    assert.ok(e.groups.some((g) => g.parentId !== null), "one subgroup");
    assert.equal(e.projects.list.filter((p) => p.defaultBranch === "master").length, 1);
    assert.ok(Math.abs(e.commits.inScopeTotals.total - 300) <= 15, `about 300 commits, got ${e.commits.inScopeTotals.total}`);
    assert.ok(e.commits.inScopeTotals.merges > 0);
    assert.ok(e.mergeRequests.total >= 55 && e.mergeRequests.total <= 65);
    assert.deepEqual(Object.keys(e.mergeRequests.byState).sort(), ["closed", "locked", "merged", "opened"]);
    assert.ok(e.mergeRequests.drafts > 0);
    assert.ok(e.users.botIds.length === 1 && e.users.adminIds.length === 1 && e.users.withSecondaryEmailIds.length === 1);
    assert.equal(e.users.unmatchedAuthors.length, 1);
    assert.ok(e.identities.some((i) => i.userId === null), "an unmatched commit author");
    assert.ok(e.special.manyFiles.files > 1000 && e.special.manyFiles.diffPages > 10);
    assert.ok(e.special.tooLarge && e.special.collapsed && e.special.hostileLines);
    for (const k of ["binaryFile", "newBinaryFile", "deletedFile", "pureRename", "renameWithEdits", "noNewlineAtEndOfFile", "minifiedBundle", "lockFile"]) assert.ok(e.special.examples[k], k);
    assert.ok(e.day2.forcePush.removedShas.length > 0 && e.day2.deletedMergeRequest && e.day2.movedProject && e.day2.newProject && e.day2.renamedDefaultBranch);
    assert.ok(e.commits.committedBeforeParent > 0 && e.commits.authoredMoreThanADayBeforeCommitted > 0);
  });
});

test("every emitted diff counts to its ground truth and every hunk header is exact", () => {
  inTempDir((dir) => {
    generate({ ...defaults(), out: dir });
    const filesDir = path.join(dir, "gitlab-stub", "__files");
    let checked = 0;
    for (const name of readdirSync(filesDir).filter((n) => n.includes("-diff-"))) {
      for (const f of JSON.parse(readFileSync(path.join(filesDir, name), "utf8"))) {
        assert.ok(hunksConsistent(f.diff), `${name} ${f.new_path}`);
        if (f.too_large || f.collapsed) assert.equal(f.diff, "");
        checked++;
      }
    }
    assert.ok(checked > 800);
    const { additions, deletions } = countDiff("@@ -1,3 +1,3 @@\n a\n--- x\n+++ y\n-b\n\\ No newline at end of file\n+c\n");
    assert.deepEqual({ additions, deletions }, { additions: 2, deletions: 2 });
  });
});

test("the expected counted lines apply the default path exclusions", () => {
  inTempDir((dir) => {
    const { expected: e } = generate({ ...defaults(), out: dir });
    for (const [p, ex] of Object.entries(e.pathExclusions.perProject)) {
      let counted = 0;
      for (const c of Object.values(e.commitFiles[p])) {
        if (c.merge) continue;
        counted += c.files.slice(0, e.pathExclusions.fileCapPerCommit).filter((f) => !isExcluded(f.path)).reduce((s, f) => s + f.additions, 0);
      }
      assert.equal(ex.counted.additions, counted, `project ${p}`);
      assert.ok(ex.counted.additions < ex.raw.additions);
    }
  });
});

test("every in-scope GitLab user that matches a Jira stub person matches by an email the Jira stub really has, on a team", () => {
  inTempDir((dir) => {
    const { expected: e } = generate({ ...defaults(), out: dir });
    const jira = JSON.parse(readFileSync(path.join(sampleData, "jira", "expected.json"), "utf8"));
    const teamOf = new Map(jira.teams.roster.map((r) => [Number(/(\d+)$/.exec(r.displayName)[1]), r.team]));
    assert.ok(e.users.jiraAlignment.matches.length >= 5);
    for (const m of e.users.jiraAlignment.matches) {
      assert.equal(m.jiraEmail, `user${m.jiraUserNumber}@flow-sample.example`);
      assert.equal(m.team, teamOf.get(m.jiraUserNumber), `team of Jira user ${m.jiraUserNumber}`);
      assert.ok(m.team, "on a team");
    }
    assert.ok(e.users.jiraAlignment.matches.some((m) => m.matchedBy === "secondary"));
  });
});

test("the stub references only bodies that exist and one scenario", () => {
  inTempDir((dir) => {
    const { mappingCount } = generate({ ...defaults(), out: dir });
    const mappings = path.join(dir, "gitlab-stub", "mappings");
    const files = new Set(readdirSync(path.join(dir, "gitlab-stub", "__files")));
    const names = readdirSync(mappings);
    assert.equal(names.length, mappingCount);
    assert.ok(names.length < 1500, "kept on the order of the Jira stub (801 mapping/body pairs)");
    for (const n of names) {
      const m = JSON.parse(readFileSync(path.join(mappings, n), "utf8"));
      assert.ok(files.has(m.response.bodyFileName), `${n} -> ${m.response.bodyFileName}`);
      if (m.scenarioName) assert.equal(m.scenarioName, "gitlab-day2");
      if (m.requiredScenarioState) assert.ok(["Started", "day2"].includes(m.requiredScenarioState));
    }
    assert.ok(existsSync(path.join(dir, "gitlab", "expected.json")));
  });
});

test("a scaled world builds with the same invariants", () => {
  inTempDir((dir) => {
    const { expected: e } = generate({ ...parseArgs(["--users", "9", "--projects", "6", "--months", "3"]), out: dir });
    assert.deepEqual(e.generator.params, { users: 9, projects: 6, months: 3 });
    assert.equal(e.projects.inScopeIds.length, 6);
    assert.ok(e.users.list.filter((u) => u.kind === "human").length === 9);
    assert.ok(e.special.manyFiles.files > 1000);
  });
});

test("the issue-key oracle follows the token rule on its boundaries (the cases IssueKeysTest pins on the Kotlin side)", () => {
  const keys = (title, branch = "") => issueKeyCandidates(title, branch).map((c) => `${c.key}${c.inTitle ? "T" : ""}${c.inBranch ? "B" : ""}`);
  assert.deepEqual(keys("[FLO-12] (FLO-12): FLO-12, x"), ["FLO-12T"]);
  assert.deepEqual(keys("", "feature/flo-12-fix"), ["FLO-12B"]);
  assert.deepEqual(keys("FLO-12 fix", "fix/flo-12-fix"), ["FLO-12TB"]);
  assert.deepEqual(keys("FLO-12a FLO-012 2FLO-12 FLO-12345678 A-1 TOOLONGKEYX-1"), []);
  assert.deepEqual(keys("xFLO-12 FLO-12_fix flo_12"), ["FLO-12T", "XFLO-12T"]);
  assert.deepEqual(keys("fix_FLO-12"), ["FIX_FLO-12T", "FLO-12T"]);
  assert.deepEqual(keys("MY_PROJ-1 A_B-1"), ["A_B-1T", "MY_PROJ-1T", "PROJ-1T"]);
  assert.deepEqual(keys("abcdefghijk_FLO-12"), ["FLO-12T"]);
  assert.deepEqual(keys("UTF-8, SHA-256, v2-3, \u00e9FLO-1"), ["FLO-1T", "SHA-256T", "UTF-8T", "V2-3T"]);
  assert.deepEqual(keys("ABCDEFGHI_FLO-123456 FLO-1234567"), ["FLO-123456T", "FLO-1234567T"], "a 13-character project part is no token, the key after its underscore is");
  assert.deepEqual(keys(null, undefined), []);
});

test("the issue-key oracle equals an independent regex over every merge request, and every edge case is present", () => {
  inTempDir((dir) => {
    const { world, expected: e } = generate({ ...defaults(), out: dir });
    // The Kotlin rule as one regex (norm/IssueKeys.kt), minus the underscore suffixes, which the cases below pin separately.
    const token = /(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9_]{1,9})-([1-9][0-9]{0,6})(?![A-Za-z0-9])/g;
    const viaRegex = (text) => new Set([...text.matchAll(token)].map((m) => `${m[1].toUpperCase()}-${m[2]}`));
    const oracle = new Map(e.mergeRequests.issueKeyCandidates.list.map((m) => [`${m.projectId}!${m.iid}`, m.keys]));
    let rows = 0;
    for (const m of world.mrs.filter((x) => !x.day2Only)) {
      const wanted = new Set([...viaRegex(m.title), ...viaRegex(m.sourceBranch)]);
      const got = new Set((oracle.get(`${m.projectId}!${m.iid}`) ?? []).map((c) => c.key));
      for (const k of wanted) assert.ok(got.has(k), `${m.projectId}!${m.iid} names ${k}`);
      // The oracle adds only the underscore-delimited suffixes of a regex match (FIX_FLO-8 also names FLO-8).
      for (const k of got) assert.ok(wanted.has(k) || [...wanted].some((w) => w.endsWith(`_${k}`)), `${m.projectId}!${m.iid} oracle-only ${k}`);
      rows += got.size;
    }
    assert.equal(e.mergeRequests.issueKeyCandidates.rows, rows);
    assert.equal(e.mergeRequests.issueKeyCandidates.mergeRequests, oracle.size);
    // The deterministic edge cases: each landed on a merge request whose oracle row is exactly what the case declares.
    const planned = new Map(world.mrs.filter((m) => m.keyCase).map((m) => [m.keyCase, m]));
    for (const [projectId, cases] of Object.entries(KEY_CASES)) {
      for (const c of cases) {
        const m = planned.get(c.name);
        assert.ok(m && m.projectId === Number(projectId), `case ${c.name} landed in project ${projectId}`);
        assert.deepEqual((oracle.get(`${m.projectId}!${m.iid}`) ?? []).map((k) => [k.key, k.inTitle, k.inBranch]), c.wants, c.name);
        assert.ok(!m.isBot && !m.draft && !m.day2Action, `${c.name} sits on a stable human merge request`);
      }
    }
    assert.equal(planned.size, Object.values(KEY_CASES).flat().length, "every case is distinct");
    const all = [...oracle.values()].flat().map((k) => k.key);
    for (const key of ["FLO-366", "FLO-900", "ABC-12", "SEC-5", "UTF-8", "V2-3"]) assert.ok(all.includes(key), key);
    for (const prefix of ["FLO", "PLT", "GTM", "OPS"]) assert.ok(all.some((k) => k.startsWith(`${prefix}-`)), `${prefix} keys`);
  });
});

test("the key edge cases leave every other figure of the world alone (no PRNG draw)", () => {
  inTempDir((dir) => {
    const { expected: e } = generate({ ...defaults(), out: dir });
    assert.equal(e.mergeRequests.total, 60);
    assert.equal(e.commits.inScopeTotals.total, 300);
  });
});
