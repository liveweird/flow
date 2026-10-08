#!/usr/bin/env node
// Deterministic synthetic Jira Cloud dataset + WireMock stub generator.
//
// Zero dependencies (Node >=24 stdlib only). Every random draw comes from a seeded PRNG so two
// runs produce byte-identical output — see sample-data/README.md for the full contract.
//
// Usage: node sample-data/jira/generate.mjs [--scale N] [--out DIR]
//
//   --scale N   multiplies every project's issue/epic counts by N (default 1 — the committed
//               dataset's own shape; N=20 is the phase-3 perf run's ~24k-issue dataset). Only
//               affects issue volume, never ids/timelines/chunking/day2 at the default N=1.
//   --out DIR   writes to DIR/jira-stub and DIR/jira/expected.json instead of the committed
//               sample-data/ location (used by the perf run so it never touches the repo).
//
// With NEITHER flag, output goes to the default location below and is BYTE-IDENTICAL across two
// runs (see sample-data/README.md's determinism check) — every --scale/--out run still draws from
// the same deterministic PRNGs, but is not itself asserted byte-identical against anything.
//
// Writes (default location; see --out above):
//   sample-data/jira/expected.json              - counts and facts the server tests assert against
//   sample-data/jira-stub/mappings/*.json        - WireMock request matchers
//   sample-data/jira-stub/__files/*.json         - WireMock response bodies (bodyFileName targets)

import { mkdirSync, rmSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..", "..");

function parseArgs(argv) {
  let scale = 1;
  let out = null;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--scale") scale = Number(argv[++i]);
    else if (a.startsWith("--scale=")) scale = Number(a.slice("--scale=".length));
    else if (a === "--out") out = argv[++i];
    else if (a.startsWith("--out=")) out = a.slice("--out=".length);
    else throw new Error(`Unknown argument: ${a}`);
  }
  if (!Number.isFinite(scale) || scale <= 0) throw new Error(`--scale must be a positive number, got: ${scale}`);
  return { scale, out };
}
const { scale: SCALE, out: OUT_DIR } = parseArgs(process.argv.slice(2));

const OUTPUT_ROOT = OUT_DIR ? path.resolve(OUT_DIR) : path.join(ROOT, "sample-data");
const STUB_DIR = path.join(OUTPUT_ROOT, "jira-stub");
const MAPPINGS_DIR = path.join(STUB_DIR, "mappings");
const FILES_DIR = path.join(STUB_DIR, "__files");
const EXPECTED_PATH = path.join(OUTPUT_ROOT, "jira", "expected.json");

// ---------------------------------------------------------------------------------------------
// Seeded PRNG (mulberry32) — deterministic across runs/platforms/Node versions.
// ---------------------------------------------------------------------------------------------
const SEED = 0x466c6f77; // "Flow" as hex-ish, arbitrary fixed constant
function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function makeRng(seed) {
  const rawRandom = mulberry32(seed);
  return {
    float() {
      return rawRandom();
    },
    int(min, max) {
      // inclusive both ends
      return min + Math.floor(rawRandom() * (max - min + 1));
    },
    bool(p) {
      return rawRandom() < p;
    },
    pick(arr) {
      return arr[this.int(0, arr.length - 1)];
    },
    pickWeighted(pairs) {
      // pairs: [[value, weight], ...]
      const total = pairs.reduce((s, [, w]) => s + w, 0);
      let r = rawRandom() * total;
      for (const [value, weight] of pairs) {
        if (r < weight) return value;
        r -= weight;
      }
      return pairs[pairs.length - 1][0];
    },
    shuffle(arr) {
      const a = arr.slice();
      for (let i = a.length - 1; i > 0; i--) {
        const j = this.int(0, i);
        [a[i], a[j]] = [a[j], a[i]];
      }
      return a;
    },
  };
}
const rng = makeRng(SEED);
// A SEPARATE, independently-seeded PRNG stream for every phase-3 (v0.3.0) addition (epic SP/dates,
// parent moves, story-point changes, work category, worklog created/updated skew, sprint
// completeDate skew, the future-sprint backlog placement, the deterministic team roster). Every
// phase-3 draw MUST come from `rng2`, never `rng` — `rng` is the phase-2 dataset's sequence, and any
// new draw threaded into it would shift every later phase-2 value (ids, timelines, the omitted
// bulkfetch chunk, the day2 scenario — see .claude/docs/persistence.md's "hard constraint" note and
// the phase-3 plan's §0 A2). `rng2`'s own draws run in a fixed, code-order sequence (never
// interleaved with input from elsewhere), so they stay just as deterministic as `rng`'s.
const SEED2 = 0x4d657472; // "Metr" as hex-ish, a second arbitrary fixed constant, distinct from SEED
const rng2 = makeRng(SEED2);

// ---------------------------------------------------------------------------------------------
// Fixed reference point. NEVER Date.now() — determinism requires a frozen "now".
// ---------------------------------------------------------------------------------------------
const REFERENCE_ISO = "2026-09-01T00:00:00.000Z";
const REFERENCE_MS = Date.parse(REFERENCE_ISO);
const DAY_MS = 86_400_000;
const BACKFILL_DAYS = 365; // "12 months before the reference date"
const BACKFILL_START_MS = REFERENCE_MS - BACKFILL_DAYS * DAY_MS;
const DAY2_NOW_MS = REFERENCE_MS + DAY_MS; // "day2" = one day of wall-clock time passing
const DAY2_NOW_ISO = new Date(DAY2_NOW_MS).toISOString();

const CLOUD_ID = "b3f1a2c4-5d6e-4f7a-8b9c-0d1e2f3a4b5c";
const SITE_HOST = "flow-sample.atlassian.net";
const GATEWAY_PREFIX = `/ex/jira/${CLOUD_ID}`;
const API = `${GATEWAY_PREFIX}/rest/api/3`;
const AGILE = `${GATEWAY_PREFIX}/rest/agile/1.0`;

function iso(ms) {
  return new Date(ms).toISOString();
}

function isoDate(ms) {
  return iso(ms).slice(0, 10);
}

// Jira Cloud's REST API v3 (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`, i.e. a colonless `+0000`/`-0500` offset,
// NEVER a colon and NEVER a bare `Z`) — verified against a real tenant, see
// `sample-data/README.md`'s "Timestamp formats" section and
// `server/src/main/kotlin/jira/JiraTime.kt`'s kdoc: `java.time.Instant.parse` rejects this shape
// outright, which is exactly why the stub must emit it rather than the more convenient `iso()`/`Z`
// form the Agile API actually returns. `pad2` mirrors the two-digit zero-padding `toISOString()`
// already does for every other field; only the offset needs building by hand.
function pad2(n) {
  return String(n).padStart(2, "0");
}

function restIso(ms) {
  const d = new Date(ms);
  const y = d.getUTCFullYear();
  const mo = pad2(d.getUTCMonth() + 1);
  const day = pad2(d.getUTCDate());
  const h = pad2(d.getUTCHours());
  const mi = pad2(d.getUTCMinutes());
  const s = pad2(d.getUTCSeconds());
  const ms3 = String(d.getUTCMilliseconds()).padStart(3, "0");
  return `${y}-${mo}-${day}T${h}:${mi}:${s}.${ms3}+0000`;
}

// ---------------------------------------------------------------------------------------------
// Reference catalogs
// ---------------------------------------------------------------------------------------------
const STATUS_CATEGORIES = [
  { id: 1, key: "undefined", name: "No Category", colorName: "medium-gray" },
  { id: 2, key: "new", name: "To Do", colorName: "blue-gray" },
  { id: 3, key: "done", name: "Done", colorName: "green" },
  { id: 4, key: "indeterminate", name: "In Progress", colorName: "yellow" },
];

const STATUS = {
  TODO: { id: "1", name: "To Do", categoryKey: "new" },
  IN_PROGRESS: { id: "3", name: "In Progress", categoryKey: "indeterminate" },
  IN_REVIEW: { id: "10001", name: "In Review", categoryKey: "indeterminate" },
  DONE: { id: "10002", name: "Done", categoryKey: "done" },
  BLOCKED: { id: "10003", name: "Blocked", categoryKey: "indeterminate" },
  WAITING: { id: "10004", name: "Waiting", categoryKey: "indeterminate" },
};
const ALL_STATUSES = Object.values(STATUS);

function statusJson(s) {
  const cat = STATUS_CATEGORIES.find((c) => c.key === s.categoryKey);
  return { id: s.id, name: s.name, statusCategory: { id: cat.id, key: cat.key, name: cat.name, colorName: cat.colorName } };
}

// `GET /statuses/search` returns the category as a plain string enum (real tenant: "TODO"/"IN_PROGRESS"/"DONE"),
// unlike an issue's `fields.status` / `/status` / project statuses, which carry the object form (`statusJson`).
const STATUS_SEARCH_CATEGORY = { new: "TODO", indeterminate: "IN_PROGRESS", done: "DONE" };
function statusSearchJson(s) {
  return { id: s.id, name: s.name, scope: { type: "GLOBAL" }, description: "", statusCategory: STATUS_SEARCH_CATEGORY[s.categoryKey] };
}

const ISSUE_TYPE = {
  EPIC: { id: "10000", name: "Epic", subtask: false, hierarchyLevel: 1 },
  STORY: { id: "10001", name: "Story", subtask: false, hierarchyLevel: 0 },
  TASK: { id: "10002", name: "Task", subtask: false, hierarchyLevel: 0 },
  BUG: { id: "10003", name: "Bug", subtask: false, hierarchyLevel: 0 },
  SUBTASK: { id: "10004", name: "Sub-task", subtask: true, hierarchyLevel: -1 },
};

const PRIORITIES = [
  { id: "1", name: "Highest" },
  { id: "2", name: "High" },
  { id: "3", name: "Medium" },
  { id: "4", name: "Low" },
  { id: "5", name: "Lowest" },
];

const RESOLUTIONS = [
  { id: "10000", name: "Done" },
  { id: "10001", name: "Won't Do" },
  { id: "10002", name: "Duplicate" },
  { id: "10003", name: "Cannot Reproduce" },
];

const ISSUE_LINK_TYPES = [
  { id: "10000", name: "Blocks", inward: "is blocked by", outward: "blocks" },
  { id: "10001", name: "Relates", inward: "relates to", outward: "relates to" },
  { id: "10002", name: "Duplicate", inward: "is duplicated by", outward: "duplicates" },
];

const LABEL_POOL = ["tech-debt", "customer-reported", "perf", "security", "flaky-test", "needs-design", "regression", "quick-win"];

const TEAMS = [
  { id: "8e6f8c9a-1a2b-4c3d-9e0f-111111111111", name: "Payments" },
  { id: "8e6f8c9a-1a2b-4c3d-9e0f-222222222222", name: "Platform Core" },
  { id: "8e6f8c9a-1a2b-4c3d-9e0f-333333333333", name: "Growth" },
  { id: "8e6f8c9a-1a2b-4c3d-9e0f-444444444444", name: "Reliability" },
];

const USERS = [];
for (let i = 0; i < 30; i++) {
  const n = i + 1;
  USERS.push({
    accountId: `5f8a1b2c3d4e5f6a7b8c9d${String(n).padStart(2, "0")}`,
    displayName: `Sample User ${n}`,
    emailAddress: `user${n}@flow-sample.example`,
    active: true,
    accountType: "atlassian",
  });
}

// ---------------------------------------------------------------------------------------------
// Custom fields (schema.custom values matter — the server discovers these by schema, not id).
// ---------------------------------------------------------------------------------------------
const CF = {
  TEAM: "customfield_10001",
  STORY_POINTS: "customfield_10016",
  RANK: "customfield_10019",
  SPRINT: "customfield_10020",
  FLAGGED: "customfield_10021",
  // Phase 3 (v0.3.0 M1 commit 1) additions — epic start date + a work-category select, per the
  // phase-3 plan's §10 commit-1 brief.
  EPIC_START: "customfield_10015",
  WORK_CATEGORY: "customfield_10030",
};

// Work category select-field options (customfield_10030) — D8's fallback (task's own value, else
// its epic's) needs real option data to exercise.
const WORK_CATEGORY_OPTIONS = [
  { id: "10100", value: "Product Development" },
  { id: "10101", value: "Maintenance" },
  { id: "10102", value: "Cost of Poor Quality" },
];

