#!/usr/bin/env node
// Deterministic HOSTILE Jira host for the worker-heap measurement (`.claude/docs/jira-integration.md` "Node and depth ceiling").
//
// A WireMock mapping/__files set that emulates a Jira host behind the allow-list answering with the densest document the node
// ceiling lets through: `search/jql` pages (a `nextPageToken` chain) and the `GET /field` body each carry padding of the shape
// "a":[{"a":0},{"a":0},...] sized to JUST UNDER the node ceiling (maxResponseBytes / 8 = 1,048,576 structural tokens at the
// default 8 MiB) and far under the byte cap (~2.8 MiB on the wire: the worst parsed-tree shape found, ~108 MiB retained).
// The pages still pass every typed read of the ISSUES stream (id, key, fields.project.{id,key}, fields.updated), so a SYNC runs
// the whole way and the heap measurement sees the real retention, not a refusal.
//
// Zero dependencies (Node >=24 stdlib only), no randomness and no clock: two runs produce byte-identical output.
//
// Usage: node sample-data/jira/hostile.mjs --out DIR [--pages N] [--pad issue|page|history|worklog] [--stored-bytes B] [--overlay]
//                                          [--max-response-bytes B]
//        node sample-data/jira/hostile.mjs --out DIR --check
//
//   --out DIR      the directory whose `jira-stub/` child is written (the same meaning as generate.mjs's --out, and what
//                  FLOW_PERF_STUB_DIR mounts). Required: this never touches the committed sample-data/jira-stub.
//   --pages N      the length of the search/jql `nextPageToken` chain (default 20).
//   --pad WHERE    `issue` (default): the padding is a field of each page's one issue, so it is RETAINED with the issue (the
//                  worst case: the tree lives on in the stream's page); `page`: a top-level key of the response, which the
//                  typed decode drops once it is read (construction only).
//                  `history` / `worklog` (the stored-size ceilings, `.claude/docs/jira-integration.md` "Stored-size ceilings"): the
//                  search pages carry a PLAIN hostile issue and the padding is ONE changelog history (answering
//                  `POST changelog/bulkfetch` for the hostile issue ids) or ONE worklog (answering `GET issue/{id}/worklog`) per
//                  hostile issue, dense `{"a":0}` objects under a key the raw trims KEEP (`historyMetadata`, `properties`).
//   --stored-bytes B  aim the padded item's own JSON text at B bytes (the stored canonical text is within a few bytes of it:
//                  the trims only slim the author), to land just under or just over a stored-size ceiling (512 KiB a history,
//                  16 KiB a worklog, `jira.maxStoredPayloadBytes` an issue). Default: history 500000, worklog 15000 (just under
//                  their ceilings); for `issue`/`page` the default stays "just under the node ceiling".
//   --overlay      layer the hostile mappings over the normal stub already generated into DIR (run generate.mjs --out DIR
//                  first): the normal mappings the hostile ones supersede (search/jql with the ISSUES `fields`, and GET /field)
//                  are removed, every other endpoint (reference data, changelog, worklog, boards...) stays, and the hostile
//                  issue and field body are built on the normal stub's own first issue and field list. Without it only the
//                  hostile endpoints exist (enough to test the response path, not a whole SYNC). WireMock mounts ONE
//                  directory, so layering is a file overlay, never two mounts.
//   --max-response-bytes B  the `jira.maxResponseBytes` the target runs with (default 8388608); the node ceiling is B / 8.
//   --check        verify the already-written `DIR/jira-stub/__files/hostile-*.json` (token count under the ceiling, size
//                  under B, depth under 512, valid JSON) and write nothing. Generation always self-checks what it wrote.

import { existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const CLOUD_ID = "b3f1a2c4-5d6e-4f7a-8b9c-0d1e2f3a4b5c"; // the normal stub's fixed gateway cloudId
const GATEWAY = `/ex/jira/${CLOUD_ID}/rest/api/3`;
const ISSUE_SEARCH_FIELDS = "*all,-comment,-worklog,-description,-attachment"; // jira/JiraIssuesStream.kt
const BYTES_PER_NODE = 8; // jira/JiraNodeCeiling.kt RESPONSE_BYTES_PER_NODE
const MAX_DEPTH = 512; // jira/JiraNodeCeiling.kt MAX_RESPONSE_DEPTH
const DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
/** Tokens kept below the ceiling, so the document is "just under" without an off-by-one risk. */
const TOKEN_MARGIN = 64;
const HOSTILE_PREFIX = "hostile-";
const PAD_WHERE = ["issue", "page", "history", "worklog"];
/** `--pad history|worklog` default sizes: just under `MAX_STORED_HISTORY_BYTES` (512 KiB) and `MAX_STORED_WORKLOG_BYTES` (16 KiB). */
const DEFAULT_STORED_BYTES = { history: 500000, worklog: 15000 };
const HOSTILE_ID_BASE = 900000;
/** Matches the hostile issue ids (900001..): the bulkfetch mapping answers any batch that names one. */
const HOSTILE_ID_REGEX = "^9[0-9]{5}$";
const TOKEN_PREFIX = "Hostile-page-";

// ---------------------------------------------------------------------------------------------
// The ceiling scan: a port of `jsonCeilingBreach` (jira/JiraNodeCeiling.kt). Structural tokens are every `{`, `[`, `:` and `,`
// outside a string; depth is the open containers outside strings.
// ---------------------------------------------------------------------------------------------
export function scanTokens(text) {
  let tokens = 0;
  let depth = 0;
  let maxDepth = 0;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (c === 0x5c) escaped = true;
      else if (c === 0x22) inString = false;
      continue;
    }
    if (c === 0x22) inString = true;
    else if (c === 0x7b || c === 0x5b) {
      tokens++;
      depth++;
      if (depth > maxDepth) maxDepth = depth;
    } else if (c === 0x7d || c === 0x5d) {
      if (depth > 0) depth--;
    } else if (c === 0x3a || c === 0x2c) tokens++;
  }
  return { tokens, maxDepth };
}

export function nodeCeiling(maxResponseBytes) {
  return Math.floor(maxResponseBytes / BYTES_PER_NODE);
}

/** Throws unless `text` is valid JSON under the node ceiling, the depth ceiling and the byte cap of a target running `maxResponseBytes`. */
export function verifyBody(name, text, maxResponseBytes) {
  JSON.parse(text);
  const bytes = Buffer.byteLength(text, "utf8");
  const { tokens, maxDepth } = scanTokens(text);
  const ceiling = nodeCeiling(maxResponseBytes);
  if (bytes >= maxResponseBytes) throw new Error(`${name}: ${bytes} bytes is not under the ${maxResponseBytes} byte cap`);
  if (tokens >= ceiling) throw new Error(`${name}: ${tokens} tokens is not under the ${ceiling} node ceiling`);
  if (maxDepth > MAX_DEPTH) throw new Error(`${name}: depth ${maxDepth} is over the ${MAX_DEPTH} depth ceiling`);
  return { name, bytes, tokens, maxDepth, ceiling };
}

// ---------------------------------------------------------------------------------------------
// Documents
// ---------------------------------------------------------------------------------------------

/** `n` padding objects `{"a":0}`: 2 tokens each (`{`, `:`) plus the commas between them = 3n - 1 tokens, 8 wire bytes each. */
function padding(n) {
  return Array.from({ length: n }, () => ({ a: 0 }));
}

/**
 * The padding count that puts `build(n)` just under the ceiling: measure the document with an EMPTY padding array (tokens `base`),
 * then each added object adds 3 tokens (2 + its separating comma), the first one only 2.
 */
