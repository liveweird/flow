// The scenario-parity gate (2026-08 review round): every spec has its scenario file and every
// test() title appears as a `## Scenario:` heading VERBATIM — and vice versa (the compiler
// contract in scenarios/README.md, previously verified by hand at every checkup).
// accessibility.spec.ts is the ONE registered exception: its titles are a template literal
// instantiated per page, so its scenario keeps a placeholder heading (see scenarios/README.md).
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const testsDir = join(root, "tests");
const scenariosDir = join(root, "scenarios");
const SKIP = new Set(["accessibility.spec.ts"]);

// Matches a top-level test declaration's double-quoted title (also when the title starts on
// the next line); modifier calls (`test.skip(`, `test.only(`) don't match — they are preceded by a dot.
// The quoted-string body is the unrolled `[^"\\]*(?:\\.[^"\\]*)*` form — linear, no alternation backtracking.
const TITLE_RE = /(?<![.\w])test\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"/g;

let failures = 0;
const fail = (msg) => {
  failures += 1;
  console.error(`✗ ${msg}`);
};

const specs = readdirSync(testsDir).filter((f) => f.endsWith(".spec.ts"));
const scenarioFiles = new Set(
  readdirSync(scenariosDir).filter((f) => f.endsWith(".md") && f !== "README.md"),
);

for (const spec of specs) {
  const base = spec.replace(/\.spec\.ts$/, "");
  const scenarioName = `${base}.md`;
  if (!scenarioFiles.has(scenarioName)) {
    fail(`${spec}: missing scenarios/${scenarioName}`);
    continue;
  }
  scenarioFiles.delete(scenarioName);
  if (SKIP.has(spec)) continue;

  const src = readFileSync(join(testsDir, spec), "utf8");
  const titles = [...src.matchAll(TITLE_RE)].map((m) => m[1]);
  const scenario = readFileSync(join(scenariosDir, scenarioName), "utf8");
  const headings = [...scenario.matchAll(/^## Scenario: (.+)$/gm)].map((m) => m[1].trimEnd());

  for (const t of titles) {
    if (!headings.includes(t)) fail(`${spec}: title has no matching heading in ${scenarioName}:\n    "${t}"`);
  }
  for (const h of headings) {
    if (!titles.includes(h)) fail(`scenarios/${scenarioName}: heading matches no test() title in ${spec}:\n    "${h}"`);
  }
  if (titles.length === 0) fail(`${spec}: no test() titles extracted — the checker's regex needs a look`);
}

for (const orphan of scenarioFiles) {
  fail(`scenarios/${orphan}: no matching tests/${orphan.replace(/\.md$/, ".spec.ts")}`);
}

if (failures === 0) {
  console.log(`✓ scenario parity: ${specs.length} specs ↔ scenarios in sync`);
} else {
  console.error(`${failures} scenario-parity failure(s)`);
  process.exit(1);
}