const FIELDS = [
  { id: "summary", key: "summary", name: "Summary", custom: false, schema: { type: "string", system: "summary" } },
  { id: "status", key: "status", name: "Status", custom: false, schema: { type: "status", system: "status" } },
  { id: "assignee", key: "assignee", name: "Assignee", custom: false, schema: { type: "user", system: "assignee" } },
  { id: "reporter", key: "reporter", name: "Reporter", custom: false, schema: { type: "user", system: "reporter" } },
  { id: "priority", key: "priority", name: "Priority", custom: false, schema: { type: "priority", system: "priority" } },
  { id: "resolution", key: "resolution", name: "Resolution", custom: false, schema: { type: "resolution", system: "resolution" } },
  { id: "labels", key: "labels", name: "Labels", custom: false, schema: { type: "array", items: "string", system: "labels" } },
  { id: "components", key: "components", name: "Components", custom: false, schema: { type: "array", items: "component", system: "components" } },
  { id: "issuetype", key: "issuetype", name: "Issue Type", custom: false, schema: { type: "issuetype", system: "issuetype" } },
  { id: "project", key: "project", name: "Project", custom: false, schema: { type: "project", system: "project" } },
  { id: "parent", key: "parent", name: "Parent", custom: false, schema: { type: "issuelink", system: "parent" } },
  { id: "duedate", key: "duedate", name: "Due date", custom: false, schema: { type: "date", system: "duedate" } },
  {
    id: CF.TEAM,
    key: CF.TEAM,
    name: "Team",
    custom: true,
    schema: { type: "team", custom: "com.atlassian.jira.plugin.system.customfieldtypes:atlassian-team", customId: 10001 },
  },
  {
    id: CF.STORY_POINTS,
    key: CF.STORY_POINTS,
    name: "Story point estimate",
    custom: true,
    schema: { type: "number", custom: "com.atlassian.jira.plugin.system.customfieldtypes:float", customId: 10016 },
  },
  {
    id: CF.RANK,
    key: CF.RANK,
    name: "Rank",
    custom: true,
    schema: { type: "any", custom: "com.pyxis.greenhopper.jira:gh-lexo-rank", customId: 10019 },
  },
  {
    id: CF.SPRINT,
    key: CF.SPRINT,
    name: "Sprint",
    custom: true,
    schema: { type: "array", items: "json", custom: "com.pyxis.greenhopper.jira:gh-sprint", customId: 10020 },
  },
  {
    id: CF.FLAGGED,
    key: CF.FLAGGED,
    name: "Flagged",
    custom: true,
    schema: { type: "array", items: "option", custom: "com.atlassian.jira.plugin.system.customfieldtypes:multicheckboxes", customId: 10021 },
  },
  {
    id: CF.EPIC_START,
    key: CF.EPIC_START,
    name: "Start date",
    custom: true,
    schema: { type: "date", custom: "com.atlassian.jira.plugin.system.customfieldtypes:datepicker", customId: 10015 },
  },
  {
    id: CF.WORK_CATEGORY,
    key: CF.WORK_CATEGORY,
    name: "Work category",
    custom: true,
    schema: { type: "option", custom: "com.atlassian.jira.plugin.system.customfieldtypes:select", customId: 10030 },
  },
];

// ---------------------------------------------------------------------------------------------
// Projects. 3 Scrum + 1 Kanban IN scope, 1 Kanban-ish project OUT of scope.
// ---------------------------------------------------------------------------------------------
const PROJECTS = [
  {
    id: "10000",
    key: "FLO",
    name: "Flow Core",
    boardType: "scrum",
    inScope: true,
    workflow: [STATUS.TODO, STATUS.IN_PROGRESS, STATUS.IN_REVIEW, STATUS.DONE],
    components: ["API", "Web", "Worker"],
    nonEpicCount: 396,
    epicCount: 4,
    boardOmitsStatus: null,
  },
  {
    id: "10001",
    key: "PLT",
    name: "Platform",
    boardType: "scrum",
    inScope: true,
    workflow: [STATUS.TODO, STATUS.IN_PROGRESS, STATUS.BLOCKED, STATUS.IN_REVIEW, STATUS.DONE],
    components: ["Infra", "CI/CD", "Observability"],
    nonEpicCount: 346,
    epicCount: 4,
    boardOmitsStatus: null,
  },
  {
    id: "10002",
    key: "GTM",
    name: "Go To Market",
    boardType: "scrum",
    inScope: true,
    workflow: [STATUS.TODO, STATUS.IN_PROGRESS, STATUS.IN_REVIEW, STATUS.WAITING, STATUS.DONE],
    components: ["Onboarding", "Billing", "Reporting"],
    nonEpicCount: 296,
    epicCount: 4,
    // Deliberately not mapped on the board configuration, to exercise the
    // "unmapped statuses" branch of the data profile.
    boardOmitsStatus: STATUS.WAITING,
  },
  {
    id: "10003",
    key: "OPS",
    name: "Operations",
    boardType: "kanban",
    inScope: true,
    workflow: [STATUS.TODO, STATUS.IN_PROGRESS, STATUS.BLOCKED, STATUS.DONE],
    components: ["Support", "Tooling"],
    nonEpicCount: 148,
    epicCount: 2,
    boardOmitsStatus: null,
  },
  {
    id: "10004",
    key: "SEC",
    name: "Security",
    boardType: "kanban",
    inScope: false,
    workflow: [STATUS.TODO, STATUS.IN_PROGRESS, STATUS.DONE],
    components: ["AppSec", "Compliance"],
    nonEpicCount: 79,
    epicCount: 1,
    boardOmitsStatus: null,
  },
];
// --scale N (default 1 — a no-op, Math.round(x*1) === x, so the committed dataset's shape is
// untouched): multiplies issue volume only, never sprint/user/team counts — the perf run's own
// "≈24k issues" target (phase-3 plan §9/§19) comes from this alone.
if (SCALE !== 1) {
  for (const project of PROJECTS) {
    project.nonEpicCount = Math.round(project.nonEpicCount * SCALE);
    project.epicCount = Math.max(1, Math.round(project.epicCount * SCALE));
  }
}
const IN_SCOPE_PROJECTS = PROJECTS.filter((p) => p.inScope);
const CONNECTION_PROJECT_KEYS = IN_SCOPE_PROJECTS.map((p) => p.key);
const SCRUM_PROJECTS = PROJECTS.filter((p) => p.boardType === "scrum");

// ---------------------------------------------------------------------------------------------
// Sprints — 2-week sprints spanning the backfill window plus a "future" one, per Scrum project.
// ---------------------------------------------------------------------------------------------
let nextSprintId = 3000;
let nextBoardId = 1;
const BOARDS = []; // {id, name, type, project}
const SPRINTS_BY_PROJECT = new Map(); // projectKey -> [sprint...]

for (const project of PROJECTS) {
  const board = {
    id: nextBoardId++,
    name: `${project.name} board`,
    type: project.boardType,
    project,
  };
  BOARDS.push(board);

  if (project.boardType !== "scrum") continue;

  const sprints = [];
  let start = BACKFILL_START_MS;
  let index = 1;
  let activeAssigned = false;
  while (start < REFERENCE_MS + 14 * DAY_MS) {
    const end = start + 14 * DAY_MS;
    let state;
    if (end <= REFERENCE_MS) state = "closed";
    else if (start <= REFERENCE_MS && REFERENCE_MS < end) {
      state = "active";
      activeAssigned = true;
    } else state = "future";
    sprints.push({
      id: nextSprintId++,
      name: `${project.key} Sprint ${index}`,
      state,
      startMs: start,
      endMs: end,
      boardId: board.id,
      goal: `Sprint ${index} goal for ${project.name}`,
    });
    if (state === "future" && activeAssigned) break; // stop after the one future sprint
    start = end;
    index++;
  }
  SPRINTS_BY_PROJECT.set(project.key, sprints);
}

function sprintForTime(project, ms) {
  const sprints = SPRINTS_BY_PROJECT.get(project.key);
  if (!sprints) return null;
  for (const s of sprints) {
    if (ms >= s.startMs && ms < s.endMs) return s;
  }
  return sprints[sprints.length - 1];
}
function nextSprintAfter(project, sprint) {
  const sprints = SPRINTS_BY_PROJECT.get(project.key);
  const idx = sprints.findIndex((s) => s.id === sprint.id);
  return idx >= 0 && idx + 1 < sprints.length ? sprints[idx + 1] : null;
}

// ---------------------------------------------------------------------------------------------
// Board configuration (columns -> statuses)
// ---------------------------------------------------------------------------------------------
function boardColumns(project) {
  const has = (s) => project.workflow.includes(s) && s !== project.boardOmitsStatus;
  const cols = [];
  cols.push({ name: "To Do", statuses: [STATUS.TODO] });
  const middle = [];
  if (has(STATUS.IN_PROGRESS)) middle.push(STATUS.IN_PROGRESS);
  if (has(STATUS.BLOCKED)) middle.push(STATUS.BLOCKED);
  if (middle.length) cols.push({ name: "In Progress", statuses: middle });
  if (has(STATUS.IN_REVIEW)) cols.push({ name: "In Review", statuses: [STATUS.IN_REVIEW] });
  cols.push({ name: "Done", statuses: [STATUS.DONE] });
  return cols;
}

// ---------------------------------------------------------------------------------------------
// Issue generation
// ---------------------------------------------------------------------------------------------
function randomCreatedMs() {
  return rng.int(BACKFILL_START_MS, REFERENCE_MS - DAY_MS);
}

const changeHistories = new Map(); // issueId -> [history...]
let nextHistoryId = 500000;
let nextWorklogId = 700000;
const worklogsByIssue = new Map(); // issueId -> [worklog...]

function makeStatusItem(from, to) {
  return { field: "status", fieldId: "status", from: from.id, fromString: from.name, to: to.id, toString: to.name };
}

function pushHistory(issueId, atMs, items) {
  const list = changeHistories.get(issueId) ?? [];
  list.push({ id: String(nextHistoryId++), created: restIso(atMs), author: pickAuthor(), items });
  changeHistories.set(issueId, list);
}

function pickAuthor() {
  return { accountId: rng.pick(USERS).accountId };
}

// Simulates one issue's lifecycle. Returns the derived current-state plus the change events
// (both are written to changelog + the issue's `fields`, from the same source of truth so they
// can never disagree).
function simulateLifecycle(project, createdMs, opts) {
  const wf = project.workflow;
  const doneStatus = wf[wf.length - 1];
  const ageDays = (REFERENCE_MS - createdMs) / DAY_MS;

  const events = []; // {atMs, from, to}
  let current = wf[0];
  let t = createdMs;

  const neverStarted = ageDays < 4 && rng.bool(0.35);
  if (!neverStarted) {
    t = Math.min(t + rng.int(1, 5) * DAY_MS, REFERENCE_MS);
    events.push({ atMs: t, from: current, to: wf[1] });
    current = wf[1];

    const targetArcDays = opts.carryoverEligible ? rng.int(15, 40) : rng.int(2, 13);
    const deadlineMs = Math.min(createdMs + targetArcDays * DAY_MS, REFERENCE_MS);

    let idx = 1;
    let hops = 0;
    const maxHops = wf.length;
    while (t < deadlineMs && hops < maxHops && rng.bool(0.55)) {
      hops++;
      if (idx < wf.length - 2 && rng.bool(0.45)) {
        idx++;
        t = Math.min(t + rng.int(1, 4) * DAY_MS, REFERENCE_MS);
        events.push({ atMs: t, from: current, to: wf[idx] });
        current = wf[idx];
      } else {
        break;
      }
    }

    const canFinish = deadlineMs < REFERENCE_MS;
    if (canFinish && rng.bool(0.78)) {
      t = Math.max(t, deadlineMs);
      events.push({ atMs: t, from: current, to: doneStatus });
      current = doneStatus;

      if (opts.reopenEligible && t < REFERENCE_MS - 3 * DAY_MS) {
        t = Math.min(t + rng.int(1, 10) * DAY_MS, REFERENCE_MS);
        events.push({ atMs: t, from: doneStatus, to: wf[1] });
        current = wf[1];
        if (t < REFERENCE_MS - 2 * DAY_MS && rng.bool(0.6)) {
          t = Math.min(t + rng.int(1, 8) * DAY_MS, REFERENCE_MS);
          events.push({ atMs: t, from: current, to: doneStatus });
          current = doneStatus;
        }
      }
    }
  }

  const resolvedAtMs = current.categoryKey === "done" ? t : null;
  return { events, current, resolvedAtMs, lastEventAtMs: t };
}

// ---------------------------------------------------------------------------------------------
// Build all issues.
// ---------------------------------------------------------------------------------------------
const bareIssues = []; // pre-id-assignment: {project, type, createdMs, parentRef}

for (const project of PROJECTS) {
  const epics = [];
  for (let i = 0; i < project.epicCount; i++) {
    const createdMs = BACKFILL_START_MS + rng.int(0, 45) * DAY_MS;
    const bare = { project, type: ISSUE_TYPE.EPIC, createdMs, parentRef: null };
    epics.push(bare);
    bareIssues.push(bare);
  }

  const parentableCount = Math.round(project.nonEpicCount * 0.85);
  const parentable = [];
  for (let i = 0; i < parentableCount; i++) {
    const createdMs = randomCreatedMs();
    const type = rng.pickWeighted([
      [ISSUE_TYPE.STORY, 40],
      [ISSUE_TYPE.TASK, 25],
      [ISSUE_TYPE.BUG, 20],
    ]);
    const parentRef = type !== ISSUE_TYPE.BUG && epics.length && rng.bool(0.35) ? rng.pick(epics) : null;
    const bare = { project, type, createdMs, parentRef };
    parentable.push(bare);
    bareIssues.push(bare);
  }

  const subtaskCount = project.nonEpicCount - parentable.length;
  for (let i = 0; i < subtaskCount; i++) {
    const parent = rng.pick(parentable);
    const createdMs = Math.min(parent.createdMs + rng.int(0, 30) * DAY_MS, REFERENCE_MS - DAY_MS);
    bareIssues.push({ project, type: ISSUE_TYPE.SUBTASK, createdMs, parentRef: parent });
  }
}

