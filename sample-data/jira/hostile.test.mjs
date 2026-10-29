// Self-test of hostile.mjs: `node --test sample-data/jira/hostile.test.mjs` (Node >=24, no install). Generates into a temp
// directory (never the committed stub) and checks the properties the heap measurement relies on.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertNotCommittedStub, checkDir, generate, nodeCeiling, paddingCountForBytes, scanTokens } from "./hostile.mjs";

const EIGHT_MIB = 8 * 1024 * 1024;

function inTempDir(fn) {
  const dir = mkdtempSync(path.join(tmpdir(), "flow-hostile-"));
  try {
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("scanTokens counts { [ : , outside strings and the depth", () => {
  assert.deepEqual(scanTokens('{"a":[{"a":0},{"a":0}]}'), { tokens: 8, maxDepth: 3 });
  assert.deepEqual(scanTokens('{"s":"{[:,\\"]"}'), { tokens: 2, maxDepth: 1 });
});

test("every body is just under the node ceiling and far under the byte cap", () => {
  inTempDir((dir) => {
    const { written } = generate({ outDir: dir, pages: 4 });
    assert.equal(written.length, 5); // 4 search pages + /field
    for (const r of written) {
      assert.ok(r.tokens < nodeCeiling(EIGHT_MIB), `${r.name}: ${r.tokens} tokens`);
      assert.ok(r.tokens > nodeCeiling(EIGHT_MIB) - 1000, `${r.name} is dense: ${r.tokens} tokens`);
      assert.ok(r.bytes < EIGHT_MIB, `${r.name}: ${r.bytes} bytes`);
    }
    assert.equal(checkDir({ outDir: dir }).length, 5);
  });
});

test("the page chain links page 1 to page N and ends without a token", () => {
  inTempDir((dir) => {
    generate({ outDir: dir, pages: 3 });
    const page = (n) => JSON.parse(readFileSync(path.join(dir, "jira-stub", "__files", `hostile-search-jql-page-${n}.json`), "utf8"));
    assert.equal(page(1).nextPageToken, "Hostile-page-2");
    assert.equal(page(2).nextPageToken, "Hostile-page-3");
    assert.equal(page(3).nextPageToken, undefined);
    assert.equal(page(3).isLast, true);
    for (const n of [1, 2, 3]) {
      const issue = page(n).issues[0];
      assert.match(issue.id, /^\d+$/);
      assert.ok(issue.key && issue.fields.project.id && issue.fields.project.key && issue.fields.updated);
    }
  });
});

test("the output is deterministic and --pad page moves the padding off the issue", () => {
  inTempDir((a) =>
    inTempDir((b) => {
      generate({ outDir: a, pages: 2 });
      generate({ outDir: b, pages: 2 });
      const files = readdirSync(path.join(a, "jira-stub", "__files"));
      for (const f of files) {
        assert.equal(readFileSync(path.join(a, "jira-stub", "__files", f), "utf8"), readFileSync(path.join(b, "jira-stub", "__files", f), "utf8"));
      }
    }),
  );
  inTempDir((dir) => {
    generate({ outDir: dir, pages: 1, padWhere: "page" });
    const doc = JSON.parse(readFileSync(path.join(dir, "jira-stub", "__files", "hostile-search-jql-page-1.json"), "utf8"));
    assert.ok(doc.a.length > 300000);
    assert.equal(doc.issues[0].fields.a, undefined);
  });
});

test("a cap raised on the target raises the ceiling the padding aims at", () => {
  inTempDir((dir) => {
    const cap = 16 * 1024 * 1024;
    const { written } = generate({ outDir: dir, pages: 1, maxResponseBytes: cap });
    for (const r of written) assert.ok(r.tokens < nodeCeiling(cap) && r.tokens > nodeCeiling(EIGHT_MIB), r.name);
  });
});

test("an --out whose jira-stub is the committed sample-data/jira-stub is refused, however it is spelled", () => {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const sampleData = path.resolve(here, "..");
  assert.throws(() => assertNotCommittedStub(sampleData), /committed sample-data\/jira-stub/);
  assert.throws(() => generate({ outDir: sampleData, pages: 1 }), /committed sample-data\/jira-stub/);
  assert.throws(() => assertNotCommittedStub(path.join(here, "..", "jira", "..")), /committed/);
  inTempDir((dir) => {
    // A symlinked scratch dir pointing at sample-data is the same directory.
    const link = path.join(dir, "link");
    symlinkSync(sampleData, link);
    assert.throws(() => assertNotCommittedStub(link), /committed/);
    assert.doesNotThrow(() => assertNotCommittedStub(path.join(dir, "scratch")));
  });
});

const files = (dir) => path.join(dir, "jira-stub", "__files");
const mappings = (dir) => path.join(dir, "jira-stub", "mappings");
const readJson = (file) => JSON.parse(readFileSync(file, "utf8"));
const bytes = (value) => Buffer.byteLength(JSON.stringify(value), "utf8");

// The stored-size ceilings of the target (server: jira/JiraStoredCeilings.kt).
const MAX_STORED_HISTORY_BYTES = 512 * 1024;
const MAX_STORED_WORKLOG_BYTES = 16 * 1024;

test("--pad history puts one dense history under a kept key, just under the history ceiling by default", () => {
  inTempDir((dir) => {
    const { written } = generate({ outDir: dir, pages: 6, padWhere: "history" });
    // 6 plain search pages + ONE bulkfetch answer (no /field: the padding is the history).
    assert.equal(written.length, 7);
    assert.ok(!existsSync(path.join(files(dir), "hostile-field.json")));
    const page = readJson(path.join(files(dir), "hostile-search-jql-page-1.json"));
    assert.equal(page.issues[0].fields.a, undefined, "the issue itself is plain");
    const bulk = readJson(path.join(files(dir), "hostile-changelog-bulkfetch.json"));
    assert.ok(bulk.issueChangeLogs.length >= 1 && bulk.issueChangeLogs.length <= 6);
    for (const entry of bulk.issueChangeLogs) {
      assert.match(entry.issueId, /^9\d{5}$/);
      const [history] = entry.changeHistories;
      const size = bytes(history);
      assert.ok(size <= 500000 && size > 500000 - 8, `history text ${size} aims at 500000`);
      assert.ok(size < MAX_STORED_HISTORY_BYTES, "under the 512 KiB history ceiling");
      assert.ok(history.historyMetadata.a.length > 60000, "dense objects under historyMetadata, a key the raw trim keeps");
    }
    const m = readJson(path.join(mappings(dir), "hostile-changelog-bulkfetch.json"));
    assert.equal(m.request.method, "POST");
    assert.match(m.request.urlPath, /\/changelog\/bulkfetch$/);
    assert.ok(new RegExp(m.request.bodyPatterns[0].matchesJsonPath.match(/\/(.+)\//)[1]).test("900001"));
    assert.ok(!new RegExp(m.request.bodyPatterns[0].matchesJsonPath.match(/\/(.+)\//)[1]).test("30001"), "a normal issue id is not a hostile one");
  });
});

test("--stored-bytes aims a history just under or just over the 512 KiB ceiling, and a worklog around 16 KiB", () => {
  for (const [padWhere, ceiling] of [["history", MAX_STORED_HISTORY_BYTES], ["worklog", MAX_STORED_WORKLOG_BYTES]]) {
    for (const target of [ceiling - 1000, ceiling + 1000]) {
      inTempDir((dir) => {
        generate({ outDir: dir, pages: 2, padWhere, storedBytes: target });
        const item =
          padWhere === "history"
            ? readJson(path.join(files(dir), "hostile-changelog-bulkfetch.json")).issueChangeLogs[0].changeHistories[0]
            : readJson(path.join(files(dir), "hostile-issue-900001-worklog.json")).worklogs[0];
        const size = bytes(item);
        assert.ok(size <= target && size > target - 8, `${padWhere}: ${size} bytes aims at ${target}`);
        assert.equal(size < ceiling, target < ceiling, `${padWhere} ${target} is on the intended side of ${ceiling}`);
      });
    }
  }
});

test("--pad worklog answers each hostile issue's worklog page with one dense worklog under `properties`", () => {
  inTempDir((dir) => {
    const { written } = generate({ outDir: dir, pages: 3, padWhere: "worklog" });
    assert.equal(written.length, 6); // 3 search pages + 3 per-issue worklog pages
    for (const issueId of [900001, 900002, 900003]) {
      const doc = readJson(path.join(files(dir), `hostile-issue-${issueId}-worklog.json`));
      assert.deepEqual([doc.startAt, doc.total], [0, 1]);
      const [worklog] = doc.worklogs;
      assert.equal(worklog.issueId, String(issueId));
      assert.ok(bytes(worklog) <= 15000 && bytes(worklog) > 15000 - 8 && bytes(worklog) < MAX_STORED_WORKLOG_BYTES);
      assert.ok(Array.isArray(worklog.properties) && worklog.properties.length > 1500);
      const m = readJson(path.join(mappings(dir), `hostile-issue-${issueId}-worklog.json`));
      assert.equal(m.request.method, "GET");
      assert.match(m.request.urlPath, new RegExp(`/issue/${issueId}/worklog$`));
    }
  });
});

test("--stored-bytes sizes the dense page and field padding of --pad issue, still under the node ceiling", () => {
  inTempDir((dir) => {
    const target = 1_000_000;
    const { written } = generate({ outDir: dir, pages: 2, storedBytes: target });
    const page = written.find((r) => r.name === "hostile-search-jql-page-1.json");
    assert.ok(page.bytes <= target && page.bytes > target - 8, `${page.bytes} aims at ${target}`);
    const field = written.find((r) => r.name === "hostile-field.json");
    assert.ok(field.bytes <= target + 2000 && field.bytes > target - 8 - 2000, `the field list is ${field.bytes} bytes`);
    for (const r of written) assert.ok(r.tokens < nodeCeiling(EIGHT_MIB), r.name);
  });
});

test("a smaller --stored-bytes than the item itself, and a bad --pad, are refused", () => {
  inTempDir((dir) => {
    assert.throws(() => generate({ outDir: dir, padWhere: "history", storedBytes: 10 }), /not over the item's own/);
    assert.throws(() => generate({ outDir: dir, padWhere: "comment" }), /--pad must be one of/);
    assert.throws(() => generate({ outDir: dir, storedBytes: 0 }), /--stored-bytes must be a positive integer/);
    assert.throws(() => paddingCountForBytes(() => ({ a: [] }), 3), /not over/);
  });
});

test("re-running with another --pad leaves no stale hostile file behind", () => {
  inTempDir((dir) => {
    generate({ outDir: dir, pages: 3, padWhere: "worklog" });
    generate({ outDir: dir, pages: 3, padWhere: "history" });
    const names = readdirSync(files(dir));
    assert.ok(!names.some((n) => n.includes("worklog")), names.join(","));
    assert.ok(names.includes("hostile-changelog-bulkfetch.json"));
  });
});

test("--overlay keeps the normal GET /field mapping for --pad history and worklog, and replaces it for --pad issue", () => {
  const seed = (dir) => {
    mkdirSync(files(dir), { recursive: true });
    mkdirSync(mappings(dir), { recursive: true });
    const gateway = "/ex/jira/b3f1a2c4-5d6e-4f7a-8b9c-0d1e2f3a4b5c/rest/api/3";
    writeFileSync(
      path.join(files(dir), "search-jql-started-page-1.json"),
      JSON.stringify({
        issues: [{ id: "30001", key: "FLO-1", fields: { project: { id: "10000", key: "FLO" }, updated: "2026-08-31T12:00:00.000+0000" } }],
      }),
    );
    writeFileSync(path.join(files(dir), "field.json"), JSON.stringify([{ id: "summary", name: "Summary", custom: false }]));
    writeFileSync(path.join(mappings(dir), "field.json"), JSON.stringify({ request: { urlPath: `${gateway}/field` } }));
    writeFileSync(path.join(mappings(dir), "other.json"), JSON.stringify({ request: { urlPath: `${gateway}/status` } }));
  };
  for (const [padWhere, fieldKept] of [["history", true], ["worklog", true], ["issue", false]]) {
    inTempDir((dir) => {
      seed(dir);
      generate({ outDir: dir, pages: 2, padWhere, overlay: true });
      assert.equal(existsSync(path.join(mappings(dir), "field.json")), fieldKept, `${padWhere}: normal /field mapping`);
      assert.ok(existsSync(path.join(mappings(dir), "other.json")), "every other endpoint stays");
    });
  }
});
