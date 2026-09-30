// Drift guard for the toolchain pins that no Dependabot ecosystem moves together (node built-ins only;
// see .claude/docs/dependencies.md "CI toolchain pins and scans"):
//   node: mise.toml == .nvmrc == every `FROM node:<v>-…` in the Dockerfile
//   java: mise.toml (temurin-<v>+…) == every `java-version:` in ci.yml == every `eclipse-temurin:<v>_…` in the Dockerfile
// Run from anywhere: node scripts/check-toolchain-pins.mjs — exits 1 with one line per mismatch.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (f) => readFileSync(join(root, f), "utf8");
const all = (text, re) => [...text.matchAll(re)].map((m) => m[1]);

const mise = read("mise.toml");
const dockerfile = read("Dockerfile");
const ci = read(".github/workflows/ci.yml");

const misePin = (tool) => mise.match(new RegExp(`^${tool}\\s*=\\s*"([^"]+)"`, "m"))?.[1];
const problems = [];
const expect = (label, want, wantFrom, got) => {
  if (got.length === 0) problems.push(`${label}: no pin found (expected ${want} from ${wantFrom})`);
  for (const g of got) if (g !== want) problems.push(`${label}: found ${g}, expected ${want} (${wantFrom})`);
};

const node = misePin("node");
const javaRaw = misePin("java"); // temurin-21.0.12+101.0.LTS
const java = javaRaw?.match(/^temurin-(\d+\.\d+\.\d+)/)?.[1];
if (!node) problems.push("mise.toml: no node pin");
if (!java) problems.push(`mise.toml: cannot read the java patch version from "${javaRaw}"`);

if (node) {
  expect(".nvmrc", node, "mise.toml node", [read(".nvmrc").trim().replace(/^v/, "")]);
  expect("Dockerfile node tag", node, "mise.toml node", all(dockerfile, /^FROM node:([\d.]+)-/gm));
}
if (java) {
  expect("ci.yml java-version", java, "mise.toml java", all(ci, /^\s*java-version:\s*([\d.]+)\s*$/gm));
  expect("Dockerfile eclipse-temurin tag", java, "mise.toml java", all(dockerfile, /^FROM eclipse-temurin:([\d.]+)_/gm));
}

if (problems.length) {
  console.error("Toolchain pins have drifted apart — bump mise.toml, .nvmrc, ci.yml and the Dockerfile together:");
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`toolchain pins agree: node ${node}, java ${java}`);