// Global chronological order -> id assignment (mirrors real Jira's instance-wide sequential id).
bareIssues.sort((a, b) => a.createdMs - b.createdMs || a.project.key.localeCompare(b.project.key));

let nextIssueId = 30000;
const projectKeyCounters = new Map(PROJECTS.map((p) => [p.key, 0]));
const issues = []; // fully-formed issue records, indexed by id later
const issueById = new Map();
const parentBareToIssue = new Map(); // bare object identity -> issue (for resolving parent refs)

for (const bare of bareIssues) {
  const keyNum = projectKeyCounters.get(bare.project.key) + 1;
  projectKeyCounters.set(bare.project.key, keyNum);
  const issue = {
    id: String(nextIssueId++),
    key: `${bare.project.key}-${keyNum}`,
    project: bare.project,
    type: bare.type,
    createdMs: bare.createdMs,
    parentRef: bare.parentRef,
  };
  issues.push(issue);
  issueById.set(issue.id, issue);
  parentBareToIssue.set(bare, issue);
}
for (const issue of issues) {
  if (issue.parentRef) issue.parent = parentBareToIssue.get(issue.parentRef);
}

// ---------------------------------------------------------------------------------------------
// Simulate each issue: status timeline, assignee, sprint, flagged, rank, estimates, worklogs.
// ---------------------------------------------------------------------------------------------
let reopenCount = 0;
let carryoverCount = 0;
let flaggedCount = 0;
let worklogIssueCount = 0;
let worklogTotalCount = 0;
let storyPointsCount = 0;
let originalEstimateCount = 0;

for (const issue of issues) {
  const carryoverEligible = issue.project.boardType === "scrum" && rng.bool(0.22);
  const reopenEligible = rng.bool(0.055);
  const { events, current, resolvedAtMs, lastEventAtMs } = simulateLifecycle(issue.project, issue.createdMs, {
    carryoverEligible,
    reopenEligible,
  });
  issue.statusEvents = events;
  issue.status = current;
  issue.resolvedAtMs = resolvedAtMs;
  issue.updatedMs = events.length ? events[events.length - 1].atMs : issue.createdMs;
  if (events.some((e) => e.from.categoryKey === "done" && e.to.categoryKey !== "done")) {
    reopenCount++;
    issue.reopened = true;
  }

  // Assignee timeline: 0-2 reassignments between creation and the last event.
  issue.reporter = rng.pick(USERS);
  const assigneeEvents = [];
  let assignee = rng.bool(0.85) ? rng.pick(USERS) : null;
  const reassignCount = rng.int(0, 2);
  const windowEnd = Math.max(lastEventAtMs, issue.createdMs);
  for (let i = 0; i < reassignCount; i++) {
    const atMs = issue.createdMs + Math.floor(((i + 1) / (reassignCount + 1)) * (windowEnd - issue.createdMs + 1));
    const next = rng.bool(0.1) ? null : rng.pick(USERS);
    assigneeEvents.push({ atMs, from: assignee, to: next });
    assignee = next;
    issue.updatedMs = Math.max(issue.updatedMs, atMs);
  }
  issue.assignee = assignee;
  issue.assigneeEvents = assigneeEvents;

  // Flagged: ~5%, toggled on partway through, sometimes toggled back off.
  issue.flagged = false;
  issue.flaggedEvents = [];
  if (rng.bool(0.05)) {
    const atMs = issue.createdMs + rng.int(1, Math.max(1, Math.floor((windowEnd - issue.createdMs) / DAY_MS) || 1)) * DAY_MS;
    const cappedAt = Math.min(atMs, REFERENCE_MS);
    issue.flaggedEvents.push({ atMs: cappedAt, from: false, to: true });
    issue.flagged = true;
    if (rng.bool(0.3) && cappedAt < REFERENCE_MS - DAY_MS) {
      const offAt = Math.min(cappedAt + rng.int(1, 5) * DAY_MS, REFERENCE_MS);
      issue.flaggedEvents.push({ atMs: offAt, from: true, to: false });
      issue.flagged = false;
    }
    issue.updatedMs = Math.max(issue.updatedMs, issue.flaggedEvents[issue.flaggedEvents.length - 1].atMs);
  }
  if (issue.flagged) flaggedCount++;

  // Rank: a LexoRank-ish string; the current value only, changes are logged in field_changes.
  issue.rank = `0|i${String(rng.int(10000, 99999)).padStart(5, "0")}:`;
  issue.rankEvents = [];
  if (rng.bool(0.15)) {
    const fromRank = issue.rank;
    issue.rank = `0|i${String(rng.int(10000, 99999)).padStart(5, "0")}:`;
    issue.rankEvents.push({ atMs: Math.min(issue.createdMs + rng.int(1, 20) * DAY_MS, REFERENCE_MS), from: fromRank, to: issue.rank });
  }

  // Sprint membership (Scrum projects only), with carry-over when work outlives the sprint.
  issue.sprintIds = [];
  issue.sprintEvents = [];
  if (issue.project.boardType === "scrum" && issue.type !== ISSUE_TYPE.EPIC) {
    const sprint = sprintForTime(issue.project, issue.createdMs);
    if (sprint) {
      issue.sprintIds.push(sprint.id);
      issue.sprintEvents.push({ atMs: issue.createdMs, from: [], to: [sprint.id] });
      // Carry-over is a direct ~20% draw (carryoverEligible, rolled per-issue above), not an
      // emergent property of arc-length-vs-sprint-boundary geometry — that geometry alone would
      // put the vast majority of long-lived issues into "carried over" territory, which is not
      // what "~20% carry-over" in the plan means. It only takes effect when the issue's home
      // sprint has actually concluded by the reference date (an issue created in the still-open
      // active/future sprint hasn't had a chance to carry over yet).
      if (carryoverEligible && sprint.endMs <= REFERENCE_MS) {
        const nxt = nextSprintAfter(issue.project, sprint);
        if (nxt) {
          issue.sprintIds.push(nxt.id);
          issue.sprintEvents.push({ atMs: sprint.endMs, from: [sprint.id], to: issue.sprintIds.slice() });
          issue.carriedOver = true;
          carryoverCount++;
        }
      }
    }
  }

  // Story points (Scrum stories only), original estimate on a subset of estimable types.
  issue.storyPoints = null;
  if (issue.project.boardType === "scrum" && issue.type === ISSUE_TYPE.STORY) {
    issue.storyPoints = rng.pick([1, 2, 3, 5, 8, 13]);
    storyPointsCount++;
  }
  issue.originalEstimateSeconds = null;
  if (issue.type !== ISSUE_TYPE.EPIC && rng.bool(0.45)) {
    issue.originalEstimateSeconds = rng.int(1, 10) * 3600 * (rng.bool(0.5) ? 1 : 2);
    originalEstimateCount++;
  }

  // Labels / components / team.
  issue.labels = rng.shuffle(LABEL_POOL).slice(0, rng.int(0, 2));
  issue.components = rng.bool(0.7) ? [rng.pick(issue.project.components)] : [];
  issue.team = rng.bool(0.3) ? rng.pick(TEAMS) : null;
  issue.priority = rng.pick(PRIORITIES);
  issue.resolution = issue.resolvedAtMs !== null ? rng.pick(RESOLUTIONS) : null;

  // Worklogs: ~40% of issues, 1-4 entries between creation and last activity.
  issue.hasWorklogs = rng.bool(0.4);
  if (issue.hasWorklogs) {
    worklogIssueCount++;
    const count = rng.int(1, 4);
    const list = [];
    for (let i = 0; i < count; i++) {
      const startedMs = issue.createdMs + rng.int(0, Math.max(1, Math.floor((windowEnd - issue.createdMs) / DAY_MS) || 1)) * DAY_MS;
      const cappedStarted = Math.min(startedMs, REFERENCE_MS);
      const worklog = {
        id: String(nextWorklogId++),
        issueId: issue.id,
        author: pickAuthor(),
        started: restIso(cappedStarted),
        created: restIso(cappedStarted),
        updated: restIso(cappedStarted),
        timeSpentSeconds: rng.int(1, 8) * 1800,
      };
      list.push(worklog);
      worklogTotalCount++;
    }
    worklogsByIssue.set(issue.id, list);
  } else {
    worklogsByIssue.set(issue.id, []);
  }
}

// ---------------------------------------------------------------------------------------------
// Emit changelog histories from the simulated events (single source of truth).
// ---------------------------------------------------------------------------------------------
for (const issue of issues) {
  const timeline = [];
  for (const e of issue.statusEvents) timeline.push({ atMs: e.atMs, items: [makeStatusItem(e.from, e.to)] });
  for (const e of issue.assigneeEvents) {
    timeline.push({
      atMs: e.atMs,
      items: [
        {
          field: "assignee",
          fieldId: "assignee",
          from: e.from?.accountId ?? null,
          fromString: e.from?.displayName ?? null,
          to: e.to?.accountId ?? null,
          toString: e.to?.displayName ?? null,
        },
      ],
    });
  }
  for (const e of issue.flaggedEvents) {
    timeline.push({
      atMs: e.atMs,
      items: [
        {
          field: "Flagged",
          fieldId: CF.FLAGGED,
          from: e.from ? "10019" : null,
          fromString: e.from ? "Impediment" : null,
          to: e.to ? "10019" : null,
          toString: e.to ? "Impediment" : null,
        },
      ],
    });
  }
  for (const e of issue.rankEvents) {
    timeline.push({
      atMs: e.atMs,
      items: [{ field: "Rank", fieldId: CF.RANK, from: e.from, fromString: e.from, to: e.to, toString: e.to }],
    });
  }
  for (const e of issue.sprintEvents) {
    timeline.push({
      atMs: e.atMs,
      items: [
        {
          field: "Sprint",
          fieldId: CF.SPRINT,
          from: e.from.join(",") || null,
          fromString: e.from.map((id) => `Sprint ${id}`).join(", ") || null,
          to: e.to.join(","),
          toString: e.to.map((id) => `Sprint ${id}`).join(", "),
        },
      ],
    });
  }
  timeline.sort((a, b) => a.atMs - b.atMs);
  for (const entry of timeline) pushHistory(issue.id, entry.atMs, entry.items);
}

// ---------------------------------------------------------------------------------------------
// Day-2 scenario: pick a deleted issue, a moved issue, and a handful of updated issues.
// Chunking (below) determines the "omitted bulkfetch chunk" set; day-2 specials are chosen from
// OUTSIDE that set to keep the two concerns independent and easy to reason about.
// ---------------------------------------------------------------------------------------------
const inScopeIssues = issues.filter((i) => i.project.inScope);
const inScopeIdsAscending = inScopeIssues.map((i) => Number(i.id)).sort((a, b) => a - b);
const BULK_CHUNK_SIZE = 50;
const bulkChunks = [];
for (let i = 0; i < inScopeIdsAscending.length; i += BULK_CHUNK_SIZE) {
  bulkChunks.push(inScopeIdsAscending.slice(i, i + BULK_CHUNK_SIZE));
}
const OMITTED_CHUNK_INDEX = 5;
const omittedChunkIds = new Set(bulkChunks[OMITTED_CHUNK_INDEX].map(String));

function eligibleForDay2(issue) {
  return (
    issue.project.inScope &&
    issue.type !== ISSUE_TYPE.EPIC &&
    !omittedChunkIds.has(issue.id) &&
    !issues.some((other) => other.parent === issue) // no children depend on it
  );
}

const day2Candidates = rng.shuffle(inScopeIssues.filter(eligibleForDay2));
const deletedIssue = day2Candidates[0];
const movedIssue = day2Candidates.find((i) => i.project.key !== deletedIssue.project.key);
const updatedIssues = day2Candidates
  .filter((i) => i !== deletedIssue && i !== movedIssue)
  .slice(0, 6)
  .filter((_, idx, arr) => idx < 4 || arr.length <= 4)
  .slice(0, 4);

// The moved issue now lives under the out-of-scope project.
const secProject = PROJECTS.find((p) => p.key === "SEC");

// New changelog history for each updated/moved issue, dated at DAY2_NOW.
for (const issue of [...updatedIssues, movedIssue]) {
  if (issue === movedIssue) {
    pushHistory(issue.id, DAY2_NOW_MS, [
      { field: "project", fieldId: "project", from: issue.project.id, fromString: issue.project.name, to: secProject.id, toString: secProject.name },
      { field: "Key", fieldId: "issuekey", from: issue.key, fromString: issue.key, to: `${secProject.key}-9001`, toString: `${secProject.key}-9001` },
    ]);
  } else {
    const wasPoints = issue.storyPoints;
    if (issue.project.boardType === "scrum" && issue.type === ISSUE_TYPE.STORY) {
      issue.day2StoryPoints = rng.pick([1, 2, 3, 5, 8, 13, 21]);
      // Real Jira Cloud number custom fields carry only fromString/toString (see the
      // backfill-history emission above) — from/to stay null here too.
      pushHistory(issue.id, DAY2_NOW_MS, [
        {
          field: "Story Points",
          fieldId: CF.STORY_POINTS,
          from: null,
          fromString: String(wasPoints ?? ""),
          to: null,
          toString: String(issue.day2StoryPoints),
        },
      ]);
    } else {
      const from = issue.status;
      const to = issue.project.workflow.find((s) => s !== from) ?? from;
      issue.day2Status = to;
      pushHistory(issue.id, DAY2_NOW_MS, [makeStatusItem(from, to)]);
    }
  }
}

