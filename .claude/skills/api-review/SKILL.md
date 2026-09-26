---
name: api-review
description: Validate an OpenAPI spec (default Flow's own) against api-guidelines/API-GUIDELINES.md — Spectral lint pass plus the LLM review checklist, reported per rule ID.
---

# API review against the guidelines

Validate an OpenAPI document against the authoritative API standard in
`api-guidelines/API-GUIDELINES.md`, in two passes. The target spec is the argument if one was
given; otherwise `server/src/main/resources/openapi/documentation.yaml`.

## Pass 1 — mechanical (Spectral)

```sh
npx --yes @stoplight/spectral-cli lint <target-spec> \
  --ruleset api-guidelines/api-guidelines.spectral.yaml
```

Interpret by severity (the model is documented in `api-guidelines/README.md`):

- **error** — a real violation; report it as a finding, citing the rule ID from the message.
  A conformant spec (including the current project spec) has **0 errors** — any error on the
  project spec is new drift.
- **warning** — expected only from known-gaps-register items that fire once per document
  (`x-sla`, `info.termsOfService`). Report unexpected warnings as findings; expected ones as
  "registered gap".
- **hint** — registered noisy gaps (`X-Request-Id`, `ETag`, `Idempotency-Key`,
  `Retry-After`). Do not report them individually; one summary line ("N hints from
  registered gaps") suffices.

## Pass 2 — review (LLM checklist)

Read `api-guidelines/API-GUIDELINES.md` and work through its **LLM review checklist**
section against the target spec — and, where a check concerns runtime behavior (whitelist
enforcement, guard-before-validate ordering, transaction-consistent totals, disclosure
policy), against the route code under `server/src/main/kotlin/`. For each checklist item
return **pass / fail / N-A** with the rule ID; a fail needs a one-line justification with a
file/spec citation. (Say N-A explicitly where a check has no subject yet — e.g. list-facet
checks before a facet-bearing endpoint lands — rather than skipping it silently.)

Items on the rulebook's **known-gaps register** (its final appendix) are reported as
**"registered gap"**, never as findings. If a gap turns out to be closed in code, say so and
suggest removing the register row (and promoting the corresponding hint-severity lint rule).

## Report

End with: findings ranked by severity (rule ID + location + one-line defect), then the
registered-gap summary, then the checklist pass counts. If there are zero findings, say the
spec conforms and give the counts.
