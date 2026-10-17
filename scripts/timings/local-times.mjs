#!/usr/bin/env node
// Local build/test timing: record a command's wall time, report trends, and rank slow JUnit tests.
// node built-ins only (node >= 18). Budgets come from budgets.json. See .claude/docs/build-times.md.
//
//   record <activity> -- <command...>   run the command, append a row to the shared TSV, exit with its code
//   report [--activity NAME] [--tsv]    latest / median of last 5 / previous 5 / trend vs budgets
//   slow-tests [dir] [options]          top classes and test cases from JUnit XML
import { spawn, execFileSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { constants as osConstants, cpus, homedir } from "node:os";
import { basename, dirname, join } from "node:path";
import { parseArgs } from "node:util";
import {
  ANALYSIS_ALIGN,
  BUDGETS,
  ANALYSIS_HEADER,
  analyse,
  analysisRow,
  budgetByKey,
  budgetFor,
  budgetCell,
  fmtDur,
  table,
} from "./lib.mjs";

const HEADER = ["time", "sha", "branch", "worktree", "activity", "seconds", "exit", "cores"];

/** The shared TSV: outside the repo, one file for every worktree. */
function timingsFile(override) {
  if (override) return override;
  if (process.env.FLOW_TIMINGS_FILE) return process.env.FLOW_TIMINGS_FILE;
  const base = process.env.XDG_DATA_HOME || join(homedir(), ".local", "share");
  return join(base, "flow", "timings.tsv");
}

function git(...args) {
  try {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return "-";
  }
}

const clean = (v) => String(v).replace(/[\t\r\n]+/g, " ");

const USAGE = `Usage: node scripts/timings/local-times.mjs <command>
  record <activity> -- <command...>
      Run the command, measure wall time, append a row (time, sha, branch, worktree, activity,
      seconds, exit code, cores) to \${XDG_DATA_HOME:-~/.local/share}/flow/timings.tsv (override with
      FLOW_TIMINGS_FILE or --file), exit with the command's code. Activities are free-form; the ones
      with budgets: server-build, server-test, web-gates, e2e-static, e2e-playwright (see budgets.json).
      e.g.  record server-build -- ./gradlew cleanTest build
            record web-gates -- sh -c 'cd web && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage && npm run build'
  report [--activity NAME] [--file PATH] [--tsv]
      Per activity (successful runs only): latest, median of the last 5, the 5 before, min/max,
      trend and budget flags. --tsv prints the raw file.
  slow-tests [dir] [--top-classes 15] [--top-tests 20] [--wall SECONDS] [--budget ci.server]
             [--forks N [--weights PATH]] [--summary-file PATH] [--allow-empty]
      Parse JUnit XML (default server/build/test-results/test): suite span (earliest start -> latest
      end), sum of class times, the Gradle-reported total if --wall is passed, the slowest classes
      and test cases with their share. --budget compares the test time with that budget's alarm
      (a ::warning:: annotation under GitHub Actions). --summary-file appends Markdown to PATH.
      --forks N adds a "fork balance" block: span vs the ideal sum/N, the fixture costs the suites
      printed (fixture-cost lines in system-out), and the classes whose weight drifted from the
      weights file (--weights, default server/test-weights.tsv; measured times scaled to the file's
      pace first, then more than max(3 s, 100 % of the smaller of the two weights) apart, or missing). It warns (::warning:: under GitHub Actions,
      exit code stays 0) when span > ideal x (1 + maxExcessRatio) + minExcessSeconds (budgets.json forkBalance).
  test-weights [dir] [--out server/test-weights.tsv] [--force]
      Write the per-class weights (suite time minus the fixture-cost seconds the suite printed, rounded
      to 0.1 s) of a full run in dir (default server/build/test-results/test) as a TSV with a comment
      header. Refuses a run with fewer than 100 suites, or with a failed suite, unless --force.`;

// ---------------------------------------------------------------- record

function record(argv) {
  const dd = argv.indexOf("--");
  const head = dd < 0 ? argv : argv.slice(0, dd);
  const command = dd < 0 ? [] : argv.slice(dd + 1);
  const { values, positionals } = parseArgs({
    args: head,
    options: { file: { type: "string" } },
    allowPositionals: true,
  });
  if (positionals.length !== 1 || command.length === 0) {
    console.error("record: expected  record <activity> -- <command...>");
    process.exit(2);
  }
  const activity = clean(positionals[0]);
  const file = timingsFile(values.file);
  const started = process.hrtime.bigint();
  const child = spawn(command[0], command.slice(1), { stdio: "inherit" });
  // Ctrl-C reaches the child through the terminal's process group; ignoring SIGINT here (instead of
  // dying) lets us still record the interrupted run. SIGTERM is forwarded only while the child lives.
  process.on("SIGINT", () => {});
  process.on("SIGTERM", () => {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGTERM");
  });
  child.on("error", (err) => {
    console.error(`record: cannot run ${command[0]}: ${err.message}`);
    finish(127);
  });
  child.on("exit", (code, signal) => finish(code ?? 128 + (osConstants.signals[signal] ?? 1)));

  let done = false;
  function finish(code) {
    if (done) return;
    done = true;
    const seconds = Number(process.hrtime.bigint() - started) / 1e9;
    const top = git("rev-parse", "--show-toplevel");
    const row = [
      new Date().toISOString(),
      git("rev-parse", "--short", "HEAD"),
      git("rev-parse", "--abbrev-ref", "HEAD"),
      top === "-" ? basename(process.cwd()) : basename(top),
      activity,
      seconds.toFixed(2),
      code,
      cpus().length,
    ].map(clean);
    mkdirSync(dirname(file), { recursive: true });
    try {
      writeFileSync(file, `${HEADER.join("\t")}\n`, { flag: "wx" }); // only the first writer creates the header
    } catch (err) {
      if (err.code !== "EEXIST") throw err;
    }
    appendFileSync(file, `${row.join("\t")}\n`);
    const budget = budgetFor("local", activity);
    let verdict = "";
    if (budget && code === 0) {
      verdict =
        seconds > budget.alarm
          ? `  OVER ALARM (${fmtDur(budget.alarm)})`
          : seconds > budget.target
            ? `  over target (${fmtDur(budget.target)})`
            : "  within target";
    }
    console.error(`[timing] ${activity}: ${fmtDur(seconds)} exit ${code}${verdict}  -> ${file}`);
    process.exit(code);
  }
}

// ---------------------------------------------------------------- report

function readRows(file) {
  if (!existsSync(file)) return [];
  const [, ...lines] = readFileSync(file, "utf8").split("\n").filter(Boolean);
  return lines.map((l) => {
    const [time, sha, branch, worktree, activity, seconds, exit, cores] = l.split("\t");
    return { time, sha, branch, worktree, activity, seconds: Number(seconds), exit: Number(exit), cores };
  });
}

function report(argv) {
  const { values } = parseArgs({
    args: argv,
    options: { activity: { type: "string" }, file: { type: "string" }, tsv: { type: "boolean", default: false } },
  });
  const file = timingsFile(values.file);
  if (values.tsv) {
    process.stdout.write(existsSync(file) ? readFileSync(file, "utf8") : `${HEADER.join("\t")}\n`);
    return;
  }
  const rows = readRows(file).filter((r) => !values.activity || r.activity === values.activity);
  if (rows.length === 0) {
    console.log(`no timings recorded yet in ${file} (use: record <activity> -- <command...>)`);
    return;
  }
  const failed = new Map();
  const byActivity = new Map();
  for (const r of rows.sort((a, b) => a.time.localeCompare(b.time))) {
    if (r.exit !== 0) {
      failed.set(r.activity, (failed.get(r.activity) ?? 0) + 1);
      continue;
    }
    if (!byActivity.has(r.activity)) byActivity.set(r.activity, []);
    byActivity.get(r.activity).push(r.seconds);
  }
  const out = [...byActivity].map(([name, samples]) => {
    const budget = budgetFor("local", name);
    return analysisRow(name, analyse(samples, budget), budget);
  });
  console.log(`local timings: ${file}`);
  console.log(table(ANALYSIS_HEADER, out, ANALYSIS_ALIGN));
  if (failed.size > 0) {
    console.log(`not counted (non-zero exit): ${[...failed].map(([k, v]) => `${k} x${v}`).join(", ")}`);
  }
}

// ---------------------------------------------------------------- slow-tests

const ENTITIES = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&apos;": "'" };
const decode = (s) =>
  s.replace(/&(?:amp|lt|gt|quot|apos);|&#(\d+);|&#x([0-9a-fA-F]+);/g, (m, dec, hex) =>
    dec ? String.fromCodePoint(Number(dec)) : hex ? String.fromCodePoint(parseInt(hex, 16)) : ENTITIES[m],
  );

function attrs(tag) {
  const out = {};
  for (const m of tag.matchAll(/([\w:.-]+)="([^"]*)"/g)) out[m[1]] = decode(m[2]);
  return out;
}

/**
 * The `fixture-cost<TAB><name><TAB><millis>` lines a shared test fixture prints once per JVM fork (Gradle puts
 * them in the running suite's system-out), as {name, seconds}. Read from the raw XML, before the CDATA is stripped.
 */
function fixtureCosts(xml) {
  return [...xml.matchAll(/(?:^|<!\[CDATA\[)fixture-cost\t([\w.-]+)\t(\d+)[ \r]*$/gm)].map((m) => ({
    name: m[1],
    seconds: Number(m[2]) / 1000,
  }));
}

/** Suites and test cases from one JUnit XML file (system-out/err CDATA stripped first). */
function parseJUnit(xml) {
  const stripped = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, "");
  const suite = /<testsuite\b[^>]*>/.exec(stripped);
  if (!suite) return undefined;
  const s = attrs(suite[0]);
  const cases = [...stripped.matchAll(/<testcase\b[^>]*>/g)].map((m) => attrs(m[0]));
  return {
    name: s.name,
    timestamp: s.timestamp ? Date.parse(s.timestamp) : undefined,
    time: Number(s.time),
    failed: Number(s.failures ?? 0) + Number(s.errors ?? 0),
    costs: fixtureCosts(xml),
    tests: cases.map((c) => ({ cls: c.classname ?? s.name, name: c.name, time: Number(c.time) })),
  };
}

const shortClass = (name) => name.replace(/^ch\.nokillswit\./, "");
const pct = (part, whole) => (whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "-");

function positiveInt(flag, text) {
  if (!/^\d+$/.test(text) || Number(text) < 1) {
    console.error(`slow-tests: ${flag} must be a positive integer, got "${text}"`);
    process.exit(2);
  }
  return Number(text);
}

function loadSuites(dir) {
  const files = existsSync(dir) && statSync(dir).isDirectory() ? readdirSync(dir).filter((f) => f.endsWith(".xml")) : [];
  return files.map((f) => parseJUnit(readFileSync(join(dir, f), "utf8"))).filter(Boolean);
}

const DEFAULT_WEIGHTS = "server/test-weights.tsv";
const round1 = (n) => Math.round(n * 10) / 10;

/** Suite time minus the fixture-cost seconds the suite itself paid, rounded to 0.1 s (never negative). */
const suiteWeight = (s) => round1(Math.max(0, s.time - s.costs.reduce((a, c) => a + c.seconds, 0)));

/** The most suites running at the same instant (a suite ending at t does not overlap one starting at t). */
function maxConcurrent(suites) {
  const events = suites
    .filter((s) => s.timestamp !== undefined)
    .flatMap((s) => [[s.timestamp, 1], [s.timestamp + s.time * 1000, -1]])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let now = 0;
  let max = 0;
  for (const [, delta] of events) {
    now += delta;
    max = Math.max(max, now);
  }
  return max;
}

function readWeights(file) {
  const weights = new Map();
  for (const line of readFileSync(file, "utf8").split("\n")) {
    if (!line.trim() || line.startsWith("#")) continue;
    const [name, seconds] = line.split("\t");
    if (name && Number.isFinite(Number(seconds))) weights.set(name, Number(seconds));
  }
  return weights;
}

/** The "fork balance" block: lines for the console / summary file, and whether the span is out of balance. */
function forkBalance(suites, { forks, span, wall, weightsFile, weightsExplicit }) {
  const sum = suites.reduce((a, s) => a + s.time, 0);
  const ideal = sum / forks;
  const lines = [];
  if (span === undefined) return { lines: ["no suite timestamps: fork balance not computed"], warn: undefined };
  const excess = span - ideal;
  lines.push(
    `forks ${forks}: sum of suite time ${fmtDur(sum)}, span ${fmtDur(span)}, ideal (sum / ${forks}) ${fmtDur(ideal)}`,
    `excess over ideal: ${fmtDur(excess)} (${pct(excess, ideal)})`,
    `${wall === undefined ? "span" : "Gradle wall"} / sum: ${((wall ?? span) / sum).toFixed(3)}`,
  );
  const costs = new Map();
  for (const c of suites.flatMap((s) => s.costs)) costs.set(c.name, [...(costs.get(c.name) ?? []), c.seconds]);
  lines.push(
    costs.size === 0
      ? "fixture costs: none printed (no fixture-cost lines in system-out)"
      : `fixture costs: ${[...costs].map(([n, v]) => `${n} ${round1(v.reduce((a, b) => a + b, 0) / v.length)} s x${v.length}`).join(", ")}`,
  );
  if (!existsSync(weightsFile)) {
    lines.push(`weights drift: ${weightsFile} not found${weightsExplicit ? "" : " (default; pass --weights PATH)"}, skipped`);
  } else {
    const weights = readWeights(weightsFile);
    // A slower or faster machine scales every class alike, and only the relative weights matter for the plan:
    // compare at the file's pace (measured x the file/measured ratio over the classes both sides know).
    const both = suites.filter((s) => weights.has(s.name));
    const measuredSum = both.reduce((a, s) => a + suiteWeight(s), 0);
    const pace = measuredSum > 0 ? both.reduce((a, s) => a + weights.get(s.name), 0) / measuredSum : 1;
    const drift = [];
    for (const s of suites) {
      const measured = suiteWeight(s) * pace;
      const known = weights.get(s.name);
      if (known === undefined) drift.push({ name: s.name, measured, known, diff: measured });
      else if (Math.abs(measured - known) > Math.max(3, Math.min(measured, known))) {
        drift.push({ name: s.name, measured, known, diff: Math.abs(measured - known) });
      }
    }
    drift.sort((a, b) => b.diff - a.diff);
    const stale = [...weights.keys()].filter((n) => !suites.some((s) => s.name === n)).length;
    lines.push(
      `weights drift vs ${weightsFile} (at the file's pace, x${round1(pace)}): ${drift.length} of ${suites.length} classes${stale ? `, ${stale} listed but not run` : ""}` +
        (drift.length ? " (top 5)" : ""),
    );
    for (const d of drift.slice(0, 5)) {
      lines.push(
        `  ${shortClass(d.name)}: measured ${d.measured.toFixed(1)} s at the file's pace, ${d.known === undefined ? "missing from the file" : `file ${d.known.toFixed(1)} s`}`,
      );
    }
  }
  const { maxExcessRatio, minExcessSeconds } = BUDGETS.forkBalance;
  const limit = ideal * (1 + maxExcessRatio) + minExcessSeconds;
  const warn =
    span > limit
      ? `fork balance: span ${fmtDur(span)} exceeds ideal ${fmtDur(ideal)} by ${fmtDur(excess)} (${pct(excess, ideal)}); limit ${fmtDur(limit)} (forkBalance in budgets.json, .claude/docs/build-times.md WHY 18)`
      : undefined;
  return { lines, warn };
}

function slowTests(argv) {
  const { values, positionals } = parseArgs({
    args: argv,
    options: {
      "top-classes": { type: "string", default: "15" },
      "top-tests": { type: "string", default: "20" },
      wall: { type: "string" },
      budget: { type: "string" },
      "summary-file": { type: "string" },
      "allow-empty": { type: "boolean", default: false },
      forks: { type: "string" },
      weights: { type: "string" },
    },
    allowPositionals: true,
  });
  const dir = positionals[0] ?? "server/build/test-results/test";
  const forks = values.forks === undefined ? undefined : positiveInt("--forks", values.forks);
  const topClasses = positiveInt("--top-classes", values["top-classes"]);
  const topTests = positiveInt("--top-tests", values["top-tests"]);
  if (values.wall !== undefined && !Number.isFinite(Number(values.wall))) {
    console.error("slow-tests: --wall must be a number of seconds");
    process.exit(2);
  }
  const suites = loadSuites(dir);
  if (suites.length === 0) {
    console.log(`slow-tests: no JUnit XML under ${dir}`);
    process.exit(values["allow-empty"] ? 0 : 1);
  }

  const classTotal = suites.reduce((a, s) => a + s.time, 0);
  const starts = suites.filter((s) => s.timestamp !== undefined);
  const spanStart = Math.min(...starts.map((s) => s.timestamp));
  const spanEnd = Math.max(...starts.map((s) => s.timestamp + s.time * 1000));
  const span = starts.length ? (spanEnd - spanStart) / 1000 : undefined;
  const wall = values.wall === undefined ? undefined : Number(values.wall);
  const allTests = suites.flatMap((s) => s.tests);
  const denominator = classTotal;

  const classRows = [...suites]
    .sort((a, b) => b.time - a.time)
    .slice(0, topClasses)
    .map((s) => [shortClass(s.name), String(s.tests.length), fmtDur(s.time), pct(s.time, denominator)]);
  const testRows = [...allTests]
    .sort((a, b) => b.time - a.time)
    .slice(0, topTests)
    .map((t) => [`${shortClass(t.cls)} > ${t.name}`, fmtDur(t.time), pct(t.time, denominator)]);

  const summaryLines = [
    `${suites.length} test classes, ${allTests.length} test cases`,
    `sum of class times: ${fmtDur(classTotal)}`,
    span === undefined ? undefined : `suite span (earliest start -> latest end): ${fmtDur(span)}`,
    wall === undefined ? undefined : `Gradle-reported total: ${fmtDur(wall)}`,
  ].filter(Boolean);
  const budget = values.budget ? budgetByKey(values.budget) : undefined;
  if (values.budget && !budget) {
    console.error(`slow-tests: unknown budget key "${values.budget}" (scope.name, e.g. ci.server)`);
    process.exit(2);
  }
  const testTime = wall ?? span ?? classTotal;
  if (budget) summaryLines.push(`budget ${values.budget}: target ${fmtDur(budget.target)}, alarm ${fmtDur(budget.alarm)}`);

  console.log(summaryLines.join("\n"));
  console.log(`\ntop ${classRows.length} classes`);
  console.log(table(["class", "tests", "time", "share"], classRows, ["l", "r", "r", "r"]));
  console.log(`\ntop ${testRows.length} test cases`);
  console.log(table(["test", "time", "share"], testRows, ["l", "r", "r"]));

  const balance =
    forks === undefined
      ? undefined
      : forkBalance(suites, {
          forks,
          span,
          wall,
          weightsFile: values.weights ?? DEFAULT_WEIGHTS,
          weightsExplicit: values.weights !== undefined,
        });
  if (balance) {
    console.log("\nfork balance");
    console.log(balance.lines.join("\n"));
    if (balance.warn) {
      console.log(process.env.GITHUB_ACTIONS === "true" ? `\n::warning title=Test forks out of balance::${balance.warn}` : `\nWARNING: ${balance.warn}`);
    }
  }

  if (budget && testTime > budget.alarm) {
    const msg = `test time ${fmtDur(testTime)} exceeds the ${values.budget} alarm of ${fmtDur(budget.alarm)} (see .claude/docs/build-times.md)`;
    console.log(process.env.GITHUB_ACTIONS === "true" ? `\n::warning title=Test time over budget::${msg}` : `\nWARNING: ${msg}`);
  }

  if (values["summary-file"]) {
    const md = [
      "### Slowest tests",
      "",
      summaryLines.map((l) => `- ${l}`).join("\n"),
      "",
      `**Top ${classRows.length} classes**`,
      "",
      "| class | tests | time | share |",
      "|---|--:|--:|--:|",
      ...classRows.map((r) => `| \`${r[0]}\` | ${r[1]} | ${r[2]} | ${r[3]} |`),
      "",
      `**Top ${testRows.length} test cases**`,
      "",
      "| test | time | share |",
      "|---|--:|--:|",
      ...testRows.map((r) => `| ${r[0].replace(/\|/g, "\\|")} | ${r[1]} | ${r[2]} |`),
      "",
      ...(balance
        ? ["**Fork balance**", "", balance.lines.map((l) => `- ${l.trim()}`).join("\n"), "", ...(balance.warn ? [`> WARNING: ${balance.warn}`, ""] : [])]
        : []),
    ].join("\n");
    appendFileSync(values["summary-file"], `${md}\n`);
  }
}

// ---------------------------------------------------------------- test-weights

const MIN_WEIGHT_SUITES = 100;

function testWeights(argv) {
  const { values, positionals } = parseArgs({
    args: argv,
    options: { out: { type: "string", default: DEFAULT_WEIGHTS }, force: { type: "boolean", default: false } },
    allowPositionals: true,
  });
  const dir = positionals[0] ?? "server/build/test-results/test";
  const suites = loadSuites(dir);
  if (suites.length < MIN_WEIGHT_SUITES && !values.force) {
    console.error(
      `test-weights: ${suites.length} suites under ${dir}, fewer than ${MIN_WEIGHT_SUITES} - not a full run ` +
        "(a filtered run would write a partial weights file); re-run the whole suite or pass --force",
    );
    process.exit(1);
  }
  const failed = suites.filter((s) => s.failed > 0);
  if (failed.length && !values.force) {
    console.error(
      `test-weights: ${failed.length} suites failed (${failed.slice(0, 5).map((s) => shortClass(s.name)).join(", ")}) - ` +
        "a failed or aborted suite's time is no weight; fix the run first or pass --force",
    );
    process.exit(1);
  }
  const starts = suites.map((s) => s.timestamp).filter((t) => t !== undefined);
  const runDate = (starts.length ? new Date(Math.min(...starts)) : new Date()).toISOString().slice(0, 10);
  const rows = suites
    .map((s) => [s.name, suiteWeight(s).toFixed(1)])
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  const text = [
    "# generated by: node scripts/timings/local-times.mjs test-weights",
    `# source run: ${runDate}, ${git("rev-parse", "--short", "HEAD")}, forks=${maxConcurrent(suites)}, ${cpus().length} cores`,
    "# format: <fully.qualified.ClassName><TAB><seconds>; a class missing here gets the average weight",
    ...rows.map((r) => r.join("\t")),
    "",
  ].join("\n");
  mkdirSync(dirname(values.out), { recursive: true });
  writeFileSync(values.out, text);
  console.log(`test-weights: ${rows.length} classes -> ${values.out}`);
}

// ---------------------------------------------------------------- main

const [command, ...rest] = process.argv.slice(2);
switch (command) {
  case "record":
    record(rest);
    break;
  case "report":
    report(rest);
    break;
  case "slow-tests":
    slowTests(rest);
    break;
  case "test-weights":
    testWeights(rest);
    break;
  default:
    console.log(USAGE);
    process.exit(command === undefined || command === "-h" || command === "--help" ? 0 : 2);
}