// Day-2 worklog feed: 2 new in-scope worklogs, 2 out-of-scope (SEC) worklogs, 1 deleted in-scope
// worklog (removed from an issue that already had one from backfill).
const secIssues = issues.filter((i) => i.project.key === "SEC" && i.type !== ISSUE_TYPE.EPIC);
const day2NewInScopeWorklogs = updatedIssues.slice(0, 2).map((issue) => {
  const w = {
    id: String(nextWorklogId++),
    issueId: issue.id,
    author: pickAuthor(),
    started: restIso(DAY2_NOW_MS),
    created: restIso(DAY2_NOW_MS),
    updated: restIso(DAY2_NOW_MS),
    timeSpentSeconds: rng.int(1, 8) * 1800,
  };
  worklogsByIssue.get(issue.id).push(w);
  worklogTotalCount++;
  return w;
});
const day2OutOfScopeWorklogs = rng.shuffle(secIssues).slice(0, 2).map((issue) => ({
  id: String(nextWorklogId++),
  issueId: issue.id,
  author: pickAuthor(),
  started: restIso(DAY2_NOW_MS),
  created: restIso(DAY2_NOW_MS),
  updated: restIso(DAY2_NOW_MS),
  timeSpentSeconds: rng.int(1, 8) * 1800,
}));
const day2DeletedWorklogSourceIssue = inScopeIssues.find((i) => i !== deletedIssue && i !== movedIssue && worklogsByIssue.get(i.id).length > 0);
const day2DeletedWorklog = worklogsByIssue.get(day2DeletedWorklogSourceIssue.id)[0];

// ---------------------------------------------------------------------------------------------
// Serialize an issue into a real Jira `fields` document, at a given "as of" scenario.
// ---------------------------------------------------------------------------------------------
function issueFieldsJson(issue, scenario) {
  const status = scenario === "day2" && issue.day2Status ? issue.day2Status : issue.status;
  const storyPoints = scenario === "day2" && issue.day2StoryPoints !== undefined ? issue.day2StoryPoints : issue.storyPoints;
  const project = scenario === "day2" && issue === movedIssue ? secProject : issue.project;
  const updatedMs =
    scenario === "day2" && (issue === movedIssue || updatedIssues.includes(issue)) ? DAY2_NOW_MS : issue.updatedMs;

  const fields = {
    summary: `${issue.type.name} ${issue.key}: sample work item`,
    status: statusJson(status),
    issuetype: { id: issue.type.id, name: issue.type.name, subtask: issue.type.subtask },
    project: { id: project.id, key: project.key, name: project.name },
    created: restIso(issue.createdMs),
    updated: restIso(updatedMs),
    resolutiondate: issue.resolvedAtMs !== null ? restIso(issue.resolvedAtMs) : null,
    assignee: issue.assignee ? { accountId: issue.assignee.accountId, displayName: issue.assignee.displayName } : null,
    reporter: { accountId: issue.reporter.accountId, displayName: issue.reporter.displayName },
    priority: issue.priority,
    resolution: issue.resolution,
    labels: issue.labels,
    components: issue.components.map((name) => ({ id: name, name })),
  };
  if (issue.parent) fields.parent = { id: issue.parent.id, key: issue.parent.key };
  if (issue.team) fields[CF.TEAM] = { id: issue.team.id, name: issue.team.name };
  if (storyPoints !== null && storyPoints !== undefined) fields[CF.STORY_POINTS] = storyPoints;
  fields[CF.RANK] = issue.rank;
  fields[CF.FLAGGED] = issue.flagged ? [{ value: "Impediment", id: "10019" }] : [];
  if (issue.project.boardType === "scrum" && issue.sprintIds.length) {
    fields[CF.SPRINT] = issue.sprintIds.map((id) => {
      const s = SPRINTS_BY_PROJECT.get(issue.project.key).find((sp) => sp.id === id);
      return {
        id: s.id,
        name: s.name,
        state: s.state,
        boardId: s.boardId,
        startDate: iso(s.startMs),
        endDate: iso(s.endMs),
        goal: s.goal,
      };
    });
  }
  if (issue.originalEstimateSeconds !== null) {
    fields.timetracking = { originalEstimateSeconds: issue.originalEstimateSeconds, remainingEstimateSeconds: issue.originalEstimateSeconds, timeSpentSeconds: 0 };
  }
  // Phase 3 (v0.3.0 M1 commit 1): epic own dates + work category (every epic and ~60% of tasks).
  if (issue.epicStartMs !== undefined) fields[CF.EPIC_START] = isoDate(issue.epicStartMs);
  if (issue.epicDueMs !== undefined) fields.duedate = isoDate(issue.epicDueMs);
  if (issue.workCategory) fields[CF.WORK_CATEGORY] = { id: issue.workCategory.id, value: issue.workCategory.value };
  return { id: issue.id, key: issue.key, fields };
}

// ===============================================================================================
// Phase 3 (v0.3.0 M1 commit 1) inputs — epic story points/dates, parent moves, story-point
// changes after start, work category, worklog created/updated skew, sprint completeDate skew, the
// future-sprint backlog, the deterministic team roster, and the two golden fixtures.
//
// Every draw below comes from `rng2` (never `rng`), and this whole section runs strictly AFTER the
// phase-2 dataset (issues, ids, changelog, day-2 scenario) is fully built — so none of it can shift
// an existing id, count, timeline, the omitted bulkfetch chunk or the day2 deltas (phase-3 plan §0
// A2's hard constraint). Where a new changelog history necessarily grows an existing total
// (`changelog.inScopeHistories` below), that total is COMPUTED, not hand-copied, so it can never
// drift from what the generator actually wrote.
// ===============================================================================================

function removeFieldHistoryEntries(issueId, fieldPredicate) {
  const list = changeHistories.get(issueId) ?? [];
  changeHistories.set(issueId, list.filter((h) => !h.items.some(fieldPredicate)));
}

const day2SpecialIds = new Set([deletedIssue.id, movedIssue.id, ...updatedIssues.map((i) => i.id)]);

// --- Epic story points (D7) + epic dates (Start date / duedate), with a date change on ~25% ------
const EPIC_SP_MIN = 20;
const EPIC_SP_MAX = 80;
const epics = issues.filter((i) => i.type === ISSUE_TYPE.EPIC);
let epicDatesChangedCount = 0;
for (const epic of epics) {
  epic.storyPoints = rng2.int(EPIC_SP_MIN, EPIC_SP_MAX);

  const startOffsetDays = rng2.int(0, 30);
  epic.epicStartMs = Math.min(epic.createdMs + startOffsetDays * DAY_MS, REFERENCE_MS - DAY_MS);
  const durationDays = rng2.int(30, 180);
  epic.epicDueMs = epic.epicStartMs + durationDays * DAY_MS;

  if (rng2.bool(0.25)) {
    epicDatesChangedCount++;
    const changeAtMs = Math.min(epic.epicStartMs + rng2.int(5, 60) * DAY_MS, REFERENCE_MS - DAY_MS);
    if (rng2.bool(0.5)) {
      const oldDue = epic.epicDueMs;
      const newDue = oldDue + rng2.int(-20, 30) * DAY_MS;
      epic.epicDueMs = newDue;
      pushHistory(epic.id, changeAtMs, [
        {
          field: "Due date",
          fieldId: "duedate",
          from: isoDate(oldDue),
          fromString: isoDate(oldDue),
          to: isoDate(newDue),
          toString: isoDate(newDue),
        },
      ]);
    } else {
      const oldStart = epic.epicStartMs;
      const newStart = Math.max(oldStart + rng2.int(-10, 20) * DAY_MS, epic.createdMs);
      epic.epicStartMs = newStart;
      pushHistory(epic.id, changeAtMs, [
        {
          field: "Start date",
          fieldId: CF.EPIC_START,
          from: isoDate(oldStart),
          fromString: isoDate(oldStart),
          to: isoDate(newStart),
          toString: isoDate(newStart),
        },
      ]);
    }
  }
}

// Point-in-time story-point timeline, one entry per (atMs, value), ascending — the source the
// golden sprint/epic fixtures resolve "estimate at commitment/entry/completion" from below. Every
// issue starts with a single entry at its own creation (epics included, now that their own SP is
// assigned above); the story-point-change/estimated-late loop appends to it.
for (const issue of issues) {
  issue.storyPointHistory = [{ atMs: issue.createdMs, value: issue.storyPoints }];
}

// --- Parent moves: ~3% of parented tasks move to another epic once (a changelog `Parent` item) --
const epicsByProject = new Map(PROJECTS.map((p) => [p.key, epics.filter((e) => e.project === p)]));
const parentedTasks = issues.filter((i) => i.parent && i.parent.type === ISSUE_TYPE.EPIC && !day2SpecialIds.has(i.id));
const parentMoveIssueIds = [];
for (const issue of parentedTasks) {
  if (!rng2.bool(0.03)) continue;
  const sameProjectEpics = epicsByProject.get(issue.project.key).filter((e) => e !== issue.parent);
  if (sameProjectEpics.length === 0) continue;
  const oldEpic = issue.parent;
  const newEpic = rng2.pick(sameProjectEpics);
  const changeAtMs = Math.min(issue.createdMs + rng2.int(1, 60) * DAY_MS, REFERENCE_MS - DAY_MS);
  pushHistory(issue.id, changeAtMs, [
    { field: "Parent", fieldId: "parent", from: oldEpic.id, fromString: oldEpic.key, to: newEpic.id, toString: newEpic.key },
  ]);
  issue.parent = newEpic;
  parentMoveIssueIds.push(issue.id);
}

// --- Story-point changes after the first IN_PROGRESS transition (~15%), estimated late (~5%) -----
const STORY_POINT_VALUES = [1, 2, 3, 5, 8, 13, 21];
const startedEstimableStories = issues.filter(
  (i) => i.project.boardType === "scrum" && i.type === ISSUE_TYPE.STORY && i.statusEvents.length > 0 && !day2SpecialIds.has(i.id),
);
let estimateChangedAfterStartCount = 0;
let estimatedLateCount = 0;
for (const issue of startedEstimableStories) {
  const outcome = rng2.pickWeighted([
    ["change", 15],
    ["late", 5],
    ["none", 80],
  ]);
  if (outcome === "none") continue;
  const firstInProgressMs = issue.statusEvents[0].atMs;
  if (outcome === "change") {
    const oldSp = issue.storyPoints;
    const changeAtMs = Math.min(firstInProgressMs + rng2.int(1, 20) * DAY_MS, REFERENCE_MS - DAY_MS);
    if (changeAtMs <= firstInProgressMs) continue;
    const candidates = STORY_POINT_VALUES.filter((v) => v !== oldSp);
    const newSp = rng2.pick(candidates);
    issue.storyPoints = newSp;
    issue.estimateChangedAfterStart = true;
    issue.storyPointHistory.push({ atMs: changeAtMs, value: newSp });
    estimateChangedAfterStartCount++;
    // Real Jira Cloud number custom fields (Story Points included) carry their changelog value
    // ONLY in fromString/toString — from/to (the id fields, meaningful for select/option fields)
    // are null on a real tenant (`metrics/DeriveKernels.kt`'s `estimateTimeline` review round 1
    // fix falls back to the text pair for exactly this reason).
    pushHistory(issue.id, changeAtMs, [
      {
        field: "Story Points",
        fieldId: CF.STORY_POINTS,
        from: null,
        fromString: String(oldSp),
        to: null,
        toString: String(newSp),
      },
    ]);
  } else {
    // "estimated late": no estimate at start, current/final value appears only via this history.
    const finalSp = issue.storyPoints;
    const changeAtMs = Math.min(firstInProgressMs + rng2.int(1, 15) * DAY_MS, REFERENCE_MS - DAY_MS);
    if (changeAtMs <= firstInProgressMs) continue;
    issue.estimatedLate = true;
    issue.storyPointHistory = [{ atMs: issue.createdMs, value: null }, { atMs: changeAtMs, value: finalSp }];
    estimatedLateCount++;
    pushHistory(issue.id, changeAtMs, [
      { field: "Story Points", fieldId: CF.STORY_POINTS, from: null, fromString: "", to: null, toString: String(finalSp) },
    ]);
  }
}

// --- Work category (customfield_10030): every epic, ~60% of every other (non-subtask-exempt) issue
for (const issue of issues) {
  if (issue.type === ISSUE_TYPE.EPIC) {
    issue.workCategory = rng2.pick(WORK_CATEGORY_OPTIONS);
  } else if (rng2.bool(0.6)) {
    issue.workCategory = rng2.pick(WORK_CATEGORY_OPTIONS);
  }
}

