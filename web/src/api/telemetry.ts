// Telemetry API — Flow's own vital signs (ADMIN only, `.claude/docs/observability.md` "Telemetry"): the latest figures of every
// sampling process and the 24 h / 7 d / 90 d trend of one panel section. Thin wrappers: transport (authedFetch, which attaches
// the timeout signal) in ./http, types from ./schema.

import { buildQuery, jsonRequest } from "./http";
import type { paths } from "./schema";

const CURRENT = "/api/v1/telemetry/current";
const HISTORY = "/api/v1/telemetry/history";

export type TelemetryCurrent = paths[typeof CURRENT]["get"]["responses"]["200"]["content"]["application/json"];
export type TelemetryProcess = TelemetryCurrent["processes"][number];
export type TelemetryConnection = TelemetryCurrent["connections"][number];
export type TelemetryRouteStats = TelemetryCurrent["requests"]["total"];

export type TelemetryHistory = paths[typeof HISTORY]["get"]["responses"]["200"]["content"]["application/json"];
export type TelemetrySeries = TelemetryHistory["series"][number];
export type TelemetryGroup = TelemetryHistory["group"];
export type TelemetryRange = TelemetryHistory["range"];

export async function getTelemetryCurrent(signal?: AbortSignal): Promise<TelemetryCurrent> {
  return jsonRequest<TelemetryCurrent>(CURRENT, { signal });
}

export async function getTelemetryHistory(
  group: TelemetryGroup,
  range: TelemetryRange,
  signal?: AbortSignal,
): Promise<TelemetryHistory> {
  return jsonRequest<TelemetryHistory>(`${HISTORY}?${buildQuery({ group, range })}`, { signal });
}
