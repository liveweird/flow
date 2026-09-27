// Metrics configuration API — the global `metrics.settings` singleton, D1's dated Jira-user
// team membership, and the site-wide Jira user directory (`.claude/docs/domain-model.md`
// "Configuration"; `authorization.md`'s metrics-settings/jira-memberships/jira-users bullets).
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
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

export async function listJiraUsers(q: JiraUserListQuery): Promise<JiraUserPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, q: q.q, teamId: q.teamId, scope: q.scope });
  return jsonRequest<JiraUserPage>(`/api/v1/jira-users?${params}`);
}
