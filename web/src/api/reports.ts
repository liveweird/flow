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

export type Distribution = TaskEstimationAccuracyReport["atStart"];
export type TaskEstimationAccuracyReport =
  paths["/api/v1/reports/task-estimation-accuracy"]["get"]["responses"]["200"]["content"]["application/json"];
export type TaskAccuracyGroup = TaskEstimationAccuracyReport["groups"][number];
export type EpicEstimationAccuracyReport =
  paths["/api/v1/reports/epic-estimation-accuracy"]["get"]["responses"]["200"]["content"]["application/json"];
export type EpicAccuracyRow = EpicEstimationAccuracyReport["epics"][number];
export type EpicAccuracyGroup = EpicEstimationAccuracyReport["groups"][number];
export type EstimateAdjustmentsReport =
  paths["/api/v1/reports/estimate-adjustments"]["get"]["responses"]["200"]["content"]["application/json"];
export type AdjustmentFigures = EstimateAdjustmentsReport["tasks"];
export type EstimateAdjustmentsGroup = EstimateAdjustmentsReport["groups"][number];

export type CycleTimeReport = paths["/api/v1/reports/cycle-time"]["get"]["responses"]["200"]["content"]["application/json"];
export type CycleTimeTrendBucket = CycleTimeReport["trend"][number];
export type CycleTimeGroup = CycleTimeReport["groups"][number];
export type EpicCycleTime = CycleTimeReport["epics"];
export type EpicCycleTimeGroup = EpicCycleTime["groups"][number];
export type ReportedTimeRatioReport =
  paths["/api/v1/reports/reported-time-ratio"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReportedTimeGroup = ReportedTimeRatioReport["groups"][number];

export type WipReport = paths["/api/v1/reports/wip"]["get"]["responses"]["200"]["content"]["application/json"];
export type WipKey = WipReport["keys"][number];
export type WipPoint = WipReport["series"][number];
export type BacklogReport = paths["/api/v1/reports/backlog"]["get"]["responses"]["200"]["content"]["application/json"];
export type BacklogCurrent = BacklogReport["current"];
export type BacklogPoint = BacklogReport["trend"][number];

export type AgingWipReport = paths["/api/v1/reports/aging-wip"]["get"]["responses"]["200"]["content"]["application/json"];
export type AgingItem = AgingWipReport["items"][number];
export type AgingThresholds = AgingWipReport["thresholds"];
export type BlockedTimeReport = paths["/api/v1/reports/blocked-time"]["get"]["responses"]["200"]["content"]["application/json"];
export type BlockedTopItem = BlockedTimeReport["topItems"][number];
export type BlockedGroup = BlockedTimeReport["groups"][number];
export type EpicProgressReport =
  paths["/api/v1/reports/epic-progress"]["get"]["responses"]["200"]["content"]["application/json"];
export type EpicProgressPoint = EpicProgressReport["series"][number];
export type EpicProgressRow = EpicProgressReport["rows"][number];
export type EpicProgressEpic = NonNullable<EpicProgressReport["epic"]>;

export type DataQualityReport =
  paths["/api/v1/reports/data-quality"]["get"]["responses"]["200"]["content"]["application/json"];
export type DataQualityTaskFinding = DataQualityReport["worklogCoverage"]["without"];
export type DataQualityTaskRef = DataQualityTaskFinding["items"][number];
export type DataQualityEpicList = DataQualityReport["epicDrift"];
export type DataQualityEpicRef = DataQualityEpicList["items"][number];
export type DataQualityGroup = DataQualityReport["groups"][number];
export type DataQualitySnapshotDrift = DataQualityReport["snapshotDrift"]["items"][number];
export type DataQualityLateWorklog = DataQualityReport["lateLogging"]["worst"][number];

export type CostMatrixReport =
  paths["/api/v1/reports/cost-matrix"]["get"]["responses"]["200"]["content"]["application/json"];
export type CostMatrixRow = CostMatrixReport["rows"][number];

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

export async function getTaskEstimationAccuracyReport(filter: ReportFilterState): Promise<TaskEstimationAccuracyReport> {
  return jsonRequest<TaskEstimationAccuracyReport>(`/api/v1/reports/task-estimation-accuracy?${reportQuery(filter)}`);
}

export async function getEpicEstimationAccuracyReport(filter: ReportFilterState): Promise<EpicEstimationAccuracyReport> {
  return jsonRequest<EpicEstimationAccuracyReport>(`/api/v1/reports/epic-estimation-accuracy?${reportQuery(filter)}`);
}

export async function getEstimateAdjustmentsReport(filter: ReportFilterState): Promise<EstimateAdjustmentsReport> {
  return jsonRequest<EstimateAdjustmentsReport>(`/api/v1/reports/estimate-adjustments?${reportQuery(filter)}`);
}

export async function getCycleTimeReport(filter: ReportFilterState): Promise<CycleTimeReport> {
  return jsonRequest<CycleTimeReport>(`/api/v1/reports/cycle-time?${reportQuery(filter)}`);
}

export async function getReportedTimeRatioReport(filter: ReportFilterState): Promise<ReportedTimeRatioReport> {
  return jsonRequest<ReportedTimeRatioReport>(`/api/v1/reports/reported-time-ratio?${reportQuery(filter)}`);
}

export async function getWipReport(filter: ReportFilterState): Promise<WipReport> {
  return jsonRequest<WipReport>(`/api/v1/reports/wip?${reportQuery(filter)}`);
}

export async function getBacklogReport(filter: ReportFilterState): Promise<BacklogReport> {
  return jsonRequest<BacklogReport>(`/api/v1/reports/backlog?${reportQuery(filter)}`);
}

export async function getAgingWipReport(filter: ReportFilterState): Promise<AgingWipReport> {
  return jsonRequest<AgingWipReport>(`/api/v1/reports/aging-wip?${reportQuery(filter)}`);
}

export async function getBlockedTimeReport(filter: ReportFilterState): Promise<BlockedTimeReport> {
  return jsonRequest<BlockedTimeReport>(`/api/v1/reports/blocked-time?${reportQuery(filter)}`);
}

export async function getEpicProgressReport(filter: ReportFilterState): Promise<EpicProgressReport> {
  return jsonRequest<EpicProgressReport>(`/api/v1/reports/epic-progress?${reportQuery(filter)}`);
}

export async function getDataQualityReport(filter: ReportFilterState): Promise<DataQualityReport> {
  return jsonRequest<DataQualityReport>(`/api/v1/reports/data-quality?${reportQuery(filter)}`);
}

export async function getCostMatrixReport(filter: ReportFilterState): Promise<CostMatrixReport> {
  return jsonRequest<CostMatrixReport>(`/api/v1/reports/cost-matrix?${reportQuery(filter)}`);
}
