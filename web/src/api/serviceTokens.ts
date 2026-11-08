// Service tokens API — the ADMIN registry of read-only credentials for other applications (V48,
// `integration/ServiceTokenRoutes.kt`). The whole surface is ADMIN-only, reads included; the secret
// travels once, in the create response. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type ServiceTokenList = paths["/api/v1/service-tokens"]["get"]["responses"]["200"]["content"]["application/json"];
export type ServiceToken = ServiceTokenList["items"][number];
export type ServiceTokenCreateBody = paths["/api/v1/service-tokens"]["post"]["requestBody"]["content"]["application/json"];
export type ServiceTokenCreated = paths["/api/v1/service-tokens"]["post"]["responses"]["201"]["content"]["application/json"];

export async function listServiceTokens(): Promise<ServiceTokenList> {
  return jsonRequest<ServiceTokenList>("/api/v1/service-tokens");
}

/** The response carries the plaintext `secret` ONCE; nothing else ever returns it. */
export async function createServiceToken(body: ServiceTokenCreateBody): Promise<ServiceTokenCreated> {
  return jsonRequest<ServiceTokenCreated>("/api/v1/service-tokens", { method: "POST", body: JSON.stringify(body) });
}

/** Terminal: a revoked token never works again (204; 409 when already revoked). */
export async function revokeServiceToken(id: number): Promise<void> {
  await voidRequest(`/api/v1/service-tokens/${id}/revoke`, { method: "POST" });
}
