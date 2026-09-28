// Data sources API — the ADMIN-managed Jira Cloud connections (v0.2.0 plan §9). Thin endpoint
// wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The list + editor's
// commands live here since commit 10; the details/profile/inspector pages' sync-jobs
// history/status/raw-issue/profile wrappers arrive with commit 11.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type DataSourcePage = paths["/api/v1/data-sources"]["get"]["responses"]["200"]["content"]["application/json"];
export type DataSourceListItem = DataSourcePage["items"][number];
export type DataSourceResponse = paths["/api/v1/data-sources/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type DataSourceRequest = paths["/api/v1/data-sources"]["post"]["requestBody"]["content"]["application/json"];
export type JiraConnectionRequest = DataSourceRequest["jira"];
export type DataSourceState = DataSourceResponse["status"]["state"];
export type DataSourceTestRequest = paths["/api/v1/data-sources/test"]["post"]["requestBody"]["content"]["application/json"];
export type ConnectionTestResult = paths["/api/v1/data-sources/test"]["post"]["responses"]["200"]["content"]["application/json"];
export type ConnectionTestRow = ConnectionTestResult["rows"][number];
export type SyncJobRequest = paths["/api/v1/data-sources/{id}/sync-jobs"]["post"]["requestBody"]["content"]["application/json"];
export type SyncJobActionResult = paths["/api/v1/data-sources/{id}/sync-jobs"]["post"]["responses"]["202"]["content"]["application/json"];
export type SyncJobPage = paths["/api/v1/data-sources/{id}/sync-jobs"]["get"]["responses"]["200"]["content"]["application/json"];
export type SyncJobResponse = SyncJobPage["items"][number];
export type SyncJobKind = SyncJobResponse["kind"];
export type SyncJobStatus = SyncJobResponse["status"];
export type SyncStatusResponse = paths["/api/v1/data-sources/{id}/status"]["get"]["responses"]["200"]["content"]["application/json"];
export type SyncCursorSummary = SyncStatusResponse["cursors"][number];
export type SyncCounts = SyncStatusResponse["counts"];
export type RawIssueInspection = paths["/api/v1/data-sources/{id}/raw-issues/{issueKey}"]["get"]["responses"]["200"]["content"]["application/json"];
export type DataProfile = paths["/api/v1/data-sources/{id}/profile"]["get"]["responses"]["200"]["content"]["application/json"];
export type ProjectProfile = NonNullable<DataProfile["projects"]>[number];
export type WorkflowProfile = NonNullable<DataProfile["workflows"]>[number];
export type BoardProfile = NonNullable<DataProfile["boards"]>[number];
export type CustomFieldProfile = NonNullable<DataProfile["customFields"]>[number];

type DataSourceListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
};

export async function listDataSources(q: DataSourceListQuery): Promise<DataSourcePage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name });
  return jsonRequest<DataSourcePage>(`/api/v1/data-sources?${params}`);
}

export async function createDataSource(body: DataSourceRequest): Promise<DataSourceResponse> {
  return jsonRequest<DataSourceResponse>("/api/v1/data-sources", { method: "POST", body: JSON.stringify(body) });
}

export async function updateDataSource(id: number, body: DataSourceRequest): Promise<void> {
  await voidRequest(`/api/v1/data-sources/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteDataSource(id: number): Promise<void> {
  await voidRequest(`/api/v1/data-sources/${id}`, { method: "DELETE" });
}

/** The ad-hoc probe (before saving, or on edit when the token field was left blank to keep — see the stored variant). */
export async function testDataSourceAdHoc(body: DataSourceTestRequest): Promise<ConnectionTestResult> {
  return jsonRequest<ConnectionTestResult>("/api/v1/data-sources/test", { method: "POST", body: JSON.stringify(body) });
}

/** The saved variant — uses the STORED, decrypted token; no request body. */
export async function testDataSourceStored(id: number): Promise<ConnectionTestResult> {
  return jsonRequest<ConnectionTestResult>(`/api/v1/data-sources/${id}/test`, { method: "POST" });
}

/** "Sync now" — a second request while one is already open for the same (connection, kind) coalesces (`coalesced: true`, the EXISTING job). */
export async function requestSyncJob(id: number, body: SyncJobRequest): Promise<SyncJobActionResult> {
  return jsonRequest<SyncJobActionResult>(`/api/v1/data-sources/${id}/sync-jobs`, { method: "POST", body: JSON.stringify(body) });
}

type SyncJobListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  kind?: SyncJobKind;
  status?: SyncJobStatus;
};

export async function listSyncJobs(id: number, q: SyncJobListQuery): Promise<SyncJobPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, kind: q.kind, status: q.status });
  return jsonRequest<SyncJobPage>(`/api/v1/data-sources/${id}/sync-jobs?${params}`);
}

/** `PENDING` cancels immediately; `RUNNING` sets `cancelRequestedAt` — the worker honours it cooperatively. */
export async function cancelSyncJob(id: number, jobId: number): Promise<SyncJobResponse> {
  return jsonRequest<SyncJobResponse>(`/api/v1/data-sources/${id}/sync-jobs/${jobId}/cancel`, { method: "POST" });
}

/** The details page's summary: connection, cursors, raw-store counts, last jobs per kind, the current job. */
export async function getDataSourceStatus(id: number): Promise<SyncStatusResponse> {
  return jsonRequest<SyncStatusResponse>(`/api/v1/data-sources/${id}/status`);
}

/** An all-digits `issueKey` is looked up by the stable Jira issue id; a tombstoned issue is still returned, never 404. */
export async function getRawIssue(id: number, issueKey: string): Promise<RawIssueInspection> {
  return jsonRequest<RawIssueInspection>(`/api/v1/data-sources/${id}/raw-issues/${encodeURIComponent(issueKey)}`);
}

/** `computedAt` is null before the connection's first PROCESS pass — every section then carries its own empty default. */
export async function getDataSourceProfile(id: number): Promise<DataProfile> {
  return jsonRequest<DataProfile>(`/api/v1/data-sources/${id}/profile`);
}
