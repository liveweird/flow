// Reports API — every report is any-authenticated read-only (D12). Thin wrappers: transport in
// ./http, types from ./schema. The query string is the shared report filter's serialization
// (utils/reportFilter.ts) — one parser server-side, one (de)serializer here.

import { jsonRequest } from "./http";
import type { paths } from "./schema";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";

export type ReportFilters = paths["/api/v1/reports/filters"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReportFilterTeam = ReportFilters["teams"][number];
export type ReportMeta = VelocityReport["meta"];
export type VelocityReport = paths["/api/v1/reports/velocity"]["get"]["responses"]["200"]["content"]["application/json"];
export type VelocitySprint = VelocityReport["sprints"][number];
export type VelocityGroup = VelocityReport["groups"][number];

export type ThroughputReport = paths["/api/v1/reports/throughput"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThroughputSprint = ThroughputReport["bySprint"][number];
export type ThroughputBucketRow = ThroughputReport["byBucket"][number];
export type ThroughputGroup = ThroughputReport["groups"][number];
export type SprintConsistencyReport =
  paths["/api/v1/reports/sprint-consistency"]["get"]["responses"]["200"]["content"]["application/json"];
export type SprintConsistencySprint = SprintConsistencyReport["sprints"][number];
export type SprintConsistencyGroup = SprintConsistencyReport["groups"][number];

export async function getReportFilters(): Promise<ReportFilters> {
  return jsonRequest<ReportFilters>("/api/v1/reports/filters");
}

export async function getVelocityReport(filter: ReportFilterState): Promise<VelocityReport> {
  return jsonRequest<VelocityReport>(`/api/v1/reports/velocity?${reportQuery(filter)}`);
}

export async function getThroughputReport(filter: ReportFilterState): Promise<ThroughputReport> {
  return jsonRequest<ThroughputReport>(`/api/v1/reports/throughput?${reportQuery(filter)}`);
}

export async function getSprintConsistencyReport(filter: ReportFilterState): Promise<SprintConsistencyReport> {
  return jsonRequest<SprintConsistencyReport>(`/api/v1/reports/sprint-consistency?${reportQuery(filter)}`);
}
