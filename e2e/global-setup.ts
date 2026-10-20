import { execSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE_URL } from "./playwright.config";
import { createSharedFixture } from "./shared-fixture.ts";
import { ensureStack } from "./stack.ts";
import { apiAsAdmin, stubDataSourceBody } from "./tests/helpers";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");

async function responds(url: string): Promise<boolean> {
  try {
    // The readiness probe: 200 only once the app answers AND its database round trip works.
    const res = await fetch(`${url}/api/v1/ready`, { redirect: "manual" });
    return res.ok;
  } catch {
    return false;
  }
}

async function waitForUp(url: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await responds(url)) return;
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`Timed out waiting for the app at ${url}`);
}

export default async function globalSetup(): Promise<void> {
  await ensureStack(BASE_URL, {
    responds,
    start: () => {
      console.log("[e2e] Starting the stack: docker compose up -d --build …");
      // The DERIVE debounce (V27) would make every configuration edit wait 45 s before the specs see its effect, and the
      // 15 s claim tick would add up to that much to every queued job.
      const env = {
        ...process.env,
        INGEST_DERIVE_QUIET_SECONDS: process.env.INGEST_DERIVE_QUIET_SECONDS ?? "1",
        INGEST_SCHEDULER_TICK_SECONDS: process.env.INGEST_SCHEDULER_TICK_SECONDS ?? "5",
      };
      execSync("docker compose up -d --build", { cwd: repoRoot, stdio: "inherit", env });
    },
    wait: (url) => waitForUp(url, 240_000),
  });
  console.log(`[e2e] Using ${BASE_URL}; services and volumes will be left intact.`);

  // The shared read-only fixture (a synced stub connection + its team): created here, its SYNC enqueued but not awaited (the first
  // consumer finishes the preparation), exported through process.env to the workers, deleted by global-teardown.ts.
  const { api } = await apiAsAdmin();
  try {
    const fixture = await createSharedFixture(api, stubDataSourceBody);
    console.log(`[e2e] Shared fixture: data source ${fixture.dataSourceId}, team ${fixture.teamId}, SYNC job ${fixture.syncJobId} enqueued.`);
  } finally {
    await api.dispose();
  }
}
