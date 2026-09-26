---
name: verify
description: Drive the Flow SPA end-to-end with Playwright to observe a change working against the local dev stack. Use after nontrivial frontend/backend changes, before committing.
---

# Verifying changes end-to-end

## Handle

The surface is the SPA in a browser. Use the running local dev stack (preferred per project
convention): `docker compose up postgres mailpit`, start the server with `./gradlew :server:run`,
and start Vite with `cd web && npm run dev`. Drive `http://localhost:5176` (Vite serves the
edited source with HMR; `/api` proxies to :8084). Check what's already up first:
`lsof -nP -iTCP:8084 -iTCP:5176 -sTCP:LISTEN` — reuse a healthy stack, and remember stray
`:server:run` JVMs squat :8084. Flow's ports deliberately avoid Lettuce's (8080/5173/5432),
Toadie's (8081/5174/5433) and Covenant's (8082/5175/5434), so double-check WHICH app answers
before concluding anything.

No Chrome-extension automation required: Playwright is installed in `e2e/node_modules`. A scratch
script can import it directly:

```js
import { chromium } from "/<repo>/e2e/node_modules/playwright/index.mjs";
```

Chromium binaries are already installed (the e2e suite uses them).

## Drive recipe (gotchas that cost time)

- **Leftover sessions block the login form.** While `flow.auth.*` localStorage keys exist,
  `RedirectIfAuthed` bounces `/login` to the home page and a `fill()` waits out the whole
  timeout. Clear first (the `e2e/tests/helpers.ts` trick): `await page.goto("/login");
  await page.evaluate(() => localStorage.clear()); await page.goto("/login");`.
- **Mantine locators:** `getByLabel(/password/i)` is a strict-mode violation (matches the
  visibility-toggle button too). Use `getByRole("textbox", { name: ... })`.
- **Login:** seed admin `admin@flow.local` / `changeme`. Keep logins to a minimum — the per-IP
  `/login` rate limit produces roaming 429s (though the dev stack lifts it to 1000/min; see
  below). Five consecutive FAILED logins for one email lock that account for 15 minutes
  (in-memory — restarting the server clears it).
- **Language probe:** switch via the account menu's Language section; the choice persists in
  `localStorage` (`flow.lang`) AND is saved on the user (`PUT /users/{id}/language`), so a
  re-login restores it — a hand-set `flow.lang` + reload probes only the UI half.
- **Lazy-route fill race (production bundle only):** after clicking a link to another SPA route,
  `waitForURL` passes while the OLD page is still rendered (React Router flips the URL before the
  lazy chunk mounts — instant in Vite dev, slow enough to bite against the built bundle). A
  locator that matches fields on both pages silently fills the old page's input, which then
  unmounts. Always `waitFor()` an element unique to the target page before filling.
- **Rate-limit self-interference:** `/login` and `/refresh` have per-IP token buckets (10/min —
  lifted to 1000/min in development mode — and 30/min). Curl "warm-up probes" against those
  endpoints eat the budget of the Playwright run that follows — probe readiness via `GET /`
  instead, or `docker restart flow-app` to reset the in-memory buckets.

## Cleanup

Verification may create records (users, teams). Give owned records a recognizable marker such as
`verify.` and clean up through the normal API in reverse dependency order: team memberships and
teams, then throwaway users. Use the owning API actions and soft-delete behavior; do not issue
blanket SQL deletes or mutate unrelated data. Never run `docker compose down -v` for
verification. Never mutate the seed admin — if a probe changed its password, restore the V3 state
(hash in `infra/db/Bootstrap.kt`, or `TestSeedState.restoreSeedAccounts()` from a test).