// --- Worklog created/updated skew: created = started + 1..5 days on ~20%, updated > created on ~10%
const allWorklogs = [...worklogsByIssue.values()].flat();
let worklogCreatedLaterCount = 0;
let worklogUpdatedLaterCount = 0;
for (const w of allWorklogs) {
  const startedMs = Date.parse(w.started);
  let createdMs = startedMs;
  if (rng2.bool(0.2)) {
    createdMs = startedMs + rng2.int(1, 5) * DAY_MS;
    worklogCreatedLaterCount++;
  }
  w.created = restIso(createdMs);
  let updatedMs = createdMs;
  if (rng2.bool(0.1)) {
    updatedMs = createdMs + rng2.int(1, 3) * DAY_MS;
    worklogUpdatedLaterCount++;
  }
  w.updated = restIso(updatedMs);
}

// --- Sprint completeDate skew: completeDate = endDate + 0..2 days on ~30% of closed sprints -------
let sprintCompleteDateShiftedCount = 0;
for (const project of SCRUM_PROJECTS) {
  for (const sprint of SPRINTS_BY_PROJECT.get(project.key)) {
    if (sprint.state !== "closed") continue;
    sprint.completeAtMs = sprint.endMs;
    if (rng2.bool(0.3)) {
      sprint.completeAtMs = sprint.endMs + rng2.int(0, 2) * DAY_MS;
      sprintCompleteDateShiftedCount++;
    }
  }
}

// --- ~10 issues placed in the future sprint (estimated, not started — D9's backlog-in-a-future-
// sprint case, as opposed to a genuinely unscheduled backlog item) ---------------------------------
// A genuinely-never-started issue is already rare in this dataset (simulateLifecycle only ever
// leaves an issue at wf[0]/TODO on its own ~1% x 35% "neverStarted" roll), so this feature
// deliberately CONSTRUCTS its ~10 backlog items rather than relying on that roll to surface enough
// of them: it picks estimated Scrum stories that never reopened or carried over (so resetting them
// can't disturb the already-finalized `reopens`/`sprints.carryOverCount` figures above) and resets
// their status timeline to TODO before assigning them to the future sprint.
const FUTURE_SPRINT_TARGET = 10;
let futureBacklogPlaced = 0;
for (const project of SCRUM_PROJECTS) {
  if (futureBacklogPlaced >= FUTURE_SPRINT_TARGET) break;
  const futureSprint = SPRINTS_BY_PROJECT.get(project.key).find((s) => s.state === "future");
  if (!futureSprint) continue;
  const candidates = issues.filter(
    (i) =>
      i.project === project &&
      i.type === ISSUE_TYPE.STORY &&
      i.storyPoints !== null &&
      !i.carriedOver &&
      !i.reopened &&
      !i.estimateChangedAfterStart &&
      !i.estimatedLate &&
      !i.sprintIds.includes(futureSprint.id) &&
      !day2SpecialIds.has(i.id),
  );
  const shuffled = rng2.shuffle(candidates);
  const take = Math.min(shuffled.length, FUTURE_SPRINT_TARGET - futureBacklogPlaced, 4);
  for (const issue of shuffled.slice(0, take)) {
    // Reset to a never-started TODO issue: drop any status history, reopen the current-state fields.
    removeFieldHistoryEntries(issue.id, (item) => item.field === "status");
    issue.statusEvents = [];
    issue.status = issue.project.workflow[0];
    issue.resolvedAtMs = null;

    removeFieldHistoryEntries(issue.id, (item) => item.field === "Sprint");
    issue.sprintIds = [futureSprint.id];
    issue.sprintEvents = [{ atMs: issue.createdMs, from: [], to: [futureSprint.id] }];
    pushHistory(issue.id, issue.createdMs, [
      {
        field: "Sprint",
        fieldId: CF.SPRINT,
        from: null,
        fromString: null,
        to: String(futureSprint.id),
        toString: `Sprint ${futureSprint.id}`,
      },
    ]);
    issue.placedInFutureSprint = true;
    futureBacklogPlaced++;

    // `updated` must stay >= every remaining changelog entry's own timestamp (assignee/flagged/
    // rank changes are untouched by this reset) — recompute rather than collapsing to createdMs.
    const remainingEventTimes = [
      issue.createdMs,
      ...issue.assigneeEvents.map((e) => e.atMs),
      ...issue.flaggedEvents.map((e) => e.atMs),
      ...issue.rankEvents.map((e) => e.atMs),
    ];
    issue.updatedMs = Math.max(...remainingEventTimes);
  }
}

// --- Deterministic 30-user roster over the 4 in-scope teams (2 users in none) ----------------------
const ROSTER_UNASSIGNED_COUNT = 2;
const rosterShuffled = rng2.shuffle(USERS);
const rosterUnassigned = rosterShuffled.slice(0, ROSTER_UNASSIGNED_COUNT);
const rosterAssignable = rosterShuffled.slice(ROSTER_UNASSIGNED_COUNT);
const teamRoster = [
  ...rosterAssignable.map((u, idx) => ({ accountId: u.accountId, displayName: u.displayName, team: TEAMS[idx % TEAMS.length].name })),
  ...rosterUnassigned.map((u) => ({ accountId: u.accountId, displayName: u.displayName, team: null })),
].sort((a, b) => a.accountId.localeCompare(b.accountId));

// --- Shared history-relocation helper: moves (or splits out) the "Sprint" changelog item that ----
// recorded a given first-entry event, from its old timestamp to a new one. Splitting only matters
// if some OTHER field's change happened to land at the exact same instant (never observed in this
// dataset — the Sprint entry is always its own standalone history — but handled generically rather
// than assumed away).
const HOUR_MS = 3_600_000;
function relocateSprintEntryHistory(issue, targetSprintId, oldAtMs, newAtMs) {
  const list = changeHistories.get(issue.id) ?? [];
  const matches = (it) => it.field === "Sprint" && it.to === String(targetSprintId) && (it.from === null || it.from === "");
  const historyIdx = list.findIndex((h) => Date.parse(h.created) === oldAtMs && h.items.some(matches));
  if (historyIdx === -1) return null; // defensive — the entry event always has a matching history
  const history = list[historyIdx];
  const itemIdx = history.items.findIndex(matches);
  let split = false;
  if (history.items.length === 1) {
    history.created = restIso(newAtMs);
  } else {
    const [item] = history.items.splice(itemIdx, 1);
    list.push({ id: String(nextHistoryId++), created: restIso(newAtMs), author: history.author, items: [item] });
    split = true;
  }
  changeHistories.set(issue.id, list);
  return { split };
}

// --- Sprint-entry planning moment: move ~80% of the future-sprint backlog's first entry to just ---
// before its sprint's own start ------------------------------------------------------------------
// In Scrum, most scope enters a sprint at planning (before it starts) — but `sprintForTime` (phase
// 2, unaltered) always finds the sprint whose window CONTAINS the issue's own `createdMs`, so a
// REGULAR issue's first entry can never safely move before its own sprint's start (it would place
// an "added to sprint" event before the issue existed relative to that sprint — impossible, since
// `createdMs >= sprint.startMs` always holds for the sprint `sprintForTime` itself picked). This
// step is therefore scoped explicitly to the one population that genuinely predates its target
// sprint: the ~10 future-sprint backlog placements above (constructed well before the future sprint
// even opens) — never the broader population "Backlog refinement" below handles differently.
let sprintEntryMovedCount = 0;
let sprintEntrySplitCount = 0;
for (const issue of issues) {
  if (!issue.placedInFutureSprint) continue;
  const entry = issue.sprintEvents[0];
  const targetSprintId = entry.to[0];
  const sprint = SPRINTS_BY_PROJECT.get(issue.project.key).find((s) => s.id === targetSprintId);
  if (!sprint) continue;
  if (!(issue.createdMs < sprint.startMs)) continue; // the issue must already have existed
  if (!rng2.bool(0.8)) continue;

  const oldAtMs = entry.atMs;
  const moveTo = Math.max(issue.createdMs, sprint.startMs - rng2.int(1, 20) * HOUR_MS);
  entry.atMs = moveTo;
  const result = relocateSprintEntryHistory(issue, targetSprintId, oldAtMs, moveTo);
  if (!result) continue;
  if (result.split) sprintEntrySplitCount++;
  sprintEntryMovedCount++;
}

// --- Backlog refinement: ~75% of level-0 Scrum tasks (FLO/PLT/GTM) move BOTH their own creation ---
// and their first sprint entry further back, modelling refined backlog: the item already existed
// and was groomed before sprint planning pulled it in, rather than being created mid-sprint. Only
// level-0 tasks move directly (D2 — sub-tasks follow their parent's sprint, and epics have their
// own budget/dates fixture); a moved task's own sub-tasks are pulled forward only if they would
// otherwise now precede it (a defensive floor — moving a parent EARLIER only widens the existing
// gap to its children's own untouched creation times in this dataset, so this is not expected to
// ever trigger). Status events, worklogs and every OTHER changelog item keep their own absolute
// timestamps unchanged — the issue's first status interval (`.claude/docs/ingestion.md` "Normalized
// layer") simply starts earlier, i.e. it sat in TODO/backlog longer, exactly the intended effect.
let backlogRefinedCount = 0;
let backlogRefinedSplitCount = 0;
let backlogRefinedSubtaskPulledCount = 0;
for (const issue of issues) {
  if (issue.project.boardType !== "scrum" || issue.type === ISSUE_TYPE.EPIC || issue.type === ISSUE_TYPE.SUBTASK) continue;
  if (issue.placedInFutureSprint || day2SpecialIds.has(issue.id)) continue;
  if (!issue.sprintEvents.length) continue;
  const entry = issue.sprintEvents[0];
  if (entry.from.length !== 0) continue; // only the genuine first entry, never the carry-over hop
  const targetSprintId = entry.to[0];
  const projectSprints = SPRINTS_BY_PROJECT.get(issue.project.key);
  const sprint = projectSprints.find((s) => s.id === targetSprintId);
  if (!sprint) continue;
  if (!(entry.atMs >= sprint.startMs)) continue; // "at/after its sprint's start" — the norm here
  if (!rng2.bool(0.75)) continue;

  const sprintIdx = projectSprints.findIndex((s) => s.id === sprint.id);
  const prevSprint = sprintIdx > 0 ? projectSprints[sprintIdx - 1] : null;
  const lowerBound = Math.max(BACKFILL_START_MS, prevSprint ? prevSprint.startMs : BACKFILL_START_MS);

  const oldEntryAtMs = entry.atMs;
  const newCreatedMs = Math.max(sprint.startMs - rng2.int(2, 21) * DAY_MS, lowerBound);
  const newEntryAtMs = Math.max(sprint.startMs - rng2.int(1, 20) * HOUR_MS, newCreatedMs);

  issue.createdMs = newCreatedMs;
  entry.atMs = newEntryAtMs;
  // storyPointHistory[0] is this generator's own internal "value held since creation" bookkeeping
  // (never serialized to the stub) — keep its anchor in step with the moved creation time.
  if (issue.storyPointHistory.length) issue.storyPointHistory[0].atMs = newCreatedMs;

  const result = relocateSprintEntryHistory(issue, targetSprintId, oldEntryAtMs, newEntryAtMs);
  if (result) {
    backlogRefinedCount++;
    if (result.split) backlogRefinedSplitCount++;
  }

  for (const child of issues) {
    if (child.parent === issue && child.type === ISSUE_TYPE.SUBTASK && child.createdMs < issue.createdMs) {
      child.createdMs = issue.createdMs;
      backlogRefinedSubtaskPulledCount++;
    }
  }
}