export function paddingCountFor(build, maxResponseBytes) {
  const base = scanTokens(JSON.stringify(build(0))).tokens;
  const budget = nodeCeiling(maxResponseBytes) - TOKEN_MARGIN - base;
  if (budget < 2) throw new Error(`the document itself (${base} tokens) leaves no room under the ceiling`);
  const n = Math.floor((budget + 1) / 3);
  // The exact count is re-verified by verifyBody after the body is serialised; this guards the arithmetic itself.
  const actual = scanTokens(JSON.stringify(build(n))).tokens;
  if (actual !== base + 3 * n - 1) throw new Error(`padding arithmetic drifted: ${actual} tokens for ${n} objects over a base of ${base}`);
  return n;
}

/**
 * The padding count that puts `build(n)` at `targetBytes` of minified JSON (never over): `n` dense objects add 8n - 1 bytes to the
 * empty-padding document (7 bytes an object plus the commas between them). Verified by measuring, so a drift fails loudly.
 */
export function paddingCountForBytes(build, targetBytes) {
  const base = Buffer.byteLength(JSON.stringify(build(0)), "utf8");
  if (targetBytes <= base) throw new Error(`--stored-bytes ${targetBytes} is not over the item's own ${base} bytes`);
  const n = Math.floor((targetBytes - base + 1) / 8);
  const actual = Buffer.byteLength(JSON.stringify(build(n)), "utf8");
  if (n >= 1 && actual !== base + 8 * n - 1) throw new Error(`byte arithmetic drifted: ${actual} bytes for ${n} objects over a base of ${base}`);
  return n;
}

const pageToken = (page) => `${TOKEN_PREFIX}${page}`;

/** A restIso-shaped timestamp (jira/JiraTime.kt accepts `+0000`); fixed, never the clock. */
const UPDATED = "2026-08-31T12:00:00.000+0000";

function hostileIssue(template, page, padWhere, n) {
  // A fixed id range far above the normal stub's (30001..), keys under the template's own in-scope project.
  const id = HOSTILE_ID_BASE + page;
  const projectKey = template.fields.project.key;
  const issue = {
    ...template,
    id: String(id),
    key: `${projectKey}-${HOSTILE_ID_BASE + page}`,
    fields: { ...template.fields, updated: UPDATED },
  };
  if (padWhere === "issue") issue.fields.a = padding(n);
  return issue;
}

function searchPage(template, page, pages, padWhere, n) {
  const doc = { issues: [hostileIssue(template, page, padWhere, n)] };
  if (padWhere === "page") doc.a = padding(n);
  doc.isLast = page === pages;
  if (page < pages) doc.nextPageToken = pageToken(page + 1);
  return doc;
}

const MINIMAL_ISSUE = {
  id: "900000",
  key: "HST-900000",
  fields: {
    summary: "hostile padding carrier",
    status: { id: "10000", name: "To Do", statusCategory: { id: 2, key: "new", name: "To Do" } },
    issuetype: { id: "10001", name: "Story", subtask: false },
    project: { id: "10000", key: "HST", name: "Hostile" },
    created: UPDATED,
    updated: UPDATED,
  },
};

const MINIMAL_FIELDS = [{ id: "summary", name: "Summary", custom: false, navigable: true, orderable: true, searchable: true }];

// ---------------------------------------------------------------------------------------------
// WireMock mappings
// ---------------------------------------------------------------------------------------------
const AUTH_HEADER = { Authorization: { matches: "^(Basic|Bearer) .+$" } };

function mapping(urlPath, query, bodyFileName) {
  return {
    priority: 1,
    request: { method: "GET", urlPath: `${GATEWAY}${urlPath}`, ...(query ? { queryParameters: query } : {}), headers: AUTH_HEADER },
    response: { status: 200, headers: { "Content-Type": "application/json" }, bodyFileName },
  };
}

function searchMapping(page) {
  const query = { fields: { equalTo: ISSUE_SEARCH_FIELDS }, nextPageToken: page === 1 ? { absent: true } : { equalTo: pageToken(page) } };
  return mapping("/search/jql", query, `${HOSTILE_PREFIX}search-jql-page-${page}.json`);
}

