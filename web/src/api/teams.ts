// Teams API — the flat teams (V6) contract ownership is scoped to: any authenticated user
// reads them (the owner pickers, the Teams page), ADMIN curates them and their rosters.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type TeamPage = paths["/api/v1/teams"]["get"]["responses"]["200"]["content"]["application/json"];
export type TeamListItem = TeamPage["items"][number];
export type TeamResponse = paths["/api/v1/teams/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type TeamMember = TeamResponse["members"][number];
export type TeamCreateBody = paths["/api/v1/teams"]["post"]["requestBody"]["content"]["application/json"];
export type TeamUpdateBody = paths["/api/v1/teams/{id}"]["put"]["requestBody"]["content"]["application/json"];

type TeamListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
  /** Only teams this user is a member of. */
  memberId?: number;
};

export async function listTeams(q: TeamListQuery): Promise<TeamPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name, memberId: q.memberId });
  return jsonRequest<TeamPage>(`/api/v1/teams?${params}`);
}

export async function getTeam(id: number): Promise<TeamResponse> {
  return jsonRequest<TeamResponse>(`/api/v1/teams/${id}`);
}

export async function createTeam(body: TeamCreateBody): Promise<TeamResponse> {
  return jsonRequest<TeamResponse>("/api/v1/teams", { method: "POST", body: JSON.stringify(body) });
}

export async function updateTeam(id: number, body: TeamUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/teams/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteTeam(id: number): Promise<void> {
  await voidRequest(`/api/v1/teams/${id}`, { method: "DELETE" });
}

export async function addTeamMember(teamId: number, userId: number): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/members/${userId}`, { method: "POST" });
}

export async function removeTeamMember(teamId: number, userId: number): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/members/${userId}`, { method: "DELETE" });
}
