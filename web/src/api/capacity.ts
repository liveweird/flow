// Capacity API (A32): a team's default MD figure and its per-sprint person x day calendars. Any
// authenticated user reads; only the team's manager or an ADMIN writes (`canEdit` says which the
// caller is — the server enforces it). Thin wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type TeamCapacity = components["schemas"]["TeamCapacityResponse"];
export type TeamCapacityBody = components["schemas"]["TeamCapacityRequest"];
export type SprintCalendarPage = components["schemas"]["SprintCalendarPage"];
export type SprintCalendarSummary = SprintCalendarPage["items"][number];
export type SprintCalendar = components["schemas"]["SprintCalendarResponse"];
export type SprintCalendarBody = paths["/api/v1/teams/{id}/sprint-calendars/{sprintId}"]["put"]["requestBody"]["content"]["application/json"];

type SprintCalendarListQuery = { page: number; pageSize: number; sort?: string };

export async function getTeamCapacity(teamId: number): Promise<TeamCapacity> {
  return jsonRequest<TeamCapacity>(`/api/v1/teams/${teamId}/capacity`);
}

export async function updateTeamCapacity(teamId: number, body: TeamCapacityBody): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/capacity`, { method: "PUT", body: JSON.stringify(body) });
}

export async function listTeamSprintCalendars(teamId: number, q: SprintCalendarListQuery): Promise<SprintCalendarPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort });
  return jsonRequest<SprintCalendarPage>(`/api/v1/teams/${teamId}/sprint-calendars?${params}`);
}

/** The one cache key of a sprint's calendar (the page reads it, the editor polls it after a save). */
export const sprintCalendarQueryKey = (teamId: number, sprintId: number) => ["capacity", "calendar", teamId, sprintId] as const;

export async function getSprintCalendar(teamId: number, sprintId: number): Promise<SprintCalendar> {
  return jsonRequest<SprintCalendar>(`/api/v1/teams/${teamId}/sprint-calendars/${sprintId}`);
}

export async function updateSprintCalendar(teamId: number, sprintId: number, body: SprintCalendarBody): Promise<void> {
  await voidRequest(`/api/v1/teams/${teamId}/sprint-calendars/${sprintId}`, { method: "PUT", body: JSON.stringify(body) });
}