// --- Golden fixtures: one FLO sprint (closed) + one FLO epic, computed from the generator's own ---
// timeline — a SECOND, independent implementation later Kotlin tests (commit 8's MetricsGoldenTest/
// MetricsDerivationTest) compare derived figures against (the `EXPECTED_STATUS_CATEGORY`/
// reopens.inScopeCount precedent, `.claude/docs/test-fixtures.md` "The invariant SQL sweep"). Every rule
// below is `.claude/docs/domain-model.md`'s own, not this file's simplification: grace is 0 ("the
// sprint's scope at start + grace"); 1 SP = 1 MD; `done_at` is recomputed FRESH from the issue's own
// status events (never reusing `resolvedAtMs`, which was computed as part of building those events
// in the first place — reusing it would grade the same code path's homework twice); estimates are
// resolved POINT-IN-TIME from each issue's own story-point timeline, never its current/final value.
function doneAtOf(issue) {
  // The start of the trailing DONE-category run: set on every entry into a done-category status,
  // cleared on every exit — so a reopened-then-redone issue's final entry wins, never its first.
  let doneAt = null;
  for (const e of issue.statusEvents) {
    if (e.to.categoryKey === "done") doneAt = e.atMs;
    else if (e.from.categoryKey === "done") doneAt = null;
  }
  return doneAt;
}
function spAsOf(issue, atMs) {
  // storyPointHistory is ascending by atMs (built in temporal order above) — the last entry at or
  // before `atMs` is the value that held at that instant; 0/null both mean "unestimated".
  let value = null;
  for (const h of issue.storyPointHistory) {
    if (h.atMs <= atMs) value = h.value;
    else break;
  }
  return value;
}
function sprintEntryTimes(issue, sprintId) {
  return issue.sprintEvents
    .filter((e) => e.to.includes(sprintId) && !e.from.includes(sprintId))
    .map((e) => e.atMs)
    .sort((a, b) => a - b);
}
function sprintExitTimes(issue, sprintId) {
  // No sprint-scope removal is modelled in this dataset today (carry-over only ADDS a sprint,
  // never drops the one it came from) — always empty here, but resolved generically (not hardcoded
  // to "never happens") so a future exit mechanism is handled correctly without touching this file.
  return issue.sprintEvents
    .filter((e) => e.from.includes(sprintId) && !e.to.includes(sprintId))
    .map((e) => e.atMs)
    .sort((a, b) => a - b);
}
function firstSprintEntryAt(issue, sprintId) {
  const entries = sprintEntryTimes(issue, sprintId);
  return entries.length ? entries[0] : null;
}
function isSprintMemberAt(issue, sprintId, atMs) {
  const entries = sprintEntryTimes(issue, sprintId);
  const exits = sprintExitTimes(issue, sprintId);
  let enteredAt = null;
  for (const e of entries) {
    if (e <= atMs) enteredAt = e;
    else break;
  }
  if (enteredAt === null) return false;
  return !exits.some((x) => x > enteredAt && x <= atMs);
}

function sprintItemsOf(project, sprint) {
  // Level-0 tasks only (D2): epics have their own budget/dates fixture below, and Jira sub-tasks
  // follow their parent task's sprint rather than carrying independent sprint scope of their own.
  return issues.filter(
    (i) =>
      i.project === project &&
      i.type !== ISSUE_TYPE.EPIC &&
      i.type !== ISSUE_TYPE.SUBTASK &&
      firstSprintEntryAt(i, sprint.id) !== null,
  );
}

function computeSprintScope(project, sprint) {
  const commitAt = sprint.startMs; // grace 0
  const completeAt = sprint.completeAtMs ?? sprint.endMs;
  const projectSprints = SPRINTS_BY_PROJECT.get(project.key);
  const sprintIndex = projectSprints.findIndex((s) => s.id === sprint.id);
  const items = sprintItemsOf(project, sprint);
  const scope = {
    committedMd: 0,
    committedItems: 0,
    committedIssueKeys: [],
    addedMd: 0,
    addedItems: 0,
    addedIssueKeys: [],
    removedMd: 0,
    removedItems: 0,
    removedIssueKeys: [],
    finalMd: 0,
    finalItems: 0,
    deliveredMd: 0,
    deliveredItems: 0,
    deliveredIssueKeys: [],
    carriedOverMd: 0,
    carriedOverItems: 0,
    carriedOverIssueKeys: [],
    droppedMd: 0,
    droppedItems: 0,
    droppedIssueKeys: [],
  };
  let hasNonZeroDelivered = false;
  for (const issue of items) {
    const enteredAt = firstSprintEntryAt(issue, sprint.id);
    const isCommitted = enteredAt <= commitAt;
    const isFinal = isSprintMemberAt(issue, sprint.id, completeAt);
    if (isCommitted && !isFinal) {
      // Committed, then exited before completion — removed scope.
      scope.removedItems++;
      scope.removedMd += spAsOf(issue, commitAt) ?? 0;
      scope.removedIssueKeys.push(issue.key);
      continue;
    }
    if (!isFinal) continue; // entered after start, exited before completion — never final scope

    const spAtCompletion = spAsOf(issue, completeAt) ?? 0;
    scope.finalItems++;
    scope.finalMd += spAtCompletion;
    if (isCommitted) {
      scope.committedItems++;
      scope.committedMd += spAsOf(issue, commitAt) ?? 0;
      scope.committedIssueKeys.push(issue.key);
    } else {
      scope.addedItems++;
      scope.addedMd += spAsOf(issue, enteredAt) ?? 0;
      scope.addedIssueKeys.push(issue.key);
    }

    const doneAt = doneAtOf(issue);
    const deliveredWithinSprint =
      doneAt !== null && doneAt >= sprint.startMs && doneAt <= completeAt && isSprintMemberAt(issue, sprint.id, doneAt);
    if (deliveredWithinSprint) {
      scope.deliveredItems++;
      scope.deliveredMd += spAtCompletion;
      scope.deliveredIssueKeys.push(issue.key);
      if (spAtCompletion > 0) hasNonZeroDelivered = true;
      continue; // delivered items are never also carried/dropped
    }
    // A17: carried-over/dropped apply to EVERY final (in-scope-at-close) item not delivered in the
    // sprint, committed OR added — not just committed ones. Every issue reaching this point is
    // already final (the `if (!isFinal) continue;` guard above), so this is unconditional:
    // final = committed + added = delivered + carried-over + dropped, always.
    const hasLaterSprint = projectSprints.some((s, idx) => idx > sprintIndex && firstSprintEntryAt(issue, s.id) !== null);
    if (hasLaterSprint) {
      scope.carriedOverItems++;
      scope.carriedOverMd += spAtCompletion;
      scope.carriedOverIssueKeys.push(issue.key);
    } else {
      scope.droppedItems++;
      scope.droppedMd += spAtCompletion;
      scope.droppedIssueKeys.push(issue.key);
    }
  }
  scope.hasNonZeroDelivered = hasNonZeroDelivered;
  return scope;
}

const floProject = PROJECTS.find((p) => p.key === "FLO");
const floClosedSprints = SPRINTS_BY_PROJECT.get("FLO").filter((s) => s.state === "closed");
const floScopes = floClosedSprints.map((s) => ({ sprint: s, scope: computeSprintScope(floProject, s) }));
// Every figure exercised: at least one delivered item with SP > 0, and at least one carried-over
// item — falls back to the whole pool (still maximising committedMd) if none of FLO's closed
// sprints happen to satisfy both at once.
// "committed" (entered at-or-before sprint start) is, by construction, populated ONLY by items
// carried in from the immediately preceding sprint — a fresh item enters at its own (effectively
// random) creation time, essentially never exactly at a sprint boundary. Carry-over itself only
// ever fires ONE hop per issue (`carryoverEligible` above, the phase-2/`rng` dataset this commit
// must not alter — A2). A committed item has therefore already spent its one hop to arrive here,
// so it can never carry over AGAIN into the next sprint: `carriedOverItems` is mathematically 0 for
// EVERY closed sprint in this dataset (confirmed by inspection across all 26 closed FLO sprints),
// not a computation bug. The selection below asks for it anyway (documenting the intent for a
// future multi-hop carry-over dataset feature) but always falls back to `hasNonZeroDelivered` alone.
const qualifyingFloScopes = floScopes.filter((c) => c.scope.hasNonZeroDelivered && c.scope.carriedOverItems >= 1);
const nonZeroDeliveredFloScopes = floScopes.filter((c) => c.scope.hasNonZeroDelivered);
const goldenSprintPool =
  qualifyingFloScopes.length > 0 ? qualifyingFloScopes : nonZeroDeliveredFloScopes.length > 0 ? nonZeroDeliveredFloScopes : floScopes;
const goldenSprint = goldenSprintPool.reduce((best, cur) => (cur.scope.committedMd > best.scope.committedMd ? cur : best));

const floEpics = epics.filter((e) => e.project === floProject);
const childrenOf = (epic) => issues.filter((i) => i.parent === epic);
const goldenEpic = floEpics.reduce((best, e) => (childrenOf(e).length > childrenOf(best).length ? e : best), floEpics[0]);
const goldenEpicChildren = childrenOf(goldenEpic);
const goldenEpicChildSumMd = goldenEpicChildren.reduce((s, c) => s + (c.storyPoints ?? 0), 0);

// ===============================================================================================
// WireMock mapping helpers
// ===============================================================================================
rmSync(STUB_DIR, { recursive: true, force: true });
mkdirSync(MAPPINGS_DIR, { recursive: true });
mkdirSync(FILES_DIR, { recursive: true });

const AUTH_HEADER_PATTERN = "^(Basic|Bearer) .+$";

// startAt-paged endpoints: page 0 carries NO startAt constraint (it matches whatever the client
// sends for its first call — omitted, or an explicit `startAt=0`) at a lower priority than later
// pages, so an explicit `startAt=N` always wins over this generic "first page" fallback. Two
// separate stubs (one absent, one equalTo "0") would work too, but would have to agree on
// priority against each other; a single unconstrained low-priority stub is simpler and just as
// correct, since every OTHER page has an explicit, higher-priority `startAt` match.
function startAtPaging(idx, pageSize) {
  return idx === 0
    ? { priority: 5 }
    : { queryParameters: { startAt: { equalTo: String(idx * pageSize) } }, priority: 1 };
}

function writeStub(name, { method = "GET", urlPath, urlPathPattern, queryParameters, bodyPatterns, requireAuth = true, scenario, requiredState, newState, priority = 1 }, status, bodyObj) {
  const bodyFile = `${name}.json`;
  writeFileSync(path.join(FILES_DIR, bodyFile), JSON.stringify(bodyObj, null, 2) + "\n");

  const request = { method };
  if (urlPath) request.urlPath = urlPath;
  if (urlPathPattern) request.urlPathPattern = urlPathPattern;
  if (queryParameters) request.queryParameters = queryParameters;
  if (bodyPatterns) request.bodyPatterns = bodyPatterns;
  if (requireAuth) {
    request.headers = { Authorization: { matches: AUTH_HEADER_PATTERN } };
  }

  const mapping = {
    priority,
    request,
    response: {
      status,
      headers: { "Content-Type": "application/json" },
      bodyFileName: bodyFile,
    },
  };
  // WireMock only honours requiredScenarioState/newScenarioState when scenarioName is also set;
  // every gated mapping in this stub belongs to the one "jira-day2" scenario.
  if (scenario || requiredState || newState) mapping.scenarioName = scenario ?? SCENARIO;
  if (requiredState) mapping.requiredScenarioState = requiredState;
  if (newState) mapping.newScenarioState = newState;

  writeFileSync(path.join(MAPPINGS_DIR, `${name}.json`), JSON.stringify(mapping, null, 2) + "\n");
}

const SCENARIO = "jira-day2";
let mappingCount = 0;
const origWriteStub = writeStub;
function stub(...args) {
  mappingCount++;
  return origWriteStub(...args);
}

// --- tenant_info (unauthenticated) --------------------------------------------------------------
stub("tenant-info", { urlPath: "/_edge/tenant_info", requireAuth: false }, 200, { cloudId: CLOUD_ID });

// --- auth catch-all (must be lowest precedence: highest priority number) -----------------------
stub(
  "auth-catchall-401",
  { urlPathPattern: `${GATEWAY_PREFIX}/.*`, requireAuth: false, priority: 100 },
  401,
  { errorMessages: ["Client must be authenticated to access this resource."], errors: {} },
);

// --- myself --------------------------------------------------------------------------------------
stub("myself", { urlPath: `${API}/myself` }, 200, {
  accountId: USERS[0].accountId,
  displayName: "Flow Sample Service Account",
  emailAddress: "flow-sample-bot@flow-sample.example",
  timeZone: null,
  active: true,
});

// --- field ---------------------------------------------------------------------------------------
stub("field", { urlPath: `${API}/field` }, 200, FIELDS);

// --- statuses/search (paged) ---------------------------------------------------------------------
const statusValues = ALL_STATUSES.map((s) => statusSearchJson(s));
const STATUS_PAGE_SIZE = 4;
for (let i = 0; i * STATUS_PAGE_SIZE < statusValues.length; i++) {
  const page = statusValues.slice(i * STATUS_PAGE_SIZE, (i + 1) * STATUS_PAGE_SIZE);
  const isLast = (i + 1) * STATUS_PAGE_SIZE >= statusValues.length;
  stub(
    `statuses-search-page-${i + 1}`,
    { urlPath: `${API}/statuses/search`, ...startAtPaging(i, STATUS_PAGE_SIZE) },
    200,
    { maxResults: STATUS_PAGE_SIZE, startAt: i * STATUS_PAGE_SIZE, total: statusValues.length, isLast, values: page },
  );
}

// --- statuscategory --------------------------------------------------------------------------------
stub("statuscategory", { urlPath: `${API}/statuscategory` }, 200, STATUS_CATEGORIES);

