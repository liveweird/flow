// Data sources API — the ADMIN-managed Jira Cloud connections (v0.2.0 plan §9). Thin endpoint
// wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. Only the commands
// this commit's list + editor need travel here; the sync-jobs history/status wrappers arrive
// with the details page (plan commit 11).

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
