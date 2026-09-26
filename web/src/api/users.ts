// Users API — the ADMIN management surface plus the self password change.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type UserPage =
  paths["/api/v1/users"]["get"]["responses"]["200"]["content"]["application/json"];
export type UserResponse =
  paths["/api/v1/users/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
type UserCreateBody =
  paths["/api/v1/users"]["post"]["requestBody"]["content"]["application/json"];
type UserUpdateBody =
  paths["/api/v1/users/{id}"]["put"]["requestBody"]["content"]["application/json"];
type PasswordUpdateBody =
  paths["/api/v1/users/{id}/password"]["put"]["requestBody"]["content"]["application/json"];
type FeaturesUpdateBody =
  paths["/api/v1/users/{id}/features"]["put"]["requestBody"]["content"]["application/json"];
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

export async function listUsers(q: UserListQuery): Promise<UserPage> {
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
  return jsonRequest<UserPage>(`/api/v1/users?${params}`);
}

export async function getUser(id: number): Promise<UserResponse> {
  return jsonRequest<UserResponse>(`/api/v1/users/${id}`);
}

export async function createUser(req: UserCreateBody): Promise<UserResponse> {
  return jsonRequest<UserResponse>("/api/v1/users", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function updateUser(id: number, body: UserUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/users/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteUser(id: number): Promise<void> {
  await voidRequest(`/api/v1/users/${id}`, { method: "DELETE" });
}

/** Wholesale-replace the user's disabled-feature set (ADMIN only). */
export async function updateUserFeatures(id: number, disabledFeatures: Feature[]): Promise<void> {
  const body: FeaturesUpdateBody = { disabledFeatures };
  return voidRequest(`/api/v1/users/${id}/features`, { method: "PUT", body: JSON.stringify(body) });
}

/** The ONE synced per-user language — the header switcher's fire-and-forget save. */
export async function setUserLanguage(id: number, language: string): Promise<void> {
  await voidRequest(`/api/v1/users/${id}/language`, {
    method: "PUT",
    body: JSON.stringify({ language }),
  });
}

export async function changeUserPassword(id: number, body: PasswordUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/users/${id}/password`, { method: "PUT", body: JSON.stringify(body) });
}