/** The one padded history of hostile issue `page`: `historyMetadata` is kept verbatim by the raw trim, so the stored text carries the padding. */
function hostileHistory(page, n) {
  return {
    id: String(HOSTILE_ID_BASE + 50000 + page), // far above the normal stub's history ids (500000..)
    created: 1790330188061,
    author: { accountId: "hostile-author" },
    items: [],
    historyMetadata: { a: padding(n) },
  };
}

/** The one padded worklog of hostile issue `page`: `properties` is kept verbatim by the worklog trim. */
function hostileWorklog(page, n) {
  const id = HOSTILE_ID_BASE + page;
  return {
    id: String(HOSTILE_ID_BASE + 70000 + page),
    issueId: String(id),
    author: { accountId: "hostile-author" },
    started: UPDATED,
    created: UPDATED,
    updated: UPDATED,
    timeSpentSeconds: 3600,
    properties: padding(n),
  };
}

function bulkfetchMapping(bodyFileName) {
  return {
    priority: 1,
    request: {
      method: "POST",
      urlPath: `${GATEWAY}/changelog/bulkfetch`,
      bodyPatterns: [{ matchesJsonPath: `$.issueIdsOrKeys[?(@ =~ /${HOSTILE_ID_REGEX}/)]` }],
      headers: AUTH_HEADER,
    },
    response: { status: 200, headers: { "Content-Type": "application/json" }, bodyFileName },
  };
}

/** True when a normal-stub mapping is one the hostile set supersedes (same URL and ISSUES `fields`, or GET /field). */
function isSuperseded(m, replacesField = true) {
  const req = m?.request ?? {};
  if (req.urlPath === `${GATEWAY}/field`) return replacesField;
  return req.urlPath === `${GATEWAY}/search/jql` && req.queryParameters?.fields?.equalTo === ISSUE_SEARCH_FIELDS;
}

// ---------------------------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------------------------
/** The committed stub this script must never write: `sample-data/jira-stub`, beside this script's directory. */
const COMMITTED_STUB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "jira-stub");

/** `p` with symlinks resolved as far as it exists (the deepest existing ancestor's realpath, then the rest as written). */
function realpathLoose(p) {
  const abs = path.resolve(p);
  if (existsSync(abs)) return realpathSync(abs);
  const parent = path.dirname(abs);
  return parent === abs ? abs : path.join(realpathLoose(parent), path.basename(abs));
}

/** Refuses an `--out` whose `jira-stub/` is the committed `sample-data/jira-stub` (symlinks and `..` resolved): the overlay deletes mappings. */
export function assertNotCommittedStub(outDir) {
  if (realpathLoose(path.join(path.resolve(outDir), "jira-stub")) === realpathLoose(COMMITTED_STUB)) {
    throw new Error(`--out ${outDir} resolves to the committed sample-data/jira-stub; pick a scratch directory (this never writes it)`);
  }
}

