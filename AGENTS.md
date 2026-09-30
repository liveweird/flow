# Repository Guidelines

This file is the Codex entry point and deliberately a pointer: the conventions live in one place
(`CLAUDE.md`, `web/CLAUDE.md`, `.claude/docs/`) so they cannot drift here. Do not copy feature,
package, migration-range or command summaries into this file.

## Read first

1. `CLAUDE.md` (repo root) — product and roadmap, commands, architecture, package layout, the
   feature template, the OpenAPI contract. Its "Cross-cutting conventions" table lists the topic
   docs in `.claude/docs/`; read the row for the area you touch **in full** before editing it.
2. `web/CLAUDE.md` for anything under `web/` (transport, typed i18n with EN/PL parity, theming and
   the colour vocabulary, test setup).
3. `api-guidelines/API-GUIDELINES.md` for any API work; cite its stable rule IDs in reviews.
4. `e2e/README.md` and `e2e/scenarios/README.md` for Playwright work.

If a document and the executable configuration (code, `application.yaml`, CI, detekt/eslint
config) disagree, the configuration wins; fix the document in the same change.

## Codex-specific notes

- `CLAUDE.md` uses Claude's `@...` import syntax for the always-loaded docs
  (`.claude/docs/testing.md`, `.claude/docs/list-endpoints.md`). Codex does not expand it — open
  those files directly, along with the rest of the docs table.
- The playbooks in `.claude/skills/` (`api-review`, `run-stack`, `verify`) are plain Markdown and
  usable outside Claude.
- Commit subjects are Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `ci:`, scopes such
  as `fix(e2e):`). A PR explains behaviour and risk, lists the verification commands run, and
  shows screenshots for UI changes.
- Never edit an applied Flyway migration or run DDL at runtime — add a new `V<n>` migration
  (`.claude/docs/persistence.md`); delete any dev-database records you create while verifying.
- Indentation is four spaces in Kotlin and two in `web/` and `e2e/`; backend test classes are
  named `*Test`; detekt's line-length gate is 140 (`config/detekt/detekt.yml`).
- Never commit production secrets; the committed `changeme` values and development keys are burned
  demo credentials that production mode refuses (see `.claude/docs/security.md`).
- Toolchains: run `mise install` once (JDK 21, Node 24); `./gradlew` needs Docker for the server
  tests (`DOCKER_HOST` recipe in `CLAUDE.md`); web installs with
  `npm install --legacy-peer-deps`. Never package with `buildFatJar`.
