// Synthetic unified-diff bodies in the shape GitLab's REST API returns for `GET .../commits/:sha/diff`:
// the `diff` text starts at the first `@@` (no `---`/`+++` file headers), hunk header counts are exact, and a
// missing final newline is marked with `\ No newline at end of file`. Ground truth (additions/deletions) is
// returned beside the text and re-checked by `countDiff` (the counter the Flow connector will implement).

const NOUNS = ["order", "invoice", "ledger", "session", "cart", "price", "route", "handler", "token", "policy", "batch", "cursor", "cache", "metric", "tenant", "quota", "queue", "audit", "limit", "catalog"];
const VERBS = ["load", "parse", "build", "render", "validate", "merge", "flush", "resolve", "apply", "encode", "sync", "emit", "check", "verify", "collect"];
const ADJ = ["pending", "active", "stale", "primary", "default", "legacy", "remote", "local", "strict", "lazy"];

const cap = (s) => s[0].toUpperCase() + s.slice(1);
const word = (rng, list) => rng.pick(list);
const ident = (rng) => `${word(rng, ADJ)}${cap(word(rng, NOUNS))}`;
const fn = (rng) => `${word(rng, VERBS)}${cap(word(rng, NOUNS))}`;

const LINES = {
  go: [
    (r) => `func (s *Service) ${cap(fn(r))}(ctx context.Context, id string) (*${cap(word(r, NOUNS))}, error) {`,
    (r) => `\t${word(r, NOUNS)}, err := s.store.${cap(fn(r))}(ctx, id)`,
    (r) => `\tif err != nil {`,
    (r) => `\t\treturn nil, fmt.Errorf("${fn(r)}: %w", err)`,
    (r) => `\tlog.Printf("${word(r, VERBS)} ${word(r, NOUNS)} id=%s took=%s", id, time.Since(start))`,
    (r) => `\ts.${ident(r)} = ${r.int(1, 90)} * time.Second`,
    (r) => `\treturn ${word(r, NOUNS)}, nil`,
    (r) => `}`,
    (r) => `// ${cap(fn(r))} ${word(r, VERBS)}s the ${word(r, ADJ)} ${word(r, NOUNS)} before it is served.`,
  ],
  ts: [
    (r) => `export async function ${fn(r)}(${word(r, NOUNS)}Id: string): Promise<${cap(word(r, NOUNS))}> {`,
    (r) => `  const ${ident(r)} = await api.${fn(r)}(${word(r, NOUNS)}Id);`,
    (r) => `  if (!${ident(r)}) throw new Error("${word(r, ADJ)} ${word(r, NOUNS)} not found");`,
    (r) => `  return { ...${word(r, NOUNS)}, ${word(r, ADJ)}: ${r.bool(0.5)}, retries: ${r.int(0, 5)} };`,
    (r) => `import { ${fn(r)} } from "./${word(r, NOUNS)}";`,
    (r) => `const ${ident(r).toUpperCase()}_LIMIT = ${r.int(5, 500)};`,
    (r) => `  <${cap(word(r, NOUNS))}Panel ${word(r, ADJ)}={${r.bool(0.5)}} onChange={handle${cap(word(r, NOUNS))}} />`,
    (r) => `  // ${word(r, VERBS)} the ${word(r, ADJ)} ${word(r, NOUNS)} once the request settles`,
    (r) => `}`,
  ],
  py: [
    (r) => `def ${fn(r).replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)}(session, ${word(r, NOUNS)}_id):`,
    (r) => `    ${word(r, NOUNS)} = session.query(${cap(word(r, NOUNS))}).filter_by(id=${word(r, NOUNS)}_id).one_or_none()`,
    (r) => `    if ${word(r, NOUNS)} is None:`,
    (r) => `        raise LookupError("no ${word(r, ADJ)} ${word(r, NOUNS)}")`,
    (r) => `    logger.info("${word(r, VERBS)} ${word(r, NOUNS)}", extra={"count": ${r.int(1, 99)}})`,
    (r) => `    return ${word(r, NOUNS)}`,
    (r) => `${word(r, NOUNS).toUpperCase()}_TIMEOUT = ${r.int(1, 120)}`,
    (r) => `    # ${word(r, VERBS)} the ${word(r, ADJ)} ${word(r, NOUNS)} before committing`,
  ],
  rb: [
    (r) => `  def ${word(r, VERBS)}_${word(r, NOUNS)}(${word(r, NOUNS)})`,
    (r) => `    @${word(r, NOUNS)}s << ${word(r, NOUNS)} if ${word(r, NOUNS)}.${word(r, ADJ)}?`,
    (r) => `    raise ArgumentError, "${word(r, ADJ)} ${word(r, NOUNS)}" unless ${word(r, NOUNS)}`,
    (r) => `  end`,
    (r) => `    render json: ${word(r, NOUNS)}, status: :ok`,
  ],
  rs: [
    (r) => `pub fn ${fn(r).replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)}(input: &str) -> Result<${cap(word(r, NOUNS))}, Error> {`,
    (r) => `    let ${word(r, NOUNS)} = input.split(',').map(str::trim).collect::<Vec<_>>();`,
    (r) => `    if ${word(r, NOUNS)}.is_empty() { return Err(Error::${cap(word(r, ADJ))}); }`,
    (r) => `    Ok(${cap(word(r, NOUNS))} { retries: ${r.int(0, 9)}, ..Default::default() })`,
    (r) => `}`,
  ],
  kt: [
    (r) => `    fun ${fn(r)}(${word(r, NOUNS)}Id: Long): ${cap(word(r, NOUNS))}? {`,
    (r) => `        val ${ident(r)} = repository.find(${word(r, NOUNS)}Id) ?: return null`,
    (r) => `        return ${ident(r)}.copy(${word(r, ADJ)} = ${r.bool(0.5)})`,
    (r) => `    }`,
  ],
  sql: [
    (r) => `CREATE INDEX idx_${word(r, NOUNS)}_${word(r, NOUNS)} ON ${word(r, NOUNS)}s (${word(r, NOUNS)}_id, created_at);`,
    (r) => `ALTER TABLE ${word(r, NOUNS)}s ADD COLUMN ${word(r, ADJ)}_${word(r, NOUNS)} TEXT;`,
    (r) => `UPDATE ${word(r, NOUNS)}s SET ${word(r, ADJ)} = false WHERE created_at < now() - interval '${r.int(1, 90)} days';`,
    (r) => `-- ${word(r, VERBS)} ${word(r, ADJ)} ${word(r, NOUNS)} rows before the constraint lands`,
  ],
  md: [
    (r) => `## ${cap(word(r, VERBS))}ing a ${word(r, ADJ)} ${word(r, NOUNS)}`,
    (r) => `The ${word(r, NOUNS)} service ${word(r, VERBS)}s every ${word(r, ADJ)} ${word(r, NOUNS)} within ${r.int(2, 60)} seconds.`,
    (r) => `- ${cap(word(r, VERBS))} the ${word(r, NOUNS)} before calling \`${fn(r)}\`.`,
    (r) => ``,
    (r) => `---`,
  ],
  yaml: [
    (r) => `  ${word(r, NOUNS)}_${word(r, ADJ)}: ${r.int(1, 300)}`,
    (r) => `  - name: ${word(r, ADJ)}-${word(r, NOUNS)}`,
    (r) => `    image: registry.flow-sample.example/${word(r, NOUNS)}/${word(r, ADJ)}:${r.int(1, 9)}.${r.int(0, 20)}.${r.int(0, 9)}`,
    (r) => `    replicas: ${r.int(1, 6)}`,
  ],
  json: [
    (r) => `  "${ident(r)}": ${r.int(1, 999)},`,
    (r) => `    "${word(r, NOUNS)}": "${word(r, ADJ)}",`,
    (r) => `  "${word(r, VERBS)}": { "retries": ${r.int(0, 9)}, "enabled": ${r.bool(0.5)} },`,
  ],
  sh: [
    (r) => `${word(r, NOUNS).toUpperCase()}_DIR="\${${word(r, NOUNS).toUpperCase()}_DIR:-/var/lib/${word(r, NOUNS)}}"`,
    (r) => `echo "${word(r, VERBS)}ing ${word(r, ADJ)} ${word(r, NOUNS)}"`,
    (r) => `docker build --pull -t ${word(r, NOUNS)}:${r.int(1, 99)} .`,
  ],
};