export function generate({
  outDir,
  pages = 20,
  padWhere = "issue",
  overlay = false,
  maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
  storedBytes = null,
}) {
  if (!Number.isInteger(pages) || pages < 1) throw new Error(`--pages must be a positive integer, got: ${pages}`);
  if (!PAD_WHERE.includes(padWhere)) throw new Error(`--pad must be one of ${PAD_WHERE.join(", ")}, got: ${padWhere}`);
  if (storedBytes !== null && (!Number.isInteger(storedBytes) || storedBytes < 1)) {
    throw new Error(`--stored-bytes must be a positive integer, got: ${storedBytes}`);
  }
  assertNotCommittedStub(outDir);
  const itemPad = padWhere === "history" || padWhere === "worklog"; // the padding is a stored history/worklog, not the issue or /field
  const stubDir = path.join(path.resolve(outDir), "jira-stub");
  const mappingsDir = path.join(stubDir, "mappings");
  const filesDir = path.join(stubDir, "__files");
  if (overlay && !existsSync(mappingsDir)) throw new Error(`--overlay needs the normal stub in ${stubDir} (run generate.mjs --out first)`);
  mkdirSync(mappingsDir, { recursive: true });
  mkdirSync(filesDir, { recursive: true });

  // Layer over the normal stub: its first issue and field list are the templates, its superseded mappings are removed.
  let issueTemplate = MINIMAL_ISSUE;
  let fieldList = MINIMAL_FIELDS;
  if (overlay) {
    const firstPage = JSON.parse(readFileSync(path.join(filesDir, "search-jql-started-page-1.json"), "utf8"));
    issueTemplate = firstPage.issues[0];
    fieldList = JSON.parse(readFileSync(path.join(filesDir, "field.json"), "utf8"));
    for (const file of readdirSync(mappingsDir)) {
      if (file.startsWith(HOSTILE_PREFIX) || !file.endsWith(".json")) continue;
      if (isSuperseded(JSON.parse(readFileSync(path.join(mappingsDir, file), "utf8")), !itemPad)) rmSync(path.join(mappingsDir, file));
    }
  }
  // Re-running replaces the previous hostile set (a smaller --pages must not leave stale pages behind).
  for (const dir of [mappingsDir, filesDir]) {
    for (const file of readdirSync(dir)) if (file.startsWith(HOSTILE_PREFIX)) rmSync(path.join(dir, file));
  }

  const written = [];
  const emit = (mappingFile, m, bodyFile, doc) => {
    const text = JSON.stringify(doc); // minified, as Jira sends it
    writeFileSync(path.join(filesDir, bodyFile), text);
    writeFileSync(path.join(mappingsDir, mappingFile), `${JSON.stringify(m, null, 2)}\n`);
    written.push(verifyBody(bodyFile, text, maxResponseBytes));
  };

  // The search pages: dense padding in the issue (or the page) by default, a PLAIN hostile issue when the padding is a history/worklog.
  const buildPage = (n) => searchPage(issueTemplate, 1, 2, itemPad ? "none" : padWhere, n); // the densest page shape: a token, an id, a key
  const pageN = itemPad ? 0 : storedBytes ? paddingCountForBytes(buildPage, storedBytes) : paddingCountFor(buildPage, maxResponseBytes);
  for (let page = 1; page <= pages; page++) {
    const bodyFile = `${HOSTILE_PREFIX}search-jql-page-${page}.json`;
    const doc = searchPage(issueTemplate, page, pages, itemPad ? "none" : padWhere, pageN);
    emit(`${HOSTILE_PREFIX}search-jql-page-${page}.json`, searchMapping(page), bodyFile, doc);
  }

  if (padWhere === "history") {
    // ONE bulkfetch answer for the hostile issues: as many padded histories as fit under the byte cap AND the node ceiling (the rest of the
    // hostile issues get none; a batch that mixes in a normal issue gets no normal history either - a measurement stub, not a whole SYNC).
    const target = storedBytes ?? DEFAULT_STORED_BYTES.history;
    const n = paddingCountForBytes((count) => hostileHistory(1, count), target);
    const itemText = JSON.stringify(hostileHistory(1, n));
    const itemTokens = scanTokens(itemText).tokens;
    const fit = Math.min(
      pages,
      Math.floor((maxResponseBytes - 1024) / (Buffer.byteLength(itemText, "utf8") + 64)),
      Math.floor((nodeCeiling(maxResponseBytes) - TOKEN_MARGIN) / (itemTokens + 8)),
    );
    if (fit < 1) throw new Error(`one ${Buffer.byteLength(itemText, "utf8")}-byte history does not fit a ${maxResponseBytes}-byte answer`);
    const issueChangeLogs = Array.from({ length: fit }, (_, i) => ({
      issueId: String(HOSTILE_ID_BASE + 1 + i),
      changeHistories: [hostileHistory(1 + i, n)],
    }));
    const bodyFile = `${HOSTILE_PREFIX}changelog-bulkfetch.json`;
    emit(`${HOSTILE_PREFIX}changelog-bulkfetch.json`, bulkfetchMapping(bodyFile), bodyFile, { issueChangeLogs });
  } else if (padWhere === "worklog") {
    const target = storedBytes ?? DEFAULT_STORED_BYTES.worklog;
    const n = paddingCountForBytes((count) => hostileWorklog(1, count), target);
    for (let page = 1; page <= pages; page++) {
      const issueId = HOSTILE_ID_BASE + page;
      const bodyFile = `${HOSTILE_PREFIX}issue-${issueId}-worklog.json`;
      const doc = { startAt: 0, maxResults: 100, total: 1, worklogs: [hostileWorklog(page, n)] };
      emit(`${HOSTILE_PREFIX}issue-${issueId}-worklog.json`, mapping(`/issue/${issueId}/worklog`, null, bodyFile), bodyFile, doc);
    }
  } else {
    // GET /field: the (normal or minimal) list plus one entity carrying the padding - every entity needs an `id`, and an entity
    // per padding object would be 350k database rows, so the padding is one entity's key, as in the page above.
    const buildFields = (n) => [...fieldList, { id: "customfield_99999", name: "Hostile padding", custom: true, a: padding(n) }];
    const fieldN = storedBytes ? paddingCountForBytes(buildFields, storedBytes) : paddingCountFor(buildFields, maxResponseBytes);
    emit(`${HOSTILE_PREFIX}field.json`, mapping("/field", null, `${HOSTILE_PREFIX}field.json`), `${HOSTILE_PREFIX}field.json`, buildFields(fieldN));
  }
  return { stubDir, written };
}

