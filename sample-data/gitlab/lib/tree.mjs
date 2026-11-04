// Per-project file trees and the file-level operations a synthetic commit performs on them: modify, add, delete,
// rename (pure and with edits), binary, lock-file churn, minified bundles, vendored/dist/generated paths.
import { binaryDiff, deletedFileDiff, lockLine, minifiedDiff, modifyDiff, newFileDiff, sourceLine } from "./diff.mjs";

/** The default `gitlab.pathExclusions` of the design doc ("Path exclusions"). */
export const DEFAULT_EXCLUSIONS = [
  "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum", "Cargo.lock", "Gemfile.lock", "poetry.lock", "composer.lock",
  "*.min.js", "*.min.css", "**/vendor/**", "**/node_modules/**", "**/dist/**", "**/generated/**",
];

function globToRegex(glob) {
  const anchored = glob.includes("/");
  let re = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*" && glob[i + 1] === "*") {
      if (glob[i + 2] === "/") {
        re += "(?:.*/)?";
        i += 2;
      } else {
        re += ".*";
        i += 1;
      }
    } else if (c === "*") re += "[^/]*";
    else re += c.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
  }
  return new RegExp(anchored ? `^${re}$` : `^(?:.*/)?${re}$`);
}
const EXCLUSION_REGEXES = DEFAULT_EXCLUSIONS.map(globToRegex);
/** gitignore-like: a glob without `/` matches the file name at any depth; one with `/` matches the whole path. */
export const isExcluded = (path) => EXCLUSION_REGEXES.some((re) => re.test(path));

const LOCK_NAMES = new Set(["package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum", "Cargo.lock", "Gemfile.lock", "poetry.lock", "composer.lock"]);
export const isGeneratedPath = (path) =>
  LOCK_NAMES.has(path.split("/").pop()) || /\.min\.(js|css)$/.test(path) || /(^|\/)generated\//.test(path) || /\.pb\.go$/.test(path);

const NAMES = ["order", "invoice", "ledger", "session", "cart", "price", "route", "handler", "token", "policy", "batch", "cursor", "cache", "metric", "tenant", "quota", "queue", "audit", "limit", "catalog", "mailer", "scheduler", "importer", "exporter", "resolver"];

export const TEMPLATES = {
  go: {
    lang: "go", ext: "go", test: (b) => `${b}_test.go`,
    srcDirs: ["cmd/gateway", "internal/router", "internal/auth", "internal/ratelimit", "internal/upstream", "internal/config", "pkg/httpx"],
    locks: ["go.sum"], manifest: "go.mod", vendor: "vendor/github.com/sample/httpmux", generated: "internal/api/generated", dist: "web/dist", min: "web/static",
    docs: "docs", cfg: ["deploy/helm/values.yaml", ".gitlab-ci.yml", "Dockerfile", "deploy/scripts/release.sh"], assets: "docs/img", nodeModules: null,
  },
  ts: {
    lang: "ts", ext: "ts", test: (b) => `${b}.test.ts`,
    srcDirs: ["src/cli", "src/core", "src/plugins", "src/config", "src/io"],
    locks: ["package-lock.json", "pnpm-lock.yaml"], manifest: "package.json", vendor: null, generated: "generated/schema", dist: "dist", min: "static", nodeModules: "node_modules/.cache",
    docs: "docs", cfg: ["tsconfig.json", ".gitlab-ci.yml", "scripts/build.sh"], assets: "docs/img",
  },
  tsx: {
    lang: "ts", ext: "tsx", test: (b) => `${b}.test.tsx`,
    srcDirs: ["src/components", "src/pages", "src/hooks", "src/api", "src/state"],
    locks: ["package-lock.json", "yarn.lock"], manifest: "package.json", vendor: "src/vendor", generated: "src/generated", dist: "dist", min: "public/js", nodeModules: null,
    docs: "docs", cfg: ["vite.config.ts", ".gitlab-ci.yml", "public/manifest.json"], assets: "public/assets",
  },
  py: {
    lang: "py", ext: "py", test: (b) => `test_${b}.py`,
    srcDirs: ["ledger/core", "ledger/accounts", "ledger/postings", "ledger/reports", "ledger/api"],
    locks: ["poetry.lock"], manifest: "pyproject.toml", vendor: "vendor/sample_money", generated: "ledger/generated", dist: "dist", min: null, nodeModules: null,
    docs: "docs", cfg: [".gitlab-ci.yml", "config/settings.yaml", "migrations/0001_init.sql", "migrations/0002_postings.sql"], assets: "docs/img", testDir: "tests",
  },
  rb: {
    lang: "rb", ext: "rb", test: (b) => `${b}_spec.rb`,
    srcDirs: ["app/models", "app/controllers", "app/services", "lib"],
    locks: ["Gemfile.lock", "composer.lock"], manifest: "Gemfile", vendor: "vendor/bundle", generated: "app/generated", dist: null, min: "public/js", nodeModules: null,
    docs: "doc", cfg: ["config/routes.rb", ".gitlab-ci.yml"], assets: "public/images", testDir: "spec",
  },
  rs: {
    lang: "rs", ext: "rs", test: (b) => `${b}_tests.rs`,
    srcDirs: ["src", "src/engine", "src/io"],
    locks: ["Cargo.lock"], manifest: "Cargo.toml", vendor: null, generated: "src/generated", dist: null, min: null, nodeModules: null,
    docs: "docs", cfg: [".gitlab-ci.yml"], assets: "docs/img",
  },
};

