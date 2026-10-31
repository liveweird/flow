import { defineConfig, devices } from "@playwright/test";

// Blackbox E2E: drives a real browser against the full stack (SPA + server + Postgres) served
// single-origin at http://localhost:8084 by `docker compose`. The stack is brought up by
// global-setup (unless one is already running locally, which is reused). Services and data
// remain intact after the run; starting containers does not establish ownership of volumes.
// 8084 — see docker-compose.yaml (the app's fixed demo port).
export const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:8084";
/** The Mailpit catcher of the stack under test (the two email specs read it); the compose default is host port 8028. */
export const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? "http://localhost:8028";

// Dispatch order (build-times.md WHY 37). The runner queues test groups in PROJECT order, and inside a project in file-name order, so a
// plain run starts `reports.spec.ts` (the longest chain: its own sync, then 32 tests) last and ends with it alone on one worker. Three
// projects with the same browser put the long chains first and the filler last; they are NOT isolation boundaries (all share one stack,
// the rulebook in README "Parallel execution" is unchanged) and nothing depends on the names.
//  - `first`: `accessibility` (the longest file that needs no data, so the sync waits of the others overlap it), `reports` and `code-identities` (it owns a GitLab sync).
//  - `chromium`: every other file, alphabetical.
//  - `last`: `accessibility-data`, split into four groups (its describe is `parallel`): it needs the shared fixture, which is long ready by
//    the time a worker reaches it, and four ~equal groups are what fills the tail of the run evenly.
const FIRST = /[\\/](accessibility|reports|code-identities)\.spec\.ts$/;
const LAST = /[\\/]accessibility-data\.spec\.ts$/;
const desktopChrome = { ...devices["Desktop Chrome"] };

export default defineConfig({
  testDir: "./tests",
  // The serial unit is the FILE (fullyParallel stays false so a file's tests may be
  // order-dependent). Files run in parallel; every spec file must own its server-side
  // state exclusively (or only READ the shared fixture) — see "Parallel execution" in the README before adding a spec.
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 4),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  globalSetup: "./global-setup.ts",
  globalTeardown: "./global-teardown.ts",
  use: {
    baseURL: BASE_URL,
    // retain-on-failure, not on-first-retry: retries are 0 locally, so on-first-retry never
    // fires and local failures would produce no trace at all. A trace beats the video it
    // replaces (DOM snapshots, network, console) and costs nothing on a passing run.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "first", testMatch: FIRST, use: desktopChrome },
    { name: "chromium", testIgnore: [FIRST, LAST], use: desktopChrome },
    { name: "last", testMatch: LAST, use: desktopChrome },
  ],
});