const SECTIONS = {
  go: (r) => `func (s *Service) ${cap(fn(r))}(`,
  ts: (r) => `export async function ${fn(r)}(`,
  py: (r) => `def ${fn(r).replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)}(`,
  rb: (r) => `  def ${word(r, VERBS)}_${word(r, NOUNS)}`,
  rs: (r) => `pub fn ${fn(r).replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)}(`,
  kt: (r) => `    fun ${fn(r)}(`,
};

export function sourceLine(rng, lang) {
  const pool = LINES[lang] ?? LINES.ts;
  return rng.pick(pool)(rng);
}

function hunkSection(rng, lang) {
  const f = SECTIONS[lang];
  return f && rng.bool(0.6) ? ` ${f(rng)}` : "";
}

function b64(rng, n) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let s = "";
  for (let i = 0; i < n; i++) s += chars[rng.int(0, chars.length - 1)];
  return s;
}
function hex(rng, n) {
  let s = "";
  for (let i = 0; i < n; i++) s += "0123456789abcdef"[rng.int(0, 15)];
  return s;
}

/** Lock-file line generators keyed by file name (a few lines per resolved package). */
const LOCK_LINES = {
  "go.sum": (r) => {
    const m = `github.com/sample/${word(r, NOUNS)}${r.int(1, 40)}`;
    const v = `v${r.int(0, 3)}.${r.int(0, 30)}.${r.int(0, 9)}`;
    return [`${m} ${v} h1:${b64(r, 43)}=`, `${m} ${v}/go.mod h1:${b64(r, 43)}=`];
  },
  "package-lock.json": (r) => {
    const n = `${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)}`;
    const v = `${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}`;
    return [`    "node_modules/${n}": {`, `      "version": "${v}",`, `      "resolved": "https://registry.flow-sample.example/${n}/-/${n}-${v}.tgz",`, `      "integrity": "sha512-${b64(r, 86)}==",`, `    },`];
  },
  "yarn.lock": (r) => {
    const n = `${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)}`;
    const v = `${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}`;
    return [`"${n}@^${v}":`, `  version "${v}"`, `  resolved "https://registry.flow-sample.example/${n}/-/${n}-${v}.tgz#${hex(r, 40)}"`, `  integrity sha512-${b64(r, 86)}==`, ``];
  },
  "pnpm-lock.yaml": (r) => {
    const n = `${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)}`;
    const v = `${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}`;
    return [`  /${n}@${v}:`, `    resolution: {integrity: sha512-${b64(r, 86)}==}`, `    engines: {node: '>=${r.int(16, 22)}'}`, `    dev: ${r.bool(0.5)}`];
  },
  "poetry.lock": (r) => {
    const n = `${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)}`;
    return [`[[package]]`, `name = "${n}"`, `version = "${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}"`, `optional = false`, `python-versions = ">=3.${r.int(8, 13)}"`, ``];
  },
  "Gemfile.lock": (r) => [`    ${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)} (${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)})`],
  "composer.lock": (r) => [`            "name": "sample/${word(r, NOUNS)}${r.int(1, 40)}",`, `            "version": "${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}",`, `            "dist": {"shasum": "${hex(r, 40)}"},`],
  "Cargo.lock": (r) => [`[[package]]`, `name = "${word(r, NOUNS)}-${word(r, VERBS)}${r.int(1, 40)}"`, `version = "${r.int(0, 5)}.${r.int(0, 30)}.${r.int(0, 9)}"`, `checksum = "${hex(r, 64)}"`, ``],
};

