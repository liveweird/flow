import { removeSharedFixture } from "./shared-fixture.ts";
import { apiAsAdmin } from "./tests/helpers";

// Deletes the shared read-only fixture global setup created. Never touches the stack itself, and never fails the run: a resource that
// is already gone (404) is fine, any other failure is logged by `removeSharedFixture`.
export default async function globalTeardown(): Promise<void> {
  try {
    const { api } = await apiAsAdmin();
    try {
      await removeSharedFixture(api);
    } finally {
      await api.dispose();
    }
  } catch (failure) {
    // An unreachable stack or a failed login: the next setup's stale sweep removes the residue.
    console.warn(`[e2e] Shared fixture teardown skipped (${String(failure)})`);
  }
}
