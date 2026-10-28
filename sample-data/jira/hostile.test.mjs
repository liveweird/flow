// Self-test of hostile.mjs: `node --test sample-data/jira/hostile.test.mjs` (Node >=24, no install). Generates into a temp
// directory (never the committed stub) and checks the properties the heap measurement relies on.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertNotCommittedStub, checkDir, generate, nodeCeiling, scanTokens } from "./hostile.mjs";

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