export function lockLine(rng, name) {
  const f = LOCK_LINES[name.split("/").pop()];
  return f ? f(rng) : [`# ${hex(rng, 24)}`];
}

/** Git's `@@` range: `start` alone when the count is 1, `start,count` otherwise. */
const range = (start, count) => (count === 1 ? `${start}` : `${start},${count}`);

function minLine(rng, n) {
  let s = "";
  while (s.length < n) s += `function ${hex(rng, 2)}(${hex(rng, 1)}){return ${hex(rng, 1)}+${rng.int(1, 99)}}var ${hex(rng, 3)}=${rng.int(0, 9999)};`;
  return s;
}

/**
 * A modify diff. `lines(rng)` yields context/changed line text (or an array of lines for lock entries), `hunks` is the
 * number of hunks, `runMax` bounds the deleted/added run sizes. `eof` ('old'|'new') ends the last hunk at the end of
 * the file with a `\ No newline at end of file` marker after the old (`-`) or the new (`+`) last line.
 */
export function modifyDiff(rng, { lines, hunks = 1, runMax = 6, oldLen = rng.int(40, 260), eof = null, lang = null, hostile = null }) {
  const out = [];
  let additions = 0;
  let deletions = 0;
  let delta = 0;
  const flat = () => {
    const l = lines(rng);
    return Array.isArray(l) ? l : [l];
  };
  const take = (n) => {
    const acc = [];
    while (acc.length < n) acc.push(...flat());
    return acc.slice(0, n);
  };
  // Hunk start lines: increasing, at least 24 apart so no two hunks overlap or merge.
  const starts = [];
  let cursor = 4;
  for (let h = 0; h < hunks; h++) {
    cursor += rng.int(2, 18);
    starts.push(cursor);
    cursor += 24 + runMax * 2;
  }
  const total = Math.max(oldLen, cursor + 8);
  for (let h = 0; h < hunks; h++) {
    const last = h === hunks - 1;
    const atEof = last && eof;
    const oldStart = atEof ? total - 3 : starts[h];
    const body = [];
    let oldCount = 0;
    let newCount = 0;
    const ctx = (n) => {
      for (const l of take(n)) body.push(` ${l}`);
      oldCount += n;
      newCount += n;
    };
    ctx(3);
    const runs = atEof ? 1 : rng.pickWeighted([[1, 70], [2, 30]]);
    for (let r = 0; r < runs; r++) {
      let d = rng.int(0, runMax);
      let a = rng.int(0, runMax);
      if (d + a === 0) a = 1;
      if (atEof) {
        d = 1;
        a = rng.int(1, 3);
      }
      let removed = take(d);
      if (hostile && r === 0 && h === 0) removed = hostile.removed;
      let added = take(a);
      if (hostile && r === 0 && h === 0) added = hostile.added;
      d = removed.length;
      a = added.length;
      for (const l of removed) body.push(`-${l}`);
      if (atEof === "old") body.push("\\ No newline at end of file");
      for (const l of added) body.push(`+${l}`);
      if (atEof === "new") body.push("\\ No newline at end of file");
      oldCount += d;
      newCount += a;
      additions += a;
      deletions += d;
      if (r < runs - 1) ctx(rng.int(1, 4));
    }
    if (!atEof) ctx(3);
    out.push(`@@ -${range(oldStart, oldCount)} +${range(oldStart + delta, newCount)} @@${hunkSection(rng, lang)}`);
    out.push(...body);
    delta += newCount - oldCount;
  }
  return { diff: `${out.join("\n")}\n`, additions, deletions };
}

