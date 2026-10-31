// WireMock mapping writer: standard `mappings/*.json` + `__files/*.json` (bodies), GitLab offset-pagination headers,
// and the day2 scenario gating. A descriptor that is identical in `Started` and `day2` is written once, ungated.
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { PER_PAGE, SCENARIO } from "./constants.mjs";

export const PRIORITY = { unauthorized: 1, page: 5, normal: 6, notFound: 9, generic: 20 };

// Static rate-limit headers (the Flow connector pauses near zero; this instance is never near it).
const RATE_LIMIT = {
  "RateLimit-Limit": "2000", "RateLimit-Observed": "10", "RateLimit-Remaining": "1990", "RateLimit-Reset": "1788220860", "RateLimit-ResetTime": "Tue, 01 Sep 2026 00:01:00 GMT",
};
const JSON_HEADERS = { "Content-Type": "application/json", ...RATE_LIMIT };

export function createWriter(stubDir) {
  const mappingsDir = path.join(stubDir, "mappings");
  const filesDir = path.join(stubDir, "__files");
  rmSync(stubDir, { recursive: true, force: true });
  mkdirSync(mappingsDir, { recursive: true });
  mkdirSync(filesDir, { recursive: true });
  const names = new Set();
  let mappingCount = 0;

  function writeBody(name, body, templated) {
    const text = typeof body === "string" ? body : JSON.stringify(body);
    // Templated mappings run their body through Handlebars as well: a literal `{{` would be parsed as an expression.
    if (templated && text.includes("{{")) throw new Error(`body ${name} contains '{{' but is served with response templating`);
    writeFileSync(path.join(filesDir, `${name}.json`), text);
  }

  function writeMapping(name, m) {
    if (names.has(name)) throw new Error(`duplicate mapping name ${name}`);
    names.add(name);
    mappingCount++;
    writeFileSync(path.join(mappingsDir, `${name}.json`), `${JSON.stringify(m, null, 2)}\n`);
  }

  function mapping({ name, method = "GET", urlPath, urlPathPattern, query, bodyPatterns, headers, priority = PRIORITY.normal, state, status = 200, bodyFile, respHeaders = {}, templated = false }) {
    const request = { method };
    if (urlPath) request.urlPath = urlPath;
    if (urlPathPattern) request.urlPathPattern = urlPathPattern;
    if (query) request.queryParameters = query;
    if (headers) request.headers = headers;
    if (bodyPatterns) request.bodyPatterns = bodyPatterns;
    const m = { priority, request, response: { status, headers: { ...JSON_HEADERS, ...respHeaders }, bodyFileName: `${bodyFile}.json` } };
    if (templated) m.response.transformers = ["response-template"];
    if (state) {
      m.scenarioName = SCENARIO;
      m.requiredScenarioState = state;
    }
    writeMapping(name, m);
  }

  const equalTo = (v) => ({ equalTo: String(v) });
  const queryOf = (q = {}) => Object.fromEntries(Object.entries(q).map(([k, v]) => [k, v && typeof v === "object" ? v : equalTo(v)]));

  /** One non-paged response. `extraPaths`: more `urlPathPattern`s answered with the same body file. */
  function single(name, d, state) {
    writeBody(name, d.body, false);
    const base = { status: d.status ?? 200, method: d.method ?? "GET", query: d.query ? queryOf(d.query) : undefined, bodyPatterns: d.bodyPatterns, priority: d.priority ?? PRIORITY.normal, state, bodyFile: name };
    mapping({ name, urlPath: d.urlPath, urlPathPattern: d.urlPathPattern, ...base });
    (d.extraPaths ?? []).forEach((p, i) => mapping({ name: `${name}-alt${i + 1}`, urlPathPattern: p, ...base }));
  }

  /** A paged list: `items` split into pages of PER_PAGE with the real offset-pagination headers and an absolute `Link`. */
  function paged(name, d, state) {
    const perPage = PER_PAGE;
    const pages = Math.max(1, Math.ceil(d.items.length / perPage));
    const linkBase = d.linkPath ?? d.urlPath;
    const link = (page) => {
      const params = { ...(d.linkParams ?? {}), page, per_page: perPage };
      const qs = Object.keys(params).filter((k) => params[k] !== undefined).sort().map((k) => `${k}=${params[k]}`).join("&");
      return `{{request.baseUrl}}${linkBase}?${qs}`;
    };
    for (let page = 1; page <= pages; page++) {
      const suffix = `-page-${page}`;
      const items = d.items.slice((page - 1) * perPage, page * perPage);
      const rels = [];
      if (page > 1) rels.push(`<${link(page - 1)}>; rel="prev"`);
      if (page < pages) rels.push(`<${link(page + 1)}>; rel="next"`);
      rels.push(`<${link(1)}>; rel="first"`, `<${link(pages)}>; rel="last"`);
      const respHeaders = {
        "X-Next-Page": page < pages ? String(page + 1) : "", "X-Page": String(page), "X-Per-Page": String(perPage), "X-Prev-Page": page > 1 ? String(page - 1) : "",
        "X-Total": String(d.items.length), "X-Total-Pages": String(d.items.length === 0 ? 0 : pages), Link: rels.join(", "),
      };
      writeBody(`${name}${suffix}`, items, true);
      const query = { ...queryOf(d.query), ...(page > 1 ? { page: equalTo(page) } : {}) };
      const common = { query, priority: page > 1 ? PRIORITY.page : PRIORITY.normal, state, bodyFile: `${name}${suffix}`, respHeaders, templated: true, bodyPatterns: undefined };
      mapping({ name: `${name}${suffix}`, urlPath: d.urlPath, urlPathPattern: d.urlPathPattern, ...common });
      (d.extraPaths ?? []).forEach((p, i) => mapping({ name: `${name}-alt${i + 1}${suffix}`, urlPathPattern: p, ...common }));
    }
  }

  const write = (name, d, state) => (d.type === "paged" ? paged(name, d, state) : single(name, d, state));

  /** Emit `describe('Started')` / `describe('day2')`: once if identical, else one gated mapping set per state. */
  function emit(name, describe) {
    const a = describe("Started");
    const b = describe("day2");
    const ja = a ? JSON.stringify(a) : null;
    const jb = b ? JSON.stringify(b) : null;
    if (ja === jb) {
      if (a) write(name, a, undefined);
      return;
    }
    if (a) write(`${name}-started`, a, "Started");
    if (b) write(`${name}-day2`, b, "day2");
  }

  /** Static mapping straight from a mapping object (auth and catch-alls). */
  function raw(name, m, body) {
    if (body !== undefined) writeBody(m.response.bodyFileName.replace(/\.json$/, ""), body, false);
    writeMapping(name, m);
  }

  return { emit, write, single, paged, raw, count: () => mappingCount, equalTo };
}
