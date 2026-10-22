// Regenerates server/cds/classlist.txt, the class list the Dockerfile dumps its AppCDS archive from
// (build-times.md WHY 27). The list is what a REAL run of the image loads: this script boots the image against a throwaway
// compose project (own containers, volume and ports, the committed WireMock Jira stub), drives a representative workload
// through the API (login, admin lists, a data source SYNC, the metrics config, every report, DERIVE, password reset mail),
// stops the app gracefully and keeps the JVM's `-XX:DumpLoadedClassList` output in canonical form (sorted, deduplicated, no ids).
// Classes missing from the list are simply loaded from the jars at runtime, so a stale list only costs startup time and
// ~metaspace, never correctness; regenerate when CdsClassListTest warns, before a release, or after a big dependency/code move.
//
//   docker compose build app            # tags the image `flow-app`
//   node scripts/cds-classlist.mjs [--image flow-app] [--keep]     # --keep leaves the project up for inspection
//
// Needs Docker, Node >= 24 and free loopback ports 8588 / 5939 / 8532 / 8598. Never touches another compose project.
import { execFileSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const image = option("--image", "flow-app");
const project = "flow-cds-train";
const base = "http://127.0.0.1:8588";
const work = mkdtempSync(join(tmpdir(), "flow-cds-"));
const cdsDir = join(work, "cds");
const override = join(work, "compose.override.yaml");

mkdirSync(cdsDir);
chmodSync(cdsDir, 0o777); // the image runs as uid 10001
writeFileSync(
  override,
  `services:
  postgres:
    container_name: ${project}-postgres
    ports: !override
      - "127.0.0.1:5939:5432"
  mailpit:
    container_name: ${project}-mailpit
    ports: !override
      - "127.0.0.1:8532:8025"
  jira-stub:
    container_name: ${project}-jira-stub
    ports: !override
      - "127.0.0.1:8598:8080"
  app:
    image: ${image}
    pull_policy: never
    container_name: ${project}-app
    ports: !override
      - "127.0.0.1:8588:8084"
    volumes:
      - ${cdsDir}:/cds
    environment:
      MAIL_APP_URL: ${base}
      INGEST_DERIVE_QUIET_SECONDS: "1"
      INGEST_SCHEDULER_TICK_SECONDS: "5"
      INGEST_SCOPE_RULES: "off"
      # Record every class the JVM loads; the image's own archive is switched off so the list is the full set, not what it served.
      JAVA_OPTS: "-Xmx512m -XX:DumpLoadedClassList=/cds/classes.lst"
      SERVER_OPTS: ""
`,
);

const compose = (...a) => execFileSync("docker", ["compose", "-p", project, "-f", join(root, "docker-compose.yaml"), "-f", override, ...a], { stdio: ["ignore", "inherit", "inherit"] });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (m) => console.log(`[cds-classlist] ${m}`);

let token = "";
const call = async (method, path, body) => {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* HTML or empty body */ }
  return { status: res.status, json };
};
const must = (res, what) => {
  if (res.status >= 300) throw new Error(`${what} answered ${res.status}: ${JSON.stringify(res.json)?.slice(0, 300)}`);
  return res.json;
};
const until = async (what, probe, seconds) => {
  for (let i = 0; i < seconds; i++) {
    const v = await probe();
    if (v) return v;
    await sleep(1000);
  }
  throw new Error(`timed out waiting for ${what}`);
};
// Enqueue a job and wait until it (or a newer one of its kind) SUCCEEDED, then until the data source has no job open:
// a SYNC chains PROCESS/PROFILE/DERIVE jobs, so "idle" must hold twice in a row before the next step.
const runJob = async (ds, kind) => {
  const queued = await call("POST", `/api/v1/data-sources/${ds}/sync-jobs`, { kind });
  const jobId = queued.json?.job?.id;
  if (jobId === undefined) throw new Error(`enqueuing ${kind} answered ${queued.status}`);
  await until(`${kind} #${jobId}`, async () => {
    const status = (await call("GET", `/api/v1/data-sources/${ds}/status`)).json;
    const last = status?.lastJobs?.[kind];
    if (last?.status === "FAILED" || last?.status === "CANCELLED") throw new Error(`${kind} ended ${last.status}`);
    return last?.status === "SUCCEEDED" && last.id >= jobId;
  }, 240);
  await until("no open job", async () => {
    const open = async () => (await call("GET", `/api/v1/data-sources/${ds}/status`)).json?.currentJob != null;
    if (await open()) return false;
    await sleep(4000);
    return !(await open());
  }, 120);
};

