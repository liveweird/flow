// The shared read-only fixture (README "Parallel execution"): ONE Jira-stub connection and the team its FLO board is mapped to,
// created by global setup, read by `reports-drill`, `reports-compare` and `accessibility-data` (and its synced raw/norm data by
// `metrics-config`), deleted by global teardown. Global setup creates both and enqueues the SYNC without waiting for it; the first
// consumer finishes the preparation (`helpers.ts`'s `sharedFixtureReady`). It is never mutated by a consumer.
import { randomUUID } from "node:crypto";
import type { APIRequestContext } from "@playwright/test";

const NAME_PREFIX = "e2e-shared";
/**
 * Residue older than this is swept at setup. A younger one may belong to a run still going against the same stack, so it stays;
 * an aborted run's connection is harmless once paused; one aborted before a consumer paused it stays ENABLED (an hourly stub re-sync,
 * and it joins DERIVE fan-outs) until the next setup past this age removes it.
 */
const STALE_AFTER_MS = 60 * 60 * 1000;

export interface SharedFixture {
  dataSourceId: number;
  dataSourceName: string;
  teamId: number;
  teamName: string;
  /** The SYNC job global setup enqueued: the consumers wait for it by id. */
  syncJobId: number;
}

/**
 * How global setup hands the fixture to the workers: Playwright forks each worker from the runner process AFTER global setup, with
 * the runner's `process.env` at that moment, so what setup exports here is what every worker reads.
 */
const ENV = {
  dataSourceId: "E2E_SHARED_DS_ID",
  dataSourceName: "E2E_SHARED_DS_NAME",
  teamId: "E2E_SHARED_TEAM_ID",
  teamName: "E2E_SHARED_TEAM_NAME",
  syncJobId: "E2E_SHARED_SYNC_JOB_ID",
} as const;

function exportToEnv(fixture: SharedFixture): void {
  process.env[ENV.dataSourceId] = String(fixture.dataSourceId);
  process.env[ENV.dataSourceName] = fixture.dataSourceName;
  process.env[ENV.teamId] = String(fixture.teamId);
  process.env[ENV.teamName] = fixture.teamName;
  process.env[ENV.syncJobId] = String(fixture.syncJobId);
}

/** The fixture global setup exported; throws (naming the variable) when a consumer runs without it. */
export function sharedFixtureFromEnv(): SharedFixture {
  const read = (key: string): string => {
    const value = process.env[key];
    if (value === undefined || value === "") throw new Error(`${key} is not set — the shared fixture is created by global-setup.ts`);
    return value;
  };
  return {
    dataSourceId: Number(read(ENV.dataSourceId)),
    dataSourceName: read(ENV.dataSourceName),
    teamId: Number(read(ENV.teamId)),
    teamName: read(ENV.teamName),
    syncJobId: Number(read(ENV.syncJobId)),
  };
}

/** DELETE tolerant of a row that is already gone (404); any other failure is logged, never thrown — a cleanup must not fail the run. */
async function deleteQuietly(api: APIRequestContext, path: string): Promise<void> {
  try {
    const status = (await api.delete(path)).status();
    if (status !== 204 && status !== 404) console.warn(`[e2e] Shared fixture cleanup: DELETE ${path} answered ${status}`);
  } catch (failure) {
    console.warn(`[e2e] Shared fixture cleanup: DELETE ${path} failed (${String(failure)})`);
  }
}

/** Deletes the `e2e-shared-*` rows an earlier, aborted run left behind (the ones past `STALE_AFTER_MS`). */
async function sweepStale(api: APIRequestContext): Promise<void> {
  const cutoff = Date.now() - STALE_AFTER_MS;
  // The connection first, then the team its board pointed at.
  for (const [list, prefix] of [
    ["data-sources", `${NAME_PREFIX}-ds-`],
    ["teams", `${NAME_PREFIX}-team-`],
  ] as const) {
    const res = await api.get(`/api/v1/${list}?name=${prefix}&pageSize=100`);
    if (!res.ok()) continue;
    const items = ((await res.json()).items ?? []) as { id: number; name: string; createdAt: number }[];
    for (const item of items) {
      if (item.name.startsWith(prefix) && item.createdAt < cutoff) await deleteQuietly(api, `/api/v1/${list}/${item.id}`);
    }
  }
}

/**
 * Global setup: sweeps stale residue, creates the connection and team, POSTs the SYNC and exports the fixture to `process.env`
 * WITHOUT waiting for the sync. `dataSourceBody` is the stub connection's create body (`helpers.ts`'s `stubDataSourceBody`).
 */
export async function createSharedFixture(api: APIRequestContext, dataSourceBody: (name: string) => object): Promise<SharedFixture> {
  await sweepStale(api);
  const tag = randomUUID().slice(0, 8);
  const dataSourceName = `${NAME_PREFIX}-ds-${tag}`;
  const teamName = `${NAME_PREFIX}-team-${tag}`;
  let dataSourceId: number | undefined;
  let teamId: number | undefined;
  try {
    const dataSource = await api.post("/api/v1/data-sources", { data: dataSourceBody(dataSourceName) });
    if (!dataSource.ok()) throw new Error(`creating the shared data source answered ${dataSource.status()}: ${await dataSource.text()}`);
    dataSourceId = ((await dataSource.json()) as { id: number }).id;
    const team = await api.post("/api/v1/teams", { data: { name: teamName } });
    if (!team.ok()) throw new Error(`creating the shared team answered ${team.status()}: ${await team.text()}`);
    teamId = ((await team.json()) as { id: number }).id;
    const sync = await api.post(`/api/v1/data-sources/${dataSourceId}/sync-jobs`, { data: { kind: "SYNC" } });
    if (!sync.ok()) throw new Error(`enqueuing the shared SYNC answered ${sync.status()}: ${await sync.text()}`);
    const fixture: SharedFixture = { dataSourceId, dataSourceName, teamId, teamName, syncJobId: ((await sync.json()) as { job: { id: number } }).job.id };
    exportToEnv(fixture);
    return fixture;
  } catch (failure) {
    // A setup that throws gets no teardown: remove what exists here.
    if (dataSourceId !== undefined) await deleteQuietly(api, `/api/v1/data-sources/${dataSourceId}`);
    if (teamId !== undefined) await deleteQuietly(api, `/api/v1/teams/${teamId}`);
    throw failure;
  }
}

/** Global teardown: the connection, then the team its board pointed at; a no-op when setup never exported a fixture. */
export async function removeSharedFixture(api: APIRequestContext): Promise<void> {
  const dataSourceId = process.env[ENV.dataSourceId];
  const teamId = process.env[ENV.teamId];
  if (dataSourceId) await deleteQuietly(api, `/api/v1/data-sources/${dataSourceId}`);
  if (teamId) await deleteQuietly(api, `/api/v1/teams/${teamId}`);
}
