// Metrics configuration API — the global `metrics.settings` singleton, D1's dated Jira-user
// team membership, and the site-wide Jira user directory (`.claude/docs/domain-model.md`
// "Configuration"; `authorization.md`'s metrics-settings/jira-memberships/jira-users bullets).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, timeoutSignal, voidRequest } from "./http";
import type { paths } from "./schema";

export type MetricsSettingsResponse =
  paths["/api/v1/metrics-settings"]["get"]["responses"]["200"]["content"]["application/json"];
export type MetricsSettingsRequest =
  paths["/api/v1/metrics-settings"]["put"]["requestBody"]["content"]["application/json"];

export async function getMetricsSettings(): Promise<MetricsSettingsResponse> {
  return jsonRequest<MetricsSettingsResponse>("/api/v1/metrics-settings");
}

/** A full-replace PUT (204 — the caller re-fetches to see the bumped `configRevision`). */
export async function updateMetricsSettings(body: MetricsSettingsRequest): Promise<void> {
  await voidRequest("/api/v1/metrics-settings", { method: "PUT", body: JSON.stringify(body) });
}

export type TeamMembershipListResponse =
  paths["/api/v1/teams/{id}/jira-memberships"]["get"]["responses"]["200"]["content"]["application/json"];
export type TeamMembershipResponse = TeamMembershipListResponse["items"][number];
export type TeamMembershipCreateBody =
  paths["/api/v1/teams/{id}/jira-memberships"]["post"]["requestBody"]["content"]["application/json"];
export type TeamMembershipUpdateBody =
  paths["/api/v1/teams/{id}/jira-memberships/{membershipId}"]["put"]["requestBody"]["content"]["application/json"];

export async function listTeamJiraMemberships(teamId: number): Promise<TeamMembershipListResponse> {
  return jsonRequest<TeamMembershipListResponse>(`/api/v1/teams/${teamId}/jira-memberships`);
}

export async function createTeamJiraMembership(
  teamId: number,
  body: TeamMembershipCreateBody,
): Promise<TeamMembershipResponse> {
  return jsonRequest<TeamMembershipResponse>(`/api/v1/teams/${teamId}/jira-memberships`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function updateTeamJiraMembership(
  teamId: number,
  membershipId: number,
  body: TeamMembershipUpdateBody,
): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/jira-memberships/${membershipId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export async function deleteTeamJiraMembership(teamId: number, membershipId: number): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/jira-memberships/${membershipId}`, { method: "DELETE" });
}

// Per-connection metrics configuration (`.claude/docs/metrics.md` "Per-connection metrics
// configuration") — the eight-table wholesale-replace resource `DataSourceMetricsConfig.tsx` edits.
export type DataSourceMetricsConfigResponse =
  paths["/api/v1/data-sources/{id}/metrics-config"]["get"]["responses"]["200"]["content"]["application/json"];
export type DataSourceMetricsConfigRequest =
  paths["/api/v1/data-sources/{id}/metrics-config"]["put"]["requestBody"]["content"]["application/json"];
export type DataSourceMetricsConfigOptions =
  paths["/api/v1/data-sources/{id}/metrics-config/options"]["get"]["responses"]["200"]["content"]["application/json"];
export type MetricsFieldOption = DataSourceMetricsConfigOptions["fields"][number];
export type MetricsFieldValueOption = DataSourceMetricsConfigOptions["workCategoryValues"][number];

export async function getDataSourceMetricsConfig(id: number): Promise<DataSourceMetricsConfigResponse> {
  return jsonRequest<DataSourceMetricsConfigResponse>(`/api/v1/data-sources/${id}/metrics-config`);
}

/** A full-replace PUT (204 — the caller re-fetches to see `configured` flip to true). */
export async function updateDataSourceMetricsConfig(id: number, body: DataSourceMetricsConfigRequest): Promise<void> {
  await voidRequest(`/api/v1/data-sources/${id}/metrics-config`, { method: "PUT", body: JSON.stringify(body) });
}

/** `workCategoryField` populates `workCategoryValues` from that field's own distinct observed values. */
export async function getDataSourceMetricsConfigOptions(
  id: number,
  workCategoryField?: string,
): Promise<DataSourceMetricsConfigOptions> {
  const query = buildQuery({ workCategoryField });
  const suffix = query ? `?${query}` : "";
  return jsonRequest<DataSourceMetricsConfigOptions>(`/api/v1/data-sources/${id}/metrics-config/options${suffix}`);
}

export type JiraUserPage = paths["/api/v1/jira-users"]["get"]["responses"]["200"]["content"]["application/json"];
type JiraUserScope = "UNIT" | "SITE";

type JiraUserListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  q?: string;
  /** That team's CURRENT membership only — combined with `scope` by set intersection. */
  teamId?: number;
  /** `UNIT` (default, any authenticated) or `SITE` (ADMIN only — the whole site directory). */
  scope?: JiraUserScope;
};

export async function listJiraUsers(q: JiraUserListQuery, signal?: AbortSignal): Promise<JiraUserPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, q: q.q, teamId: q.teamId, scope: q.scope });
  // A caller's signal replaces the transport's default deadline (`{ signal: timeoutSignal(), ...init }`),
  // so the two are combined where the runtime can (`AbortSignal.any`).
  const deadline = timeoutSignal();
  const combined = signal && deadline && typeof AbortSignal.any === "function" ? AbortSignal.any([signal, deadline]) : (signal ?? deadline);
  return jsonRequest<JiraUserPage>(`/api/v1/jira-users?${params}`, { signal: combined });
}

/** The server's maximum `pageSize` (API-LIST-001) and the page cap that bounds a walk of a whole directory. */
const MAX_PAGE_SIZE = 100;
const MAX_DIRECTORY_PAGES = 50;

/** A whole-directory walk: the accounts read, the server's `total`, and whether the page cap cut it short. */
export type JiraDirectory = { people: JiraUserPage["items"]; total: number; truncated: boolean };

/**
 * Every account in a directory view, paged through at the server's maximum page size until `total`
 * is reached (bounded by [MAX_DIRECTORY_PAGES], so a runaway directory cannot loop; `truncated`
 * says the cap stopped it) — for the name-resolution lookups that need EVERY person, not a search
 * result. `signal` (React Query's) aborts the in-flight page and ends the walk.
 */
export async function listAllJiraUsers(query: Pick<JiraUserListQuery, "scope">, signal?: AbortSignal): Promise<JiraDirectory> {
  const people: JiraUserPage["items"] = [];
  let total = 0;
  for (let page = 1; page <= MAX_DIRECTORY_PAGES; page += 1) {
    const result = await listJiraUsers({ page, pageSize: MAX_PAGE_SIZE, sort: "displayName", scope: query.scope }, signal);
    people.push(...result.items);
    total = result.total;
    if (result.items.length === 0 || people.length >= result.total) return { people, total, truncated: false };
  }
  return { people, total, truncated: true };
}
