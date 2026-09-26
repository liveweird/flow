# API Guidelines

The **single authoritative standard** for this project's JSON HTTP APIs — merged from a
general, validatable API rulebook and the conventions the codebase already implements. It
covers document shape (bespoke flat JSON DTOs), resource & URL design (updates via `PUT`
full replace), URL versioning (`/api/v1/`) & compatibility, collections (offset pagination
in the `{items,page,pageSize,total}` envelope, `sort`, whitelisted filtering), naming,
data-format conventions, success codes, RFC 7807 errors, authentication & authorization,
caching & concurrency, rate limiting, idempotency, input & transport security, HTTP
protocol, machine-readable SLA/legal terms, OpenAPI-as-contract, and spec ↔ implementation
conformance. The general rules are portable; Flow-specific exceptions and remaining gaps are identified explicitly.

## Contents

| File | What it is |
|---|---|
| [`API-GUIDELINES.md`](./API-GUIDELINES.md) | The normative rulebook. Every rule has a stable ID (e.g. `API-LIST-002`), a MUST/SHOULD statement, and a concrete check. Ends with the [known-gaps register](./API-GUIDELINES.md#appendix-known-gaps-register). |
| [`api-guidelines.spectral.yaml`](./api-guidelines.spectral.yaml) | A [Spectral](https://stoplight.io/open-source/spectral) ruleset encoding the machine-checkable subset, runnable against any OpenAPI 3.x spec. |
| [`examples/conformant.yaml`](./examples/conformant.yaml) | A minimal spec that lints fully clean — including the gap-register hints — the reference for "conformant" and a copy-paste starting point for a new resource. |

## How to validate an API against it

Two complementary passes — together they cover the whole rulebook. The `/api-review` skill
runs both.

### 1. Mechanical — run the linter

Checks the structural rules tagged `[spectral]` / `[both]`:

```sh
npx @stoplight/spectral-cli lint server/src/main/resources/openapi/documentation.yaml \
  --ruleset api-guidelines/api-guidelines.spectral.yaml

# Sanity-check the ruleset itself against the conformant fixture (fully clean, incl. hints):
npx @stoplight/spectral-cli lint api-guidelines/examples/conformant.yaml \
  --ruleset api-guidelines/api-guidelines.spectral.yaml
```

Violations cite the guideline ID (e.g. *"...(API-ERR-001)"*). Severity model:

- **error** — a real violation of the standard; must be fixed. **A conformant spec lints
  with 0 errors** (the project spec does).
- **warn** — SHOULD-level rules plus once-per-document known-gap items (`x-sla`,
  `info.termsOfService`). Expected warnings map 1:1 onto the gaps register.
- **hint** — known-gap items that would otherwise fire on every operation (`X-Request-Id`,
  `ETag`, `Idempotency-Key`, `Retry-After`). Informational until the gap is closed; promote
  the rule's severity when adopting the header.

> **Transport & protocol rules are review-only.** `API-SEC` (HTTPS/TLS, injection, XSS) and
> `API-HTTP` (HTTP/2 & HTTP/3) describe runtime behavior a spec linter cannot see — there
> are no Spectral rules for them. They live in the rulebook and the review checklist.

### 2. Review — feed the rulebook to an LLM (or a human)

The `[llm/manual]` rules encode semantics, runtime, and transport a linter can't see —
envelope-total consistency, whitelist enforcement, backward-compatible evolution,
guard-before-validate ordering, existence-disclosure discipline, HTTPS/TLS. Hand
`API-GUIDELINES.md` plus the target OpenAPI document (or the route code) to a reviewer and
have it work through the [**LLM review checklist**](./API-GUIDELINES.md#llm-review-checklist),
returning pass / fail / N-A + a cited rule ID per item. Items on the known-gaps register are
reported as "registered gap", never as findings.

## Provenance & decision record

This standard began as a prescriptive **JSON:API 1.1** rulebook drafted while reviewing this
codebase, which was then **merged** with the project's committed conventions into one set
(2026-07-24). Where the two disagreed, the implemented house convention was deliberately
chosen after weighing the alternatives:

| Topic | Chosen | Over | Rationale |
|---|---|---|---|
| Document format | Bespoke flat JSON DTOs, `application/json` | JSON:API document (`vnd.api+json`, `data`/`attributes`) | JSON:API's benefits (many independent consumers, `include`, sparse fieldsets) don't materialize for a single first-party SPA with a typed OpenAPI contract |
| Errors | RFC 7807 `application/problem+json` | JSON:API `errors` array | An IETF standard in its own right; centrally implemented; follows from the document-format choice |
| Pagination | Offset (`page`/`pageSize` + envelope), cursor as per-endpoint opt-in | Opaque cursors as the rule | Page-number UIs and easy totals are in active use; cursor advantages (write-stability, large-offset cost) apply per-endpoint, not globally |
| Versioning | URL `/api/v1/` | `API-Version` header | One version, one client — path versioning's simplicity and visibility win; header versioning pays off only with concurrent majors |
| Updates | `PUT` full-document replace | `PATCH` | Every update endpoint is a documented full replace; `PUT` is the semantically correct method for that |

Rules the codebase does not yet satisfy (correlation-id echo, `Retry-After`, `ETag`/`304`,
conditional writes (`If-Match`/`412`), `Idempotency-Key`, HTTP/2, SLA metadata, the 409
`instance` URI on generic unique-violations, and the inline-vs-`$ref` error declarations)
are kept as rules with their non-conformance **accepted and registered** in the
[known-gaps register](./API-GUIDELINES.md#appendix-known-gaps-register) — each with an
adoption pointer. Closing a gap = implement + declare in the spec + remove the register row
(+ promote the corresponding hint-severity lint rule).
