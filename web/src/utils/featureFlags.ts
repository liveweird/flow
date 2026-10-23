import type { FlowUserPage } from "../api/flowUsers";
import type { Feature } from "../api/session";

/** One row of the Flow-user list, as the /feature-flags screen reads and rewrites it. */
export type FlowUserRow = FlowUserPage["items"][number];

/** The wholesale-replace payload's inverse: a feature is ENABLED unless it is in the user's disabled set. */
export function isFeatureEnabled(disabled: readonly Feature[], feature: Feature): boolean {
  return !disabled.includes(feature);
}

/** The new disabled set after switching one feature on or off (the others keep their state). */
export function withFeatureEnabled(
  disabled: readonly Feature[],
  feature: Feature,
  enabled: boolean,
): Feature[] {
  const rest = disabled.filter((f) => f !== feature);
  return enabled ? rest : [...rest, feature];
}