const LANG_BY_EXT = { go: "go", ts: "ts", tsx: "ts", js: "ts", py: "py", rb: "rb", rs: "rs", kt: "kt", sql: "sql", md: "md", yaml: "yaml", yml: "yaml", json: "json", sh: "sh", toml: "yaml", mod: "yaml" };
export function langOf(path) {
  const base = path.split("/").pop();
  if (base === "Dockerfile" || base === "Gemfile") return "sh";
  return LANG_BY_EXT[base.split(".").pop()] ?? "ts";
}
const lineFnFor = (path) => {
  const lang = langOf(path);
  return { lang, lines: (r) => sourceLine(r, lang) };
};

/** Build a project's initial tree of existing files. */
export function makeTree(rng, tplName) {
  const tpl = TEMPLATES[tplName];
  const tree = [];
  const add = (path, cat, extra = {}) => {
    if (!tree.some((f) => f.path === path)) tree.push({ path, cat, binary: false, ...extra });
  };
  for (const dir of tpl.srcDirs) {
    const n = rng.int(3, 5);
    for (let i = 0; i < n; i++) {
      const base = rng.pick(NAMES);
      add(`${dir}/${base}.${tpl.ext}`, "src");
      if (rng.bool(0.5)) add(`${tpl.testDir ?? dir}/${tpl.test(base)}`, "test");
    }
  }
  for (let i = 0; i < 4; i++) add(`${tpl.docs}/${rng.pick(NAMES)}.md`, "docs");
  add("README.md", "docs");
  for (const c of tpl.cfg) add(c, "cfg");
  add(tpl.manifest, "cfg");
  for (const l of tpl.locks) add(l, "lock");
  if (tpl.vendor) for (let i = 0; i < 3; i++) add(`${tpl.vendor}/${rng.pick(NAMES)}.${tpl.ext === "tsx" ? "js" : tpl.ext}`, "vendor");
  if (tpl.nodeModules) for (let i = 0; i < 3; i++) add(`${tpl.nodeModules}/${rng.pick(NAMES)}/index.js`, "vendor");
  for (let i = 0; i < 2; i++) add(`${tpl.generated}/${rng.pick(NAMES)}.${tpl.ext}`, "generated");
  if (tpl.dist) {
    add(`${tpl.dist}/bundle.js`, "dist");
    add(`${tpl.dist}/${tpl.lang === "py" ? "ledger-1.4.0.tar.gz" : "styles.css"}`, "dist", { binary: tpl.lang === "py" });
  }
  if (tpl.min) {
    add(`${tpl.min}/app.min.js`, "min");
    add(`${tpl.min}/site.min.css`, "min");
  }
  for (let i = 0; i < 3; i++) add(`${tpl.assets}/${rng.pick(NAMES)}.png`, "binary", { binary: true });
  return tree;
}

