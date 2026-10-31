#!/usr/bin/env node
// Deterministic synthetic self-managed GitLab world + WireMock stub generator.
//
// Zero dependencies (Node >=24 stdlib only). Every random draw comes from a seeded PRNG and the "current time" is the
// frozen reference instant below, so two runs with the same flags produce byte-identical output; see
// sample-data/README.md ("GitLab stub") for the contract.
//
// Usage: node sample-data/gitlab/generate.mjs [--users N] [--projects N] [--months N] [--out DIR]
//
//   --users N     GitLab humans (default 6, min 6). More users means more commits and merge requests.
//   --projects N  in-scope projects (default 4, min 4). The archived, out-of-scope and day2-only projects stay.
//   --months N    months of history before the reference instant (default 6, min 2).
//   --out DIR     writes DIR/gitlab-stub and DIR/gitlab/expected.json instead of the committed sample-data/ location
//                 (the performance run uses this so it never touches the repo).
//
// With NO flags, the output is the committed default world and is BYTE-IDENTICAL across runs. Runs with flags are
// deterministic too but are not committed.
//
// Writes (default location):
//   sample-data/gitlab/expected.json            - the figures the server tests assert against
//   sample-data/gitlab-stub/mappings/*.json     - WireMock request matchers
//   sample-data/gitlab-stub/__files/*.json      - WireMock response bodies
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DEFAULT_PARAMS, buildWorld } from "./lib/world.mjs";
import { createWriter } from "./lib/writer.mjs";
import { defineRoutes } from "./lib/routes.mjs";
import { buildExpected, prettyJson } from "./lib/expected.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const SAMPLE_DATA = path.resolve(here, "..");

export function parseArgs(argv) {
  const params = { ...DEFAULT_PARAMS };
  let out = null;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const [flag, inline] = a.includes("=") ? [a.slice(0, a.indexOf("=")), a.slice(a.indexOf("=") + 1)] : [a, undefined];
    const value = () => inline ?? argv[++i];
    if (flag === "--users") params.users = Number(value());
    else if (flag === "--projects") params.projects = Number(value());
    else if (flag === "--months") params.months = Number(value());
    else if (flag === "--out") out = value();
    else throw new Error(`Unknown argument: ${a}`);
  }
  return { params, out };
}

export function generate({ params, out }) {
  const outputRoot = out ? path.resolve(out) : SAMPLE_DATA;
  const world = buildWorld(params);
  const writer = createWriter(path.join(outputRoot, "gitlab-stub"));
  defineRoutes(writer, world);
  const expected = buildExpected(world, { mappingCount: writer.count() });
  const expectedPath = path.join(outputRoot, "gitlab", "expected.json");
  mkdirSync(path.dirname(expectedPath), { recursive: true });
  writeFileSync(expectedPath, `${prettyJson(expected)}\n`);
  return { world, expected, mappingCount: writer.count(), outputRoot };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const r = generate(parseArgs(process.argv.slice(2)));
  console.log(`gitlab stub: ${r.mappingCount} mappings -> ${path.join(r.outputRoot, "gitlab-stub")}`);
}