/** Verifies the hostile bodies already in `outDir` (the --check mode); throws on the first breach. */
export function checkDir({ outDir, maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES }) {
  const filesDir = path.join(path.resolve(outDir), "jira-stub", "__files");
  const names = existsSync(filesDir) ? readdirSync(filesDir).filter((f) => f.startsWith(HOSTILE_PREFIX)).sort() : [];
  if (names.length === 0) throw new Error(`no ${HOSTILE_PREFIX}* bodies in ${filesDir}`);
  return names.map((name) => verifyBody(name, readFileSync(path.join(filesDir, name), "utf8"), maxResponseBytes));
}

function parseArgs(argv) {
  const opts = {
    outDir: null,
    pages: 20,
    padWhere: "issue",
    overlay: false,
    check: false,
    maxResponseBytes: DEFAULT_MAX_RESPONSE_BYTES,
    storedBytes: null,
  };
  const value = (a, i, flag) => (a.startsWith(`${flag}=`) ? [a.slice(flag.length + 1), i] : [argv[i + 1], i + 1]);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    let v;
    if (a === "--overlay") opts.overlay = true;
    else if (a === "--check") opts.check = true;
    else if (a === "--out" || a.startsWith("--out=")) ([v, i] = value(a, i, "--out"), (opts.outDir = v));
    else if (a === "--pages" || a.startsWith("--pages=")) ([v, i] = value(a, i, "--pages"), (opts.pages = Number(v)));
    else if (a === "--pad" || a.startsWith("--pad=")) ([v, i] = value(a, i, "--pad"), (opts.padWhere = v));
    else if (a === "--stored-bytes" || a.startsWith("--stored-bytes=")) ([v, i] = value(a, i, "--stored-bytes"), (opts.storedBytes = Number(v)));
    else if (a === "--max-response-bytes" || a.startsWith("--max-response-bytes="))
      ([v, i] = value(a, i, "--max-response-bytes"), (opts.maxResponseBytes = Number(v)));
    else throw new Error(`Unknown argument: ${a}`);
  }
  if (!opts.outDir) throw new Error("--out DIR is required (this never writes the committed sample-data/jira-stub)");
  if (!Number.isInteger(opts.maxResponseBytes) || opts.maxResponseBytes < 1024) throw new Error(`--max-response-bytes must be an integer >= 1024`);
  return opts;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const rows = opts.check ? checkDir(opts) : generate(opts).written;
  for (const r of rows) console.log(`${r.name}: ${r.tokens} tokens (ceiling ${r.ceiling}), ${r.bytes} bytes, depth ${r.maxDepth}: ok`);
  console.log(`${rows.length} hostile bodies ${opts.check ? "verified" : "written and verified"}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