export function makeFile({ oldPath, newPath, diff, additions, deletions, newFile = false, deletedFile = false, renamedFile = false, tooLarge = false, collapsed = false, binary = false }) {
  const mode = newPath.endsWith(".sh") ? "100755" : "100644";
  return {
    oldPath, newPath,
    aMode: newFile ? "0" : mode, bMode: deletedFile ? "0" : mode,
    newFile, renamedFile, deletedFile, generatedFile: isGeneratedPath(newPath),
    diff, additions, deletions, tooLarge, collapsed, binary,
  };
}

const CAT_WEIGHTS = [["src", 60], ["test", 14], ["docs", 6], ["cfg", 6], ["generated", 3], ["dist", 2], ["vendor", 2], ["min", 1], ["lock", 2], ["binary", 3]];

function pickFromTree(rng, tree, cat) {
  const pool = tree.filter((f) => f.cat === cat);
  return pool.length ? rng.pick(pool) : null;
}

/** A modify diff for a tree entry, shaped by its category. */
export function modifyEntry(rng, entry) {
  const { path } = entry;
  if (entry.binary) return { ...binaryDiff(path, path, false, false), binary: true };
  if (entry.cat === "lock") {
    const name = path.split("/").pop();
    return modifyDiff(rng, { lines: (r) => lockLine(r, name), hunks: rng.int(1, 4), runMax: 24, oldLen: rng.int(200, 1200) });
  }
  if (entry.cat === "min") return minifiedDiff(rng);
  const { lang, lines } = lineFnFor(path);
  const big = entry.cat === "generated" || entry.cat === "vendor" || entry.cat === "dist";
  return modifyDiff(rng, {
    lines, lang, hunks: big ? rng.int(1, 3) : rng.pickWeighted([[1, 55], [2, 30], [3, 15]]), runMax: big ? 30 : 6,
    eof: !big && rng.bool(0.05) ? rng.pick(["old", "new"]) : null,
  });
}

function newPathFor(rng, tpl, tree, cat) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const base = `${rng.pick(NAMES)}${rng.bool(0.3) ? rng.int(2, 9) : ""}`;
    let path;
    if (cat === "test") path = `${tpl.testDir ?? rng.pick(tpl.srcDirs)}/${tpl.test(base)}`;
    else if (cat === "docs") path = `${tpl.docs}/${base}.md`;
    else if (cat === "binary") path = `${tpl.assets}/${base}.png`;
    else path = `${rng.pick(tpl.srcDirs)}/${base}.${tpl.ext}`;
    if (!tree.some((f) => f.path === path)) return path;
  }
  return `${rng.pick(tpl.srcDirs)}/${rng.pick(NAMES)}${rng.int(10, 99999)}.${tpl.ext}`;
}

