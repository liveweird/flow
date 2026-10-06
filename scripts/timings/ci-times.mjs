#!/usr/bin/env node
// GitHub Actions job durations for the `ci` (and `e2e`) workflows, with trend and budget flags.
// Read-only: `gh run list` / `gh run view` only. Needs an authenticated `gh` and node >= 18.
// Budgets come from budgets.json (the one place). See .claude/docs/build-times.md.
import { execFile } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs, promisify } from "node:util";
import {
  ANALYSIS_ALIGN,
  ANALYSIS_HEADER,
  BUDGETS,
  analyse,
  analysisRow,
  budgetFor,
  fmtDur,
  table,
} from "./lib.mjs";

const execFileAsync = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));

const USAGE = `Usage: node scripts/timings/ci-times.mjs [options]
  --limit N          successful runs per workflow to analyse (default 30)
  --branch NAME      only runs on this branch (e.g. master)
  --workflows LIST   comma-separated workflow names (default ci,e2e)
  --tsv              print the raw rows (created, sha, event, branch, job, seconds) instead of the table
  --steps JOB        also print the slowest steps of JOB in its latest successful run
  --repo OWNER/NAME  passed to gh as -R (default: the repository of the current directory)
  -h, --help`;

const { values: opts } = parseArgs({
  options: {
    limit: { type: "string", default: "30" },
    branch: { type: "string" },
    workflows: { type: "string", default: "ci,e2e" },
    tsv: { type: "boolean", default: false },
    steps: { type: "string" },
    repo: { type: "string" },
    help: { type: "boolean", short: "h", default: false },
  },
});
if (opts.help) {
  console.log(USAGE);
  process.exit(0);
}
const limit = Number.parseInt(opts.limit, 10);
if (!Number.isInteger(limit) || limit < 1 || limit > 500) {
  console.error("--limit must be an integer between 1 and 500");
  process.exit(2);
}

async function gh(args) {
  const full = opts.repo ? [...args, "-R", opts.repo] : args;
  try {
    const { stdout } = await execFileAsync("gh", full, { maxBuffer: 256 * 1024 * 1024 });
    return JSON.parse(stdout);
  } catch (err) {
    console.error(`gh ${args.slice(0, 2).join(" ")} failed: ${String(err.stderr || err.message).trim()}`);
    process.exit(1);
  }
}

const RUN_FIELDS = "databaseId,number,status,conclusion,headBranch,headSha,event,createdAt,startedAt,updatedAt";

function listRuns(workflow, extra) {
  const args = ["run", "list", "--workflow", workflow, "--json", RUN_FIELDS, ...extra];
  if (opts.branch) args.push("--branch", opts.branch);
  return gh(args);
}

async function jobsOf(run) {
  const { jobs } = await gh(["run", "view", String(run.databaseId), "--json", "jobs"]);
  return jobs;
}

/** Run `fn` over `items` with at most `n` in flight; results keep input order. */
async function pool(items, n, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(n, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i]);
      }
    }),
  );
  return out;
}

const seconds = (from, to) => (Date.parse(to) - Date.parse(from)) / 1000;

/** timeout-minutes per job, read from the workflow file (a tiny indentation-based scan). */
function workflowTimeouts(workflow) {
  const file = join(here, "..", "..", ".github", "workflows", `${workflow}.yml`);
  const found = new Set(BUDGETS.historicTimeoutMinutes.map((m) => m * 60));
  if (!existsSync(file)) return found;
  let inJobs = false;
  for (const line of readFileSync(file, "utf8").split("\n")) {
    if (/^jobs:/.test(line)) inJobs = true;
    else if (inJobs) {
      const m = /^ {4}timeout-minutes:\s*(\d+)/.exec(line);
      if (m) found.add(Number(m[1]) * 60);
    }
  }
  return found;
}

async function collect(workflow) {
  const successful = await listRuns(workflow, ["--status", "success", "--limit", String(limit)]);
  if (successful.length === 0) return { workflow, successful, rows: [], others: [] };
  const oldest = successful.map((r) => r.createdAt).sort()[0];
  const recent = await listRuns(workflow, ["--limit", String(limit * 2)]);
  const others = recent.filter((r) => r.conclusion !== "success" && r.createdAt >= oldest);

  const jobLists = await pool(successful, 8, jobsOf);
  const rows = [];
  const walls = [];
  successful.forEach((run, i) => {
    const ok = jobLists[i].filter((j) => j.conclusion === "success" && j.startedAt && j.completedAt);
    for (const j of ok) {
      rows.push({ run, job: j.name, seconds: seconds(j.startedAt, j.completedAt) });
    }
    if (workflow === "ci" && ok.length > 0) {
      const start = Math.min(...ok.map((j) => Date.parse(j.startedAt)));
      const end = Math.max(...ok.map((j) => Date.parse(j.completedAt)));
      walls.push({ run, job: "(run wall)", seconds: (end - start) / 1000 });
    }
  });

  // Non-success runs: find timed-out jobs (a job stopped right at a timeout-minutes value).
  const timeouts = workflowTimeouts(workflow);
  // A cancelled run is a concurrency cancellation (a newer push superseded it), never a timeout:
  // a job that hits timeout-minutes ends its run as a failure, so cancelled runs are skipped.
  const finished = others.filter((r) => r.status === "completed" && r.conclusion !== "cancelled");
  const otherJobs = await pool(finished, 8, jobsOf);
  finished.forEach((run, i) => {
    run.timedOut = otherJobs[i]
      .filter((j) => (j.conclusion === "cancelled" || j.conclusion === "timed_out") && j.startedAt && j.completedAt)
      .map((j) => ({ job: j.name, seconds: seconds(j.startedAt, j.completedAt), conclusion: j.conclusion }))
      .filter(
        (j) => j.conclusion === "timed_out" || [...timeouts].some((t) => j.seconds >= t - 45 && j.seconds <= t + 90),
      );
  });
  return { workflow, successful, rows, walls, others };
}

