import { execSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE_URL } from "./playwright.config";
import { ensureStack } from "./stack.ts";

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
      execSync("docker compose up -d --build", { cwd: repoRoot, stdio: "inherit" });
    },
    wait: (url) => waitForUp(url, 240_000),
  });
  console.log(`[e2e] Using ${BASE_URL}; services and volumes will be left intact.`);
}