// --- project/search + project/{key}/statuses ---------------------------------------------------
stub("project-search", { urlPath: `${API}/project/search` }, 200, {
  maxResults: 50,
  startAt: 0,
  total: PROJECTS.length,
  isLast: true,
  values: PROJECTS.map((p) => ({ id: p.id, key: p.key, name: p.name, projectTypeKey: "software", simplified: false, style: "classic" })),
});
for (const project of PROJECTS) {
  stub(`project-statuses-${project.key}`, { urlPath: `${API}/project/${project.key}/statuses` }, 200, [
    { id: ISSUE_TYPE.STORY.id, name: ISSUE_TYPE.STORY.name, subtask: false, statuses: project.workflow.map(statusJson) },
    { id: ISSUE_TYPE.TASK.id, name: ISSUE_TYPE.TASK.name, subtask: false, statuses: project.workflow.map(statusJson) },
    { id: ISSUE_TYPE.BUG.id, name: ISSUE_TYPE.BUG.name, subtask: false, statuses: project.workflow.map(statusJson) },
    { id: ISSUE_TYPE.SUBTASK.id, name: ISSUE_TYPE.SUBTASK.name, subtask: true, statuses: project.workflow.map(statusJson) },
    { id: ISSUE_TYPE.EPIC.id, name: ISSUE_TYPE.EPIC.name, subtask: false, statuses: project.workflow.map(statusJson) },
  ]);
}

// --- issuetype / priority / resolution / issueLinkType ------------------------------------------
stub("issuetype", { urlPath: `${API}/issuetype` }, 200, Object.values(ISSUE_TYPE));
stub("priority-search", { urlPath: `${API}/priority/search` }, 200, {
  maxResults: 50,
  startAt: 0,
  total: PRIORITIES.length,
  isLast: true,
  values: PRIORITIES,
});
stub("resolution-search", { urlPath: `${API}/resolution/search` }, 200, {
  maxResults: 50,
  startAt: 0,
  total: RESOLUTIONS.length,
  isLast: true,
  values: RESOLUTIONS,
});
stub("issuelinktype", { urlPath: `${API}/issueLinkType` }, 200, { issueLinkTypes: ISSUE_LINK_TYPES });

// --- users/search (paged) -------------------------------------------------------------------------
const USER_PAGE_SIZE = 20;
for (let i = 0; i * USER_PAGE_SIZE < USERS.length; i++) {
  const page = USERS.slice(i * USER_PAGE_SIZE, (i + 1) * USER_PAGE_SIZE);
  stub(
    `users-search-page-${i + 1}`,
    { urlPath: `${API}/users/search`, ...startAtPaging(i, USER_PAGE_SIZE) },
    200,
    page,
  );
}

// --- search/jql (GET, paged, two "fields" variants, scenario-gated) -----------------------------
const SEARCH_PAGE_SIZE = 100;

function issuesForScenario(scenario) {
  return inScopeIssues.filter((i) => {
    if (scenario === "day2" && (i === deletedIssue || i === movedIssue)) return false;
    return true;
  });
}

for (const scenario of ["Started", "day2"]) {
  const scenarioIssues = issuesForScenario(scenario);
  const fullDocs = scenarioIssues.map((i) => issueFieldsJson(i, scenario));
  const pages = [];
  for (let i = 0; i < fullDocs.length; i += SEARCH_PAGE_SIZE) pages.push(fullDocs.slice(i, i + SEARCH_PAGE_SIZE));

  pages.forEach((page, idx) => {
    const isLast = idx === pages.length - 1;
    stub(
      `search-jql-${scenario.toLowerCase()}-page-${idx + 1}`,
      {
        urlPath: `${API}/search/jql`,
        queryParameters: {
          // Real `search/jql` returns only `id` without `fields`; the full documents need `fields=*all`.
          fields: { equalTo: "*all" },
          nextPageToken: idx === 0 ? { absent: true } : { equalTo: `${scenario}-page-${idx + 1}` },
        },
        requiredState: scenario,
      },
      200,
      { issues: page, ...(isLast ? { isLast: true } : { isLast: false, nextPageToken: `${scenario}-page-${idx + 2}` }) },
    );
  });

  // fields=id variant, single page (pages of up to 5000; our in-scope set fits in one page).
  stub(
    `search-jql-ids-${scenario.toLowerCase()}`,
    { urlPath: `${API}/search/jql`, queryParameters: { fields: { equalTo: "id" } }, requiredState: scenario },
    200,
    { issues: scenarioIssues.map((i) => ({ id: i.id })), isLast: true },
  );

  stub(
    `approximate-count-${scenario.toLowerCase()}`,
    { method: "POST", urlPath: `${API}/search/approximate-count`, requiredState: scenario },
    200,
    { count: scenarioIssues.length },
  );
}

// --- issue/{id} probes: deliberately scoped to the handful of ids the pipeline actually fetches --
stub(`issue-get-probe`, { urlPath: `${API}/issue/${inScopeIssues[0].id}` }, 200, issueFieldsJson(inScopeIssues[0], "Started"));

stub(
  `issue-get-deleted-started`,
  { urlPath: `${API}/issue/${deletedIssue.id}`, requiredState: "Started" },
  200,
  issueFieldsJson(deletedIssue, "Started"),
);
stub(
  `issue-get-deleted-day2`,
  { urlPath: `${API}/issue/${deletedIssue.id}`, requiredState: "day2" },
  404,
  { errorMessages: ["Issue does not exist or you do not have permission to see it."], errors: {} },
);
stub(
  `issue-get-moved-started`,
  { urlPath: `${API}/issue/${movedIssue.id}`, requiredState: "Started" },
  200,
  issueFieldsJson(movedIssue, "Started"),
);
stub(
  `issue-get-moved-day2`,
  { urlPath: `${API}/issue/${movedIssue.id}`, requiredState: "day2" },
  200,
  issueFieldsJson(movedIssue, "day2"),
);

// --- changelog/bulkfetch: ascending 50-id chunks; one chunk deliberately omitted -----------------
function historiesJson(issueId, upToMs) {
  return (changeHistories.get(issueId) ?? []).filter((h) => Date.parse(h.created) <= upToMs);
}
// Real bulkfetch gives a history's `created` as epoch millis (a number); the per-issue changelog keeps the text form.
function bulkHistoriesJson(issueId, upToMs) {
  return historiesJson(issueId, upToMs).map((h) => ({ ...h, created: Date.parse(h.created) }));
}

bulkChunks.forEach((chunk, idx) => {
  if (idx === OMITTED_CHUNK_INDEX) return; // omitted on purpose: exercises the per-issue fallback
  const body = chunk.map((id) => ({ issueId: String(id), changeHistories: bulkHistoriesJson(String(id), REFERENCE_MS) }));
  stub(
    `changelog-bulkfetch-chunk-${idx}`,
    {
      method: "POST",
      urlPath: `${API}/changelog/bulkfetch`,
      bodyPatterns: [{ equalToJson: JSON.stringify({ issueIdsOrKeys: chunk.map(String) }), ignoreExtraElements: true, ignoreArrayOrder: false }],
    },
    200,
    { issueChangeLogs: body, nextPageToken: null },
  );
});

// Day-2 delta: a fresh bulkfetch for exactly the stale ids (updated + moved), full history to date.
const day2StaleIds = [...updatedIssues, movedIssue].map((i) => i.id).sort((a, b) => Number(a) - Number(b));
stub(
  "changelog-bulkfetch-day2-delta",
  {
    method: "POST",
    urlPath: `${API}/changelog/bulkfetch`,
    bodyPatterns: [{ equalToJson: JSON.stringify({ issueIdsOrKeys: day2StaleIds }), ignoreExtraElements: true, ignoreArrayOrder: false }],
    requiredState: "day2",
  },
  200,
  { issueChangeLogs: day2StaleIds.map((id) => ({ issueId: id, changeHistories: bulkHistoriesJson(id, DAY2_NOW_MS) })), nextPageToken: null },
);

// --- issue/{id}/changelog: catch-all (empty) + real data for the omitted chunk's issues ----------
stub(
  "issue-changelog-catchall",
  { urlPathPattern: `${API}/issue/[^/]+/changelog`, priority: 10 },
  200,
  { startAt: 0, maxResults: 100, total: 0, histories: [] },
);

const CHANGELOG_PAGE_SIZE = 3;
for (const idStr of omittedChunkIds) {
  const issue = issueById.get(idStr);
  const histories = historiesJson(idStr, REFERENCE_MS);
  const pages = [];
  for (let i = 0; i < Math.max(histories.length, 1); i += CHANGELOG_PAGE_SIZE) pages.push(histories.slice(i, i + CHANGELOG_PAGE_SIZE));
  if (histories.length === 0) pages[0] = [];
  pages.forEach((page, idx) => {
    stub(
      `issue-${issue.id}-changelog-page-${idx + 1}`,
      { urlPath: `${API}/issue/${issue.id}/changelog`, ...startAtPaging(idx, CHANGELOG_PAGE_SIZE) },
      200,
      { startAt: idx * CHANGELOG_PAGE_SIZE, maxResults: CHANGELOG_PAGE_SIZE, total: histories.length, histories: page },
    );
  });
}

// --- issue/{id}/worklog: catch-all (empty) + real data for issues with worklogs (A1 backfill) ----
stub(
  "issue-worklog-catchall",
  { urlPathPattern: `${API}/issue/[^/]+/worklog`, priority: 10 },
  200,
  { startAt: 0, maxResults: 100, total: 0, worklogs: [] },
);

const WORKLOG_PAGE_SIZE = 3;
for (const issue of inScopeIssues) {
  const backfillWorklogs = (worklogsByIssue.get(issue.id) ?? []).filter((w) => Date.parse(w.started) <= REFERENCE_MS);
  if (backfillWorklogs.length === 0) continue;
  const pages = [];
  for (let i = 0; i < backfillWorklogs.length; i += WORKLOG_PAGE_SIZE) pages.push(backfillWorklogs.slice(i, i + WORKLOG_PAGE_SIZE));
  pages.forEach((page, idx) => {
    stub(
      `issue-${issue.id}-worklog-page-${idx + 1}`,
      { urlPath: `${API}/issue/${issue.id}/worklog`, ...startAtPaging(idx, WORKLOG_PAGE_SIZE) },
      200,
      { startAt: idx * WORKLOG_PAGE_SIZE, maxResults: WORKLOG_PAGE_SIZE, total: backfillWorklogs.length, worklogs: page },
    );
  });
}

// --- worklog/updated + worklog/deleted (instance-wide) --------------------------------------------
stub(
  "worklog-updated-started",
  { urlPath: `${API}/worklog/updated`, queryParameters: { since: { matches: ".*" } }, requiredState: "Started" },
  200,
  { since: 0, until: REFERENCE_MS, lastPage: true, values: [] },
);
stub(
  "worklog-updated-started-nosince",
  { urlPath: `${API}/worklog/updated`, queryParameters: { since: { absent: true } }, requiredState: "Started" },
  200,
  { since: 0, until: REFERENCE_MS, lastPage: true, values: [] },
);
const day2UpdatedValues = [...day2NewInScopeWorklogs, ...day2OutOfScopeWorklogs].map((w) => ({ worklogId: Number(w.id), updatedTime: DAY2_NOW_MS }));
stub(
  "worklog-updated-day2",
  { urlPath: `${API}/worklog/updated`, queryParameters: { since: { matches: ".*" } }, requiredState: "day2" },
  200,
  { since: REFERENCE_MS, until: DAY2_NOW_MS, lastPage: true, values: day2UpdatedValues },
);
stub(
  "worklog-updated-day2-nosince",
  { urlPath: `${API}/worklog/updated`, queryParameters: { since: { absent: true } }, requiredState: "day2" },
  200,
  { since: REFERENCE_MS, until: DAY2_NOW_MS, lastPage: true, values: day2UpdatedValues },
);

stub(
  "worklog-deleted-started",
  { urlPath: `${API}/worklog/deleted`, queryParameters: { since: { matches: ".*" } }, requiredState: "Started" },
  200,
  { since: 0, until: REFERENCE_MS, lastPage: true, values: [] },
);
stub(
  "worklog-deleted-started-nosince",
  { urlPath: `${API}/worklog/deleted`, queryParameters: { since: { absent: true } }, requiredState: "Started" },
  200,
  { since: 0, until: REFERENCE_MS, lastPage: true, values: [] },
);
stub(
  "worklog-deleted-day2",
  { urlPath: `${API}/worklog/deleted`, queryParameters: { since: { matches: ".*" } }, requiredState: "day2" },
  200,
  { since: REFERENCE_MS, until: DAY2_NOW_MS, lastPage: true, values: [{ worklogId: Number(day2DeletedWorklog.id), updatedTime: DAY2_NOW_MS }] },
);
stub(
  "worklog-deleted-day2-nosince",
  { urlPath: `${API}/worklog/deleted`, queryParameters: { since: { absent: true } }, requiredState: "day2" },
  200,
  { since: REFERENCE_MS, until: DAY2_NOW_MS, lastPage: true, values: [{ worklogId: Number(day2DeletedWorklog.id), updatedTime: DAY2_NOW_MS }] },
);

// --- worklog/list (POST, chunk of ids -> full worklog bodies) -------------------------------------
const day2ListIds = [...day2NewInScopeWorklogs, ...day2OutOfScopeWorklogs].map((w) => w.id).sort((a, b) => Number(a) - Number(b));
stub(
  "worklog-list-day2-delta",
  {
    method: "POST",
    urlPath: `${API}/worklog/list`,
    bodyPatterns: [{ equalToJson: JSON.stringify({ ids: day2ListIds.map(Number) }), ignoreArrayOrder: true, ignoreExtraElements: true }],
    requiredState: "day2",
  },
  200,
  [...day2NewInScopeWorklogs, ...day2OutOfScopeWorklogs],
);