function tsv(all) {
  const lines = ["created\tsha\tevent\tbranch\tjob\tseconds"];
  const rows = all.flatMap((c) => c.rows).sort((a, b) => a.run.createdAt.localeCompare(b.run.createdAt));
  for (const { run, job, seconds: s } of rows) {
    lines.push([run.createdAt, run.headSha.slice(0, 7), run.event, run.headBranch, job, Math.round(s)].join("\t"));
  }
  return lines.join("\n");
}

function report(c) {
  const out = [];
  const span = c.successful.map((r) => r.createdAt.slice(0, 10)).sort();
  out.push(
    `${c.workflow}: ${c.successful.length} successful run(s)${opts.branch ? ` on ${opts.branch}` : ""}` +
      (span.length ? `, ${span[0]} .. ${span[span.length - 1]}` : ""),
  );
  if (c.successful.length === 0) return out.join("\n");

  const byJob = new Map();
  for (const r of [...c.rows, ...c.walls].sort((a, b) => a.run.createdAt.localeCompare(b.run.createdAt))) {
    if (!byJob.has(r.job)) byJob.set(r.job, []);
    byJob.get(r.job).push(r.seconds);
  }
  const analysed = [...byJob].map(([job, samples]) => {
    const budget = budgetFor("ci", job);
    return { job, budget, a: analyse(samples, budget) };
  });
  analysed.sort((x, y) => y.a.recent - x.a.recent);
  out.push(table(ANALYSIS_HEADER, analysed.map((x) => analysisRow(x.job, x.a, x.budget)), ANALYSIS_ALIGN));

  const counted = {};
  for (const r of c.others) counted[r.conclusion || r.status] = (counted[r.conclusion || r.status] || 0) + 1;
  const timedOut = c.others.filter((r) => r.timedOut?.length);
  out.push(
    `not counted (same window): ${
      Object.keys(counted).length
        ? Object.entries(counted)
            .map(([k, v]) => `${v} ${k}`)
            .join(", ")
        : "none"
    }`,
  );
  for (const r of timedOut) {
    for (const j of r.timedOut) {
      out.push(
        `  TIMEOUT? run #${r.number} ${r.headSha.slice(0, 7)} (${r.event}, ${r.headBranch}): job ${j.job} stopped after ${fmtDur(j.seconds)} (${j.conclusion})`,
      );
    }
  }
  return out.join("\n");
}

async function stepsReport(all) {
  const found = all
    .flatMap((c) => c.rows.map((r) => ({ ...r, workflow: c.workflow })))
    .filter((r) => r.job === opts.steps)
    .sort((a, b) => b.run.createdAt.localeCompare(a.run.createdAt))[0];
  if (!found) return `--steps: no successful run of job "${opts.steps}"`;
  const jobs = await jobsOf(found.run);
  const job = jobs.find((j) => j.name === opts.steps);
  const steps = job.steps
    .filter((s) => s.startedAt && s.completedAt)
    .map((s) => ({ name: s.name, seconds: seconds(s.startedAt, s.completedAt) }))
    .sort((a, b) => b.seconds - a.seconds)
    .slice(0, 12);
  const total = seconds(job.startedAt, job.completedAt);
  // Step budgets live under `ci-steps` in budgets.json, keyed "<job>/<step name>" (steps without a row show "-").
  const flag = (b, seconds) => (!b ? "" : seconds > b.alarm ? "OVER ALARM" : seconds > b.target ? "over target" : "");
  return [
    `slowest steps of ${opts.steps}, run #${found.run.number} ${found.run.headSha.slice(0, 7)} (job ${fmtDur(total)})`,
    table(
      ["step", "time", "share", "target", "alarm", "flag"],
      steps.map((s) => {
        const b = budgetFor("ci-steps", `${opts.steps}/${s.name}`);
        return [
          s.name,
          fmtDur(s.seconds),
          `${Math.round((s.seconds / total) * 100)}%`,
          b ? fmtDur(b.target) : "-",
          b ? fmtDur(b.alarm) : "-",
          flag(b, s.seconds),
        ];
      }),
      ["l", "r", "r", "r", "r", "l"],
    ),
  ].join("\n");
}

const all = [];
for (const workflow of opts.workflows.split(",").map((w) => w.trim()).filter(Boolean)) {
  all.push(await collect(workflow));
}

if (opts.tsv) {
  console.log(tsv(all));
} else {
  console.log(all.map(report).join("\n\n"));
  if (opts.steps) console.log(`\n${await stepsReport(all)}`);
}