let ok = false;
try {
  compose("up", "-d", "postgres", "mailpit", "jira-stub");
  compose("up", "-d", "--no-deps", "app");
  await until("the app to answer /health", async () => (await fetch(`${base}/api/v1/health`).catch(() => null))?.ok, 90);

  log("workload: auth");
  await call("POST", "/api/v1/login", { email: "admin@flow.local", password: "wrong-password-0" }); // the failure path
  const session = must(await call("POST", "/api/v1/login", { email: "admin@flow.local", password: "changeme" }), "login");
  token = session.token;
  await fetch(`${base}/`); // the SPA catch-all

  log("workload: team + user + data source + SYNC");
  const team = must(await call("POST", "/api/v1/teams", { name: "cds-training-team" }), "creating the team").id;
  const user = must(await call("POST", "/api/v1/flow-users", { name: "CDS Training", email: "cds-training@flow.local", password: "cds-training-pass-1" }), "creating the user").id;
  await call("POST", "/api/v1/password-reset", { email: "cds-training@flow.local" }); // renders and sends the mail (Mailpit)
  const ds = must(
    await call("POST", "/api/v1/data-sources", {
      name: "cds-training-source",
      syncIntervalMinutes: 60,
      jira: { siteUrl: "https://flow-e2e.atlassian.net", email: "svc-account@flow-e2e.example.com", apiToken: "e2e-fake-api-token", projectKeys: ["FLO", "PLT", "GTM", "OPS"] },
    }),
    "creating the data source",
  ).id;
  await runJob(ds, "SYNC");

  log("workload: metrics config + DERIVE");
  const options = (await call("GET", `/api/v1/data-sources/${ds}/metrics-config/options`)).json;
  const cfg = (await call("GET", `/api/v1/data-sources/${ds}/metrics-config`)).json;
  const board = options.boards.find((b) => b.projectKey === "FLO")?.boardId;
  const put = await call("PUT", `/api/v1/data-sources/${ds}/metrics-config`, {
    statusStages: cfg.statusStages,
    fields: cfg.fields,
    domains: cfg.domains,
    boards: [{ boardId: board, teamId: team }],
    activityTypes: cfg.activityTypes,
    workCategories: cfg.workCategories,
  });
  if (put.status !== 204) throw new Error(`mapping the FLO board answered ${put.status}`);
  await runJob(ds, "DERIVE"); // the mapping's own DERIVE may run too; this one is the explicit second

  log("workload: reads");
  const reports = ["home", "commitments", "throughput", "sprint-consistency", "task-estimation-accuracy", "task-estimates", "epic-estimation-accuracy", "estimate-adjustments", "cycle-time", "wip", "backlog", "aging-wip", "blocked-time", "epic-progress", "data-quality", "costs", "deep-dive", "compare-people", "reported-time-ratio", "filters"];
  const paths = [
    "/api/v1/flow-users", "/api/v1/teams", "/api/v1/public-holidays", "/api/v1/metrics-settings", "/api/v1/jira-users", "/api/v1/jira-teams", "/api/v1/data-sources", "/api/v1/ready",
    `/api/v1/data-sources/${ds}/status`, `/api/v1/data-sources/${ds}/profile`, `/api/v1/teams/${team}/capacity`, `/api/v1/teams/${team}/jira-memberships`,
    ...reports.flatMap((r) => [`/api/v1/reports/${r}`, `/api/v1/reports/${r}?teamId=${team}`, `/api/v1/reports/${r}?teamId=${team}&connectionId=${ds}`]),
  ];
  const statuses = {};
  for (let round = 0; round < 2; round++) {
    for (const p of paths) {
      const { status } = await call("GET", p);
      statuses[status] = (statuses[status] ?? 0) + 1;
    }
  }
  log(`reads answered ${JSON.stringify(statuses)}`);
  await call("DELETE", `/api/v1/flow-users/${user}`);
  must(await call("POST", "/api/v1/refresh", { refreshToken: session.refreshToken }), "refreshing the session");
  await call("POST", "/api/v1/logout", {});

  log("stopping the app so the JVM flushes the class list");
  compose("stop", "-t", "30", "app");
  const lines = readFileSync(join(cdsDir, "classes.lst"), "utf8").split("\n");
  const unique = (xs) => [...new Set(xs)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)); // code-point order, locale-free
  const plain = lines.filter((l) => l && !l.startsWith("#") && !l.startsWith("@")).map((l) => l.replace(/ id: \d+$/, ""));
  const proxies = lines.filter((l) => l.startsWith("@lambda-proxy"));
  const invokers = lines.filter((l) => l.startsWith("@lambda-form-invoker"));
  const header = ["# AppCDS class list (build-times.md WHY 27), generated by scripts/cds-classlist.mjs from a real run of the image:", "# classes, then lambda proxies, then lambda-form invokers, each sorted. Regenerate it, never edit it."];
  const out = [...header, ...unique(plain), ...unique(proxies), ...unique(invokers)].join("\n") + "\n";
  writeFileSync(join(root, "server", "cds", "classlist.txt"), out);
  log(`wrote server/cds/classlist.txt: ${unique(plain).length} classes, ${unique(proxies).length} lambda proxies, ${unique(invokers).length} invokers`);
  ok = true;
} finally {
  if (!flag("--keep")) {
    try { compose("down", "-v", "--remove-orphans"); } catch { /* best effort: the project is throwaway */ }
    rmSync(work, { recursive: true, force: true });
  } else {
    log(`--keep: project ${project} is still up (app ${base}); remove it with: docker compose -p ${project} down -v`);
  }
}
process.exit(ok ? 0 : 1);