/** One file operation on the tree; returns the file record (and updates the tree). */
export function randomOp(rng, tplName, tree, used) {
  const tpl = TEMPLATES[tplName];
  const op = rng.pickWeighted([["modify", 76], ["new", 11], ["delete", 5], ["rename", 5], ["binary", 3]]);
  const fresh = (e) => e && !used.has(e.path);
  if (op === "modify" || op === "binary") {
    let entry = null;
    for (let i = 0; i < 6 && !fresh(entry); i++) entry = pickFromTree(rng, tree, op === "binary" ? "binary" : rng.pickWeighted(CAT_WEIGHTS));
    if (!fresh(entry)) return null;
    used.add(entry.path);
    return makeFile({ oldPath: entry.path, newPath: entry.path, ...modifyEntry(rng, entry) });
  }
  if (op === "new") {
    const cat = rng.pickWeighted([["src", 62], ["test", 22], ["docs", 10], ["binary", 6]]);
    const path = newPathFor(rng, tpl, tree, cat);
    used.add(path);
    if (cat === "binary") {
      tree.push({ path, cat, binary: true });
      return makeFile({ oldPath: path, newPath: path, newFile: true, binary: true, ...binaryDiff(path, path, true, false) });
    }
    tree.push({ path, cat, binary: false });
    const { lines } = lineFnFor(path);
    return makeFile({ oldPath: path, newPath: path, newFile: true, ...newFileDiff(rng, { lines, n: rng.int(4, 90) }) });
  }
  if (op === "delete") {
    const cands = tree.filter((f) => (f.cat === "src" || f.cat === "test" || f.cat === "docs" || f.cat === "binary") && !used.has(f.path));
    if (cands.length < 6) return null;
    const entry = rng.pick(cands);
    used.add(entry.path);
    tree.splice(tree.indexOf(entry), 1);
    if (entry.binary) return makeFile({ oldPath: entry.path, newPath: entry.path, deletedFile: true, binary: true, ...binaryDiff(entry.path, entry.path, false, true) });
    const { lines } = lineFnFor(entry.path);
    return makeFile({ oldPath: entry.path, newPath: entry.path, deletedFile: true, ...deletedFileDiff(rng, { lines, n: rng.int(5, 70) }) });
  }
  // rename: pure (empty diff) 60% of the time, otherwise with a small edit
  const cands = tree.filter((f) => (f.cat === "src" || f.cat === "test" || f.cat === "docs") && !used.has(f.path));
  if (!cands.length) return null;
  const entry = rng.pick(cands);
  const ext = entry.path.split(".").pop();
  const newPath = `${rng.pick(tpl.srcDirs)}/${rng.pick(NAMES)}${rng.int(10, 99)}.${ext}`;
  if (tree.some((f) => f.path === newPath)) return null;
  const oldPath = entry.path;
  used.add(oldPath);
  used.add(newPath);
  entry.path = newPath;
  if (rng.bool(0.6)) return makeFile({ oldPath, newPath, renamedFile: true, diff: "", additions: 0, deletions: 0 });
  return makeFile({ oldPath, newPath, renamedFile: true, ...modifyEntry(rng, entry) });
}

const FILE_COUNT = [[1, 38], [2, 26], [3, 16], [4, 9], [5, 6], [8, 3], [14, 2]];

/** Files of one ordinary commit. `kind`: normal | deps | regen | wide. */
export function makeCommitFiles(rng, tplName, tree, kind = "normal") {
  const tpl = TEMPLATES[tplName];
  const used = new Set();
  const files = [];
  const modify = (entry) => {
    if (!entry || used.has(entry.path)) return;
    used.add(entry.path);
    files.push(makeFile({ oldPath: entry.path, newPath: entry.path, ...modifyEntry(rng, entry) }));
  };
  if (kind === "deps") {
    modify(tree.find((f) => f.path === tpl.manifest));
    for (const l of tpl.locks) if (l === tpl.locks[0] || rng.bool(0.4)) modify(tree.find((f) => f.path === l));
  } else if (kind === "regen") {
    for (const f of tree.filter((e) => e.cat === "generated").slice(0, 2)) modify(f);
  }
  const n = kind === "wide" ? rng.int(12, 60) : kind === "normal" ? rng.pickWeighted(FILE_COUNT) : rng.int(0, 2);
  const target = files.length + n;
  for (let guard = 0; files.length < target && guard < n * 6 + 6; guard++) {
    const f = randomOp(rng, tplName, tree, used);
    if (f) files.push(f);
  }
  if (!files.length) {
    const entry = pickFromTree(rng, tree, "src");
    modify(entry);
  }
  files.sort((a, b) => (a.newPath < b.newPath ? -1 : a.newPath > b.newPath ? 1 : 0));
  return files;
}

export function statsOf(files) {
  const additions = files.reduce((s, f) => s + f.additions, 0);
  const deletions = files.reduce((s, f) => s + f.deletions, 0);
  return { additions, deletions, total: additions + deletions };
}

/** Net change of several commits: per path the LAST commit's file record wins (an approximation of a branch diff). */
export function aggregateFiles(commitFiles) {
  const byPath = new Map();
  for (const files of commitFiles) for (const f of files) byPath.set(f.newPath, f);
  return [...byPath.values()].sort((a, b) => (a.newPath < b.newPath ? -1 : a.newPath > b.newPath ? 1 : 0));
}