// --- agile board (paged), board/{id}/configuration, board/{id}/sprint (paged) ---------------------
const BOARD_PAGE_SIZE = 2;
for (let i = 0; i * BOARD_PAGE_SIZE < BOARDS.length; i++) {
  const page = BOARDS.slice(i * BOARD_PAGE_SIZE, (i + 1) * BOARD_PAGE_SIZE);
  const isLast = (i + 1) * BOARD_PAGE_SIZE >= BOARDS.length;
  stub(
    `board-list-page-${i + 1}`,
    { urlPath: `${AGILE}/board`, ...startAtPaging(i, BOARD_PAGE_SIZE) },
    200,
    {
      maxResults: BOARD_PAGE_SIZE,
      startAt: i * BOARD_PAGE_SIZE,
      total: BOARDS.length,
      isLast,
      values: page.map((b) => ({ id: b.id, name: b.name, type: b.type, location: { projectId: Number(b.project.id), projectKey: b.project.key } })),
    },
  );
}

for (const board of BOARDS) {
  const columns = boardColumns(board.project);
  stub(`board-${board.id}-configuration`, { urlPath: `${AGILE}/board/${board.id}/configuration` }, 200, {
    id: board.id,
    name: board.name,
    type: board.type,
    columnConfig: {
      columns: columns.map((c) => ({ name: c.name, statuses: c.statuses.map((s) => ({ id: s.id })) })),
      constraintType: "issueCount",
    },
  });
}

const SPRINT_PAGE_SIZE = 15;
for (const project of SCRUM_PROJECTS) {
  const board = BOARDS.find((b) => b.project === project);
  const sprints = SPRINTS_BY_PROJECT.get(project.key);
  for (let i = 0; i * SPRINT_PAGE_SIZE < sprints.length; i++) {
    const page = sprints.slice(i * SPRINT_PAGE_SIZE, (i + 1) * SPRINT_PAGE_SIZE);
    const isLast = (i + 1) * SPRINT_PAGE_SIZE >= sprints.length;
    stub(
      `board-${board.id}-sprint-page-${i + 1}`,
      { urlPath: `${AGILE}/board/${board.id}/sprint`, ...startAtPaging(i, SPRINT_PAGE_SIZE) },
      200,
      {
        maxResults: SPRINT_PAGE_SIZE,
        startAt: i * SPRINT_PAGE_SIZE,
        total: sprints.length,
        isLast,
        values: page.map((s) => ({
          id: s.id,
          self: `${AGILE}/sprint/${s.id}`,
          state: s.state,
          name: s.name,
          startDate: iso(s.startMs),
          endDate: iso(s.endMs),
          ...(s.state === "closed" ? { completeDate: iso(s.completeAtMs ?? s.endMs) } : {}),
          originBoardId: s.boardId,
          goal: s.goal,
        })),
      },
    );
  }
}

// ===============================================================================================
// expected.json
// ===============================================================================================
const issuesPerProject = {};
for (const project of PROJECTS) {
  issuesPerProject[project.key] = issues.filter((i) => i.project === project).length;
}

const totalChangelogHistories = [...changeHistories.values()].reduce((s, l) => s + l.length, 0);

// In-scope-reachable figures (v0.2.0 plan §12 item 7): the numbers a single backfill SYNC actually
// stores, filtered to the REFERENCE-date cutoff the stub's own backfill mappings use
// (historiesJson/the issue/{id}/worklog mappings above) and to in-scope issues only — UNLIKE
// totalChangelogHistories/worklogIssueCount/worklogTotalCount below, which loop over EVERY project
// (including the out-of-scope SEC project) and, for worklogs, also fold in the day2 scenario's
// additions. Computed here, not hand-copied, so a generator change can never drift silently from
// what JiraSyncPipelineTest actually asserts.
const inScopeChangelogHistories = inScopeIdsAscending.reduce(
  (sum, id) => sum + historiesJson(String(id), REFERENCE_MS).length,
  0,
);
// reopenCount/flaggedCount above loop over EVERY project (including the out-of-scope SEC project),
// same "whole-dataset vs in-scope-reachable" split as inScopeChangelogHistories/inScopeWorklogCount
// below — the data profile (v0.2.0 plan §8/§12 item 9) is computed ONLY over this connection's own
// in-scope norm.* rows, so its own reopen count can only ever match the in-scope-reachable figure.
const inScopeReopenCount = inScopeIssues.filter((i) => i.reopened).length;
const inScopeBackfillWorklogCounts = inScopeIssues.map(
  (issue) => (worklogsByIssue.get(issue.id) ?? []).filter((w) => Date.parse(w.started) <= REFERENCE_MS).length,
);
const inScopeWorklogCount = inScopeBackfillWorklogCounts.reduce((s, n) => s + n, 0);
const inScopeWorklogIssueCount = inScopeBackfillWorklogCounts.filter((n) => n > 0).length;

const expected = {
  referenceDate: REFERENCE_ISO,
  day2Date: DAY2_NOW_ISO,
  cloudId: CLOUD_ID,
  siteHost: SITE_HOST,
  connection: {
    projectKeys: CONNECTION_PROJECT_KEYS,
    outOfScopeProjectKey: "SEC",
    backfillFrom: iso(BACKFILL_START_MS).slice(0, 10),
  },
  issues: {
    totalInScope: inScopeIssues.length,
    perProject: issuesPerProject,
    outOfScopeTotal: issues.filter((i) => i.project.key === "SEC").length,
  },
  changelog: {
    // Whole-dataset (all 5 projects incl. out-of-scope SEC): never asserted directly against a
    // backfill's own stored row count — see inScopeHistories below for that.
    allProjectsHistories: totalChangelogHistories,
    // The in-scope-reachable total a single backfill SYNC actually stores: every in-scope issue's
    // history, whether via a real bulkfetch chunk or the omitted chunk's per-issue fallback.
    inScopeHistories: inScopeChangelogHistories,
    bulkfetchChunkSize: BULK_CHUNK_SIZE,
    bulkfetchChunkCount: bulkChunks.length,
    omittedBulkfetchChunkIndex: OMITTED_CHUNK_INDEX,
    omittedBulkfetchChunkIssueIds: [...omittedChunkIds].sort((a, b) => Number(a) - Number(b)),
    fallbackIssueCount: omittedChunkIds.size,
  },
  worklogs: {
    // Whole-dataset (all 5 projects incl. out-of-scope SEC), and — unlike every other
    // allProjects* figure here — ALSO folding in the 2 day2-scenario in-scope worklog additions
    // (worklogTotalCount/worklogIssueCount are mutated again in the day2 section above): never
    // asserted directly against a backfill's own stored row count — see inScopeCount/
    // inScopeIssueCount below for that.
    allProjectsIssueCount: worklogIssueCount,
    allProjectsTotalCount: worklogTotalCount,
    // The in-scope-reachable total/issue-count a single backfill SYNC actually stores (every
    // in-scope issue's own `issue/{id}/worklog` page, filtered to the REFERENCE-date cutoff —
    // day2's additions are dated AFTER that cutoff, so they're correctly excluded here).
    inScopeCount: inScopeWorklogCount,
    inScopeIssueCount: inScopeWorklogIssueCount,
    outOfScopeDay2FeedCount: day2OutOfScopeWorklogs.length,
    day2NewInScopeCount: day2NewInScopeWorklogs.length,
    day2DeletedWorklogId: day2DeletedWorklog.id,
    day2DeletedWorklogIssueId: day2DeletedWorklogSourceIssue.id,
  },
  reopens: { count: reopenCount, inScopeCount: inScopeReopenCount },
  flagged: { count: flaggedCount },
  sprints: {
    carryOverCount: carryoverCount,
    perProjectSprintCounts: Object.fromEntries(SCRUM_PROJECTS.map((p) => [p.key, SPRINTS_BY_PROJECT.get(p.key).length])),
    // Phase 3 additions (v0.3.0 M1 commit 1):
    completeDateShiftedCount: sprintCompleteDateShiftedCount,
    futureBacklogCount: futureBacklogPlaced,
    entryMovedToPlanningCount: sprintEntryMovedCount,
    entryMovedHistorySplitCount: sprintEntrySplitCount,
    backlogRefinedCount,
    backlogRefinedHistorySplitCount: backlogRefinedSplitCount,
    backlogRefinedSubtaskPulledCount,
  },
  estimates: {
    storyPointsCount,
    originalEstimateCount,
    // Phase 3 additions (v0.3.0 M1 commit 1) — D7's epic own estimate, and the task-side
    // estimate-change/estimated-late population the epic/task accuracy reports (3-5) will read.
    epicStoryPointsCount: epics.filter((e) => e.storyPoints !== null).length,
    taskEstimateChangedAfterStartCount: estimateChangedAfterStartCount,
    taskEstimatedLateCount: estimatedLateCount,
  },
  // Phase 3 additions (v0.3.0 M1 commit 1).
  epicDates: {
    datedEpicCount: epics.length,
    changedCount: epicDatesChangedCount,
  },
  parentMoves: {
    count: parentMoveIssueIds.length,
    issueIds: parentMoveIssueIds.slice().sort((a, b) => Number(a) - Number(b)),
  },
  workCategory: {
    epicCount: epics.filter((e) => e.workCategory).length,
    otherCount: issues.filter((i) => i.type !== ISSUE_TYPE.EPIC && i.workCategory).length,
  },
  worklogTimestamps: {
    createdLaterCount: worklogCreatedLaterCount,
    updatedLaterCount: worklogUpdatedLaterCount,
  },
  workflows: Object.fromEntries(PROJECTS.map((p) => [p.key, p.workflow.map((s) => s.name)])),
  boards: BOARDS.map((b) => ({
    id: b.id,
    projectKey: b.project.key,
    type: b.type,
    columns: boardColumns(b.project).map((c) => ({ name: c.name, statuses: c.statuses.map((s) => s.name) })),
    unmappedStatuses: b.project.boardOmitsStatus ? [b.project.boardOmitsStatus.name] : [],
  })),
  day2: {
    deletedIssueId: deletedIssue.id,
    deletedIssueKey: deletedIssue.key,
    movedIssueId: movedIssue.id,
    movedIssueKeyBefore: movedIssue.key,
    movedFromProjectKey: movedIssue.project.key,
    movedToProjectKey: "SEC",
    updatedIssueIds: updatedIssues.map((i) => i.id),
  },
  users: { count: USERS.length },
  // Phase 3 additions (v0.3.0 M1 commit 1) — the deterministic team roster (D1's dated membership
  // is entered through the API in a later commit; this is the source data for that) and the two
  // golden fixtures computed by the generator from its own timeline (grace 0, 1 SP = 1 MD; see the
  // "Golden fixtures" comment above for the exact rules and their deliberate scope boundary).
  teams: {
    names: TEAMS.map((t) => t.name),
    roster: teamRoster,
  },
  golden: {
    sprint: {
      sprintId: goldenSprint.sprint.id,
      name: goldenSprint.sprint.name,
      projectKey: "FLO",
      startDate: isoDate(goldenSprint.sprint.startMs),
      endDate: isoDate(goldenSprint.sprint.endMs),
      completeDate: isoDate(goldenSprint.sprint.completeAtMs ?? goldenSprint.sprint.endMs),
      // hasNonZeroDelivered is a selection-time helper, not a fixture figure — excluded below.
      ...(({ hasNonZeroDelivered, ...rest }) => rest)(goldenSprint.scope),
    },
    epic: {
      issueId: goldenEpic.id,
      issueKey: goldenEpic.key,
      budgetMd: goldenEpic.storyPoints,
      startDate: isoDate(goldenEpic.epicStartMs),
      dueDate: isoDate(goldenEpic.epicDueMs),
      childSumMd: goldenEpicChildSumMd,
      childCount: goldenEpicChildren.length,
    },
  },
  mappingCount,
};

mkdirSync(path.dirname(EXPECTED_PATH), { recursive: true });
writeFileSync(EXPECTED_PATH, JSON.stringify(expected, null, 2) + "\n");

// ---------------------------------------------------------------------------------------------
// Report on stdout.
// ---------------------------------------------------------------------------------------------
function dirStats(dir) {
  let count = 0;
  let bytes = 0;
  for (const f of readdirSync(dir)) {
    const st = statSync(path.join(dir, f));
    if (st.isFile()) {
      count++;
      bytes += st.size;
    }
  }
  return { count, bytes };
}
const mappingsStats = dirStats(MAPPINGS_DIR);
const filesStats = dirStats(FILES_DIR);
console.log(`expected.json: ${EXPECTED_PATH}`);
console.log(`mappings: ${mappingsStats.count} files, ${(mappingsStats.bytes / 1024 / 1024).toFixed(2)} MiB`);
console.log(`__files:  ${filesStats.count} files, ${(filesStats.bytes / 1024 / 1024).toFixed(2)} MiB`);
console.log(`total in-scope issues: ${inScopeIssues.length}, changelog histories: ${totalChangelogHistories}`);
