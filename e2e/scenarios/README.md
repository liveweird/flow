# E2E scenarios — versioned test-design artifacts

One markdown file per spec file: `scenarios/auth.md` describes `tests/auth.spec.ts`. These are
**design artifacts, not executable tests** — deliberately NOT Gherkin/Cucumber (no step-definition
glue to rot). They exist so a test's *intent* is reviewable, diffable, and writable by anyone —
including someone (or some agent) who will later compile a new scenario into spec code.

**The same-commit rule**: a new or behaviorally changed `test()` lands with its scenario file
updated **in the same commit**. `npm run check:scenarios` enforces the parity; a spec without a
scenario file is a review failure.

## Format

```md
# <Journey name — short, human>

- **Spec**: [tests/<name>.spec.ts](../tests/<name>.spec.ts)
- **Actors**: <seed accounts and/or throwaway users involved>
- **Owns** (exclusive server-side state): <what this file may mutate, per ../README.md's
  Parallel execution rulebook; "nothing — read-only" when applicable>

## Scenario: <the test() title, VERBATIM>

1. <numbered prose steps: actor → action, in user terms>
   - *Expected*: <the observable consequence asserted at that step>

## Not covered here (and why)

<per-journey deliberate exclusions — only when there are any>
```

Rules:

- **The `## Scenario:` heading must equal the `test()` title verbatim** — that is the
  traceability link, greppable in both directions. One `## Scenario` section per test in the file.
  The ONE registered exception: a spec that generates tests from a list (today only
  `accessibility.spec.ts`, one `test(`\`${path} has no WCAG A/AA violations\``)` per page) keeps a
  single scenario section whose heading uses the template placeholder (`<path>`), since there is
  no literal title to mirror.