export function newFileDiff(rng, { lines, n }) {
  const body = [];
  while (body.length < n) {
    const l = lines(rng);
    body.push(...(Array.isArray(l) ? l : [l]));
  }
  body.length = n;
  return { diff: `@@ -0,0 +1${n === 1 ? "" : `,${n}`} @@\n${body.map((l) => `+${l}`).join("\n")}\n`, additions: n, deletions: 0 };
}

export function deletedFileDiff(rng, { lines, n }) {
  const body = [];
  while (body.length < n) {
    const l = lines(rng);
    body.push(...(Array.isArray(l) ? l : [l]));
  }
  body.length = n;
  return { diff: `@@ -1${n === 1 ? "" : `,${n}`} +0,0 @@\n${body.map((l) => `-${l}`).join("\n")}\n`, additions: 0, deletions: n };
}

/** A minified bundle: one very long line replaced by another, neither ending in a newline. */
export function minifiedDiff(rng) {
  const a = minLine(rng, rng.int(400, 900));
  const b = minLine(rng, rng.int(400, 900));
  return { diff: `@@ -1 +1 @@\n-${a}\n\\ No newline at end of file\n+${b}\n\\ No newline at end of file\n`, additions: 1, deletions: 1 };
}

export function binaryDiff(oldPath, newPath, isNew, isDeleted) {
  const a = isNew ? "/dev/null" : `a/${oldPath}`;
  const b = isDeleted ? "/dev/null" : `b/${newPath}`;
  return { diff: `Binary files ${a} and ${b} differ\n`, additions: 0, deletions: 0 };
}

/**
 * The line counter the Flow connector will implement (gitlab-integration.md "COMMIT FILES"): lines after the first
 * `@@` that start with `+` or `-`; the `\ No newline at end of file` marker never counts.
 */
export function countDiff(diff) {
  let additions = 0;
  let deletions = 0;
  let inHunk = false;
  for (const line of diff.split("\n")) {
    if (!inHunk) {
      if (line.startsWith("@@")) inHunk = true;
      continue;
    }
    if (line.startsWith("+")) additions++;
    else if (line.startsWith("-")) deletions++;
  }
  return { additions, deletions };
}

/** A stricter check used by the self-test: every hunk header's counts equal the lines the hunk really has. */
export function hunksConsistent(diff) {
  if (diff === "" || diff.startsWith("Binary files")) return true;
  const lines = diff.split("\n");
  if (lines.at(-1) !== "") return false;
  lines.pop();
  let i = 0;
  while (i < lines.length) {
    const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(lines[i]);
    if (!m) return false;
    const oldCount = m[2] === undefined ? 1 : Number(m[2]);
    const newCount = m[4] === undefined ? 1 : Number(m[4]);
    let o = 0;
    let n = 0;
    i++;
    while (i < lines.length && !lines[i].startsWith("@@ ")) {
      const c = lines[i][0];
      if (c === " ") (o++, n++);
      else if (c === "-") o++;
      else if (c === "+") n++;
      else if (c !== "\\") return false;
      i++;
    }
    if (o !== oldCount || n !== newCount) return false;
  }
  return true;
}
