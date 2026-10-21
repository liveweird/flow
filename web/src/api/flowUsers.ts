// Flow users API (`/api/v1/flow-users`) — the ADMIN management surface plus the self password change.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type FlowUserPage =
  paths["/api/v1/flow-users"]["get"]["responses"]["200"]["content"]["application/json"];
export type FlowUserResponse =
  paths["/api/v1/flow-users/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
type UserCreateBody =
  paths["/api/v1/flow-users"]["post"]["requestBody"]["content"]["application/json"];
type UserUpdateBody =
  paths["/api/v1/flow-users/{id}"]["put"]["requestBody"]["content"]["application/json"];
type PasswordUpdateBody =
  paths["/api/v1/flow-users/{id}/password"]["put"]["requestBody"]["content"]["application/json"];
type FeaturesUpdateBody =
  paths["/api/v1/flow-users/{id}/features"]["put"]["requestBody"]["content"]["application/json"];
export type Feature = FeaturesUpdateBody["disabledFeatures"][number];

type UserListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
  email?: string;
  role?: string;
  /** Feature-flag state filter — the pair must travel together (the server 400s a lone half). */
  feature?: Feature;
  featureEnabled?: boolean;
};

export async function listFlowUsers(q: UserListQuery): Promise<FlowUserPage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    email: q.email,
    role: q.role,
    feature: q.feature,
    // buildQuery would keep `false` (deliberately) — but here false must be SENT too, and
    // undefined omitted, which is exactly its default behavior.
    featureEnabled: q.featureEnabled,
  });
  return jsonRequest<FlowUserPage>(`/api/v1/flow-users?${params}`);
}

export async function getFlowUser(id: number): Promise<FlowUserResponse> {
  return jsonRequest<FlowUserResponse>(`/api/v1/flow-users/${id}`);
}

export async function createFlowUser(req: UserCreateBody): Promise<FlowUserResponse> {
  return jsonRequest<FlowUserResponse>("/api/v1/flow-users", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateFlowUser(id: number, body: UserUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/flow-users/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteFlowUser(id: number): Promise<void> {
  await voidRequest(`/api/v1/flow-users/${id}`, { method: "DELETE" });
}

/** Wholesale-replace the user's disabled-feature set (ADMIN only). */
export async function updateFlowUserFeatures(id: number, disabledFeatures: Feature[]): Promise<void> {
  const body: FeaturesUpdateBody = { disabledFeatures };
  return voidRequest(`/api/v1/flow-users/${id}/features`, { method: "PUT", body: JSON.stringify(body) });
}

/** The ONE synced per-user language — the header switcher's fire-and-forget save. */
export async function setFlowUserLanguage(id: number, language: string): Promise<void> {
  await voidRequest(`/api/v1/flow-users/${id}/language`, {
    method: "PUT",
    body: JSON.stringify({ language }),
  });
}

export async function changeFlowUserPassword(id: number, body: PasswordUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/flow-users/${id}/password`, { method: "PUT", body: JSON.stringify(body) });
}
